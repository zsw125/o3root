#!/usr/bin/env bash
# Copy to env.sh (gitignored) and fill in paths for your machine.
# ---------------------------------------------------------------------------
# App build (required)
# ---------------------------------------------------------------------------
# Android platform 34 android.jar (from platform-34-ext12_r01.zip, see fetch-toolchain.sh)
export ANDROID_JAR=/path/to/android-34-ext12/android.jar

# Build-tools directory containing aapt2, zipalign and lib/d8.jar + lib/apksigner.jar
export BUILD_TOOLS=/path/to/build-tools/34.0.0

# Directory containing api-classes.jar provider-classes.jar aidl-classes.jar shared-classes.jar
export SHIZUKU_JARS=/path/to/shizuku-jars

# JDK 17+ (javac/java). Optional if javac/java are already on PATH.
export JAVA_HOME=/path/to/jdk

# Optional: override d8 jar location
export D8_JAR="$BUILD_TOOLS/lib/d8.jar"

# ---------------------------------------------------------------------------
# Signing (required for the app; create YOUR OWN keystore, never commit it)
# ---------------------------------------------------------------------------
export KEYSTORE=/path/to/release.p12
export KS_PASS='changeit'
export KEY_ALIAS='release'
export KEY_PASS='changeit'

# ---------------------------------------------------------------------------
# Payload build (required)
# ---------------------------------------------------------------------------
# NDK clang wrapper, e.g.
#   $NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android35-clang.cmd
#   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang
export NDK_CC=/path/to/ndk/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android35-clang

# ---------------------------------------------------------------------------
# Optional: regenerate payload/ksu/ksu_symtab.h from a kernel image
# ---------------------------------------------------------------------------
# vmlinux.elf produced by extracting the OTA boot image (see tools/)
export VMLINUX=/path/to/ota_extract/vmlinux.elf
export KSU_LKM="$PWD/payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko"
# dir containing elftools/ (pip install --target tools/pylib pyelftools)
export PYELFTOOLS="$PWD/tools/pylib"
