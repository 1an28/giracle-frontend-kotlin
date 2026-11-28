package com.giracle.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * 配布版 APK をエミュレータで外から操作して、通しで確かめる (#12)。
 *
 * 引数 (am instrument -e):
 *   appPackage  確かめるアプリ (既定 io.github.u1an28.giracle.kotlin)
 *   server      端末から見たサーバー (既定 http://localhost:3333。CI は adb reverse で手元のサーバーにつなぐ)
 *   adminUser / adminPass  サーバーの管理者 (既定 admin / password123)
 *   lanServer   端末から LAN のアドレスで見たサーバー (CI は http://10.0.2.2:3333)。無ければ、それを使う確認を飛ばす。
 *               adb reverse の localhost は端末の通信網を通らないので、電波を切る確認 (#37) と
 *               Android 17 のローカルネットワーク権限の確認 (#42) はこちらを使う
 *   uxServer / uxNet  よく使う作業の測定 (uxTasks) でアプリがつなぐサーバーと、ログに付ける回線の名前
 *               (既定 server / fast。e2e-run.sh が遅延の中継を通した遅い回線 (slow) でも測る。D-046)
 *   upgradePhase  上書き更新の確認 (upgradeBefore / upgradeAfter、D-075 の 3) の段階 (before / after)。無ければその 2 つを飛ばす
 *   prevVersion  上書きする前の版の名前 (ログに付けるだけ)
 *   updateVersion  新しい版の確認 (#147) の偽の応答が返す版。指定したときだけ、ホームに知らせが出ることを確かめる
 *
 * 1 つの流れにしてあるのは、ログイン → チャンネル → 背面 → 設定 と、前の段階の状態を引き継ぐため。
 * 各段階の画面を [shots] に保存する (CI が取り出して成果物にする)。
 */
@RunWith(AndroidJUnit4::class)
class ReleaseAppTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instr)
    private val pkg = args.getString("appPackage") ?: "io.github.u1an28.giracle.kotlin"
    private val server = args.getString("server") ?: "http://localhost:3333"
    private val lanServer = args.getString("lanServer")
    /** よく使う作業を測るときにつなぐサーバーと、その回線の名前 (e2e-run.sh が遅い回線でも測る。D-046) */
    private val uxServer = args.getString("uxServer") ?: server
    private val uxNet = args.getString("uxNet") ?: "fast"
    private val adminUser = args.getString("adminUser") ?: "admin"
    private val adminPass = args.getString("adminPass") ?: "password123"
    private val upgradePhase = args.getString("upgradePhase")
    private val prevVersion = args.getString("prevVersion") ?: "?"
    private val updateVersion = args.getString("updateVersion")
    private val shots = File(instr.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    private var suffix = System.currentTimeMillis().toString().takeLast(6)
    /** 上書き更新の確認で、前の段階 (upgradeBefore) から引き継ぐもの (チャンネルと識別子)。確認用 APK は入れ直さないので、その中に置く */
    private val upgradeState = File(instr.context.filesDir, "upgrade-state.txt")

    @get:Rule val testName = TestName()

    private val admin = ServerApi(server)
    private val bob = ServerApi(server)
    private lateinit var channelId: String
    private var step = 0

    @Before fun setUp() {
        // 前のテストが通知欄を開いたまま失敗しても、次のテストの画面を覆わないよう閉じる (巻き添えの失敗を防ぐ)
        runCatching { shell("cmd statusbar collapse"); device.pressHome() }
        if (testName.methodName.startsWith("upgrade")) {
            assumeTrue("upgradePhase の指定が無い", upgradePhase != null && lanServer != null)
            if (testName.methodName == "upgradeAfter") {
                // 前の段階で作ったチャンネルを使う (ここでは作らない)
                admin.signIn(adminUser, adminPass)
                val (id, sfx) = upgradeState.readText().trim().split(" ")
                channelId = id
                suffix = sfx
                return
            }
        }
        // 管理者で招待コードを作り、相手役 (bob) を登録する。確認用のチャンネルを作り、2 人とも参加する
        admin.signIn(adminUser, adminPass)
        val invite = "e2e$suffix"
        admin.createInvite(invite, 2)
        bob.signUp("bob$suffix", "pass1234", invite)
        bob.signIn("bob$suffix", "pass1234")
        channelId = admin.createChannel("e2e$suffix", "配布版の確認用 (自動で消す)")
        runCatching { admin.joinChannel(channelId) } // 作成者が自動で参加する版もあるので失敗は無視する
        bob.joinChannel(channelId)
    }

    @After fun tearDown() {
        shot("last")
        // 上書き更新の確認の前半が通ったときは、後半 (upgradeAfter) が同じチャンネルを使うので消さない
        if (testName.methodName == "upgradeBefore" && upgradeState.exists()) return
        runCatching { admin.deleteChannel(channelId) }
    }

    @Test fun releaseBuildWorksEndToEnd() {
        // 1〜3. まっさらな状態から起動し、ログインして通知の権限を許可する
        freshLogin()

        // 3b. 新しい版の知らせ (#147、D-086)。R8 を通した配布版で、偽の応答 (e2e-run.sh) の JSON を読めて、ホームに出る。
        //     閉じたら消える (後の操作の邪魔をしないよう、ここで閉じる)
        if (updateVersion != null) {
            val notice = wait(res("home.update_notice"), 20_000, "新しい版の知らせ")
            // 押せる行は文字をまとめて持つことがあるので、行そのものと中の要素の両方を見る
            val text = (listOf(notice.text) + notice.findObjects(By.textContains("v$updateVersion")).map { it.text })
                .firstOrNull { it?.contains("v$updateVersion") == true }
            if (text == null) fail("新しい版の知らせに v$updateVersion が出ていない")
            shot("update_notice")
            wait(res("home.update_dismiss"), 5_000, "知らせを閉じるボタン").click()
            if (!device.wait(Until.gone(res("home.update_notice")), 5_000)) fail("閉じた新しい版の知らせが消えない")
            Log.i(TAG, "E2E-RESULT update_notice=done text=$text")
        }

        // 4. チャンネルを開く
        findScrolling(res("home.channel:$channelId"), "確認用チャンネル").click()
        wait(res("screen.channel"), 10_000, "チャンネル画面")

        // 5. 送信: 画面に出て、サーバーにも届いている
        val sent = "送信テスト $suffix"
        wait(res("composer.input"), 10_000, "入力欄").apply { click(); text = sent }
        clickSendChecked(sent)
        // 入力欄の文字に当たらないよう、メッセージの一覧の中だけで探す (入力欄に残ったままでも通っていた)
        wait(res("channel.messages").hasDescendant(By.textContains(sent)), 15_000, "自分の送信 (一覧の中)")
        waitServerSend(sent)
        shot("sent")

        // 6. 受信 (WebSocket): 相手の送信が、取り直さずに画面に出る
        val received = "受信テスト $suffix"
        bob.send(channelId, received)
        wait(By.textContains(received), 15_000, "相手の送信の受信 (WebSocket)")
        shot("received")

        // 6b. リアクション (#20): 長押し → 絵文字ピッカーで 🥳 を付け、もう一度押して外す。サーバーにも反映される
        val receivedId = admin.recent(channelId).first { it.content == received }.id
        wait(res("channel.message:$receivedId"), 5_000, "受信したメッセージ").longClick()
        wait(res("message_actions.emoji_picker"), 5_000, "操作シートのリアクションボタン").click()
        wait(res("emoji_picker.search"), 5_000, "絵文字の検索欄").text = "hooray"
        wait(res("emoji_picker.item:hooray"), 10_000, "絵文字ピッカーの 🥳").click()
        val chip = res("channel.reaction:$receivedId:hooray")
        wait(chip, 15_000, "付けたリアクション (WebSocket で反映)")
        waitFor("サーバーにリアクションが付く") { admin.recent(channelId).first { it.id == receivedId }.reactions["hooray"] == 1 }
        shot("reacted")
        wait(chip, 5_000, "リアクション").click()
        if (!device.wait(Until.gone(chip), 15_000)) fail("押したリアクションが外れない")
        waitFor("サーバーのリアクションが外れる") { admin.recent(channelId).first { it.id == receivedId }.reactions["hooray"] == null }

        // 6c. 他のアプリからの共有 (#4) で、画像と文書ファイルを送る。送り先はホームで選ぶ。
        //     アップロード (#20)、画像の読み込みと表示 (Coil。R8 後)、ファイルの表示まで確かめる
        val png = "e2e-$suffix.png"
        val txt = "e2e-$suffix.txt"
        writeShareFiles(png, txt)
        val uris = arrayListOf(shareUri(png), shareUri(txt))
        val share = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            putExtra(android.content.Intent.EXTRA_TEXT, "共有テスト $suffix")
            clipData = android.content.ClipData.newRawUri("", uris[0]).apply { addItem(android.content.ClipData.Item(uris[1])) }
            setPackage(pkg)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        instr.context.startActivity(share)
        wait(res("home.share_banner"), 15_000, "共有の送り先を選ぶ案内 (ホーム)")
        shot("share_home")
        findScrolling(res("home.channel:$channelId"), "確認用チャンネル (共有の送り先)").click()
        wait(res("screen.channel"), 10_000, "共有の送り先のチャンネル画面")
        waitFor("添付のアップロードが終わり、送信できるようになる", 30_000) { device.findObject(res("composer.send"))?.isEnabled == true }
        shot("share_composer")
        wait(res("composer.send"), 5_000, "送信ボタン").click()
        var sharedMsg: ServerApi.Msg? = null
        waitFor("サーバーに共有の投稿 (添付 2 つと本文) が届く", 20_000) {
            sharedMsg = admin.recent(channelId).firstOrNull { it.content.contains("共有テスト $suffix") && it.files.size == 2 }
            sharedMsg != null
        }
        val imageId = sharedMsg!!.files.first { it.second.startsWith("image/") }.first
        wait(res("channel.image_loaded:$imageId"), 30_000, "送った画像の読み込みと表示")
        wait(By.text(txt), 10_000, "送った文書ファイルの表示")
        shot("shared")
        assertAlive("共有の投稿の後")

        // 6b. 画像を押すと全画面で開き、戻るで閉じる (配布版で、拡大用の大きさの読み込みが落ちないこと)
        wait(res("channel.image_loaded:$imageId"), 5_000, "送った画像").click()
        wait(res("channel.image_viewer"), 10_000, "画像の全画面表示")
        SystemClock.sleep(1_500) // 大きい方の読み込みを待つ
        shot("image_viewer")
        assertAlive("画像の全画面表示")
        device.pressBack()
        waitFor("画像の全画面表示が閉じる", 5_000) { device.findObject(res("channel.image_viewer")) == null }

        // 7. 背面に回して戻る: 背面の間の投稿が戻ったときに取り直され、その後の投稿も WebSocket で届く
        device.pressHome()
        SystemClock.sleep(5_000) // 背面では切断する設定 (常時接続 OFF) なので、切断を待つ
        assertAlive("背面に回した後")
        val whileAway = "背面中の投稿 $suffix"
        bob.send(channelId, whileAway)
        SystemClock.sleep(2_000)
        launch()
        wait(res("screen.channel"), 15_000, "戻ったときのチャンネル画面")
        wait(By.textContains(whileAway), 20_000, "背面の間の投稿 (再接続後の取り直し)")
        val afterReturn = "復帰後の投稿 $suffix"
        bob.send(channelId, afterReturn)
        wait(By.textContains(afterReturn), 15_000, "戻った後の受信 (再接続した WebSocket)")
        shot("resumed")

        // 8. 常時接続 (前面サービス) を ON にしても落ちない。背面でも接続が保たれ、メンションが通知される
        hideKeyboard()
        device.pressBack()
        wait(res("screen.home"), 10_000, "ホーム画面に戻る")
        wait(res("home.settings"), 5_000, "設定ボタン").click()
        wait(res("screen.settings"), 10_000, "設定画面")
        wait(By.text("通知"), 5_000, "通知タブ").click()
        val keep = res("settings.keep_connection")
        findScrolling(keep, "常時接続の設定").let { it.findObject(By.checkable(true)) ?: it }.click()
        waitFor("常時接続の設定が ON になる") { device.findObject(keep)?.findObject(By.checkable(true))?.isChecked == true }
        shot("keep_connection")
        waitFor("前面サービスが動く", 15_000) { shell("dumpsys activity services $pkg").contains("isForeground=true") }
        assertAlive("常時接続を ON にした後")

        device.pressHome()
        SystemClock.sleep(5_000)
        assertAlive("常時接続のまま背面に回した後")
        assertTrue("背面で前面サービスが止まった", shell("dumpsys activity services $pkg").contains("isForeground=true"))
        val mention = "通知テスト @<${admin.userId}> $suffix"
        bob.send(channelId, mention)
        device.openNotification()
        // 通知の本文はメンションを名前に置き換えるので、末尾の識別子だけで探す
        val n = device.wait(Until.findObject(By.textContains("通知テスト")), 20_000)
        shot("notification")
        if (n == null) fail("常時接続で背面にいるときのメンションが通知されない")
        device.pressBack()
        assertAlive("通知を受けた後")

        // 9. プッシュ通知 (D-019): ログイン後に自動で有効になり (配信アプリが無いので Google Play 開発者サービス経由)、
        //    アプリのプロセスが無い状態でも、サーバーの Web Push が FCM 経由で届いて通知になる
        launch()
        wait(res("screen.settings"), 10_000, "設定画面に戻る")
        wait(By.text("通知"), 5_000, "通知タブ").click()
        findScrolling(keep, "常時接続の設定").let { it.findObject(By.checkable(true)) ?: it }.click()
        waitFor("常時接続の設定が OFF になる") { device.findObject(keep)?.findObject(By.checkable(true))?.isChecked == false }
        waitFor("前面サービスが止まる", 15_000) { !shell("dumpsys activity services $pkg").contains("isForeground=true") }
        val status = res("settings.push_status")
        findScrolling(status, "プッシュ通知の状態")
        // 有効になった経路 (auto: 自動の登録とそのやり直し、button: ボタン) と、アプリが登録をやり直した回数を残す (#93、D-077)。
        // 登録の失敗は、Google Play 開発者サービスの準備が済んでいないとき (AUTHENTICATION_FAILED) に起きる
        var via = "auto"
        if (!pushEnabled(status, 60_000)) {
            // 自動の登録 (やり直しを含む) で済んでいなければ、ボタンで登録する
            Log.w(TAG, "自動では有効になっていない: " + device.findObject(status)?.text)
            via = "button"
            findScrolling(res("settings.push_enable"), "プッシュ通知の有効化ボタン").click()
            if (!pushEnabled(status, 90_000)) {
                shot("push_status")
                Log.i(TAG, "E2E-RESULT push enabled=false failures=${pushFailures()}")
                fail("プッシュ通知が有効にならない: " + device.findObject(status)?.text)
            }
        }
        Log.i(TAG, "E2E-RESULT push enabled=true via=$via failures=${pushFailures()}")
        Log.i(TAG, "プッシュ通知の状態: " + device.findObject(status)?.text)
        shot("push_enabled")

        device.pressHome()
        SystemClock.sleep(3_000)
        // 背面のプロセスを終わらせる (利用者がアプリを使っていない間に OS が終わらせるのと同じ状態)
        shell("am kill $pkg")
        val killed = runCatching { waitFor("背面のプロセスが終わる", 10_000) { shell("pidof $pkg").isBlank() } }.isSuccess
        Log.i(TAG, "プロセスを終わらせた: $killed")
        val pushText = "プッシュテスト @<${admin.userId}> $suffix"
        bob.send(channelId, pushText)
        device.openNotification()
        val pn = device.wait(Until.findObject(By.textContains("プッシュテスト")), 60_000)
        shot("push_notification")
        if (pn == null) {
            // 手がかり: アプリの通知の一覧と、アプリのプロセスがあるか
            Log.w(TAG, "notifications:\n" + shell("dumpsys notification --noredact").lines().filter { pkg in it || "android.title" in it || "android.text" in it }.take(40).joinToString("\n"))
            device.pressBack()
            fail("プッシュ通知が届かない (プロセスを終わらせた: $killed、今のプロセス: ${shell("pidof $pkg").trim()})")
        }
        device.pressBack()
    }

    /**
     * 1. 起動 (まっさらな状態から)。R8 で必要なものが消えていれば、ここかログインで落ちる
     * 2. サーバーを指定してログイン
     * 3. 通知の権限の確認 (Android 13 以上)。許可しても落ちないこと
     */
    private fun freshLogin(url: String = server, prev: Boolean = false) {
        fun r(tag: String) = if (prev) prevRes(tag) else res(tag)
        shell("pm clear $pkg")
        // LAN のアドレスのサーバーは、Android 17 以上ではローカルネットワークの権限が要る (#42)。確認画面は別のテストで確かめるので、先に許可しておく
        if ("localhost" !in url && android.os.Build.VERSION.SDK_INT >= 37) shell("pm grant $pkg android.permission.ACCESS_LOCAL_NETWORK")
        launch()
        val field = wait(r("login.server_url"), 30_000, "ログイン画面")
        shot("launch")
        field.text = url
        wait(r("login.connect"), 5_000, "接続ボタン").click()
        wait(r("login.username"), 30_000, "サーバー情報の取得 (login.username)").text = adminUser
        wait(r("login.password"), 5_000, "パスワード欄").text = adminPass
        hideKeyboard() // ボタンを隠さないように
        wait(r("login.submit"), 5_000, "ログインボタン").click()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (device.wait(Until.findObject(ALLOW_BUTTON), 20_000) == null) fail("通知の権限の確認画面が出ない")
            shot("permission")
            if (!clickAllowSettled()) fail("通知の権限の確認画面を閉じられない")
        }
        wait(r("screen.home"), 20_000, "ホーム画面")
        assertAlive("通知の権限を許可した後")
        shot("home")
    }

    /**
     * よく使う作業 (docs/UI_PRINCIPLES.md の 1 章と 9 章、D-046) の手数と時間を測り、ログに出す。
     * 手数は利用者がする操作 (押す・長押し・すべらせる・戻る) の回数。文字の入力は数えない。
     * 時間は、最後の操作から、終わりの状態の要素が画面に出るまで (エミュレータなので、前の実行との比較だけに使う)。
     * 結果は「E2E-RESULT ux net=... task=... taps=... ms=...」の行。e2e-run.sh が CI のログにまとめて出す。
     */
    @Test fun uxTasks() {
        // 準備: A は既読 25 件の後に未読 20 件、B は 10 件 (1 件目に検索の語)
        val chB = admin.createChannel("e2eB$suffix", "配布版の確認用 (自動で消す)")
        try {
            runCatching { admin.joinChannel(chB) }
            bob.joinChannel(chB)
            repeat(25) { bob.send(channelId, "既読%02d-$suffix".format(it + 1)) }
            admin.updateReadTime(channelId, admin.recent(channelId).first().createdAt)
            SystemClock.sleep(50) // 既読の最後と未読の始まりの時刻が同じにならないように
            repeat(20) { bob.send(channelId, "未読%02d-$suffix".format(it + 1)) }
            val findWord = "uxfind$suffix"
            bob.send(chB, "B01-$suffix $findWord")
            for (i in 2..10) bob.send(chB, "B%02d-$suffix".format(i))
            val firstUnread = admin.recent(channelId).first { it.content == "未読01-$suffix" }.id
            val bFirst = admin.recent(chB).first { it.content.startsWith("B01-$suffix") }.id

            logRoundTrip()
            freshLogin(uxServer)

            // 読む: ホームの未読のチャンネルを開き、未読の始まりが見える
            UxTask("read_unread").run {
                tap(findScrolling(res("home.channel:$channelId"), "チャンネル A"))
                done(By.textContains("未読01-$suffix"))
            }
            shot("ux_read_unread")

            // 未読の始まりへ戻る (C-5): 上 (古い側) へ読み進めて未読の始まりが隠れた後、ボタンで戻る
            val list = wait(res("channel.messages"), 5_000, "メッセージの一覧")
            repeat(3) { list.scroll(Direction.UP, 1.0f) }
            waitFor("未読の始まりが画面の外に出る", 5_000) { device.findObject(By.textContains("未読01-$suffix")) == null }
            UxTask("back_to_unread").run {
                tap(wait(res("channel.jump_unread"), 5_000, "「未読の始まりへ」のボタン"))
                done(By.textContains("未読01-$suffix"))
            }

            // 返す: 未読の始まりのメッセージを右へすべらせて返信する。終わりは、送った返信が画面に見えること
            val reply = "返信-$suffix"
            val replyTask = UxTask("reply")
            replyTask.run {
                swipeRight(wait(res("channel.message:$firstUnread"), 5_000, "未読の始まりのメッセージ"))
                wait(res("composer.reply"), 5_000, "返信先の表示")
                tap(wait(res("composer.input"), 5_000, "入力欄"))
                wait(res("composer.input"), 5_000, "入力欄").text = reply
                tap(wait(res("composer.send"), 5_000, "送信ボタン"))
                // 読んでいる途中で返しても、送った返信が画面に見える (#58、D-051。以前は一番下に出て見えなかった)。
                // メッセージの一覧の中だけを探す (一覧の外の入力欄などに残った本文に一致させないため。#57)
                done(By.res("channel.messages").hasDescendant(By.textContains(reply)), timeoutMs = 8_000)
            }
            logSendBreakdown(replyTask)
            waitFor("サーバーに返信が届く") { admin.recentTexts(channelId).contains(reply) }
            shot("ux_reply")

            // 切り替える (初めて開く): チャンネル → ホーム → B。B の最新が見える
            hideKeyboard()
            UxTask("switch_first").run {
                back()
                tap(findScrolling(res("home.channel:$chB"), "チャンネル B"))
                done(By.textContains("B10-$suffix"))
            }
            // 切り替える (一度開いた A に戻る)。A の最新 (自分の返信) が見える
            UxTask("switch_again").run {
                back()
                tap(findScrolling(res("home.channel:$channelId"), "チャンネル A"))
                done(By.textContains(reply))
            }

            // 切り替える (つなぎ直した後に、一度開いた B へ)。背面に回すと接続を切る設定 (常時接続 OFF) なので、
            // 戻るとつなぎ直して読み込み直す (docs/UI_PRINCIPLES.md の C-2)
            // 端末の時刻 (エポック秒)。この時刻より後に、再同期の終わりのログが出るのを待つ
            val since = device.executeShellCommand("date +%s").trim()
            device.pressHome()
            SystemClock.sleep(5_000)
            launch()
            wait(res("screen.channel"), 15_000, "戻ったときのチャンネル画面")
            val ping = "再接続確認-$suffix"
            bob.send(channelId, ping)
            wait(By.textContains(ping), 20_000, "戻った後の受信 (つなぎ直した WebSocket)")
            // つなぎ直しの後の再同期 (読み込み直し) が終わるのを、アプリのログで待つ。
            // 以前は 3 秒だけ待っており、遅い回線では再同期が終わる前に B を開いて、つなぎ直す前の状態を測っていた (#57)
            val waitFrom = SystemClock.uptimeMillis()
            waitFor("つなぎ直した後の再同期が終わる (GiracleSync)", 30_000) { resyncedSince(since) }
            Log.i(TAG, "E2E-RESULT ux net=$uxNet task=resync_wait ms=${SystemClock.uptimeMillis() - waitFrom}")
            UxTask("switch_after_reconnect").run {
                back()
                tap(findScrolling(res("home.channel:$chB"), "チャンネル B"))
                done(By.textContains("B10-$suffix"))
            }
            shot("ux_switch_after_reconnect")

            // 自分宛てを拾う: B にいる間に A でメンションされる。ホーム → 通知 → その行で、メンションが見える
            val mention = "メンション-$suffix @<${admin.userId}>"
            bob.send(channelId, mention)
            val mentionId = admin.recent(channelId).first { it.content == mention }.id
            device.pressBack()
            wait(res("screen.home"), 10_000, "ホーム画面")
            SystemClock.sleep(2_000) // 通知 (受信箱) への反映を待つ
            UxTask("inbox").run {
                tap(wait(res("home.inbox"), 5_000, "通知の行"))
                tap(wait(res("inbox.item:$mentionId"), 15_000, "通知の一覧のメンション"))
                done(res("channel.message:$mentionId"))
            }
            shot("ux_inbox")

            // 探す: ホーム → 検索 → 語を入れて検索 → 結果を開くと、そのメッセージが見える
            // 通知の一覧から開いたチャンネルは、通知の一覧の上に積まれるので、戻るは 2 回
            device.pressBack()
            wait(res("screen.inbox"), 10_000, "通知の一覧に戻る")
            device.pressBack()
            wait(res("screen.home"), 10_000, "ホーム画面")
            UxTask("search").run {
                tap(wait(res("home.search"), 5_000, "検索ボタン"))
                tap(wait(res("search.input"), 5_000, "検索の入力欄"))
                wait(res("search.input"), 5_000, "検索の入力欄").text = findWord
                tap(wait(res("search.submit"), 5_000, "検索の実行ボタン"))
                part("search_results", res("search.result:$bFirst"))
                tap(wait(res("search.result:$bFirst"), 5_000, "検索の結果"))
                done(res("channel.message:$bFirst"))
            }
            shot("ux_search")
            assertAlive("よく使う作業の測定の後")
            Log.i(TAG, "E2E-RESULT ux_test=done net=$uxNet sdk=${android.os.Build.VERSION.SDK_INT}")
        } finally {
            runCatching { admin.deleteChannel(chB) }
        }
    }

    /**
     * 端末から [uxServer] への往復時間を 5 回測ってログに出す。遅い回線 (e2e-run.sh の遅延の中継) が
     * 実際に効いているかを、作業の時間とは別に確かめるため (#57)。新しい接続を毎回張る
     */
    private fun logRoundTrip() {
        val ms = (1..5).map {
            val t = SystemClock.uptimeMillis()
            runCatching {
                val c = java.net.URL("${uxServer.trimEnd('/')}/api/server/config").openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("Connection", "close")
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.inputStream.use { it.readBytes() }
                c.disconnect()
            }.fold({ SystemClock.uptimeMillis() - t }, { -1L })
        }
        Log.i(TAG, "E2E-RESULT ux net=$uxNet task=round_trip ms=${ms.sorted()[2]} all=$ms")
    }

    /**
     * 端末の時刻 [since] (エポック秒) より後に、アプリが再同期を終えたか (ChatStore.resync が終わりに出すログ)。
     * 行数で比べるとログのリングバッファが回ったときに数え違えるので、時刻で区切る。
     * 500ms ごとに呼ぶので、[shell] を通さず (問い合わせのたびにデバッグ行を残さず) に問い合わせる
     */
    private fun resyncedSince(since: String): Boolean =
        device.executeShellCommand("logcat -d -s GiracleSync:I -T $since.000").lines().any { "resync:" in it }

    /**
     * 送信を押してから返信が見えるまでの内訳 (#70)。アプリの記録 (SendTrace、タグ GiraclePerf) と e2e の時刻を、
     * 端末共通の uptimeMillis で並べる。値はすべて e2e が送信を押した時刻からの ms。無い区切りは -1。
     * - input: アプリが押されたと受け取るまで (click の注入を含む)。click: UiObject2.click() が戻るまで
     * - http_body: 送信の HTTP の応答の本文を IO で読み終えた (通信の終わり)。parsed: 解析が終わった (Default)。
     *   main: 呼んだ側 (メインスレッド) に戻った。http: 一覧に足した後、画面に送信の終わりを知らせる直前。ws: WebSocket で自分の送信が届いた。in_list: 画面の一覧に載り、送る動きを始めた
     * - scroll: 送る動きの終わり (cancelled は取り消された時刻)。frame: その次の描画。found: e2e が見つけた
     * - row_local: 仮のもの (D-071) の行を初めて組み立てた。effect_start: 送ったものへ動かす効果が最初に動いた (どちらも D-078。2 回目以降は GiraclePerf の行を見る)
     */
    private fun logSendBreakdown(task: UxTask) {
        val t0 = task.lastAt
        val phases = mutableMapOf<String, Long>()
        val detail = mutableMapOf<String, String>()
        val re = Regex("send phase=(\\w+) t=(\\d+)(.*)")
        // 近くへ動かすときは、動きの途中で返信が見つかる。送る動きの終わり (frame か scroll_cancelled) が出るまで読み直す (上限 3 秒)
        val end = SystemClock.uptimeMillis() + 3_000
        do {
            phases.clear()
            detail.clear()
            device.executeShellCommand("logcat -d -s $PERF_TAG:I").lines().forEach { line ->
                val m = re.find(line) ?: return@forEach
                val t = m.groupValues[2].toLong()
                // 送信を押す前 (前の送信) の記録は使わない。同じ区切りが複数あれば最初のもの
                if (t >= t0 && m.groupValues[1] !in phases) {
                    phases[m.groupValues[1]] = t
                    detail[m.groupValues[1]] = m.groupValues[3].trim()
                }
            }
            val settled = "frame" in phases || "scroll_cancelled" in phases
            if (!settled) SystemClock.sleep(100)
        } while (!settled && SystemClock.uptimeMillis() < end)
        fun at(p: String) = phases[p]?.let { it - t0 } ?: -1
        Log.i(TAG, "E2E-RESULT ux net=$uxNet task=reply_breakdown input=${at("tap")} click=${task.lastClickMs} " +
            "http_body=${at("http_body")} parsed=${at("parsed")} main=${at("main_resumed")} http=${at("http_done")} ws=${at("ws_arrived")} in_list=${at("in_list")} scroll=${at("scroll_done")} " +
            "cancelled=${at("scroll_cancelled")} frame=${at("frame")} found=${task.foundAt - t0} " +
            "row_local=${at("row_local")} effect_start=${at("effect_start")}")
        Log.i(TAG, "E2E-RESULT ux net=$uxNet task=reply_detail in_list=[${detail["in_list"]}] " +
            "scroll=[${detail["scroll_done"] ?: detail["scroll_cancelled"]}] frame=[${detail["frame"]}]")
    }

    /** 1 つの作業の手数と時間を数える ([uxTasks]) */
    private inner class UxTask(private val name: String) {
        private var taps = 0
        private var last = 0L
        /** 最後の「押す」で UiObject2.click() にかかった時間 (#70。e2e 側の待ちを分けるため) */
        var lastClickMs = 0L
            private set
        /** 終わりの状態の要素が見つかった時刻 (uptimeMillis。[lastAt] と同じ時計) */
        var foundAt = 0L
            private set
        /** 最後の操作の時刻 (uptimeMillis) */
        val lastAt get() = last

        private fun mark() { taps++; last = SystemClock.uptimeMillis() }
        fun tap(o: UiObject2) {
            mark()
            o.click()
            lastClickMs = SystemClock.uptimeMillis() - last
        }
        fun back() { mark(); device.pressBack() }

        /** 左端の「戻る」の操作と取り合わないよう、要素の 3 割の位置から右へすべらせる */
        fun swipeRight(o: UiObject2) {
            val b = o.visibleBounds
            val y = b.centerY()
            mark()
            device.swipe(b.left + b.width() * 3 / 10, y, b.left + b.width() * 8 / 10, y, 20)
        }

        /** 作業の途中の区切り ([label]) として、要素が出るまでの時間と、ここまでの手数を出す */
        fun part(label: String, sel: BySelector, timeoutMs: Long = 20_000) = measure(label, sel, timeoutMs, required = true)

        /** 終わりの状態の要素が出るまでの時間を測る。[required] でなければ、出なくても失敗にせず shown=false と記録する */
        fun done(sel: BySelector, timeoutMs: Long = 20_000, required: Boolean = true) = measure(name, sel, timeoutMs, required)

        // UiDevice.wait は問い合わせの間隔が粗いので、自分で短い間隔で探す。
        // findObject は既定で、画面が 500ms 落ち着くのを待ってから探す。それを測る時間に含めないよう、測る間だけ待たない
        private fun measure(label: String, sel: BySelector, timeoutMs: Long, required: Boolean) {
            val conf = Configurator.getInstance()
            val idle = conf.getWaitForIdleTimeout()
            conf.setWaitForIdleTimeout(0)
            var polls = 0
            var pollMax = 0L
            val found = try {
                val end = last + timeoutMs
                var hit = false
                while (!hit && SystemClock.uptimeMillis() < end) {
                    val p0 = SystemClock.uptimeMillis()
                    hit = device.findObject(sel) != null
                    val p = SystemClock.uptimeMillis() - p0
                    polls++
                    if (p > pollMax) pollMax = p
                    if (!hit) SystemClock.sleep(30)
                }
                hit
            } finally {
                conf.setWaitForIdleTimeout(idle)
            }
            if (found) {
                foundAt = SystemClock.uptimeMillis()
                // polls: 探した回数。poll_max: 1 回の探索の最長 (判定の粒度。#70)
                Log.i(TAG, "E2E-RESULT ux net=$uxNet task=$label taps=$taps ms=${foundAt - last} polls=$polls poll_max=$pollMax")
                return
            }
            Log.i(TAG, "E2E-RESULT ux net=$uxNet task=$label taps=$taps ms=-1 shown=false")
            if (required) {
                shot("ux_$label")
                assertAlive("「$label」の測定")
                fail("「$label」の終わりの状態が ${timeoutMs / 1000} 秒以内に出ない")
            }
        }
    }

    /**
     * LAN のアドレスのサーバーにつなぐ (#42、#37)。
     * 1. Android 17 以上: ローカルネットワークの権限を求められ、拒否すると理由が出る。許可するとつながる
     * 2. 電波を切って送ったメッセージが「送信待ち」になり、電波が戻ると 1 件だけ届く (#37、D-029)
     */
    @Test fun lanServerPermissionAndOfflineSend() {
        assumeTrue("lanServer の指定が無い", lanServer != null)
        val lan = lanServer!!
        shell("pm clear $pkg")
        launch()
        wait(res("login.server_url"), 30_000, "ログイン画面").text = lan
        wait(res("login.connect"), 5_000, "接続ボタン").click()
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            // 1 回目は拒否する: 理由と設定を開くボタンが出る
            val deny = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny_button"))), 20_000)
            shot("lan_permission")
            if (deny == null) fail("LAN のサーバーなのに、ローカルネットワークの権限の確認画面が出ない (Android 17)")
            deny!!.click()
            wait(By.textContains("付近のデバイス"), 10_000, "権限が無いときの理由の表示")
            shot("lan_denied")
            // 設定で許可したのと同じ状態にして、つなぎ直す
            shell("pm grant $pkg android.permission.ACCESS_LOCAL_NETWORK")
            wait(res("login.connect"), 5_000, "接続ボタン (2 回目)").click()
        }
        wait(res("login.username"), 30_000, "LAN のサーバーの情報の取得 (login.username)").text = adminUser
        wait(res("login.password"), 5_000, "パスワード欄").text = adminPass
        hideKeyboard()
        wait(res("login.submit"), 5_000, "ログインボタン").click()
        if (device.wait(Until.findObject(ALLOW_BUTTON), 15_000) != null) clickAllowSettled()
        wait(res("screen.home"), 20_000, "ホーム画面 (LAN のサーバー)")
        findScrolling(res("home.channel:$channelId"), "確認用チャンネル").click()
        wait(res("screen.channel"), 10_000, "チャンネル画面")
        // WebSocket がつながったことを、相手の送信の受信で確かめる (Origin が CORS_ORIGIN に入っていること)
        val hello = "LAN 受信 $suffix"
        bob.send(channelId, hello)
        wait(By.textContains(hello), 20_000, "LAN のサーバーからの受信 (WebSocket)")
        shot("lan_connected")

        // 2. 電波を切って送る (#37)
        val offline = "電波なし送信 $suffix"
        shell("cmd connectivity airplane-mode enable")
        try {
            SystemClock.sleep(3_000)
            wait(res("composer.input"), 10_000, "入力欄").apply { click(); text = offline }
            waitSendButtonSettled("送信ボタン").click()
            wait(res("composer.outbox"), 30_000, "送信待ちの表示 (composer.outbox)")
            shot("outbox")
            assertTrue("電波が無いのにサーバーに届いている", admin.recentTexts(channelId).none { it == offline })
        } finally {
            shell("cmd connectivity airplane-mode disable")
        }
        // 3. 電波が戻ると、送信待ちが消えて 1 件だけ届く
        val restoredAt = SystemClock.uptimeMillis()
        if (!device.wait(Until.gone(res("composer.outbox")), 90_000)) { shot("outbox_stuck"); fail("電波が戻っても送信待ちが消えない") }
        waitFor("電波が戻った後、サーバーに届く", 30_000) { admin.recentTexts(channelId).contains(offline) }
        // 電波を戻してから届くまでの時間 (D-034 の効果を見るため)
        Log.i(TAG, "E2E-RESULT outbox_delivered_ms=${SystemClock.uptimeMillis() - restoredAt}")
        SystemClock.sleep(3_000)
        val count = admin.recentTexts(channelId).count { it == offline }
        assertTrue("送信待ちのメッセージが $count 件届いた (1 件のはず)", count == 1)
        wait(By.textContains(offline), 15_000, "送信待ちだったメッセージの表示")
        shot("outbox_sent")
        assertAlive("送信待ちを送った後")
        // e2e-run.sh が、飛ばされずに最後まで動いたことをこの印で確かめる
        Log.i(TAG, "E2E-RESULT lan_test=done sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    /**
     * 電波が無いときに起動する (#82、P-2)。前回の内容が見え続けるか、電波が戻ったら操作なしで最新になるかを測る。
     * adb reverse の localhost は機内モードでも届くので、LAN のアドレスのサーバー (lanServer) を使う。
     * 結果は「E2E-RESULT offline_start ...」の行。
     */
    @Test fun offlineColdStart() {
        assumeTrue("lanServer の指定が無い", lanServer != null)
        freshLogin(lanServer!!)
        findScrolling(res("home.channel:$channelId"), "確認用チャンネル").click()
        wait(res("screen.channel"), 10_000, "チャンネル画面")
        val before = "圏外起動の前 $suffix"
        bob.send(channelId, before)
        wait(By.textContains(before), 20_000, "圏外にする前の受信")
        // 背面に回して前回の内容 (スナップショット) を書かせてから、プロセスを止める
        device.pressHome()
        SystemClock.sleep(3_000)
        shell("am force-stop $pkg")
        val after = "圏外の間の送信 $suffix"
        val mine = "圏外起動から送信 $suffix"
        var outboxShown = false
        var shownMs = -1L
        var errorMs = -1L
        var hiddenMs = -1L
        var bannerMs = -1L
        var noticeWithBannerMs = -1L
        shell("cmd connectivity airplane-mode enable")
        try {
            SystemClock.sleep(3_000)
            val t0 = SystemClock.uptimeMillis()
            launch()
            // 15 秒見続ける (読み込みの失敗は、接続のタイムアウト 10 秒より前に出るはず)
            while (SystemClock.uptimeMillis() - t0 < 15_000) {
                val now = SystemClock.uptimeMillis() - t0
                val visible = device.findObject(By.textContains(before)) != null
                if (visible && shownMs < 0) shownMs = now
                if (!visible && shownMs >= 0 && hiddenMs < 0) hiddenMs = now
                if (errorMs < 0 && device.findObject(By.text(LOAD_ERROR_TEXT)) != null) errorMs = now
                if (bannerMs < 0 && device.findObject(res("app.offline_banner")) != null) bannerMs = now
                // 帯が出ている間は、チャンネルの題名の下に接続の知らせを重ねない (D-073)
                if (noticeWithBannerMs < 0 && device.findObject(res("app.offline_banner")) != null && device.findObject(res("app.connection_notice")) != null) noticeWithBannerMs = now
                SystemClock.sleep(300)
            }
            shot("offline_start")
            // 書ける (P-2): 圏外で起動したままでも、送ると送信待ちになる (D-029)
            wait(res("composer.input"), 10_000, "入力欄 (圏外で起動)").apply { click(); text = mine }
            waitSendButtonSettled("送信ボタン (圏外で起動)").click()
            outboxShown = device.wait(Until.findObject(res("composer.outbox")), 20_000) != null
            hideKeyboard()
            // 圏外の間に、相手が送る (端末の外のこと。電波が戻ったら見えるはず)
            bob.send(channelId, after)
        } finally {
            shell("cmd connectivity airplane-mode disable")
        }
        val stillShown = device.findObject(By.textContains(before)) != null
        val back = SystemClock.uptimeMillis()
        val updated = device.wait(Until.findObject(By.textContains(after)), 60_000) != null
        val updatedMs = if (updated) SystemClock.uptimeMillis() - back else -1L
        shot("offline_back")
        var delivered = false
        runCatching { waitFor("圏外で送ったものがサーバーに届く", 30_000) { admin.recentTexts(channelId).contains(mine) }; delivered = true }
        SystemClock.sleep(2_000)
        val deliveredCount = admin.recentTexts(channelId).count { it == mine }
        val errorStill = device.findObject(By.text(LOAD_ERROR_TEXT)) != null
        Log.i(
            TAG,
            "E2E-RESULT offline_start shown_ms=$shownMs hidden_ms=$hiddenMs error_ms=$errorMs banner_ms=$bannerMs notice_with_banner_ms=$noticeWithBannerMs still_shown_15s=$stillShown " +
                "updated_after_net_ms=$updatedMs error_after_net=$errorStill outbox_shown=$outboxShown delivered=$deliveredCount sdk=${android.os.Build.VERSION.SDK_INT}",
        )
        assertAlive("電波が無いときに起動した後")
        // 直した後の期待 (#82): 前回の内容は見え続け、画面全体の失敗の表示にならない。電波が戻ったら操作なしで最新になる
        assertTrue("電波が無いときに起動して、前回の内容が出ない (shown_ms=$shownMs)", shownMs >= 0)
        assertTrue("電波が無いときに起動して、前回の内容が隠れた (hidden_ms=$hiddenMs error_ms=$errorMs)", stillShown && hiddenMs < 0 && errorMs < 0)
        assertTrue("電波が無いときに、オフラインの帯 (app.offline_banner) が出ない", bannerMs >= 0)
        assertTrue("起動時の帯と接続の知らせが重なって出た (notice_with_banner_ms=$noticeWithBannerMs)", noticeWithBannerMs < 0)
        assertTrue("電波が戻っても、操作なしでは圏外の間の送信が出ない", updated)
        assertTrue("圏外で起動して送ったものが、送信待ちにならない", outboxShown)
        assertTrue("圏外で起動して送ったものが $deliveredCount 件届いた (1 件のはず。delivered=$delivered)", deliveredCount == 1)
        Log.i(TAG, "E2E-RESULT offline_start_test=done sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    /**
     * 上書き更新で失わない (D-075 の 3、D-080) の前半。前の版 (e2e-run.sh が入れる) で次の状態を作る:
     * ログイン (LAN のサーバー。電波を切る確認に使うため) → 設定を 1 つ変える → 圏外で送って送信待ちを作る → 下書きを書く。
     * 圏外のまま終える (後半で、電波が無いまま起動しても残っているかを見てから、電波を戻す)。
     * 前の版の画面は前の版の testTag・文字で操作する ([prevRes]・[prevText])。
     */
    @Test fun upgradeBefore() {
        assumeTrue("upgradePhase=before ではない", upgradePhase == "before")
        upgradeState.delete()
        var ok = false
        try {
            freshLogin(lanServer!!, prev = true)
            // 設定: 「送る写真を縮小する」を切り替える (切り替えた後の値を後半で見る)
            wait(prevRes("home.settings"), 5_000, "設定ボタン (前の版)").click()
            wait(prevRes("screen.settings"), 10_000, "設定画面 (前の版)")
            wait(prevText(SETTING_TAB), 5_000, "チャットのタブ (前の版)").click()
            val sw = switchFor(prevText(SETTING_LABEL), "写真の縮小の設定 (前の版)")
            val after = !sw.isChecked
            sw.click()
            waitFor("写真の縮小の設定が切り替わる (前の版)") { switchFor(prevText(SETTING_LABEL), "写真の縮小の設定").isChecked == after }
            device.pressBack()
            findScrolling(prevRes("home.channel:$channelId"), "確認用チャンネル (前の版)").click()
            wait(prevRes("screen.channel"), 10_000, "チャンネル画面 (前の版)")
            // 送信待ち: 圏外で送る
            val queued = "更新前の送信待ち $suffix"
            shell("cmd connectivity airplane-mode enable")
            SystemClock.sleep(3_000)
            wait(prevRes("composer.input"), 10_000, "入力欄 (前の版)").apply { click(); text = queued }
            waitSendButtonSettled("送信ボタン (前の版)", prevRes("composer.send")).click()
            wait(prevRes("composer.outbox"), 30_000, "送信待ちの表示 (前の版)")
            // 下書き: 送らずに残す
            val draft = "更新前の下書き $suffix"
            wait(prevRes("composer.input"), 10_000, "入力欄 (前の版。下書き)").apply { click(); text = draft }
            SystemClock.sleep(1_500) // 下書きは手が止まって 0.5 秒後に保存される
            hideKeyboard()
            device.pressHome() // 画面を離れて、保存を書き切らせる
            SystemClock.sleep(3_000)
            assertTrue("電波が無いのにサーバーに届いている (前の版)", admin.recentTexts(channelId).none { it == queued })
            shot("upgrade_before")
            upgradeState.writeText("$channelId $suffix ${if (after) 1 else 0}")
            Log.i(TAG, "E2E-RESULT upgrade_before=done from=$prevVersion setting_after=$after sdk=${android.os.Build.VERSION.SDK_INT}")
            ok = true
        } finally {
            // 失敗したときは電波を戻しておく (後の確認を巻き添えにしない)。通ったときは圏外のまま後半へ渡す
            if (!ok) shell("cmd connectivity airplane-mode disable")
        }
    }

    /**
     * 上書き更新で失わない (D-075 の 3、D-080) の後半。新しい版を上書きした後 (e2e-run.sh が adb install -r)、圏外のまま起動して、
     * ログインし直しが要らないこと・設定・下書き・送信待ちが残っていることを見る。電波を戻し、送信待ちが 1 件だけ届くことを見る。
     * 結果は「E2E-RESULT upgrade ...」の行。
     */
    @Test fun upgradeAfter() {
        assumeTrue("upgradePhase=after ではない", upgradePhase == "after")
        val settingAfter = upgradeState.readText().trim().split(" ")[2] == "1"
        val queued = "更新前の送信待ち $suffix"
        val draft = "更新前の下書き $suffix"
        var loginKept = false
        var settingKept = false
        var draftKept = false
        var outboxShown = false
        var channelOffline = false
        var delivered = 0
        try {
            launch()
            // ログインし直しが要らない: ログイン後の画面が出て、ログイン画面が出ない。
            // 前回開いていた画面に戻るので、ホームとは限らない (前回のチャンネル画面に戻ることがある)
            val first = waitDismissingSystemDialogs(By.res(Pattern.compile("screen\\.(home|channel)")), 30_000)
            loginKept = first != null && device.findObject(res("login.server_url")) == null
            shot("upgrade_launch")
            if (!loginKept) dumpUi("上書き後の起動でログイン後の画面が出ない")
            if (loginKept) {
                var channelChecked = false
                // チャンネルの下書きと送信待ちの表示を見る (圏外のまま)
                fun checkChannel() {
                    draftKept = pollUntil(5_000) { device.findObject(res("composer.input"))?.text?.contains(draft) == true }
                    if (channelOffline) {
                        outboxShown = device.wait(Until.findObject(res("composer.outbox")), 10_000) != null
                        assertTrue("電波が無いのにサーバーに届いている (上書き後)", admin.recentTexts(channelId).none { it == queued })
                    }
                    shot("upgrade_channel")
                    channelChecked = true
                }
                if (device.findObject(res("screen.channel")) != null) {
                    // 前回の確認用チャンネルに戻った (ほかのチャンネルを開く手順は無いので、このチャンネルのはず)
                    channelOffline = true
                    checkChannel()
                    hideKeyboard()
                    device.pressBack()
                }
                wait(res("screen.home"), 10_000, "ホーム画面")
                wait(res("home.settings"), 5_000, "設定ボタン").click()
                wait(res("screen.settings"), 10_000, "設定画面")
                wait(By.text(SETTING_TAB), 5_000, "チャットのタブ").click()
                settingKept = switchFor(By.text(SETTING_LABEL), "写真の縮小の設定").isChecked == settingAfter
                shot("upgrade_setting")
                device.pressBack()
                wait(res("screen.home"), 10_000, "ホーム画面に戻る")
                if (!channelChecked) {
                    // 前回の内容は圏外でも出るはず (P-2) だが、この確認の対象ではない。出なければ記録して電波を戻してから開く
                    device.wait(Until.findObject(res("home.channel:$channelId")), 15_000)
                    channelOffline = runCatching { findScrolling(res("home.channel:$channelId"), "確認用チャンネル (圏外)") }.isSuccess
                    if (!channelOffline) shell("cmd connectivity airplane-mode disable")
                    findScrolling(res("home.channel:$channelId"), "確認用チャンネル").click()
                    wait(res("screen.channel"), 10_000, "チャンネル画面")
                    checkChannel()
                }
            }
        } finally {
            shell("cmd connectivity airplane-mode disable")
        }
        // つながったら送信待ちが送られる (ログインとサーバー URL が残っていることの確かめにもなる)。1 件だけ
        if (pollUntil(90_000) { admin.recentTexts(channelId).contains(queued) }) {
            SystemClock.sleep(3_000)
            delivered = admin.recentTexts(channelId).count { it == queued }
        }
        Log.i(
            TAG,
            "E2E-RESULT upgrade from=$prevVersion login_kept=$loginKept setting_kept=$settingKept draft_kept=$draftKept " +
                "outbox_shown=$outboxShown channel_offline=$channelOffline delivered=$delivered sdk=${android.os.Build.VERSION.SDK_INT}",
        )
        if (delivered == 0) shot("upgrade_not_delivered")
        assertAlive("上書き後")
        assertTrue("上書きした後、ログインし直しが要る (ログイン後の画面が出ない)", loginKept)
        assertTrue("上書きした後、設定 ($SETTING_LABEL) が残っていない", settingKept)
        assertTrue("上書きした後、下書きが残っていない", draftKept)
        // 圏外でチャンネルを開けなかったときは、表示は見られないが、届いた件数で残っていたことを確かめる
        assertTrue("上書きした後、送信待ちの表示が無い", outboxShown || !channelOffline)
        assertTrue("上書きした後、送信待ちが $delivered 件届いた (1 件のはず)", delivered == 1)
        Log.i(TAG, "E2E-RESULT upgrade_test=done sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    /**
     * 前の版の画面の要素。testTag を変えた (名前を変えた・消した) ときは、前の版が出ている間 (次のリリースまで)、
     * [RENAMED_SINCE_PREV] に「今の名前 → 前の版の名前」を足す。足し忘れると、変えた PR の e2e の upgradeBefore が失敗して気付ける
     */
    private fun prevRes(tag: String): BySelector = By.res(RENAMED_SINCE_PREV[tag] ?: tag)

    /** 前の版の画面の文字 ([prevRes] と同じ考え) */
    private fun prevText(text: String): BySelector = By.text(RENAMED_SINCE_PREV[text] ?: text)

    /** 設定の 1 行 (文字 [label]) のスイッチ。文字から親へたどって、同じ行のスイッチを探す (設定の行には testTag が無い) */
    private fun switchFor(label: BySelector, what: String): UiObject2 {
        var o: UiObject2? = wait(label, 5_000, what)
        repeat(4) {
            o = o?.parent ?: return@repeat
            o?.findObject(By.checkable(true))?.let { return it }
        }
        dumpUi("「$what」のスイッチが見つからない")
        fail("「$what」のスイッチが見つからない"); error("unreachable")
    }

    /** 共有するファイル (64x64 の PNG と、短い文書) をキャッシュの share/ に作る */
    private fun writeShareFiles(png: String, txt: String) {
        val dir = File(instr.context.cacheDir, "share").apply { mkdirs() }
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(0x33, 0x99, 0xcc))
        File(dir, png).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, txt).writeText("共有テストの文書 $suffix\n")
    }

    private fun shareUri(name: String): android.net.Uri = android.net.Uri.parse("content://${ShareProvider.AUTHORITY}/$name")

    /** アプリが受け取った、配信先への登録の失敗の回数 (やり直した回数の目安) */
    private fun pushFailures(): Int =
        shell("logcat -d -s GiraclePush:W").lines().count { "registration failed" in it }

    private fun pushEnabled(status: BySelector, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (device.findObject(status)?.text?.startsWith("有効") == true) return true
            SystemClock.sleep(1_000)
        }
        return false
    }

    // ---------- 補助 ----------

    /** ソフトウェアキーボードが出ていれば閉じる (出ていないときに「戻る」を押すと画面を離れてしまうので確かめる) */
    private fun hideKeyboard() {
        if (shell("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            SystemClock.sleep(500)
        }
    }

    private fun res(tag: String): BySelector = By.res(tag)

    /** ランチャーから開くのと同じ画面を起動する (起動画面のクラス名は端末に問い合わせる) */
    private fun launch() {
        val component = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
            .lines().map { it.trim() }.lastOrNull { it.contains('/') }
            ?: error("$pkg の起動画面が見つからない (インストールされていない?)")
        shell("am start -W -n $component")
    }

    private fun wait(sel: BySelector, timeoutMs: Long, what: String): UiObject2 =
        waitDismissingSystemDialogs(sel, timeoutMs.also { Log.i(TAG, "待つ: $what") }) ?: run {
            assertAlive("「$what」を待つ間")
            shot("missing")
            dumpUi("「$what」が出ない")
            fail("「$what」が ${timeoutMs / 1000} 秒以内に出ない"); error("unreachable")
        }

    /**
     * 要素を待つ。間に、ほかのアプリの「応答していません」ダイアログ (エミュレータが重いときに出る) が
     * 画面を覆ったら「待つ」を押して閉じる。アプリ自身の不具合ではないので、確認を止めない。
     */
    private fun waitDismissingSystemDialogs(sel: BySelector, timeoutMs: Long): UiObject2? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            device.findObject(By.res("android:id/aerr_wait"))?.let {
                Log.w(TAG, "システムのダイアログを閉じた: " + device.findObject(By.res("android:id/alertTitle"))?.text)
                it.click()
            }
            // 通知の許可の確認は、ログイン後に遅れて出ることがある (待つ時間を過ぎてから出て、画面を覆ったまま失敗した例がある)。
            // 出ていたら許可して閉じる
            if (device.findObject(ALLOW_BUTTON) != null) {
                val msg = device.findObject(By.res(Pattern.compile(".*:id/permission_message")))?.text.orEmpty()
                if ("notifications" in msg || "通知" in msg) {
                    Log.w(TAG, "遅れて出た通知の許可の確認を閉じる ($msg)")
                    clickAllowSettled()
                }
            }
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return null
            device.wait(Until.findObject(sel), minOf(left, 2_000L))?.let { return it }
        }
    }

    /**
     * 権限の確認の「許可」を押す。確認画面が出てすぐ (表示の動きの途中) に押すと、タップが下のアプリの画面に届き、
     * ホームのチャンネルの行を押してしまうことがあった (LAN の確認で、ログイン後にチャンネル画面が開いて失敗した)。
     * 落ち着くのを待ってから押し、閉じたことを確かめる。閉じられたら true
     */
    private fun clickAllowSettled(): Boolean {
        // #126: 押しても閉じない回の原因を確かめるため、logcat に始まりと終わりの印を書く (PERM-CLUE)。
        // e2e-run.sh が、押し直した回・閉じられなかった回だけ、印の間のシステムの記録 (権限の画面のプロセス・
        // ActivityTaskManager・WindowManager・InputDispatcher など) を CI のログに出す
        val id = "${testName.methodName}-${++permClueSeq}"
        val pcPids = listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
            .joinToString(" ") { shell("pidof $it").trim() }.trim()
        Log.i(TAG, "PERM-CLUE begin id=$id at=${clock()} pc_pid=$pcPids")
        var attempts = 0
        val ok = run {
            repeat(3) { i ->
                device.waitForIdle()
                SystemClock.sleep(800)
                val b = device.findObject(ALLOW_BUTTON) ?: return@run true
                attempts = i + 1
                // 押す前の状態。押すのは b.click() (visibleBounds の中心)
                val before = runCatching {
                    "bounds=${b.visibleBounds} enabled=${b.isEnabled} clickable=${b.isClickable} pkg=${b.applicationPackage} " +
                        "matches=${device.findObjects(ALLOW_BUTTON).size}"
                }.getOrElse { "error=${it.javaClass.simpleName}" }
                val tapAt = clock()
                b.click()
                val goneStart = SystemClock.uptimeMillis()
                if (device.wait(Until.gone(ALLOW_BUTTON), 5_000)) return@run true
                // 5 秒たっても残っている。同じ確認画面か (文言)・押せる状態か・何が前面か を残す
                val after = device.findObject(ALLOW_BUTTON)
                val afterDesc = runCatching { "bounds=${after?.visibleBounds} enabled=${after?.isEnabled}" }.getOrElse { "gone_now" }
                val msg = device.findObject(By.res(Pattern.compile(".*:id/permission_message")))?.text?.visible()
                Log.w(
                    TAG,
                    "E2E-RESULT permission_tap_stuck id=$id attempt=${i + 1} tap_at=$tapAt waited_ms=${SystemClock.uptimeMillis() - goneStart} " +
                        "before=[$before] after=[$afterDesc] message=$msg",
                )
                logWindows(id)
                dumpUi("権限の確認画面が押しても閉じない (id=$id attempt=${i + 1})")
            }
            device.findObject(ALLOW_BUTTON) == null
        }
        if (!ok) shot("permission_stuck")
        Log.i(TAG, "PERM-CLUE end id=$id at=${clock()} ok=$ok attempts=$attempts")
        return ok
    }

    /** 前面の窓と、見えている窓の一覧 (権限の確認画面の上に別の窓が重なっていないかを見るため。#126) */
    private fun logWindows(id: String) {
        runCatching {
            shell("dumpsys window").lines()
                .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mObscuringWindow" in it }
                .take(10).forEach { Log.w(TAG, "PERM-CLUE window id=$id ${it.trim()}") }
            shell("dumpsys window visible").lines()
                .filter { it.trimStart().startsWith("Window #") }
                .take(30).forEach { Log.w(TAG, "PERM-CLUE window id=$id visible ${it.trim()}") }
        }
    }

    /** 端末の時計 (logcat の行の時刻と突き合わせる) */
    private fun clock(): String = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    /** 一覧を送りながら探す (画面の外にある行・タブ用) */
    private fun findScrolling(sel: BySelector, what: String): UiObject2 {
        repeat(8) {
            device.wait(Until.findObject(sel), 2_000)?.let { return it }
            val scrollable = device.findObject(By.scrollable(true)) ?: return@repeat
            scrollable.scroll(Direction.DOWN, 0.8f)
        }
        return wait(sel, 2_000, what)
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        if (pollUntil(timeoutMs, cond)) return
        shot("timeout")
        dumpUi("「$what」が満たされない")
        fail("$what (${timeoutMs / 1000} 秒待った)")
    }

    /** [cond] が満たされるまで待つ。満たされたら true */
    private fun pollUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return true
            SystemClock.sleep(500)
        }
        return false
    }

    /**
     * 自分の送信がサーバーに届くのを待つ (10 秒)。届かなければ失敗にする (判定は [waitFor] と同じ)。
     * #94: 届かなかったとき、遅れただけか・失われたか・本文が違うものが届いたかを分けるため、さらに最大 60 秒待ち、
     * 行方を E2E-RESULT send_server として残してから失敗にする (遅れて届いても失敗のまま。隠さない)
     */
    private fun waitServerSend(sent: String) {
        val what = "サーバーに送信が届く"
        if (pollUntil(10_000) { admin.recentTexts(channelId).contains(sent) }) return
        shot("timeout")
        dumpUi("「$what」が満たされない")
        val t0 = SystemClock.uptimeMillis()
        val late = pollUntil(60_000) { admin.recentTexts(channelId).contains(sent) }
        val lateMs = SystemClock.uptimeMillis() - t0
        val texts = runCatching { admin.recentTexts(channelId) }.getOrNull()
        // 送った文字を含むが一致しないもの (押し損ねでキーボードの文字が足された、など)。確認用の文字なので記録してよい
        val similar = texts.orEmpty().filter { it != sent && it.contains(sent) }.map { it.visible() }
        val outcome = when {
            late -> "late"
            similar.isNotEmpty() -> "different_text"
            texts == null -> "unknown"
            else -> "missing"
        }
        Log.w(TAG, "E2E-RESULT send_server outcome=$outcome extra_wait_ms=$lateMs count=${texts?.size} similar=$similar")
        fail("$what (10 秒待った。その後の行方: $outcome)")
    }

    /**
     * 押す前に、キーボードが出て送信ボタンの位置が落ち着くのを待つ (#94、[clickAllowSettled] と同じ考え)。
     * 入力欄を押した直後はキーボードが出る途中で、前の位置を押すとキーボード (Gboard の右下のキー) に当たり、
     * 入力欄に文字が足されたことがあった (2026-11-21 の main の e2e。推測)。
     * キーボードが出ていて、ボタンの位置が 2 回続けて同じなら落ち着いたとみる。5 秒で落ち着かなくても押す (押し損ねは呼ぶ側が記録する)
     */
    private fun waitSendButtonSettled(what: String, send: BySelector = res("composer.send")): UiObject2 {
        wait(send, 5_000, what)
        val t0 = SystemClock.uptimeMillis()
        var last: android.graphics.Rect? = null
        var same = 0
        var ime = false
        while (SystemClock.uptimeMillis() - t0 < 5_000) {
            ime = imeShown()
            val b = device.findObject(send)?.visibleBounds
            same = if (ime && b != null && b == last) same + 1 else 0
            if (same >= 2) break
            last = b
            SystemClock.sleep(250)
        }
        Log.i(TAG, "E2E-RESULT send_tap_settle ms=${SystemClock.uptimeMillis() - t0} ime_shown=$ime settled=${same >= 2} bounds=$last")
        return wait(send, 5_000, what)
    }

    private fun imeShown(): Boolean = shell("dumpsys input_method").contains("mInputShown=true")

    /**
     * 送信ボタンを押し、入力欄が空になる (アプリが送信を受け付けた) ことを確かめる。
     * #94: 押しても入力欄に残ったままになることが、この PR の CI で 2 回あった (送信ボタンは押せる状態だった)。
     * 原因を切り分けるため、押す前後のボタンの位置・キーボードの状態を記録し、受け付けられなければ 1 回だけ押し直す。
     * 押し直したことは E2E-RESULT send_tap_retry として残し、e2e-run.sh が警告にする (見えなくしない)
     */
    private fun clickSendChecked(text: String) {
        val btn = waitSendButtonSettled("送信ボタン")
        val before = btn.visibleBounds
        btn.click()
        if (waitInputCleared(text, 3_000)) return
        val after = device.findObject(res("composer.send"))?.visibleBounds
        val ime = imeShown()
        // 押し損ねがキーボードに当たると、入力欄に文字が足される。改行などが見えるようにして残す (確認用の文字なので記録してよい)
        val input = device.findObject(res("composer.input"))?.text?.visible()
        Log.w(TAG, "E2E-RESULT send_tap_retry before=$before after=$after ime_shown=$ime input=$input")
        dumpUi("送信ボタンを押しても入力欄が空にならない")
        wait(res("composer.send"), 5_000, "送信ボタン (押し直し)").click()
        if (!waitInputCleared(text, 5_000)) fail("送信ボタンを 2 回押しても、送信を受け付けない (入力欄に残ったまま)")
    }

    private fun waitInputCleared(text: String, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (device.findObject(res("composer.input"))?.text?.contains(text) != true) return true
            SystemClock.sleep(200)
        }
        return false
    }

    /** 失敗したときの手がかり: 画面の要素 (testTag・文字・押せるか) を logcat に出す。e2e-run.sh が CI のログに出す */
    private fun dumpUi(reason: String) {
        runCatching {
            Log.i(TAG, "UI-DUMP $reason")
            device.findObjects(By.res(Pattern.compile(".+"))).take(60).forEach { o ->
                // 改行を含む文字は、そのままだと logcat の行が分かれて抜き出しから落ちる (#94)。見える形にする
                Log.i(TAG, "UI-DUMP  ${o.resourceName} text=${o.text?.take(60)?.visible()} enabled=${o.isEnabled}")
            }
        }
    }

    /** 改行・タブを見える形にする (ログの 1 行に収めるため) */
    private fun String.visible(): String = replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun assertAlive(whenDoing: String) {
        if (shell("pidof $pkg").isBlank()) fail("$whenDoing にアプリのプロセスが無い (落ちた可能性。logcat の crash バッファを見る)")
    }

    private fun shell(cmd: String): String = device.executeShellCommand(cmd).also { Log.d(TAG, "$ $cmd\n" + it.take(300)) }

    private fun shot(name: String) {
        runCatching { device.takeScreenshot(File(shots, "%02d-%s.png".format(step++, name))) }
    }

    companion object {
        private const val TAG = "GiracleE2E"
        /** PERM-CLUE の印の通し番号 (同じ実行の中のテストをまたいで重ならないように) */
        private var permClueSeq = 0
        /** アプリの SendTrace のタグ (#70) */
        private const val PERF_TAG = "GiraclePerf"
        private val ALLOW_BUTTON: BySelector = By.res(Pattern.compile(".*:id/permission_allow_button"))
        /** 読み込みに失敗したときの画面全体の表示 (ui/Root.kt の LoadError) */
        private const val LOAD_ERROR_TEXT = "サーバーから読み込めませんでした"
        /** 上書き更新の確認で変える設定 (設定のチャットのタブの最初の行) */
        private const val SETTING_TAB = "チャット"
        private const val SETTING_LABEL = "送る写真を縮小する"
        /**
         * 前の版から名前を変えた testTag・文字 (今の名前 → 前の版の名前)。上書き更新の確認の前半 (前の版を操作する) だけが使う。
         * 前の版 (e2e が上書きの元にする最新のリリース、D-080) が今の名前になったら消してよい
         */
        private val RENAMED_SINCE_PREV: Map<String, String> = emptyMap()
    }
}
