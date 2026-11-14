#!/bin/sh
# Builds app/src/main/jniLibs/arm64-v8a/libgcam100x_native.so with the Android NDK.
# Usage: NDK=/path/to/ndk native/build.sh
set -e
cd "$(dirname "$0")/.."
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
mkdir -p app/src/main/jniLibs/arm64-v8a
"$CC" -O2 -fPIC -shared -Wall -Wextra -fvisibility=hidden -Wl,--build-id -Wl,-z,max-page-size=16384 \
  -o app/src/main/jniLibs/arm64-v8a/libgcam100x_native.so native/gcam100x_native.c -llog
