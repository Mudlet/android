#pragma once

#include <QString>
#include <functional>

struct lua_State;

class LuaHost
{
public:
    std::function<void(const QString&, const QString&)> onNotify;
    std::function<void(const QString&)> onPrint;

    LuaHost();
    ~LuaHost();
    LuaHost(const LuaHost&) = delete;
    LuaHost& operator=(const LuaHost&) = delete;

    // Returns an error message, or an empty string on success.
    QString run(const QString& code, QString* resultOut = nullptr);
    QString callOnLine(const QString& text);

private:
    static int luaNotify(lua_State* L);
    static int luaPrint(lua_State* L);

    lua_State* mL = nullptr;
};
