# Stand-in engine: Qt Core/Gui/Network + static Lua 5.1.5. No Android or JNI includes,
# so the same target builds for desktop (tests) and Android (inside libmudletproto).
cmake_minimum_required(VERSION 3.21)

set(LUA_DIR ${CMAKE_CURRENT_LIST_DIR}/../third_party/lua-5.1.5/src)
set(LUA_SOURCES
    lapi.c lcode.c ldebug.c ldo.c ldump.c lfunc.c lgc.c llex.c lmem.c lobject.c lopcodes.c
    lparser.c lstate.c lstring.c ltable.c ltm.c lundump.c lvm.c lzio.c
    lauxlib.c lbaselib.c ldblib.c liolib.c lmathlib.c loslib.c ltablib.c lstrlib.c loadlib.c linit.c)
list(TRANSFORM LUA_SOURCES PREPEND ${LUA_DIR}/)
add_library(lua51 STATIC ${LUA_SOURCES})
target_include_directories(lua51 PUBLIC ${LUA_DIR})
target_compile_definitions(lua51 PRIVATE LUA_USE_POSIX)
set_target_properties(lua51 PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)

add_library(protoengine STATIC
    ${CMAKE_CURRENT_LIST_DIR}/IFrontend.h
    ${CMAKE_CURRENT_LIST_DIR}/AnsiParser.h
    ${CMAKE_CURRENT_LIST_DIR}/AnsiParser.cpp
    ${CMAKE_CURRENT_LIST_DIR}/TelnetParser.h
    ${CMAKE_CURRENT_LIST_DIR}/TelnetParser.cpp
    ${CMAKE_CURRENT_LIST_DIR}/Engine.h
    ${CMAKE_CURRENT_LIST_DIR}/Engine.cpp)
target_include_directories(protoengine PUBLIC ${CMAKE_CURRENT_LIST_DIR})
target_compile_features(protoengine PUBLIC cxx_std_20)
target_link_libraries(protoengine PUBLIC Qt6::Core Qt6::Gui Qt6::Network PRIVATE lua51)
set_target_properties(protoengine PROPERTIES AUTOMOC ON POSITION_INDEPENDENT_CODE ON)
