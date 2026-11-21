# Builds libtsdroid_core.so for arm64.
#
# Copying the result into the app is deliberately a separate step (printed at the
# end, not executed): a build that failed must never leave the previous .so in
# place, looking like the new code shipped.
#
# Requires: cargo-ndk, the Android NDK, and cmake+ninja (audiopus compiles the
# libopus C sources through them). The SDK's own cmake is used when present,
# because a machine without cmake on PATH otherwise fails deep inside a
# dependency build.
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}"
export ANDROID_NDK="$ANDROID_NDK_HOME"

if [ -d "$SDK/cmake/3.22.1/bin" ]; then
    export PATH="$SDK/cmake/3.22.1/bin:$PATH"
fi
# cmake otherwise picks the Visual Studio generator, which cannot cross-compile.
export CMAKE_GENERATOR=Ninja

cargo ndk -t arm64-v8a -o target/jniLibs build --release

SO=target/jniLibs/arm64-v8a/libtsdroid_core.so

echo
echo "Built:"
ls -lh "$SO"
echo
echo "To install into the app:"
echo "  cp $SO ../app/src/main/jniLibs/arm64-v8a/"
