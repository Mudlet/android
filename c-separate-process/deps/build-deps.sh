#!/bin/bash
# Cross-compiles libmudlet's third-party libraries for Android as static libraries in one prefix:
# PCRE2, pugixml, libzip and hunspell from pinned upstream releases. zlib comes from the NDK, Lua 5.1,
# communi and qtkeychain from Mudlet's own tree, and Boost (header-only for Mudlet) from the host.
# Usage: ANDROID_ABI=x86_64 deps/build-deps.sh   ->  build-deps-<abi>/prefix
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
abi=${ANDROID_ABI:-arm64-v8a}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
ndk=${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/28.2.13676358}
api=28
src="$here/build-deps-src"
work="$here/build-deps-$abi"
prefix="$work/prefix"
jobs=${JOBS:-4}
mkdir -p "$src" "$work" "$prefix"

fetch() { # name url sha256
    local file="$src/$1-$(basename "$2")"
    if [ ! -f "$file" ]; then
        curl -fsSL --retry 3 -o "$file.part" "$2"
        mv "$file.part" "$file"
    fi
    echo "$3  $file" | sha256sum -c --quiet - || { echo "checksum mismatch for $1" >&2; exit 1; }
    rm -rf "$work/$1-src"
    mkdir -p "$work/$1-src"
    if [[ $file == *.zip ]]; then
        unzip -q "$file" -d "$work/$1-src"
    else
        tar -xf "$file" -C "$work/$1-src" --strip-components=1
    fi
}

cmake_build() { # name source-dir [cmake args...]
    local name=$1 dir=$2
    shift 2
    cmake -S "$dir" -B "$work/$name-build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF "$@" > "$work/$name-configure.log"
    build-slot ninja -C "$work/$name-build" -j "$jobs" install > "$work/$name-build.log"
    echo "built $name"
}

fetch pcre2 https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.45/pcre2-10.45.tar.gz \
    0e138387df7835d7403b8351e2226c1377da804e0737db0e071b48f07c9d12ee
cmake_build pcre2 "$work/pcre2-src" -DPCRE2_BUILD_PCRE2_8=ON -DPCRE2_BUILD_PCRE2_16=OFF -DPCRE2_BUILD_PCRE2_32=OFF \
    -DPCRE2_SUPPORT_UNICODE=ON -DPCRE2_SUPPORT_JIT=ON -DPCRE2_BUILD_TESTS=OFF -DPCRE2_BUILD_PCRE2GREP=OFF

fetch pugixml https://github.com/zeux/pugixml/releases/download/v1.15/pugixml-1.15.tar.gz \
    655ade57fa703fb421c2eb9a0113b5064bddb145d415dd1f88c79353d90d511a
cmake_build pugixml "$work/pugixml-src" -DPUGIXML_BUILD_TESTS=OFF

fetch libzip https://github.com/nih-at/libzip/releases/download/v1.11.4/libzip-1.11.4.tar.gz \
    82e9f2f2421f9d7c2466bbc3173cd09595a88ea37db0d559a9d0a2dc60dc722e
cmake_build libzip "$work/libzip-src" -DBUILD_TOOLS=OFF -DBUILD_REGRESS=OFF -DBUILD_OSSFUZZ=OFF -DBUILD_EXAMPLES=OFF \
    -DBUILD_DOC=OFF -DENABLE_BZIP2=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_OPENSSL=OFF -DENABLE_GNUTLS=OFF \
    -DENABLE_MBEDTLS=OFF -DENABLE_COMMONCRYPTO=OFF -DENABLE_WINDOWS_CRYPTO=OFF

# hunspell ships autotools only; its library is plain C++ with one generated header
fetch hunspell https://github.com/hunspell/hunspell/releases/download/v1.7.2/hunspell-1.7.2.tar.gz \
    11ddfa39afe28c28539fe65fc4f1592d410c1e9b6dd7d8a91ca25d85e9ec65b8
cat > "$work/hunspell-src/src/hunspell/hunvisapi.h" <<'EOF'
#ifndef HUNVISAPI_H_
#define HUNVISAPI_H_
#define LIBHUNSPELL_DLL_EXPORTED
#endif
EOF
cp "$here/deps/hunspell.cmake" "$work/hunspell-src/CMakeLists.txt"
cmake_build hunspell "$work/hunspell-src"

# Lua C modules Mudlet requires, as sources only: the engine compiles them against Mudlet's Lua and puts them
# in package.preload, as Android loads no Lua modules from files
fetch lfs https://github.com/lunarmodules/luafilesystem/archive/refs/tags/v1_8_0.tar.gz \
    16d17c788b8093f2047325343f5e9b74cccb1ea96001e45914a58bbae8932495
fetch lrexlib https://github.com/rrthomas/lrexlib/archive/refs/tags/rel-2-9-2.tar.gz \
    e32577792b199b2daa0106df59417c29f7e22f232c594be75564e3cc201ec12e
fetch lpeg https://www.inf.puc-rio.br/~roberto/lpeg/lpeg-1.1.0.tar.gz \
    4b155d67d2246c1ffa7ad7bc466c1ea899bbc40fef0257cc9c03cecbaed4352a
fetch luautf8 https://github.com/starwing/luautf8/archive/refs/tags/0.1.6.tar.gz \
    b76873b4afb09abd00508a66887ae1658492078869f9414c334dea35b5769656
fetch yajl https://github.com/lloyd/yajl/archive/refs/tags/2.1.0.tar.gz \
    3fb73364a5a30efe615046d07e6db9d09fd2b41c763c5f7d3bfb121cd5c5ac5a
# yajl's headers are included as <yajl/...>, and one is generated
mkdir -p "$work/yajl-src/include/yajl"
cp "$work/yajl-src/src/api/"*.h "$work/yajl-src/include/yajl/"
sed -e 's/\${YAJL_MAJOR}/2/; s/\${YAJL_MINOR}/1/; s/\${YAJL_MICRO}/0/' "$work/yajl-src/src/api/yajl_version.h.cmake" \
    > "$work/yajl-src/include/yajl/yajl_version.h"
# lua-yajl and lua-zip have no release since their last fixes, so a commit each
fetch lua-yajl https://github.com/brimworks/lua-yajl/archive/1dab6c43bad1142a53e168773b6e9f95f9dc2b75.tar.gz \
    e42c66caaf92c9c35aba9efe76d5cb3d8ad35b6519e91c84cc8b614cf319e9fd
fetch lua-zip https://github.com/brimworks/lua-zip/archive/291ca28a5b36792c8cda23c8de37d07c69f82a8a.tar.gz \
    9f6a0f1cf85e2f766cd244215b125de4e0c97beb535baf60b03e0ecf576cb16e
fetch luasql https://github.com/lunarmodules/luasql/archive/refs/tags/2.7.0.tar.gz \
    15de59f0ea1a2ab205a11e0115d760f8e0c3fee0d3fcc37f52e326a53236e0c7
fetch sqlite https://www.sqlite.org/2025/sqlite-amalgamation-3500400.zip \
    1d3049dd0f830a025a53105fc79fd2ab9431aea99e137809d064d8ee8356b032

# Boost: Mudlet uses header-only parts (graph, range), which are the same for every target
mkdir -p "$prefix/include" "$prefix/lib/cmake/Boost"
ln -sfn "${BOOST_INCLUDE:-/usr/include}/boost" "$prefix/include/boost"
cat > "$prefix/lib/cmake/Boost/BoostConfig.cmake" <<'EOF'
set(Boost_VERSION 1.90.0)
set(Boost_FOUND TRUE)
if(NOT TARGET Boost::boost)
  add_library(Boost::boost INTERFACE IMPORTED)
  set_target_properties(Boost::boost PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
EOF
cat > "$prefix/lib/cmake/Boost/BoostConfigVersion.cmake" <<'EOF'
set(PACKAGE_VERSION 1.90.0)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
EOF
echo "deps for $abi in $prefix"
