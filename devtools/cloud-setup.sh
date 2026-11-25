#!/usr/bin/env bash
# giracle-frontend-kotlin: クラウドの作業場所でビルドとテストができるようにする (D-083)
# 正本は Claude Code の環境「giracle-frontend-kotlin」の準備の手順 (setup script)。これはその控え。
# 直すときは環境の手順も同じに直す (リポジトリだけ直しても、環境の保存は作り直されない)
set -euo pipefail
SDK=/opt/android-sdk
# 1. Android SDK: Google の公式の道具を、利用状況を送らない指定で使う
if [ ! -x "$SDK/cmdline-tools/latest/bin/android" ]; then
  t=$(mktemp -d)
  curl -fsSL -o "$t/c.zip" https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  echo "e025545c62a8e64c7559119566a569fb1dec5f60  $t/c.zip" | sha1sum -c --quiet -
  unzip -q "$t/c.zip" -d "$t" && mkdir -p "$SDK/cmdline-tools" && mv "$t/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$t"
fi
"$SDK/cmdline-tools/latest/bin/android" --no-metrics --sdk="$SDK" sdk install "build-tools;36.0.0" "platforms;android-37.0" || true
# 2. この作業場所でだけ、Maven Central の取得先を Google のミラーに替える (出口が 429 で断られるため。CI は変えない)
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/maven-central-mirror.gradle <<'G'
def MIRROR = 'https://maven-central.storage-download.googleapis.com/maven2/'
def swap = { repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        if (r.url.toString().startsWith('https://repo.maven.apache.org/maven2')) r.url = MIRROR
    }
}
beforeSettings { s ->
    swap(s.pluginManagement.repositories)
    swap(s.dependencyResolutionManagement.repositories)
    swap(s.buildscript.repositories)
}
allprojects { swap(buildscript.repositories); swap(repositories) }
G
# 3. 部品を取っておく (環境の保存に残るので、次の会話からは取りに行かない)
if [ -d giracle-frontend-kotlin ]; then
  cd giracle-frontend-kotlin
  echo "sdk.dir=$SDK" > local.properties
  # 速く終わる単体テストを 1 つ流し、単体テストに要る部品をすべて取る
  # 上限 220 秒。取れなくても会話は始められるようにする (保存は約 5 分以内に終わったときだけ作られる)
  timeout 220 ./gradlew --no-daemon -q :app:testDebugUnitTest --tests 'com.giracle.android.MessageParserTest' || true
fi
