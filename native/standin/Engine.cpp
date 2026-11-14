#include "Engine.h"

#include <QDateTime>
#include <QFile>
#include <QFont>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

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

constexpr int promptSilenceMs = 500;

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

} // namespace

Engine::Engine(IFrontend* frontend, int heartbeatMs, const QString& profilesPath, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
, mProfilesPath(profilesPath)
{
    loadProfiles();
    connect(&mSocket, &QTcpSocket::readyRead, this, &Engine::onReadyRead);
    connect(&mSocket, &QTcpSocket::connected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
    });
    connect(&mSocket, &QTcpSocket::disconnected, this, [this] {
        if (!mLineBuffer.isEmpty()) {
            emitLine(true);
        }
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

    mLua = luaL_newstate();
    luaL_openlibs(mLua);
    lua_pushlightuserdata(mLua, this);
    lua_pushcclosure(mLua, luaNotifyFn, 1);
    lua_setglobal(mLua, "notify");
    if (luaL_dostring(mLua, defaultScript) != 0) {
        mFrontend->status(QStringLiteral("luaError"), QString::fromUtf8(lua_tostring(mLua, -1)));
        lua_pop(mLua, 1);
    }

    reportFont();

    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.setInterval(heartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &Engine::onHeartbeat);
    mHeartbeat.start();
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
}

Engine::~Engine()
{
    lua_close(mLua);
}

void Engine::reportFont()
{
    if (!qobject_cast<QGuiApplication*>(QCoreApplication::instance())) {
        mFrontend->status(QStringLiteral("fontError"), QStringLiteral("no QGuiApplication"));
        return;
    }
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    const QFontMetrics metrics(font);
    mAdvancePx = metrics.horizontalAdvance(QLatin1Char('M'));
    mFrontend->status(QStringLiteral("fontFamily"), QFontInfo(font).family());
    mFrontend->status(QStringLiteral("advanceM"), QString::number(mAdvancePx));
    mFrontend->status(QStringLiteral("platform"), QGuiApplication::platformName());
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
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting %1:%2").arg(host).arg(port));
    mSocket.connectToHost(host, port);
}

void Engine::send(const QString& line)
{
    if (mSocket.state() != QAbstractSocket::ConnectedState) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("not connected"));
        return;
    }
    QByteArray data = line.toUtf8();
    data.replace(char(IAC), QByteArray(2, char(IAC)));
    data.append("\r\n");
    mSocket.write(data);
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

void Engine::setViewWidthPx(int px)
{
    mViewWidthPx = px;
    const int columns = mAdvancePx > 0 ? px / mAdvancePx : 0;
    mFrontend->status(QStringLiteral("viewWidthPx"), QString::number(px));
    mFrontend->status(QStringLiteral("columns"), QString::number(columns));
}

// {"v": 1, "profiles": [{"name", "host", "port", "tls", "login", "autoReconnect"}, ...]}, the last opened first
void Engine::loadProfiles()
{
    QFile file(mProfilesPath);
    if (!file.exists()) {
        return;
    }
    if (!file.open(QIODevice::ReadOnly)) {
        qWarning("Engine: cannot read the profiles in %s: %s", qPrintable(mProfilesPath), qPrintable(file.errorString()));
        return;
    }
    QJsonParseError error{};
    const QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &error);
    if (!doc.isObject()) {
        qWarning("Engine: ignoring the unreadable profiles in %s: %s", qPrintable(mProfilesPath), qPrintable(error.errorString()));
        return;
    }
    for (const QJsonValue& value : doc.object().value(QLatin1String("profiles")).toArray()) {
        const QJsonObject json = value.toObject();
        const Profile profile{json.value(QLatin1String("name")).toString(),
                              json.value(QLatin1String("host")).toString(),
                              json.value(QLatin1String("port")).toInt(23),
                              json.value(QLatin1String("tls")).toBool(),
                              json.value(QLatin1String("login")).toString(),
                              json.value(QLatin1String("autoReconnect")).toBool()};
        if (Profile::newNameProblem(profile.name).isEmpty() && profileIndex(profile.name) < 0) {
            mProfiles.append(profile);
        }
    }
}

// Kept in memory only once on disk, so the UI is never shown a profile a restart would lose
QString Engine::storeProfiles(const QList<Profile>& profiles)
{
    QJsonArray list;
    for (const Profile& profile : profiles) {
        list.append(QJsonObject{{QStringLiteral("name"), profile.name},
                                {QStringLiteral("host"), profile.host},
                                {QStringLiteral("port"), profile.port},
                                {QStringLiteral("tls"), profile.tls},
                                {QStringLiteral("login"), profile.login},
                                {QStringLiteral("autoReconnect"), profile.autoReconnect}});
    }
    QSaveFile file(mProfilesPath);
    if (!file.open(QIODevice::WriteOnly) || file.write(QJsonDocument(QJsonObject{{QStringLiteral("v"), 1}, {QStringLiteral("profiles"), list}}).toJson()) < 0 || !file.commit()) {
        return QStringLiteral("could not store the profiles in %1: %2").arg(mProfilesPath, file.errorString());
    }
    mProfiles = profiles;
    return {};
}

qsizetype Engine::profileIndex(const QString& name) const
{
    for (qsizetype i = 0; i < mProfiles.size(); ++i) {
        if (mProfiles.at(i).name.compare(name, Qt::CaseInsensitive) == 0) {
            return i;
        }
    }
    return -1;
}

QString Engine::saveProfile(const Profile& profile)
{
    QList<Profile> profiles = mProfiles;
    const qsizetype index = profileIndex(profile.name);
    if (index >= 0) {
        // Under the name it was created with, as libmudlet keeps its folder's
        Profile updated = profile;
        updated.name = profiles.at(index).name;
        profiles[index] = updated;
    } else if (const QString problem = Profile::newNameProblem(profile.name); !problem.isEmpty()) {
        return problem;
    } else {
        profiles.prepend(profile);
    }
    return storeProfiles(profiles);
}

QString Engine::deleteProfile(const QString& name)
{
    const qsizetype index = profileIndex(name);
    if (index < 0) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    if (mOpenProfile.compare(name, Qt::CaseInsensitive) == 0) {
        return {};
        return QStringLiteral("\"%1\" is open: open another profile before deleting it").arg(mOpenProfile);
    }
    QList<Profile> profiles = mProfiles;
    profiles.removeAt(index);
    return storeProfiles(profiles);
}

// The stand-in has no login, so the password goes nowhere
QString Engine::openProfile(const QString& name, const QString&, bool connect)
{
    const qsizetype index = profileIndex(name);
    if (index < 0) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    QList<Profile> profiles = mProfiles;
    const Profile profile = profiles.takeAt(index);
    profiles.prepend(profile);
    if (const QString problem = storeProfiles(profiles); !problem.isEmpty()) {
        return problem;
    }
    const bool switching = mOpenProfile != profile.name;
    mOpenProfile = profile.name;
    if (switching) {
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile.name));
    }
    if (connect && switching) {
        connectTo(profile.host, quint16(profile.port), profile.tls);
    } else if (!connect && switching) {
        disconnectFromGame();
    }
    return {};
}

void Engine::luaNotify(const QString& title, const QString& text)
{
    ++mNotifications;
    markCountersDirty();
    mFrontend->notify(title, text);
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

void Engine::onHeartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 gap = now - mLastHeartbeatMs;
    mLastHeartbeatMs = now;
    ++mHeartbeatTicks;
    mMaxHeartbeatGapMs = std::max(mMaxHeartbeatGapMs, gap);
    mFrontend->status(QStringLiteral("heartbeatTicks"), QString::number(mHeartbeatTicks));
    mFrontend->status(QStringLiteral("heartbeatLastGapMs"), QString::number(gap));
    mFrontend->status(QStringLiteral("heartbeatMaxGapMs"), QString::number(mMaxHeartbeatGapMs));
}
