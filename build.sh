#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT_DIR/build"
APP_MAIN="$ROOT_DIR/app/src/main"
MANIFEST="$APP_MAIN/AndroidManifest.xml"
OUT_UNSIGNED_APK="$BUILD_DIR/rekorder-unsigned.apk"
OUT_APK="$BUILD_DIR/rekorder.apk"

UNSIGNED_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --unsigned) UNSIGNED_ONLY=1 ;;
    esac
done

# 1. Locate JDK (javac + keytool)
if ! command -v javac >/dev/null 2>&1; then
    for cand in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/default; do
        if [[ -x "$cand/bin/javac" ]]; then
            export JAVA_HOME="$cand"
            export PATH="$cand/bin:$PATH"
            break
        fi
    done
fi

if ! command -v javac >/dev/null 2>&1; then
    echo "ERROR: javac not found. Install JDK 17+ or set JAVA_HOME." >&2
    exit 1
fi

# 2. Locate Android SDK
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR/platforms" ]]; then
    for cand in /shared/coding/Android/SDK /opt/android-sdk "$HOME/Android/Sdk"; do
        if [[ -d "$cand/platforms" && -d "$cand/build-tools" ]]; then
            SDK_DIR="$cand"
            break
        fi
    done
fi

if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR/platforms" ]]; then
    echo "ERROR: Android SDK with platforms/ and build-tools/ not found. Set ANDROID_HOME." >&2
    exit 1
fi

# 3. Locate clang/lld/readelf (prefer explicitly configured Android NDK if present, else PATH)
NDK_LLVM_BIN=""
NDK_SYSROOT_INC=""
for ndk_cand in "${ANDROID_NDK:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}" "${NDK:-}" "$SDK_DIR"/ndk/*; do
    if [[ -n "$ndk_cand" && -x "$ndk_cand/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
        NDK_LLVM_BIN="$ndk_cand/toolchains/llvm/prebuilt/linux-x86_64/bin"
        NDK_SYSROOT_INC="$ndk_cand/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
        break
    fi
done

if [[ -n "$NDK_LLVM_BIN" ]]; then
    export PATH="$NDK_LLVM_BIN:$PATH"
fi

if ! command -v clang >/dev/null 2>&1; then
    echo "ERROR: clang not found. Install clang+lld or set ANDROID_NDK / ANDROID_NDK_HOME." >&2
    exit 1
fi

READELF_BIN="readelf"
if ! command -v readelf >/dev/null 2>&1 && command -v llvm-readelf >/dev/null 2>&1; then
    READELF_BIN="llvm-readelf"
fi

# Pick newest installed build-tools & android-35/36 platform
BUILD_TOOLS_VER="$(ls -1 "$SDK_DIR/build-tools" | sort -V | tail -n 1)"
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VER"
if [[ -f "$SDK_DIR/platforms/android-35/android.jar" ]]; then
    PLATFORM_JAR="$SDK_DIR/platforms/android-35/android.jar"
else
    PLAT_VER="$(ls -1 "$SDK_DIR/platforms" | sort -V | tail -n 1)"
    PLATFORM_JAR="$SDK_DIR/platforms/$PLAT_VER/android.jar"
fi

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

# Parse versionCode and versionName directly from AndroidManifest.xml (single source of truth)
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' "$MANIFEST" | head -n 1)"
VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -n 1)"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"

echo "==> SDK        : $SDK_DIR"
echo "==> BuildTools : $BUILD_TOOLS_VER"
echo "==> Platform   : $PLATFORM_JAR"
echo "==> JDK        : $(javac -version 2>&1)"
echo "==> Version    : $VERSION_NAME ($VERSION_CODE)"

rm -rf "$BUILD_DIR/obj" "$BUILD_DIR/gen" "$BUILD_DIR/res_compiled" "$BUILD_DIR/apk_stage"
mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/gen" "$BUILD_DIR/res_compiled" "$BUILD_DIR/apk_stage/lib"

# 4. Compile custom native C AAudio driver (librekorder_aaudio.so) & MP3 encoder (librekorder_mp3.so)
CLANG_INC="$(clang -print-resource-dir)/include"
JAVAC_REAL="$(readlink -f "$(command -v javac)")"
JDK_INC_DIR="$(dirname "$(dirname "$JAVAC_REAL")")/include"
if [[ ! -f "$JDK_INC_DIR/jni.h" && -n "$NDK_SYSROOT_INC" && -f "$NDK_SYSROOT_INC/jni.h" ]]; then
    JDK_INC_DIR="$NDK_SYSROOT_INC"
fi

build_native_abi() {
    local ABI="$1"
    local TRIPLE="$2"
    local EXTRA="$3"
    local STUB_DIR="$BUILD_DIR/stubs/$ABI"
    local OUT_SO_DIR="$BUILD_DIR/apk_stage/lib/$ABI"
    mkdir -p "$STUB_DIR" "$OUT_SO_DIR"

    local COMMON=(
        -target "$TRIPLE"
        -ffreestanding -nostdinc
        -isystem "$CLANG_INC"
        -I"$ROOT_DIR/native/inc_shim"
        -I"$JDK_INC_DIR"
        -ffile-prefix-map="$ROOT_DIR"=.
        -fPIC -fno-stack-protector -O2 -Wall -Wextra -Werror
    )
    if [[ -d "$JDK_INC_DIR/linux" ]]; then
        COMMON+=(-I"$JDK_INC_DIR/linux")
    fi
    if [[ -n "$EXTRA" ]]; then
        # shellcheck disable=SC2206
        COMMON+=($EXTRA)
    fi

    # Link-time stubs for Bionic libc.so & Android libaaudio.so (never packaged)
    clang "${COMMON[@]}" -shared -nostdlib -fuse-ld=lld -Wl,-soname,libc.so \
        -o "$STUB_DIR/libc.so" "$ROOT_DIR/native/stubs/libc_stub.c"
    clang "${COMMON[@]}" -shared -nostdlib -fuse-ld=lld -Wl,-soname,libaaudio.so \
        -o "$STUB_DIR/libaaudio.so" "$ROOT_DIR/native/stubs/libaaudio_stub.c"

    # Real 16KB-page-aligned JNI AAudio driver
    clang "${COMMON[@]}" -std=c11 -fvisibility=hidden -shared -nostdlib -fuse-ld=lld \
        -Wl,-soname,librekorder_aaudio.so \
        -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 \
        -Wl,-z,noexecstack \
        -Wl,-z,relro \
        -Wl,-z,now \
        -Wl,--hash-style=both \
        -Wl,--no-undefined \
        -Wl,--strip-all \
        -o "$OUT_SO_DIR/librekorder_aaudio.so" \
        "$ROOT_DIR/native/rekorder_aaudio.c" \
        -L"$STUB_DIR" -laaudio -lc

    # Guard against forbidden text relocations
    if "$READELF_BIN" -d "$OUT_SO_DIR/librekorder_aaudio.so" | grep -q TEXTREL; then
        echo "ERROR: TEXTREL detected in $ABI/librekorder_aaudio.so" >&2
        exit 1
    fi

    # Built-in freestanding pure-C MPEG-1 Layer III (MP3) encoder (depends ONLY on libc.so)
    clang "${COMMON[@]}" -std=c11 -fvisibility=hidden -shared -nostdlib -fuse-ld=lld \
        -Wl,-soname,librekorder_mp3.so \
        -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 \
        -Wl,-z,noexecstack \
        -Wl,-z,relro \
        -Wl,-z,now \
        -Wl,--hash-style=both \
        -Wl,--no-undefined \
        -Wl,--strip-all \
        -o "$OUT_SO_DIR/librekorder_mp3.so" \
        "$ROOT_DIR/native/mp3_encoder.c" \
        "$ROOT_DIR/native/rekorder_mp3_jni.c" \
        -L"$STUB_DIR" -lc

    if "$READELF_BIN" -d "$OUT_SO_DIR/librekorder_mp3.so" | grep -q TEXTREL; then
        echo "ERROR: TEXTREL detected in $ABI/librekorder_mp3.so" >&2
        exit 1
    fi
}

echo "==> Building custom C AAudio driver + MP3 encoder (arm64-v8a, armeabi-v7a, x86_64, x86)..."
build_native_abi arm64-v8a aarch64-linux-android28 "-mno-outline-atomics"
build_native_abi armeabi-v7a armv7a-linux-androideabi28 "-mfloat-abi=softfp -mfpu=neon"
build_native_abi x86_64 x86_64-linux-android28 ""
build_native_abi x86 i686-linux-android28 ""

# 5. Compile & link Android XML resources with aapt2
echo "==> Compiling Android resources..."
"$AAPT2" compile --dir "$APP_MAIN/res" -o "$BUILD_DIR/res_compiled/res.zip"
"$AAPT2" link \
    -o "$BUILD_DIR/unaligned.apk" \
    -I "$PLATFORM_JAR" \
    --manifest "$MANIFEST" \
    --java "$BUILD_DIR/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD_DIR/res_compiled/res.zip"

# 6. Compile pure Java sources (zero third-party libraries)
echo "==> Compiling Java sources..."
mapfile -t JAVA_FILES < <(LC_ALL=C find "$APP_MAIN/java" "$BUILD_DIR/gen" -name '*.java' | LC_ALL=C sort)
javac \
    --release 11 \
    -classpath "$PLATFORM_JAR" \
    -d "$BUILD_DIR/obj" \
    -Xlint:all,-deprecation,-serial \
    "${JAVA_FILES[@]}"

# 7. Dex bytecode with d8
echo "==> Generating classes.dex..."
mapfile -t CLASS_FILES < <(LC_ALL=C find "$BUILD_DIR/obj" -name '*.class' | LC_ALL=C sort)
"$D8" \
    --release \
    --min-api 26 \
    --lib "$PLATFORM_JAR" \
    --output "$BUILD_DIR/apk_stage" \
    "${CLASS_FILES[@]}"

# 8. Package classes.dex (deflated) + native .so libs (uncompressed for 16KB mmap) deterministically
echo "==> Packaging APK (reproducible timestamps)..."
find "$BUILD_DIR/apk_stage" -exec env TZ=UTC touch -t 198001010000.00 {} +
(
    cd "$BUILD_DIR/apk_stage"
    TZ=UTC zip -X -q -u "$BUILD_DIR/unaligned.apk" classes.dex
    TZ=UTC zip -X -0 -q -u "$BUILD_DIR/unaligned.apk" \
        lib/arm64-v8a/librekorder_aaudio.so \
        lib/arm64-v8a/librekorder_mp3.so \
        lib/armeabi-v7a/librekorder_aaudio.so \
        lib/armeabi-v7a/librekorder_mp3.so \
        lib/x86/librekorder_aaudio.so \
        lib/x86/librekorder_mp3.so \
        lib/x86_64/librekorder_aaudio.so \
        lib/x86_64/librekorder_mp3.so
)

# 9. Align APK (16 KB page alignment for uncompressed .so files on Android 15+)
echo "==> Aligning APK (16 KB native page alignment)..."
if "$ZIPALIGN" -P 16 -f 4 "$BUILD_DIR/unaligned.apk" "$OUT_UNSIGNED_APK" 2>/dev/null; then
    true
else
    "$ZIPALIGN" -p -f 4 "$BUILD_DIR/unaligned.apk" "$OUT_UNSIGNED_APK"
fi
rm -f "$BUILD_DIR/unaligned.apk"

if [[ "$UNSIGNED_ONLY" -eq 1 ]]; then
    echo ""
    echo "UNSIGNED BUILD SUCCEEDED: $OUT_UNSIGNED_APK"
    ls -lh "$OUT_UNSIGNED_APK"
    exit 0
fi

# 10. Sign APK (reuses local debug keystore if present; auto-generates otherwise)
KEYSTORE="$BUILD_DIR/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
    echo "==> Generating local debug signing keystore..."
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias rekorder \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Rekorder,O=Local,C=US" >/dev/null 2>&1
fi

echo "==> Signing APK..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias rekorder \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT_APK" \
    "$OUT_UNSIGNED_APK"

"$APKSIGNER" verify --verbose "$OUT_APK" >/dev/null

echo ""
echo "BUILD SUCCEEDED: $OUT_APK"
ls -lh "$OUT_UNSIGNED_APK" "$OUT_APK"
