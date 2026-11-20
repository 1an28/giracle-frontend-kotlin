package com.giracle.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Prefs
import com.giracle.android.data.model.Channel
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 起動直後にチャンネルを開いたとき、そのチャンネルの情報を取ってくる間に届いたチャンネルの全件を、
 * 取ってきた 1 件だけの一覧で上書きしない。ホームの参加チャンネルが 1 つしか出なかった。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatStoreChannelInfoRaceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    private val requested = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @After fun tearDown() {
        release.countDown()
        scope.cancel()
        server.close()
    }

    private fun store(): ChatStore {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.contains("channel/get-info/b")) return MockResponse.Builder().code(404).build()
                requested.countDown()
                release.await(30, TimeUnit.SECONDS)
                val body = """{"message":"ok","data":{"id":"b","name":"B (詳しい情報)"}}"""
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(ctx, "test-channel-info-race")
        val ep = Endpoint.parse(server.url("/").toString())!!
        val http = buildOkHttp(prefs, null)
        val api = GiracleApi(http, ep)
        return ChatStore(api, WsClient(http, scope) { ep }, prefs, scope)
    }

    @Test fun channelListArrivingDuringFetchIsKept() = runBlocking {
        val store = store()
        // 起動直後で、チャンネルの一覧はまだ空
        val fetch = scope.async { store.fetchChannelInfo("b") }
        check(requested.await(10, TimeUnit.SECONDS)) { "チャンネルの情報の取得が始まらない" }
        // 取ってくる間に、起動時の読み込みで全件が届く
        store.channels.value = listOf(Channel("a", "A"), Channel("b", "B"), Channel("c", "C"))
        release.countDown()
        fetch.await()
        assertEquals(listOf("a", "b", "c"), store.channels.value.map { it.id })
        assertEquals("B (詳しい情報)", store.channels.value.first { it.id == "b" }.name)
    }

    @Test fun missingChannelIsAdded() = runBlocking {
        val store = store()
        store.channels.value = listOf(Channel("a", "A"))
        release.countDown()
        store.fetchChannelInfo("b")
        assertEquals(listOf("a", "b"), store.channels.value.map { it.id })
    }
}
