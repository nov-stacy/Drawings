#!/bin/sh
set -eu

SDK_ROOT="/Users/wolfchik/Library/Android/sdk"
BUILD_TOOLS="$SDK_ROOT/build-tools/36.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-37.0/android.jar"
JAVA_HOME_LOCAL="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
COMPILED_DIR="$BUILD_DIR/compiled"
GENERATED_DIR="$BUILD_DIR/generated"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"

export JAVA_HOME="$JAVA_HOME_LOCAL"
export PATH="$JAVA_HOME_LOCAL/bin:$PATH"

rm -rf "$BUILD_DIR"
mkdir -p "$COMPILED_DIR" "$GENERATED_DIR" "$CLASSES_DIR" "$DEX_DIR"

"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/app/src/main/res" -o "$COMPILED_DIR"
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/base-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$GENERATED_DIR" \
  --min-sdk-version 26 \
  --target-sdk-version 37 \
  "$COMPILED_DIR"/*.flat

"$JAVA_HOME_LOCAL/bin/javac" \
  -encoding UTF-8 \
  -source 8 \
  -target 8 \
  -classpath "$ANDROID_JAR" \
  -d "$CLASSES_DIR" \
  "$GENERATED_DIR/com/example/drawingsarchive/R.java" \
  "$PROJECT_DIR/app/src/main/java/com/example/drawingsarchive/CaptureFileProvider.java" \
  "$PROJECT_DIR/app/src/main/java/com/example/drawingsarchive/MainActivity.java"

"$JAVA_HOME_LOCAL/bin/jar" cf "$BUILD_DIR/classes.jar" -C "$CLASSES_DIR" .
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$DEX_DIR" "$BUILD_DIR/classes.jar"
(cd "$DEX_DIR" && zip -q -j "$BUILD_DIR/base-unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD_DIR/base-unsigned.apk" "$BUILD_DIR/app-aligned.apk"

if [ ! -f "$PROJECT_DIR/debug.keystore" ]; then
  "$JAVA_HOME_LOCAL/bin/keytool" -genkeypair -v \
    -keystore "$PROJECT_DIR/debug.keystore" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000
fi

"$BUILD_TOOLS/apksigner" sign \
  --ks "$PROJECT_DIR/debug.keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$BUILD_DIR/drawings-archive-debug.apk" \
  "$BUILD_DIR/app-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose "$BUILD_DIR/drawings-archive-debug.apk"
echo "$BUILD_DIR/drawings-archive-debug.apk"
