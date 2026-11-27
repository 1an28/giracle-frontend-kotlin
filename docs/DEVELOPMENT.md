# 開発の手引き

## 必要なもの

- JDK 17 以上 (21 で確認)
- Android SDK (platforms;android-37.0, build-tools;37.0.0)。`local.properties` に `sdk.dir=...`
- ローカル検証用: [Bun](https://bun.sh) と Node.js (バックエンドと Web 版を動かす)

## Claude Code のクラウドの作業場所 (D-083)

- このリポジトリの会話は、環境「giracle-frontend-kotlin」で始める。準備の手順 (控えは `devtools/cloud-setup.sh`) が
  Android SDK を入れ、単体テストに要る部品を取り置く (新しい会話で働くかは未確認。会話の始めに `ls /opt/android-sdk ~/.gradle/init.d` で確かめる)。手順が約 5 分以内に終われば作業場所の中身が保存され、次の会話からはそこから始まる (約 7 日で作り直し)
- この作業場所の出口は Maven Central から 429 (Too Many Requests) で断られることが多い。そのため、作業場所でだけ
  `~/.gradle/init.d/maven-central-mirror.gradle` で Maven Central の取得先を Google のミラーに替える。リポジトリと CI の設定は変えない
- push の前に、変えたところの単体テストを手元で流す (`./gradlew :app:testDebugUnitTest --tests '<クラス>'`)。CI は最後の確かめにする
- 準備の手順を直すときは、環境の手順 (正本) と `devtools/cloud-setup.sh` (控え) の両方を直す

## ビルド

```bash
./gradlew :app:assembleDebug     # 開発版 (io.github.u1an28.giracle.kotlin.debug、「Giracle Native (dev)」)
./gradlew :app:assembleRelease   # 配布版 (R8 で縮小、約 2MB)
```

配布版の署名: リポジトリ直下に `keystore.properties` を置く (コミットしない)。無ければ debug 鍵で署名される。

```properties
storeFile=keystore/giracle-native.p12
storePassword=...
keyAlias=giracle
keyPassword=...
```

## リリース

`v0.1.0` のようなタグを push すると、GitHub Actions (`release.yml`) がリリースを作り APK を添付する。
署名鍵の扱いと手順は [releases/README.md](../releases/README.md)。

## ローカルでの動作確認

```bash
devtools/start-local-stack.sh   # バックエンド :3000 と Web 版 :3333 (/api と /ws を転送) を起動。VAPID 鍵も作って入れる
devtools/seed-demo.sh           # デモデータ (admin / password123 を先に Web 版かアプリで作る)
```

アプリからは `http://<PC の IP>:3333` に接続する (本番と同じ `/api` 配置)。
エミュレータからなら `http://10.0.2.2:3333`。バックエンドの `.env` の `CORS_ORIGIN` に、
アプリに入れた URL の Origin を加えないと WebSocket が 403 になる。

## テスト

```bash
./gradlew :app:testDebugUnitTest                                   # 単体テストのみ
GIRACLE_TEST_SERVER=http://localhost:3333 ./gradlew :app:testDebugUnitTest   # 実サーバー結合テストと画面描画も
```

- `LiveServerTest`: 管理者 (`GIRACLE_TEST_ADMIN_USER` / `GIRACLE_TEST_ADMIN_PASS`、既定 admin / password123) で
  招待コードを作り、2 ユーザーで送受信・WebSocket の反映などを確認する。
- `UiScreenshotTest`: 管理者でテストごとに専用のチャンネルを作り、実際の画面を描画して `app/build/screenshots/` に PNG を出す。
  デモデータ (`seed-demo.sh`) は要らない。要素は文言ではなく `ui/common/TestTags.kt` の名前 (testTag) で探す (D-016)。
- 管理者は、サーバーを立てた直後に最初に登録したユーザーがなる:
  `curl -X PUT localhost:3333/api/user/sign-up -H 'content-type: application/json' -d '{"username":"admin","password":"password123"}'`
- CI では `live-test.yml` が同じ手順でサーバーを立ててこれらを動かし、スクリーンショットを成果物 `screenshots` に残す。
- Robolectric はプロキシ環境で自前のダウンロードに失敗するため、Gradle で取得してオフラインで動かす設定にしてある。

## 配布版をエミュレータで確かめる (e2e)

CI (`e2e.yml`) が、配布版 (`assembleRelease`、debug 鍵で署名) をエミュレータに入れ、ランナー上のサーバーにつないで
UI Automator で外から操作する (D-018)。手元で動かす場合 (KVM のある Linux と Android SDK が必要):

```bash
./gradlew :app:assembleRelease :e2e:assembleDebug
devtools/start-local-stack.sh   # 管理者 admin / password123 も作っておく (上の「テスト」参照)
.github/scripts/emulator-start.sh
.github/scripts/e2e-run.sh      # 結果は e2e-out/ (画面・logcat・crash バッファ)
```

- CI は API 35 と API 37 (targetSdk と同じ Android 17) の 2 つのエミュレータで動かす。
- 端末の通信網を通る確認 (機内モードでの送信待ち #37、Android 17 のローカルネットワーク権限 #42) は、
  `adb reverse` の `localhost` ではなく `http://10.0.2.2:3333` (エミュレータから見たランナー) を使う。
  そのため `local-server` はバックエンドの `CORS_ORIGIN` に `http://10.0.2.2:3333` も入れる。`LAN_SERVER=` (空) で飛ばせる。
- 手順は `e2e/src/main/java/com/giracle/e2e/ReleaseAppTest.kt`。要素は testTag (resource-id) で探すので、
  `TestTags` の名前を変えるとここも直す必要がある。
- 失敗したときは、CI のログに logcat の要点・crash バッファ・終了時点の画面の要素一覧が出る。

## Baseline Profile の記録と起動時間の計測 (#8、D-021)

CI (`baseline-profile.yml`。main への push・`baselineprofile/` を変えた PR・手動実行) が、エミュレータで次を行う。
配布版のリリース (`release.yml`) は、main の同じコミットの記録 (成果物) を使う。無ければリリースの中で記録する
(失敗したらリポジトリの内容のまま出す)。

1. 難読化しない配布版 (`assembleNonMinifiedRelease`) を入れ、`BaselineProfileGenerator` で
   ログイン → ホーム → チャンネルを開いてスクロール → 設定、を繰り返して使われたコードを記録する
2. 記録を `app/src/main/baseline-prof.txt` に置き、配布版を作り直す (R8 がクラス名を合わせて取り込む)
3. `StartupBenchmark` で、記録前・記録後の配布版の起動時間 (コールドスタート) を測り、ログに出す
4. `ScrollBenchmark` で、記録後の配布版のチャンネルのスクロールのフレーム時間を測り、ログに出す (D-048)

```bash
./gradlew :app:assembleNonMinifiedRelease :baselineprofile:assembleDebug
.github/scripts/baseline-profile.sh generate app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk
./gradlew :app:assembleRelease
.github/scripts/baseline-profile.sh measure app/build/outputs/apk/release/app-release.apk after
.github/scripts/baseline-profile.sh scroll app/build/outputs/apk/release/app-release.apk scroll
```

## 実機での計測 (Firebase Test Lab、D-048)

`device-benchmark.yml` (手動と 1 日 1 回) が、Firebase Test Lab の実機で配布版の起動時間を測り、ログと実行の要約に出す。
Secrets の `FIREBASE_SA_KEY` が無ければ何もしない (準備は docs/ROADMAP.md の持ち主の欄)。
Test Lab の端末はランナーのサーバーに届かないので、今はログイン前の画面までの起動だけを測る (`login=false`)。
無料の枠 (Spark: 実機は 1 日 5 回) を超えないよう、push では走らせない。手で回すのは 1 日 4 回まで。

## プッシュ通知の確認 (サーバー側)

```bash
cd ~/backend-run && bunx web-push generate-vapid-keys --json   # 鍵を作る
VAPID_PUBLIC_KEY=... VAPID_PRIVATE_KEY=... NODE_TLS_REJECT_UNAUTHORIZED=0 bun run src/index.ts
python3 devtools/push-receiver.py &   # localhost:8443 で受ける (自己署名証明書 c.pem/k.pem が必要)
python3 devtools/push-e2e.py          # 登録 → メンション → 受信 → 復号 (pip install http_ece cryptography)
```

## PR を作る前に (D-087)

CI は、PR のあるブランチ (作ったときと、その後の push のたび) と main への push でだけ走る (PR の無いブランチへの push では走らない。手で起こす `workflow_dispatch` は別。2026-11-28 に workflow の `on:` で確かめた。push のトリガーを足すとこの前提が崩れる)。
レビューの指摘を直すたびに CI (e2e は 1 回約 10〜12 分 (API 35・37 の 2 本を並べて)) がやり直しにならないよう、次の順にする。

1. 変えたところのテストを手元で流す。サーバーが要るテスト (`LiveServerTest`・`UiScreenshotTest`) に関わる変更なら、`devtools/start-local-stack.sh` で立てて流す
2. ブランチに push する (PR はまだ作らない)
3. レビュー: 主セッションが差分 (`git diff origin/main...<ブランチ>`) を読む。レビュー役にも要件と一緒に渡す (子セッションの作業では補助として任意、D-067)。子セッションの作業なら、指摘を子セッションに送って直させる
4. 直ったら PR を作る。本文に「レビューの指摘と対応 (直さないものは理由)」を書く (PR の無い間のやり取りは記録に残らないため)
5. CI の結果で新しく分かったことがあれば直す。CI は全部また走る (走る範囲は選べない)

文書だけの変更は、今までどおり PR を作ってよい (CI は docs-check だけ)。

## PR をマージまで進める (D-052)

並行するセッションの PR が先にマージされると、docs の追記どうしが競合する。CI の終わりの知らせも届かないことがある。
どちらも、自分で確かめて進める。

1. **判断の番号**: その時点の main と開いている PR の最大の次にする。重なりは docs-check (CI) が見つける。
2. **push したら、知らせだけに頼らず時間をおいて自分で確かめる**。PR を購読したうえで、
   後で自分に知らせる手段 (`send_later` など) を、下の目安の時間で予約してから待つ。起きたら PR の check runs を読み、
   終わっていなければ 5〜10 分後にまた予約する。`sleep` で待たない。待つ間は調べものなど別のことをしてよい。
   - 目安 (2026-11 の実績): docs-check 1 分弱・live-test 約 2 分・build 約 4 分・e2e 約 10 分・
     baseline-profile 約 19 分 (baselineprofile/ などを変えたときだけ)。文書だけの変更は docs-check だけが走る
   - 最初の確認は、走る中で一番長いものの目安 + 2 分ほど後
   - 「check suite が終わった」という知らせは、ワークフローごとに来る。先に終わった docs-check だけで来ることがあるので、
     知らせを受けても check runs をすべて見て、全部が終わったかを確かめる (2026-11-13 に #63 で確かめた)
3. **マージの直前に main を取り込む**。`git fetch origin main` して、main が PR の元から進んでいれば
   `git merge origin/main` する (GitHub が競合と言っていなくても。番号の重なりは競合として出ないため)。
   - DECISIONS.md・RESEARCH.md は、`.gitattributes` の設定で両方の追記が自動で残る。ただし既存の行を両方が書き換えていても、
     競合にならず新旧が並んで残る。取り込んだ後 `git diff ORIG_HEAD -- docs/DECISIONS.md docs/RESEARCH.md` で、追記以外が無いか見る
   - 自分の枝に `.gitattributes` の設定がまだ無い (D-052 より前に分かれた) ときは、union が効かず普通の競合になる。手で両方残す
   - それ以外の競合 (ROADMAP.md など) は手で直す。追記どうしは両方残し、書き換えどうしは両方の意図を合わせる
   - union は自分の追記を先に置くので、番号の順が前後することがある。並べ直す
   - 判断の番号が重なっていたら、自分の方を付け直す (本文・コミット以外の文書・PR の説明の参照も)
   - 取り込んで push したら、待つ CI を決める (D-058)。取り込んだ直後なら `ORIG_HEAD` が取り込む前の自分の HEAD なので、
     `B=$(git merge-base ORIG_HEAD origin/main)` として、PR の変更 `git diff --name-only $B ORIG_HEAD` と、
     取り込んだ main の変更 `git diff --name-only $B origin/main` を比べる (取り込んだ後に `merge-base HEAD origin/main` を使うと、main の変更が空になり、必ず「重なりなし」になる)
     - 両方に、`docs/` 以外で同じファイルがあれば、2. に戻り CI 全部が通るのを確かめる
     - 無ければ、docs-check (判断の番号の重なり) が通るのを待つだけでよい。ただし、この PR で CI 全部が最後に通ったコミットがあることが条件。
       CI が取り消された (build・live-test・e2e は新しい push で前の実行を取り消す) などで、全部が通ったコミットが無ければ、CI 全部を待つ
     - 違うファイルどうしでも壊れることはある (片方が名前を変え、もう片方が古い名前で使うなど)。まれと見て、マージ後の main の CI で見つける
4. **マージ**: 直前にもう一度 `git fetch origin main` し、3. で取り込んだ main から進んでいないことを確かめる (進んでいたら 3. に戻る)。
   GitHub の MCP の `merge_pull_request` に、3. で確かめたコミットを `expectedHeadSha` として渡してマージする
   (これは PR の head しか比べず、main が進んだかは見ない)。マージ後、main の CI を見てから終える。
   main の CI が落ちたら、Issue にして最優先で直す (主セッションで直すか、子セッションに切り分けて見届ける。D-062)。取り込み直しで CI を省いた組み合わせは、main で初めて全部確かめられるため。
   リリースは、main の CI が全部通っているときだけ出す。

## 構成

```
app/src/main/java/com/giracle/android/
├── GiracleApp.kt / MainActivity.kt   起動・前面/背面の切り替え
├── AppGraph.kt                       依存関係の組み立て・ログイン状態・起動時スナップショット
├── data/
│   ├── net/   Endpoint (URL 配置)・Http (OkHttp・トークン)・GiracleApi (全 API)・WsClient・LocalNetwork (Android 17 の権限)
│   ├── model/ サーバーの応答モデル
│   ├── ChatStore.kt   状態と WebSocket イベントの反映 (Web 版の stores + WS)
│   ├── MessageParser.kt  本文の記法 (メンション・URL など) の解析
│   ├── Emojis.kt  ショートコード ⇔ 絵文字
│   └── Prefs.kt   端末内設定・トークン
├── notify/   通知・常時接続サービス・UnifiedPush 受信
└── ui/       画面 (Compose)
```

判断の経緯は [DECISIONS.md](DECISIONS.md)、サーバーへの要望は [SERVER_CHANGES.md](SERVER_CHANGES.md)、
Web 版との対応は [FEATURES.md](FEATURES.md)。
