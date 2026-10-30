#include "lauxlib.h"
#include "lua.h"

int luaopen_lfs(lua_State* L);
int luaopen_lpeg(lua_State* L);
int luaopen_rex_pcre2(lua_State* L);
int luaopen_utf8(lua_State* L);
int luaopen_yajl(lua_State* L);
int luaopen_brimworks_zip(lua_State* L);
int luaopen_luasql_sqlite3(lua_State* L);

// Called by Mudlet (TLuaInterpreter, Android only) once its Lua state has the standard libraries
void mudlet_preload_lua_modules(lua_State* L)
{
    static const luaL_Reg modules[] = {
        {"lfs", luaopen_lfs},
        {"lpeg", luaopen_lpeg},
        {"rex_pcre2", luaopen_rex_pcre2},
        {"lua-utf8", luaopen_utf8},
        {"yajl", luaopen_yajl},
        {"brimworks.zip", luaopen_brimworks_zip},
        {"luasql.sqlite3", luaopen_luasql_sqlite3},
        {NULL, NULL},
    };
    lua_getglobal(L, "package");
    lua_getfield(L, -1, "preload");
    for (const luaL_Reg* module = modules; module->name; ++module) {
        lua_pushcfunction(L, module->func);
        lua_setfield(L, -2, module->name);
    }
    lua_pop(L, 2);
}
