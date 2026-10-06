#!/bin/sh
set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_HOME_LOCAL="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
BUILD_DIR="$PROJECT_DIR/build"

export JAVA_HOME="$JAVA_HOME_LOCAL"
export PATH="$JAVA_HOME_LOCAL/bin:$PATH"

rm -rf "$BUILD_DIR"
sh "$PROJECT_DIR/gradlew" --no-daemon :app:assembleDebug
mkdir -p "$BUILD_DIR"
cp "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$BUILD_DIR/drawings-archive-debug.apk"
"/Users/wolfchik/Library/Android/sdk/build-tools/36.0.0/apksigner" verify --verbose "$BUILD_DIR/drawings-archive-debug.apk"
echo "$BUILD_DIR/drawings-archive-debug.apk"
