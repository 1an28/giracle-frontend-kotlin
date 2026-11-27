# サーバー (giracle-backend) への要望と報告

Android ネイティブアプリ (Giracle Native) を作るなかで分かった、サーバー側への要望と不具合の報告。
**giracle-backend の作者が読む前提** で書いている。

- 参照した backend のコミット: `87be860` (2026-11-08)
- アプリの作りの前提: HTTP は `/api/...`、WebSocket は `/ws` に接続する (Web 版と同じ配置)。
  認証はログイン応答の Cookie `token` を取り出して保存し、すべての通信に付けている。

## まとめ

| ID | 種類 | 内容 | 急ぎ度 | 関連 Issue |
| --- | --- | --- | --- | --- |
| S-1 | 不具合 | 通知が残っているチャンネルを削除すると 500 になる | **直った** (backend `244dfe6`、2026-11-24。ローカルで確かめた 2026-11-28)。本番に入ったかは未確認 | [#24](https://github.com/1an28/giracle-frontend-kotlin/issues/24) |
| S-3 | 相談 | アプリからの WebSocket 接続の正式な入口 | 急がない (今は暫定の方法で動いている) | [#7](https://github.com/1an28/giracle-frontend-kotlin/issues/7) |
| S-5 | 要望 | ログインのトークンを Cookie 以外でも受け渡せるようにする | 急がない | [#7](https://github.com/1an28/giracle-frontend-kotlin/issues/7) |
| S-2 | 情報 | プッシュ通知: **サーバーの変更は不要になった** (アプリが FCM 経由の Web Push を受ける)。作者の方針「Apple は APNs、Web と Android は Web Push」と一致 (2026-11-13) | 本番から FCM へ送れるかだけ確認したい | [#6](https://github.com/1an28/giracle-frontend-kotlin/issues/6) |
| S-6 | 要望 | サーバーのバージョンを返す窓口 | あると助かる | - |
| S-7 | 相談 | プッシュの中身に送信者の userId とチャンネル名を入れる | あると助かる (無くてもアプリで作れる) | [#26](https://github.com/1an28/giracle-frontend-kotlin/issues/26) |
| S-8 | 相談 | 送信の重複を防ぐため、送信にクライアントが付ける ID を受け付ける | 急がない | [#30](https://github.com/1an28/giracle-frontend-kotlin/issues/30) |
| S-9 | 相談 | メンション・返信のプッシュを Urgency: high で送る (待機中の Android に遅れず届くか) | 急がない (遅れているかは未確認) | - |
| S-4 | 情報 | そのほか気づいたこと | 対応不要 | - |

### 話すときのチェックリスト

- [x] S-1 を直してもらえるか → 直すと返事をもらった (2026-11-12)。backend `244dfe6` で直った (2026-11-24)
- [ ] S-2: Web 版のプッシュ通知が Chrome で届いているか (届いていれば、本番から `fcm.googleapis.com` へ送れている)
- [ ] S-3 / S-5: アプリ向けの認証・接続方法をどうするか (案は下に)

---

## S-1 通知が残っているチャンネルを削除すると 500 になる (不具合)

- **現象**: 未読のメンション/返信通知 (Inbox) が残っているメッセージを含むチャンネルを
  `DELETE /channel/delete` すると、`500 somethin went wrong :(` になり削除できない。
  サーバーログ: `SQLiteError: FOREIGN KEY constraint failed` (`QueryMessage.removeByChannelInTx`)。
- **再現手順**: ユーザー A がユーザー B をメンションする → B が通知を既読にする前に、管理者がそのチャンネルを削除する。
  (このアプリの結合テスト `LiveServerTest` で見つけた)
- **原因**: `src/db/schema.ts` の `Inbox.messageId` が `onDelete: "restrict"`。
  `ServiceChannel.Delete` (`channel.service.ts` の 470 行目付近) のトランザクションは
  ReadTime / ViewableRole / FileAttached / Message / ChannelJoin … を消すが、Inbox を消していない。
- **直し方の案** (どちらか):
  - `Message` を消す前に、そのチャンネルのメッセージに紐づく Inbox 行を消す (`QueryInbox.removeByChannelInTx` を足す)。
    単体のメッセージ削除 (`/message/delete`) は Inbox を先に消しているので、それとそろう。
  - `Inbox.messageId` を `onDelete: "cascade"` にする。
- **直ったかの確かめ方**: CI の `live-test` が、backend の最新版で毎回 `LiveServerTest.deleteChannelWithUnreadMention` を動かす。
  (2026-11-13 時点の `87be860` ではまだ 500)。
- **直った (2026-11-24、backend `244dfe6`「InboxにchannelIdを紐付け、チャンネル削除時に通知も削除する (#182)」)**:
  Inbox に `channelId` (チャンネルの削除で cascade) を足し、`ServiceChannel.Delete` のトランザクションの先頭でそのチャンネルの Inbox を消す形。
  確かめたこと: この会話の作業場所に backend `2be5bd5` を立て、`LiveServerTest.deleteChannelWithUnreadMention` が「直っている」側を通った (2026-11-28)。
  このテストは、500 なら失敗する退行の確認に変えた (#24)。確かめていないこと: 本番 (chat.girak.moe) にこの版が入ったか

---

## S-3 アプリからの WebSocket 接続の正式な入口 (相談)

### 今の状況

`src/ws.ts` の `/ws` は次の条件で接続を受ける。

- Origin ヘッダが無いと 403。`CORS_ORIGIN` を設定している場合は、一覧と完全一致しないと 403
- 認証は Cookie の `token` だけを見る

ネイティブアプリには本来 Origin が無い。そのためアプリは、利用者が入力したサーバー URL から Origin を作って名乗っている
(例: `https://chat.example.com`)。Web 版と同じホスト名で公開していれば `CORS_ORIGIN` に含まれるので、今はこれで動く。

### 困っていること

- ブラウザ向けの Origin 確認を、アプリが Web 版のふりをして通っている形になっている
- Web 版の Origin とサーバーの公開ホスト名が違う運用だと、アプリから接続できない
  (アプリは「リアルタイム接続が拒否されました」と表示する)

### 入口の案

| 案 | サーバーの変更 | アプリの変更 |
| --- | --- | --- |
| A. Origin が無い接続は Cookie (または S-5 のヘッダ) の認証だけで通す | `beforeHandle` の条件を変える | Origin を送るのをやめる |
| B. `?token=` クエリでの認証を実装する (README には既に書いてある) | `open` で query も見る | URL にトークンを付ける |
| C. `CORS_ORIGIN` とは別に、アプリ用に許可する Origin の設定を足す | 環境変数を 1 つ足す | アプリ専用の Origin を名乗る |

- アプリ側はどの案でも数行の変更で合わせられる。
- B はトークンが URL に載るので、リバースプロキシのアクセスログに残りやすい点に注意。
- A を選ぶ理由: Origin 確認の目的は、ブラウザ上の他サイトからの接続 (CSWSH) を防ぐこと。
  ブラウザは Origin を必ず付けるので、Origin が無い接続はブラウザ以外からのものと判断できる。
  この判断は推測で、作者の意図とは確かめていない。

### 情報: README と実装の食い違い

README には「`?token` クエリでも認証できる」とあるが、実装は Cookie しか見ていない。
backend の履歴を見ると、`?token` は 2026-08-18 の #136「WS接続にqueryトークン見ているのを削除」で **意図して外されている**
(README だけが古い)。Origin の確認は 2026-10-19 の #170 で入った。作者が外した以上、案 B は選ばれにくい
(この読みは履歴からの推測で、作者に確かめてはいない)。案 A か C が現実的。

### 情報: Elysia 2.0 への移行ブランチ

backend に `migrateToElysia2` ブランチがある (最終 2026-07-31、main の #136・#170 より前から分かれている)。
`/ws` のパス・Cookie での認証・JSON のフレームという、アプリから見える約束は変わっていない (`src/ws.ts` の差分で確認)。
main に入る場合は #136・#170 との合わせ込みが要るはずなので、入ったら live-test (backend の最新版を使う) で確かめる。

---

## S-5 ログインのトークンを Cookie 以外でも受け渡せるようにする (要望)

### 今の状況

- `POST /user/sign-in` はトークンを `Set-Cookie` でだけ返す。応答の本文には `userId` しか無い
- 通常の API の認証 (`Middleware.CheckToken`) は Cookie の `token` だけを見る。
  `Authorization` ヘッダは Bot 用 (`CheckBotToken`) だけで使われている
- Cookie の期限は 15 日で、使うたびに `Set-Cookie` で延長される

アプリは `Set-Cookie` から値を取り出して保存し、自分で `Cookie: token=...` を付けている。今はこれで問題なく動く。

### 要望

Web 以外のクライアントでよく使われる形にも対応してほしい。

- `sign-in` の応答の本文にもトークン (と期限) を入れる
- `Authorization: Bearer <token>` でも、Cookie と同じように認証する (HTTP と `/ws` の両方)

S-3 の案 A と組み合わせると、アプリは Cookie も Origin も使わずに済む。
Cookie での認証はそのまま残せば、Web 版には影響しない。

---

## S-2 プッシュ通知 (情報: サーバーの変更は不要)

### 結論 (2026-11-12 更新)

**サーバーのコード変更も Firebase プロジェクトも要らない。** 今の VAPID 付き Web Push のまま、アプリに届く。
配信アプリ (ntfy など) を入れなくてもよい。

### 仕組み

アプリは UnifiedPush の「組み込み FCM 配信」を使う (アプリの判断記録 D-019)。

1. アプリが端末の Google Play 開発者サービスに、**サーバーの VAPID 公開鍵** (`GET /notification/vapid-public-key`) を送信者として登録する。
   宛先 `https://fcm.googleapis.com/fcm/send/<トークン>` が返ってくる。Chrome で Web Push を購読したときと同じ種類の宛先。
2. アプリはその宛先と鍵 (P-256 公開鍵・auth) を、`POST /notification/device/register` に `platform: "web"` で登録する。
3. サーバーは今の `Utils/SendPushNotification.ts` のまま、Web Push (RFC 8030 + RFC 8291 aes128gcm + VAPID) で送る。
   FCM が端末に届け、アプリが復号して通知を出す。Google には暗号文しか見えない。

配信アプリ (ntfy など) を入れている端末では、そちらが使われる (宛先が ntfy のサーバーになるだけで、サーバー側の扱いは同じ)。

サーバー側に要るもの:

1. VAPID 鍵の設定。**本番 (chat.girak.moe) は設定済み** (2026-11-12 に `GET /api/notification/vapid-public-key` で確認)。
2. サーバーから `https://fcm.googleapis.com` へ HTTPS で出られること。**本番では未確認**。
   Web 版の利用者が Chrome でプッシュ通知を受け取れていれば、同じ宛先なので出られている。

### 作者の方針との関係 (2026-11-13)

作者の方針は「Apple 向けは APNs、Web と Android は Web Push」と聞いた。アプリはこのとおり Web Push (`platform: "web"`) で受けているので、
**Android のためにサーバーで足すものは無い**。`platform: "android"` の送信も作らなくてよい。

iOS 対応で `SendPushNotification.ts` に `"ios"` (APNs) の分岐を足すときのお願いは 1 つだけ:

- `"web"` 宛てに送る中身の形を変えないでほしい。アプリは `title` / `body` / `tag` / `data.channelId` / `data.messageId` を読んでいる
  (項目を足すのは問題ない。S-7 のように送信者を足してもらえると助かる)。

### 注意

- VAPID 鍵を作り直すと、登録済みの端末 (Web 版・アプリとも) は登録し直しが必要になる。
- 送信に失敗した宛先 (404 / 410) を消す今の処理は、そのまま役に立つ (アプリを消した端末の宛先が消える)。

### 以前の案 (参考: 今は不要)

アプリに Firebase Messaging SDK を入れ、サーバーに FCM HTTP v1 API での送信 (`platform === "android"`) を足す案だった。
Firebase プロジェクトを誰が持つか、サービスアカウントの鍵をサーバーに置くか、実装を誰がするか、を決める必要があった。
上の方法で要らなくなった。将来、上の方法が Google 側の変更で使えなくなったときの代わりとして残しておく。

---

## S-6 サーバーのバージョンを返す窓口 (要望・あると助かる)

今は、サーバーのバージョンや、どの機能に対応しているかをアプリから知る方法が無い。
S-3 や S-5 のように入口が増えたとき、アプリは「このサーバーは新しい方法に対応しているか」を見て使い分けたい。

- 例: `GET /server/config` の応答に `version` (や対応している機能の一覧) を足す
- 無い場合、アプリは新しい方法を試して、失敗したら古い方法に戻る形で対応する (できるが、少し手間がかかる)

---

## S-7 プッシュの中身に送信者とチャンネル名を入れる (相談・あると助かる)

アプリの通知を、送信者のアイコン付きの会話の形 (Android の MessagingStyle) にしたい (#26)。
今のプッシュの中身 (`PushPayload`) は title / body と `data` (type, messageId, channelId) だけなので、
アプリは通知を受けるたびに `GET /message/:messageId` などで送信者を取りに行くことになる。

- A. `data` に `userId` (送信者) と `channelName` を足す。Web 版にも影響しない (使わないだけ)
- B. 今のまま。アプリが取りに行く (通知が出るまで少し遅れる。通信が 1〜2 回増える)

どちらでもアプリは作れる。

---

## S-8 送信の重複を防ぐ ID (相談・急がない)

電波が不安定なときに送ったメッセージを、つながってから送り直す仕組みを考えている (#30)。
「送ったが応答が返らなかった」場合に送り直すと、二重に投稿される。

- A. `POST /message/send` に任意の `clientMessageId` を受け付け、同じユーザーの同じ ID は 1 回だけ保存する
- B. 今のまま。アプリは送り直す前に履歴を取り直し、同じ本文が無いか確かめる (完全には防げない)

---

## S-9 プッシュの急ぎ度 (Urgency) (相談・急がない)

`src/Utils/SendPushNotification.ts` は `webpush.sendNotification` をオプション無しで呼んでいる。`web-push` の既定は Urgency が `normal`、TTL が 4 週間。

- 確かめたこと: RFC 8030 (5.3) では、`normal` は「電源にも Wi-Fi にもつながっていない端末にも届けてよい」急ぎ度で、例はチャットやカレンダーのメッセージ。今の送り方は仕様に沿っている。
  FCM の公式の説明 (Android のメッセージの優先度) では、優先度 normal のメッセージは、端末が Doze (待機) のとき、抜けるまで配信が遅れうる。high は待機中でもすぐ届けようとする。
  同じ説明に、high のメッセージで通知を出さない (利用者に見える通知にならない) ことが続くと、normal に格下げされうるとある
- 確かめていないこと: FCM が Web Push の Urgency を Android への優先度にどう写すか。公式の説明は見つからず、開発者の報告 (Urgency を付けないと normal 扱いで、待機中に遅れる) だけ。
  実際に本番で通知が遅れているかも分からない
- 選択肢 (アプリの側では決められない):
  - A. メンション・返信の通知だけ `urgency: 'high'` で送る (`sendNotification` の第 3 引数。`sendToWebDevice` に通知の種類 (`eventType`) を渡す変更も要る)。待機中の端末にも遅れず届く見込み (推測)。
    このアプリは、受け取ったプッシュで通知を出すが、アプリが前面でリアルタイム接続中のときと、通知の許可が無いときは出さない。前者は端末が起きているときなので影響は小さい見込み。後者が続くと、FCM の説明のとおり格下げされうる
  - B. すべて `high` にする。通知の多いチャンネルでは電池に響く見込み
  - C. 今のまま。遅れが気になると分かってから考える

---

## S-10 アイコンの変更を知らせる (相談・急がない)

`/user/change-icon` はアイコンを保存するだけで、シグナルを送らない。`/user/icon/<userId>` の URL も変わらず、`max-age=86400, immutable` で返す。
そのため、ほかの人がアイコンを変えたことをクライアントは知る手段がなく、ブラウザでは最大 1 日、前のアイコンが残りうる (ヘッダーからの見立て)。
アプリは、表示のたびに 30 分に 1 回まで取り直して比べることで替えている (#148、D-082)。

- A. アイコンを変えたら `user::ProfileUpdate` (またはアイコン用のシグナル) を送る。クライアントはそれを見て読み直せる
- B. ユーザー情報にアイコンの版 (更新時刻など) を入れ、クライアントは URL に `?v=` として付ける。URL が替わるので `immutable` のままでよい
- C. 今のまま (アプリは取り直しで対応済み)

---

## S-4 そのほか気づいたこと (情報・対応不要)

- `GET /server/banner` は、バナーが未設定だと 404 を返し、サーバーログにエラーとして出る (動作上の問題はない)。
- ユーザー名がログイン名を兼ねるため、プロフィールで名前を変えるとログインに使う名前も変わる。
  利用者が戸惑うかもしれないので、注意書きを出すか、ログイン ID と表示名を分けることを考えてもよい。
