cmake_minimum_required(VERSION 3.22)
project(mudlet_android_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Network)
find_package(ZLIB REQUIRED)
qt_standard_project_setup()

# IEngine and IFrontend: what the engine host and the engine (libmudlet, through MudletEngine) say to each other
add_library(engine_api INTERFACE)
target_include_directories(engine_api INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/api)

add_subdirectory(enginehost)

if(NOT ANDROID)
    # The map's PNG writer against Qt's own, which needs no libmudlet
    qt_add_executable(map_png_test tests/map_png_test.cpp enginehost/MapPng.cpp enginehost/MapPng.h)
    target_include_directories(map_png_test PRIVATE enginehost)
    target_link_libraries(map_png_test PRIVATE Qt6::Gui ZLIB::ZLIB)

    # What the bridge makes of a map's frames, with no engine behind it and no UI attached
    qt_add_executable(map_frames_test tests/map_frames_test.cpp enginehost/SocketBridge.cpp enginehost/SocketBridge.h
        enginehost/ControlBridge.cpp enginehost/ControlBridge.h)
    target_include_directories(map_frames_test PRIVATE enginehost)
    target_link_libraries(map_frames_test PRIVATE engine_api Qt6::Core Qt6::Network)
endif()
