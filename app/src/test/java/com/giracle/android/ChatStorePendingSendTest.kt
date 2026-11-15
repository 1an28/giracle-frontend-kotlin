package com.giracle.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Prefs
import com.giracle.android.data.Snapshot
import com.giracle.android.data.model.Message
import com.giracle.android.data.model.User
import com.giracle.android.data.model.WsFrame
import com.giracle.android.data.net.AppJson
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.WsClient
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 送信を押した直後に、応答を待たずに仮のものを一覧に出す (D-071)。
 * 応答・WebSocket で本物が届いたら置き換わり (二重に見えない・消えない)、失敗したら消える。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatStorePendingSendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    private val sendRequested = CountDownLatch(1)
    private val sendRelease = CountDownLatch(1)
    @Volatile private var sendCode = 200
    @Volatile private var historyRequested = CountDownLatch(1)
    @Volatile private var historyRelease: CountDownLatch? = null
    /** 取り直しの結果の先頭 (新しい側) に足すもの。サーバーが送ったものを保存済みのときに使う */
    @Volatile private var historyHead: List<Message> = emptyList()

    @After fun tearDown() {
        sendRelease.countDown()
        historyRelease?.countDown()
        scope.cancel()
        server.close()
    }

    private val t1 = "2026-11-14T00:00:01.000Z"
    private val t2 = "2026-11-14T00:00:02.000Z"
    private fun msg(id: String, t: String, user: String = "u2", content: String = "m$id") =
        Message(id = id, channelId = "ch", userId = user, content = content, createdAt = t)
    private fun json(m: Message) = AppJson.encodeToString(Message.serializer(), m)
    private fun ok(data: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"message":"ok","data":$data}""").build()

    /** サーバーが返す、送ったもの (サーバーの時刻は端末より後) */
    private val real = msg("r1", "2026-11-14T00:00:09.000Z", user = "me", content = "こんにちは")

    private fun store(): ChatStore {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.contains("channel/get-history") -> {
                        historyRequested.countDown()
                        historyRelease?.await(30, TimeUnit.SECONDS)
                        val list = historyHead + listOf(msg("2", t2), msg("1", t1))
                        ok("""{"history":[${list.joinToString(",") { json(it) }}],"atTop":true,"atEnd":true}""")
                    }
                    path.contains("message/send") -> {
                        sendRequested.countDown()
                        sendRelease.await(30, TimeUnit.SECONDS)
                        if (sendCode != 200) MockResponse.Builder().code(sendCode).body("""{"message":"error"}""").build()
                        else ok(json(real))
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "test-pending-send")
        val ep = Endpoint.parse(server.url("/").toString())!!
        val http = buildOkHttp(prefs, null)
        val api = GiracleApi(http, ep)
        return ChatStore(api, WsClient(http, scope) { ep }, prefs, scope).also {
            it.restore(Snapshot(userId = "me", me = User(id = "me", name = "me")))
        }
    }

    private suspend fun opened(): ChatStore = store().also { it.openChannel("ch") }

    private fun ids(s: ChatStore) = s.history("ch").value.messages.map { it.id }

    private fun wsSend(m: Message) = WsFrame("message::SendMessage", AppJson.parseToJsonElement(json(m)) as JsonElement)

    @Test fun pendingIsShownBeforeResponseAndReplacedByReal() = runBlocking {
        val s = opened()
        val local = s.addPending("ch", "こんにちは", null)
        assertNotNull(local)
        val head = s.history("ch").value.messages.first()
        assertEquals(local, head.id)
        assertTrue(head.pending)
        assertEquals("me", head.userId)

        val send = scope.async { s.send("ch", "こんにちは", emptyList(), null, local) }
        check(sendRequested.await(10, TimeUnit.SECONDS))
        // 応答の前も、仮のものが先頭に出ている
        assertEquals(listOf(local, "2", "1"), ids(s))
        sendRelease.countDown()
        send.await()
        assertEquals(listOf("r1", "2", "1"), ids(s))
        assertFalse(s.history("ch").value.messages.any { it.pending })
        // 画面が送ったものへ動かす前に置き換わっても、本物を探せる
        assertEquals("r1", s.resolveSent(local!!))
    }

    @Test fun webSocketBeforeResponseReplacesPendingWithoutDuplicate() = runBlocking {
        val s = opened()
        val local = s.addPending("ch", "こんにちは", null)
        val send = scope.async { s.send("ch", "こんにちは", emptyList(), null, local) }
        check(sendRequested.await(10, TimeUnit.SECONDS))
        // 応答より先に WebSocket で本物が届く
        s.handle(wsSend(real))
        assertEquals(listOf("r1", "2", "1"), ids(s))
        assertEquals("r1", s.resolveSent(local!!))
        sendRelease.countDown()
        send.await()
        assertEquals(listOf("r1", "2", "1"), ids(s))
    }

    @Test fun othersMessageDuringSendGoesBelowPending() = runBlocking {
        val s = opened()
        val local = s.addPending("ch", "こんにちは", null)
        val send = scope.async { s.send("ch", "こんにちは", emptyList(), null, local) }
        check(sendRequested.await(10, TimeUnit.SECONDS))
        // 送信中に他の人の新着 (サーバーでは送ったものより前) が届く。仮のものは先頭 (いちばん下) のまま
        s.handle(wsSend(msg("3", "2026-11-14T00:00:05.000Z")))
        assertEquals(listOf(local, "3", "2", "1"), ids(s))
        sendRelease.countDown()
        send.await()
        assertEquals(listOf("r1", "3", "2", "1"), ids(s))
    }

    @Test fun failureRemovesPending() = runBlocking {
        val s = opened()
        sendCode = 500
        val local = s.addPending("ch", "こんにちは", null)
        sendRelease.countDown()
        val r = runCatching { s.send("ch", "こんにちは", emptyList(), null, local) }
        assertTrue(r.isFailure)
        assertEquals(listOf("2", "1"), ids(s))
    }

    @Test fun pendingSurvivesRefetchAndIsReplacedAfter() = runBlocking {
        val s = opened()
        val local = s.addPending("ch", "こんにちは", null)
        // 送信中に最新を取り直す (「最新へ移動」・再同期と同じ)。取り直しの結果には、送ったものはまだ無い
        historyRequested = CountDownLatch(1)
        historyRelease = CountDownLatch(1)
        val refetch = scope.async { s.jumpToLatest("ch") }
        check(historyRequested.await(10, TimeUnit.SECONDS))
        historyRelease!!.countDown()
        refetch.await()
        assertEquals(listOf(local, "2", "1"), ids(s))
        sendRelease.countDown()
        s.send("ch", "こんにちは", emptyList(), null, local)
        assertEquals(listOf("r1", "2", "1"), ids(s))
    }

    @Test fun responseDuringRefetchIsKeptOnce() = runBlocking {
        val s = opened()
        val local = s.addPending("ch", "こんにちは", null)
        historyRequested = CountDownLatch(1)
        historyRelease = CountDownLatch(1)
        val refetch = scope.async { s.jumpToLatest("ch") }
        check(historyRequested.await(10, TimeUnit.SECONDS))
        // 取り直しの最中に応答が届き、仮のものが本物に置き換わる。取り直しの結果を入れても消えない (D-065)
        sendRelease.countDown()
        s.send("ch", "こんにちは", emptyList(), null, local)
        historyRelease!!.countDown()
        refetch.await()
        assertEquals(listOf("r1", "2", "1"), ids(s))
    }

    @Test fun refetchContainingRealReplacesPending() = runBlocking {
        // つなぎ直しの取り直しの結果に、もう保存された本物が入っている (応答はまだ返っていない)。仮のものと二重に見えない (#118 のレビュー)
        val s = opened()
        val local = s.addPending("ch", "こんにちは", null)
        val send = scope.async { s.send("ch", "こんにちは", emptyList(), null, local) }
        check(sendRequested.await(10, TimeUnit.SECONDS))
        historyHead = listOf(real)
        s.jumpToLatest("ch")
        assertEquals(listOf("r1", "2", "1"), ids(s))
        assertEquals("r1", s.resolveSent(local!!))
        // 後から WebSocket で同じ本物が届いても、応答が返っても増えない
        s.handle(wsSend(real))
        assertEquals(listOf("r1", "2", "1"), ids(s))
        sendRelease.countDown()
        send.await()
        assertEquals(listOf("r1", "2", "1"), ids(s))
    }

    @Test fun oldSameTextInRefetchDoesNotRemovePending() = runBlocking {
        // 前の一覧にあった昔の同じ本文 (「ok」など) とは突き合わせない
        historyHead = listOf(msg("old", "2026-11-14T00:00:03.000Z", user = "me", content = "ok"))
        val s = opened()
        val local = s.addPending("ch", "ok", null)
        s.jumpToLatest("ch")
        assertEquals(listOf(local, "old", "2", "1"), ids(s))
    }

    @Test fun pendingIsNotSavedNorUsedAsReadTime() = runBlocking {
        val s = opened()
        s.markRead("ch")
        val before = s.readTimes.value["ch"]
        assertEquals(t2, before)
        s.addPending("ch", "こんにちは", null)
        s.markRead("ch")
        // 仮のものの時刻 (端末の時計) で既読にしない
        assertEquals(t2, s.readTimes.value["ch"])
        assertEquals(listOf("2", "1"), s.snapshot()!!.histories["ch"]!!.map { it.id })
    }

    @Test fun noPendingWhenHistoryIsCache() {
        // 前回の内容 (キャッシュ) を出しているときは仮のものを出さない (応答の後に今までどおり扱う)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "test-pending-cache")
        val ep = Endpoint.parse("http://127.0.0.1:1/")!!
        val http = buildOkHttp(prefs, null)
        val s = ChatStore(GiracleApi(http, ep), WsClient(http, scope) { ep }, prefs, scope)
        s.restore(Snapshot(userId = "me", me = User(id = "me", name = "me"), histories = mapOf("ch" to listOf(msg("1", t1)))))
        assertNull(s.addPending("ch", "x", null))
        assertNull(s.addPending("other", "x", null))
    }
}
