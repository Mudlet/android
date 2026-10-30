#include "Engine.h"

#include <QDateTime>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QTcpSocket>

namespace {
constexpr int kPromptSilenceMs = 500;
constexpr int kDefaultHeartbeatMs = 30000;
constexpr int kCountersCoalesceMs = 200;
} // namespace

Engine::Engine(IFrontend* frontend, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
, mSocket(new QTcpSocket(this))
{
    resetTelnet();

    mLua.onNotify = [this](const QString& title, const QString& text) {
        ++mNotifications;
        mFrontend->notify(title, text);
        scheduleCounters();
    };
    mLua.onPrint = [this](const QString& text) { mFrontend->status(QStringLiteral("lua.print"), text); };

    mPromptTimer.setSingleShot(true);
    mPromptTimer.setInterval(kPromptSilenceMs);
    connect(&mPromptTimer, &QTimer::timeout, this, &Engine::flushPromptCandidate);

    mCountersTimer.setSingleShot(true);
    mCountersTimer.setInterval(kCountersCoalesceMs);
    connect(&mCountersTimer, &QTimer::timeout, this, [this]() {
        mFrontend->status(QStringLiteral("counters"),
                          QStringLiteral("lines=%1 lua=%2 notify=%3").arg(mLines).arg(mLuaCalls).arg(mNotifications));
    });

    mHeartbeatTimer.setInterval(kDefaultHeartbeatMs);
    mHeartbeatTimer.setTimerType(Qt::CoarseTimer);
    connect(&mHeartbeatTimer, &QTimer::timeout, this, &Engine::heartbeat);

    connect(mSocket, &QTcpSocket::readyRead, this, &Engine::onReadyRead);
    connect(mSocket, &QTcpSocket::stateChanged, this, &Engine::onSocketState);
    connect(mSocket, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        mFrontend->status(QStringLiteral("conn"), QStringLiteral("error: %1").arg(mSocket->errorString()));
    });
}

Engine::~Engine() = default;

void Engine::resetTelnet()
{
    mTelnet = TelnetDecoder{};
    mTelnet.onData = [this](const QByteArray& bytes) { onBytes(bytes); };
    mTelnet.onReply = [this](const QByteArray& reply) { mSocket->write(reply); };
    mTelnet.onGoAhead = [this]() { flushPromptCandidate(); };
}

void Engine::setHeartbeatIntervalMs(int ms)
{
    mHeartbeatTimer.setInterval(ms);
}

void Engine::start()
{
    reportFont();
    mLastBeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeatTimer.start();
    mFrontend->status(QStringLiteral("engine"), QStringLiteral("ready"));
    scheduleCounters();
}

void Engine::reportFont()
{
    if (!qobject_cast<QGuiApplication*>(QCoreApplication::instance())) {
        mFrontend->status(QStringLiteral("font.error"), QStringLiteral("no QGuiApplication"));
        return;
    }
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    const QFontMetrics metrics(font);
    mAdvanceM = metrics.horizontalAdvance(QLatin1Char('M'));
    mFrontend->status(QStringLiteral("font.family"), QFontInfo(font).family());
    mFrontend->status(QStringLiteral("font.advanceM"), QString::number(mAdvanceM));
    setViewWidthPx(mViewWidthPx);
}

void Engine::setViewWidthPx(int px)
{
    mViewWidthPx = px;
    const int columns = (mAdvanceM > 0 && px > 0) ? px / mAdvanceM : 0;
    mFrontend->status(QStringLiteral("columns"), QStringLiteral("%1 (width %2px)").arg(columns).arg(px));
}

void Engine::connectTo(const QString& host, int port)
{
    disconnectFromGame();
    resetTelnet();
    mAnsi.reset();
    mPending.clear();
    mFrontend->status(QStringLiteral("conn"), QStringLiteral("connecting %1:%2").arg(host).arg(port));
    mSocket->connectToHost(host, quint16(port));
}

void Engine::send(const QString& line)
{
    if (mSocket->state() != QAbstractSocket::ConnectedState) {
        mFrontend->status(QStringLiteral("conn"), QStringLiteral("not connected"));
        return;
    }
    mSocket->write(TelnetDecoder::escapeOutgoing(line.toUtf8()) + "\r\n");
}

void Engine::disconnectFromGame()
{
    if (mSocket->state() != QAbstractSocket::UnconnectedState) {
        mSocket->abort();
    }
    mPromptTimer.stop();
}

void Engine::runLua(const QString& code)
{
    QString result;
    const QString error = mLua.run(code, &result);
    if (error.isEmpty()) {
        mFrontend->status(QStringLiteral("lua.result"), result);
    } else {
        mFrontend->status(QStringLiteral("lua.error"), error);
    }
}

void Engine::onReadyRead()
{
    mTelnet.feed(mSocket->readAll());
}

void Engine::onSocketState(QAbstractSocket::SocketState state)
{
    switch (state) {
    case QAbstractSocket::ConnectedState:
        mFrontend->status(QStringLiteral("conn"), QStringLiteral("connected %1:%2").arg(mSocket->peerName()).arg(mSocket->peerPort()));
        break;
    case QAbstractSocket::UnconnectedState:
        flushPromptCandidate();
        mFrontend->status(QStringLiteral("conn"), QStringLiteral("disconnected"));
        break;
    default:
        break;
    }
}

void Engine::onBytes(const QByteArray& bytes)
{
    mPending.append(bytes);
    qsizetype nl;
    while ((nl = mPending.indexOf('\n')) >= 0) {
        QByteArray raw = mPending.left(nl);
        mPending.remove(0, nl + 1);
        emitLine(raw, false);
    }
    if (mPending.isEmpty()) {
        mPromptTimer.stop();
    } else {
        mPromptTimer.start();
    }
}

void Engine::flushPromptCandidate()
{
    mPromptTimer.stop();
    if (!mPending.isEmpty()) {
        emitLine(std::exchange(mPending, {}), true);
    }
}

void Engine::emitLine(const QByteArray& raw, bool isPrompt)
{
    QByteArray cleaned = raw;
    cleaned.replace('\r', QByteArray());
    const AnsiParser::Result parsed = mAnsi.parse(QString::fromUtf8(cleaned));
    ++mLines;
    mFrontend->line(parsed.text, parsed.runs, isPrompt);
    ++mLuaCalls;
    const QString error = mLua.callOnLine(parsed.text);
    if (!error.isEmpty()) {
        mFrontend->status(QStringLiteral("lua.error"), error);
    }
    scheduleCounters();
}

void Engine::scheduleCounters()
{
    if (!mCountersTimer.isActive()) {
        mCountersTimer.start();
    }
}

void Engine::heartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    mLastGapMs = now - mLastBeatMs;
    mLastBeatMs = now;
    mMaxGapMs = std::max(mMaxGapMs, mLastGapMs);
    ++mHeartbeatTicks;
    mFrontend->status(QStringLiteral("heartbeat"),
                      QStringLiteral("ticks=%1 lastGapMs=%2 maxGapMs=%3 at=%4")
                              .arg(mHeartbeatTicks)
                              .arg(mLastGapMs)
                              .arg(mMaxGapMs)
                              .arg(QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss"))));
}
