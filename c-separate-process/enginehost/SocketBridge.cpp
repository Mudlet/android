#include "SocketBridge.h"

#include "IEngine.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLocalServer>
#include <QLocalSocket>
#include <QtEndian>

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
    sendAllStatus();
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
    sendAllStatus();
    sendLinesAfter(after, true);
    sendFrame(tailMessage());
    sendFrame({{QStringLiteral("t"), QStringLiteral("synced")}, {QStringLiteral("seq"), mNextSeq - 1}});
}

void SocketBridge::sendAllStatus()
{
    for (auto it = mStatus.cbegin(); it != mStatus.cend(); ++it) {
        sendFrame({{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), it.key()}, {QStringLiteral("value"), it.value()}});
    }
}

void SocketBridge::sendLinesAfter(qint64 seq, bool replay)
{
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
            sendFrame({{QStringLiteral("t"), QStringLiteral("error")},
                       {QStringLiteral("message"), QStringLiteral("expected hello with v=%1").arg(protocolVersion)}});
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
        mEngine->connectTo(message.value(QLatin1String("host")).toString(), quint16(message.value(QLatin1String("port")).toInt(23)));
    } else if (type == QLatin1String("send")) {
        mEngine->send(message.value(QLatin1String("text")).toString());
    } else if (type == QLatin1String("disconnect")) {
        mEngine->disconnectFromGame();
    } else if (type == QLatin1String("runLua")) {
        mEngine->runLua(message.value(QLatin1String("code")).toString());
    } else if (type == QLatin1String("setViewWidthPx")) {
        mEngine->setViewWidthPx(message.value(QLatin1String("px")).toInt());
    } else {
        sendFrame({{QStringLiteral("t"), QStringLiteral("error")}, {QStringLiteral("message"), QStringLiteral("unknown type %1").arg(type)}});
    }
    mCurrentRequestId = QJsonValue();
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

// [start, length, fg], with bg and attributes only when a run has them
QJsonArray SocketBridge::runsJson(const QList<ColorRun>& runs)
{
    QJsonArray json;
    for (const ColorRun& run : runs) {
        if (run.bg == -1 && run.attributes == 0) {
            json.append(QJsonArray{run.start, run.length, run.fg});
        } else {
            json.append(QJsonArray{run.start, run.length, run.fg, run.bg, run.attributes});
        }
    }
    return json;
}

QJsonObject SocketBridge::tailMessage() const
{
    return {{QStringLiteral("t"), QStringLiteral("tail")},
            {QStringLiteral("text"), mTail.text},
            {QStringLiteral("runs"), runsJson(mTail.runs)},
            {QStringLiteral("prompt"), mTail.prompt}};
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
    const bool isReply = !mCurrentRequestId.isUndefined() && !mCurrentRequestId.isNull();
    // A reply is sent even to a lagging UI, which would otherwise wait for it forever
    if (clientLive() || (isReply && mClient && mClientGreeted)) {
        QJsonObject message{{QStringLiteral("t"), QStringLiteral("status")}, {QStringLiteral("key"), key}, {QStringLiteral("value"), value}};
        if (isReply) {
            message.insert(QStringLiteral("re"), mCurrentRequestId);
        }
        sendFrame(message);
    }
}
