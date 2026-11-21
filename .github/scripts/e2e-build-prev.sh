#!/usr/bin/env bash
# CI 用: 上書き更新の確認 (D-075 の 3、D-080) の元にする前の版 (最新のリリースのタグ) の配布版 APK を作り、prev-apk/ に置く。
# e2e.yml がタグごとにキャッシュする。署名は e2e-run.sh が今の版とそろえる。
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
