#!/usr/bin/env bash
# CI 用: ランナー既存の Android SDK に、ビルドに必要なパッケージを入れる
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
SDKM=$(ls -d "$SDK"/cmdline-tools/*/bin/sdkmanager | sort -V | tail -1)
echo "sdkmanager: $SDKM"
yes | "$SDKM" --licenses > /dev/null || true
"$SDKM" "platforms;android-37.0" "platforms;android-37.2" "build-tools;37.0.0" "platform-tools" > /dev/null
echo "sdk.dir=$SDK" > local.properties
