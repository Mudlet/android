#include "SocketBridge.h"

#include "ControlBridge.h"
#include "IEngine.h"
#include "LineJson.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDeadlineTimer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLocalServer>
#include <QLocalSocket>
#include <QtEndian>

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>
#include <variant>

#include <sys/socket.h>
#include <unistd.h>

#ifdef Q_OS_ANDROID
#include "AndroidNotifier.h"
#endif

namespace {

// A JSON number that is a whole number an int holds, from least
std::optional<int> wholeNumber(const QJsonValue& value, int least = -std::numeric_limits<int>::max())
{
    const double number = value.toDouble();
    if (!value.isDouble() || number != std::floor(number) || number < least || number > std::numeric_limits<int>::max()) {
        return std::nullopt;
    }
    return int(number);
}

// The console a link request names: empty for the main console, which an absent window, or main as in Mudlet, names;
// nullopt for a window that is not a string
std::optional<QString> linkWindow(const QJsonObject& message)
{
    const QJsonValue value = message.value(QLatin1String("window"));
    if (value.isUndefined()) {
        return QString();
    }
    if (!value.isString()) {
        return std::nullopt;
    }
    const QString window = value.toString();
    return window == QLatin1String("main") ? QString() : window;
}

// The fields of a pointer request, or with map of a mapPointer one; else why they are wrong
std::variant<PointerEvent, QString> pointerEvent(const QJsonObject& message, bool map)
{
    static constexpr std::pair<QLatin1StringView, PointerEvent::Type> types[] = {
            {QLatin1StringView("press"), PointerEvent::Type::press},
            {QLatin1StringView("release"), PointerEvent::Type::release},
            {QLatin1StringView("doubleClick"), PointerEvent::Type::doubleClick},
            {QLatin1StringView("move"), PointerEvent::Type::move},
            {QLatin1StringView("enter"), PointerEvent::Type::enter},
            {QLatin1StringView("leave"), PointerEvent::Type::leave},
            {QLatin1StringView("wheel"), PointerEvent::Type::wheel},
    };
    PointerEvent event;
    const QString type = message.value(QLatin1String("type")).toString();
    const auto known = std::find_if(std::begin(types), std::end(types), [&type](const auto& entry) {
        return entry.first == type;
    });
    // The map takes a press, a double-click and a wheel turn: its drags are the GUI's alone
    const bool takenByMap = known != std::end(types) && (known->second == PointerEvent::Type::press || known->second == PointerEvent::Type::doubleClick || known->second == PointerEvent::Type::wheel);
    if (map && !takenByMap) {
        return QStringLiteral("type must be press, doubleClick or wheel");
    }
    if (known == std::end(types)) {
        return QStringLiteral("type must be press, release, doubleClick, move, enter, leave or wheel");
    }
    event.type = known->second;
    const QJsonValue x = message.value(QLatin1String("x"));
    const QJsonValue y = message.value(QLatin1String("y"));
    if (!x.isDouble() || !y.isDouble()) {
        return QStringLiteral("x and y must be numbers");
    }
    event.position = QPointF(x.toDouble(), y.toDouble());
    // Absent is 0; the buttons and modifiers are Qt's flags, so never negative. The map has no buttons held.
    const std::pair<QJsonValue, int*> optional[] = {
            {message.value(QLatin1String("button")), &event.button},
            {map ? QJsonValue(QJsonValue::Undefined) : message.value(QLatin1String("buttons")), &event.buttons},
            {message.value(QLatin1String("modifiers")), &event.modifiers},
            {message.value(QLatin1String("wheelY")), &event.wheelY},
    };
    for (const auto& [value, target] : optional) {
        if (value.isUndefined()) {
            continue;
        }
        const std::optional<int> number = wholeNumber(value);
        if (!number || (target != &event.wheelY && *number < 0)) {
            return map ? QStringLiteral("button and modifiers must be whole numbers from 0, wheelY a whole number")
                       : QStringLiteral("button, buttons and modifiers must be whole numbers from 0, wheelY a whole number");
        }
        *target = *number;
    }
    return event;
}

// nullopt for a map that is neither app nor mapper
std::optional<IEngine::MapTarget> mapTarget(const QJsonObject& message)
{
    const QString map = message.value(QLatin1String("map")).toString();
    if (map == QLatin1String("app")) {
        return IEngine::MapTarget::app;
    }
    if (map == QLatin1String("mapper")) {
        return IEngine::MapTarget::mapper;
    }
    return std::nullopt;
}

QString wireName(RoomMatch::Match match)
{
    switch (match) {
    case RoomMatch::Match::exact:
        return QStringLiteral("exact");
    case RoomMatch::Match::prefix:
        return QStringLiteral("prefix");
    case RoomMatch::Match::contains:
        return QStringLiteral("contains");
    case RoomMatch::Match::id:
        return QStringLiteral("id");
    }
    return QStringLiteral("contains");
}

QString wireName(MapDownload::State state)
{
    switch (state) {
    case MapDownload::State::none:
        return QStringLiteral("none");
    case MapDownload::State::downloading:
        return QStringLiteral("downloading");
    case MapDownload::State::loaded:
        return QStringLiteral("loaded");
    case MapDownload::State::failed:
        return QStringLiteral("failed");
    case MapDownload::State::cancelled:
        return QStringLiteral("cancelled");
    }
    return {};
}

QString wireName(PackageInstallResult result)
{
    switch (result) {
    case PackageInstallResult::installed:
        return QStringLiteral("installed");
    case PackageInstallResult::installedWithErrors:
        return QStringLiteral("installedWithErrors");
    case PackageInstallResult::alreadyInstalled:
        return QStringLiteral("alreadyInstalled");
    case PackageInstallResult::failed:
        return QStringLiteral("failed");
    }
    return {};
}

QString wireName(MapFile::Operation operation)
{
    switch (operation) {
    case MapFile::Operation::load:
        return QStringLiteral("load");
    case MapFile::Operation::save:
        return QStringLiteral("save");
    }
    return {};
}

QString wireName(MapFile::State state)
{
    switch (state) {
    case MapFile::State::running:
        return QStringLiteral("running");
    case MapFile::State::done:
        return QStringLiteral("done");
    case MapFile::State::failed:
        return QStringLiteral("failed");
    case MapFile::State::cancelled:
        return QStringLiteral("cancelled");
    }
    return {};
}

constexpr std::pair<QLatin1StringView, IEngine::ScriptItemKind> scriptItemKinds[]{{QLatin1StringView("trigger"), IEngine::ScriptItemKind::trigger},
                                                                                  {QLatin1StringView("alias"), IEngine::ScriptItemKind::alias},
                                                                                  {QLatin1StringView("script"), IEngine::ScriptItemKind::script},
                                                                                  {QLatin1StringView("timer"), IEngine::ScriptItemKind::timer},
                                                                                  {QLatin1StringView("key"), IEngine::ScriptItemKind::key},
                                                                                  {QLatin1StringView("button"), IEngine::ScriptItemKind::button}};

std::optional<IEngine::ScriptItemKind> scriptItemKind(const QJsonObject& message)
{
    const QString kind = message.value(QLatin1String("kind")).toString();
    for (const auto& [name, value] : scriptItemKinds) {
        if (kind == name) {
            return value;
        }
    }
    return std::nullopt;
}

QString wireName(IEngine::ScriptItemKind kind)
{
    for (const auto& [name, value] : scriptItemKinds) {
        if (kind == value) {
            return name;
        }
    }
    return {};
}

QString wireName(IEngine::ScriptSearchField field)
{
    switch (field) {
    case IEngine::ScriptSearchField::name:
        return QStringLiteral("name");
    case IEngine::ScriptSearchField::command:
        return QStringLiteral("command");
    case IEngine::ScriptSearchField::commandDown:
        return QStringLiteral("commandDown");
    case IEngine::ScriptSearchField::commandUp:
        return QStringLiteral("commandUp");
    case IEngine::ScriptSearchField::pattern:
        return QStringLiteral("pattern");
    case IEngine::ScriptSearchField::event:
        return QStringLiteral("event");
    case IEngine::ScriptSearchField::css:
        return QStringLiteral("css");
    case IEngine::ScriptSearchField::lua:
        return QStringLiteral("lua");
    }
    return {};
}

// The most matches a scriptSearch carries, and the most of a line it keeps either side of a match: Lua kept on one line,
// as minified code is, would otherwise fill a frame many times over
constexpr int maxSearchMatches = 500;
constexpr int searchContext = 80;

// text cut to searchContext either side of the length characters matched at start, with an ellipsis where it is cut,
// and where the match starts in what is left
std::pair<QString, int> searchSnippet(const QString& text, int start, int length)
{
    qsizetype from = std::max(0, start - searchContext);
    qsizetype to = std::min(text.size(), qsizetype(start) + length + searchContext);
    // A surrogate pair cut in two would be no character
    if (from > 0 && text.at(from).isLowSurrogate()) {
        --from;
    }
    if (to < text.size() && text.at(to).isLowSurrogate()) {
        ++to;
    }
    QString snippet = text.mid(from, to - from);
    int moved = start - int(from);
    if (from > 0) {
        snippet.prepend(QChar(0x2026));
        ++moved;
    }
    if (to < text.size()) {
        snippet.append(QChar(0x2026));
    }
    return {snippet, moved};
}

// The most variables a variables frame carries, and the most of a value: a table of thousands of rooms, or a string a
// script read a whole file into, would otherwise fill a frame many times over
constexpr int maxVariablesPerFrame = 500;
constexpr int maxVariableValueChars = 1000;

constexpr std::pair<QLatin1StringView, IEngine::VariableType> variableTypes[]{{QLatin1StringView("boolean"), IEngine::VariableType::boolean},
                                                                              {QLatin1StringView("number"), IEngine::VariableType::number},
                                                                              {QLatin1StringView("string"), IEngine::VariableType::string},
                                                                              {QLatin1StringView("table"), IEngine::VariableType::table},
                                                                              {QLatin1StringView("function"), IEngine::VariableType::function},
                                                                              {QLatin1StringView("other"), IEngine::VariableType::other}};

QString wireName(IEngine::VariableType type)
{
    for (const auto& [name, value] : variableTypes) {
        if (type == value) {
            return name;
        }
    }
    return {};
}

std::optional<IEngine::VariableType> variableType(const QJsonValue& value)
{
    for (const auto& [name, type] : variableTypes) {
        if (value.toString() == name) {
            return type;
        }
    }
    return std::nullopt;
}

constexpr QLatin1StringView scriptItemKindProblem("kind must be trigger, alias, script, timer, key or button");

// The kind and item a script item request names, or why it names none
std::variant<std::pair<IEngine::ScriptItemKind, int>, QString> scriptItemTarget(const QJsonObject& message)
{
    const std::optional<IEngine::ScriptItemKind> kind = scriptItemKind(message);
    if (!kind) {
        return QString(scriptItemKindProblem);
    }
    const std::optional<int> item = wholeNumber(message.value(QLatin1String("item")), 1);
    if (!item) {
        return QStringLiteral("item must be a whole number from 1");
    }
    return std::pair{*kind, *item};
}

// The messages of those of now that are new or differ from what they were before, matched by name
template <typename Info, typename Message>
QList<QJsonObject> changedByName(const QList<Info>& before, const QList<Info>& now, Message message)
{
    QHash<QString, const Info*> was;
    for (const Info& info : before) {
        was.insert(info.name, &info);
    }
    QList<QJsonObject> changed;
    for (const Info& info : now) {
        if (const Info* old = was.value(info.name); !old || *old != info) {
            changed.append(message(info));
        }
    }
    return changed;
}

} // namespace

namespace {

// Each bridge's own, so that a UI that kept another's sequence numbers, as of a slot's earlier profile, replays from
// scratch
QString newInstance(int slot)
{
    static qint64 last = 0;
    last = std::max(last + 1, QDateTime::currentMSecsSinceEpoch());
    const QString instance = QStringLiteral("%1-%2").arg(QCoreApplication::applicationPid()).arg(last);
    return slot == 0 ? instance : QStringLiteral("%1-%2").arg(instance).arg(slot);
}

} // namespace

SocketBridge::SocketBridge(QObject* parent, ControlBridge* control, const QString& profile, int slot)
: QObject(parent)
, mControl(control)
, mProfileName(profile)
, mSlot(slot)
, mServer(new QLocalServer(this))
, mInstance(newInstance(slot))
{
    connect(mServer, &QLocalServer::newConnection, this, &SocketBridge::onNewConnection);
}

bool SocketBridge::listen(const QString& path)
{
    // A killed engine leaves its socket file behind
    QLocalServer::removeServer(path);
    mServer->setSocketOptions(QLocalServer::UserAccessOption);
    if (!mServer->listen(path)) {
        qWarning("SocketBridge: listen(%s) failed: %s", qPrintable(path), qPrintable(mServer->errorString()));
        return false;
    }
    return true;
}

void SocketBridge::close()
{
    if (mClient) {
        QLocalSocket* socket = mClient;
        socket->disconnect(this);
        connect(socket, &QLocalSocket::disconnected, socket, &QObject::deleteLater);
        mClient = nullptr;
        mClientGreeted = false;
        // Graceful, unlike abort(), so the profile's last frames, as why it closed, are delivered first
        socket->disconnectFromServer();
        forgetClient();
    }
    const QString path = mServer->fullServerName();
    mServer->close();
    QLocalServer::removeServer(path);
}

QString SocketBridge::latestText() const
{
    const auto latest = std::find_if(mScrollback.crbegin(), mScrollback.crend(), [](const StoredLine& line) {
        return !line.prompt && !line.deleted && !line.text.trimmed().isEmpty();
    });
    return latest == mScrollback.crend() ? QString() : latest->text;
}

QString SocketBridge::socketPath() const
{
    return mServer->fullServerName();
}

void SocketBridge::onNewConnection()
{
    while (QLocalSocket* socket = mServer->nextPendingConnection()) {
        ucred cred{};
        socklen_t len = sizeof(cred);
        if (getsockopt(int(socket->socketDescriptor()), SOL_SOCKET, SO_PEERCRED, &cred, &len) != 0 || cred.uid != getuid()) {
            qWarning("SocketBridge: rejecting peer uid %u", unsigned(cred.uid));
            socket->abort();
            socket->deleteLater();
            continue;
        }
        if (mClient) {
            dropClient(QStringLiteral("replaced by a new connection"));
        }
        mClient = socket;
        mClientGreeted = false;
        mClientLagging = false;
        mReadBuffer.clear();
        connect(socket, &QLocalSocket::readyRead, this, &SocketBridge::onReadyRead);
        connect(socket, &QLocalSocket::bytesWritten, this, &SocketBridge::onBytesWritten);
        connect(socket, &QLocalSocket::disconnected, this, [this, socket] {
            if (mClient == socket) {
                mClient = nullptr;
                mClientGreeted = false;
                forgetClient();
            }
            deleteSocket(socket);
        });
    }
}

// A script's file pick runs an event loop inside a read, and Qt delivers a deferred delete posted in that loop before
// the loop ends: the socket whose readyRead the read is in would be freed under Qt, which uses it once the read returns
void SocketBridge::deleteSocket(QLocalSocket* socket)
{
    if (mReading > 0) {
        mRetired.append(socket);
    } else {
        socket->deleteLater();
    }
}

void SocketBridge::dropClient(const QString& reason)
{
    if (!mClient) {
        return;
    }
    qInfo("SocketBridge: dropping client: %s", qPrintable(reason));
    mClient->disconnect(this);
    mClient->abort();
    deleteSocket(mClient);
    mClient = nullptr;
    mClientGreeted = false;
    forgetClient();
}

// The app's map is drawn only for the UI that asked for it, so one that goes stops it, and the next is told nothing of
// it until it asks: a UI that died cannot take back its size, and none is drawn while none is attached. The mapper's
// map goes back to the window's own pixels, as the next UI may show it at another size, or not ask
void SocketBridge::forgetClient()
{
    if (mEngine && mProfileOutput) {
        mEngine->setAppMapSize(QSize(0, 0), 1);
        mEngine->setMapScale(1, 1);
    }
    mAppMap = {};
}

void SocketBridge::onReadyRead()
{
    // The frames read by takeFrame() wait for the request or script that took one to end
    if (!mClient || mTakingFrame) {
        return;
    }
    ++mReading;
    readFrames();
    if (--mReading == 0) {
        for (const QPointer<QLocalSocket>& socket : std::exchange(mRetired, {})) {
            if (socket) {
                socket->deleteLater();
            }
        }
    }
}

void SocketBridge::readFrames()
{
    mReadBuffer.append(mClient->readAll());
    while (mReadBuffer.size() >= 4) {
        const quint32 length = qFromBigEndian<quint32>(mReadBuffer.constData());
        if (length > maxFrameBytes) {
            dropClient(QStringLiteral("frame too large"));
            return;
        }
        if (mReadBuffer.size() < qsizetype(4 + length)) {
            return;
        }
        QJsonParseError error{};
        const QJsonDocument doc = QJsonDocument::fromJson(mReadBuffer.mid(4, length), &error);
        mReadBuffer.remove(0, 4 + length);
        if (!doc.isObject()) {
            dropClient(QStringLiteral("bad frame: %1").arg(error.errorString()));
            return;
        }
        handleMessage(doc.object());
        if (!mClient) {
            return;
        }
    }
}

bool SocketBridge::mapFileCancelAsked()
{
    // Mudlet holds the main thread for the whole load or save, so the socket is read here, at each step of its progress
    return takeFrame(QLatin1StringView("cancelMapFile"), QJsonValue(QJsonValue::Undefined), 0).has_value();
}

std::optional<QJsonObject> SocketBridge::takeFrame(QLatin1StringView type, const QJsonValue& id, int waitMs)
{
    // The complete frames before at are not it
    qsizetype at = 0;
    const auto scan = [&]() -> std::optional<QJsonObject> {
        while (mReadBuffer.size() - at >= 4) {
            const quint32 length = qFromBigEndian<quint32>(mReadBuffer.constData() + at);
            if (length > maxFrameBytes || mReadBuffer.size() - at < qsizetype(4 + length)) {
                return std::nullopt;
            }
            const QJsonObject frame = QJsonDocument::fromJson(mReadBuffer.mid(at + 4, length)).object();
            if (frame.value(QLatin1String("t")).toString() == type && (id.isUndefined() || frame.value(QLatin1String("id")) == id)) {
                mReadBuffer.remove(at, 4 + length);
                return frame;
            }
            at += 4 + length;
        }
        return std::nullopt;
    };
    const QDeadlineTimer deadline(waitMs);
    std::optional<QJsonObject> taken = scan();
    while (mClient && !taken) {
        mTakingFrame = true;
        const bool read = mClient->waitForReadyRead(int(deadline.remainingTime()));
        mTakingFrame = false;
        if (!mClient) {
            break;
        }
        mReadBuffer.append(mClient->readAll());
        taken = scan();
        if (!read || deadline.hasExpired()) {
            break;
        }
    }
    // Outside a request's handling, as in a timer's script, no readyRead would come for frames already read
    if (mClient && !mReadBuffer.isEmpty() && !mReadQueued) {
        mReadQueued = true;
        QMetaObject::invokeMethod(
                this,
                [this] {
                    mReadQueued = false;
                    onReadyRead();
                },
                Qt::QueuedConnection);
    }
    return taken;
}

void SocketBridge::onBytesWritten()
{
    if (!mClient || !mClientLagging || mClient->bytesToWrite() > lowWaterBytes) {
        return;
    }
    mClientLagging = false;
    sendState();
    if (!mProfileOutput) {
        return;
    }
    const QSet<qint64> owed = std::exchange(mUpdatesOwed, {});
    for (const StoredLine& stored : mScrollback) {
        if (owed.contains(stored.seq)) {
            sendFrame(lineUpdatedMessage(stored));
        }
    }
    sendLinesAfter(mLastSentSeq, false);
    if (!mClientLagging) {
        sendFrame(tailMessage());
    }
}

void SocketBridge::greet(const QJsonObject& hello)
{
    mClientGreeted = true;
    // Its text boxes' typing is counted from its hello, as the UI counts it
    mTextBoxTyped.clear();
    // A UI that was only detached says what it already has, so it gets just the rest
    qint64 after = 0;
    if (hello.value(QLatin1String("instance")).toString() == mInstance) {
        after = std::clamp(qint64(hello.value(QLatin1String("lastSeq")).toDouble()), qint64(0), mNextSeq - 1);
    }
    mLastSentSeq = after;
    QJsonObject reply{{QStringLiteral("t"), QStringLiteral("hello")},
                      {QStringLiteral("v"), protocolVersion},
                      {QStringLiteral("pid"), qint64(QCoreApplication::applicationPid())},
                      {QStringLiteral("instance"), mInstance}};
    if (!mProfileName.isEmpty()) {
        reply.insert(QStringLiteral("profile"), mProfileName);
    }
    sendFrame(reply);
    // The lines first: the UI reads frames in order, and a profile's labels and windows can take it longer to read than
    // its lines
    if (mProfileOutput) {
        sendLinesAfter(after, true);
        sendLineUpdatesUpTo(after);
        sendFrame(tailMessage());
    }
    sendState();
    sendFrame({{QStringLiteral("t"), QStringLiteral("synced")}, {QStringLiteral("seq"), mNextSeq - 1}});
}

void SocketBridge::sendState()
{
    for (auto it = mStatus.cbegin(); it != mStatus.cend(); ++it) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), it.key()}, {QStringLiteral("value"), it.value()}});
    }
    sendFrame(hiddenInputMessage());
    sendFrame(secureOfferMessage());
    sendFrame(scriptsStoppedMessage());
    sendFrame(replayRecordingMessage());
    sendFrame(replayMessage());
    sendFrame(certificateMessage());
    if (mEngine) {
        sendFrame(historyMessage(mEngine->history()));
        sendFrame(errorConsoleMessage(mEngine->errorConsole(), true));
    }
    if (std::exchange(mLinksMissed, false)) {
        sendFrame(linksChangedMessage({}));
    }
    if (mMainArea) {
        sendFrame(mainAreaMessage(*mMainArea));
    }
    QJsonArray windows;
    for (const ScriptWindowInfo& window : std::as_const(mWindows)) {
        windows.append(windowJson(window));
    }
    sendFrame({{QStringLiteral("t"), QStringLiteral("windows")}, {QStringLiteral("full"), true}, {QStringLiteral("windows"), windows}});
    for (const LabelInfo& label : std::as_const(mLabels)) {
        sendFrame(labelMessage(label));
    }
    for (const TextBoxInfo& textBox : std::as_const(mTextBoxes)) {
        sendFrame(textBoxMessage(textBox));
    }
    if (!mMap.message.isEmpty()) {
        sendFrame(mMap.message);
    }
    if (!mAppMap.message.isEmpty()) {
        sendFrame(mAppMap.message);
    }
    if (!mMapDownload.isEmpty()) {
        sendFrame(mMapDownload);
    }
    if (!mSounds.isEmpty()) {
        sendFrame(mSounds);
    }
    sendFrame(mButtonBars);
    sendFrame(mCommands);
    sendFrame(mKeyBindings);
    if (!mEngine) {
        return;
    }
    for (const ScriptWindowInfo& window : std::as_const(mWindows)) {
        if (!window.console) {
            continue;
        }
        if (const std::optional<WindowText> text = mEngine->windowText(window.name, -WindowText::recentRows, WindowText::recentRows)) {
            sendFrame(windowTextMessage(window.name, *text));
        }
    }
}

void SocketBridge::sendLinesAfter(qint64 seq, bool replay)
{
    // Lines from before a clear are gone, not skipped
    if (seq <= mClearedAfterSeq) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("clear")}});
        seq = mClearedAfterSeq;
    }
    const qint64 oldest = mScrollback.empty() ? mNextSeq : mScrollback.front().seq;
    if (seq + 1 < oldest) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("gap")}, {QStringLiteral("skipped"), oldest - seq - 1}});
    }
    for (const StoredLine& stored : mScrollback) {
        if (stored.seq <= seq) {
            continue;
        }
        if (!stored.deleted) {
            sendFrame(lineMessage(stored, replay));
        }
        mLastSentSeq = stored.seq;
        if (mClientLagging) {
            return;
        }
    }
}

void SocketBridge::sendLineUpdatesUpTo(qint64 seq)
{
    mUpdatesOwed.clear();
    for (const StoredLine& stored : mScrollback) {
        if (stored.seq > seq) {
            break;
        }
        if (stored.edited) {
            sendFrame(lineUpdatedMessage(stored));
        }
    }
}

void SocketBridge::handleMessage(const QJsonObject& message)
{
    const QString type = message.value(QLatin1String("t")).toString();
    if (!mClientGreeted) {
        if (type != QLatin1String("hello") || message.value(QLatin1String("v")).toInt() != protocolVersion) {
            QLocalSocket* socket = mClient;
            sendError(QStringLiteral("expected hello with v=%1").arg(protocolVersion));
            socket->disconnect(this);
            connect(socket, &QLocalSocket::disconnected, socket, &QObject::deleteLater);
            mClient = nullptr;
            // Graceful, unlike abort(), so the error frame is delivered first
            socket->disconnectFromServer();
            return;
        }
        greet(message);
        return;
    }

    mCurrentRequestId = message.value(QLatin1String("id"));
    if (type == QLatin1String("ping")) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("pong")}, {QStringLiteral("id"), mCurrentRequestId}});
    } else if (handleControl(type, message) || !mEngine) {
        // Answered, or there is no profile to take it
    } else if (type == QLatin1String("connect")) {
        const QJsonValue tls = message.value(QLatin1String("tls"));
        if (!tls.isUndefined() && !tls.isBool()) {
            // Not taken as absent, which could connect in the clear a game the UI meant to encrypt
            sendError(QStringLiteral("connect: tls must be true or false"));
        } else {
            mEngine->connectTo(
                    message.value(QLatin1String("host")).toString(), quint16(message.value(QLatin1String("port")).toInt(23)), tls.isBool() ? std::optional<bool>(tls.toBool()) : std::nullopt);
        }
    } else if (type == QLatin1String("send")) {
        mEngine->send(message.value(QLatin1String("text")).toString());
    } else if (type == QLatin1String("sendHidden")) {
        mEngine->sendHidden(message.value(QLatin1String("text")).toString());
    } else if (type == QLatin1String("dismissHidden")) {
        mEngine->dismissHidden();
    } else if (type == QLatin1String("complete")) {
        complete(message);
    } else if (type == QLatin1String("disconnect")) {
        mEngine->disconnectFromGame();
    } else if (type == QLatin1String("runLua")) {
        mEngine->runLua(message.value(QLatin1String("code")).toString());
    } else if (type == QLatin1String("setScreenSize")) {
        setScreenSize(message);
    } else if (type == QLatin1String("getLink")) {
        getLink(message);
    } else if (type == QLatin1String("followLink")) {
        followLink(message);
    } else if (type == QLatin1String("setMainWindowSize")) {
        refuse(type, setMainWindowSize(message));
    } else if (type == QLatin1String("setMainWindowDevice")) {
        refuse(type, setMainWindowDevice(message));
    } else if (type == QLatin1String("getWindowText")) {
        getWindowText(message);
    } else if (type == QLatin1String("placeUserWindow")) {
        refuse(type, placeUserWindow(message));
    } else if (type == QLatin1String("closeUserWindow")) {
        refuse(type, mEngine->closeUserWindow(message.value(QLatin1String("name")).toString()));
    } else if (type == QLatin1String("sendWindowCommandLine")) {
        refuse(type, mEngine->sendWindowCommandLine(message.value(QLatin1String("name")).toString(), message.value(QLatin1String("text")).toString()));
    } else if (type == QLatin1String("setTextBoxText")) {
        setTextBoxText(message);
    } else if (type == QLatin1String("selectFrameTab")) {
        refuse(type, mEngine->selectFrameTab(message.value(QLatin1String("name")).toString()));
    } else if (type == QLatin1String("followLabelLink")) {
        refuse(type, mEngine->followLabelLink(message.value(QLatin1String("label")).toString(), message.value(QLatin1String("href")).toString()));
    } else if (type == QLatin1String("pointer")) {
        refuse(type, pointer(message));
    } else if (type == QLatin1String("getImage")) {
        getImage(message);
    } else if (type == QLatin1String("pressButton")) {
        const std::optional<int> button = wholeNumber(message.value(QLatin1String("button")));
        refuse(type, button ? mEngine->pressButton(*button) : QStringLiteral("button must be a whole number"));
    } else if (type == QLatin1String("activateCommand")) {
        const std::optional<int> command = wholeNumber(message.value(QLatin1String("command")));
        refuse(type, command ? mEngine->activateCommand(*command) : QStringLiteral("command must be a whole number"));
    } else if (type == QLatin1String("mapPointer")) {
        refuse(type, mapPointer(message));
    } else if (type == QLatin1String("setMapViewOnly")) {
        const QJsonValue viewOnly = message.value(QLatin1String("viewOnly"));
        refuse(type, viewOnly.isBool() ? mEngine->setMapViewOnly(viewOnly.toBool()) : QStringLiteral("viewOnly must be true or false"));
    } else if (type == QLatin1String("setMapScale")) {
        refuse(type, setMapScale(message));
    } else if (type == QLatin1String("setAppMapSize")) {
        refuse(type, setAppMapSize(message));
    } else if (type == QLatin1String("appMapPointer")) {
        std::variant<PointerEvent, QString> event = pointerEvent(message, true);
        const QString* problem = std::get_if<QString>(&event);
        refuse(type, problem ? *problem : mEngine->appMapPointer(std::get<PointerEvent>(event)));
    } else if (type == QLatin1String("findRooms")) {
        findRooms(message);
    } else if (type == QLatin1String("showRoom")) {
        const std::optional<IEngine::MapTarget> map = mapTarget(message);
        const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
        refuse(type, !map ? QStringLiteral("map must be app or mapper") : room ? mEngine->showRoom(*map, *room) : QStringLiteral("room must be a whole number"));
    } else if (type == QLatin1String("moveMap")) {
        refuse(type, moveMap(message));
    } else if (type == QLatin1String("followPlayer")) {
        const std::optional<IEngine::MapTarget> map = mapTarget(message);
        refuse(type, map ? mEngine->followPlayer(*map) : QStringLiteral("map must be app or mapper"));
    } else if (type == QLatin1String("showMapLevel")) {
        const std::optional<IEngine::MapTarget> map = mapTarget(message);
        const std::optional<int> level = wholeNumber(message.value(QLatin1String("level")));
        refuse(type, !map ? QStringLiteral("map must be app or mapper") : level ? mEngine->showMapLevel(*map, *level) : QStringLiteral("level must be a whole number"));
    } else if (type == QLatin1String("showMapArea")) {
        const std::optional<IEngine::MapTarget> map = mapTarget(message);
        const std::optional<int> area = wholeNumber(message.value(QLatin1String("area")));
        refuse(type, !map ? QStringLiteral("map must be app or mapper") : area ? mEngine->showMapArea(*map, *area) : QStringLiteral("area must be a whole number"));
    } else if (type == QLatin1String("walkToRoom")) {
        const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
        refuse(type, room ? mEngine->walkToRoom(*room) : QStringLiteral("room must be a whole number"));
    } else if (type == QLatin1String("roomInfo")) {
        roomInfo(message);
    } else if (type == QLatin1String("mapAreas")) {
        mapAreas();
    } else if (type == QLatin1String("editRoom")) {
        editRoom(message);
    } else if (type == QLatin1String("setRoomExit")) {
        setRoomExit(message);
    } else if (type == QLatin1String("lockRoomExit")) {
        lockRoomExit(message);
    } else if (type == QLatin1String("addRoom")) {
        addRoom(message);
    } else if (type == QLatin1String("deleteRoom")) {
        const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
        refuse(type, room ? mEngine->deleteRoom(*room) : QStringLiteral("room must be a whole number"));
    } else if (type == QLatin1String("setPlayerRoom")) {
        const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
        refuse(type, room ? mEngine->setPlayerRoom(*room) : QStringLiteral("room must be a whole number"));
    } else if (type == QLatin1String("renameArea")) {
        refuse(type, renameArea(message));
    } else if (type == QLatin1String("mapEnvs")) {
        mapEnvs();
    } else if (type == QLatin1String("downloadMap")) {
        refuse(type, mEngine->downloadMap());
    } else if (type == QLatin1String("cancelMapDownload")) {
        refuse(type, mEngine->cancelMapDownload());
    } else if (type == QLatin1String("answerSecureOffer")) {
        const QJsonValue accept = message.value(QLatin1String("accept"));
        refuse(type, accept.isBool() ? mEngine->answerSecureOffer(accept.toBool()) : QStringLiteral("accept must be true or false"));
    } else if (type == QLatin1String("deleteMap")) {
        refuse(type, mEngine->deleteMap());
    } else if (type == QLatin1String("setScriptsStopped")) {
        const QJsonValue stopped = message.value(QLatin1String("stopped"));
        refuse(type, stopped.isBool() ? mEngine->setScriptsStopped(stopped.toBool()) : QStringLiteral("stopped must be true or false"));
    } else if (type == QLatin1String("setTimeStamps")) {
        const QJsonValue shown = message.value(QLatin1String("shown"));
        refuse(type, shown.isBool() ? mEngine->setTimeStamps(shown.toBool()) : QStringLiteral("shown must be true or false"));
    } else if (type == QLatin1String("setReplayRecording")) {
        const QJsonValue record = message.value(QLatin1String("record"));
        refuse(type, record.isBool() ? mEngine->setReplayRecording(record.toBool()) : QStringLiteral("record must be true or false"));
    } else if (type == QLatin1String("playReplay")) {
        const QString path = message.value(QLatin1String("path")).toString();
        refuse(type, path.isEmpty() ? QStringLiteral("path must be a file's path") : mEngine->playReplay(path));
    } else if (type == QLatin1String("setReplayPaused")) {
        const QJsonValue paused = message.value(QLatin1String("paused"));
        refuse(type, paused.isBool() ? mEngine->setReplayPaused(paused.toBool()) : QStringLiteral("paused must be true or false"));
    } else if (type == QLatin1String("stopReplay")) {
        refuse(type, mEngine->stopReplay());
    } else if (type == QLatin1String("changeReplaySpeed")) {
        const QJsonValue faster = message.value(QLatin1String("faster"));
        refuse(type, faster.isBool() ? mEngine->changeReplaySpeed(faster.toBool()) : QStringLiteral("faster must be true or false"));
    } else if (type == QLatin1String("loadMapFile") || type == QLatin1String("saveMapFile")) {
        const QString path = message.value(QLatin1String("path")).toString();
        const auto cancelAsked = [this] {
            return mapFileCancelAsked();
        };
        refuse(type, type == QLatin1String("loadMapFile") ? mEngine->loadMapFile(path, cancelAsked) : mEngine->saveMapFile(path, cancelAsked));
    } else if (type == QLatin1String("cancelMapFile")) {
        // One that came while a load or save ran was taken by mapFileCancelAsked()
        refuse(type, QStringLiteral("no map file is being loaded or saved that can be cancelled"));
    } else if (type == QLatin1String("scriptItems")) {
        scriptItems(message);
    } else if (type == QLatin1String("scriptItem")) {
        scriptItem(message);
    } else if (type == QLatin1String("addScriptItem")) {
        addScriptItem(message);
    } else if (type == QLatin1String("editScriptItem")) {
        editScriptItem(message);
    } else if (type == QLatin1String("enableScriptItem")) {
        enableScriptItem(message);
    } else if (type == QLatin1String("moveScriptItem")) {
        refuse(type, moveScriptItem(message));
    } else if (type == QLatin1String("deleteScriptItem")) {
        refuse(type, deleteScriptItem(message));
    } else if (type == QLatin1String("packages")) {
        packages();
    } else if (type == QLatin1String("installPackage")) {
        const QString path = message.value(QLatin1String("path")).toString();
        refuse(type, path.isEmpty() ? QStringLiteral("path must not be empty") : mEngine->installPackage(path));
    } else if (type == QLatin1String("uninstallPackage")) {
        uninstallPackage(message);
    } else if (type == QLatin1String("searchScriptItems")) {
        searchScriptItems(message);
    } else if (type == QLatin1String("variables")) {
        variables(message);
    } else if (type == QLatin1String("checkLua")) {
        checkLua(message);
    } else if (type == QLatin1String("clearErrorConsole")) {
        mEngine->clearErrorConsole();
    } else if (type == QLatin1String("pressKey")) {
        pressKey(message);
    } else if (type == QLatin1String("getProxy")) {
        sendProxy(type);
    } else if (type == QLatin1String("setProxy")) {
        setProxy(message);
    } else if (type == QLatin1String("getSettings")) {
        getSettings(message);
    } else if (type == QLatin1String("setSettings")) {
        setSettings(message);
    } else {
        sendError(QStringLiteral("unknown type %1").arg(type));
    }
    mCurrentRequestId = QJsonValue();
}

bool SocketBridge::handleControl(const QString& type, const QJsonObject&)
{
    static constexpr QLatin1StringView controlRequests[] = {QLatin1StringView("listProfiles"),
                                                            QLatin1StringView("saveProfile"),
                                                            QLatin1StringView("deleteProfile"),
                                                            QLatin1StringView("openProfile"),
                                                            QLatin1StringView("closeProfile"),
                                                            QLatin1StringView("setFront"),
                                                            QLatin1StringView("markSeen"),
                                                            QLatin1StringView("setAppState"),
                                                            QLatin1StringView("setScriptSpeech"),
                                                            QLatin1StringView("answerClipboardRead"),
                                                            QLatin1StringView("answerPickFile"),
                                                            QLatin1StringView("answerOpenUrl")};
    if (std::find(std::begin(controlRequests), std::end(controlRequests), type) == std::end(controlRequests)) {
        return false;
    }
    sendError(QStringLiteral("%1: send it on the control socket").arg(type));
    return true;
}

void SocketBridge::complete(const QJsonObject& message)
{
    const QJsonValue fromHistory = message.value(QLatin1String("fromHistory"));
    if (!fromHistory.isUndefined() && !fromHistory.isBool()) {
        sendError(QStringLiteral("complete: fromHistory must be true or false"));
        return;
    }
    status(QStringLiteral("completion"), mEngine->complete(message.value(QLatin1String("text")).toString(), fromHistory.toBool(false)));
}

void SocketBridge::setScreenSize(const QJsonObject& message)
{
    if (const std::optional<QSize> size = screenSizeOf(message)) {
        mEngine->setScreenSize(size->width(), size->height());
    }
}

std::optional<QSize> SocketBridge::screenSizeOf(const QJsonObject& message)
{
    const double columns = message.value(QLatin1String("columns")).toDouble(-1);
    const double rows = message.value(QLatin1String("rows")).toDouble(-1);
    // NAWS carries each in 16 bits
    const auto fits = [](double n) {
        return n == std::floor(n) && n >= 1 && n <= 65535;
    };
    if (!fits(columns) || !fits(rows)) {
        sendError(QStringLiteral("setScreenSize: columns and rows must be whole numbers from 1 to 65535"));
        return std::nullopt;
    }
    return QSize(int(columns), int(rows));
}

void SocketBridge::getLink(const QJsonObject& message)
{
    const std::optional<int> id = wholeNumber(message.value(QLatin1String("link")), 1);
    const std::optional<QString> window = linkWindow(message);
    if (!id) {
        sendError(QStringLiteral("getLink: link must be a whole number from 1"));
        return;
    }
    if (!window) {
        sendError(QStringLiteral("getLink: window must be a console's name"));
        return;
    }
    Link link;
    if (const QString problem = mEngine->link(*window, *id, link); !problem.isEmpty()) {
        sendError(QStringLiteral("getLink: %1").arg(problem));
        return;
    }
    QJsonArray items;
    for (const Link::Item& item : link.items) {
        items.append(QJsonObject{{QStringLiteral("label"), item.label}, {QStringLiteral("enabled"), item.enabled}, {QStringLiteral("separator"), item.separator}});
    }
    QJsonObject reply{{QStringLiteral("t"), QStringLiteral("link")},
                      {QStringLiteral("link"), *id},
                      {QStringLiteral("tooltip"), link.tooltip},
                      {QStringLiteral("menu"), link.menu},
                      {QStringLiteral("menuTitle"), link.menuTitle},
                      {QStringLiteral("items"), items},
                      {QStringLiteral("spoiler"), link.spoiler},
                      {QStringLiteral("revealed"), link.revealed},
                      {QStringLiteral("disabled"), link.disabled},
                      {QStringLiteral("concealed"), link.concealed},
                      {QStringLiteral("visited"), link.visited},
                      {QStringLiteral("selected"), link.selected}};
    if (link.fg) {
        reply.insert(QStringLiteral("fg"), *link.fg);
    }
    if (link.bg) {
        reply.insert(QStringLiteral("bg"), *link.bg);
    }
    if (link.styles) {
        reply.insert(QStringLiteral("styles"), *link.styles);
    }
    if (!window->isEmpty()) {
        reply.insert(QStringLiteral("window"), *window);
    }
    sendReply(reply);
}

void SocketBridge::followLink(const QJsonObject& message)
{
    const std::optional<int> id = wholeNumber(message.value(QLatin1String("link")), 1);
    const QJsonValue itemValue = message.value(QLatin1String("item"));
    const std::optional<int> item = wholeNumber(itemValue, 0);
    const std::optional<QString> window = linkWindow(message);
    if (!id) {
        sendError(QStringLiteral("followLink: link must be a whole number from 1"));
        return;
    }
    if (!itemValue.isUndefined() && !item) {
        sendError(QStringLiteral("followLink: item must be a whole number from 0"));
        return;
    }
    if (!window) {
        sendError(QStringLiteral("followLink: window must be a console's name"));
        return;
    }
    if (const QString problem = mEngine->followLink(*window, *id, item); !problem.isEmpty()) {
        sendError(QStringLiteral("followLink: %1").arg(problem));
    }
}

QString SocketBridge::setMainWindowSize(const QJsonObject& message)
{
    const std::optional<QSize> size = mainWindowSizeOf(message);
    if (!size) {
        return mainWindowSizeProblem();
    }
    return mEngine->setMainWindowSize(size->width(), size->height());
}

std::optional<QSize> SocketBridge::mainWindowSizeOf(const QJsonObject& message)
{
    const std::optional<int> width = wholeNumber(message.value(QLatin1String("w")), 1);
    const std::optional<int> height = wholeNumber(message.value(QLatin1String("h")), 1);
    if (!width || !height) {
        return std::nullopt;
    }
    return QSize(*width, *height);
}

QString SocketBridge::setMainWindowDevice(const QJsonObject& message)
{
    const std::optional<MainWindowDevice> device = mainWindowDeviceOf(message);
    if (!device) {
        return mainWindowDeviceProblem();
    }
    return mEngine->setMainWindowDevice(*device);
}

std::optional<MainWindowDevice> SocketBridge::mainWindowDeviceOf(const QJsonObject& message)
{
    const QJsonValue pointer = message.value(QLatin1String("pointer"));
    const QJsonValue hover = message.value(QLatin1String("hover"));
    const QJsonValue ratio = message.value(QLatin1String("dpr"));
    const QJsonValue scale = message.value(QLatin1String("pixelScale"));
    const auto positive = [](const QJsonValue& value) {
        return value.isDouble() && value.toDouble() > 0;
    };
    const bool coarse = pointer == QJsonValue(QStringLiteral("coarse"));
    if ((!coarse && pointer != QJsonValue(QStringLiteral("fine"))) || !hover.isBool() || !positive(ratio) || !positive(scale)) {
        return std::nullopt;
    }
    return MainWindowDevice{coarse, hover.toBool(), ratio.toDouble(), scale.toDouble()};
}

void SocketBridge::getWindowText(const QJsonObject& message)
{
    const QString window = message.value(QLatin1String("window")).toString();
    const std::optional<int> first = wholeNumber(message.value(QLatin1String("first")));
    const std::optional<int> count = wholeNumber(message.value(QLatin1String("count")));
    if (!first || !count || *count < 1) {
        sendError(QStringLiteral("getWindowText: first and count must be whole numbers, count above 0"));
        return;
    }
    // An empty name would be the main console's in Mudlet
    const std::optional<WindowText> text = window.isEmpty() ? std::nullopt : mEngine->windowText(window, *first, std::min(*count, maxWindowTextRows));
    if (!text) {
        sendError(QStringLiteral("getWindowText: there is no console named \"%1\"").arg(window));
        return;
    }
    sendReply(windowTextMessage(window, *text));
}

QString SocketBridge::placeUserWindow(const QJsonObject& message)
{
    const std::optional<int> x = wholeNumber(message.value(QLatin1String("x")));
    const std::optional<int> y = wholeNumber(message.value(QLatin1String("y")));
    const std::optional<int> width = wholeNumber(message.value(QLatin1String("w")));
    const std::optional<int> height = wholeNumber(message.value(QLatin1String("h")));
    const QJsonValue floating = message.value(QLatin1String("floating"));
    if (!x || !y || !width || !height || *width < 1 || *height < 1) {
        return QStringLiteral("x, y, w and h must be whole numbers, w and h above 0");
    }
    if (!floating.isBool()) {
        return QStringLiteral("floating must be true or false");
    }
    return mEngine->placeUserWindow(message.value(QLatin1String("name")).toString(), QRect(*x, *y, *width, *height), floating.toBool());
}

QString SocketBridge::pointer(const QJsonObject& message)
{
    std::variant<PointerEvent, QString> event = pointerEvent(message, false);
    if (const QString* problem = std::get_if<QString>(&event)) {
        return *problem;
    }
    return mEngine->pointer(message.value(QLatin1String("window")).toString(), std::get<PointerEvent>(event));
}

QString SocketBridge::mapPointer(const QJsonObject& message)
{
    std::variant<PointerEvent, QString> event = pointerEvent(message, true);
    if (const QString* problem = std::get_if<QString>(&event)) {
        return *problem;
    }
    return mEngine->mapPointer(std::get<PointerEvent>(event));
}

QString SocketBridge::moveMap(const QJsonObject& message)
{
    const std::optional<IEngine::MapTarget> map = mapTarget(message);
    if (!map) {
        return QStringLiteral("map must be app or mapper");
    }
    const QJsonValue numbers[] = {
            message.value(QLatin1String("dx")), message.value(QLatin1String("dy")), message.value(QLatin1String("zoom")), message.value(QLatin1String("x")), message.value(QLatin1String("y"))};
    if (!std::all_of(std::begin(numbers), std::end(numbers), [](const QJsonValue& value) {
            return value.isDouble();
        })) {
        return QStringLiteral("dx, dy, zoom, x and y must be numbers");
    }
    if (!(numbers[2].toDouble() > 0)) {
        return QStringLiteral("zoom must be above 0");
    }
    const std::optional<int> seq = wholeNumber(message.value(QLatin1String("seq")), 1);
    if (!seq) {
        return QStringLiteral("seq must be a whole number from 1");
    }
    return mEngine->moveMap(*map, QPointF(numbers[0].toDouble(), numbers[1].toDouble()), numbers[2].toDouble(), QPointF(numbers[3].toDouble(), numbers[4].toDouble()), *seq);
}

QString SocketBridge::setMapScale(const QJsonObject& message)
{
    const QJsonValue scale = message.value(QLatin1String("scale"));
    if (!scale.isDouble() || scale.toDouble() < 1 || scale.toDouble() > IEngine::maxMapScale) {
        return QStringLiteral("scale must be a number from 1 to %1").arg(IEngine::maxMapScale);
    }
    const QJsonValue density = message.value(QLatin1String("density"));
    if (density.isUndefined()) {
        return mEngine->setMapScale(scale.toDouble(), scale.toDouble());
    }
    if (!density.isDouble() || density.toDouble() < 1 || density.toDouble() > IEngine::maxAppMapScale) {
        return QStringLiteral("density must be a number from 1 to %1").arg(IEngine::maxAppMapScale);
    }
    return mEngine->setMapScale(scale.toDouble(), density.toDouble());
}

QString SocketBridge::setAppMapSize(const QJsonObject& message)
{
    const std::optional<int> width = wholeNumber(message.value(QLatin1String("w")));
    const std::optional<int> height = wholeNumber(message.value(QLatin1String("h")));
    const QJsonValue scale = message.value(QLatin1String("scale"));
    if (!width || !height || *width < 0 || *height < 0 || (*width == 0) != (*height == 0)) {
        return QStringLiteral("w and h must be whole numbers above 0, or both 0");
    }
    if (!scale.isDouble() || scale.toDouble() < 1 || scale.toDouble() > IEngine::maxAppMapScale) {
        return QStringLiteral("scale must be a number from 1 to %1").arg(IEngine::maxAppMapScale);
    }
    return mEngine->setAppMapSize(QSize(*width, *height), scale.toDouble());
}

void SocketBridge::findRooms(const QJsonObject& message)
{
    const QString text = message.value(QLatin1String("text")).toString();
    if (text.trimmed().isEmpty()) {
        sendError(QStringLiteral("findRooms: text must not be empty"));
        return;
    }
    const std::variant<RoomList, QString> found = mEngine->findRooms(text);
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("findRooms: %1").arg(*problem));
        return;
    }
    const RoomList& list = std::get<RoomList>(found);
    QJsonArray rooms;
    for (const RoomMatch& room : list.rooms) {
        rooms.append(QJsonObject{{QStringLiteral("id"), room.id},
                                 {QStringLiteral("name"), room.name},
                                 {QStringLiteral("areaId"), room.areaId},
                                 {QStringLiteral("area"), room.area},
                                 {QStringLiteral("match"), wireName(room.match)},
                                 {QStringLiteral("steps"), room.steps}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("rooms")}, {QStringLiteral("text"), text}, {QStringLiteral("rooms"), rooms}, {QStringLiteral("more"), list.more}});
}

void SocketBridge::setProxy(const QJsonObject& message)
{
    const QJsonValue use = message.value(QLatin1String("use"));
    const QJsonValue host = message.value(QLatin1String("host"));
    const QJsonValue user = message.value(QLatin1String("user"));
    const std::optional<int> port = wholeNumber(message.value(QLatin1String("port")), 0);
    if (!use.isBool()) {
        sendError(QStringLiteral("setProxy: use must be true or false"));
        return;
    }
    if (!host.isString() || !user.isString()) {
        sendError(QStringLiteral("setProxy: host and user must be strings"));
        return;
    }
    if (!port || *port > 65535) {
        sendError(QStringLiteral("setProxy: port must be a whole number from 0 to 65535"));
        return;
    }
    const QString problem = mEngine->setProxy({use.toBool(), host.toString(), *port, user.toString()});
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("setProxy: %1").arg(problem));
        return;
    }
    sendProxy(QStringLiteral("setProxy"));
}

void SocketBridge::sendProxy(const QString& request)
{
    const std::variant<IEngine::Proxy, QString> read = mEngine->proxy();
    if (const QString* problem = std::get_if<QString>(&read)) {
        sendError(QStringLiteral("%1: %2").arg(request, *problem));
        return;
    }
    const IEngine::Proxy& proxy = std::get<IEngine::Proxy>(read);
    sendReply({{QStringLiteral("t"), QStringLiteral("proxy")},
               {QStringLiteral("use"), proxy.use},
               {QStringLiteral("host"), proxy.host},
               {QStringLiteral("port"), proxy.port},
               {QStringLiteral("user"), proxy.user}});
}

void SocketBridge::getSettings(const QJsonObject& message)
{
    const QJsonArray keysArray = message.value(QLatin1String("keys")).toArray();
    QStringList keys;
    for (const QJsonValue& key : keysArray) {
        if (key.toString().isEmpty()) {
            break;
        }
        keys.append(key.toString());
    }
    if (keys.isEmpty() || keys.size() != keysArray.size()) {
        sendError(QStringLiteral("getSettings: keys must be a list of names"));
        return;
    }
    sendSettings(QStringLiteral("getSettings"), keys, {});
}

void SocketBridge::setSettings(const QJsonObject& message)
{
    const QJsonObject values = message.value(QLatin1String("values")).toObject();
    if (values.isEmpty()) {
        sendError(QStringLiteral("setSettings: values must be an object of settings"));
        return;
    }
    QVariantMap wanted;
    QMap<QString, QString> refused;
    for (auto it = values.constBegin(); it != values.constEnd(); ++it) {
        if (it.value().isBool() || it.value().isDouble() || it.value().isString()) {
            wanted.insert(it.key(), it.value().toVariant());
        } else {
            refused.insert(it.key(), QStringLiteral("a setting is true or false, a number or a string"));
        }
    }
    if (!wanted.isEmpty()) {
        const QString problem = mEngine->setSettings(wanted, refused);
        if (!problem.isEmpty()) {
            sendError(QStringLiteral("setSettings: %1").arg(problem));
            return;
        }
    }
    sendSettings(QStringLiteral("setSettings"), values.keys(), refused);
}

void SocketBridge::sendSettings(const QString& request, const QStringList& keys, const QMap<QString, QString>& refused)
{
    const std::variant<QVariantMap, QString> read = mEngine->settings(keys);
    if (const QString* problem = std::get_if<QString>(&read)) {
        sendError(QStringLiteral("%1: %2").arg(request, *problem));
        return;
    }
    QJsonObject answer{{QStringLiteral("t"), QStringLiteral("settings")}, {QStringLiteral("values"), QJsonObject::fromVariantMap(std::get<QVariantMap>(read))}};
    if (!refused.isEmpty()) {
        QJsonObject why;
        for (auto it = refused.constBegin(); it != refused.constEnd(); ++it) {
            why.insert(it.key(), it.value());
        }
        answer.insert(QStringLiteral("refused"), why);
    }
    sendReply(answer);
}

void SocketBridge::roomInfo(const QJsonObject& message)
{
    const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
    if (!room) {
        sendError(QStringLiteral("roomInfo: room must be a whole number"));
        return;
    }
    sendRoom(QStringLiteral("roomInfo"), mEngine->roomInfo(*room));
}

void SocketBridge::sendRoom(const QString& request, const std::variant<RoomDetails, QString>& found)
{
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("%1: %2").arg(request, *problem));
        return;
    }
    const RoomDetails& details = std::get<RoomDetails>(found);
    QJsonArray exits;
    for (const RoomExitInfo& exit : details.exits) {
        exits.append(QJsonObject{{QStringLiteral("direction"), exit.direction}, {QStringLiteral("to"), exit.to}, {QStringLiteral("toName"), exit.toName}, {QStringLiteral("locked"), exit.locked}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("room")},
               {QStringLiteral("id"), details.id},
               {QStringLiteral("name"), details.name},
               {QStringLiteral("areaId"), details.areaId},
               {QStringLiteral("area"), details.area},
               {QStringLiteral("x"), details.x},
               {QStringLiteral("y"), details.y},
               {QStringLiteral("z"), details.z},
               {QStringLiteral("steps"), details.steps},
               {QStringLiteral("exits"), exits},
               {QStringLiteral("symbol"), details.symbol},
               {QStringLiteral("env"), details.env},
               {QStringLiteral("color"), details.color},
               {QStringLiteral("weight"), details.weight},
               {QStringLiteral("locked"), details.locked}});
}

void SocketBridge::mapAreas()
{
    const std::variant<QList<MapAreaInfo>, QString> found = mEngine->mapAreas();
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("mapAreas: %1").arg(*problem));
        return;
    }
    QJsonArray areas;
    for (const MapAreaInfo& area : std::get<QList<MapAreaInfo>>(found)) {
        areas.append(QJsonObject{{QStringLiteral("id"), area.id}, {QStringLiteral("name"), area.name}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("areas")}, {QStringLiteral("areas"), areas}});
}

void SocketBridge::editRoom(const QJsonObject& message)
{
    const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
    if (!room) {
        sendError(QStringLiteral("editRoom: room must be a whole number"));
        return;
    }
    IEngine::RoomEdit edit;
    QString problem;
    const auto text = [&problem](const QJsonValue& value, const QString& key, std::optional<QString>& into) {
        if (value.isString()) {
            into = value.toString();
        } else if (!value.isUndefined()) {
            problem = QStringLiteral("%1 must be a string").arg(key);
        }
    };
    const auto number = [&problem](const QJsonValue& value, const QString& key, std::optional<int>& into) {
        if (value.isUndefined()) {
            return;
        }
        into = wholeNumber(value);
        if (!into) {
            problem = QStringLiteral("%1 must be a whole number").arg(key);
        }
    };
    text(message.value(QLatin1String("name")), QStringLiteral("name"), edit.name);
    text(message.value(QLatin1String("symbol")), QStringLiteral("symbol"), edit.symbol);
    number(message.value(QLatin1String("env")), QStringLiteral("env"), edit.env);
    number(message.value(QLatin1String("weight")), QStringLiteral("weight"), edit.weight);
    if (const QJsonValue locked = message.value(QLatin1String("locked")); locked.isBool()) {
        edit.locked = locked.toBool();
    } else if (!locked.isUndefined()) {
        problem = QStringLiteral("locked must be true or false");
    }
    std::optional<int> x;
    std::optional<int> y;
    std::optional<int> z;
    number(message.value(QLatin1String("x")), QStringLiteral("x"), x);
    number(message.value(QLatin1String("y")), QStringLiteral("y"), y);
    number(message.value(QLatin1String("z")), QStringLiteral("z"), z);
    if (x && y && z) {
        edit.position = std::array<int, 3>{*x, *y, *z};
    } else if (problem.isEmpty() && (x || y || z)) {
        problem = QStringLiteral("x, y and z go together");
    }
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("editRoom: %1").arg(problem));
        return;
    }
    sendRoom(QStringLiteral("editRoom"), mEngine->editRoom(*room, edit));
}

void SocketBridge::setRoomExit(const QJsonObject& message)
{
    const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
    const std::optional<int> to = wholeNumber(message.value(QLatin1String("to")), 0);
    const QJsonValue bothWays = message.value(QLatin1String("bothWays"));
    QString problem;
    if (!room) {
        problem = QStringLiteral("room must be a whole number");
    } else if (!to) {
        problem = QStringLiteral("to must be a whole number from 0");
    } else if (!bothWays.isBool() && !bothWays.isUndefined()) {
        problem = QStringLiteral("bothWays must be true or false");
    }
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("setRoomExit: %1").arg(problem));
        return;
    }
    sendRoom(QStringLiteral("setRoomExit"), mEngine->setRoomExit(*room, message.value(QLatin1String("direction")).toString(), *to, bothWays.toBool()));
}

void SocketBridge::lockRoomExit(const QJsonObject& message)
{
    const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
    const QJsonValue locked = message.value(QLatin1String("locked"));
    if (!room || !locked.isBool()) {
        sendError(!room ? QStringLiteral("lockRoomExit: room must be a whole number") : QStringLiteral("lockRoomExit: locked must be true or false"));
        return;
    }
    sendRoom(QStringLiteral("lockRoomExit"), mEngine->lockRoomExit(*room, message.value(QLatin1String("direction")).toString(), locked.toBool()));
}

void SocketBridge::addRoom(const QJsonObject& message)
{
    const std::optional<int> room = wholeNumber(message.value(QLatin1String("room")));
    if (!room) {
        sendError(QStringLiteral("addRoom: room must be a whole number"));
        return;
    }
    sendRoom(QStringLiteral("addRoom"), mEngine->addRoom(*room, message.value(QLatin1String("direction")).toString()));
}

QString SocketBridge::renameArea(const QJsonObject& message)
{
    const std::optional<int> area = wholeNumber(message.value(QLatin1String("area")));
    const QJsonValue name = message.value(QLatin1String("name"));
    if (!area) {
        return QStringLiteral("area must be a whole number");
    }
    if (!name.isString()) {
        return QStringLiteral("name must be a string");
    }
    return mEngine->renameArea(*area, name.toString());
}

void SocketBridge::mapEnvs()
{
    const std::variant<QList<MapEnvInfo>, QString> found = mEngine->mapEnvs();
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("mapEnvs: %1").arg(*problem));
        return;
    }
    QJsonArray envs;
    for (const MapEnvInfo& env : std::get<QList<MapEnvInfo>>(found)) {
        envs.append(QJsonObject{{QStringLiteral("env"), env.env}, {QStringLiteral("color"), env.color}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("envs")}, {QStringLiteral("envs"), envs}});
}

void SocketBridge::packages()
{
    const std::variant<QList<PackageInfo>, QString> found = mEngine->packages();
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("packages: %1").arg(*problem));
        return;
    }
    QJsonArray packages;
    for (const PackageInfo& package : std::get<QList<PackageInfo>>(found)) {
        packages.append(QJsonObject{{QStringLiteral("name"), package.name},
                                    {QStringLiteral("title"), package.title},
                                    {QStringLiteral("author"), package.author},
                                    {QStringLiteral("version"), package.version},
                                    {QStringLiteral("description"), package.description},
                                    {QStringLiteral("icon"), package.icon},
                                    {QStringLiteral("mobile"), package.mobile}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("packages")}, {QStringLiteral("packages"), packages}});
}

void SocketBridge::uninstallPackage(const QJsonObject& message)
{
    const QString name = message.value(QLatin1String("name")).toString();
    if (name.isEmpty()) {
        sendError(QStringLiteral("uninstallPackage: name must not be empty"));
        return;
    }
    if (const QString problem = mEngine->uninstallPackage(name); !problem.isEmpty()) {
        sendError(QStringLiteral("uninstallPackage: %1").arg(problem));
        return;
    }
    packages();
}

void SocketBridge::scriptItems(const QJsonObject& message)
{
    const std::optional<IEngine::ScriptItemKind> kind = scriptItemKind(message);
    if (!kind) {
        sendError(QStringLiteral("scriptItems: %1").arg(scriptItemKindProblem));
        return;
    }
    const std::variant<QList<ScriptItemInfo>, QString> found = mEngine->scriptItems(*kind);
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("scriptItems: %1").arg(*problem));
        return;
    }
    QJsonArray items;
    for (const ScriptItemInfo& item : std::get<QList<ScriptItemInfo>>(found)) {
        items.append(QJsonObject{{QStringLiteral("id"), item.id},
                                 {QStringLiteral("parent"), item.parent},
                                 {QStringLiteral("name"), item.name},
                                 {QStringLiteral("folder"), item.folder},
                                 {QStringLiteral("enabled"), item.enabled},
                                 {QStringLiteral("active"), item.active},
                                 {QStringLiteral("package"), item.package},
                                 {QStringLiteral("error"), item.error},
                                 {QStringLiteral("summary"), item.summary}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("scriptItems")}, {QStringLiteral("kind"), message.value(QLatin1String("kind"))}, {QStringLiteral("items"), items}});
}

void SocketBridge::sendScriptItem(const QString& request, const QJsonObject& message, int item)
{
    const std::variant<QVariantMap, QString> found = mEngine->scriptItem(*scriptItemKind(message), item);
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("%1: %2").arg(request, *problem));
        return;
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("scriptItem")},
               {QStringLiteral("kind"), message.value(QLatin1String("kind"))},
               {QStringLiteral("fields"), QJsonObject::fromVariantMap(std::get<QVariantMap>(found))}});
}

void SocketBridge::scriptItem(const QJsonObject& message)
{
    const std::variant<std::pair<IEngine::ScriptItemKind, int>, QString> target = scriptItemTarget(message);
    if (const QString* problem = std::get_if<QString>(&target)) {
        sendError(QStringLiteral("scriptItem: %1").arg(*problem));
        return;
    }
    sendScriptItem(QStringLiteral("scriptItem"), message, std::get<0>(target).second);
}

void SocketBridge::addScriptItem(const QJsonObject& message)
{
    const std::optional<IEngine::ScriptItemKind> kind = scriptItemKind(message);
    const std::optional<int> parent = wholeNumber(message.value(QLatin1String("parent")), 0);
    const std::optional<int> position = wholeNumber(message.value(QLatin1String("position")), 0);
    const QJsonValue folder = message.value(QLatin1String("folder"));
    const QJsonValue name = message.value(QLatin1String("name"));
    QString problem;
    if (!kind) {
        problem = QString(scriptItemKindProblem);
    } else if (!parent) {
        problem = QStringLiteral("parent must be a whole number from 0");
    } else if (!position) {
        problem = QStringLiteral("position must be a whole number from 0");
    } else if (!folder.isBool()) {
        problem = QStringLiteral("folder must be true or false");
    } else if (!name.isString()) {
        problem = QStringLiteral("name must be a string");
    }
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("addScriptItem: %1").arg(problem));
        return;
    }
    const std::variant<int, QString> added = mEngine->addScriptItem(*kind, *parent, *position, folder.toBool(), name.toString());
    if (const QString* refused = std::get_if<QString>(&added)) {
        sendError(QStringLiteral("addScriptItem: %1").arg(*refused));
        return;
    }
    sendScriptItem(QStringLiteral("addScriptItem"), message, std::get<int>(added));
}

void SocketBridge::editScriptItem(const QJsonObject& message)
{
    const std::variant<std::pair<IEngine::ScriptItemKind, int>, QString> target = scriptItemTarget(message);
    const QJsonValue fields = message.value(QLatin1String("fields"));
    QString problem;
    if (const QString* bad = std::get_if<QString>(&target)) {
        problem = *bad;
    } else if (!fields.isObject()) {
        problem = QStringLiteral("fields must be an object");
    } else {
        problem = mEngine->editScriptItem(std::get<0>(target).first, std::get<0>(target).second, fields.toObject().toVariantMap());
    }
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("editScriptItem: %1").arg(problem));
        return;
    }
    sendScriptItem(QStringLiteral("editScriptItem"), message, std::get<0>(target).second);
}

void SocketBridge::enableScriptItem(const QJsonObject& message)
{
    const std::variant<std::pair<IEngine::ScriptItemKind, int>, QString> target = scriptItemTarget(message);
    const QJsonValue enabled = message.value(QLatin1String("enabled"));
    QString problem;
    if (const QString* bad = std::get_if<QString>(&target)) {
        problem = *bad;
    } else if (!enabled.isBool()) {
        problem = QStringLiteral("enabled must be true or false");
    } else {
        problem = mEngine->enableScriptItem(std::get<0>(target).first, std::get<0>(target).second, enabled.toBool());
    }
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("enableScriptItem: %1").arg(problem));
        return;
    }
    sendScriptItem(QStringLiteral("enableScriptItem"), message, std::get<0>(target).second);
}

QString SocketBridge::moveScriptItem(const QJsonObject& message)
{
    const std::variant<std::pair<IEngine::ScriptItemKind, int>, QString> target = scriptItemTarget(message);
    if (const QString* problem = std::get_if<QString>(&target)) {
        return *problem;
    }
    const std::optional<int> parent = wholeNumber(message.value(QLatin1String("parent")), 0);
    const std::optional<int> position = wholeNumber(message.value(QLatin1String("position")), 0);
    if (!parent) {
        return QStringLiteral("parent must be a whole number from 0");
    }
    if (!position) {
        return QStringLiteral("position must be a whole number from 0");
    }
    return mEngine->moveScriptItem(std::get<0>(target).first, std::get<0>(target).second, *parent, *position);
}

void SocketBridge::searchScriptItems(const QJsonObject& message)
{
    const QJsonValue text = message.value(QLatin1String("text"));
    const QJsonValue caseSensitive = message.value(QLatin1String("caseSensitive"));
    const QJsonValue wholeWord = message.value(QLatin1String("wholeWord"));
    if (!text.isString()) {
        sendError(QStringLiteral("searchScriptItems: text must be a string"));
        return;
    }
    if (!(caseSensitive.isBool() || caseSensitive.isUndefined()) || !(wholeWord.isBool() || wholeWord.isUndefined())) {
        sendError(QStringLiteral("searchScriptItems: caseSensitive and wholeWord must be true or false"));
        return;
    }
    const std::variant<IEngine::ScriptSearch, QString> found = mEngine->searchScriptItems(text.toString(), caseSensitive.toBool(), wholeWord.toBool(), maxSearchMatches);
    if (const QString* problem = std::get_if<QString>(&found)) {
        sendError(QStringLiteral("searchScriptItems: %1").arg(*problem));
        return;
    }
    const IEngine::ScriptSearch& search = std::get<IEngine::ScriptSearch>(found);
    QJsonArray matches;
    for (const IEngine::ScriptSearchMatch& match : search.matches) {
        const auto [snippet, start] = searchSnippet(match.text, match.start, int(text.toString().size()));
        matches.append(QJsonObject{{QStringLiteral("kind"), wireName(match.kind)},
                                   {QStringLiteral("item"), match.item},
                                   {QStringLiteral("name"), match.name},
                                   {QStringLiteral("field"), wireName(match.field)},
                                   {QStringLiteral("index"), match.index},
                                   {QStringLiteral("text"), snippet},
                                   {QStringLiteral("start"), start}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("scriptSearch")},
               {QStringLiteral("text"), text},
               {QStringLiteral("caseSensitive"), caseSensitive.toBool()},
               {QStringLiteral("wholeWord"), wholeWord.toBool()},
               {QStringLiteral("matches"), matches},
               {QStringLiteral("more"), search.more}});
}

void SocketBridge::variables(const QJsonObject& message)
{
    const QJsonValue path = message.value(QLatin1String("path"));
    const QJsonValue showHidden = message.value(QLatin1String("showHidden"));
    const QJsonValue fromValue = message.value(QLatin1String("from"));
    if (!(path.isArray() || path.isUndefined())) {
        sendError(QStringLiteral("variables: path must be a list"));
        return;
    }
    QList<IEngine::VariableKey> keys;
    for (const QJsonValue& step : path.toArray()) {
        const QJsonValue name = step.toObject().value(QLatin1String("name"));
        const std::optional<IEngine::VariableType> key = variableType(step.toObject().value(QLatin1String("key")));
        if (!name.isString() || !key) {
            sendError(QStringLiteral("variables: each step of path must have a name and a key type"));
            return;
        }
        keys.append({name.toString(), *key});
    }
    if (!(showHidden.isBool() || showHidden.isUndefined())) {
        sendError(QStringLiteral("variables: showHidden must be true or false"));
        return;
    }
    const std::optional<int> from = fromValue.isUndefined() ? std::optional<int>(0) : wholeNumber(fromValue, 0);
    if (!from) {
        sendError(QStringLiteral("variables: from must be a whole number from 0"));
        return;
    }
    const std::variant<IEngine::Variables, QString> listed = mEngine->variables(keys, showHidden.toBool());
    if (const QString* problem = std::get_if<QString>(&listed)) {
        sendError(QStringLiteral("variables: %1").arg(*problem));
        return;
    }
    const IEngine::Variables& variables = std::get<IEngine::Variables>(listed);
    QJsonArray entries;
    for (const IEngine::Variable& variable : variables.variables.mid(*from, maxVariablesPerFrame)) {
        QJsonObject entry{{QStringLiteral("name"), variable.key.name},
                          {QStringLiteral("key"), wireName(variable.key.type)},
                          {QStringLiteral("type"), wireName(variable.type)},
                          {QStringLiteral("hidden"), variable.hidden}};
        if (variable.type == IEngine::VariableType::table) {
            entry.insert(QStringLiteral("members"), variable.members);
            entry.insert(QStringLiteral("indexed"), variable.membersIndexed);
        } else if (variable.type != IEngine::VariableType::function) {
            qsizetype length = std::min(variable.value.size(), qsizetype(maxVariableValueChars));
            // A surrogate pair cut in two would be no character
            if (length < variable.value.size() && variable.value.at(length).isLowSurrogate()) {
                --length;
            }
            entry.insert(QStringLiteral("value"), variable.value.left(length));
            if (length < variable.value.size()) {
                entry.insert(QStringLiteral("cut"), true);
            }
        }
        entries.append(entry);
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("variables")},
               {QStringLiteral("path"), path.toArray()},
               {QStringLiteral("showHidden"), showHidden.toBool()},
               {QStringLiteral("from"), *from},
               {QStringLiteral("total"), int(variables.variables.size())},
               {QStringLiteral("hiddenLeftOut"), variables.hiddenLeftOut},
               {QStringLiteral("entries"), entries}});
}

void SocketBridge::checkLua(const QJsonObject& message)
{
    const QJsonValue code = message.value(QLatin1String("code"));
    if (!code.isString()) {
        sendError(QStringLiteral("checkLua: code must be a string"));
        return;
    }
    const std::optional<IEngine::LuaProblem> problem = mEngine->checkLua(code.toString());
    QJsonObject reply{{QStringLiteral("t"), QStringLiteral("luaCheck")}, {QStringLiteral("ok"), !problem}};
    if (problem) {
        reply.insert(QStringLiteral("line"), problem->line);
        reply.insert(QStringLiteral("message"), problem->message);
    }
    sendReply(reply);
}

void SocketBridge::pressKey(const QJsonObject& message)
{
    const std::optional<int> key = wholeNumber(message.value(QLatin1String("key")), 0);
    const QJsonValue modifiersValue = message.value(QLatin1String("modifiers"));
    const std::optional<int> modifiers = modifiersValue.isUndefined() ? std::optional<int>(0) : wholeNumber(modifiersValue, 0);
    const QJsonValue textValue = message.value(QLatin1String("text"));
    if (!key) {
        sendError(QStringLiteral("pressKey: key must be a whole number from 0"));
        return;
    }
    if (!modifiers) {
        sendError(QStringLiteral("pressKey: modifiers must be a whole number from 0"));
        return;
    }
    if (!textValue.isUndefined() && !textValue.isString()) {
        sendError(QStringLiteral("pressKey: text must be a string"));
        return;
    }
    const std::optional<QString> text = textValue.isString() ? std::optional<QString>(textValue.toString()) : std::nullopt;
    const std::variant<IEngine::KeyPress, QString> pressed = mEngine->pressKey(*key, *modifiers, text);
    if (const QString* problem = std::get_if<QString>(&pressed)) {
        sendError(QStringLiteral("pressKey: %1").arg(*problem));
        return;
    }
    QString result;
    switch (std::get<IEngine::KeyPress>(pressed)) {
    case IEngine::KeyPress::bindingRan:
        result = QStringLiteral("bindingRan");
        break;
    case IEngine::KeyPress::commandRan:
        result = QStringLiteral("commandRan");
        break;
    case IEngine::KeyPress::noBinding:
        result = QStringLiteral("noBinding");
        break;
    case IEngine::KeyPress::keyKept:
        result = QStringLiteral("keyKept");
        break;
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("keyPressed")}, {QStringLiteral("result"), result}});
}

QString SocketBridge::deleteScriptItem(const QJsonObject& message)
{
    const std::variant<std::pair<IEngine::ScriptItemKind, int>, QString> target = scriptItemTarget(message);
    if (const QString* problem = std::get_if<QString>(&target)) {
        return *problem;
    }
    return mEngine->deleteScriptItem(std::get<0>(target).first, std::get<0>(target).second);
}

void SocketBridge::getImage(const QJsonObject& message)
{
    const QString path = message.value(QLatin1String("path")).toString();
    if (path.isEmpty()) {
        sendError(QStringLiteral("getImage: path must not be empty"));
        return;
    }
    const QJsonValue widthValue = message.value(QLatin1String("w"));
    const QJsonValue heightValue = message.value(QLatin1String("h"));
    const QJsonValue frameValue = message.value(QLatin1String("frame"));
    QSize size;
    if (!widthValue.isUndefined() || !heightValue.isUndefined()) {
        const std::optional<int> width = wholeNumber(widthValue);
        const std::optional<int> height = wholeNumber(heightValue);
        if (!width || !height || *width < 1 || *height < 1 || *width > PngImage::maxSide || *height > PngImage::maxSide) {
            sendError(QStringLiteral("getImage: w and h go together, whole numbers from 1 to %1").arg(PngImage::maxSide));
            return;
        }
        size = QSize(*width, *height);
    }
    std::optional<int> frame;
    if (!frameValue.isUndefined()) {
        frame = wholeNumber(frameValue);
        if (!frame || *frame < 0) {
            sendError(QStringLiteral("getImage: frame must be a whole number from 0"));
            return;
        }
    }
    const std::variant<PngImage, QString> image = mEngine->image(path, size, frame);
    if (const QString* problem = std::get_if<QString>(&image)) {
        sendError(QStringLiteral("getImage: %1").arg(*problem));
        return;
    }
    const PngImage& png = std::get<PngImage>(image);
    const QByteArray base64 = png.png.toBase64();
    // With room for the path, each of its characters escaped at worst, and the other fields
    if (base64.size() + path.size() * 6 + 1024 > qsizetype(maxFrameBytes)) {
        sendError(QStringLiteral("getImage: %1 as a PNG is too large for a frame").arg(path));
        return;
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("image")},
               {QStringLiteral("path"), path},
               {QStringLiteral("png"), QString::fromLatin1(base64)},
               {QStringLiteral("w"), png.size.width()},
               {QStringLiteral("h"), png.size.height()}});
}

void SocketBridge::refuse(const QString& request, const QString& problem)
{
    if (!problem.isEmpty()) {
        sendError(QStringLiteral("%1: %2").arg(request, problem));
    }
}

void SocketBridge::sendError(const QString& message)
{
    sendReply({{QStringLiteral("t"), QStringLiteral("error")}, {QStringLiteral("message"), message}});
}

void SocketBridge::sendReply(QJsonObject message)
{
    if (replying()) {
        message.insert(QStringLiteral("re"), mCurrentRequestId);
    }
    sendFrame(message);
}

QByteArray SocketBridge::lineMessage(const StoredLine& stored, bool replay)
{
    return lineJson(stored.seq, stored.text, stored.runs, stored.prompt, stored.time, replay);
}

QByteArray SocketBridge::lineUpdatedMessage(const StoredLine& stored)
{
    return stored.deleted ? lineDeletedJson(stored.seq) : lineUpdatedJson(stored.seq, stored.text, stored.runs, stored.prompt);
}

// [start, length, fg], with bg and attributes, then link, only when a run has them
QJsonArray SocketBridge::runsJson(const QList<ColorRun>& runs)
{
    QJsonArray json;
    for (const ColorRun& run : runs) {
        if (run.link != 0) {
            json.append(QJsonArray{run.start, run.length, run.fg, run.bg, run.attributes, run.link});
        } else if (run.bg != -1 || run.attributes != 0) {
            json.append(QJsonArray{run.start, run.length, run.fg, run.bg, run.attributes});
        } else {
            json.append(QJsonArray{run.start, run.length, run.fg});
        }
    }
    return json;
}

QByteArray SocketBridge::tailMessage() const
{
    return tailJson(mTail.text, mTail.runs, mTail.prompt, mTail.time);
}

QJsonObject SocketBridge::hiddenInputMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("hiddenInput")}, {QStringLiteral("wanted"), mHiddenInput}};
}

QJsonObject SocketBridge::secureOfferMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("secureOffer")}, {QStringLiteral("offered"), mSecureOffer}};
}

QJsonObject SocketBridge::scriptsStoppedMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("scriptsStopped")}, {QStringLiteral("stopped"), mScriptsStopped}};
}

QJsonObject SocketBridge::replayRecordingMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("replayRecording")}, {QStringLiteral("recording"), mReplayRecording}, {QStringLiteral("file"), mRecordedReplay}};
}

QJsonObject SocketBridge::replayMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("replay")}, {QStringLiteral("playing"), mReplay.playing}, {QStringLiteral("paused"), mReplay.paused}, {QStringLiteral("speed"), mReplay.speed}};
}

QJsonObject SocketBridge::certificateMessage() const
{
    QJsonObject message{{QStringLiteral("t"), QStringLiteral("certificateRefused")}, {QStringLiteral("refused"), mRefusedCertificate.has_value()}};
    if (mRefusedCertificate) {
        const RefusedCertificate& certificate = *mRefusedCertificate;
        message.insert(QStringLiteral("issuedTo"), certificate.issuedTo);
        message.insert(QStringLiteral("issuer"), certificate.issuer);
        message.insert(QStringLiteral("expires"), certificate.expires.isValid() ? certificate.expires.toUTC().toString(Qt::ISODate) : QString());
        message.insert(QStringLiteral("serial"), certificate.serial);
        message.insert(QStringLiteral("expired"), certificate.expired);
        message.insert(QStringLiteral("selfSigned"), certificate.selfSigned);
        message.insert(QStringLiteral("others"), certificate.others);
        message.insert(QStringLiteral("errors"), QJsonArray::fromStringList(certificate.errors));
    }
    return message;
}

QJsonObject SocketBridge::historyMessage(const QStringList& lines)
{
    return {{QStringLiteral("t"), QStringLiteral("history")}, {QStringLiteral("lines"), QJsonArray::fromStringList(lines)}};
}

QJsonObject SocketBridge::errorConsoleMessage(const QList<ErrorConsoleEntry>& entries, const bool full)
{
    QJsonArray json;
    for (const ErrorConsoleEntry& entry : entries) {
        json.append(QJsonObject{{QStringLiteral("n"), entry.n},
                                {QStringLiteral("at"), entry.at},
                                {QStringLiteral("debug"), entry.debug},
                                {QStringLiteral("object"), entry.object},
                                {QStringLiteral("function"), entry.function},
                                {QStringLiteral("event"), entry.event},
                                {QStringLiteral("message"), entry.message},
                                {QStringLiteral("kind"), entry.kind},
                                {QStringLiteral("item"), entry.item},
                                {QStringLiteral("line"), entry.line}});
    }
    return {{QStringLiteral("t"), QStringLiteral("errorConsole")}, {QStringLiteral("full"), full}, {QStringLiteral("entries"), json}};
}

QJsonObject SocketBridge::linksChangedMessage(const QList<int>& ids)
{
    QJsonArray json;
    for (const int id : ids) {
        json.append(id);
    }
    return {{QStringLiteral("t"), QStringLiteral("linksChanged")}, {QStringLiteral("ids"), json}};
}

QJsonObject SocketBridge::windowJson(const ScriptWindowInfo& window)
{
    QJsonObject json{{QStringLiteral("name"), window.name},
                     {QStringLiteral("kind"), window.kind},
                     {QStringLiteral("parent"), window.parent},
                     {QStringLiteral("x"), window.geometry.x()},
                     {QStringLiteral("y"), window.geometry.y()},
                     {QStringLiteral("w"), window.geometry.width()},
                     {QStringLiteral("h"), window.geometry.height()},
                     {QStringLiteral("visible"), window.visible},
                     {QStringLiteral("shown"), window.shown},
                     {QStringLiteral("stack"), window.stackOrder},
                     {QStringLiteral("inDock"), window.inDock}};
    if (!window.parentKind.isEmpty()) {
        json.insert(QStringLiteral("parentKind"), window.parentKind);
    }
    if (window.background != -1) {
        json.insert(QStringLiteral("bg"), window.background);
        if (window.backgroundAlpha < 255) {
            json.insert(QStringLiteral("bgAlpha"), window.backgroundAlpha);
        }
    }
    if (!window.fontFamily.isEmpty()) {
        json.insert(QStringLiteral("fontFamily"), window.fontFamily);
    }
    if (window.fontSize > 0) {
        json.insert(QStringLiteral("fontSize"), window.fontSize);
    }
    if (window.dock) {
        json.insert(QStringLiteral("autoDock"), window.dock->autoDock);
        json.insert(QStringLiteral("dockArea"), window.dock->area);
        json.insert(QStringLiteral("dockFloating"), window.dock->floating);
        json.insert(QStringLiteral("dockTitle"), window.dock->title);
    }
    if (window.console) {
        if (window.console->scrolledBackTo) {
            json.insert(QStringLiteral("scrolledBackTo"), *window.console->scrolledBackTo);
        }
        json.insert(QStringLiteral("columns"), window.console->columns);
        json.insert(QStringLiteral("rows"), window.console->rows);
        json.insert(QStringLiteral("scrollBar"), window.console->scrollBar);
        json.insert(QStringLiteral("timeStamps"), window.console->timeStamps);
    }
    if (window.frameTabs) {
        json.insert(QStringLiteral("tabs"), QJsonArray::fromStringList(window.frameTabs->pages));
        json.insert(QStringLiteral("tabTitles"), QJsonArray::fromStringList(window.frameTabs->titles));
        json.insert(QStringLiteral("tabCurrent"), window.frameTabs->current);
    }
    if (!window.styleSheet.isEmpty()) {
        json.insert(QStringLiteral("styleSheet"), window.styleSheet);
    }
    if (!window.packages.isEmpty()) {
        json.insert(QStringLiteral("packages"), QJsonArray::fromStringList(window.packages));
        json.insert(QStringLiteral("mobile"), window.mobile);
    }
    return json;
}

QJsonObject SocketBridge::labelMessage(const LabelInfo& label)
{
    QJsonObject json{{QStringLiteral("t"), QStringLiteral("label")},
                     {QStringLiteral("name"), label.name},
                     {QStringLiteral("text"), label.text},
                     {QStringLiteral("image"), label.image},
                     {QStringLiteral("svg"), label.svg},
                     {QStringLiteral("movie"), label.movie},
                     {QStringLiteral("moviePaused"), label.moviePaused},
                     {QStringLiteral("movieFrame"), label.movieFrame},
                     {QStringLiteral("movieSpeed"), label.movieSpeed},
                     {QStringLiteral("toolTip"), label.toolTip},
                     {QStringLiteral("clickThrough"), label.clickThrough},
                     {QStringLiteral("accessibleName"), label.accessibleName},
                     {QStringLiteral("accessibleButton"), label.accessibleButton},
                     {QStringLiteral("styleSheet"), label.styleSheet}};
    if (label.svgTint != -1) {
        json.insert(QStringLiteral("svgTint"), label.svgTint);
        if (label.svgTintAlpha < 255) {
            json.insert(QStringLiteral("svgTintAlpha"), label.svgTintAlpha);
        }
    }
    if (label.svgRotation != 0) {
        json.insert(QStringLiteral("svgRotation"), label.svgRotation);
    }
    if (label.svgShearX != 0) {
        json.insert(QStringLiteral("svgShearX"), label.svgShearX);
    }
    if (label.svgShearY != 0) {
        json.insert(QStringLiteral("svgShearY"), label.svgShearY);
    }
    if (label.movieSize.isValid()) {
        json.insert(QStringLiteral("movieW"), label.movieSize.width());
        json.insert(QStringLiteral("movieH"), label.movieSize.height());
    }
    if (!label.liveRegion.isEmpty()) {
        json.insert(QStringLiteral("liveRegion"), label.liveRegion);
    }
    if (const auto& value = label.accessibleValue) {
        json.insert(QStringLiteral("value"), value->value);
        json.insert(QStringLiteral("valueMin"), value->min);
        json.insert(QStringLiteral("valueMax"), value->max);
    }
    return json;
}

QJsonObject SocketBridge::textBoxMessage(const TextBoxInfo& textBox) const
{
    return {{QStringLiteral("t"), QStringLiteral("textBox")},
            {QStringLiteral("name"), textBox.name},
            {QStringLiteral("text"), textBox.text},
            {QStringLiteral("readOnly"), textBox.readOnly},
            {QStringLiteral("placeholder"), textBox.placeholder},
            {QStringLiteral("styleSheet"), textBox.styleSheet},
            {QStringLiteral("tabMovesFocus"), textBox.tabMovesFocus},
            {QStringLiteral("typed"), mTextBoxTyped.value(textBox.name)}};
}

QJsonArray SocketBridge::buttonsJson(const QList<BarButton>& buttons)
{
    QJsonArray json;
    for (const BarButton& button : buttons) {
        json.append(QJsonObject{{QStringLiteral("id"), button.id},
                                {QStringLiteral("name"), button.name},
                                {QStringLiteral("icon"), button.icon},
                                {QStringLiteral("styleSheet"), button.styleSheet},
                                {QStringLiteral("pushDown"), button.pushDown},
                                {QStringLiteral("checked"), button.checked},
                                {QStringLiteral("menu"), button.menu},
                                {QStringLiteral("children"), buttonsJson(button.children)}});
    }
    return json;
}

QJsonObject SocketBridge::buttonBarsMessage(const QList<ButtonBar>& bars)
{
    QJsonArray json;
    for (const ButtonBar& bar : bars) {
        json.append(QJsonObject{{QStringLiteral("id"), bar.id},
                                {QStringLiteral("name"), bar.name},
                                {QStringLiteral("styleSheet"), bar.styleSheet},
                                {QStringLiteral("place"), bar.place},
                                {QStringLiteral("vertical"), bar.vertical},
                                {QStringLiteral("columns"), bar.columns},
                                {QStringLiteral("fillerOffset"), bar.fillerOffset},
                                {QStringLiteral("buttons"), buttonsJson(bar.buttons)}});
    }
    return {{QStringLiteral("t"), QStringLiteral("buttonBars")}, {QStringLiteral("bars"), json}};
}

QJsonObject SocketBridge::commandsMessage(const QList<AddonCommandInfo>& commands)
{
    QJsonArray json;
    for (const AddonCommandInfo& command : commands) {
        json.append(QJsonObject{{QStringLiteral("id"), command.id},
                                {QStringLiteral("package"), command.package},
                                {QStringLiteral("name"), command.name},
                                {QStringLiteral("icon"), command.icon},
                                {QStringLiteral("tooltip"), command.tooltip},
                                {QStringLiteral("menuPath"), command.menuPath},
                                {QStringLiteral("shortcut"), command.shortcut},
                                {QStringLiteral("onMenu"), command.onMenu},
                                {QStringLiteral("onToolbar"), command.onToolbar},
                                {QStringLiteral("enabled"), command.enabled},
                                {QStringLiteral("checkable"), command.checkable},
                                {QStringLiteral("checked"), command.checked},
                                {QStringLiteral("pinned"), command.pinned},
                                {QStringLiteral("pulse"), command.pulse}});
    }
    return {{QStringLiteral("t"), QStringLiteral("commands")}, {QStringLiteral("commands"), json}};
}

QJsonObject SocketBridge::keyBindingsMessage(const QList<KeyBinding>& bindings)
{
    QJsonArray json;
    for (const KeyBinding& binding : bindings) {
        json.append(QJsonObject{{QStringLiteral("key"), binding.key}, {QStringLiteral("modifiers"), binding.modifiers}});
    }
    return {{QStringLiteral("t"), QStringLiteral("keyBindings")}, {QStringLiteral("keys"), json}};
}

QJsonObject SocketBridge::windowTextMessage(const QString& window, const WindowText& text)
{
    QJsonArray lines;
    for (const WindowText::Row& row : text.rows) {
        lines.append(QJsonObject{{QStringLiteral("text"), row.text}, {QStringLiteral("runs"), runsJson(row.runs)}, {QStringLiteral("prompt"), row.prompt}, {QStringLiteral("wrapped"), row.wrapped}});
    }
    return {{QStringLiteral("t"), QStringLiteral("windowText")},
            {QStringLiteral("window"), window},
            {QStringLiteral("lineCount"), text.lineCount},
            {QStringLiteral("first"), text.first},
            {QStringLiteral("lines"), lines}};
}

QJsonObject SocketBridge::mainAreaMessage(const MainArea& area)
{
    QJsonObject json{{QStringLiteral("t"), QStringLiteral("mainArea")},
                     {QStringLiteral("w"), area.size.width()},
                     {QStringLiteral("h"), area.size.height()},
                     {QStringLiteral("columns"), area.grid.width()},
                     {QStringLiteral("rows"), area.grid.height()},
                     {QStringLiteral("textX"), area.textRect.x()},
                     {QStringLiteral("textY"), area.textRect.y()},
                     {QStringLiteral("textW"), area.textRect.width()},
                     {QStringLiteral("textH"), area.textRect.height()},
                     {QStringLiteral("borderColor"), area.borderColor},
                     {QStringLiteral("adaptive"), area.adaptive}};
    if (!area.looks.isEmpty()) {
        QJsonArray looks;
        for (const MainArea::Look& look : area.looks) {
            looks.append(QJsonObject{{QStringLiteral("look"), look.look}, {QStringLiteral("packages"), QJsonArray::fromStringList(look.packages)}, {QStringLiteral("mobile"), look.mobile}});
        }
        json.insert(QStringLiteral("looks"), looks);
    }
    return json;
}

void SocketBridge::sendFrame(const QJsonObject& message)
{
    if (mClient && mClient->state() == QLocalSocket::ConnectedState) {
        sendFrame(QJsonDocument(message).toJson(QJsonDocument::Compact));
    }
}

void SocketBridge::sendFrame(const QByteArray& json)
{
    if (!mClient || mClient->state() != QLocalSocket::ConnectedState) {
        return;
    }
    char header[4];
    qToBigEndian<quint32>(quint32(json.size()), header);
    mClient->write(header, 4);
    mClient->write(json);
    if (mClient->bytesToWrite() > highWaterBytes) {
        mClientLagging = true;
    }
}

void SocketBridge::line(const QString& text, const QList<ColorRun>& runs, bool isPrompt, qint64 time, qint64 lineId)
{
    if (mMirror) {
        mMirror->line(text, runs, isPrompt, time, lineId);
    }
    mScrollback.push_back({mNextSeq++, text, runs, isPrompt, time, lineId});
    if (lineId) {
        mSeqOfLineId.insert(lineId, mScrollback.back().seq);
    }
    if (qsizetype(mScrollback.size()) > scrollbackLines) {
        const StoredLine& oldest = mScrollback.front();
        if (const auto kept = mSeqOfLineId.constFind(oldest.lineId); kept != mSeqOfLineId.cend() && kept.value() == oldest.seq) {
            mSeqOfLineId.erase(kept);
        }
        mUpdatesOwed.remove(oldest.seq);
        mScrollback.pop_front();
    }
    if (mControl) {
        mControl->profileChanged(this, ControlBridge::Change::lines);
    }
    if (outputLive()) {
        sendFrame(lineMessage(mScrollback.back(), false));
        mLastSentSeq = mScrollback.back().seq;
    }
}

void SocketBridge::lineUpdated(qint64 lineId, const QString& text, const QList<ColorRun>& runs, bool isPrompt, bool deleted)
{
    if (mMirror) {
        mMirror->lineUpdated(lineId, text, runs, isPrompt, deleted);
    }
    const auto found = mSeqOfLineId.constFind(lineId);
    if (found == mSeqOfLineId.cend()) {
        return;
    }
    // Sequence numbers run on without a hole from the oldest line kept
    StoredLine& stored = mScrollback[size_t(found.value() - mScrollback.front().seq)];
    stored.edited = true;
    if (deleted) {
        stored.deleted = true;
        stored.text.clear();
        stored.runs.clear();
        mSeqOfLineId.erase(found);
    } else {
        stored.text = text;
        stored.runs = runs;
        stored.prompt = isPrompt;
    }
    // A line not sent yet goes as it now is, or not at all
    if (stored.seq > mLastSentSeq) {
        return;
    }
    if (outputLive()) {
        sendFrame(lineUpdatedMessage(stored));
    } else if (mClient && mClientGreeted && mProfileOutput) {
        mUpdatesOwed.insert(stored.seq);
    }
}

void SocketBridge::tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt, qint64 time)
{
    if (mMirror) {
        mMirror->tail(text, runs, isPrompt, time);
    }
    mTail = {0, text, runs, isPrompt, time};
    if (outputLive()) {
        sendFrame(tailMessage());
    }
}

void SocketBridge::cleared()
{
    if (mMirror) {
        mMirror->cleared();
    }
    mScrollback.clear();
    mSeqOfLineId.clear();
    mUpdatesOwed.clear();
    mClearedAfterSeq = mNextSeq - 1;
    mTail = {0, {}, {}, false, 0};
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("clear")}});
    }
}

void SocketBridge::notify(const QString& title, const QString& text, int expireMs)
{
#ifdef Q_OS_ANDROID
    // Posted here, not in the UI, so tells still arrive while the UI process is frozen or dead
    AndroidNotifier::postTell(mProfileName, title, text, expireMs);
#else
    Q_UNUSED(expireMs)
#endif
    sendNotify(title, text);
    if (mMirror) {
        mMirror->sendNotify(title, text);
    }
}

void SocketBridge::sendNotify(const QString& title, const QString& text)
{
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("notify")}, {QStringLiteral("title"), title}, {QStringLiteral("text"), text}});
    }
}

void SocketBridge::status(const QString& key, const QString& value)
{
    if (mMirror) {
        mMirror->status(key, value);
    }
    const bool connectionChanged = key == QLatin1String("connection") && mStatus.value(key) != value;
    mStatus.insert(key, value);
    if (mControl && connectionChanged) {
        mControl->profileChanged(this, ControlBridge::Change::connection);
    }
    // A reply is sent even to a lagging UI, which would otherwise wait for it forever
    if (outputLive() || (replying() && mProfileOutput && mClient && mClientGreeted)) {
        sendReply({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), key}, {QStringLiteral("value"), value}});
    }
}

void SocketBridge::hiddenInput(bool wanted)
{
    if (mMirror) {
        mMirror->hiddenInput(wanted);
    }
    if (wanted == mHiddenInput) {
        return;
    }
    mHiddenInput = wanted;
    if (outputLive()) {
        sendFrame(hiddenInputMessage());
    }
}

void SocketBridge::secureOffer(bool offered)
{
    if (mMirror) {
        mMirror->secureOffer(offered);
    }
    if (offered == mSecureOffer) {
        return;
    }
    mSecureOffer = offered;
    if (outputLive()) {
        sendFrame(secureOfferMessage());
    }
}

void SocketBridge::scriptsStopped(bool stopped)
{
    if (mMirror) {
        mMirror->scriptsStopped(stopped);
    }
    if (stopped == mScriptsStopped) {
        return;
    }
    mScriptsStopped = stopped;
    if (outputLive()) {
        sendFrame(scriptsStoppedMessage());
    }
}

void SocketBridge::replayRecording(bool recording, const QString& file)
{
    if (mMirror) {
        mMirror->replayRecording(recording, file);
    }
    if (recording == mReplayRecording && file == mRecordedReplay) {
        return;
    }
    mReplayRecording = recording;
    mRecordedReplay = file;
    if (outputLive()) {
        sendFrame(replayRecordingMessage());
    }
}

void SocketBridge::replay(const Replay& replay)
{
    if (mMirror) {
        mMirror->replay(replay);
    }
    if (replay == mReplay) {
        return;
    }
    mReplay = replay;
    if (outputLive()) {
        sendFrame(replayMessage());
    }
}

void SocketBridge::certificateRefused(const std::optional<RefusedCertificate>& certificate)
{
    if (mMirror) {
        mMirror->certificateRefused(certificate);
    }
    if (certificate == mRefusedCertificate) {
        return;
    }
    mRefusedCertificate = certificate;
    if (outputLive()) {
        sendFrame(certificateMessage());
    }
}

void SocketBridge::history(const QStringList& lines)
{
    if (mMirror) {
        mMirror->history(lines);
    }
    if (outputLive()) {
        sendFrame(historyMessage(lines));
    }
}

// Not kept: a UI that attaches later keeps what it has on its line
void SocketBridge::commandLine(const QString& text)
{
    if (mMirror) {
        mMirror->commandLine(text);
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("commandLine")}, {QStringLiteral("text"), text}});
    }
}

// Not kept, as notify: a UI that attaches later has missed the moment it was for
void SocketBridge::announce(const QString& text, bool assertive)
{
    if (mMirror) {
        mMirror->announce(text, assertive);
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("announce")}, {QStringLiteral("text"), text}, {QStringLiteral("assertive"), assertive}});
    }
}

void SocketBridge::linksChanged(const QList<int>& ids)
{
    if (mMirror) {
        mMirror->linksChanged(ids);
    }
    if (ids.isEmpty()) {
        return;
    }
    if (outputLive()) {
        sendFrame(linksChangedMessage(ids));
    } else {
        mLinksMissed = true;
    }
}

// Not kept, as announce: the moment it asked for has passed for a UI that attaches later
void SocketBridge::attention(int ms)
{
    if (mMirror) {
        mMirror->attention(ms);
    }
    if (mControl) {
        mControl->profileChanged(this, ControlBridge::Change::attention);
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("attention")}, {QStringLiteral("ms"), ms}});
    }
}

// Not kept: Mudlet's copy of the line is already empty, and a UI that attaches later keeps what it has on its line
void SocketBridge::moveTypedLine()
{
    if (mMirror) {
        mMirror->moveTypedLine();
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("moveTypedLine")}});
    }
}

// Only what changed is sent, the windows keyed by name and kind and the labels and text boxes by name, each after the
// windows so that the UI knows its window first; a UI that attaches or drains gets them all
void SocketBridge::windows(const QList<ScriptWindowInfo>& windows, const QList<LabelInfo>& labels, const QList<TextBoxInfo>& textBoxes)
{
    if (mMirror) {
        mMirror->windows(windows, labels, textBoxes);
    }
    using Key = std::pair<QString, QString>;
    QHash<Key, const ScriptWindowInfo*> before;
    for (const ScriptWindowInfo& window : std::as_const(mWindows)) {
        before.insert({window.name, window.kind}, &window);
    }
    QJsonArray changed;
    for (const ScriptWindowInfo& window : windows) {
        const ScriptWindowInfo* was = before.take({window.name, window.kind});
        if (!was || !(*was == window)) {
            changed.append(windowJson(window));
        }
    }
    QJsonArray removed;
    for (const ScriptWindowInfo& window : std::as_const(mWindows)) {
        if (before.contains({window.name, window.kind})) {
            removed.append(QJsonObject{{QStringLiteral("name"), window.name}, {QStringLiteral("kind"), window.kind}});
        }
    }
    // Labels before text boxes, each after the windows frame that places it
    QList<QJsonObject> changedContent = changedByName(mLabels, labels, &SocketBridge::labelMessage);
    changedContent += changedByName(mTextBoxes, textBoxes, [this](const TextBoxInfo& textBox) {
        return textBoxMessage(textBox);
    });
    mWindows = windows;
    mLabels = labels;
    mTextBoxes = textBoxes;
    if (!outputLive()) {
        return;
    }
    if (!changed.isEmpty() || !removed.isEmpty()) {
        QJsonObject message{{QStringLiteral("t"), QStringLiteral("windows")}, {QStringLiteral("windows"), changed}};
        if (!removed.isEmpty()) {
            message.insert(QStringLiteral("removed"), removed);
        }
        sendFrame(message);
    }
    for (const QJsonObject& content : changedContent) {
        sendFrame(content);
    }
}

// What Mudlet kept of the player's text is kept here too, so it is not sent back; a refusal is followed by the box as
// Mudlet holds it, so the UI puts back what it showed the player. Taken or not, each counts in the box's typed from
// then on, before Mudlet can tell of the box
void SocketBridge::setTextBoxText(const QJsonObject& message)
{
    const QString name = message.value(QLatin1String("name")).toString();
    ++mTextBoxTyped[name];
    const QJsonValue value = message.value(QLatin1String("text"));
    QString text = value.toString();
    // Not taken as empty, which would clear what the player typed
    const QString problem = value.isString() ? mEngine->setTextBoxText(name, text) : QStringLiteral("text must be a string");
    const auto kept = std::ranges::find(mTextBoxes, name, &TextBoxInfo::name);
    if (problem.isEmpty()) {
        if (kept != mTextBoxes.end()) {
            kept->text = text;
        }
        return;
    }
    refuse(QStringLiteral("setTextBoxText"), problem);
    if (kept != mTextBoxes.end()) {
        sendFrame(textBoxMessage(*kept));
    }
}

// Not kept: a UI that attaches or drains gets every console's from IEngine::windowText()
void SocketBridge::windowText(const QString& window, const WindowText& text)
{
    if (mMirror) {
        mMirror->windowText(window, text);
    }
    if (outputLive()) {
        sendFrame(windowTextMessage(window, text));
    }
}

void SocketBridge::mainArea(const MainArea& area)
{
    if (mMirror) {
        mMirror->mainArea(area);
    }
    if (mMainArea == area) {
        return;
    }
    mMainArea = area;
    if (outputLive()) {
        sendFrame(mainAreaMessage(area));
    }
}

// Not kept, as commandLine is not
void SocketBridge::windowCommandLine(const QString& name, const QString& text)
{
    if (mMirror) {
        mMirror->windowCommandLine(name, text);
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("windowCommandLine")}, {QStringLiteral("name"), name}, {QStringLiteral("text"), text}});
    }
}

void SocketBridge::buttonBars(const QList<ButtonBar>& bars)
{
    if (mMirror) {
        mMirror->buttonBars(bars);
    }
    sendIfChanged(mButtonBars, buttonBarsMessage(bars));
}

void SocketBridge::commands(const QList<AddonCommandInfo>& commands)
{
    if (mMirror) {
        mMirror->commands(commands);
    }
    sendIfChanged(mCommands, commandsMessage(commands));
}

void SocketBridge::errorConsoleEntry(const ErrorConsoleEntry& entry)
{
    if (mMirror) {
        mMirror->errorConsoleEntry(entry);
    }
    if (outputLive()) {
        sendFrame(errorConsoleMessage({entry}, false));
    }
}

void SocketBridge::errorConsoleCleared()
{
    if (mMirror) {
        mMirror->errorConsoleCleared();
    }
    if (outputLive()) {
        sendFrame(errorConsoleMessage({}, true));
    }
}

void SocketBridge::keyBindings(const QList<KeyBinding>& bindings)
{
    if (mMirror) {
        mMirror->keyBindings(bindings);
    }
    sendIfChanged(mKeyBindings, keyBindingsMessage(bindings));
}

void SocketBridge::map(const std::optional<MapFrame>& frame)
{
    if (mMirror) {
        mMirror->map(frame);
    }
    if (frame && frame == mMap.frame) {
        return;
    }
    // The mapper's window going is told by windows, so nothing is sent for it
    QJsonObject message = frame ? mapMessage(*frame) : QJsonObject();
    if (message.isEmpty()) {
        mMap = {};
        return;
    }
    message.insert(QStringLiteral("t"), QStringLiteral("map"));
    mMap.frame = frame;
    sendIfChanged(mMap.message, std::move(message));
}

// Sent with nothing to draw too, as only this tells the UI to take down what it drew
void SocketBridge::appMap(const std::optional<MapFrame>& frame)
{
    if (mMirror) {
        mMirror->appMap(frame);
    }
    if (frame && frame == mAppMap.frame) {
        return;
    }
    QJsonObject message = frame ? mapMessage(*frame) : QJsonObject();
    mAppMap.frame = frame;
    if (message.isEmpty()) {
        message = mapMessage(MapFrame{{}, QSize(0, 0), true});
    }
    message.insert(QStringLiteral("t"), QStringLiteral("appMap"));
    sendIfChanged(mAppMap.message, std::move(message));
}

void SocketBridge::mapDownload(const MapDownload& download)
{
    if (mMirror) {
        mMirror->mapDownload(download);
    }
    sendIfChanged(mMapDownload,
                  {{QStringLiteral("t"), QStringLiteral("mapDownload")},
                   {QStringLiteral("offered"), download.offered},
                   {QStringLiteral("host"), download.host},
                   {QStringLiteral("state"), wireName(download.state)},
                   {QStringLiteral("label"), download.label},
                   {QStringLiteral("value"), download.value},
                   {QStringLiteral("maximum"), download.maximum},
                   {QStringLiteral("cancelable"), download.cancelable},
                   {QStringLiteral("error"), download.error}});
}

void SocketBridge::mapFile(const MapFile& file)
{
    if (mMirror) {
        mMirror->mapFile(file);
    }
    sendFrame({{QStringLiteral("t"), QStringLiteral("mapFile")},
               {QStringLiteral("op"), wireName(file.operation)},
               {QStringLiteral("state"), wireName(file.state)},
               {QStringLiteral("label"), file.label},
               {QStringLiteral("value"), file.value},
               {QStringLiteral("maximum"), file.maximum},
               {QStringLiteral("cancelable"), file.cancelable},
               {QStringLiteral("error"), file.error}});
    // Sent while Mudlet holds the main thread, which would otherwise write it only once the load or save has ended
    if (mClient) {
        mClient->flush();
    }
}

void SocketBridge::sounds(const Sounds& sounds)
{
    if (mMirror) {
        mMirror->sounds(sounds);
    }
    sendIfChanged(mSounds,
                  {{QStringLiteral("t"), QStringLiteral("sounds")},
                   {QStringLiteral("played"), sounds.played},
                   {QStringLiteral("scriptsMuted"), sounds.scriptsMuted},
                   {QStringLiteral("gameMuted"), sounds.gameMuted}});
}

QJsonObject SocketBridge::mapMessage(const MapFrame& frame)
{
    ++sMapMessagesMade;
    const QByteArray base64 = frame.png.toBase64();
    const QJsonObject view = frame.view ? mapViewMessage(*frame.view) : QJsonObject();
    const QJsonObject info = frame.info.lines.isEmpty() ? QJsonObject() : mapInfoMessage(frame.info);
    // The rooms' names can add tens of kilobytes to the PNG, and a script's map info any length
    const auto bytes = [](const QJsonObject& part) {
        return part.isEmpty() ? 0 : QJsonDocument(part).toJson(QJsonDocument::Compact).size();
    };
    if (base64.size() + bytes(view) + bytes(info) + 1024 > qsizetype(maxFrameBytes)) {
        qWarning("SocketBridge: a map at %dx%d as a PNG, with its view and info, is too large for a frame", frame.size.width(), frame.size.height());
        return {};
    }
    QJsonObject message{
            {QStringLiteral("png"), QString::fromLatin1(base64)}, {QStringLiteral("w"), frame.size.width()}, {QStringLiteral("h"), frame.size.height()}, {QStringLiteral("viewOnly"), frame.viewOnly}};
    if (!view.isEmpty()) {
        message.insert(QStringLiteral("view"), view);
    }
    if (!info.isEmpty()) {
        message.insert(QStringLiteral("info"), info);
    }
    return message;
}

QJsonObject SocketBridge::mapInfoMessage(const MapInfo& info)
{
    QJsonArray lines;
    for (const MapInfoLine& line : info.lines) {
        lines.append(QJsonObject{{QStringLiteral("text"), line.text}, {QStringLiteral("bold"), line.bold}, {QStringLiteral("italic"), line.italic}, {QStringLiteral("color"), line.color}});
    }
    return {{QStringLiteral("lines"), lines}, {QStringLiteral("bg"), info.background}, {QStringLiteral("bgAlpha"), info.backgroundAlpha}};
}

QJsonObject SocketBridge::mapViewMessage(const MapViewInfo& view)
{
    QJsonArray rooms;
    for (const MapRoomPlace& room : view.rooms) {
        rooms.append(QJsonObject{{QStringLiteral("id"), room.id}, {QStringLiteral("x"), room.position.x()}, {QStringLiteral("y"), room.position.y()}, {QStringLiteral("name"), room.name}});
    }
    QJsonArray levels;
    for (const int level : view.levels) {
        levels.append(level);
    }
    return {{QStringLiteral("area"), view.area},
            {QStringLiteral("areaName"), view.areaName},
            {QStringLiteral("level"), view.level},
            {QStringLiteral("zoom"), view.zoom},
            {QStringLiteral("minZoom"), view.minZoom},
            {QStringLiteral("maxZoom"), view.maxZoom},
            {QStringLiteral("following"), view.following},
            {QStringLiteral("player"), view.playerRoom},
            {QStringLiteral("roomSize"), view.roomSize},
            {QStringLiteral("moved"), view.moved},
            {QStringLiteral("rooms"), rooms},
            {QStringLiteral("levels"), levels}};
}

void SocketBridge::sendIfChanged(QJsonObject& kept, QJsonObject message)
{
    if (message == kept) {
        return;
    }
    kept = std::move(message);
    if (outputLive()) {
        sendFrame(kept);
    }
}

void SocketBridge::packagesChanged()
{
    if (mMirror) {
        mMirror->packagesChanged();
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("packagesChanged")}});
    }
}

void SocketBridge::packageInstalled(const QString& path, PackageInstallResult result, const QString& error)
{
    if (mMirror) {
        mMirror->packageInstalled(path, result, error);
    }
    if (outputLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("packageInstalled")}, {QStringLiteral("path"), path}, {QStringLiteral("result"), wireName(result)}, {QStringLiteral("error"), error}});
    }
}
