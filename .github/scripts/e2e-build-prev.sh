#!/usr/bin/env bash
# CI 用: 上書き更新の確認 (D-075 の 3、D-080) の元にする前の版 (最新のリリースのタグ) の配布版 APK を作り、prev-apk/ に置く。
# 今の版と同じジョブで作る (同じ debug 鍵で署名され、adb install -r で上書きできる)。e2e.yml が鍵と一緒にキャッシュする。
#   $1: タグ (例 v0.9.0)
set -euo pipefail
TAG=$1
DIR="${RUNNER_TEMP:-/tmp}/prev-src"
git fetch --depth 1 origin tag "$TAG"
rm -rf "$DIR"
git worktree add --detach "$DIR" "$TAG"
cp local.properties "$DIR/" # sdk-install.sh が書いた SDK の場所
t0=$(date +%s)
(cd "$DIR" && ./gradlew :app:assembleRelease)
echo "前の版 ($TAG) のビルド: $(( $(date +%s) - t0 ))s"
mkdir -p prev-apk
cp "$DIR/app/build/outputs/apk/release/app-release.apk" prev-apk/app-release.apk
echo "$TAG" > prev-apk/tag
git worktree remove --force "$DIR"
