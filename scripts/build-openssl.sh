#!/usr/bin/env bash
# Cross-compiles OpenSSL's libcrypto and libssl for Android, as the shared libraries Qt's TLS backend loads at run
# time: Qt for Android is built against OpenSSL 3 and dlopens libcrypto_3.so and libssl_3.so (the major version as a
# suffix, so that the system's own libcrypto.so and libssl.so are never picked up). Their SONAMEs carry the suffix too,
# and libssl_3.so needs libcrypto_3.so. Writes <prefix>/lib/libcrypto_3.so and libssl_3.so, stripped and 16 KB page
# aligned, and <prefix>/versions.txt.
#
# Usage: ANDROID_NDK_ROOT=... scripts/build-openssl.sh <prefix>
# Optional: ANDROID_ABI (arm64-v8a or x86_64), ANDROID_API (28), JOBS (nproc), WORK_DIR (default <prefix>.work: a
# scratch directory for this script alone, as the whole directory is deleted when the script succeeds unless
# KEEP_WORK=1).
set -euo pipefail

prefix=$(realpath -m "${1:?usage: $0 <prefix>}")
abi=${ANDROID_ABI:-arm64-v8a}
api=${ANDROID_API:-28}
ndk=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}
jobs=${JOBS:-$(nproc)}
work=$(realpath -m "${WORK_DIR:-$prefix.work}")

openssl_version=3.5.9 # an LTS release, supported until April 2030
openssl_sha256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
# Qt's suffix: "_" and the major version Qt was built against (QT_OPENSSL_VERSION in qsslsocket_openssl_symbols.cpp)
variant=_${openssl_version%%.*}

case $abi in
    arm64-v8a) target=android-arm64 ;;
    x86_64) target=android-x86_64 ;;
    *) echo "ANDROID_ABI=$abi: only arm64-v8a and x86_64 are supported" >&2; exit 2 ;;
esac

bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$bin/clang" ] || { echo "no NDK clang in $bin" >&2; exit 1; }

mkdir -p "$work/dl"
url="https://github.com/openssl/openssl/releases/download/openssl-$openssl_version/openssl-$openssl_version.tar.gz"
file="$work/dl/$(basename "$url")"
if [ ! -f "$file" ]; then
    curl -fsSL --retry 3 -o "$file.part" "$url"
    mv "$file.part" "$file"
fi
echo "$openssl_sha256  $file" | sha256sum -c --quiet - || { echo "checksum mismatch: $url" >&2; exit 1; }
src="$work/openssl"
rm -rf "$src"
mkdir -p "$src"
tar -xf "$file" -C "$src" --strip-components=1

# OpenSSL's own Android targets, with the suffix as shlib_variant. Its Android config reads the architecture from
# the target name's last part, so the suffix goes at the front.
cat > "$work/qt-android.conf" <<EOF
my %targets = (
    "qt-$target" => {
        inherit_from  => [ "$target" ],
        shlib_variant => "$variant",
    },
);
EOF

# OpenSSL's Android config finds the NDK's clang on PATH; the API level picks aarch64-linux-android<api>-clang.
# No apps, tests or docs are built; engines, the legacy provider and loadable modules are left out, as Qt uses none
# of them.
log="$work/openssl.log"
if ! ( cd "$src" \
        && PATH="$bin:$PATH" ./Configure "qt-$target" --config="$work/qt-android.conf" \
            -D__ANDROID_API__="$api" -Wl,-z,max-page-size=16384 \
            shared no-tests no-docs no-engine no-legacy no-module \
        && PATH="$bin:$PATH" make -j "$jobs" build_libs ) > "$log" 2>&1; then
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        # the work dir is gone when the job ends, so the whole log goes into the job's output
        echo "::group::openssl.log"; cat "$log"; echo "::endgroup::"
        echo "::error::building OpenSSL failed; its log is in the group above"
    else
        tail -n 40 "$log" >&2
        echo "building OpenSSL failed; full log: $log" >&2
    fi
    exit 1
fi

# What Qt's backend checks before it uses the libraries, so that a build that would be refused on a device fails here
readelf="$bin/llvm-readelf"
mkdir -p "$prefix/lib"
for lib in "libcrypto$variant.so" "libssl$variant.so"; do
    [ -f "$src/$lib" ] || { echo "OpenSSL built no $lib" >&2; exit 1; }
    "$bin/llvm-strip" --strip-all -o "$prefix/lib/$lib" "$src/$lib"
    soname=$("$readelf" -dW "$prefix/lib/$lib" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
    [ "$soname" = "$lib" ] || { echo "$lib has the SONAME '$soname'; Qt loads it by its file name" >&2; exit 1; }
    for align in $("$readelf" -lW "$prefix/lib/$lib" | awk '$1 == "LOAD" { print $NF }'); do
        (( align >= 0x4000 )) || { echo "$lib has segments aligned to $align, below 16 KB" >&2; exit 1; }
    done
done
needed=$("$readelf" -dW "$prefix/lib/libssl$variant.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
grep -qxF "libcrypto$variant.so" <<< "$needed" \
    || { printf 'libssl%s.so does not need libcrypto%s.so, but:\n%s\n' "$variant" "$variant" "$needed" >&2; exit 1; }

cat > "$prefix/versions.txt" <<EOF
abi $abi, android-$api, NDK $(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties")
openssl $openssl_version (libcrypto$variant.so, libssl$variant.so)
EOF

[ "${KEEP_WORK:-0}" = 1 ] || rm -rf "${work:?}"
echo "OpenSSL $openssl_version for $abi in $prefix/lib"
