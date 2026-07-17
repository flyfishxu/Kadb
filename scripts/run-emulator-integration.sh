#!/usr/bin/env bash
set -euo pipefail

readonly DEVICE_ADBD_PORT=5555
readonly HOST_ADBD_PORT=15555
readonly TEST_PACKAGE=com.flyfishxu.kadb.integrationfixture
readonly WORK_DIR="${RUNNER_TEMP:-/tmp}/kadb-emulator-integration"

mkdir -p "$WORK_DIR"

adb wait-for-device
adb tcpip "$DEVICE_ADBD_PORT"
adb wait-for-device
adb forward --remove "tcp:$HOST_ADBD_PORT" 2>/dev/null || true
adb forward "tcp:$HOST_ADBD_PORT" "tcp:$DEVICE_ADBD_PORT"

build_tools_dir="$ANDROID_SDK_ROOT/build-tools/$(ls "$ANDROID_SDK_ROOT/build-tools" | sort -V | tail -n 1)"
platform_jar="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
unsigned_apk="$WORK_DIR/integration-fixture-unsigned.apk"
signed_apk="$WORK_DIR/integration-fixture.apk"
keystore="$WORK_DIR/integration-fixture.jks"

"$build_tools_dir/aapt2" link \
  -I "$platform_jar" \
  --manifest "kadb/src/jvmTest/resources/AndroidManifest.xml" \
  -o "$unsigned_apk"

keytool -genkeypair -noprompt \
  -keystore "$keystore" \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -dname "CN=Kadb Integration Test,O=Kadb,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

"$build_tools_dir/apksigner" sign \
  --ks "$keystore" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$signed_apk" \
  "$unsigned_apk"

export KADB_EMULATOR_TESTS=1
export KADB_TEST_HOST=127.0.0.1
export KADB_TEST_PORT="$HOST_ADBD_PORT"
export KADB_TEST_DEVICE_ADBD_PORT="$DEVICE_ADBD_PORT"
export KADB_TEST_ADBKEY="${ANDROID_USER_HOME:-$HOME/.android}/adbkey"
export KADB_TEST_APK="$signed_apk"
export KADB_TEST_PACKAGE="$TEST_PACKAGE"
export KADB_LARGE_FILE_BYTES="${KADB_LARGE_FILE_BYTES:-67108864}"

"${GRADLE_BIN:-./gradlew}" :kadb:jvmTest \
  -PsignAllPublications=false \
  --tests com.flyfishxu.kadb.KadbEmulatorIntegrationTest
