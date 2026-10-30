set(LUA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/lua-5.1.5/src)
file(GLOB LUA_SOURCES ${LUA_DIR}/*.c)
add_library(lua51 STATIC ${LUA_SOURCES})
target_include_directories(lua51 PUBLIC ${LUA_DIR})
set_target_properties(lua51 PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(lua51 PRIVATE LUA_USE_POSIX)
# Lua 5.1's getstr() is (TString*)+1, which clang's FORTIFY object-size check reads as a 0-byte buffer: bionic aborts in luaL_openlibs
target_compile_options(lua51 PRIVATE -U_FORTIFY_SOURCE)

add_library(engine_core STATIC Engine.cpp Engine.h IFrontend.h)
target_include_directories(engine_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(engine_core PUBLIC Qt6::Core Qt6::Gui Qt6::Network PRIVATE lua51)
set_target_properties(engine_core PROPERTIES AUTOMOC ON POSITION_INDEPENDENT_CODE ON)
