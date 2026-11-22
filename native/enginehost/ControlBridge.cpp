#include "ControlBridge.h"

#include "IEngine.h"
#include "IEngineControl.h"

#ifdef Q_OS_ANDROID
#include "AndroidNotifier.h"
#endif

#include <QDir>
#include <QEventLoop>
#include <QFileInfo>
#include <QJsonArray>
#include <QLocalSocket>
#include <QTimer>
#include <QUrl>

#include <cmath>

namespace {

QString wireName(IEngineHost::ProfileState state)
{
    switch (state) {
    case IEngineHost::ProfileState::starting:
        return QStringLiteral("starting");
    case IEngineHost::ProfileState::loaded:
        return QStringLiteral("loaded");
    case IEngineHost::ProfileState::closing:
        return QStringLiteral("closing");
    case IEngineHost::ProfileState::restoring:
        return QStringLiteral("restoring");
    }
    return {};
}

} // namespace

ControlBridge::ControlBridge(QObject* parent)
: SocketBridge(parent)
{
    mChangeTimer.setSingleShot(true);
    mChangeTimer.setInterval(changeDelayMs);
    connect(&mChangeTimer, &QTimer::timeout, this, [this] {
        sendOpenProfiles(true);
    });
    mNotificationTimer.setSingleShot(true);
    mNotificationTimer.setInterval(0);
    connect(&mNotificationTimer, &QTimer::timeout, this, &ControlBridge::showProfiles);
}

void ControlBridge::setEngineControl(IEngineControl* control)
{
    mEngineControl = control;
    if (mFront < 0) {
        setEngine(control->defaultSession());
    }
}

bool ControlBridge::listen(const QString& path)
{
    mSocketDir = QFileInfo(path).absolutePath();
    // A restore can open a profile before this
    for (const OpenProfile& open : std::as_const(mOpen)) {
        listenFor(open.bridge);
    }
    // While a restore waits, the notification keeps saying the game is kept, until the restore opens it
    if (!mOpen.isEmpty() || !mEngineControl || !mEngineControl->restorePending()) {
        mNotificationTimer.start();
    }
    return SocketBridge::listen(path);
}

void ControlBridge::listenFor(SocketBridge* bridge) const
{
    if (!mSocketDir.isEmpty() && bridge->socketPath().isEmpty()) {
        bridge->listen(QDir(mSocketDir).filePath(QStringLiteral("engine-p%1.sock").arg(bridge->slot())));
    }
}

void ControlBridge::greet(const QJsonObject& hello)
{
    mProfileOutput = !hello.value(QLatin1String("profileSockets")).toBool();
    mOpensUrls = hello.value(QLatin1String("opensUrls")).toBool();
    mScriptSpeechToScreenReader = false;
    SocketBridge::greet(hello);
}

void ControlBridge::sendState()
{
    if (mProfileOutput) {
        SocketBridge::sendState();
    } else {
        for (auto it = mEngineStatus.cbegin(); it != mEngineStatus.cend(); ++it) {
            sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), it.key()}, {QStringLiteral("value"), it.value()}});
        }
        sendFrame(openProfilesMessage());
    }
    // Android stops the app behind the system's picker, so the UI that comes back may be a new one
    if (mPick && !mPick->path) {
        sendFrame(pickFileMessage());
    }
}

void ControlBridge::engineStatus(const QString& key, const QString& value)
{
    mEngineStatus.insert(key, value);
    mStatus.insert(key, value);
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), key}, {QStringLiteral("value"), value}});
    }
}

bool ControlBridge::handleControl(const QString& type, const QJsonObject& message)
{
    if (!mEngineControl) {
        return false;
    }
    if (type == QLatin1String("listProfiles")) {
        sendProfiles();
    } else if (type == QLatin1String("saveProfile")) {
        answerProfiles(type, saveProfile(message));
    } else if (type == QLatin1String("deleteProfile")) {
        answerProfiles(type, mEngineControl->deleteProfile(message.value(QLatin1String("name")).toString()));
    } else if (type == QLatin1String("openProfile")) {
        const QString problem = openProfile(message);
        if (!problem.isEmpty()) {
            sendError(QStringLiteral("openProfile: %1").arg(problem));
        } else if (mProfileOutput) {
            // As in version 13, for a UI that does not know of the open profiles
            sendProfiles();
        } else {
            sendReply(openProfilesMessage());
        }
    } else if (type == QLatin1String("closeProfile")) {
        const QString problem = mEngineControl->closeProfile(message.value(QLatin1String("name")).toString());
        if (problem.isEmpty()) {
            sendReply(openProfilesMessage());
        } else {
            sendError(QStringLiteral("closeProfile: %1").arg(problem));
        }
    } else if (type == QLatin1String("setFront")) {
        const QString name = message.value(QLatin1String("name")).toString();
        const qsizetype index = openIndex(name);
        if (index < 0) {
            sendError(QStringLiteral("setFront: \"%1\" is not open").arg(name));
        } else {
            setFront(index);
            sendOpenProfiles(true);
        }
    } else if (type == QLatin1String("markSeen")) {
        markSeen(message);
    } else if (type == QLatin1String("setAppState")) {
        setAppState(message);
    } else if (type == QLatin1String("setScriptSpeech")) {
        setScriptSpeech(message);
    } else if (type == QLatin1String("answerClipboardRead") || type == QLatin1String("answerOpenUrl")) {
        // One that came after its script stopped waiting
    } else if (type == QLatin1String("answerPickFile")) {
        // A second answer, as from a UI asked again, or one for a pick that ended, is ignored
        if (mPick && !mPick->path && message.value(QLatin1String("id")) == QJsonValue(double(mPick->id))) {
            mPick->path = message.value(QLatin1String("path")).toString();
            if (mPickLoop) {
                mPickLoop->quit();
            }
        }
    } else if (!mProfileOutput && type == QLatin1String("setScreenSize")) {
        if (const std::optional<QSize> size = screenSizeOf(message)) {
            mEngineControl->setStartScreenSize(size->width(), size->height());
        }
    } else if (!mProfileOutput && type == QLatin1String("setMainWindowSize")) {
        if (const std::optional<QSize> size = mainWindowSizeOf(message)) {
            mEngineControl->setStartMainWindowSize(size->width(), size->height());
        } else {
            refuse(type, mainWindowSizeProblem());
        }
    } else if (!mProfileOutput && type == QLatin1String("setMainWindowDevice")) {
        if (const std::optional<MainWindowDevice> device = mainWindowDeviceOf(message)) {
            mEngineControl->setStartMainWindowDevice(*device);
        } else {
            refuse(type, mainWindowDeviceProblem());
        }
    } else if (!mProfileOutput) {
        sendError(QStringLiteral("%1: send it on the profile's socket").arg(type));
    } else {
        return false;
    }
    return true;
}

// Android lets only the app in front read the clipboard, so the UI reads it. The script waits in its call, as for any
// clipboard read, but at most clipboardWaitMs, and not at all with no UI: a frozen UI or none reads as an empty clipboard.
QString ControlBridge::clipboardText()
{
    if (!mClient || !mClientGreeted) {
        return {};
    }
    const QJsonValue id(double(++mClipboardReads));
    sendFrame({{QStringLiteral("t"), QStringLiteral("clipboardRead")}, {QStringLiteral("id"), id}});
    // Written now, as the event loop that would write it does not run while the script waits
    mClient->flush();
    const std::optional<QJsonObject> answer = takeFrame(QLatin1StringView("answerClipboardRead"), id, clipboardWaitMs);
    if (!answer) {
        qWarning("ControlBridge: the UI did not answer clipboardRead %lld within %d ms", mClipboardReads, clipboardWaitMs);
        return {};
    }
    return answer->value(QLatin1String("text")).toString();
}

// With no UI the text goes nowhere, as the app is not in front to put it on the clipboard
void ControlBridge::setClipboardText(const QString& text)
{
    if (mClient && mClientGreeted) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("clipboardWrite")}, {QStringLiteral("text"), text}});
    }
}

// Android gives an app no path to a folder outside its own storage, so a folder is never asked for. A pick waits, as
// the desktop's modal dialog does, running the event loop, until the UI answers or the engine quits, which ends it.
QString ControlBridge::pickFile(bool folder, const QString& title, const QString& location)
{
    if (folder || mPick || !mClient || !mClientGreeted) {
        return {};
    }
    mPick = FilePick{++mPickIds, title, location, std::nullopt};
    sendFrame(pickFileMessage());
    QEventLoop loop;
    mPickLoop = &loop;
    // Qt emits no readyRead inside a readyRead slot, and a script's runLua is handled in one: without reading here the
    // answer, and every other request, would wait for the pick to end
    QTimer reader;
    connect(&reader, &QTimer::timeout, this, [this] {
        const QJsonValue outerRequest = mCurrentRequestId;
        onReadyRead();
        mCurrentRequestId = outerRequest;
    });
    reader.start(pickReadMs);
    loop.exec();
    mPickLoop = nullptr;
    const QString path = mPick->path.value_or(QString());
    mPick.reset();
    return path;
}

// Only the schemes a browser or mail app takes: a file: URL, a content: or intent: one, or a game's own scheme, is
// never handed to the app. The script gets the app's answer, false when it did not open the page, as the desktop's
// QDesktopServices::openUrl() returns; the URL is the script's, so it is never logged.
bool ControlBridge::openWebPage(const QUrl& url)
{
    const QString scheme = url.scheme().toLower();
    if (scheme != QLatin1String("http") && scheme != QLatin1String("https") && scheme != QLatin1String("mailto")) {
        return false;
    }
    if (!mClient || !mClientGreeted || !mOpensUrls) {
        return false;
    }
    const QJsonValue id(double(++mUrlOpens));
    sendFrame({{QStringLiteral("t"), QStringLiteral("openUrl")}, {QStringLiteral("id"), id}, {QStringLiteral("url"), url.toString(QUrl::FullyEncoded)}});
    // Written now, as the event loop that would write it does not run while the script waits
    mClient->flush();
    const std::optional<QJsonObject> answer = takeFrame(QLatin1StringView("answerOpenUrl"), id, openUrlWaitMs);
    if (!answer) {
        qWarning("ControlBridge: the UI did not answer openUrl %lld within %d ms", mUrlOpens, openUrlWaitMs);
        return false;
    }
    return answer->value(QLatin1String("opened")).toBool();
}

// Only while a UI is attached, so on screen: a live region of an app no one sees is not read, and the engine's text to
// speech says it instead. The text is the script's, so it is never logged.
bool ControlBridge::speakForScripts(const QString& text)
{
    if (!mClient || !mClientGreeted || !mScriptSpeechToScreenReader) {
        return false;
    }
    sendFrame({{QStringLiteral("t"), QStringLiteral("speak")}, {QStringLiteral("text"), text}});
    return true;
}

QJsonObject ControlBridge::pickFileMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("pickFile")}, {QStringLiteral("id"), double(mPick->id)}, {QStringLiteral("title"), mPick->title}, {QStringLiteral("into"), mPick->into}};
}

// Checked here rather than taken with defaults, as a profile stored with a wrong field would only fail later, at its connect
QString ControlBridge::saveProfile(const QJsonObject& message)
{
    const QJsonValue host = message.value(QLatin1String("host"));
    const QJsonValue port = message.value(QLatin1String("port"));
    const QJsonValue tls = message.value(QLatin1String("tls"));
    const QJsonValue login = message.value(QLatin1String("login"));
    const QJsonValue autoReconnect = message.value(QLatin1String("autoReconnect"));
    if (!host.isString() || host.toString().trimmed().isEmpty()) {
        return QStringLiteral("a profile needs a host");
    }
    const double portNumber = port.toDouble(-1);
    if (!port.isDouble() || portNumber != std::floor(portNumber) || portNumber < 1 || portNumber > 65535) {
        return QStringLiteral("port must be a whole number from 1 to 65535");
    }
    if (!tls.isBool() || !autoReconnect.isBool()) {
        return QStringLiteral("tls and autoReconnect must be true or false");
    }
    if (!login.isString()) {
        return QStringLiteral("login must be a string");
    }
    return mEngineControl->saveProfile({message.value(QLatin1String("name")).toString(), host.toString().trimmed(), int(portNumber), tls.toBool(), login.toString().trimmed(), autoReconnect.toBool()});
}

QString ControlBridge::openProfile(const QJsonObject& message)
{
    const QJsonValue password = message.value(QLatin1String("password"));
    const QJsonValue proxyPassword = message.value(QLatin1String("proxyPassword"));
    const QJsonValue connectNow = message.value(QLatin1String("connect"));
    const QJsonValue alongside = message.value(QLatin1String("alongside"));
    if (!password.isUndefined() && !password.isNull() && !password.isString()) {
        return QStringLiteral("password must be a string");
    }
    if (!proxyPassword.isUndefined() && !proxyPassword.isNull() && !proxyPassword.isString()) {
        return QStringLiteral("proxyPassword must be a string");
    }
    if (!connectNow.isUndefined() && !connectNow.isBool()) {
        return QStringLiteral("connect must be true or false");
    }
    if (!alongside.isUndefined() && !alongside.isBool()) {
        return QStringLiteral("alongside must be true or false");
    }
    const QString name = message.value(QLatin1String("name")).toString();
    if (alongside.toBool() && mOpen.size() >= maxOpenProfiles && openIndex(name) < 0) {
        return QStringLiteral("at most %1 open").arg(maxOpenProfiles);
    }
    return mEngineControl->openProfile(name, {password.toString(), proxyPassword.toString()}, connectNow.toBool(true), alongside.toBool());
}

void ControlBridge::setAppState(const QJsonObject& message)
{
    const QJsonValue active = message.value(QLatin1String("active"));
    const QJsonValue minimized = message.value(QLatin1String("minimized"));
    if (!active.isBool() || !minimized.isBool()) {
        sendError(QStringLiteral("setAppState: active and minimized must be true or false"));
        return;
    }
    mEngineControl->setAppState(active.toBool(), minimized.toBool());
}

void ControlBridge::setScriptSpeech(const QJsonObject& message)
{
    const QJsonValue screenReader = message.value(QLatin1String("screenReader"));
    if (!screenReader.isBool()) {
        sendError(QStringLiteral("setScriptSpeech: screenReader must be true or false"));
        return;
    }
    mScriptSpeechToScreenReader = screenReader.toBool();
}

void ControlBridge::markSeen(const QJsonObject& message)
{
    const QString name = message.value(QLatin1String("name")).toString();
    const QJsonValue seq = message.value(QLatin1String("seq"));
    const qsizetype index = openIndex(name);
    if (index < 0) {
        sendError(QStringLiteral("markSeen: \"%1\" is not open").arg(name));
        return;
    }
    if (!seq.isDouble() || seq.toDouble() < 0 || seq.toDouble() != std::floor(seq.toDouble())) {
        sendError(QStringLiteral("markSeen: seq must be a whole number from 0"));
        return;
    }
    OpenProfile& open = mOpen[index];
    open.seenSeq = std::clamp(qint64(seq.toDouble()), open.seenSeq, open.bridge->lastSeq());
    open.attention = false;
    sendOpenProfiles(true);
}

void ControlBridge::answerProfiles(const QString& request, const QString& problem)
{
    if (problem.isEmpty()) {
        sendProfiles();
    } else {
        sendError(QStringLiteral("%1: %2").arg(request, problem));
    }
}

void ControlBridge::sendProfiles()
{
    QJsonArray list;
    for (const Profile& profile : mEngineControl->profiles()) {
        list.append(QJsonObject{{QStringLiteral("name"), profile.name},
                                {QStringLiteral("host"), profile.host},
                                {QStringLiteral("port"), profile.port},
                                {QStringLiteral("tls"), profile.tls},
                                {QStringLiteral("login"), profile.login},
                                {QStringLiteral("autoReconnect"), profile.autoReconnect}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("profiles")}, {QStringLiteral("profiles"), list}});
}

qsizetype ControlBridge::openIndex(const QString& name) const
{
    for (qsizetype i = 0; i < mOpen.size(); ++i) {
        if (mOpen.at(i).bridge->profileName().compare(name, Qt::CaseInsensitive) == 0) {
            return i;
        }
    }
    return -1;
}

// What a UI that left out profileSockets sees and asks of from then on
void ControlBridge::setFront(qsizetype index)
{
    if (mFront >= 0 && mFront < mOpen.size()) {
        mOpen.at(mFront).bridge->setMirror(nullptr);
    }
    mFront = index;
    if (index < 0) {
        setEngine(mEngineControl ? mEngineControl->defaultSession() : nullptr);
        return;
    }
    SocketBridge* front = mOpen.at(index).bridge;
    // The new front's line ids may name other lines of the old one's still kept here
    forgetLineIds();
    front->setMirror(this);
    setEngine(front->engine());
    if (mEngineControl) {
        mEngineControl->setFront(front->profileName());
    }
    // So the connection and the rest are the new front's; sent without re, as no request asked for them
    const QHash<QString, QString>& statuses = front->statuses();
    for (auto it = statuses.cbegin(); it != statuses.cend(); ++it) {
        if (mStatus.value(it.key()) == it.value()) {
            continue;
        }
        mStatus.insert(it.key(), it.value());
        if (outputLive()) {
            sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), it.key()}, {QStringLiteral("value"), it.value()}});
        }
    }
}

IFrontend* ControlBridge::profileOpened(const QString& name, IEngine* session, ProfileState state, bool passwordGiven)
{
    int slot = 1;
    while (std::any_of(mOpen.cbegin(), mOpen.cend(), [slot](const OpenProfile& open) {
        return open.bridge->slot() == slot;
    })) {
        ++slot;
    }
    auto* bridge = new SocketBridge(this, this, name, slot);
    bridge->setEngine(session);
    listenFor(bridge);
    mOpen.append({bridge, state, passwordGiven});
    // A restore brings the front profile back first, and the others behind it
    if (state != ProfileState::restoring || mFront < 0) {
        setFront(mOpen.size() - 1);
    }
    sendOpenProfiles(true);
    mNotificationTimer.start();
    return bridge;
}

void ControlBridge::profileState(const QString& name, ProfileState state)
{
    const qsizetype index = openIndex(name);
    if (index >= 0 && mOpen.at(index).state != state) {
        mOpen[index].state = state;
        sendOpenProfiles(true);
    }
}

void ControlBridge::profilePasswordGiven(const QString& name, bool given)
{
    const qsizetype index = openIndex(name);
    if (index >= 0 && mOpen.at(index).passwordGiven != given) {
        mOpen[index].passwordGiven = given;
        sendOpenProfiles(true);
    }
}

// A UI that misses it, lagging or detached, asks for the list as it attaches
void ControlBridge::profilesChanged()
{
    if (clientLive() && mEngineControl) {
        sendProfiles();
    }
}

// The front goes to the next profile, else the one before
void ControlBridge::profileClosed(const QString& name)
{
    const qsizetype index = openIndex(name);
    if (index < 0) {
        return;
    }
    SocketBridge* bridge = mOpen.at(index).bridge;
    const bool wasFront = index == mFront;
    if (wasFront) {
        // Not setFront(-1), which would start the engine's default session while others stay open
        bridge->setMirror(nullptr);
        mFront = -1;
    }
    mOpen.removeAt(index);
    if (wasFront) {
        setFront(index < mOpen.size() ? index : mOpen.size() - 1);
    } else if (mFront > index) {
        --mFront;
    }
    bridge->close();
    bridge->deleteLater();
    sendOpenProfiles(true);
    mNotificationTimer.start();
}

void ControlBridge::profileChanged(SocketBridge* profile, Change change)
{
    const auto open = std::find_if(mOpen.begin(), mOpen.end(), [profile](const OpenProfile& o) {
        return o.bridge == profile;
    });
    if (open == mOpen.end()) {
        return;
    }
    switch (change) {
    case Change::lines:
        sendOpenProfiles(false);
        return;
    case Change::connection:
        sendOpenProfiles(true);
        mNotificationTimer.start();
        return;
    case Change::attention:
        if (!std::exchange(open->attention, true)) {
            sendOpenProfiles(true);
        }
        return;
    }
}

void ControlBridge::sendOpenProfiles(bool now)
{
    if (!clientLive() || mProfileOutput) {
        mChangeTimer.stop();
        return;
    }
    if (!now) {
        if (!mChangeTimer.isActive()) {
            mChangeTimer.start();
        }
        return;
    }
    mChangeTimer.stop();
    sendFrame(openProfilesMessage());
}

QJsonObject ControlBridge::openProfilesMessage() const
{
    QJsonArray profiles;
    for (qsizetype i = 0; i < mOpen.size(); ++i) {
        const OpenProfile& open = mOpen.at(i);
        profiles.append(QJsonObject{{QStringLiteral("name"), open.bridge->profileName()},
                                    {QStringLiteral("slot"), open.bridge->slot()},
                                    {QStringLiteral("socket"), open.bridge->socketPath()},
                                    {QStringLiteral("state"), wireName(open.state)},
                                    {QStringLiteral("passwordGiven"), open.passwordGiven},
                                    {QStringLiteral("connection"), open.bridge->connection()},
                                    {QStringLiteral("lastSeq"), open.bridge->lastSeq()},
                                    {QStringLiteral("unread"), open.bridge->lastSeq() - open.seenSeq},
                                    {QStringLiteral("attention"), open.attention},
                                    {QStringLiteral("front"), i == mFront}});
    }
    return {{QStringLiteral("t"), QStringLiteral("openProfiles")}, {QStringLiteral("profiles"), profiles}};
}

void ControlBridge::showProfiles()
{
    std::pair<QStringList, QStringList> shown;
    for (const OpenProfile& open : std::as_const(mOpen)) {
        shown.first.append(open.bridge->profileName());
        shown.second.append(open.bridge->connection());
    }
    if (mShown == shown) {
        return;
    }
    mShown = shown;
#ifdef Q_OS_ANDROID
    AndroidNotifier::showProfiles(shown.first, shown.second);
#endif
}
