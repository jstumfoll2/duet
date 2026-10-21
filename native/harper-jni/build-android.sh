#!/usr/bin/env bash
# Builds libduet_harper.so for the app's ABIs into native/harper-jni/target/jniLibs, which
# app/build.gradle packs into the APK. Needs the Android NDK (ANDROID_NDK_HOME) and cargo-ndk
# (`cargo install cargo-ndk --locked --version 4.1.2`). Without this step the app still
# builds; the grammar setting then finds no library and stays inert.
set -euo pipefail
cd "$(dirname "$0")"
# Paths baked into panic messages would differ between machines; map them to fixed names.
export RUSTFLAGS="--remap-path-prefix=$PWD=duet-harper --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=cargo"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 26 -o target/jniLibs build --release --locked
ls -l target/jniLibs/*/libduet_harper.so
