set(HOST_SOURCES main.cpp ControlBridge.cpp ControlBridge.h SocketBridge.cpp SocketBridge.h MudletEngine.cpp MudletEngine.h
    MapPng.cpp MapPng.h MudletProfile.cpp MudletProfile.h ProfileStore.cpp ProfileStore.h SessionStore.cpp SessionStore.h)
if(ANDROID)
    list(APPEND HOST_SOURCES AndroidNotifier.cpp AndroidNotifier.h AndroidServiceSetup.cpp AndroidServiceSetup.h)
endif()

# On Android this produces libengine_arm64-v8a.so, which EngineService (:engine-service) loads in the :engine process.
# LIBMUDLET_BUILD_DIR is a Mudlet build tree made by scripts/build-libmudlet.sh (Android) or
# scripts/build-libmudlet-desktop.sh (host): the engine is libmudlet (MudletEngine, through THeadlessSession's public
# API). That tree's libmudlet.cmake (scripts/export-libmudlet.py) defines libmudlet::headless.
set(LIBMUDLET_BUILD_DIR "" CACHE PATH "Mudlet build tree with libmudlet.cmake")
if(NOT LIBMUDLET_BUILD_DIR)
    message(FATAL_ERROR "Set LIBMUDLET_BUILD_DIR to a Mudlet build tree from scripts/build-libmudlet.sh or scripts/build-libmudlet-desktop.sh")
endif()
if(NOT EXISTS "${LIBMUDLET_BUILD_DIR}/libmudlet.cmake")
    message(FATAL_ERROR "${LIBMUDLET_BUILD_DIR}/libmudlet.cmake is missing: run scripts/export-libmudlet.py on that tree")
endif()
include("${LIBMUDLET_BUILD_DIR}/libmudlet.cmake")

qt_add_executable(engine ${HOST_SOURCES})
target_link_libraries(engine PRIVATE libmudlet::headless)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # As Mudlet links its runner: a distribution's qt6keychain needs Qt6DBus but has no search path; unlike a
    # RUNPATH, an RPATH finds it beside our Qt
    target_link_options(engine PRIVATE "LINKER:--disable-new-dtags")
endif()
target_link_libraries(engine PRIVATE engine_api Qt6::Core Qt6::Gui Qt6::Network ZLIB::ZLIB)
if(ANDROID)
    # QtAndroidPrivate's service-setup semaphore; ties the build to this exact Qt version
    find_package(Qt6 REQUIRED COMPONENTS CorePrivate)
    target_link_libraries(engine PRIVATE Qt6::CorePrivate)
endif()
set_target_properties(engine PROPERTIES AUTOMOC ON)

if(ANDROID)
    set_target_properties(engine PROPERTIES
        QT_ANDROID_PACKAGE_NAME org.mudlet.mudlet
        QT_ANDROID_MIN_SDK_VERSION 28
        QT_ANDROID_TARGET_SDK_VERSION 36
        QT_ANDROID_COMPILE_SDK_VERSION 36)

    # Qt's TLS backend dlopens OpenSSL 3 as libcrypto_3.so and libssl_3.so, which Android does not have: without them
    # Qt has no TLS backend, so neither games over TLS nor https work. scripts/build-openssl.sh builds them;
    # androiddeployqt copies QT_ANDROID_EXTRA_LIBS into the APK and lists them in libs.xml, so Qt loads them at start.
    set(ANDROID_OPENSSL_DIR "" CACHE PATH "Folder with libcrypto_3.so and libssl_3.so for ${ANDROID_ABI} (scripts/build-openssl.sh)")
    if(NOT ANDROID_OPENSSL_DIR)
        message(FATAL_ERROR "Set ANDROID_OPENSSL_DIR to the lib folder of a prefix scripts/build-openssl.sh made")
    endif()
    set(openssl_libs "${ANDROID_OPENSSL_DIR}/libcrypto_3.so" "${ANDROID_OPENSSL_DIR}/libssl_3.so")
    foreach(lib IN LISTS openssl_libs)
        if(NOT EXISTS "${lib}")
            message(FATAL_ERROR "No ${lib}: is ANDROID_OPENSSL_DIR the lib folder of a prefix scripts/build-openssl.sh made?")
        endif()
    endforeach()
    set_property(TARGET engine APPEND PROPERTY QT_ANDROID_EXTRA_LIBS ${openssl_libs})
endif()
