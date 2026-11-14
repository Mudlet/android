cmake_minimum_required(VERSION 3.22)
project(mudlet_android_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Network)
qt_standard_project_setup()

# IEngine and IFrontend: what the engine host and an engine (the stand-in, or libmudlet) say to each other
add_library(engine_api INTERFACE)
target_include_directories(engine_api INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/api)

add_subdirectory(standin)
add_subdirectory(enginehost)
