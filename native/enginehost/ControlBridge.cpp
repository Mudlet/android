#include "ControlBridge.h"

#include "IEngine.h"
#include "IEngineControl.h"

#ifdef Q_OS_ANDROID
#include "AndroidNotifier.h"
#endif

#include <QDir>
#include <QFileInfo>
#include <QJsonArray>
#include <QLocalSocket>

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
    SocketBridge::greet(hello);
}

void ControlBridge::sendState()
{
    if (mProfileOutput) {
        SocketBridge::sendState();
        return;
    }
    for (auto it = mEngineStatus.cbegin(); it != mEngineStatus.cend(); ++it) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), it.key()}, {QStringLiteral("value"), it.value()}});
    }
    sendFrame(openProfilesMessage());
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
    } else if (!mProfileOutput) {
        sendError(QStringLiteral("%1: send it on the profile's socket").arg(type));
    } else {
        return false;
    }
    return true;
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
    const QJsonValue connectNow = message.value(QLatin1String("connect"));
    const QJsonValue alongside = message.value(QLatin1String("alongside"));
    if (!password.isUndefined() && !password.isNull() && !password.isString()) {
        return QStringLiteral("password must be a string");
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
    return mEngineControl->openProfile(name, password.toString(), connectNow.toBool(true), alongside.toBool());
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
    front->setMirror(this);
    setEngine(front->engine());
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

IFrontend* ControlBridge::profileOpened(const QString& name, IEngine* session, ProfileState state)
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
    mOpen.append({bridge, state});
    setFront(mOpen.size() - 1);
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
        // Not setFront(-1), which would start the stand-in's default session while others stay open
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
