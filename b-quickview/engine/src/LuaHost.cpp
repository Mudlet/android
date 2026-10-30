#include "LuaHost.h"

#include <QStringList>

extern "C" {
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
}

namespace {
const char* const kDefaultScript = R"lua(
lineCount = 0
function onLine(text)
  lineCount = lineCount + 1
  if string.find(text, "tells you", 1, true) then
    notify("Tell", text)
  end
end
)lua";

LuaHost* hostFrom(lua_State* L)
{
    return static_cast<LuaHost*>(lua_touserdata(L, lua_upvalueindex(1)));
}

// Leaves tostring(value) on the stack; the caller pops it.
QString toQString(lua_State* L, int absIdx)
{
    lua_getglobal(L, "tostring");
    lua_pushvalue(L, absIdx);
    lua_call(L, 1, 1);
    size_t len = 0;
    const char* s = lua_tolstring(L, -1, &len);
    return s ? QString::fromUtf8(s, qsizetype(len)) : QString();
}
} // namespace

LuaHost::LuaHost()
{
    mL = luaL_newstate();
    luaL_openlibs(mL);
    lua_pushlightuserdata(mL, this);
    lua_pushcclosure(mL, &LuaHost::luaNotify, 1);
    lua_setglobal(mL, "notify");
    lua_pushlightuserdata(mL, this);
    lua_pushcclosure(mL, &LuaHost::luaPrint, 1);
    lua_setglobal(mL, "print");
    run(QString::fromUtf8(kDefaultScript));
}

LuaHost::~LuaHost()
{
    lua_close(mL);
}

int LuaHost::luaNotify(lua_State* L)
{
    LuaHost* self = hostFrom(L);
    const QString title = QString::fromUtf8(luaL_checkstring(L, 1));
    const QString text = QString::fromUtf8(luaL_optstring(L, 2, ""));
    if (self->onNotify) {
        self->onNotify(title, text);
    }
    return 0;
}

int LuaHost::luaPrint(lua_State* L)
{
    LuaHost* self = hostFrom(L);
    QString out;
    const int n = lua_gettop(L);
    for (int i = 1; i <= n; ++i) {
        if (i > 1) {
            out += QLatin1Char('\t');
        }
        out += toQString(L, i);
        lua_pop(L, 1);
    }
    if (self->onPrint) {
        self->onPrint(out);
    }
    return 0;
}

QString LuaHost::run(const QString& code, QString* resultOut)
{
    const QByteArray utf8 = code.toUtf8();
    const int top = lua_gettop(mL);
    if (luaL_loadbuffer(mL, utf8.constData(), size_t(utf8.size()), "=runLua") != 0
        || lua_pcall(mL, 0, LUA_MULTRET, 0) != 0) {
        QString err = QString::fromUtf8(lua_tostring(mL, -1));
        lua_settop(mL, top);
        return err.isEmpty() ? QStringLiteral("unknown Lua error") : err;
    }
    if (resultOut) {
        QStringList values;
        for (int i = top + 1; i <= lua_gettop(mL); ++i) {
            values << toQString(mL, i);
            lua_pop(mL, 1);
        }
        *resultOut = values.join(QLatin1String(", "));
    }
    lua_settop(mL, top);
    return {};
}

QString LuaHost::callOnLine(const QString& text)
{
    lua_getglobal(mL, "onLine");
    if (!lua_isfunction(mL, -1)) {
        lua_pop(mL, 1);
        return QStringLiteral("onLine is not a function");
    }
    const QByteArray utf8 = text.toUtf8();
    lua_pushlstring(mL, utf8.constData(), size_t(utf8.size()));
    if (lua_pcall(mL, 1, 0, 0) != 0) {
        QString err = QString::fromUtf8(lua_tostring(mL, -1));
        lua_pop(mL, 1);
        return err;
    }
    return {};
}
