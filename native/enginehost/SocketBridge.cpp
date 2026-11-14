#include "SocketBridge.h"

#include "IEngine.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLocalServer>
#include <QLocalSocket>
#include <QtEndian>

#include <cmath>
#include <limits>
#include <utility>

#include <sys/socket.h>
#include <unistd.h>

#ifdef Q_OS_ANDROID
#include "AndroidNotifier.h"
#endif

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
    if (std::exchange(mLinksMissed, false)) {
        sendFrame(linksChangedMessage({}));
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
    } else if (type == QLatin1String("setScreenSize")) {
        setScreenSize(message);
    } else if (type == QLatin1String("getLink")) {
        getLink(message);
    } else if (type == QLatin1String("followLink")) {
        followLink(message);
    } else if (type == QLatin1String("setAppState")) {
        setAppState(message);
    } else if (type == QLatin1String("listProfiles")) {
        sendProfiles();
    } else if (type == QLatin1String("saveProfile")) {
        answerProfiles(type, saveProfile(message));
    } else if (type == QLatin1String("deleteProfile")) {
        answerProfiles(type, mEngine->deleteProfile(message.value(QLatin1String("name")).toString()));
    } else if (type == QLatin1String("openProfile")) {
        answerProfiles(type, openProfile(message));
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

void SocketBridge::setScreenSize(const QJsonObject& message)
{
    const double columns = message.value(QLatin1String("columns")).toDouble(-1);
    const double rows = message.value(QLatin1String("rows")).toDouble(-1);
    // NAWS carries each in 16 bits
    const auto fits = [](double n) {
        return n == std::floor(n) && n >= 1 && n <= 65535;
    };
    if (!fits(columns) || !fits(rows)) {
        sendError(QStringLiteral("setScreenSize: columns and rows must be whole numbers from 1 to 65535"));
        return;
    }
    mEngine->setScreenSize(int(columns), int(rows));
}

namespace {

std::optional<int> wholeNumber(const QJsonValue& value, int least)
{
    const double n = value.toDouble(-1);
    if (!value.isDouble() || n != std::floor(n) || n < least || n > std::numeric_limits<int>::max()) {
        return std::nullopt;
    }
    return int(n);
}

} // namespace

void SocketBridge::getLink(const QJsonObject& message)
{
    const std::optional<int> id = wholeNumber(message.value(QLatin1String("link")), 1);
    if (!id) {
        sendError(QStringLiteral("getLink: link must be a whole number from 1"));
        return;
    }
    Link link;
    if (const QString problem = mEngine->link(*id, link); !problem.isEmpty()) {
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
    sendReply(reply);
}

void SocketBridge::followLink(const QJsonObject& message)
{
    const std::optional<int> id = wholeNumber(message.value(QLatin1String("link")), 1);
    const QJsonValue itemValue = message.value(QLatin1String("item"));
    const std::optional<int> item = wholeNumber(itemValue, 0);
    if (!id) {
        sendError(QStringLiteral("followLink: link must be a whole number from 1"));
        return;
    }
    if (!itemValue.isUndefined() && !item) {
        sendError(QStringLiteral("followLink: item must be a whole number from 0"));
        return;
    }
    if (const QString problem = mEngine->followLink(*id, item); !problem.isEmpty()) {
        sendError(QStringLiteral("followLink: %1").arg(problem));
    }
}

void SocketBridge::setAppState(const QJsonObject& message)
{
    const QJsonValue active = message.value(QLatin1String("active"));
    const QJsonValue minimized = message.value(QLatin1String("minimized"));
    if (!active.isBool() || !minimized.isBool()) {
        sendError(QStringLiteral("setAppState: active and minimized must be true or false"));
        return;
    }
    mEngine->setAppState(active.toBool(), minimized.toBool());
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

QJsonObject SocketBridge::linksChangedMessage(const QList<int>& ids)
{
    QJsonArray json;
    for (const int id : ids) {
        json.append(id);
    }
    return {{QStringLiteral("t"), QStringLiteral("linksChanged")}, {QStringLiteral("ids"), json}};
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

// Not kept, as notify: a UI that attaches later has missed the moment it was for
void SocketBridge::announce(const QString& text, bool assertive)
{
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("announce")}, {QStringLiteral("text"), text}, {QStringLiteral("assertive"), assertive}});
    }
}

void SocketBridge::linksChanged(const QList<int>& ids)
{
    if (ids.isEmpty()) {
        return;
    }
    if (clientLive()) {
        sendFrame(linksChangedMessage(ids));
    } else {
        mLinksMissed = true;
    }
}

// Not kept, as announce: the moment it asked for has passed for a UI that attaches later
void SocketBridge::attention(int ms)
{
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("attention")}, {QStringLiteral("ms"), ms}});
    }
}

// Not kept: Mudlet's copy of the line is already empty, and a UI that attaches later keeps what it has on its line
void SocketBridge::moveTypedLine()
{
    if (clientLive()) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("moveTypedLine")}});
    }
}
