# Desktop build of the stand-in engine plus a host-side test against a fake telnet server.
#   cmake -S desktop -B build-desktop -G Ninja -DCMAKE_PREFIX_PATH=/opt/android-toolchain/qt/6.12.0/gcc_64
#   ninja -C build-desktop -j2 && ctest --test-dir build-desktop --output-on-failure
cmake_minimum_required(VERSION 3.21)
project(protoengine_desktop LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Qt6 REQUIRED COMPONENTS Core Gui Network)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../engine ${CMAKE_BINARY_DIR}/engine)

add_executable(engine_test engine_test.cpp)
target_link_libraries(engine_test PRIVATE protoengine)

enable_testing()
find_package(Python3 REQUIRED COMPONENTS Interpreter)
add_test(NAME engine_fake_server
         COMMAND engine_test ${Python3_EXECUTABLE} ${CMAKE_CURRENT_LIST_DIR}/fake_server.py)
set_tests_properties(engine_fake_server PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen" TIMEOUT 30)
