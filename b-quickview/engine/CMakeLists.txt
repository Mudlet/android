cmake_minimum_required(VERSION 3.22)

# Standalone (desktop) or add_subdirectory() from ../qtapp (Android).
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(mudengine_core LANGUAGES C CXX)
    set(ENGINE_STANDALONE ON)
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Core Gui Network)

file(GLOB LUA_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lua-5.1.5/src/*.c)
list(FILTER LUA_SOURCES EXCLUDE REGEX ".*/(lua|luac|print)\\.c$")
add_library(lua51 STATIC ${LUA_SOURCES})
target_include_directories(lua51 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lua-5.1.5/src)
target_compile_definitions(lua51 PRIVATE LUA_USE_POSIX)
set_target_properties(lua51 PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)

add_library(engine_core STATIC
    src/IFrontend.h
    src/AnsiParser.h src/AnsiParser.cpp
    src/TelnetDecoder.h src/TelnetDecoder.cpp
    src/LuaHost.h src/LuaHost.cpp
    src/Engine.h src/Engine.cpp
)
set_target_properties(engine_core PROPERTIES AUTOMOC ON POSITION_INDEPENDENT_CODE ON)
target_include_directories(engine_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(engine_core PUBLIC Qt6::Core Qt6::Gui Qt6::Network PRIVATE lua51)

if(ENGINE_STANDALONE)
    add_executable(engine_cli desktop/main.cpp)
    target_link_libraries(engine_cli PRIVATE engine_core)

    add_executable(engine_selftest desktop/selftest.cpp)
    target_link_libraries(engine_selftest PRIVATE engine_core)

    enable_testing()
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    add_test(NAME engine_fake_server
        COMMAND ${Python3_EXECUTABLE} -I ${CMAKE_CURRENT_SOURCE_DIR}/tests/run_test.py
                $<TARGET_FILE:engine_selftest> ${CMAKE_CURRENT_SOURCE_DIR}/tests/fake_telnet_server.py)
endif()
