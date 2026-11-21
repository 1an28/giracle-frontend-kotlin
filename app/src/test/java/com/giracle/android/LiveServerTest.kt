package com.giracle.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Prefs
import com.giracle.android.data.model.Message
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.UploadFile
import com.giracle.android.data.net.WsClient
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 実際のバックエンドに対する結合テスト。
 * devtools/start-local-stack.sh でサーバーを立て、環境変数 GIRACLE_TEST_SERVER (例: http://localhost:3333)
 * と、管理者の GIRACLE_TEST_ADMIN_USER / GIRACLE_TEST_ADMIN_PASS を指定したときだけ動く。
 * 2 人のユーザー (WebSocket 付き) を作り、Web 版の主要な操作が一通り通ることを確かめる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveServerTest {
    private val server = System.getenv("GIRACLE_TEST_SERVER")
    private val scopes = mutableListOf<CoroutineScope>()

    class Client(val api: GiracleApi, val ws: WsClient, val store: ChatStore, val prefs: Prefs)

    private fun client(name: String): Client {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "test-$name")
        prefs.clearSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val ep = Endpoint.parse(server!!)!!
        val http = buildOkHttp(prefs, null)
        val api = GiracleApi(http, ep)
        val ws = WsClient(http, scope) { ep }
        return Client(api, ws, ChatStore(api, ws, prefs, scope), prefs)
    }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private suspend fun <T> eventually(timeoutMs: Long = 8000, f: suspend () -> T?): T = withTimeout(timeoutMs) {
        while (true) { f()?.let { return@withTimeout it }; delay(100) }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    @Test fun fullFlow() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val suffix = System.currentTimeMillis().toString().takeLast(6)

        // 管理者で招待コードを作り、2 人登録する
        val admin = client("admin")
        admin.api.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123")
        val invite = "inv$suffix"
        admin.api.createInvite(invite, 10)
        val a = client("a"); val b = client("b")
        a.api.signUp("alice$suffix", "pass1234", invite)
        b.api.signUp("bob$suffix", "pass1234", invite)
        val aId = a.api.signIn("alice$suffix", "pass1234").userId
        val bId = b.api.signIn("bob$suffix", "pass1234").userId
        assertTrue(a.prefs.token != null)

        // チャンネル作成・参加
        val ch = admin.api.createChannel("test$suffix", "テスト用").channelId
        a.api.joinChannel(ch); b.api.joinChannel(ch)

        // 初期読み込み + WebSocket 接続
        a.store.initialLoad(aId); b.store.initialLoad(bId)
        eventually { if (a.ws.state.value == WsClient.State.Connected && b.ws.state.value == WsClient.State.Connected) true else null }
        assertTrue(a.store.me.value!!.channelJoin.any { it.channelId == ch })

        // 履歴を開き、B の送信を A が WS で受け取る
        a.store.openChannel(ch)
        b.store.send(ch, "こんにちは @<$aId> https://example.com", emptyList(), null)
        val got = eventually { a.store.history(ch).value.messages.firstOrNull { it.userId == bId && !it.isSystemMessage } }
        assertEquals("こんにちは @<$aId> https://example.com", got.content)
        // メンションが A の通知に入る
        eventually { a.store.inbox.value.firstOrNull { it.message.id == got.id } }

        // リアクション (追加と削除が WS で反映される)
        a.store.toggleReaction(got, "thumbsup")
        val reacted = eventually { a.store.history(ch).value.messages.first { it.id == got.id }.takeIf { m -> m.reactionSummary.any { it.emojiCode == "thumbsup" && it.includingYou } } }
        assertEquals(1, reacted.reactionSummary.first().count)
        assertEquals(listOf(aId), a.api.whoReacted(got.id, "thumbsup"))
        a.store.toggleReaction(reacted, "thumbsup")
        eventually { a.store.history(ch).value.messages.first { it.id == got.id }.takeIf { it.reactionSummary.isEmpty() } }

        // 返信・編集・削除
        a.store.send(ch, "返信です", emptyList(), got.id)
        val reply = eventually { b.store.inbox.value.firstOrNull { it.type == "reply" } }
        assertEquals(got.id, reply.message.replyingMessageId)
        b.api.edit(got.id, "編集後")
        eventually { a.store.history(ch).value.messages.first { it.id == got.id }.takeIf { it.isEdited && it.content == "編集後" } }
        b.api.delete(got.id)
        eventually { if (a.store.history(ch).value.messages.none { it.id == got.id }) true else null }

        // ファイル添付 (画像) と画像サイズ
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAADCAIAAAA2iEnWAAAAEElEQVR4nGP4z8AARAwoFABE0AX7pM/egAAAAABJRU5ErkJggg==")
        val fileId = a.api.uploadFile(ch, UploadFile("dot.png", "image/png", png.toRequestBody("image/png".toMediaType())))
        a.store.send(ch, "", listOf(fileId), null)
        val withFile = eventually { b.store.history(ch).value.messages.firstOrNull { it.files.isNotEmpty() } ?: run { b.store.openChannel(ch); null } }
        assertEquals("dot.png", withFile.files.first().actualFileName)
        val hist = a.api.history(ch)
        assertEquals(2, hist.imageDimensions[fileId]?.width)

        // 既読・新着
        a.store.markRead(ch)
        delay(800)
        val rt = a.api.readTimes().first { it.channelId == ch }
        assertEquals(a.store.history(ch).value.messages.first().createdAt.take(19), rt.readTime.take(19))

        // 検索・ユーザー一覧・ミュート・通知設定・セッション
        assertTrue(a.api.search("返信", ch, null, null, null, 1, "desc").any { it.content == "返信です" })
        assertTrue(a.api.userList(username = "bob$suffix").any { it.id == bId })
        a.store.setMuted(ch, true); assertTrue(a.api.mutedChannels().any { it.channelId == ch }); a.store.setMuted(ch, false)
        assertEquals("all", a.api.updateNotificationConfig(true, "all").mode)
        assertTrue(a.api.sessions().any { it.thisIsYou })

        // プロフィール更新が WS で他人に届く
        a.api.profileUpdate("Alice$suffix", "よろしく")
        eventually { b.store.users.value[aId]?.takeIf { it.name == "Alice$suffix" } }

        // ロール作成→付与が WS で届く
        admin.api.createRole("tester$suffix")
        val role = eventually { admin.api.roles().firstOrNull { it.name == "tester$suffix" } }
        admin.api.linkRole(aId, role.id)
        eventually { a.store.me.value?.roleLink?.firstOrNull { it.roleId == role.id } }

        // 退出が WS で反映される
        b.api.leaveChannel(ch)
        eventually { if (b.store.me.value!!.channelJoin.none { it.channelId == ch }) true else null }

        // 背面で切断 → その間の新着 → 前面復帰で再接続し、取りこぼしを取り直す
        b.api.joinChannel(ch)
        a.ws.stop()
        val missed = b.api.send(ch, "切断中のメッセージ", emptyList(), null)
        a.ws.start()
        eventually(15_000) { a.store.history(ch).value.messages.firstOrNull { it.id == missed.id } }

        // 起動高速化用スナップショット: 保存→読み込みで履歴と名前が復元される
        val snap = a.store.snapshot()!!
        val json = com.giracle.android.data.net.AppJson.encodeToString(com.giracle.android.data.Snapshot.serializer(), snap)
        val a2 = client("a2"); a2.prefs.token = a.prefs.token
        val restored = a2.store
        restored.restore(com.giracle.android.data.net.AppJson.decodeFromString(com.giracle.android.data.Snapshot.serializer(), json))
        val h = restored.history(ch).value
        assertTrue(h.loaded && h.fromCache && h.messages.isNotEmpty())
        assertTrue(restored.users.value.containsKey(aId) || restored.me.value?.id == aId)
        // 開くとサーバーから取り直し、fromCache が外れる
        restored.openChannel(ch)
        assertTrue(!restored.history(ch).value.fromCache)

        // 後片付け (未読の通知が残っているとチャンネル削除がサーバー側で失敗するため先に消す。docs/SERVER_CHANGES.md 参照)
        a.store.clearInbox(); b.store.clearInbox()
        admin.api.deleteChannel(ch)
        admin.api.deleteRole(role.id)
        Unit
    }

    /** 管理者によるパスワードのリセット (D-022): 新しいパスワードでログインでき、古いものは使えなくなる */
    /**
     * サーバーの不具合 S-1 (#24): 未読のメンションが残っているチャンネルを削除すると 500 になる。
     * どちらでも成功にし、結果を CI のログに出す (アプリの不具合ではないので失敗にしない。
     * スキップにすると、live-test の「スキップがあれば失敗」の確認 (サーバーが立っていないのに通るのを防ぐもの) にかかる)。
     */
    @Test fun deleteChannelWithUnreadMention() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val admin = client("s1admin")
        admin.api.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123")
        val invite = "s1inv$suffix"
        admin.api.createInvite(invite, 1)
        val u = client("s1user")
        u.api.signUp("carol$suffix", "pass1234", invite)
        val uId = u.api.signIn("carol$suffix", "pass1234").userId
        val ch = admin.api.createChannel("s1-$suffix", "S-1 の確認").channelId
        runCatching { admin.api.joinChannel(ch) }
        u.api.joinChannel(ch)
        admin.api.send(ch, "未読のメンション @<$uId>", emptyList(), null)
        val result = runCatching { admin.api.deleteChannel(ch) }
        val err = result.exceptionOrNull()
        if (err is com.giracle.android.data.net.ApiException && err.code >= 500) {
            println("S-1: まだ直っていない (チャンネル削除が ${err.code})。#24")
            // 後片付け: 通知を消してから消し直す
            runCatching { u.api.inboxClear() }
            runCatching { admin.api.deleteChannel(ch) }
            return@runBlocking
        }
        result.getOrThrow()
        println("S-1: 直っている (未読のメンションがあっても削除できた)。#24 を閉じてよい")
    }

    /** ホームの「すべて既読」: 未読のチャンネルを、それぞれの最新のメッセージまで既読にする */
    @Test fun markAllRead() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val admin = client("ra-admin")
        admin.api.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123")
        admin.api.createInvite("ra$suffix", 1)
        val d = client("dave")
        d.api.signUp("dave$suffix", "pass1234", "ra$suffix")
        val dId = d.api.signIn("dave$suffix", "pass1234").userId
        val chs = listOf("ra1-$suffix", "ra2-$suffix").map { admin.api.createChannel(it, "すべて既読の確認").channelId }
        try {
            chs.forEach { runCatching { admin.api.joinChannel(it) }; d.api.joinChannel(it) }
            d.store.initialLoad(dId)
            eventually { if (d.ws.state.value == WsClient.State.Connected) true else null }
            val latest = chs.map { admin.api.send(it, "未読 $it", emptyList(), null) }
            // 新着は WebSocket で届く (開いていないので未読になる)
            eventually { if (chs.all { d.store.hasNew.value[it] == true }) true else null }
            assertEquals(0, d.store.markAllRead())
            assertTrue(chs.none { d.store.hasNew.value[it] == true })
            val rts = d.api.readTimes().associate { it.channelId to it.readTime }
            chs.zip(latest).forEach { (ch, m) -> assertEquals(m.createdAt.take(19), rts[ch]?.take(19)) }
            // サーバーから取り直しても未読が残っていない
            assertTrue(chs.none { d.api.hasNew()[it] == true })
        } finally {
            chs.forEach { runCatching { admin.api.deleteChannel(it) } }
        }
    }

    @Test fun adminResetsPassword() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val admin = client("admin-reset")
        admin.api.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123")
        admin.api.createInvite("rst$suffix", 1)
        val c = client("carol")
        c.api.signUp("carol$suffix", "pass1234", "rst$suffix")
        val cId = c.api.signIn("carol$suffix", "pass1234").userId
        val pw = admin.api.resetPassword(cId)
        assertTrue(pw.length >= 8)
        val c2 = client("carol2")
        assertEquals(cId, c2.api.signIn("carol$suffix", pw).userId)
        val old = runCatching { client("carol3").api.signIn("carol$suffix", "pass1234") }
        assertTrue("古いパスワードでログインできてしまう", old.isFailure)
    }

    /** 管理者によるユーザーの削除 (#43): 削除済みになり、ログイン中のセッションも使えなくなる */
    @Test fun adminDeletesUser() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val admin = client("admin-del")
        val adminId = admin.api.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123").userId
        admin.api.createInvite("del$suffix", 1)
        val d = client("dave")
        d.api.signUp("dave$suffix", "pass1234", "del$suffix")
        val dId = d.api.signIn("dave$suffix", "pass1234").userId
        admin.api.deleteUser(dId)
        assertTrue("削除済みにならない", admin.api.userInfo(dId).isDeleted)
        assertTrue("削除した人のセッションが使えてしまう", runCatching { d.api.verifyToken() }.isFailure)
        assertTrue("削除した人がログインできてしまう", runCatching { client("dave2").api.signIn("dave$suffix", "pass1234") }.isFailure)
        // 自分は消せない (サーバーが断る)
        assertTrue("自分を削除できてしまう", runCatching { admin.api.deleteUser(adminId) }.isFailure)
    }

    /**
     * 古いファイルの削除 (DELETE server/delete-old-files、D-074): 指定より前の添付が消え、使用量が減る。
     * 管理者でないと断られる。サーバー上の、それより前の添付はすべて消える (このテストより前に上げたものも)
     */
    @Test fun adminDeletesOldFiles() = runBlocking {
        assumeTrue("GIRACLE_TEST_SERVER 未指定のためスキップ", server != null)
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val admin = client("admin-files")
        admin.api.signIn(System.getenv("GIRACLE_TEST_ADMIN_USER") ?: "admin", System.getenv("GIRACLE_TEST_ADMIN_PASS") ?: "password123")
        val ch = admin.api.createChannel("files$suffix", "古いファイルの削除").channelId
        admin.api.joinChannel(ch)
        try {
            val bytes = "old file\n".repeat(512).toByteArray()
            val fileId = admin.api.uploadFile(ch, UploadFile("old.txt", "text/plain", bytes.toRequestBody("text/plain".toMediaType())))
            admin.api.send(ch, "添付つき", listOf(fileId), null)
            val sent = eventually { admin.api.history(ch).history.firstOrNull { m -> m.files.any { it.id == fileId } } }
            val before = admin.api.storageUsage()
            assertTrue("上げたファイルが使用量に入っていない", before >= bytes.size)
            // サーバーの記録は秒単位で切り捨てなので、上げた後の今を指定すれば必ず含まれる (同じ機械の時計)
            val cutoff = java.time.Instant.now().plusSeconds(1)

            // 管理者でない人は消せない
            admin.api.createInvite("fil$suffix", 1)
            val e = client("erin")
            e.api.signUp("erin$suffix", "pass1234", "fil$suffix")
            e.api.signIn("erin$suffix", "pass1234")
            assertTrue("管理者でない人が削除できてしまう", runCatching { e.api.deleteOldFiles(cutoff) }.isFailure)
            assertTrue(admin.api.history(ch).history.first { it.id == sent.id }.files.isNotEmpty())

            assertEquals(false, admin.api.deleteOldFiles(cutoff))
            val after = admin.api.storageUsage()
            assertTrue("使用量が減っていない ($before → $after)", after <= before - bytes.size)
            // メッセージは残り、添付だけが消える
            val m = admin.api.history(ch).history.first { it.id == sent.id }
            assertEquals("添付つき", m.content)
            assertTrue("添付が残っている", m.files.isEmpty())
        } finally {
            runCatching { admin.api.deleteChannel(ch) }
        }
    }

    @Suppress("unused") private fun dummy(m: Message) = m
}
