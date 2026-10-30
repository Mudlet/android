cmake_minimum_required(VERSION 3.22)
project(mudlet_proto_c LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Network)
qt_standard_project_setup()

add_subdirectory(engine)
add_subdirectory(enginehost)
