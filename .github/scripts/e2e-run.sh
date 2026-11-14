#!/usr/bin/env bash
# CI 用: 起動済みのエミュレータに配布版 APK と確認用 APK (:e2e) を入れ、通しの確認 (#12) を動かす。
# 前提: emulator-start.sh で起動済み、localhost:3333 にサーバー (local-server)、APK はビルド済み。
#   APP_APK : 確かめる APK (既定: assembleRelease の出力)
#   LAN_SERVER : 端末から LAN のアドレスで見たサーバー (既定 http://10.0.2.2:3333 = エミュレータから見たランナー)。
#                電波を切る確認 (#37) とローカルネットワーク権限の確認 (#42) に使う。空にすると飛ばす
# 結果は e2e-out/ (画面・logcat・crash バッファ) に置く。
set -uo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK/platform-tools/adb"
AAPT2=$(ls -d "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)
APP_APK=${APP_APK:-app/build/outputs/apk/release/app-release.apk}
LAN_SERVER=${LAN_SERVER-http://10.0.2.2:3333}
TEST_APK=$(ls e2e/build/outputs/apk/debug/*.apk | head -1)
APP_PKG=$("$AAPT2" dump packagename "$APP_APK")
TEST_PKG=$("$AAPT2" dump packagename "$TEST_APK")
OUT=e2e-out
mkdir -p "$OUT"
echo "app: $APP_PKG ($APP_APK)  test: $TEST_PKG ($TEST_APK)"

set -e
# 端末の localhost:3333 をランナーのサーバーにつなぐ (アプリが送る Origin が CORS_ORIGIN と一致する)
"$ADB" reverse tcp:3333 tcp:3333
# 権限は付けずに入れる (通知の権限の確認画面が出ることも確かめるため)
"$ADB" install -r "$APP_APK"
"$ADB" install -r -t "$TEST_APK"
"$ADB" shell pm list instrumentation
# 送信を押した直後のスタックと描画の間隔を記録させる (SendTrace.profileAfterTap、#70、D-068)。利用者の端末では付かない
"$ADB" shell setprop log.tag.GiraclePerfTrace VERBOSE
set +e
# logcat の入れ物 (エミュレータは 2M) は、実行の間にあふれて古い行が消える (#81)。
# 大きくしたうえで、実行の間ずっとファイルに流し続ける (終わってから -d で取り出すと、最後の数秒しか残らないことがある)
"$ADB" logcat -G 16M || echo "::warning::logcat の入れ物を大きくできなかった"
"$ADB" logcat -g || true
LOGCAT_PID=
# 実行の前に呼ぶ。入れ物を空にし、ファイルへ流し始め、始まりの印を書く
logcat_start() { # $1: 追記するファイル $2: 実行の名前
  "$ADB" logcat -c || true
  "$ADB" logcat >> "$1" 2>&1 &
  LOGCAT_PID=$!
  sleep 1
  "$ADB" shell log -t GiracleE2E "E2E-LOG start=$2"
}
# 実行の後に呼ぶ。終わりの印がファイルに届くまで待ってから止め、始まりと終わりの印がそろっているかを見る
logcat_stop() { # $1: ファイル $2: 実行の名前
  "$ADB" shell log -t GiracleE2E "E2E-LOG end=$2"
  for _ in $(seq 20); do grep -q "E2E-LOG end=$2" "$1" && break; sleep 0.5; done
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
  if grep -q "E2E-LOG start=$2" "$1" && grep -q "E2E-LOG end=$2" "$1"; then
    echo "logcat ($2): 始まりと終わりの印がそろっている (流す途中で logd に切られていない)"
  else
    # 結果の印が見つからない失敗が、ログの欠けによるものだと分かるようにする
    echo "::warning::logcat ($2) の始まりか終わりの印が無い。ログが欠けていて、下の「動いていない」の判定は当てにならない"
  fi
}

# 実行ごとに追記するので、前の実行 (手元で 2 回目を動かしたとき) の行を残さないよう空にしておく
: > "$OUT/logcat.txt"
logcat_start "$OUT/logcat.txt" fast
t0=$(date +%s)
LAN_ARGS=()
[ -n "$LAN_SERVER" ] && LAN_ARGS=(-e lanServer "$LAN_SERVER")
"$ADB" shell am instrument -w -e appPackage "$APP_PKG" -e server http://localhost:3333 "${LAN_ARGS[@]}" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument.txt"
echo "e2e: $(( $(date +%s) - t0 ))s"
logcat_stop "$OUT/logcat.txt" fast

# よく使う作業を、遅い回線でも測る (D-046)。上の実行は端末の localhost (adb reverse) なので、通信をほぼ待たない。
# ランナーの上に遅延を足す中継 (latency-proxy.py、片道 150ms) を置き、端末の localhost:3334 をそこへつなぐ。
# エミュレータの遅延 (adb emu network delay) は効いたり効かなかったりしたので使わない (#57)
SLOW_OK=1
SLOW_PORT=3334
# 1 回目のクラッシュの記録を、消す前に取っておく (下のクラッシュの判定は crash.txt だけを見る)
"$ADB" logcat -b crash -d > "$OUT/crash.txt" 2>&1
python3 .github/scripts/latency-proxy.py $SLOW_PORT 127.0.0.1 3333 150 > "$OUT/latency-proxy.log" 2>&1 &
PROXY_PID=$!
sleep 1
"$ADB" reverse tcp:$SLOW_PORT tcp:$SLOW_PORT
proxy_check=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code} %{time_total}" http://localhost:$SLOW_PORT/api/server/config)
echo "遅延の中継を通した問い合わせ (ランナーから。状態と秒): $proxy_check"
case "$proxy_check" in 200*) ;; *) echo "::error::遅延の中継が動いていない"; cat "$OUT/latency-proxy.log"; SLOW_OK=0 ;; esac
logcat_start "$OUT/logcat.txt" slow
t1=$(date +%s)
"$ADB" shell am instrument -w -e appPackage "$APP_PKG" -e server http://localhost:3333 \
  -e class com.giracle.e2e.ReleaseAppTest#uxTasks -e uxServer "http://localhost:$SLOW_PORT" -e uxNet slow \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument-slow.txt"
echo "e2e (遅い回線の ux): $(( $(date +%s) - t1 ))s"
kill $PROXY_PID 2>/dev/null || true
logcat_stop "$OUT/logcat.txt" slow
"$ADB" logcat -b crash -d >> "$OUT/crash.txt" 2>&1
"$ADB" pull "/sdcard/Android/data/$TEST_PKG/files/screenshots" "$OUT/" > /dev/null 2>&1 || true

status=0
# 失敗したときの手がかりを、成果物を開かなくても CI のログで読めるように出す
show_clues() {
  echo "::group::失敗したときの画面の要素 (UI-DUMP)"
  grep -E "GiracleE2E: UI-DUMP" "$OUT/logcat.txt" | sed -E 's/.*UI-DUMP/UI-DUMP/' | tail -n 200
  echo "::endgroup::"
  echo "::group::logcat (アプリ・起動・クラッシュに関係する行)"
  grep -E "GiracleE2E|GiracleNav|AndroidRuntime|$APP_PKG|ActivityTaskManager: (START|Displayed)|ActivityManager: (Start proc|Process .* has died|Killing)" "$OUT/logcat.txt" | tail -n 150
  echo "::endgroup::"
  # アプリのプロセス (途中で作り直されたものも含む) の記録をすべて出す。プッシュ受信など、裏で起きたことを追うため
  pids=$(grep -oE "Start proc [0-9]+:$APP_PKG" "$OUT/logcat.txt" | grep -oE "[0-9]+" | tr '\n' '|' | sed 's/|$//')
  if [ -n "$pids" ]; then
    echo "::group::アプリのプロセスの記録 (pid: $pids)"
    grep -E "^[0-9-]+ [0-9:.]+ +($pids) " "$OUT/logcat.txt" | grep -vE "ImeTracker|FrameTracker|InputMethod" | tail -n 200
    echo "::endgroup::"
  fi
  # 送信待ち・接続・再同期の記録 (#101)。上の 2 つは最後の行だけなので、前半のテストの記録が押し出される。タグで絞って全部出す
  echo "::group::送信待ち・接続・再同期 (Outbox・GiracleWs・GiracleSync・GiracleNav)"
  grep -E " (Outbox|GiracleWs|GiracleSync|GiracleNav) *: |TestRunner: (started|finished)|GiracleE2E: E2E-RESULT (offline|outbox|lan)" "$OUT/logcat.txt" | tail -n 300
  echo "::endgroup::"
  echo "::group::プッシュ通知の受信 (UnifiedPush・Play 開発者サービス)"
  grep -E "GiraclePush|UnifiedPush|UP-|FirebaseReceiver|GCM|FCM" "$OUT/logcat.txt" | tail -n 80
  echo "::endgroup::"
  echo "::group::crash バッファ"; cat "$OUT/crash.txt"; echo "::endgroup::"
  # 終了時点の画面の要素 (resource-id・文字・クラス) の一覧
  echo "::group::画面の要素 (uiautomator dump)"
  "$ADB" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>&1
  grep -o '<node [^>]*' "$OUT/ui.xml" | sed -E 's/.*text="([^"]*)" resource-id="([^"]*)" class="([^"]*)" package="([^"]*)".*/id=\2 class=\3 text=\1 pkg=\4/' | head -n 120
  echo "::endgroup::"
}
# am instrument は失敗しても終了コードが 0 なので、出力で判断する
grep -q '^OK (' "$OUT/instrument.txt" || { echo "::error::配布版の確認 (e2e) が失敗した。成果物 e2e の instrument.txt と画面を見る"; status=1; }
[ $SLOW_OK = 1 ] || status=1
if ! grep -q '^OK (' "$OUT/instrument-slow.txt"; then
  echo "::error::遅い回線でのよく使う作業の測定 (uxTasks) が失敗した。instrument-slow.txt を見る"
  tail -n 40 "$OUT/instrument-slow.txt"
  status=1
fi
# 条件付きの確認 (assume) は、飛ばされても「OK」になる。指定したのに動いていなければ失敗にする
echo "::group::e2e の結果の印"; grep -o "E2E-RESULT .*" "$OUT/logcat.txt"; echo "::endgroup::"
# よく使う作業の手数と時間 (D-046)。エミュレータの時間は絶対値ではなく、前の実行 (docs/UI_PRINCIPLES.md 9 章の基準) との比較に使う
echo "::group::よく使う作業の手数と時間 (ux)"
grep -oE "E2E-RESULT ux net=.*" "$OUT/logcat.txt" | sed "s/E2E-RESULT ux //"
# 手がかり: 再同期・スナップショットからの復元・アプリのプロセスの起動 (つなぎ直した後の切り替えの値を読むため)・送信の区切り (GiraclePerf、#70)
grep -E " I GiracleSync: | I GiraclePerf: | I GiracleE2E: E2E-RESULT ux|ActivityManager: Start proc [0-9]+:$APP_PKG" "$OUT/logcat.txt" | tail -n 60
echo "::endgroup::"
# 送信を押した直後の描画の間隔と、メイン・Default のスレッドのスタック (#70、D-068)。R8 の名前を mapping.txt で元に戻して出す
echo "::group::送信を押した直後のスレッドのスタック (GiraclePerfTrace)"
MAPPING=app/build/outputs/mapping/release/mapping.txt
RETRACE=$(ls -d "$SDK"/cmdline-tools/*/bin/retrace 2>/dev/null | sort -V | tail -1)
grep -E " GiraclePerfTrace: " "$OUT/logcat.txt" | sed -E 's/^.* GiraclePerfTrace: //' > "$OUT/perf-trace.txt"
if [ -n "$RETRACE" ] && [ -f "$MAPPING" ]; then
  "$RETRACE" "$MAPPING" "$OUT/perf-trace.txt" > "$OUT/perf-trace-retraced.txt" 2> "$OUT/retrace-err.txt" || echo "::warning::retrace が失敗した (下は元の名前のまま)"
  [ -s "$OUT/perf-trace-retraced.txt" ] || cp "$OUT/perf-trace.txt" "$OUT/perf-trace-retraced.txt"
else
  echo "retrace か mapping.txt が無いので、R8 の名前のまま出す (retrace=$RETRACE)"
  cp "$OUT/perf-trace.txt" "$OUT/perf-trace-retraced.txt"
fi
head -n 1500 "$OUT/perf-trace-retraced.txt"
echo "::endgroup::"
if [ $status = 0 ] && ! grep -q "E2E-RESULT ux_test=done net=fast" "$OUT/logcat.txt"; then
  echo "::error::よく使う作業の測定 (uxTasks) が最後まで動いていない"
  status=1
fi
if [ $status = 0 ] && ! grep -q "E2E-RESULT ux_test=done net=slow" "$OUT/logcat.txt"; then
  echo "::error::遅い回線でのよく使う作業の測定 (uxTasks) が最後まで動いていない"
  status=1
fi
if [ $status = 0 ] && [ -n "$LAN_SERVER" ] && ! grep -q "E2E-RESULT lan_test=done" "$OUT/logcat.txt"; then
  echo "::error::LAN のサーバーでの確認 (lanServerPermissionAndOfflineSend) が最後まで動いていない (飛ばされた?)"
  status=1
fi
if [ $status = 0 ] && [ -n "$LAN_SERVER" ] && ! grep -q "E2E-RESULT offline_start_test=done" "$OUT/logcat.txt"; then
  echo "::error::電波が無いときの起動の確認 (offlineColdStart) が最後まで動いていない (飛ばされた?)"
  status=1
fi
# 送信ボタンを押し直した回 (#94)。通っても見えるように警告にする
if grep -q "E2E-RESULT send_tap_retry" "$OUT/logcat.txt"; then
  echo "::warning::送信ボタンを押しても受け付けられず、押し直した (#94)。$(grep -oE 'E2E-RESULT send_tap_retry .*' "$OUT/logcat.txt" | head -1)"
fi
# 電波が無いときの起動 (#82、P-2)。前回の内容が見え続けるか・電波が戻ったら操作なしで最新になるか
echo "::group::電波が無いときの起動 (offline_start)"
grep -oE "E2E-RESULT offline_start .*" "$OUT/logcat.txt" | sed "s/E2E-RESULT //"
echo "::endgroup::"
[ $status = 0 ] || show_clues
if grep -q "Process: $APP_PKG" "$OUT/crash.txt"; then
  echo "::error::アプリが落ちた (crash.txt。R8 のスタックトレースは mapping.txt で戻せる)"
  cat "$OUT/crash.txt"
  status=1
fi
# 落ちはしないが、アプリ全体の裏の処理から想定外の例外が漏れた (D-036)。見逃さないよう失敗にする
if grep -q "GiracleUncaught" "$OUT/logcat.txt"; then
  echo "::error::想定外の例外が記録された (GiracleUncaught。D-036)"
  grep -A 30 "GiracleUncaught" "$OUT/logcat.txt" | head -n 80
  status=1
fi
exit $status
