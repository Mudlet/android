# Android native part of prototype A: libmudletproto_arm64-v8a.so = main() + JNI facade + engine.
# Configured by scripts/build-native.sh (called from Gradle); qt-cmake supplies the toolchain.
cmake_minimum_required(VERSION 3.21)
project(mudletproto LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Qt6 REQUIRED COMPONENTS Core CorePrivate Gui Network)
qt_standard_project_setup()

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../engine ${CMAKE_BINARY_DIR}/engine)

qt_add_executable(mudletproto main.cpp JniBridge.cpp JniBridge.h)
target_link_libraries(mudletproto PRIVATE protoengine Qt6::Core Qt6::CorePrivate Qt6::Gui Qt6::Network log)
# 16 KB page alignment for Android 15+ devices (NDK 28 defaults to it, kept explicit)
target_link_options(mudletproto PRIVATE "-Wl,-z,max-page-size=16384")

set_target_properties(mudletproto PROPERTIES
    QT_ANDROID_MIN_SDK_VERSION 28
    QT_ANDROID_TARGET_SDK_VERSION 36
    QT_ANDROID_COMPILE_SDK_VERSION 36
    QT_ANDROID_PACKAGE_NAME org.mudlet.proto.a)
