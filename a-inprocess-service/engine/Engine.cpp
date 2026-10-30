#include "Engine.h"

#include <QDateTime>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QTcpSocket>

extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

namespace {
constexpr int cPromptSilenceMs = 500;
constexpr int cHeartbeatMs = 30000;

const char* const cDefaultScript = R"lua(
lineCount = 0
function onLine(text)
  lineCount = lineCount + 1
  if string.find(text, "tells you", 1, true) then
    notify("Tell", text)
  end
end
)lua";
} // namespace

Engine::Engine(IFrontend* frontend, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
{
    mTelnet.onByte = [this](char ch) { onByte(ch); };
    mTelnet.onGoAhead = [this]() {
        mPromptTimer.stop();
        if (!mLineBuffer.isEmpty()) {
            flushLine(true);
        }
    };
    mTelnet.onReply = [this](const QByteArray& reply) {
        if (mSocket) {
            mSocket->write(reply);
        }
    };

    mPromptTimer.setSingleShot(true);
    mPromptTimer.setInterval(cPromptSilenceMs);
    connect(&mPromptTimer, &QTimer::timeout, this, [this]() {
        if (!mLineBuffer.isEmpty()) {
            flushLine(true);
        }
    });

    mHeartbeat.setInterval(cHeartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &Engine::onHeartbeat);

    initLua();
}

Engine::~Engine()
{
    if (mLua) {
        lua_close(mLua);
    }
}

void Engine::start()
{
    if (!qobject_cast<QGuiApplication*>(QCoreApplication::instance())) {
        mFrontend->status(QStringLiteral("font.error"), QStringLiteral("no QGuiApplication"));
    } else {
        QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
        font.setPixelSize(14);
        const QFontMetrics metrics(font);
        mAdvanceM = metrics.horizontalAdvance(QLatin1Char('M'));
        mFrontend->status(QStringLiteral("font.family"), QFontInfo(font).family());
        mFrontend->status(QStringLiteral("font.requested"), font.family());
        mFrontend->status(QStringLiteral("font.advanceM"), QString::number(mAdvanceM));
        mFrontend->status(QStringLiteral("qpa.platform"), QGuiApplication::platformName());
        reportColumns();
    }

    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.start();
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("idle"));
    reportCounters();
}

void Engine::setViewWidthPx(int px)
{
    mViewWidthPx = px;
    reportColumns();
}

void Engine::reportColumns()
{
    if (mAdvanceM > 0 && mViewWidthPx > 0) {
        mFrontend->status(QStringLiteral("font.columns"), QString::number(mViewWidthPx / mAdvanceM));
    }
}

void Engine::connectTo(const QString& host, int port)
{
    disconnectFromGame();
    mTelnet.reset();
    mAnsi.reset();
    mLineBuffer.clear();

    mSocket = new QTcpSocket(this);
    connect(mSocket, &QTcpSocket::readyRead, this, &Engine::onReadyRead);
    connect(mSocket, &QTcpSocket::connected, this, [this, host, port]() {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected %1:%2").arg(host).arg(port));
    });
    connect(mSocket, &QTcpSocket::disconnected, this, [this]() {
        if (!mLineBuffer.isEmpty()) {
            flushLine(false);
        }
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
    });
    connect(mSocket, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSocket->errorString()));
    });
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting %1:%2").arg(host).arg(port));
    mSocket->connectToHost(host, static_cast<quint16>(port));
}

void Engine::disconnectFromGame()
{
    mPromptTimer.stop();
    if (!mSocket) {
        return;
    }
    QTcpSocket* socket = mSocket;
    mSocket = nullptr;
    socket->disconnect(this);
    socket->abort();
    socket->deleteLater();
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
}

void Engine::send(const QString& line)
{
    if (!mSocket || mSocket->state() != QAbstractSocket::ConnectedState) {
        mFrontend->status(QStringLiteral("send.error"), QStringLiteral("not connected"));
        return;
    }
    QByteArray bytes = line.toUtf8();
    // A literal 0xff in the payload must be doubled so it is not read as IAC
    bytes.replace(QByteArray(1, '\xff'), QByteArray(2, '\xff'));
    bytes.append("\r\n");
    mSocket->write(bytes);
}

void Engine::onReadyRead()
{
    if (!mSocket) {
        return;
    }
    mTelnet.feed(mSocket->readAll());
    if (!mLineBuffer.isEmpty()) {
        mPromptTimer.start();
    } else {
        mPromptTimer.stop();
    }
    reportCounters();
}

void Engine::onByte(char ch)
{
    if (ch == '\r') {
        return;
    }
    if (ch == '\n') {
        flushLine(false);
        return;
    }
    mLineBuffer.append(ch);
}

void Engine::flushLine(bool isPrompt)
{
    const AnsiParser::Result parsed = mAnsi.parse(QString::fromUtf8(mLineBuffer));
    mLineBuffer.clear();
    ++mLinesReceived;
    mFrontend->line(parsed.text, parsed.runs, isPrompt);
    callOnLine(parsed.text);
    if (isPrompt) {
        reportCounters();
    }
}

void Engine::callOnLine(const QString& text)
{
    lua_getglobal(mLua, "onLine");
    if (!lua_isfunction(mLua, -1)) {
        lua_pop(mLua, 1);
        return;
    }
    const QByteArray utf8 = text.toUtf8();
    lua_pushlstring(mLua, utf8.constData(), static_cast<size_t>(utf8.size()));
    if (lua_pcall(mLua, 1, 0, 0) != 0) {
        mFrontend->status(QStringLiteral("lua.error"), QString::fromUtf8(lua_tostring(mLua, -1)));
        lua_pop(mLua, 1);
        return;
    }
    ++mLuaOnLineCalls;
}

void Engine::runLua(const QString& code)
{
    const QByteArray utf8 = code.toUtf8();
    const int top = lua_gettop(mLua);
    if (luaL_loadbuffer(mLua, utf8.constData(), static_cast<size_t>(utf8.size()), "=runLua") != 0
        || lua_pcall(mLua, 0, LUA_MULTRET, 0) != 0) {
        mFrontend->status(QStringLiteral("lua.error"), QString::fromUtf8(lua_tostring(mLua, -1)));
        lua_settop(mLua, top);
        return;
    }
    QStringList results;
    const int last = lua_gettop(mLua);
    for (int i = top + 1; i <= last; ++i) {
        lua_getglobal(mLua, "tostring");
        lua_pushvalue(mLua, i);
        lua_call(mLua, 1, 1);
        const char* s = lua_tostring(mLua, -1);
        results << QString::fromUtf8(s ? s : "?");
        lua_pop(mLua, 1);
    }
    lua_settop(mLua, top);
    mFrontend->status(QStringLiteral("lua.result"), results.join(QLatin1String(", ")));
    reportCounters();
}

void Engine::reportCounters()
{
    lua_getglobal(mLua, "lineCount");
    const auto luaCount = static_cast<qint64>(lua_tonumber(mLua, -1));
    lua_pop(mLua, 1);
    mFrontend->status(QStringLiteral("count.lines"), QString::number(mLinesReceived));
    mFrontend->status(QStringLiteral("count.luaOnLine"), QString::number(mLuaOnLineCalls));
    mFrontend->status(QStringLiteral("count.luaLineCount"), QString::number(luaCount));
    mFrontend->status(QStringLiteral("count.notifications"), QString::number(mNotifications));
}

void Engine::onHeartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 gap = now - mLastHeartbeatMs;
    mLastHeartbeatMs = now;
    mMaxGapMs = std::max(mMaxGapMs, gap);
    ++mHeartbeatTicks;
    mFrontend->status(QStringLiteral("heartbeat.ticks"), QString::number(mHeartbeatTicks));
    mFrontend->status(QStringLiteral("heartbeat.lastGapMs"), QString::number(gap));
    mFrontend->status(QStringLiteral("heartbeat.maxGapMs"), QString::number(mMaxGapMs));
}

void Engine::initLua()
{
    mLua = luaL_newstate();
    luaL_openlibs(mLua);
    lua_pushlightuserdata(mLua, this);
    lua_pushcclosure(mLua, &Engine::luaNotify, 1);
    lua_setglobal(mLua, "notify");
    lua_pushlightuserdata(mLua, this);
    lua_pushcclosure(mLua, &Engine::luaPrint, 1);
    lua_setglobal(mLua, "print");
    if (luaL_dostring(mLua, cDefaultScript) != 0) {
        mFrontend->status(QStringLiteral("lua.error"), QString::fromUtf8(lua_tostring(mLua, -1)));
        lua_pop(mLua, 1);
    }
}

int Engine::luaNotify(lua_State* L)
{
    auto* self = static_cast<Engine*>(lua_touserdata(L, lua_upvalueindex(1)));
    const QString title = QString::fromUtf8(luaL_checkstring(L, 1));
    const QString text = QString::fromUtf8(luaL_optstring(L, 2, ""));
    ++self->mNotifications;
    self->mFrontend->notify(title, text);
    return 0;
}

int Engine::luaPrint(lua_State* L)
{
    auto* self = static_cast<Engine*>(lua_touserdata(L, lua_upvalueindex(1)));
    QStringList parts;
    const int n = lua_gettop(L);
    for (int i = 1; i <= n; ++i) {
        lua_getglobal(L, "tostring");
        lua_pushvalue(L, i);
        lua_call(L, 1, 1);
        parts << QString::fromUtf8(lua_tostring(L, -1));
        lua_pop(L, 1);
    }
    const QString text = parts.join(QLatin1Char('\t'));
    self->mFrontend->line(text, {{0, static_cast<int>(text.size()), 11}}, false);
    return 0;
}
