package com.giracle.android.data

import com.giracle.android.data.net.AppJson
import com.giracle.android.data.net.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 新しい版の確認で覚えておくこと (#147、D-086)。
 * 見つけた版は「今の版より新しいか」を表示のたびに比べ直す (更新した後に知らせが残らないように)。
 */
@Serializable
data class UpdateState(
    /** 最後に確かめて分かった最新の版 ("0.9.4" の形。先頭の v は除く)。まだ分からなければ null */
    val latestVersion: String? = null,
    /** その版のリリースのページ (https のものだけ) */
    val htmlUrl: String? = null,
    /** 最後に確かめた時刻 (成功・失敗どちらも)。0 は「まだ確かめていない」 */
    val checkedAt: Long = 0,
    /** 最後の確認が成功したか */
    val lastOk: Boolean = false,
    /** ホームの知らせを閉じた版。この版では二度と出さない */
    val dismissedVersion: String? = null,
)

/** 知らせる新しい版 */
data class NewVersion(val version: String, val htmlUrl: String?)

/**
 * GitHub Releases の最新の版を確かめる (#147、D-086)。
 *
 * - 前面に来たとき ([onForeground])、前回の成功から [OK_INTERVAL] (24 時間)、失敗なら [FAIL_INTERVAL] (6 時間) たっていれば 1 回だけ問い合わせる。
 *   前回の時刻が今より未来 (時計を巻き戻した) なら確かめ直す
 * - 失敗 (圏外・403・429・読めない) は黙る
 * - Giracle サーバー用の OkHttpClient (トークン・Cookie・キャッシュ) は使わない。GitHub に Giracle の認証情報を送らないため
 * - [url] が空なら何もしない (debug・ベンチマーク用のビルド。build.gradle.kts の giracle.updateCheckUrl)
 */
class UpdateChecker(
    private val url: String,
    private val currentVersion: String,
    private val store: Store,
    private val scope: CoroutineScope,
    /** 利用者の設定「新しい版を確かめる」 */
    private val enabledSetting: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    clientFactory: () -> OkHttpClient = ::defaultClient,
) {
    /** 状態の保存先。読み書きはメインスレッド以外から呼ぶ */
    interface Store {
        fun read(): String?
        fun write(json: String)
    }

    private val client by lazy(clientFactory)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state
    @Volatile private var loaded = false

    private val _checking = MutableStateFlow(false)
    /** 「今すぐ確かめる」の最中か */
    val checking: StateFlow<Boolean> = _checking

    /** ビルドで有効になっているか (問い合わせ先が空なら、設定の切り替えも出さない) */
    val available: Boolean get() = url.isNotBlank()

    init {
        if (available) scope.launch(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }
    }

    /** アプリが前面に来たときに呼ぶ (メインスレッドから呼んでよい。中の処理は裏で行う) */
    fun onForeground() {
        if (!available || !enabledSetting()) return
        scope.launch(Dispatchers.IO) { checkIfDue() }
    }

    /** 間隔が空いていれば確かめる。確かめたら true */
    suspend fun checkIfDue(): Boolean = mutex.withLock {
        if (!available || !enabledSetting()) return false
        ensureLoaded()
        if (!isDue(_state.value, now())) return false
        fetchLocked()
        true
    }

    /** 設定の「今すぐ確かめる」。間隔を見ずに確かめる (設定の切り替えが切れていても) */
    suspend fun checkNow() {
        if (!available) return
        _checking.value = true
        try {
            mutex.withLock {
                ensureLoaded()
                fetchLocked()
            }
        } finally {
            _checking.value = false
        }
    }

    /** ホームの知らせを閉じる。その版では二度と出さない */
    fun dismiss(version: String) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                save(_state.value.copy(dismissedVersion = version))
            }
        }
    }

    /** 今の版より新しい版があれば返す (閉じたかどうかは見ない。設定の画面用) */
    fun newVersion(s: UpdateState): NewVersion? = newerThanCurrent(s, currentVersion)

    /** ホームに知らせを出す版 (閉じた版・設定で切った場合は出さない) */
    fun noticeFor(s: UpdateState, enabled: Boolean): NewVersion? =
        if (!available || !enabled) null else newVersion(s)?.takeIf { it.version != s.dismissedVersion }

    private fun ensureLoaded() {
        if (loaded) return
        val text = runCatching { store.read() }.getOrNull()
        val s = text?.let { runCatching { AppJson.decodeFromString(UpdateState.serializer(), it) }.getOrNull() }
        if (s != null) _state.value = s
        loaded = true
    }

    private fun save(s: UpdateState) {
        _state.value = s
        runCatching { store.write(AppJson.encodeToString(UpdateState.serializer(), s)) }
            .onFailure { android.util.Log.w(TAG, "save failed", it) }
    }

    private suspend fun fetchLocked() {
        val t = now()
        val release = runCatching { fetch() }
            .onFailure { android.util.Log.i(TAG, "check failed: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()
        val prev = _state.value
        save(
            if (release == null) prev.copy(checkedAt = t, lastOk = false)
            else {
                val v = release.usableVersion()
                android.util.Log.i(TAG, "checked: latest=${release.tagName} usable=$v")
                // 読めない版 (接尾辞付き・プレリリース・下書き) は無視し、前に分かった版を残す
                if (v == null) prev.copy(checkedAt = t, lastOk = true)
                else prev.copy(latestVersion = v, htmlUrl = release.htmlUrl?.takeIf { it.startsWith("https://") }, checkedAt = t, lastOk = true)
            },
        )
    }

    private suspend fun fetch(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Giracle-Native")
            .build()
        client.newCall(req).await().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            AppJson.decodeFromString(Release.serializer(), res.body.string())
        }
    }

    @Serializable
    internal data class Release(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
    ) {
        fun usableVersion(): String? = if (prerelease || draft) null else tagName?.let { parseVersion(it) }?.joinToString(".")
    }

    companion object {
        private const val TAG = "GiracleUpdate"
        const val OK_INTERVAL = 24L * 60 * 60 * 1000
        const val FAIL_INTERVAL = 6L * 60 * 60 * 1000

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /** 確かめる時期か。まだ確かめていない・間隔が過ぎた・前回の時刻が未来 (時計の巻き戻し) なら true */
        fun isDue(s: UpdateState, now: Long): Boolean {
            if (s.checkedAt <= 0L || now < s.checkedAt) return true
            return now - s.checkedAt >= if (s.lastOk) OK_INTERVAL else FAIL_INTERVAL
        }

        /** "v1.2.3" / "1.2.3" を数の並びにする。それ以外 (接尾辞付きなど) は null */
        fun parseVersion(s: String): List<Int>? {
            val m = Regex("""^v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})$""").matchEntire(s.trim()) ?: return null
            return m.groupValues.drop(1).map { it.toInt() }
        }

        /** a が b より新しいか。どちらかが読めなければ false */
        fun isNewer(a: String, b: String): Boolean {
            val x = parseVersion(a) ?: return false
            val y = parseVersion(b) ?: return false
            for (i in 0 until 3) if (x[i] != y[i]) return x[i] > y[i]
            return false
        }

        fun newerThanCurrent(s: UpdateState, current: String): NewVersion? {
            val v = s.latestVersion ?: return null
            return if (isNewer(v, current)) NewVersion(v, s.htmlUrl) else null
        }
    }
}
