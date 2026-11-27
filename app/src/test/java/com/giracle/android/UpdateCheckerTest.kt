package com.giracle.android

import com.giracle.android.data.UpdateChecker
import com.giracle.android.data.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** 新しい版の確認 (#147、D-086)。問い合わせ先は MockWebServer、時計は手で進める */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class UpdateCheckerTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = 1_000_000_000L
    private var enabled = true

    private class MemStore : UpdateChecker.Store {
        var json: String? = null
        override fun read() = json
        override fun write(json: String) { this.json = json }
    }
    private val store = MemStore()

    @Before fun setUp() = server.start()
    @After fun tearDown() { scope.cancel(); server.close() }

    private fun checker(current: String = "0.9.3", url: String = server.url("/repos/x/y/releases/latest").toString()) =
        UpdateChecker(url, current, store, scope, enabledSetting = { enabled }, now = { clock })

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) = MockResponse.Builder()
        .addHeader("content-type", "application/json")
        .body("""{"tag_name":"$tag","html_url":"https://github.com/x/y/releases/tag/$tag","prerelease":$prerelease,"draft":$draft,"name":"n","assets":[]}""")
        .build()

    private fun fail(code: Int) = MockResponse.Builder().code(code).body("{\"message\":\"rate limit\"}").build()

    private fun UpdateChecker.notice() = noticeFor(state.value, enabled)

    @Test fun findsNewerVersion() = runBlocking {
        server.enqueue(release("v0.9.4"))
        val c = checker()
        assertTrue(c.checkIfDue())
        val n = c.notice()!!
        assertEquals("0.9.4", n.version)
        assertEquals("https://github.com/x/y/releases/tag/v0.9.4", n.htmlUrl)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("application/vnd.github+json", req.headers["Accept"])
        assertEquals("/repos/x/y/releases/latest", req.url.encodedPath)
    }

    @Test fun sameOrOlderIsNotShown() = runBlocking {
        server.enqueue(release("v0.9.3"))
        val c = checker()
        c.checkIfDue()
        assertNull(c.notice())
        clock += UpdateChecker.OK_INTERVAL
        server.enqueue(release("0.8.10"))
        assertTrue(c.checkIfDue())
        assertNull(c.notice())
        assertTrue(c.state.value.lastOk)
    }

    @Test fun comparesNumerically() {
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.3"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.99"))
        assertFalse(UpdateChecker.isNewer("0.9.3", "0.9.3"))
        assertFalse(UpdateChecker.isNewer("0.9.2", "0.9.10"))
        // 今の版が読めない (debug の -debug 付きなど) なら出さない
        assertFalse(UpdateChecker.isNewer("1.0.0", "0.9.3-debug"))
    }

    @Test fun notShownAfterUpdating() = runBlocking {
        server.enqueue(release("v0.9.4"))
        checker(current = "0.9.3").checkIfDue()
        // 新しい版に入れ替えた後は、保存した「見つけた版」があっても出さない (表示のたびに今の版と比べる)
        val after = checker(current = "0.9.4")
        after.checkIfDue() // 24 時間たっていないので問い合わせない。保存したものを読むだけ
        assertEquals("0.9.4", after.state.value.latestVersion)
        assertNull(after.notice())
        assertNull(after.newVersion(after.state.value))
        assertEquals(1, server.requestCount)
    }

    @Test fun checksOncePer24Hours() = runBlocking {
        server.enqueue(release("v0.9.4"))
        val c = checker()
        assertTrue(c.checkIfDue())
        clock += UpdateChecker.OK_INTERVAL - 1
        assertFalse(c.checkIfDue())
        assertEquals(1, server.requestCount)
        clock += 1
        server.enqueue(release("v0.9.5"))
        assertTrue(c.checkIfDue())
        assertEquals("0.9.5", c.notice()!!.version)
        assertEquals(2, server.requestCount)
    }

    @Test fun failureRetriesAfter6HoursSilently() = runBlocking {
        server.enqueue(fail(403))
        val c = checker()
        assertTrue(c.checkIfDue())
        assertFalse(c.state.value.lastOk)
        assertNull(c.notice())
        clock += UpdateChecker.FAIL_INTERVAL - 1
        assertFalse(c.checkIfDue())
        clock += 1
        server.enqueue(fail(429))
        assertTrue(c.checkIfDue())
        clock += UpdateChecker.FAIL_INTERVAL
        server.enqueue(MockResponse.Builder().body("<html>not json</html>").build())
        assertTrue(c.checkIfDue())
        assertFalse(c.state.value.lastOk)
        clock += UpdateChecker.FAIL_INTERVAL
        server.enqueue(release("v0.9.4"))
        assertTrue(c.checkIfDue())
        assertTrue(c.state.value.lastOk)
        assertEquals("0.9.4", c.notice()!!.version)
    }

    @Test fun failureKeepsPreviouslyFoundVersion() = runBlocking {
        server.enqueue(release("v0.9.4"))
        val c = checker()
        c.checkIfDue()
        clock += UpdateChecker.OK_INTERVAL
        server.enqueue(fail(500))
        c.checkIfDue()
        assertEquals("0.9.4", c.notice()!!.version)
    }

    @Test fun clockMovedBackwardChecksAgain() = runBlocking {
        server.enqueue(release("v0.9.3"))
        val c = checker()
        c.checkIfDue()
        clock -= 60_000 // 時計を巻き戻した
        server.enqueue(release("v0.9.4"))
        assertTrue(c.checkIfDue())
        assertEquals("0.9.4", c.notice()!!.version)
    }

    @Test fun dismissedVersionIsNotShownAgain() = runBlocking {
        server.enqueue(release("v0.9.4"))
        val c = checker()
        c.checkIfDue()
        c.dismiss("0.9.4")
        waitUntil { c.state.value.dismissedVersion == "0.9.4" }
        assertNull(c.notice())
        // 設定の画面では出す (閉じたのはホームの知らせだけ)
        assertEquals("0.9.4", c.newVersion(c.state.value)!!.version)
        // 保存先から読み直しても出さない
        assertNull(checker().let { runBlocking { it.checkIfDue() }; it.notice() })
        // さらに新しい版が出たら、また出す
        clock += UpdateChecker.OK_INTERVAL
        server.enqueue(release("v0.9.5"))
        c.checkIfDue()
        assertEquals("0.9.5", c.notice()!!.version)
    }

    @Test fun ignoresUnreadableTagsAndPrereleases() = runBlocking {
        val c = checker()
        for (r in listOf(release("v1.0.0-rc1"), release("nightly"), release("v1.0.0", prerelease = true), release("v1.0.0", draft = true))) {
            server.enqueue(r)
            assertTrue(c.checkIfDue())
            assertNull(c.notice())
            assertTrue(c.state.value.lastOk)
            clock += UpdateChecker.OK_INTERVAL
        }
    }

    @Test fun nonHttpsPageIsNotOpened() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"tag_name":"v0.9.4","html_url":"javascript:alert(1)"}""").build())
        val c = checker()
        c.checkIfDue()
        assertEquals("0.9.4", c.notice()!!.version)
        assertNull(c.notice()!!.htmlUrl)
    }

    @Test fun disabledBySettingOrEmptyUrl() = runBlocking {
        enabled = false
        val c = checker()
        assertFalse(c.checkIfDue())
        enabled = true
        val off = checker(url = "")
        assertFalse(off.available)
        assertFalse(off.checkIfDue())
        assertEquals(0, server.requestCount)
        // 設定で切ったら、見つけてあった版も知らせない
        server.enqueue(release("v0.9.4"))
        c.checkIfDue()
        assertEquals("0.9.4", c.noticeFor(c.state.value, enabled = true)!!.version)
        assertNull(c.noticeFor(c.state.value, enabled = false))
    }

    @Test fun checkNowIgnoresInterval() = runBlocking {
        server.enqueue(release("v0.9.3"))
        val c = checker()
        c.checkIfDue()
        server.enqueue(release("v0.9.4"))
        c.checkNow()
        assertEquals("0.9.4", c.notice()!!.version)
        assertFalse(c.checking.value)
    }

    /** Giracle のトークンと Cookie を GitHub に送らない (Giracle サーバー用の OkHttpClient を使わない) */
    @Test fun doesNotSendGiracleCredentials() = runBlocking {
        // Giracle サーバー用の OkHttpClient にトークンを入れても、確認の問い合わせには付かない
        val prefs = com.giracle.android.data.Prefs(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(), "update-test",
        )
        prefs.token = "secret-giracle-token"
        server.enqueue(release("v0.9.4"))
        checker().checkIfDue()
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["Cookie"])
        assertFalse(req.headers.toString().contains("secret-giracle-token"))
        prefs.clearSession()
    }

    @Test fun stateSurvivesRestart() = runBlocking {
        server.enqueue(release("v0.9.4"))
        checker().checkIfDue()
        val restored = checker()
        assertFalse(restored.checkIfDue()) // 保存した時刻を読むので、24 時間以内は問い合わせない
        assertEquals(1, server.requestCount)
        assertEquals("0.9.4", restored.notice()!!.version)
        // 壊れた保存は捨てて確かめ直す
        store.json = "{broken"
        server.enqueue(release("v0.9.4"))
        assertTrue(checker().checkIfDue())
    }

    @Test fun isDueRules() {
        assertTrue(UpdateChecker.isDue(UpdateState(), 5))
        assertFalse(UpdateChecker.isDue(UpdateState(checkedAt = 100, lastOk = true), 100 + UpdateChecker.OK_INTERVAL - 1))
        assertTrue(UpdateChecker.isDue(UpdateState(checkedAt = 100, lastOk = false), 100 + UpdateChecker.FAIL_INTERVAL))
        assertTrue(UpdateChecker.isDue(UpdateState(checkedAt = 100, lastOk = true), 99))
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("時間内に条件を満たさない")
            Thread.sleep(10)
        }
    }
}
