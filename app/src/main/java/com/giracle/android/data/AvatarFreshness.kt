package com.giracle.android.data

import com.giracle.android.data.net.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * ユーザーのアイコンが変わったら、表示を新しいものに替える (#148、D-082)。
 *
 * 画像の読み込み (Coil 3) は、ディスクに残した画像を期限なしで使い続ける (Coil 3 の既定。サーバーの Cache-Control を見ない)。
 * サーバーはアイコンを変えても知らせを出さず (`/user/change-icon` はシグナルを送らない)、URL も変わらない。
 * そのため、他の人がアイコンを変えても、このアプリでは古いまま残っていた。
 *
 * ここでは、表示されたアイコンを [ttlMillis] に 1 回まで (プロセスを起こした直後は必ず 1 回) 裏でサーバーに取りに行き、
 * 手元の画像と中身が違えば手元の画像を捨てて [versionOf] を進める。[versionOf] を見ている表示は読み込み直す。
 * 圏外などで取りに行けなかったときは何もしない (古いアイコンを出したままにする。頭文字に戻さない)。
 */
class AvatarFreshness(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val cache: IconCache,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 手元に残っている画像の読み書き (本番は Coil のキャッシュ)。テストで差し替える */
    interface IconCache {
        /** ディスクに残っている画像の中身。無ければ null */
        fun diskBytes(url: String): ByteArray?
        /** メモリに読み込み済みの画像があるか */
        fun inMemory(url: String): Boolean
        /** ディスクとメモリから捨てる */
        fun evict(url: String)
    }

    /** URL → 最後にサーバーへ確かめに行った時刻 */
    private val checkedAt = ConcurrentHashMap<String, Long>()
    private val versions = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** 一度に取りに行く数を抑える (チャンネルを開いた直後にアイコンが並んでも回線を埋めない) */
    private val gate = Semaphore(4)

    /** 表示中の画像を読み込み直すための番号。アイコンが替わるたびに増える */
    val versionsFlow: StateFlow<Map<String, Int>> get() = versions

    fun versionOf(url: String): Int = versions.value[url] ?: 0

    /**
     * アイコンを表示するときに呼ぶ。期限内に確かめていれば何もしない (呼ぶだけなら軽い)。
     * [force] は自分でアイコンを変えた直後など、期限に関係なく確かめるとき。
     */
    fun check(url: String, force: Boolean = false) {
        val t = now()
        if (!force) {
            val last = checkedAt[url]
            if (last != null && t - last < ttlMillis) return
        }
        checkedAt[url] = t
        scope.launch { gate.withPermit { refresh(url) } }
    }

    private suspend fun refresh(url: String) {
        // OkHttp の HTTP キャッシュにも 1 日分 (immutable) 残るので、必ずサーバーに確かめる
        val req = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
        val fresh = runCatching {
            http.newCall(req).await().use { res ->
                if (!res.isSuccessful) return@use null
                withContext(Dispatchers.IO) {
                    val body = res.body
                    if (body.contentLength() > MAX_BYTES) null else body.bytes().takeIf { it.size <= MAX_BYTES }
                }
            }
        }.getOrNull() ?: run {
            // 取りに行けなかったら次の表示でまた試す
            checkedAt.remove(url)
            return
        }
        val changed = withContext(Dispatchers.IO) {
            val old = cache.diskBytes(url)
            when {
                old != null -> !old.contentEquals(fresh)
                // ディスクから消えていてメモリだけに残っている。比べられないので読み込み直す (HTTP キャッシュは今更新した)
                else -> cache.inMemory(url)
            }
        }
        if (changed) {
            withContext(Dispatchers.IO) { cache.evict(url) }
            versions.update { it + (url to ((it[url] ?: 0) + 1)) }
        }
    }

    private companion object {
        /** アイコンはサーバーで 125px に縮められる。これより大きいものは比べずに諦める */
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
