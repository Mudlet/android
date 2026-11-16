#include "Engine.h"


extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

namespace {

constexpr unsigned char IAC = 255;
constexpr unsigned char DONT = 254;
constexpr unsigned char DO = 253;
constexpr unsigned char WONT = 252;
constexpr unsigned char WILL = 251;
constexpr unsigned char SB = 250;
constexpr unsigned char GA = 249;
constexpr unsigned char SE = 240;
constexpr unsigned char EOR = 239;
constexpr unsigned char TELOPT_ECHO = 1;
constexpr unsigned char TELOPT_NAWS = 31;

constexpr int promptSilenceMs = 500;
// As Mudlet's default for the lines of history it saves
constexpr qsizetype historyLines = 500;

const char* const defaultScript = R"lua(
lineCount = 0
function onLine(text)
  lineCount = lineCount + 1
  if string.find(text, "tells you", 1, true) then
    notify("Tell", text)
  end
end
)lua";

int luaNotifyFn(lua_State* L)
{
    auto* engine = static_cast<Engine*>(lua_touserdata(L, lua_upvalueindex(1)));
    const QString title = QString::fromUtf8(luaL_checkstring(L, 1));
    const QString text = QString::fromUtf8(luaL_checkstring(L, 2));
    engine->luaNotify(title, text);
    return 0;
}

// announce(text[, style]): assertive for Mudlet's "importantall" and "importantmostrecent" styles, as libmudlet's
int luaAnnounceFn(lua_State* L)
{
    auto* engine = static_cast<Engine*>(lua_touserdata(L, lua_upvalueindex(1)));
    const QString text = QString::fromUtf8(luaL_checkstring(L, 1));
    const QString style = QString::fromUtf8(luaL_optstring(L, 2, ""));
    engine->luaAnnounce(text, style == QLatin1String("importantall") || style == QLatin1String("importantmostrecent"));
    return 0;
}

} // namespace

Engine::Engine(QObject* parent)
: QObject(parent)
{
    connect(&mSocket, &QTcpSocket::readyRead, this, &Engine::onReadyRead);
    connect(&mSocket, &QTcpSocket::connected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
    });
    connect(&mSocket, &QTcpSocket::disconnected, this, [this] {
        if (!mLineBuffer.isEmpty()) {
            emitLine(true);
        }
        mNaws = false;
        setHiddenInput(false);
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
    });
    connect(&mSocket, &QTcpSocket::errorOccurred, this, [this](QAbstractSocket::SocketError) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSocket.errorString()));
    });

    mPromptTimer.setSingleShot(true);
    mPromptTimer.setInterval(promptSilenceMs);
    connect(&mPromptTimer, &QTimer::timeout, this, [this] {
        if (!mLineBuffer.isEmpty()) {
            emitLine(true);
        }
    });

    // Coalesces counter updates so a flood of lines costs one status frame per tick
    mCounterTimer.setSingleShot(true);
    mCounterTimer.setInterval(100);
    connect(&mCounterTimer, &QTimer::timeout, this, &Engine::flushCounters);
}

void Engine::start(IFrontend* frontend)
{
    mFrontend = frontend;
    mLua = luaL_newstate();
    luaL_openlibs(mLua);
    lua_pushlightuserdata(mLua, this);
    lua_pushcclosure(mLua, luaNotifyFn, 1);
    lua_setglobal(mLua, "notify");
    lua_pushlightuserdata(mLua, this);
    lua_pushcclosure(mLua, luaAnnounceFn, 1);
    lua_setglobal(mLua, "announce");
    if (luaL_dostring(mLua, defaultScript) != 0) {
        mFrontend->status(QStringLiteral("luaError"), QString::fromUtf8(lua_tostring(mLua, -1)));
        lua_pop(mLua, 1);
    }

    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
}

Engine::~Engine()
{
    if (mLua) {
        lua_close(mLua);
    }
}

void Engine::close()
{
    mPromptTimer.stop();
    mSocket.abort();
}

void Engine::connectTo(const QString& host, quint16 port, std::optional<bool> tls)
{
    if (tls.value_or(false)) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: the stand-in engine has no TLS"));
        return;
    }
    mSocket.abort();
    mTelnetState = TelnetState::Data;
    mLineBuffer.clear();
    mFgBase = -1;
    mBold = false;
    mNaws = false;
    setHiddenInput(false);
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting %1:%2").arg(host).arg(port));
    mSocket.connectToHost(host, port);
}

void Engine::send(const QString& line)
{
    if (mSocket.state() != QAbstractSocket::ConnectedState) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("not connected"));
        return;
    }
    writeLine(line);
    if (!line.isEmpty()) {
        mHistory.removeAll(line);
        mHistory.prepend(line);
        if (mHistory.size() > historyLines) {
            mHistory.removeLast();
        }
    }
}

void Engine::sendHidden(const QString& line)
{
    if (mSocket.state() != QAbstractSocket::ConnectedState) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("not connected"));
        return;
    }
    if (!mHiddenInput) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("the game is not hiding input"));
        return;
    }
    // As Mudlet's hidden-input box: a pasted line break must not make a second line
    QString text = line;
    text.remove(QLatin1Char('\r'));
    text.remove(QLatin1Char('\n'));
    writeLine(text);
}

void Engine::dismissHidden()
{
    if (!mHiddenInput) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("the game is not hiding input"));
        return;
    }
    setHiddenInput(false);
}

// From the lines send() sent: the newest that starts with text, or, as Tab, the newest word in them that starts with
// text's last word
QString Engine::complete(const QString& text, bool fromHistory)
{
    if (text.isEmpty() || mHiddenInput) {
        return {};
    }
    if (fromHistory) {
        for (const QString& sent : std::as_const(mHistory)) {
            if (sent.size() > text.size() && sent.startsWith(text)) {
                return sent;
            }
        }
        return {};
    }
    const qsizetype wordStart = text.lastIndexOf(QLatin1Char(' ')) + 1;
    const QString word = text.mid(wordStart);
    if (word.isEmpty()) {
        return {};
    }
    for (const QString& sent : std::as_const(mHistory)) {
        for (const QString& candidate : sent.split(QLatin1Char(' '), Qt::SkipEmptyParts)) {
            if (candidate.size() > word.size() && candidate.startsWith(word)) {
                return text.left(wordStart) + candidate;
            }
        }
    }
    return {};
}

void Engine::writeLine(const QString& line)
{
    QByteArray data = line.toUtf8();
    data.replace(char(IAC), QByteArray(2, char(IAC)));
    data.append("\r\n");
    mSocket.write(data);
}

void Engine::setHiddenInput(bool wanted)
{
    if (wanted == mHiddenInput) {
        return;
    }
    mHiddenInput = wanted;
    mFrontend->hiddenInput(wanted);
}

void Engine::disconnectFromGame()
{
    mSocket.disconnectFromHost();
}

void Engine::runLua(const QString& code)
{
    const QByteArray chunk = code.toUtf8();
    const int top = lua_gettop(mLua);
    QString result;
    if (luaL_loadbuffer(mLua, chunk.constData(), size_t(chunk.size()), "=runLua") != 0 || lua_pcall(mLua, 0, LUA_MULTRET, 0) != 0) {
        result = QStringLiteral("error: %1").arg(QString::fromUtf8(lua_tostring(mLua, -1)));
    } else {
        QStringList values;
        for (int i = top + 1; i <= lua_gettop(mLua); ++i) {
            lua_getglobal(mLua, "tostring");
            lua_pushvalue(mLua, i);
            lua_call(mLua, 1, 1);
            values << QString::fromUtf8(lua_tostring(mLua, -1));
            lua_pop(mLua, 1);
        }
        result = values.join(QLatin1Char('\t'));
    }
    lua_settop(mLua, top);
    mFrontend->status(QStringLiteral("luaResult"), result);
}

void Engine::setScreenSize(int columns, int rows)
{
    if (columns == mColumns && rows == mRows) {
        return;
    }
    mColumns = columns;
    mRows = rows;
    if (mNaws) {
        sendNaws();
    }
}

// RFC 1073: IAC SB NAWS, the width and the height in two bytes each, IAC SE; a 255 byte doubled
void Engine::sendNaws()
{
    QByteArray message;
    message.append(char(IAC)).append(char(SB)).append(char(TELOPT_NAWS));
    for (const int value : {mColumns >> 8, mColumns & 0xff, mRows >> 8, mRows & 0xff}) {
        message.append(char(value));
        if (value == IAC) {
            message.append(char(IAC));
        }
    }
    message.append(char(IAC)).append(char(SE));
    mSocket.write(message);
}

void Engine::luaNotify(const QString& title, const QString& text)
{
    ++mNotifications;
    markCountersDirty();
    mFrontend->notify(title, text);
}

void Engine::luaAnnounce(const QString& text, bool assertive)
{
    mFrontend->announce(text, assertive);
}

void Engine::onReadyRead()
{
    const QByteArray data = mSocket.readAll();
    for (const char c : data) {
        processByte(static_cast<unsigned char>(c));
    }
    if (mLineBuffer.isEmpty()) {
        mPromptTimer.stop();
    } else {
        mPromptTimer.start();
    }
}

void Engine::processByte(unsigned char byte)
{
    switch (mTelnetState) {
    case TelnetState::Data:
        if (byte == IAC) {
            mTelnetState = TelnetState::Iac;
        } else if (byte == '\n') {
            emitLine(false);
        } else if (byte != '\r') {
            mLineBuffer.append(char(byte));
        }
        return;
    case TelnetState::Iac:
        mTelnetState = TelnetState::Data;
        switch (byte) {
        case IAC:
            mLineBuffer.append(char(IAC));
            return;
        case DO:
        case DONT:
        case WILL:
        case WONT:
            mTelnetCommand = byte;
            mTelnetState = TelnetState::Option;
            return;
        case SB:
            mTelnetState = TelnetState::Sub;
            return;
        case GA:
        case EOR:
            if (!mLineBuffer.isEmpty()) {
                emitLine(true);
            }
            return;
        default:
            return;
        }
    case TelnetState::Option: {
        mTelnetState = TelnetState::Data;
        // Accepted, as Mudlet accepts it: a game that echoes for the player is hiding what they type
        if (byte == TELOPT_ECHO && (mTelnetCommand == WILL || mTelnetCommand == WONT)) {
            const bool wanted = mTelnetCommand == WILL;
            if (wanted != mHiddenInput) {
                const char reply[3] = {char(IAC), char(wanted ? DO : DONT), char(TELOPT_ECHO)};
                mSocket.write(reply, 3);
                setHiddenInput(wanted);
            }
            return;
        }
        // Accepted, as Mudlet accepts it, and answered with the size at once
        if (byte == TELOPT_NAWS && mTelnetCommand == DO) {
            if (!mNaws) {
                const char reply[3] = {char(IAC), char(WILL), char(TELOPT_NAWS)};
                mSocket.write(reply, 3);
                mNaws = true;
            }
            sendNaws();
            return;
        }
        if (byte == TELOPT_NAWS && mTelnetCommand == DONT) {
            if (std::exchange(mNaws, false)) {
                const char reply[3] = {char(IAC), char(WONT), char(TELOPT_NAWS)};
                mSocket.write(reply, 3);
            }
            return;
        }
        if (mTelnetCommand == DO || mTelnetCommand == WILL) {
            const char reply[3] = {char(IAC), char(mTelnetCommand == DO ? WONT : DONT), char(byte)};
            mSocket.write(reply, 3);
        }
        return;
    }
    case TelnetState::Sub:
        if (byte == IAC) {
            mTelnetState = TelnetState::SubIac;
        }
        return;
    case TelnetState::SubIac:
        mTelnetState = byte == SE ? TelnetState::Data : TelnetState::Sub;
        return;
    }
}

int Engine::effectiveFg() const
{
    if (mFgBase >= 0 && mFgBase < 8 && mBold) {
        return mFgBase + 8;
    }
    return mFgBase;
}

void Engine::applySgr(const QByteArray& params)
{
    const QList<QByteArray> parts = params.split(';');
    for (qsizetype i = 0; i < parts.size(); ++i) {
        const int code = parts.at(i).isEmpty() ? 0 : parts.at(i).toInt();
        if (code == 0) {
            mFgBase = -1;
            mBold = false;
        } else if (code == 1) {
            mBold = true;
        } else if (code == 22) {
            mBold = false;
        } else if (code >= 30 && code <= 37) {
            mFgBase = code - 30;
        } else if (code == 39) {
            mFgBase = -1;
        } else if (code >= 90 && code <= 97) {
            mFgBase = 8 + code - 90;
        } else if (code == 38 && i + 1 < parts.size()) {
            const int mode = parts.at(i + 1).toInt();
            if (mode == 5 && i + 2 < parts.size()) {
                const int index = parts.at(i + 2).toInt();
                mFgBase = index < 16 ? index : -1;
                i += 2;
            } else if (mode == 2) {
                mFgBase = -1;
                i += 4;
            }
        } else if (code == 48 && i + 1 < parts.size()) {
            i += parts.at(i + 1).toInt() == 5 ? 2 : 4;
        }
    }
}

void Engine::emitLine(bool isPrompt)
{
    mPromptTimer.stop();
    QString text;
    QList<ColorRun> runs;
    QByteArray segment;

    auto flush = [&] {
        if (segment.isEmpty()) {
            return;
        }
        const QString part = QString::fromUtf8(segment);
        segment.clear();
        const int fg = effectiveFg();
        if (!runs.isEmpty() && runs.last().fg == fg && runs.last().start + runs.last().length == text.size()) {
            runs.last().length += int(part.size());
        } else {
            runs.append({int(text.size()), int(part.size()), fg});
        }
        text += part;
    };

    const QByteArray& raw = mLineBuffer;
    for (qsizetype i = 0; i < raw.size(); ++i) {
        const char c = raw.at(i);
        if (c != '\x1b') {
            segment.append(c);
            continue;
        }
        if (i + 1 < raw.size() && raw.at(i + 1) == '[') {
            qsizetype end = i + 2;
            while (end < raw.size() && !(raw.at(end) >= 0x40 && raw.at(end) <= 0x7e)) {
                ++end;
            }
            if (end < raw.size() && raw.at(end) == 'm') {
                flush();
                applySgr(raw.mid(i + 2, end - i - 2));
            }
            i = end;
        } else {
            ++i;
        }
    }
    flush();
    mLineBuffer.clear();

    if (isPrompt) {
        ++mPromptsReceived;
    } else {
        ++mLinesReceived;
    }
    mFrontend->line(text, runs, isPrompt);
    if (!isPrompt) {
        callOnLine(text);
    }
    markCountersDirty();
}

void Engine::callOnLine(const QString& text)
{
    lua_getglobal(mLua, "onLine");
    if (!lua_isfunction(mLua, -1)) {
        lua_pop(mLua, 1);
        return;
    }
    const QByteArray utf8 = text.toUtf8();
    lua_pushlstring(mLua, utf8.constData(), size_t(utf8.size()));
    ++mLuaCalls;
    if (lua_pcall(mLua, 1, 0, 0) != 0) {
        mFrontend->status(QStringLiteral("luaError"), QString::fromUtf8(lua_tostring(mLua, -1)));
        lua_pop(mLua, 1);
    }
}

void Engine::markCountersDirty()
{
    mCountersDirty = true;
    if (!mCounterTimer.isActive()) {
        mCounterTimer.start();
    }
}

void Engine::flushCounters()
{
    if (!mCountersDirty) {
        return;
    }
    mCountersDirty = false;
    mFrontend->status(QStringLiteral("linesReceived"), QString::number(mLinesReceived));
    mFrontend->status(QStringLiteral("promptsReceived"), QString::number(mPromptsReceived));
    mFrontend->status(QStringLiteral("luaOnLineCalls"), QString::number(mLuaCalls));
    mFrontend->status(QStringLiteral("notificationsPosted"), QString::number(mNotifications));
}

std::variant<QVariantMap, QString> Engine::settings(const QStringList& keys)
{
    QVariantMap values;
    for (const QString& key : keys) {
        if (mSettings.contains(key)) {
            values.insert(key, mSettings.value(key));
        }
    }
    return values;
}

QString Engine::setSettings(const QVariantMap& values, QMap<QString, QString>&)
{
    mSettings.insert(values);
    return {};
}
