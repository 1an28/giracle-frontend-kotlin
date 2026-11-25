package com.giracle.android

import com.giracle.android.data.AvatarFreshness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 他の人がアイコンを変えたら表示を替える (#148、D-082)。
 * サーバーと同じく `cache-control: public, max-age=86400, immutable` を付けて返し、
 * OkHttp の HTTP キャッシュが残っていても新しいアイコンに替わることを確かめる。
 */
class AvatarFreshnessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clock = 0L

    /** Coil のキャッシュの代わり */
    private class FakeCache : AvatarFreshness.IconCache {
        val disk = mutableMapOf<String, ByteArray>()
        val memory = mutableSetOf<String>()
        var evicted = 0
        override fun diskBytes(url: String) = disk[url]
        override fun inMemory(url: String) = url in memory
        override fun evict(url: String) { disk.remove(url); memory.remove(url); evicted++ }
    }

    @Before fun setUp() = server.start()
    @After fun tearDown() { scope.cancel(); server.close() }

    private fun icon(bytes: ByteArray) = MockResponse.Builder()
        .addHeader("cache-control", "public, max-age=86400, immutable")
        .addHeader("content-type", "image/webp")
        .body(Buffer().write(bytes)).build()

    private fun client() = OkHttpClient.Builder().cache(Cache(tmp.newFolder("http"), 1L shl 20)).build()

    private fun freshness(http: OkHttpClient, cache: FakeCache) =
        AvatarFreshness(http, scope, cache, ttlMillis = 1000, now = { clock })

    private fun awaitVersion(f: AvatarFreshness, url: String, v: Int) = runBlocking {
        withTimeout(5000) { f.versionsFlow.first { (it[url] ?: 0) >= v } }
    }

    private fun awaitRequests(n: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (server.requestCount < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(n, server.requestCount)
    }

    @Test fun changedIconIsEvictedAndVersionBumped() {
        val http = client()
        val url = server.url("/api/user/icon/u1").toString()
        val cache = FakeCache().apply { disk[url] = byteArrayOf(1, 1, 1); memory += url }
        // OkHttp の HTTP キャッシュに古いアイコンを入れておく (immutable で 1 日有効)
        server.enqueue(icon(byteArrayOf(1, 1, 1)))
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body.bytes() }

        server.enqueue(icon(byteArrayOf(2, 2, 2)))
        val f = freshness(http, cache)
        f.check(url)
        awaitVersion(f, url, 1)
        assertEquals(1, cache.evicted)
        assertTrue(url !in cache.disk && url !in cache.memory)

        // 読み込み直すと、HTTP キャッシュからも新しいアイコンが返る (サーバーには行かない)
        val again = http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body.bytes() }
        assertEquals(listOf<Byte>(2, 2, 2), again.toList())
        assertEquals(2, server.requestCount)
    }

    @Test fun sameIconKeepsCacheAndChecksOnlyOncePerTtl() {
        val url = server.url("/api/user/icon/u2").toString()
        val cache = FakeCache().apply { disk[url] = byteArrayOf(5); memory += url }
        server.enqueue(icon(byteArrayOf(5)))
        server.enqueue(icon(byteArrayOf(5)))
        val f = freshness(client(), cache)

        f.check(url)
        awaitRequests(1)
        // 期限内は何度表示しても取りに行かない
        clock = 999
        repeat(10) { f.check(url) }
        Thread.sleep(100)
        assertEquals(1, server.requestCount)
        // 期限を過ぎたらもう 1 回だけ確かめる
        clock = 1000
        f.check(url)
        awaitRequests(2)
        Thread.sleep(100)
        assertEquals(0, cache.evicted)
        assertEquals(0, f.versionOf(url))
    }

    @Test fun failureKeepsOldIconAndRetriesOnNextShow() {
        val url = server.url("/api/user/icon/u3").toString()
        val cache = FakeCache().apply { disk[url] = byteArrayOf(7) }
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(icon(byteArrayOf(8)))
        val f = freshness(client(), cache)

        f.check(url)
        awaitRequests(1)
        Thread.sleep(100)
        // 失敗しても古いアイコンは捨てない
        assertEquals(0, cache.evicted)
        // 期限内でも、失敗していれば次の表示で確かめ直す
        f.check(url)
        awaitVersion(f, url, 1)
        assertEquals(2, server.requestCount)
    }

    @Test fun forceChecksEvenWithinTtl() {
        val url = server.url("/api/user/icon/me").toString()
        val cache = FakeCache().apply { disk[url] = byteArrayOf(1) }
        server.enqueue(icon(byteArrayOf(1)))
        server.enqueue(icon(byteArrayOf(9)))
        val f = freshness(client(), cache)
        f.check(url)
        awaitRequests(1)
        Thread.sleep(50)
        f.check(url, force = true)
        awaitVersion(f, url, 1)
    }
}
