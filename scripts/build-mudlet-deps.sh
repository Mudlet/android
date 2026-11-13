#!/usr/bin/env bash
# Cross-compiles the third-party libraries mudlet_core needs and Qt for Android does not ship, as static
# libraries in one prefix: PCRE2, pugixml, libzip, yajl, hunspell, SQLite, QtKeychain and Boost's headers.
# zlib comes from the NDK; Lua 5.1, communi and the Lua C modules are built by Mudlet's own CMake.
#
# Usage: ANDROID_NDK_ROOT=... QT_ROOT=<dir with gcc_64 and android_arm64_v8a> scripts/build-mudlet-deps.sh <prefix>
# Optional: QT_ANDROID / QT_HOST_PATH instead of QT_ROOT, ANDROID_ABI (arm64-v8a), ANDROID_API (28), JOBS (nproc),
# WORK_DIR (default <prefix>.work: a scratch directory for this script alone, as the whole directory is deleted
# when the script succeeds unless KEEP_WORK=1).
set -euo pipefail

prefix=$(realpath -m "${1:?usage: $0 <prefix>}")
abi=${ANDROID_ABI:-arm64-v8a}
api=${ANDROID_API:-28}
ndk=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}
qt_android=${QT_ANDROID:-${QT_ROOT:?set QT_ROOT, or QT_ANDROID and QT_HOST_PATH}/android_${abi//-/_}}
qt_host=${QT_HOST_PATH:-${QT_ROOT:?set QT_ROOT, or QT_ANDROID and QT_HOST_PATH}/gcc_64}
jobs=${JOBS:-$(nproc)}
work=$(realpath -m "${WORK_DIR:-$prefix.work}")

pcre2_version=10.45
pugixml_version=1.15
libzip_version=1.11.4
hunspell_version=1.7.2
sqlite_version=3.50.4
sqlite_file=2025/sqlite-amalgamation-3500400.zip
yajl_version=2.1.0
yajl_commit=a0ecdde0c042b9256170f2f8890dd9451a4240aa # tag 2.1.0 (a branch has the same name)
qtkeychain_version=0.17.0
qtkeychain_commit=875f77d9f61bd97fd84cca47ce3bc71186dfbd09 # tag 0.17.0
boost_version=1.89.0

mkdir -p "$prefix" "$work/dl"

fetch() { # name url sha256 [member of the archive to extract] -> $work/<name>
    local name=$1 url=$2 sha=$3 member=${4:-} file
    file="$work/dl/$(basename "$url")"
    if [ ! -f "$file" ]; then
        curl -fsSL --retry 3 -o "$file.part" "$url"
        mv "$file.part" "$file"
    fi
    echo "$sha  $file" | sha256sum -c --quiet - || { echo "checksum mismatch: $url" >&2; exit 1; }
    rm -rf "${work:?}/$name"
    mkdir -p "$work/$name"
    if [[ $file == *.zip ]]; then
        unzip -q "$file" -d "$work/$name.zip"
        mv "$work/$name.zip"/*/* "$work/$name/"
        rm -rf "$work/$name.zip"
    else
        tar -xf "$file" -C "$work/$name" --strip-components=1 ${member:+"$member"}
    fi
}

git_fetch() { # name url commit -> $work/<name>
    local name=$1 url=$2 commit=$3
    rm -rf "${work:?}/$name"
    git init -q "$work/$name"
    git -C "$work/$name" fetch -q --depth 1 "$url" "$commit"
    git -C "$work/$name" -c advice.detachedHead=false checkout -q FETCH_HEAD
}

cmake_build() { # name source-dir [cmake args...]
    local name=$1 dir=$2
    shift 2
    if ! { cmake -S "$dir" -B "$work/$name-build" -G Ninja \
            -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" -DANDROID_STL=c++_shared \
            -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" \
            -DCMAKE_PREFIX_PATH="$prefix" -DCMAKE_FIND_ROOT_PATH="$prefix" \
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF "$@" \
        && cmake --build "$work/$name-build" -j "$jobs" \
        && cmake --install "$work/$name-build"; } > "$work/$name.log" 2>&1; then
        if [ -n "${GITHUB_ACTIONS:-}" ]; then
            # the work dir is gone when the job ends, so the whole log goes into the job's output
            echo "::group::$name.log"; cat "$work/$name.log"; echo "::endgroup::"
            echo "::error::building $name failed; its log is in the group above"
        else
            tail -n 40 "$work/$name.log" >&2
            echo "building $name failed; full log: $work/$name.log" >&2
        fi
        exit 1
    fi
    echo "built $name"
}

fetch pcre2 "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$pcre2_version/pcre2-$pcre2_version.tar.gz" \
    0e138387df7835d7403b8351e2226c1377da804e0737db0e071b48f07c9d12ee
cmake_build pcre2 "$work/pcre2" -DPCRE2_BUILD_PCRE2_8=ON -DPCRE2_BUILD_PCRE2_16=OFF -DPCRE2_BUILD_PCRE2_32=OFF \
    -DPCRE2_SUPPORT_UNICODE=ON -DPCRE2_SUPPORT_JIT=ON -DPCRE2_BUILD_TESTS=OFF -DPCRE2_BUILD_PCRE2GREP=OFF

fetch pugixml "https://github.com/zeux/pugixml/releases/download/v$pugixml_version/pugixml-$pugixml_version.tar.gz" \
    655ade57fa703fb421c2eb9a0113b5064bddb145d415dd1f88c79353d90d511a
cmake_build pugixml "$work/pugixml" -DPUGIXML_BUILD_TESTS=OFF

fetch libzip "https://github.com/nih-at/libzip/releases/download/v$libzip_version/libzip-$libzip_version.tar.gz" \
    82e9f2f2421f9d7c2466bbc3173cd09595a88ea37db0d559a9d0a2dc60dc722e
cmake_build libzip "$work/libzip" -DBUILD_TOOLS=OFF -DBUILD_REGRESS=OFF -DBUILD_OSSFUZZ=OFF -DBUILD_EXAMPLES=OFF \
    -DBUILD_DOC=OFF -DENABLE_BZIP2=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_OPENSSL=OFF -DENABLE_GNUTLS=OFF \
    -DENABLE_MBEDTLS=OFF -DENABLE_COMMONCRYPTO=OFF -DENABLE_WINDOWS_CRYPTO=OFF

# yajl's own CMake also builds its tools and tests; only the library is wanted
git_fetch yajl https://github.com/lloyd/yajl.git "$yajl_commit"
cat > "$work/yajl/CMakeLists.txt" <<EOF
cmake_minimum_required(VERSION 3.16)
project(yajl C)
set(YAJL_MAJOR 2)
set(YAJL_MINOR 1)
set(YAJL_MICRO 0)
file(GLOB api_headers src/api/*.h)
file(COPY \${api_headers} DESTINATION \${CMAKE_BINARY_DIR}/include/yajl)
configure_file(src/api/yajl_version.h.cmake \${CMAKE_BINARY_DIR}/include/yajl/yajl_version.h)
file(GLOB sources src/*.c)
add_library(yajl STATIC \${sources})
target_include_directories(yajl PRIVATE \${CMAKE_BINARY_DIR}/include)
install(TARGETS yajl ARCHIVE DESTINATION lib)
install(DIRECTORY \${CMAKE_BINARY_DIR}/include/yajl DESTINATION include)
EOF
cmake_build yajl "$work/yajl"

# hunspell ships autotools only; its library is plain C++ with one generated header
fetch hunspell "https://github.com/hunspell/hunspell/releases/download/v$hunspell_version/hunspell-$hunspell_version.tar.gz" \
    11ddfa39afe28c28539fe65fc4f1592d410c1e9b6dd7d8a91ca25d85e9ec65b8
cat > "$work/hunspell/src/hunspell/hunvisapi.h" <<'EOF'
#ifndef HUNVISAPI_H_
#define HUNVISAPI_H_
#define LIBHUNSPELL_DLL_EXPORTED
#endif
EOF
cat > "$work/hunspell/CMakeLists.txt" <<'EOF'
cmake_minimum_required(VERSION 3.16)
project(hunspell CXX)
file(GLOB sources src/hunspell/*.cxx)
add_library(hunspell STATIC ${sources})
target_compile_features(hunspell PRIVATE cxx_std_11)
target_compile_definitions(hunspell PRIVATE BUILDING_LIBHUNSPELL)
install(TARGETS hunspell ARCHIVE DESTINATION lib)
install(FILES src/hunspell/atypes.hxx src/hunspell/hunspell.h src/hunspell/hunspell.hxx src/hunspell/hunvisapi.h
              src/hunspell/w_char.hxx
        DESTINATION include/hunspell)
EOF
cmake_build hunspell "$work/hunspell"

# Only Mudlet's bundled luasql.sqlite3 module uses it
fetch sqlite "https://www.sqlite.org/$sqlite_file" \
    1d3049dd0f830a025a53105fc79fd2ab9431aea99e137809d064d8ee8356b032
cat > "$work/sqlite/CMakeLists.txt" <<'EOF'
cmake_minimum_required(VERSION 3.16)
project(sqlite3 C)
add_library(sqlite3 STATIC sqlite3.c)
target_compile_definitions(sqlite3 PRIVATE SQLITE_THREADSAFE=1 SQLITE_ENABLE_FTS5)
install(TARGETS sqlite3 ARCHIVE DESTINATION lib)
install(FILES sqlite3.h sqlite3ext.h DESTINATION include)
EOF
cmake_build sqlite "$work/sqlite"

# The same commit as Mudlet's 3rdparty/qtkeychain submodule
git_fetch qtkeychain https://github.com/frankosterfeld/qtkeychain.git "$qtkeychain_commit"
cmake_build qtkeychain "$work/qtkeychain" -DQT_HOST_PATH="$qt_host" \
    -DCMAKE_PREFIX_PATH="$prefix;$qt_android" -DCMAKE_FIND_ROOT_PATH="$prefix;$qt_android" \
    -DBUILD_WITH_QT6=ON -DBUILD_TRANSLATIONS=OFF -DBUILD_TEST_APPLICATION=OFF

# Mudlet uses only header-only Boost (graph, range); find_package(Boost CONFIG) gets a small config for them
boost_dir=boost_${boost_version//./_}
fetch boost "https://archives.boost.io/release/$boost_version/source/$boost_dir.tar.gz" \
    9de758db755e8330a01d995b0a24d09798048400ac25c03fc5ea9be364b13c93 "$boost_dir/boost"
rm -rf "$prefix/include/boost"
mv "$work/boost/boost" "$prefix/include/boost"
mkdir -p "$prefix/lib/cmake/Boost-$boost_version"
cat > "$prefix/lib/cmake/Boost-$boost_version/BoostConfig.cmake" <<EOF
set(Boost_VERSION $boost_version)
set(Boost_FOUND TRUE)
if(NOT TARGET Boost::boost)
  add_library(Boost::boost INTERFACE IMPORTED)
  set_target_properties(Boost::boost PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "\${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
EOF
cat > "$prefix/lib/cmake/Boost-$boost_version/BoostConfigVersion.cmake" <<EOF
set(PACKAGE_VERSION $boost_version)
if(PACKAGE_FIND_VERSION VERSION_GREATER PACKAGE_VERSION)
  set(PACKAGE_VERSION_COMPATIBLE FALSE)
else()
  set(PACKAGE_VERSION_COMPATIBLE TRUE)
endif()
EOF
echo "installed boost $boost_version headers"

cat > "$prefix/versions.txt" <<EOF
abi $abi, android-$api, NDK $(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties")
pcre2 $pcre2_version
pugixml $pugixml_version
libzip $libzip_version
yajl $yajl_version
hunspell $hunspell_version
sqlite $sqlite_version
qtkeychain $qtkeychain_version
boost $boost_version (headers)
EOF

[ "${KEEP_WORK:-0}" = 1 ] || rm -rf "${work:?}"
echo "deps for $abi in $prefix"
