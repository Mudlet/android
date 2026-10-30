cmake_minimum_required(VERSION 3.22)

project(mudengine VERSION 0.1 LANGUAGES C CXX)

# OFF builds the same engine without Qt Quick/QML, only to measure what Quick adds to the package.
option(MUDENGINE_WITH_QUICK "Link Qt Quick so a QtQuickView can start the engine" ON)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(qt_components Core Gui Network)
if(MUDENGINE_WITH_QUICK)
    list(APPEND qt_components Quick)
endif()
find_package(Qt6 6.8 REQUIRED COMPONENTS ${qt_components})
qt_standard_project_setup(REQUIRES 6.8)

add_subdirectory(../engine engine)

qt_add_executable(mudengine
    main.cpp
    AndroidBridge.h
    AndroidBridge.cpp
)
target_link_libraries(mudengine PRIVATE engine_core)

if(MUDENGINE_WITH_QUICK)
    qt_add_qml_module(mudengine
        URI MudEngine
        VERSION 1.0
        QML_FILES Hidden.qml
    )
    target_link_libraries(mudengine PRIVATE Qt6::Quick)
    target_compile_definitions(mudengine PRIVATE MUDENGINE_WITH_QUICK)
endif()

set_target_properties(mudengine PROPERTIES
    QT_ANDROID_PACKAGE_NAME org.mudlet.proto.b.qt
    QT_ANDROID_MIN_SDK_VERSION 28
    QT_ANDROID_TARGET_SDK_VERSION 36
    QT_ANDROID_COMPILE_SDK_VERSION 36
    QT_ANDROID_SDK_BUILD_TOOLS_REVISION 37.0.0
)
