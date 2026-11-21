package com.giracle.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.Prefs
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.UploadFile
import com.giracle.android.data.net.buildOkHttp
import com.giracle.android.ui.Root
import com.giracle.android.ui.common.TestTags
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ForcedSize

/**
 * 実サーバーにつないだ実際の画面を描画し、PNG に保存する (目視確認用)。
 * GIRACLE_TEST_SERVER を指定したときだけ動く。管理者 (GIRACLE_TEST_ADMIN_USER / GIRACLE_TEST_ADMIN_PASS、
 * 既定 admin / password123) が居ればよく、デモデータ (seed-demo.sh) には頼らない。
 * テストごとに専用のチャンネルを作り、終わったら消す。要素は文言ではなく testTag (ui/common/TestTags.kt) で探す。
 * 出力先: app/build/screenshots/
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class UiScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val server = System.getenv("GIRACLE_TEST_SERVER")
    private val adminUser = System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin"
    private val adminPass = System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123"
    private val out = File("build/screenshots").apply { mkdirs() }
    private val createdChannels = mutableListOf<String>()

    /** 画面の外 (テストの準備・後片付け) からサーバーを操作する、アプリとは別の管理者セッション */
    private val fixture: GiracleApi by lazy {
        val prefs = Prefs(ApplicationProvider.getApplicationContext(), "ui-fixture").apply { clearSession() }
        GiracleApi(buildOkHttp(prefs, null), Endpoint.parse(server!!)!!).also { api ->
            runBlocking { api.signIn(adminUser, adminPass) }
        }
    }

    @After fun tearDown() {
        if (createdChannels.isEmpty()) return
        runBlocking {
            // 未読の通知が残っているとチャンネル削除がサーバー側で失敗するため先に消す (docs/SERVER_CHANGES.md)
            runCatching { fixture.inboxClear() }
            createdChannels.forEach { runCatching { fixture.deleteChannel(it) } }
        }
    }

    private fun newChannel(purpose: String): String = runBlocking {
        val name = "ui-$purpose-${System.currentTimeMillis().toString().takeLast(6)}"
        val id = fixture.createChannel(name, "画面確認テスト用 (自動で消す)").channelId
        createdChannels += id
        runCatching { fixture.joinChannel(id) } // 作成者が自動で参加する版もあるので、失敗は無視する
        id
    }

    private fun send(channelId: String, text: String): String = runBlocking { fixture.send(channelId, text, emptyList(), null).id }

    /** 160x90 のグラデーション画像を添付して送る */
    private fun sendImage(channelId: String, text: String): String {
        val bmp = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        for (x in 0 until 160) for (y in 0 until 90) bmp.setPixel(x, y, Color.rgb(255 * x / 160, 255 * y / 90, 200))
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return runBlocking {
            val fileId = fixture.uploadFile(channelId, UploadFile("grad.png", "image/png", png.toRequestBody("image/png".toMediaType())))
            fixture.send(channelId, text, listOf(fileId), null).id
        }
    }

    private fun startApp(size: androidx.compose.runtime.State<androidx.compose.ui.unit.DpSize>? = null) {
        val graph = ApplicationProvider.getApplicationContext<GiracleApp>().graph
        graph.setServer(server!!, false)
        graph.prefs.lastChannelId = null // 前回のチャンネルを自動で開かないようにする
        graph.setForeground(true) // Robolectric では前面への切り替えが届かないことがあるので、明示する
        runBlocking { graph.signIn(adminUser, adminPass) }
        rule.setContent {
            if (size == null) Root(graph, mutableStateOf(null))
            else androidx.compose.ui.test.DeviceConfigurationOverride(androidx.compose.ui.test.DeviceConfigurationOverride.ForcedSize(size.value)) {
                Root(graph, mutableStateOf(null))
            }
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 押せる範囲が 48dp に足りない要素を、CI のログに出す (D-045 の P-4)。CI の live-test は TOUCH-TARGET が 1 件でもあれば失敗にする (#68)。
        // Issue にした既知の不足は TOUCH-TARGET-KNOWN として出し、失敗にはしない
        rule.smallTouchTargets(rule.density).forEach {
            if (it.startsWith(KNOWN_PREFIX)) println("TOUCH-TARGET-KNOWN $name: $it") else println("TOUCH-TARGET $name: $it")
        }
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(m: SemanticsMatcher, ms: Long = 10_000) = rule.waitUntil(ms) { exists(m) }
    private fun waitTag(tag: String, ms: Long = 10_000) = waitFor(hasTestTag(tag), ms)
    private fun waitGone(tag: String, ms: Long = 10_000) = rule.waitUntil(ms) { !exists(hasTestTag(tag)) }
    private fun click(tag: String) { waitTag(tag); rule.onNodeWithTag(tag).performClick() }

    private fun back(expectTag: String) {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag(expectTag)
    }

    /** 履歴の一覧に (どれかのメッセージとして) 指定の文言が出ているか */
    private fun messageWithText(text: String) =
        hasText(text, substring = true) and hasAnyAncestor(hasTestTag(TestTags.CHANNEL_MESSAGES)) and
            SemanticsMatcher("メッセージの行") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("channel.message:") == true }

    @Test fun walkThrough() {
        assumeTrue(server != null)
        val ch = newChannel("walk")
        val greeting = send(ch, "おはようございます！今日もよろしくお願いします")
        send(ch, "おはよう〜 `./gradlew assembleRelease` が通りました")
        send(ch, "資料はこちら https://example.com")
        sendImage(ch, "新しいアイコン案です")
        // メッセージリンクの埋め込み (本文と同じ文言が画面に 2 回出る。文言で探すと取り違える例)
        val latest = send(ch, "さっきの話はこちら &<$ch:$greeting>")
        runBlocking { fixture.react(greeting, ch, "thumbsup"); fixture.react(greeting, ch, "heart") }

        startApp()
        waitTag(TestTags.homeChannel(ch)); shot("01_home")

        click(TestTags.homeChannel(ch))
        waitTag(TestTags.message(latest)); shot("02_channel")

        // 入力欄から送ると、履歴に出る (WebSocket 経由で反映)
        val typed = "画面確認テストからの送信"
        click(TestTags.COMPOSER_INPUT)
        rule.onNodeWithTag(TestTags.COMPOSER_INPUT).performTextInput(typed)
        click(TestTags.COMPOSER_SEND)
        waitFor(messageWithText(typed)); shot("02b_sent")

        // 長押しで操作シート
        rule.onNodeWithTag(TestTags.CHANNEL_MESSAGES).performScrollToNode(hasTestTag(TestTags.message(greeting)))
        waitTag(TestTags.reaction(greeting, "heart"))
        rule.onNodeWithTag(TestTags.message(greeting)).performSemanticsAction(SemanticsActions.OnLongClick)
        waitTag(TestTags.MESSAGE_ACTIONS); shot("03_message_actions")

        // 絵文字ピッカーで 🥳 (hooray) を選ぶとリアクションが付く (WebSocket 経由で反映)
        click(TestTags.MESSAGE_ACTIONS_EMOJI_PICKER)
        waitTag(TestTags.EMOJI_PICKER); shot("04_emoji_picker")
        rule.onNodeWithTag(TestTags.EMOJI_PICKER_SEARCH).performTextInput("hooray")
        click(TestTags.emojiPickerItem("hooray"))
        waitGone(TestTags.EMOJI_PICKER)
        waitTag(TestTags.reaction(greeting, "hooray")); shot("04b_reacted")

        back(TestTags.SCREEN_HOME); shot("04c_after_back")
        click(TestTags.HOME_INBOX); waitTag(TestTags.SCREEN_INBOX); shot("05_inbox")
        back(TestTags.SCREEN_HOME)
        click(TestTags.HOME_MANAGE_SERVER); waitTag(TestTags.SCREEN_MANAGE_SERVER); shot("06_manage")
        back(TestTags.SCREEN_HOME)
        click(TestTags.HOME_SETTINGS); waitTag(TestTags.SCREEN_SETTINGS); shot("07_settings")
        back(TestTags.SCREEN_HOME)
    }

    /** 使っている途中でリアルタイム接続が切れたら、チャンネルの題名の下に出し、つながったら消す (D-073) */
    @Test fun connectionLostInChannel() {
        assumeTrue(server != null)
        val ch = newChannel("conn")
        val latest = send(ch, "接続の知らせの確認")
        startApp()
        click(TestTags.homeChannel(ch))
        waitTag(TestTags.message(latest))
        val graph = ApplicationProvider.getApplicationContext<GiracleApp>().graph
        rule.waitUntil(10_000) { graph.ws.state.value == com.giracle.android.data.net.WsClient.State.Connected }
        assertTrue(!exists(hasTestTag(TestTags.CONNECTION_NOTICE)))
        // 切る (stop は自分からはつなぎ直さない)。少し続いたら知らせが出る
        rule.runOnUiThread { graph.ws.stop() }
        // 3 秒たつまでは出さない (ちらつかせない)
        advance(2_500)
        assertTrue(!exists(hasTestTag(TestTags.CONNECTION_NOTICE)))
        awaitAdvancing { exists(hasText("オフライン", substring = true)) }; shot("09_connection_lost")
        rule.runOnUiThread { graph.ws.start() }
        awaitAdvancing { exists(hasText("つながりました")) }; shot("09b_reconnected")
        awaitAdvancing { !exists(hasTestTag(TestTags.CONNECTION_NOTICE)) }
    }

    /**
     * 画面の時計を進めながら待つ。画面側の待ち (delay) は画面の時計で進むので、`waitUntil` だけでは進まない
     * (ComposerDraftTest の await と同じ)
     */
    private fun awaitAdvancing(ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "${ms}ms 待っても条件が満たされない" }
            advance(100)
            Thread.sleep(20)
        }
    }

    private fun advance(ms: Long) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    @Test fun imageViewer() {
        assumeTrue(server != null)
        val ch = newChannel("image")
        sendImage(ch, "画像の全画面表示")

        startApp()
        click(TestTags.homeChannel(ch))
        val img = SemanticsMatcher("画像") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("channel.image") == true }
        waitFor(img)
        rule.onAllNodes(img)[0].performClick()
        waitTag(TestTags.IMAGE_VIEWER); shot("10_image_viewer")
        // 右上の「閉じる」で閉じる
        rule.onNodeWithContentDescription("閉じる").performClick()
        waitGone(TestTags.IMAGE_VIEWER)
    }

    /** メッセージを右へすべらせると返信になる (D-043) */
    @Test fun swipeToReply() {
        assumeTrue(server != null)
        val ch = newChannel("swipe")
        val target = send(ch, "すべらせて返信するメッセージ")

        startApp()
        click(TestTags.homeChannel(ch))
        waitTag(TestTags.message(target))
        // 指を離さずに右へ 120dp 動かし、離す
        rule.onNodeWithTag(TestTags.message(target)).performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(20f, 0f))
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(36f, 0f)) }
            up()
        }
        waitTag(TestTags.COMPOSER_REPLY); shot("11_swipe_reply")
    }

    /** ホームの並べ替え: つまみを下へ引くと 1 つ下と入れ替わる (D-044) */
    @Test fun dragToReorder() {
        assumeTrue(server != null)
        // 名前で並ぶとき先頭に来るよう、数字で始める
        val a = newChannel("0drag-a")
        val b = newChannel("0drag-b")
        startApp()
        waitTag(TestTags.homeChannel(a))
        click(TestTags.HOME_REORDER)
        // つまみは行 (押せる要素) の中にまとめられるので、まとめる前の木で探す
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(TestTags.homeChannelDrag(a)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val h = rule.onNodeWithTag(TestTags.homeChannel(a)).fetchSemanticsNode().size.height.toFloat()
        // つまみの反応する範囲は 48dp (H-4)。見た目のアイコン (内側の 24dp) の外、左上の角から 4dp の所を押して引く
        val handle = rule.onNodeWithTag(TestTags.homeChannelDrag(a), useUnmergedTree = true)
        val size = handle.fetchSemanticsNode().size
        val dp = rule.density.density
        assertTrue("つまみが 48dp 四方ある: $size", size.width >= 48 * dp - 1 && size.height >= 48 * dp - 1)
        handle.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(4 * dp, 4 * dp))
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, h / 8f)) }
            up()
        }
        rule.waitForIdle(); shot("12_reorder_drag")
        val order = ApplicationProvider.getApplicationContext<GiracleApp>().graph.prefs.channelOrder
        assertTrue("b が a より上に来る: $order", order.indexOf(b) in 0 until order.indexOf(a))
    }

    @Test fun longText() {
        assumeTrue(server != null)
        val ch = newChannel("long")
        val long = send(ch, (1..11).joinToString("\n") { "長文テスト $it 行目" })

        startApp()
        click(TestTags.homeChannel(ch))
        waitTag(TestTags.expandLongText(long)); shot("08_long_text")
        click(TestTags.expandLongText(long))
        waitTag(TestTags.LONG_TEXT_DIALOG); shot("09_long_text_open")
    }

    /** 幅の広い画面では、ホームとチャンネルを左右に並べる (#29) */
    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi") fun tabletTwoPane() {
        assumeTrue(server != null)
        val ch = newChannel("tablet")
        val msg = send(ch, "タブレットの 2 ペイン表示の確認")

        startApp()
        waitTag(TestTags.TWO_PANE_EMPTY)
        waitTag(TestTags.homeChannel(ch)); shot("10_tablet_home")
        click(TestTags.homeChannel(ch))
        waitTag(TestTags.message(msg))
        // チャンネルを開いても、左のホームは出たまま
        assertTrue(exists(hasTestTag(TestTags.SCREEN_HOME)))
        shot("11_tablet_channel")
        back(TestTags.TWO_PANE_EMPTY)
    }

    /**
     * 1 ペインと 2 ペインが切り替わっても (回転・折りたたみの開閉)、チャンネルの表示位置が保たれる (#39、D-032)。
     * 画面の幅だけを変えて、同じ画面のまま切り替える。
     */
    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi") fun twoPaneKeepsScrollPosition() {
        assumeTrue(server != null)
        val ch = newChannel("pane")
        // 3 行にして 1 件の高さを増やす。1 行だと、読み込んだ 30 件のほとんどが開いた直後の画面に収まり、
        // 下の old が描かれてしまう (#111 の live で確かめた)
        val ids = (1..40).map { send(ch, "表示位置の確認 $it\n2 行目\n3 行目") }
        // 古いほう。開いた直後 (最新が見える位置) には描かれないが、最初に読み込む 30 件 (ids[10]〜) には入っている。
        // 30 件より古いもの (以前は ids[4]) を使うと、古い側の続きの読み込みが間に合わずに端に着き、
        // performScrollToNode が見つけられずに落ちることがあった (#88)。確かめたいのは幅を変えても表示位置が残ることなので、続きの読み込みは要らない
        val old = ids[12]
        val size = mutableStateOf(androidx.compose.ui.unit.DpSize(1024.dp, 768.dp))

        startApp(size)
        click(TestTags.homeChannel(ch))
        waitTag(TestTags.message(ids.last()))
        assertTrue("開いた直後に古いメッセージが描かれている (確認の前提が崩れた)", !exists(hasTestTag(TestTags.message(old))))
        rule.onNodeWithTag(TestTags.CHANNEL_MESSAGES).performScrollToNode(hasTestTag(TestTags.message(old)))
        waitTag(TestTags.message(old)); shot("12_pane_wide_scrolled")

        // 狭くする (1 ペイン): チャンネルだけが出て、表示位置はそのまま
        // 高さは画面 (768dp) に収める。はみ出すと DeviceConfigurationOverride.ForcedSize が密度を下げて縮めるため、
        // 押せる範囲の検査で 40dp のボタンが 38dp に見えてしまう (#68)
        size.value = androidx.compose.ui.unit.DpSize(400.dp, 760.dp)
        rule.waitForIdle()
        assertTrue("1 ペインに切り替えるとホームが出たまま", !exists(hasTestTag(TestTags.SCREEN_HOME)))
        assertTrue("1 ペインに切り替えると表示位置が戻った", exists(hasTestTag(TestTags.message(old))))
        shot("13_pane_narrow_kept")

        // 広げ直す (2 ペイン): ホームが並び、表示位置はそのまま
        size.value = androidx.compose.ui.unit.DpSize(1024.dp, 768.dp)
        rule.waitForIdle()
        waitTag(TestTags.SCREEN_HOME)
        assertTrue("2 ペインに戻すと表示位置が戻った", exists(hasTestTag(TestTags.message(old))))
        shot("14_pane_wide_kept")
    }

    @Test fun login() {
        assumeTrue(server != null)
        val app = ApplicationProvider.getApplicationContext<GiracleApp>()
        app.graph.prefs.serverUrl = server
        rule.setContent { Root(app.graph, mutableStateOf(null)) }
        waitTag(TestTags.LOGIN_SUBMIT); shot("00_login")
    }
}
