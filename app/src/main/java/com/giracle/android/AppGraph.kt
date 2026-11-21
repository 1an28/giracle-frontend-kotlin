package com.giracle.android

import android.content.Context
import com.giracle.android.data.ChatStore
import com.giracle.android.data.Prefs
import com.giracle.android.data.model.ServerConfig
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.Endpoint
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.WsClient
import com.giracle.android.data.net.buildOkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.File

/** 画面の大分類 */
sealed interface AppPhase {
    /** 保存済みセッションを確認中 */
    data object Starting : AppPhase
    /** サーバー URL とログインが必要 */
    data object SignedOut : AppPhase
    /** ログイン済み。初期データを読み込み中か完了 */
    data class SignedIn(val userId: String, val ready: Boolean, val error: String? = null) : AppPhase
}

/**
 * 依存関係の入れ物。DI ライブラリは使わず手で組み立てる (起動時間を短くするため)。
 * HTTP・WebSocket・画像読み込みで同じ OkHttpClient を共有する。
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val prefs = Prefs(appContext)
    /**
     * アプリ全体の裏の処理。想定していない例外が 1 つの処理から漏れても、アプリごと落とさずに記録だけする
     * (Android では、処理器の無い launch の例外はアプリを落とす)。e2e は logcat に [UNCAUGHT_TAG] が出たら失敗にする
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e(UNCAUGHT_TAG, "uncaught in app scope", e) },
    )

    val http: OkHttpClient = buildOkHttp(prefs, File(appContext.cacheDir, "http"))

    @Volatile var endpoint: Endpoint = Endpoint.parse(prefs.serverUrl ?: DEFAULT_SERVER, prefs.directBackend)
        ?: Endpoint.parse(DEFAULT_SERVER)!!
        private set

    val api = GiracleApi(http, endpoint)
    val ws = WsClient(http, scope) { endpoint }

    /** プッシュ通知の登録が一時的に失敗したときのやり直し (#93、D-077) */
    val pushRetry = com.giracle.android.notify.PushRegistrationRetry(scope) { vapid ->
        withContext(Dispatchers.IO) { org.unifiedpush.android.connector.UnifiedPush.register(appContext, vapid = vapid) }
    }

    /**
     * アプリの画面が前面にあるか (GiracleApp が切り替える)。プッシュで裏から起きたプロセスでは false のまま。
     * 以前は ChatStore の既定値 (true) のままになり、プッシュ通知を「画面内で知らせる」扱いで捨てていた (D-019 の e2e で発見)。
     */
    @Volatile var inForeground = false
        private set

    fun setForeground(fg: Boolean) {
        inForeground = fg
        store.appInForeground = fg
    }

    /** 電波が無くて送れなかったメッセージ (#30、D-029) */
    val outbox = com.giracle.android.data.Outbox(File(appContext.filesDir, "outbox.json"))

    private val outboxSender = object : com.giracle.android.data.Outbox.Sender {
        override suspend fun send(item: com.giracle.android.data.OutboxItem): String =
            store.send(item.channelId, item.text, item.fileIds, item.replyTo).id
        override suspend fun recent(channelId: String) = api.history(channelId, fetchLength = 30).history
        override val myUserId: String? get() = store.me.value?.id ?: prefs.userId
    }

    /** 送信待ちを送る。[only] を指定したらそれだけ (利用者が「今すぐ送る」を選んだとき) */
    fun flushOutbox(only: String? = null) {
        if (_phase.value !is AppPhase.SignedIn || outbox.items.value.isEmpty()) return
        // 送り直しのきっかけを記録する (e2e の失敗の手がかり、#101)
        android.util.Log.i("Outbox", "flush requested: items=${outbox.items.value.size} only=${only != null}")
        scope.launch { outbox.flush(outboxSender, only) }
    }

    private fun newStore() =
        ChatStore(api, ws, prefs, scope) { inForeground || prefs.settings.value.keepConnection }.also { it.appInForeground = inForeground }

    private val _store = MutableStateFlow(newStore())
    /** ログアウトのたびに作り直す。画面側はこれを購読する */
    val storeFlow: StateFlow<ChatStore> = _store
    val store: ChatStore get() = _store.value

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
    val phase: StateFlow<AppPhase> = _phase

    private val _loading = MutableStateFlow(false)
    /** サーバーからの読み込み ([loadSession]) の最中か。失敗の表示で「やり直している」ことを出すため */
    val loading: StateFlow<Boolean> = _loading
    /** 動いている読み込み。同時に 2 つ走らせない (回線の復帰・WebSocket の接続・前面への切り替えが重なっても 1 回) */
    private var loadJob: kotlinx.coroutines.Job? = null
    /**
     * 読み込みの最中に、やり直しのきっかけ (回線の復帰・WebSocket の接続など) が来た印。その読み込みが失敗したら、もう 1 回やり直す。
     * 捨てると、回線が戻った直後の読み込みが失敗し、その間に WebSocket がつながり終えていた場合に、次のきっかけが来なくなる
     */
    private val retryAfterLoad = java.util.concurrent.atomic.AtomicBoolean(false)

    private var snapshotJob: kotlinx.coroutines.Job? = null
    // init の中で起こす処理から使うので、init より前に置く
    private val snapshotFile = com.giracle.android.data.SafeFile(java.io.File(appContext.filesDir, "snapshot.json"))
    /** 書くのを待っているスナップショット。null は「書くものが無い」(書き終えた後・ログアウト後) */
    private val snapshotToSave = MutableStateFlow<com.giracle.android.data.Snapshot?>(null)
    @Volatile private var sessionLoadStarted = false

    init {
        val uid = prefs.userId
        if (prefs.hasToken && uid != null && prefs.serverUrl != null) {
            // 保存済みセッションで即座にメイン画面へ (確認は裏で行う)
            _phase.value = AppPhase.SignedIn(uid, ready = false)
            // 前回の内容を先に出す。サーバーの最新への置き換えは、画面を開いたときか常時接続のときに始める
            // (プッシュで裏から起きたときに、通信と接続を始めないため。ensureSessionLoaded)
            snapshotJob = scope.launch { readSnapshot(uid)?.let { store.restore(it) } }
        } else {
            _phase.value = AppPhase.SignedOut
        }
        scope.launch {
            // ログイン状態の移り変わりを記録する (e2e の失敗の手がかり)
            _phase.collect { android.util.Log.i("GiracleNav", "phase $it") }
        }
        scope.launch {
            // つながったら送信待ちを送る (#30)。読み込みに失敗していたら (圏外で起動したなど) やり直す (#82)
            ws.state.collect {
                if (it == com.giracle.android.data.net.WsClient.State.Connected) {
                    flushOutbox()
                    retryLoadIfFailed()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            // 背面に回ったときのスナップショットを書く。書き込みはこの 1 本だけで行い、重ならない (#67)。
            // 書いている間に次が来たら、書き終えてから最新の 1 つだけを書く (StateFlow は途中の値を飛ばす)
            snapshotToSave.collect { if (it != null) writeSnapshot() }
        }
        scope.launch {
            // 「セッション切れ」通知を監視 (ストアが作り直されたら付け替える)
            _store.collectLatest { s -> s.sessionExpired.collect { if (it) signOutLocal() } }
        }
    }


    private fun readSnapshot(userId: String): com.giracle.android.data.Snapshot? = runCatching {
        val text = snapshotFile.readText() ?: return null
        val s = com.giracle.android.data.net.AppJson.decodeFromString(com.giracle.android.data.Snapshot.serializer(), text)
        // 別ユーザー・別サーバーのものは使わない
        if (s.userId == userId && prefs.serverUrl != null) s else null
    }.getOrNull()

    /** アプリが背面に回ったときに呼ぶ。書き込みは裏の 1 本 (init) が行う */
    fun saveSnapshot() {
        snapshotToSave.value = store.snapshot() ?: return
    }

    private fun writeSnapshot() {
        var written: com.giracle.android.data.Snapshot? = null
        try {
            // 書く直前の最新を書く。ログアウトで null になっていたら書かない (前のユーザーの内容を戻さない)
            snapshotFile.write {
                written = snapshotToSave.value
                written?.let { com.giracle.android.data.net.AppJson.encodeToString(com.giracle.android.data.Snapshot.serializer(), it) }
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w("GiracleSnapshot", "save failed", e)
        }
        // 書き終えたものは手放す (メモリに持ち続けない)。その間に次が来ていれば残り、もう一度書く
        written?.let { snapshotToSave.compareAndSet(it, null) }
    }

    fun setServer(url: String, direct: Boolean): Boolean {
        val ep = Endpoint.parse(url, direct) ?: return false
        endpoint = ep
        api.endpoint = ep
        prefs.serverUrl = url.trim()
        prefs.directBackend = direct
        return true
    }

    /** ログイン前のサーバー確認 */
    suspend fun probeServer(): ServerConfig = api.serverConfig()

    suspend fun signIn(username: String, password: String) {
        val r = api.signIn(username, password)
        prefs.userId = r.userId
        _phase.value = AppPhase.SignedIn(r.userId, ready = false)
        // 読み込みはアプリの scope で行い、呼び出し元はその完了を待つだけにする。呼び出し元 (ログイン画面) は
        // 上の行で画面から外れて scope が取り消されるため、そこで読み込むと途中で止まり
        // 「サーバーから読み込めませんでした」になっていた (#12 で発見)。待つ側が取り消されても読み込みは続く。
        sessionLoadStarted = true
        startLoad(r.userId).join()
    }

    suspend fun signUp(username: String, password: String, invite: String?) {
        api.signUp(username, password, invite)
        signIn(username, password)
    }

    /** 読み込みを始める。もう動いていれば、それを返す (2 つ走らせない。そのかわり、失敗したらもう 1 回やり直す印を付ける) */
    private fun startLoad(userId: String, before: kotlinx.coroutines.Job? = null): kotlinx.coroutines.Job = synchronized(this) {
        loadJob?.takeIf { it.isActive }?.let {
            retryAfterLoad.set(true)
            return it
        }
        retryAfterLoad.set(false)
        _loading.value = true
        lateinit var job: kotlinx.coroutines.Job
        job = scope.launch {
            try {
                before?.join() // 前回の内容を出し終えてから最新に置き換える (逆になると古い内容で上書きされる)
                loadSession(userId)
            } finally {
                // 「動いている」をやめることと、最中に来たきっかけでやり直すかの判断を、同じロックの中で行う。
                // 分けると、判断の後・終わる前に来たきっかけが、終わりかけの読み込みに付いたまま捨てられる (レビューで見つけた)。
                // ログアウトで取り消された古い読み込みは、後から始めた読み込みの印を消さない (loadJob === job)
                val again = synchronized(this@AppGraph) {
                    if (loadJob !== job) return@synchronized false
                    loadJob = null
                    _loading.value = false
                    retryAfterLoad.getAndSet(false) && !job.isCancelled && (_phase.value as? AppPhase.SignedIn)?.error != null
                }
                if (again) startLoad(userId)
            }
        }
        loadJob = job
        job
    }

    private suspend fun loadSession(userId: String) {
        try {
            store.initialLoad(userId)
            // ログアウトで取り消されていたら、前のユーザーの状態を書き戻さない (取り消しは次の中断点まで効かないため)
            currentCoroutineContext().ensureActive()
            _phase.value = AppPhase.SignedIn(userId, ready = true)
            // サーバーに届くと分かったので、送信待ちも送る。WebSocket がつながらなくても送れるように (#101)
            flushOutbox()
            // 開いているチャンネルが前回の内容のまま (圏外で開いて取り直せなかった) なら、ここで最新にする (#82)
            store.refreshOpenChannel()
        } catch (e: ApiException) {
            currentCoroutineContext().ensureActive()
            if (e.isUnauthorized) signOutLocal()
            else _phase.value = AppPhase.SignedIn(userId, ready = false, error = e.body.ifBlank { e.message })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取り消しは失敗ではない
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _phase.value = AppPhase.SignedIn(userId, ready = false, error = e.message ?: e.toString())
        }
    }

    /**
     * 保存済みのセッションで起動したときの、サーバーからの読み込みを始める (1 回だけ)。
     * MainActivity の起動・前面への切り替え・常時接続のサービスから呼ぶ。プッシュの受信だけでは呼ばない。
     */
    fun ensureSessionLoaded() {
        val p = _phase.value as? AppPhase.SignedIn ?: return
        synchronized(this) {
            if (sessionLoadStarted || p.ready) return
            sessionLoadStarted = true
        }
        startLoad(p.userId, before = snapshotJob)
    }

    /**
     * 失敗した読み込みをやり直す (「再試行」)。失敗の表示 (error) は、やり直しが終わるまで残す。
     * 消すと、前回の内容を出していた画面が「読み込み中」に戻り、また失敗するとちらつくため。やり直し中かは [loading] で出す
     */
    fun retryLoad() {
        val p = _phase.value as? AppPhase.SignedIn ?: return
        if (p.ready) return
        startLoad(p.userId)
    }

    /**
     * 読み込みに失敗したままなら、やり直す (#82)。回線が戻ったとき・WebSocket がつながったとき・前面に戻ったときに呼ぶ。
     * 圏外で起動すると読み込みが失敗し、以前は利用者が「再試行」を押すまで最新にならなかった
     */
    fun retryLoadIfFailed() {
        val p = _phase.value as? AppPhase.SignedIn ?: return
        if (p.error != null) retryLoad()
    }

    suspend fun signOut() {
        // サーバーから端末の登録を消すにはログイン中のトークンが要るので、ログアウトより先に解く
        com.giracle.android.notify.PushRegistrar.onSignOut(appContext, this)
        runCatching { api.signOut() }
        signOutLocal()
    }

    private fun signOutLocal() {
        // セッション切れでも、前のユーザー宛ての通知を受け続けないよう配信先の登録を解く
        pushRetry.reset()
        if (prefs.pushEndpoint != null) {
            prefs.pushEndpoint = null
            scope.launch(Dispatchers.IO) { runCatching { org.unifiedpush.android.connector.UnifiedPush.unregister(appContext) } }
        }
        // 前のユーザーの読み込みを止める。残すと、次のログインが「もう動いている読み込み」としてそれを待ってしまう (startLoad)
        synchronized(this) {
            loadJob?.cancel()
            loadJob = null
            _loading.value = false
        }
        store.stop()
        prefs.clearSession()
        com.giracle.android.ui.channel.Drafts.clear(appContext)
        outbox.clear()
        com.giracle.android.ui.ChannelShortcuts.clear(appContext)
        snapshotToSave.value = null
        snapshotFile.delete()
        // 状態を捨てて作り直す
        _store.value = newStore()
        sessionLoadStarted = false
        _phase.value = AppPhase.SignedOut
    }

    companion object {
        const val DEFAULT_SERVER = "http://localhost"
        const val UNCAUGHT_TAG = "GiracleUncaught"
    }
}
