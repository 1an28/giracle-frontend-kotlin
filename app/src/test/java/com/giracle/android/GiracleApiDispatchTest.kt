package com.giracle.android

import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.TokenStore
import com.giracle.android.data.net.UploadFile
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * GiracleApi の呼び出しが、呼んだ側のディスパッチャ (アプリではメインスレッド) に戻るのが終わりの 1 回だけであること (D-078)。
 * 以前は通信と解析を別々の withContext で囲んでいて、間で呼んだ側に戻っていた。呼んだ側が塞がっている間は解析が始まらなかった (D-068 の記録)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GiracleApiDispatchTest {
    private val server = MockWebServer()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "caller") }
    private lateinit var api: GiracleApi

    /** 呼んだ側の代わり。何回この上で続きが動いたか (dispatch されたか) を数える */
    private class CountingDispatcher(private val base: CoroutineDispatcher) : CoroutineDispatcher() {
        val count = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            count.incrementAndGet()
            base.dispatch(context, block)
        }
    }

    private val caller = CountingDispatcher(executor.asCoroutineDispatcher())

    private val sendArrived = CountDownLatch(1)
    private val releaseSend = CountDownLatch(1)
    @Volatile private var holdSend = false

    private val message = """{"id":"m1","channelId":"ch","userId":"u1","content":"hi","createdAt":"2026-11-21T00:00:00.000Z"}"""

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path.endsWith("message/send") && holdSend) {
                    sendArrived.countDown()
                    releaseSend.await(30, TimeUnit.SECONDS)
                }
                if (path.endsWith("message/missing")) return MockResponse.Builder().code(404).body("nf").build()
                val body = when {
                    path.endsWith("message/send") -> """{"message":"ok","data":$message}"""
                    path.endsWith("message/m1") -> """{"message":"ok","data":$message}"""
                    path.endsWith("message/file/upload") -> """{"message":"ok","data":{"fileId":{"id":"f1"}}}"""
                    else -> """{"message":"ok","data":null}"""
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        val store = object : TokenStore { override var token: String? = null }
        api = GiracleApi(buildOkHttp(store, null), Endpoint.parse(server.url("/").toString())!!)
    }

    @After fun tearDown() {
        releaseSend.countDown()
        server.close()
        executor.shutdownNow()
    }

    /** [block] を呼んだ側の上で動かし、始めた後に呼んだ側へ戻った回数を返す */
    private fun <T> resumesOnCaller(block: suspend () -> T): Pair<T, Int> = runBlocking {
        withContext(caller) {
            caller.count.set(0)
            val result = block()
            result to caller.count.get()
        }
    }

    @Test fun sendResumesCallerOnce() {
        val (msg, resumes) = resumesOnCaller { api.send("ch", "hi", emptyList(), null) }
        assertEquals("m1", msg.id)
        assertEquals(1, resumes)
    }

    @Test fun callResumesCallerOnce() {
        val (msg, resumes) = resumesOnCaller { api.message("m1") }
        assertEquals("m1", msg.id)
        assertEquals(1, resumes)
    }

    @Test fun callUnitResumesCallerOnce() {
        val (_, resumes) = resumesOnCaller { api.delete("m1") }
        assertEquals(1, resumes)
    }

    @Test fun uploadFileResumesCallerOnce() {
        val file = UploadFile("a.txt", "text/plain", "abc".toRequestBody())
        val (id, resumes) = resumesOnCaller { api.uploadFile("ch", file) }
        assertEquals("f1", id)
        assertEquals(1, resumes)
    }

    /** 2xx 以外は、これまでどおり ApiException として呼んだ側に届く */
    @Test fun httpErrorStillThrowsApiException() = runBlocking {
        try {
            withContext(caller) { api.message("missing") }
            fail("ApiException が投げられない")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
        }
    }

    /** 応答を待っている間に取り消すと、応答を待たずに取り消しで終わる */
    @Test fun cancelWhileWaitingForResponse() = runBlocking {
        holdSend = true
        val job = async(caller) { api.send("ch", "hi", emptyList(), null) }
        check(sendArrived.await(10, TimeUnit.SECONDS)) { "送信が届かない" }
        job.cancel()
        try {
            job.await()
            fail("取り消されない")
        } catch (e: CancellationException) {
            assertTrue(job.isCancelled)
        }
    }
}
