#include "SocketBridge.h"

#include "IEngine.h"

#include <QCoreApplication>
#include <QDateTime>
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

// A JSON number that is a whole number an int holds
std::optional<int> wholeNumber(const QJsonValue& value)
{
    const double number = value.toDouble();
    if (!value.isDouble() || number != std::floor(number) || std::abs(number) > std::numeric_limits<int>::max()) {
        return std::nullopt;
    }
    return int(number);
}

} // namespace

SocketBridge::SocketBridge(QObject* parent)
: QObject(parent)
, mServer(new QLocalServer(this))
, mInstance(QStringLiteral("%1-%2").arg(QCoreApplication::applicationPid()).arg(QDateTime::currentMSecsSinceEpoch()))
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
            }
            socket->deleteLater();
        });
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
    mClient->deleteLater();
    mClient = nullptr;
    mClientGreeted = false;
}

void SocketBridge::onReadyRead()
{
    if (!mClient) {
        return;
    }
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

void SocketBridge::onBytesWritten()
{
    if (!mClient || !mClientLagging || mClient->bytesToWrite() > lowWaterBytes) {
        return;
    }
    mClientLagging = false;
    sendState();
    sendLinesAfter(mLastSentSeq, false);
    if (!mClientLagging) {
        sendFrame(tailMessage());
    }
}

void SocketBridge::greet(const QJsonObject& hello)
{
    mClientGreeted = true;
    // A UI that was only detached says what it already has, so it gets just the rest
    qint64 after = 0;
    if (hello.value(QLatin1String("instance")).toString() == mInstance) {
        after = std::clamp(qint64(hello.value(QLatin1String("lastSeq")).toDouble()), qint64(0), mNextSeq - 1);
    }
    mLastSentSeq = after;
    sendFrame({{QStringLiteral("t"), QStringLiteral("hello")},
               {QStringLiteral("v"), protocolVersion},
               {QStringLiteral("pid"), qint64(QCoreApplication::applicationPid())},
               {QStringLiteral("instance"), mInstance}});
    sendState();
    sendLinesAfter(after, true);
    sendFrame(tailMessage());
    sendFrame({{QStringLiteral("t"), QStringLiteral("synced")}, {QStringLiteral("seq"), mNextSeq - 1}});
}

void SocketBridge::sendState()
{
    for (auto it = mStatus.cbegin(); it != mStatus.cend(); ++it) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), it.key()}, {QStringLiteral("value"), it.value()}});
    }
    sendFrame(hiddenInputMessage());
    if (mEngine) {
        sendFrame(historyMessage(mEngine->history()));
    }
    if (mMainArea) {
        sendFrame(mainAreaMessage(*mMainArea));
    }
    QJsonArray windows;
    for (const ScriptWindowInfo& window : std::as_const(mWindows)) {
        windows.append(windowJson(window));
    }
    sendFrame({{QStringLiteral("t"), QStringLiteral("windows")}, {QStringLiteral("full"), true}, {QStringLiteral("windows"), windows}});
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
        sendFrame(lineMessage(stored, replay));
        mLastSentSeq = stored.seq;
        if (mClientLagging) {
            return;
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

    if (!mEngine) {
        return;
    }
    mCurrentRequestId = message.value(QLatin1String("id"));
    if (type == QLatin1String("ping")) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("pong")}, {QStringLiteral("id"), mCurrentRequestId}});
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
    } else if (type == QLatin1String("setViewWidthPx")) {
        mEngine->setViewWidthPx(message.value(QLatin1String("px")).toInt());
    } else if (type == QLatin1String("listProfiles")) {
        sendProfiles();
    } else if (type == QLatin1String("saveProfile")) {
        answerProfiles(type, saveProfile(message));
    } else if (type == QLatin1String("deleteProfile")) {
        answerProfiles(type, mEngine->deleteProfile(message.value(QLatin1String("name")).toString()));
    } else if (type == QLatin1String("openProfile")) {
        answerProfiles(type, openProfile(message));
    } else if (type == QLatin1String("setMainWindowSize")) {
        refuse(type, setMainWindowSize(message));
    } else if (type == QLatin1String("getWindowText")) {
        getWindowText(message);
    } else if (type == QLatin1String("placeUserWindow")) {
        refuse(type, placeUserWindow(message));
    } else if (type == QLatin1String("closeUserWindow")) {
        refuse(type, mEngine->closeUserWindow(message.value(QLatin1String("name")).toString()));
    } else if (type == QLatin1String("sendWindowCommandLine")) {
        refuse(type, mEngine->sendWindowCommandLine(message.value(QLatin1String("name")).toString(), message.value(QLatin1String("text")).toString()));
    } else if (type == QLatin1String("pointer")) {
        refuse(type, pointer(message));
    } else if (type == QLatin1String("getImage")) {
        getImage(message);
    } else {
        sendError(QStringLiteral("unknown type %1").arg(type));
    }
    mCurrentRequestId = QJsonValue();
}

// Checked here rather than taken with defaults, as a profile stored with a wrong field would only fail later, at its connect
QString SocketBridge::saveProfile(const QJsonObject& message)
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
    return mEngine->saveProfile({message.value(QLatin1String("name")).toString(), host.toString().trimmed(), int(portNumber), tls.toBool(), login.toString().trimmed(), autoReconnect.toBool()});
}

QString SocketBridge::openProfile(const QJsonObject& message)
{
    const QJsonValue password = message.value(QLatin1String("password"));
    const QJsonValue connectNow = message.value(QLatin1String("connect"));
    if (!password.isUndefined() && !password.isNull() && !password.isString()) {
        return QStringLiteral("password must be a string");
    }
    if (!connectNow.isUndefined() && !connectNow.isBool()) {
        return QStringLiteral("connect must be true or false");
    }
    return mEngine->openProfile(message.value(QLatin1String("name")).toString(), password.toString(), connectNow.toBool(true));
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

QString SocketBridge::setMainWindowSize(const QJsonObject& message)
{
    const std::optional<int> width = wholeNumber(message.value(QLatin1String("w")));
    const std::optional<int> height = wholeNumber(message.value(QLatin1String("h")));
    if (!width || !height || *width < 1 || *height < 1) {
        return QStringLiteral("w and h must be whole numbers above 0");
    }
    return mEngine->setMainWindowSize(*width, *height);
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
    // Absent is 0; the buttons and modifiers are Qt's flags, so never negative
    const std::pair<QJsonValue, int*> optional[] = {
            {message.value(QLatin1String("button")), &event.button},
            {message.value(QLatin1String("buttons")), &event.buttons},
            {message.value(QLatin1String("modifiers")), &event.modifiers},
            {message.value(QLatin1String("wheelY")), &event.wheelY},
    };
    for (const auto& [value, target] : optional) {
        if (value.isUndefined()) {
            continue;
        }
        const std::optional<int> number = wholeNumber(value);
        if (!number || (target != &event.wheelY && *number < 0)) {
            return QStringLiteral("button, buttons and modifiers must be whole numbers from 0, wheelY a whole number");
        }
        *target = *number;
    }
    return mEngine->pointer(message.value(QLatin1String("window")).toString(), event);
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
            sendError(QStringLiteral("getImage: frame must be a number"));
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

void SocketBridge::answerProfiles(const QString& request, const QString& problem)
{
    if (problem.isEmpty()) {
        sendProfiles();
    } else {
        sendError(QStringLiteral("%1: %2").arg(request, problem));
    }
}

void SocketBridge::sendProfiles()
{
    QJsonArray list;
    for (const Profile& profile : mEngine->profiles()) {
        list.append(QJsonObject{{QStringLiteral("name"), profile.name},
                                {QStringLiteral("host"), profile.host},
                                {QStringLiteral("port"), profile.port},
                                {QStringLiteral("tls"), profile.tls},
                                {QStringLiteral("login"), profile.login},
                                {QStringLiteral("autoReconnect"), profile.autoReconnect}});
    }
    sendReply({{QStringLiteral("t"), QStringLiteral("profiles")}, {QStringLiteral("profiles"), list}});
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

QJsonObject SocketBridge::lineMessage(const StoredLine& stored, bool replay)
{
    QJsonObject message{{QStringLiteral("t"), QStringLiteral("line")},
                        {QStringLiteral("seq"), stored.seq},
                        {QStringLiteral("text"), stored.text},
                        {QStringLiteral("runs"), runsJson(stored.runs)},
                        {QStringLiteral("prompt"), stored.prompt}};
    if (replay) {
        message.insert(QStringLiteral("replay"), true);
    }
    return message;
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

QJsonObject SocketBridge::tailMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("tail")}, {QStringLiteral("text"), mTail.text}, {QStringLiteral("runs"), runsJson(mTail.runs)}, {QStringLiteral("prompt"), mTail.prompt}};
}

QJsonObject SocketBridge::hiddenInputMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("hiddenInput")}, {QStringLiteral("wanted"), mHiddenInput}};
}

QJsonObject SocketBridge::historyMessage(const QStringList& lines)
{
    return {{QStringLiteral("t"), QStringLiteral("history")}, {QStringLiteral("lines"), QJsonArray::fromStringList(lines)}};
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
    return json;
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
    return {{QStringLiteral("t"), QStringLiteral("mainArea")},
            {QStringLiteral("w"), area.size.width()},
            {QStringLiteral("h"), area.size.height()},
            {QStringLiteral("columns"), area.grid.width()},
            {QStringLiteral("rows"), area.grid.height()},
            {QStringLiteral("textX"), area.textRect.x()},
            {QStringLiteral("textY"), area.textRect.y()},
            {QStringLiteral("textW"), area.textRect.width()},
            {QStringLiteral("textH"), area.textRect.height()}};
}

void SocketBridge::sendFrame(const QJsonObject& message)
{
    if (!mClient || mClient->state() != QLocalSocket::ConnectedState) {
        return;
    }
    const QByteArray payload = QJsonDocument(message).toJson(QJsonDocument::Compact);
    char header[4];
    qToBigEndian<quint32>(quint32(payload.size()), header);
    mClient->write(header, 4);
    mClient->write(payload);
    if (mClient->bytesToWrite() > highWaterBytes) {
        mClientLagging = true;
    }
}

void SocketBridge::line(const QString& text, const QList<ColorRun>& runs, bool isPrompt)
{
    mScrollback.push_back({mNextSeq++, text, runs, isPrompt});
    if (qsizetype(mScrollback.size()) > scrollbackLines) {
        mScrollback.pop_front();
    }
    if (clientLive()) {
        sendFrame(lineMessage(mScrollback.back(), false));
        mLastSentSeq = mScrollback.back().seq;
    }
}

void SocketBridge::tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt)
{
    mTail = {0, text, runs, isPrompt};
    if (clientLive()) {
        sendFrame(tailMessage());
    }
}

void SocketBridge::cleared()
{
    mScrollback.clear();
    mClearedAfterSeq = mNextSeq - 1;
    mTail = {0, {}, {}, false};
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("clear")}});
    }
}

void SocketBridge::notify(const QString& title, const QString& text)
{
#ifdef Q_OS_ANDROID
    // Posted here, not in the UI, so tells still arrive while the UI process is frozen or dead
    AndroidNotifier::postTell(title, text);
#endif
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("notify")}, {QStringLiteral("title"), title}, {QStringLiteral("text"), text}});
    }
}

void SocketBridge::status(const QString& key, const QString& value)
{
    mStatus.insert(key, value);
    // A reply is sent even to a lagging UI, which would otherwise wait for it forever
    if (clientLive() || (replying() && mClient && mClientGreeted)) {
        sendReply({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), key}, {QStringLiteral("value"), value}});
    }
}

void SocketBridge::hiddenInput(bool wanted)
{
    if (wanted == mHiddenInput) {
        return;
    }
    mHiddenInput = wanted;
    if (clientLive()) {
        sendFrame(hiddenInputMessage());
    }
}

void SocketBridge::history(const QStringList& lines)
{
    if (clientLive()) {
        sendFrame(historyMessage(lines));
    }
}

// Not kept: a UI that attaches later keeps what it has on its line
void SocketBridge::commandLine(const QString& text)
{
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("commandLine")}, {QStringLiteral("text"), text}});
    }
}

// Only what changed is sent, the windows keyed by name and kind and the labels by name, each label after the windows
// so that the UI knows its window first; a UI that attaches or drains gets them all
void SocketBridge::windows(const QList<ScriptWindowInfo>& windows, const QList<LabelInfo>& labels)
{
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
    QHash<QString, const LabelInfo*> labelsBefore;
    for (const LabelInfo& label : std::as_const(mLabels)) {
        labelsBefore.insert(label.name, &label);
    }
    QList<QJsonObject> changedLabels;
    for (const LabelInfo& label : labels) {
        const LabelInfo* was = labelsBefore.value(label.name);
        if (!was || *was != label) {
            changedLabels.append(labelMessage(label));
        }
    }
    mWindows = windows;
    mLabels = labels;
    if (!clientLive()) {
        return;
    }
    if (!changed.isEmpty() || !removed.isEmpty()) {
        QJsonObject message{{QStringLiteral("t"), QStringLiteral("windows")}, {QStringLiteral("windows"), changed}};
        if (!removed.isEmpty()) {
            message.insert(QStringLiteral("removed"), removed);
        }
        sendFrame(message);
    }
    for (const QJsonObject& label : std::as_const(changedLabels)) {
        sendFrame(label);
    }
}

// Not kept: a UI that attaches or drains gets every console's from IEngine::windowText()
void SocketBridge::windowText(const QString& window, const WindowText& text)
{
    if (clientLive()) {
        sendFrame(windowTextMessage(window, text));
    }
}

void SocketBridge::mainArea(const MainArea& area)
{
    if (mMainArea == area) {
        return;
    }
    mMainArea = area;
    if (clientLive()) {
        sendFrame(mainAreaMessage(area));
    }
}

// Not kept, as commandLine is not
void SocketBridge::windowCommandLine(const QString& name, const QString& text)
{
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("windowCommandLine")}, {QStringLiteral("name"), name}, {QStringLiteral("text"), text}});
    }
}
