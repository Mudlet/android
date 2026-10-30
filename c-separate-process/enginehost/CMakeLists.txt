set(HOST_SOURCES main.cpp SocketBridge.cpp SocketBridge.h)
if(ANDROID)
    list(APPEND HOST_SOURCES AndroidNotifier.cpp AndroidNotifier.h AndroidServiceSetup.cpp AndroidServiceSetup.h)
endif()

# On Android this produces libengine_arm64-v8a.so, loaded by QtServiceBase in the :engine process
qt_add_executable(engine ${HOST_SOURCES})
target_link_libraries(engine PRIVATE engine_core Qt6::Core Qt6::Gui Qt6::Network)
if(ANDROID)
    # QtAndroidPrivate's service-setup semaphore; ties the build to this exact Qt version
    find_package(Qt6 REQUIRED COMPONENTS CorePrivate)
    target_link_libraries(engine PRIVATE Qt6::CorePrivate)
endif()
set_target_properties(engine PROPERTIES AUTOMOC ON)

if(ANDROID)
    set_target_properties(engine PROPERTIES
        QT_ANDROID_PACKAGE_NAME org.mudlet.proto.c
        QT_ANDROID_MIN_SDK_VERSION 28
        QT_ANDROID_TARGET_SDK_VERSION 36
        QT_ANDROID_COMPILE_SDK_VERSION 36)
endif()
