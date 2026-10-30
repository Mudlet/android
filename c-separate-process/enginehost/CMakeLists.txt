set(HOST_SOURCES main.cpp SocketBridge.cpp SocketBridge.h)
if(ANDROID)
    list(APPEND HOST_SOURCES AndroidNotifier.cpp AndroidNotifier.h AndroidServiceSetup.cpp AndroidServiceSetup.h)
endif()

# On Android this produces libengine_arm64-v8a.so, loaded by QtServiceBase in the :engine process
# Set MUDLET_SOURCE_DIR and MUDLET_BUILD_DIR to run libmudlet (mudlet_headless) instead of the stand-in engine.
# The Mudlet build is its own tree (see deps/build-mudlet.sh); its link line is copied here, as it exports no targets.
set(MUDLET_SOURCE_DIR "" CACHE PATH "Mudlet source tree with src/headless")
set(MUDLET_BUILD_DIR "" CACHE PATH "Mudlet build tree for this ABI, with mudlet_headless built")
set(MUDLET_DEPS_PREFIX "" CACHE PATH "Prefix from deps/build-deps.sh for this ABI")
if(MUDLET_BUILD_DIR)
    # LuaModules.c in the engine itself: Mudlet's weak reference to it would not pull it out of an archive
    list(APPEND HOST_SOURCES MudletEngine.cpp MudletEngine.h LuaModules.c)
    # The Lua C modules Mudlet requires, from the sources deps/build-deps.sh fetched, against Mudlet's own Lua
    set(mods "${MUDLET_DEPS_PREFIX}/..")
    add_library(lua_modules STATIC
        "${mods}/lfs-src/src/lfs.c"
        "${mods}/lpeg-src/lpcap.c" "${mods}/lpeg-src/lpcode.c" "${mods}/lpeg-src/lpcset.c" "${mods}/lpeg-src/lpprint.c"
        "${mods}/lpeg-src/lptree.c" "${mods}/lpeg-src/lpvm.c"
        "${mods}/lrexlib-src/src/common.c" "${mods}/lrexlib-src/src/pcre2/lpcre2.c" "${mods}/lrexlib-src/src/pcre2/lpcre2_f.c"
        "${mods}/luautf8-src/lutf8lib.c"
        "${mods}/lua-yajl-src/lua_yajl.c"
        "${mods}/lua-zip-src/lua_zip.c"
        "${mods}/luasql-src/src/luasql.c" "${mods}/luasql-src/src/ls_sqlite3.c"
        "${mods}/sqlite-src/sqlite-amalgamation-3500400/sqlite3.c")
    file(GLOB yajl_sources "${mods}/yajl-src/src/*.c")
    target_sources(lua_modules PRIVATE ${yajl_sources})
    target_include_directories(lua_modules PRIVATE "${MUDLET_SOURCE_DIR}/3rdparty/lua51/src" "${MUDLET_DEPS_PREFIX}/include"
        "${mods}/yajl-src/include" "${mods}/yajl-src/src" "${mods}/sqlite-src/sqlite-amalgamation-3500400")
    target_compile_definitions(lua_modules PRIVATE PCRE2_CODE_UNIT_WIDTH=8 PCRE2_STATIC VERSION="2.9.2"
        SQLITE_OMIT_LOAD_EXTENSION SQLITE_THREADSAFE=1)
    target_compile_options(lua_modules PRIVATE -w)
endif()

qt_add_executable(engine ${HOST_SOURCES})
if(MUDLET_BUILD_DIR)
    find_package(Qt6 REQUIRED COMPONENTS Concurrent Core5Compat Multimedia OpenGL Svg TextToSpeech)
    set(keychain "${MUDLET_BUILD_DIR}/bin/libqt6keychain.so")
    # MudletEngine reads Host and the console model, so it must see their classes as mudlet_core was built:
    # the same defines and headers
    target_compile_definitions(engine PRIVATE ENGINE_LIBMUDLET
        HUNSPELL_STATIC INCLUDE_OWN_QT6_KEYCHAIN INCLUDE_VARIABLE_SPLASH_SCREEN IRC_STATIC PCRE2_STATIC QTKEYCHAIN_NO_EXPORT)
    target_include_directories(engine PRIVATE
        "${MUDLET_SOURCE_DIR}/src/headless"
        "${MUDLET_SOURCE_DIR}/src"
        "${MUDLET_BUILD_DIR}/src"
        "${MUDLET_BUILD_DIR}/src/mudlet_core_autogen/include"
        "${MUDLET_SOURCE_DIR}/3rdparty/qtkeychain"
        "${MUDLET_BUILD_DIR}/3rdparty/qtkeychain/qtkeychain"
        "${MUDLET_SOURCE_DIR}/3rdparty/communi/include/IrcCore"
        "${MUDLET_SOURCE_DIR}/3rdparty/communi/include/IrcModel"
        "${MUDLET_SOURCE_DIR}/3rdparty/communi/include/IrcUtil"
        "${MUDLET_SOURCE_DIR}/3rdparty/lua51/src"
        "${MUDLET_DEPS_PREFIX}/include")
    target_link_libraries(engine PRIVATE engine_api lua_modules
        "${MUDLET_BUILD_DIR}/src/headless/libmudlet_headless.a"
        "${MUDLET_BUILD_DIR}/src/libmudlet_core.a"
        "${MUDLET_BUILD_DIR}/3rdparty/communi/libcommuni.a"
        "${MUDLET_BUILD_DIR}/3rdparty/lua51/liblua51.a"
        "${MUDLET_DEPS_PREFIX}/lib/libhunspell.a"
        "${MUDLET_DEPS_PREFIX}/lib/libpcre2-8.a"
        "${MUDLET_DEPS_PREFIX}/lib/libpugixml.a"
        "${MUDLET_DEPS_PREFIX}/lib/libzip.a"
        "${keychain}"
        z dl m GLESv2 EGL
        Qt6::Core Qt6::Gui Qt6::Network Qt6::Concurrent Qt6::Core5Compat Qt6::Multimedia Qt6::OpenGL Qt6::Svg Qt6::TextToSpeech)
    if(ANDROID)
        set_target_properties(engine PROPERTIES QT_ANDROID_EXTRA_LIBS "${keychain}")
    endif()
else()
    target_link_libraries(engine PRIVATE engine_core)
endif()
target_link_libraries(engine PRIVATE Qt6::Core Qt6::Gui Qt6::Network)
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
