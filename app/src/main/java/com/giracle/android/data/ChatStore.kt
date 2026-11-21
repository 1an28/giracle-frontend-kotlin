package com.giracle.android.data

import android.util.Log
import androidx.compose.runtime.Immutable
import com.giracle.android.data.model.*
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.AppJson
import com.giracle.android.data.net.GiracleApi
import com.giracle.android.data.net.WsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 1 チャンネル分の履歴。messages は新しい順 (index 0 が最新) */
@Immutable
data class HistoryState(
    val messages: List<Message> = emptyList(),
    val atTop: Boolean = false,
    val atEnd: Boolean = false,
    val loadingOlder: Boolean = false,
    val loadingNewer: Boolean = false,
    /** 初回読み込みが終わったか */
    val loaded: Boolean = false,
    /** 変更の世代。スクロール位置の調整などに使う */
    val version: Int = 0,
    /** 端末に保存していた前回の内容 (表示はするが、開いたらサーバーから取り直す) */
    val fromCache: Boolean = false,
)

/** 起動を速くするため端末に保存する状態のスナップショット */
@kotlinx.serialization.Serializable
data class Snapshot(
    val userId: String,
    val me: User? = null,
    val server: ServerConfig = ServerConfig(),
    val channels: List<Channel> = emptyList(),
    val users: List<User> = emptyList(),
    val roles: List<Role> = emptyList(),
    val customEmoji: List<CustomEmoji> = emptyList(),
    val readTimes: Map<String, String> = emptyMap(),
    val hasNew: Map<String, Boolean> = emptyMap(),
    val inbox: List<InboxItem> = emptyList(),
    val histories: Map<String, List<Message>> = emptyMap(),
    val imageSizes: Map<String, ImageSize> = emptyMap(),
    val muted: List<String> = emptyList(),
)

/** アプリ内通知 (画面上部のバナーや Android 通知に使う) */
data class InAppNotice(
    val title: String,
    val body: String,
    val channelId: String?,
    val messageId: String?,
    /** 送信者の表示名 (通知を会話の形にするのに使う。分からなければ null) */
    val sender: String? = null,
    /** 送信者の ID (通知に送信者のアイコンを出すのに使う。#26) */
    val senderId: String? = null,
    /** チャンネル名 (分かっていれば。無ければ通知を出すときにストアから探す) */
    val channelName: String? = null,
    /** メッセージの時刻 (サーバーの時刻。分かれば)。別の端末で既読にしたら通知を消すのに使う (#49) */
    val createdAt: String? = null,
)

/**
 * ログイン後の状態をすべて持つストア。
 * Web 版の stores/ 以下と WS/ 以下 をまとめたもの。
 *
 * 性能上の工夫:
 * - チャンネルごとに別の StateFlow を持ち、表示中のチャンネルだけが再描画される
 * - ユーザー情報は必要になったときに 1 回だけ取りに行く (同時要求はまとめる)
 * - JSON 解析と状態計算は呼び出し側のディスパッチャ (Default) で行う
 */
class ChatStore(
    private val api: GiracleApi,
    private val ws: WsClient,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** 読み込みの後に WebSocket をつなぐか。プッシュで裏から起きたプロセスではつながない (AppGraph が決める) */
    private val shouldConnect: () -> Boolean = { true },
) {
    val me = MutableStateFlow<User?>(null)
    /** 前回の内容 (スナップショット) を戻したか。読み込みに失敗したとき、画面全体の失敗にせず前回の内容を出す判断に使う (#82) */
    val restoredFromSnapshot = MutableStateFlow(false)
    val serverConfig = MutableStateFlow(ServerConfig())
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val users = MutableStateFlow<Map<String, User>>(emptyMap())
    val roles = MutableStateFlow<Map<String, Role>>(emptyMap())
    val customEmoji = MutableStateFlow<List<CustomEmoji>>(emptyList())
    val online = MutableStateFlow<Set<String>>(emptySet())
    val hasNew = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    val readTimes = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 「新着」線の位置に使う、チャンネルを開いた時点の既読時刻 */
    val readTimesBefore = MutableStateFlow<Map<String, String>>(emptyMap())
    val imageSizes = MutableStateFlow<Map<String, ImageSize>>(emptyMap())
    val notificationConfig = MutableStateFlow(NotificationConfig())
    val mutedChannels = MutableStateFlow<Set<String>>(emptySet())

    /** 現在開いているチャンネル (通知・既読の判定用) */
    @Volatile var currentChannelId: String? = null
    @Volatile var appInForeground: Boolean = true

    private val _notices = MutableSharedFlow<InAppNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<InAppNotice> = _notices

    /** セッションが無効になった (ログアウトさせる) */
    val sessionExpired = MutableStateFlow(false)

    private val histories = ConcurrentHashMap<String, MutableStateFlow<HistoryState>>()
    private val historyLocks = ConcurrentHashMap<String, Mutex>()
    private val userRequests = ConcurrentHashMap<String, Job>()
    /** WebSocket 関係の購読をまとめた親。stop() でまとめて止める (ログアウト後に古いストアが動き続けないように) */
    private var wsJob: Job? = null

    fun history(channelId: String): StateFlow<HistoryState> =
        histories.getOrPut(channelId) { MutableStateFlow(HistoryState()) }

    private fun hist(channelId: String) = histories.getOrPut(channelId) { MutableStateFlow(HistoryState()) }
    private fun lock(channelId: String) = historyLocks.getOrPut(channelId) { Mutex() }

    /**
     * 履歴を取り直している間に届いた新着 (送信の応答・WebSocket)。取り直しの結果は、取りに行った時点の内容なので、
     * その後に届いたものを含まない。以前は結果で一覧を置き換え、間に届いたものが消えていた。
     * 再同期の取り直しの間は「キャッシュから」の印で [addMessage] が足さないので、なおさら見えなくなった (#84)
     */
    private val arrivedWhileFetching = java.util.concurrent.ConcurrentHashMap<String, MutableList<Message>>()

    /** 取り直しの間に届いた新着を控え始める */
    private fun beginFetch(channelId: String) { arrivedWhileFetching[channelId] = java.util.Collections.synchronizedList(mutableListOf()) }

    /**
     * 取り直しの結果を一覧に入れ、控えを終える。最新側まで取れた結果なら、間に届いた新着のうち一覧に無いものを先頭 (新しい側) に足す。
     * 先に結果を入れてから控えを外す。逆の順だと、外してから入れるまでの間に届いた新着が、古い一覧に足されて結果で上書きされる (#84 のレビュー)
     */
    private fun applyFetched(channelId: String, flow: MutableStateFlow<HistoryState>, state: HistoryState) {
        // 送信中の仮のもの (D-071) は、最新側まで取れた結果なら先頭に残す。応答が来たら本物に置き換わる
        // ただし、結果に本物がもう入っていたら (つなぎ直しの取り直しで、応答より先にサーバーの保存が見えた) 外す。
        // 外さないと、応答が返るまで二重に見える (#118 のレビュー)。突き合わせるのは前の一覧に無かったものだけ
        // (前からある昔の同じ本文、例えば「ok」と取り違えないため)
        flow.update { old ->
            val pend = if (state.atEnd) old.messages.filter { it.pending } else emptyList()
            if (pend.isEmpty()) state else {
                val oldIds = old.messages.mapTo(HashSet()) { it.id }
                val left = withoutConfirmed(pend, state.messages.filter { it.id !in oldIds })
                if (left.isEmpty()) state else state.copy(messages = left + state.messages)
            }
        }
        if (state.atEnd) {
            flow.update { s ->
                // 控えは更新の中で読む (間に削除の知らせが来たら、やり直しで最新の控えを読む)
                val arrived = arrivedWhileFetching[channelId]?.let { synchronized(it) { it.toList() } }.orEmpty()
                val ids = s.messages.mapTo(HashSet()) { it.id }
                val extra = arrived.filter { it.id !in ids }.distinctBy { it.id }
                if (extra.isEmpty()) s else {
                    // 間に一覧へ直接入った新着もあるので、先頭に付けるだけでなく作成時刻で並べる (同じ時刻は今の順を保つ)。
                    // 仮のものは端末の時計の時刻なので並べる対象にせず、先頭に置いたままにする
                    val (pend, rest) = s.messages.partition { it.pending }
                    val sorted = (extra + rest).sortedByDescending { it.createdAt }
                    // 控えに入った本物が、先頭に残した仮のものと同じ送信なら、仮のものを外す
                    s.copy(messages = withoutConfirmed(pend, extra) + sorted, version = s.version + 1)
                }
            }
        }
        arrivedWhileFetching.remove(channelId)
    }

    // ------------------------------------------------------------------
    // 初期読み込み
    // ------------------------------------------------------------------

    /** ログイン直後・起動時に呼ぶ。Web 版 InitLoad と同じものを並列に取る */
    suspend fun initialLoad(userId: String) = coroutineScope {
        val tasks = listOf(
            async { me.value = api.userInfo(userId).also { putUser(it) } },
            async { serverConfig.value = api.serverConfig() },
            async { channels.value = api.channelList() },
            async { roles.value = api.roles().associateBy { it.id } },
            async {
                val rt = api.readTimes().associate { it.channelId to it.readTime }
                readTimes.value = rt
                readTimesBefore.value = rt
            },
            async { hasNew.value = api.hasNew() },
            async { inbox.value = api.inbox() },
            async { customEmoji.value = api.customEmoji() },
            async { runCatching { notificationConfig.value = api.notificationConfig() } },
            async { runCatching { mutedChannels.value = api.mutedChannels().map { it.channelId }.toSet() } },
        )
        tasks.awaitAll()
        startWs()
    }

    private fun startWs() {
        if (wsJob == null) {
            val job = SupervisorJob(scope.coroutineContext[Job])
            wsJob = job
            val s = CoroutineScope(scope.coroutineContext + job)
            s.launch { ws.frames.collect { runCatching { handle(it) }.onFailure { e -> Log.w(TAG, "ws handle", e) } } }
            s.launch {
                var last = ws.reconnects.value
                ws.reconnects.collect { n -> if (n != last) { last = n; resync() } }
            }
            s.launch {
                ws.state.collect { s ->
                    if (s == WsClient.State.Connected) runCatching { online.value = api.onlineUsers().toSet() }
                    if (s == WsClient.State.AuthError) checkSession()
                }
            }
        }
        if (shouldConnect()) ws.start()
    }

    /** WS エラー時、トークンが本当に無効か確かめる */
    private suspend fun checkSession() {
        try {
            api.verifyToken()
        } catch (e: ApiException) {
            if (e.isUnauthorized) sessionExpired.value = true
        } catch (_: Exception) {
        }
    }

    /** 再接続後の再同期 (Web 版 resyncAfterReconnect と同等) */
    internal suspend fun resync() {
        val uid = me.value?.id ?: return
        try {
            initialLoadNoWs(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resync: reload failed", e)
        }
        // 切れていた間の投稿が抜けているかもしれないので、どのチャンネルも「キャッシュから」の印を付け、開いたときに取り直す。
        // 開いていないチャンネルも、最新側の履歴は捨てずに残す。次に開いたとき、取り直しを待たずにすぐ出すため
        // (起動時のスナップショットの復元と同じ扱い。docs/UI_PRINCIPLES.md の C-2、P-1、D-047)。
        // 以前は捨てており、つなぎ直した後に開くと、中央の回転アイコンで取り直しを待たされた。
        // 最新側に揃っていない (古い側を読んでいた) 履歴は、続きを読む位置が分からなくなるので捨てる。
        // 開いているチャンネルは、取り直すまで今の表示を残す (fromCache にすると openChannel が取り直す)。
        // 取り直しに失敗しても、表示はそのまま残り、次に開いたときに取り直す。
        // 以前は表示を空にしてから取り直しており、失敗すると例外が購読の外まで抜けてアプリが落ちていた
        val current = currentChannelId
        val others = histories.keys.count { it != current }
        for ((id, flow) in histories) {
            if (id != current) flow.update(::keepAsCache)
        }
        if (current != null) {
            histories[current]?.update { if (it.loaded) it.copy(fromCache = true) else it }
            try {
                openChannel(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resync: reopen failed", e)
            }
        }
        // 手がかり: つなぎ直した後に、どのチャンネルの履歴をどう扱ったか。e2e はこの行で再同期の終わりを待つ (D-046)
        Log.i(SYNC_TAG, "resync: current=$current others=$others kept=${histories.count { (id, f) -> id != current && f.value.loaded }}")
    }

    /** 再接続の後、開いていないチャンネルの履歴を、件数を抑えて「キャッシュから」として残す ([resync]) */
    private fun keepAsCache(s: HistoryState): HistoryState =
        if (s.loaded && s.atEnd && s.messages.isNotEmpty()) {
            HistoryState(
                messages = s.messages.take(KEEP_WHEN_LEFT), atTop = s.atTop && s.messages.size <= KEEP_WHEN_LEFT, atEnd = true,
                loaded = true, version = s.version + 1, fromCache = true,
            )
        } else {
            HistoryState(version = s.version + 1)
        }

    private suspend fun initialLoadNoWs(userId: String) = coroutineScope {
        listOf(
            async { me.value = api.userInfo(userId).also { putUser(it) } },
            async { channels.value = api.channelList() },
            async { readTimes.value = api.readTimes().associate { it.channelId to it.readTime } },
            async { hasNew.value = api.hasNew() },
            async { inbox.value = api.inbox() },
        ).awaitAll()
    }

    /** 保存用のスナップショット。履歴は各チャンネルの最新 [perChannel] 件だけ */
    fun snapshot(perChannel: Int = 40): Snapshot? {
        val u = me.value ?: return null
        val hs = histories.mapNotNull { (id, f) ->
            val v = f.value
            // 送信中の仮のもの (D-071) は保存しない (次の起動でサーバーに無いものが残るため)
            val msgs = v.messages.filterNot { it.pending }
            if (v.loaded && v.atEnd && msgs.isNotEmpty()) id to msgs.take(perChannel) else null
        }.toMap()
        val needUsers = HashSet<String>().apply {
            hs.values.forEach { l -> l.forEach { add(it.userId) } }
            inbox.value.forEach { add(it.message.userId) }
        }
        val fileIds = hs.values.flatMap { l -> l.flatMap { m -> m.files.map { it.id } } }.toSet()
        return Snapshot(
            userId = u.id, me = u, server = serverConfig.value, channels = channels.value,
            users = users.value.values.filter { it.id in needUsers },
            roles = roles.value.values.toList(), customEmoji = customEmoji.value,
            readTimes = readTimes.value, hasNew = hasNew.value, inbox = inbox.value.take(50),
            histories = hs, imageSizes = imageSizes.value.filterKeys { it in fileIds },
            muted = mutedChannels.value.toList(),
        )
    }

    /** 前回のスナップショットを反映する (サーバーからの読み込みが終わる前の仮表示) */
    fun restore(s: Snapshot) {
        if (me.value != null) return
        Log.i(SYNC_TAG, "restore: histories=${s.histories.size}")
        restoredFromSnapshot.value = true
        me.value = s.me
        serverConfig.value = s.server
        channels.value = s.channels
        users.value = s.users.associateBy { it.id }
        roles.value = s.roles.associateBy { it.id }
        customEmoji.value = s.customEmoji
        readTimes.value = s.readTimes
        readTimesBefore.value = s.readTimes
        hasNew.value = s.hasNew
        inbox.value = s.inbox
        imageSizes.value = s.imageSizes
        mutedChannels.value = s.muted.toSet()
        for ((id, msgs) in s.histories) {
            hist(id).value = HistoryState(messages = msgs, atTop = false, atEnd = true, loaded = true, fromCache = true)
        }
    }

    fun stop() {
        ws.stop()
        wsJob?.cancel()
        wsJob = null
    }

    // ------------------------------------------------------------------
    // ユーザー
    // ------------------------------------------------------------------

    fun putUser(u: User) = users.update { it + (u.id to u) }

    /** 未取得ならバックグラウンドで取りに行く。同じ ID の同時要求は 1 回にまとめる */
    fun ensureUser(userId: String) {
        if (userId == "SYSTEM" || users.value.containsKey(userId) || userRequests.containsKey(userId)) return
        userRequests[userId] = scope.launch {
            try {
                putUser(api.userInfo(userId))
            } catch (e: Exception) {
                // 存在しないユーザー: 名前不明として置いておき、再要求を防ぐ
                if (e is ApiException) putUser(User(id = userId, name = null, isDeleted = true))
            } finally {
                userRequests.remove(userId)
            }
        }
    }

    fun roleOf(user: User?): List<Role> = user?.roleLink?.mapNotNull { roles.value[it.roleId] } ?: emptyList()

    /** 自分がその権限を持つか (manageServer は全権限を含む) */
    fun can(term: String): Boolean {
        val rs = roleOf(me.value)
        return rs.any { r ->
            r.manageServer || when (term) {
                "manageChannel" -> r.manageChannel
                "manageRole" -> r.manageRole
                "manageUser" -> r.manageUser
                "manageEmoji" -> r.manageEmoji
                else -> false
            }
        }
    }

    // ------------------------------------------------------------------
    // 履歴
    // ------------------------------------------------------------------

    /**
     * チャンネルを開く。未読み込みなら既読位置の周りを取得する。
     * 既読位置より古い側を取り、未読があれば新しい側も続けて取る。
     */
    suspend fun openChannel(channelId: String) {
        currentChannelId = channelId
        readTimesBefore.update { it + (channelId to (readTimes.value[channelId] ?: "")) }
        val flow = hist(channelId)
        if (flow.value.loaded && !flow.value.fromCache) return
        lock(channelId).withLock {
            if (flow.value.loaded && !flow.value.fromCache) return
            beginFetch(channelId)
            try {
                val readTime = readTimes.value[channelId]
                val r = api.history(channelId, messageTimeFrom = readTime?.takeIf { it.isNotEmpty() }, fetchLength = 30, direction = "older")
                mergeSizes(r.imageDimensions)
                var state = HistoryState(messages = r.history, atTop = r.atTop, atEnd = r.atEnd, loaded = true, version = flow.value.version + 1)
                if (!r.atEnd && r.history.isNotEmpty()) {
                    val newer = api.history(channelId, messageIdFrom = r.history.first().id, fetchLength = 30, direction = "newer")
                    mergeSizes(newer.imageDimensions)
                    state = state.copy(messages = mergeNewer(state.messages, newer.history), atEnd = newer.atEnd)
                }
                applyFetched(channelId, flow, state)
                flow.value.messages.asSequence().map { it.userId }.distinct().forEach(::ensureUser)
            } finally {
                arrivedWhileFetching.remove(channelId)
            }
        }
    }

    /**
     * 開いているチャンネルが前回の内容 (キャッシュ) のままなら取り直す。圏外で起動して取り直せなかったチャンネルを、
     * つながって読み込みが済んだ後に最新にする (#82)。失敗しても表示は残す
     */
    suspend fun refreshOpenChannel() {
        val current = currentChannelId ?: return
        if (histories[current]?.value?.fromCache != true) return
        try {
            openChannel(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refreshOpenChannel failed", e)
        }
    }

    /** 最新位置から読み込み直す (「最新へ移動」ボタン) */
    suspend fun jumpToLatest(channelId: String) {
        val flow = hist(channelId)
        lock(channelId).withLock {
            beginFetch(channelId)
            try {
                val r = api.history(channelId, fetchLength = 30, direction = "older")
                mergeSizes(r.imageDimensions)
                applyFetched(channelId, flow, HistoryState(messages = r.history, atTop = r.atTop, atEnd = r.atEnd, loaded = true, version = flow.value.version + 1))
                flow.value.messages.asSequence().map { it.userId }.distinct().forEach(::ensureUser)
            } finally {
                arrivedWhileFetching.remove(channelId)
            }
        }
    }

    /** 指定メッセージの周辺を読み込む (メッセージリンク・通知から飛ぶとき)。見つかれば true */
    suspend fun jumpToMessage(channelId: String, messageId: String): Boolean {
        val flow = hist(channelId)
        if (flow.value.messages.any { it.id == messageId }) return true
        return lock(channelId).withLock {
            try {
                val older = api.history(channelId, messageIdFrom = messageId, fetchLength = 30, direction = "older")
                val newer = api.history(channelId, messageIdFrom = messageId, fetchLength = 30, direction = "newer")
                mergeSizes(older.imageDimensions); mergeSizes(newer.imageDimensions)
                flow.value = HistoryState(
                    messages = mergeNewer(older.history, newer.history),
                    atTop = older.atTop, atEnd = newer.atEnd, loaded = true, version = flow.value.version + 1,
                )
                flow.value.messages.forEach { ensureUser(it.userId) }
                flow.value.messages.any { it.id == messageId }
            } catch (e: ApiException) {
                false
            }
            // 通信の失敗 (圏外など) は呼び出し側に投げる。「削除された」とは別の案内を出すため
        }
    }

    suspend fun loadOlder(channelId: String) {
        val flow = hist(channelId)
        val s = flow.value
        // キャッシュの続きは読まない (取り直しで別の範囲に差し替わり、つなぐと抜けができるため)
        if (!s.loaded || s.atTop || s.loadingOlder || s.fromCache) return
        val oldest = s.messages.lastOrNull() ?: return
        flow.update { it.copy(loadingOlder = true) }
        lock(channelId).withLock {
            // ロックを待つ間に取り直しで差し替わっていたら、続きを読まない
            if (flow.value.fromCache || flow.value.messages.lastOrNull()?.id != oldest.id) {
                flow.update { it.copy(loadingOlder = false) }
                return
            }
            try {
                val r = api.history(channelId, messageIdFrom = oldest.id, fetchLength = 30, direction = "older")
                mergeSizes(r.imageDimensions)
                flow.update { cur ->
                    val merged = mergeOlder(cur.messages, r.history)
                    // メモリを抑えるため、極端に長くなったら新しい側を捨てる
                    if (merged.size > MAX_IN_MEMORY) cur.copy(messages = merged.takeLast(KEEP_AFTER_TRIM), atTop = r.atTop, atEnd = false, loadingOlder = false, version = cur.version + 1)
                    else cur.copy(messages = merged, atTop = r.atTop, loadingOlder = false, version = cur.version + 1)
                }
                r.history.forEach { ensureUser(it.userId) }
            } catch (e: Exception) {
                flow.update { it.copy(loadingOlder = false) }
                throw e
            }
        }
    }

    suspend fun loadNewer(channelId: String) {
        val flow = hist(channelId)
        val s = flow.value
        if (!s.loaded || s.atEnd || s.loadingNewer || s.fromCache) return
        // 送信中の仮のもの (D-071) の id はサーバーに無いので、続きの起点にしない
        val newest = s.messages.firstOrNull { !it.pending } ?: return
        flow.update { it.copy(loadingNewer = true) }
        lock(channelId).withLock {
            if (flow.value.fromCache || flow.value.messages.firstOrNull { !it.pending }?.id != newest.id) {
                flow.update { it.copy(loadingNewer = false) }
                return
            }
            try {
                val r = api.history(channelId, messageIdFrom = newest.id, fetchLength = 30, direction = "newer")
                mergeSizes(r.imageDimensions)
                flow.update { cur ->
                    val merged = mergeNewer(cur.messages, r.history)
                    if (merged.size > MAX_IN_MEMORY) cur.copy(messages = merged.take(KEEP_AFTER_TRIM), atEnd = r.atEnd, atTop = false, loadingNewer = false, version = cur.version + 1)
                    else cur.copy(messages = merged, atEnd = r.atEnd, loadingNewer = false, version = cur.version + 1)
                }
                r.history.forEach { ensureUser(it.userId) }
            } catch (e: Exception) {
                flow.update { it.copy(loadingNewer = false) }
                throw e
            }
        }
    }

    /** チャンネルから離れたとき。メモリ節約のため、最新側に揃っていれば末尾を詰める */
    fun leaveChannel(channelId: String) {
        if (currentChannelId == channelId) currentChannelId = null
        hist(channelId).update { s ->
            if (s.atEnd && s.messages.size > KEEP_WHEN_LEFT) s.copy(messages = s.messages.take(KEEP_WHEN_LEFT), atTop = false, version = s.version + 1) else s
        }
    }

    private fun mergeSizes(m: Map<String, ImageSize>) {
        if (m.isNotEmpty()) imageSizes.update { it + m }
    }

    /** [current] (新しい順) の後ろに古いメッセージ [older] (新しい順) をつなぐ。重複は除く */
    private fun mergeOlder(current: List<Message>, older: List<Message>): List<Message> {
        if (current.isEmpty()) return older
        val ids = HashSet<String>(current.size * 2).apply { current.forEach { add(it.id) } }
        return current + older.filter { it.id !in ids }
    }

    /** [current] (新しい順) の前に新しいメッセージ [newer] (新しい順) をつなぐ。重複は除く */
    private fun mergeNewer(current: List<Message>, newer: List<Message>): List<Message> {
        if (current.isEmpty()) return newer
        val ids = HashSet<String>(current.size * 2).apply { current.forEach { add(it.id) } }
        return newer.filter { it.id !in ids } + current
    }

    // ------------------------------------------------------------------
    // 既読
    // ------------------------------------------------------------------

    private val readTimeJobs = ConcurrentHashMap<String, Job>()

    /**
     * 既読時刻を更新する (最新のメッセージを表示しているときに呼ぶ)。
     * 連続スクロールでの多重送信を避けるため 300ms まとめる。
     */
    fun markRead(channelId: String) {
        val s = hist(channelId).value
        // キャッシュ (切れていた間の投稿が抜けているかもしれない) の最新で既読にすると、見ていない投稿まで既読になる。取り直してから既読にする
        if (!s.atEnd || s.fromCache) return
        // 送信中の仮のもの (D-071) の時刻は端末の時計なので、既読の時刻にしない
        val newest = s.messages.firstOrNull { !it.pending }?.createdAt ?: return
        val current = readTimes.value[channelId]
        if (current != null && !isAfter(newest, current)) {
            if (hasNew.value[channelId] == true) hasNew.update { it - channelId }
            return
        }
        readTimes.update { it + (channelId to newest) }
        hasNew.update { it - channelId }
        pushReadTime(channelId, newest)
    }

    /**
     * 未読のあるチャンネルをすべて既読にする (ホームの「すべて既読」)。サーバーにまとめて既読にする API は無いので、
     * チャンネルごとに最新のメッセージを 1 件取り、その時刻を既読にする。
     * @return 既読にできなかったチャンネルの数
     */
    suspend fun markAllRead(): Int = coroutineScope {
        val targets = hasNew.value.filterValues { it }.keys.toList()
        targets.map { ch ->
            async {
                try {
                    val latest = api.history(ch, fetchLength = 1, direction = "older").history.firstOrNull()
                    if (latest != null) {
                        val cur = readTimes.value[ch]
                        if (cur == null || isAfter(latest.createdAt, cur)) {
                            api.updateReadTime(ch, latest.createdAt)
                            readTimes.update { it + (ch to latest.createdAt) }
                        }
                    }
                    // 既読にしている間に新着が届いていたら、その未読表示は残す
                    val arrived = lastArrival[ch]
                    if (latest == null || arrived == null || !isAfter(arrived, latest.createdAt)) hasNew.update { it - ch }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "markAllRead: $ch", e)
                    false
                }
            }
        }.awaitAll().count { !it }
    }

    /** チャンネルごとの、最後に届いた他の人のメッセージの時刻 (「すべて既読」の最中に届いた新着を見分ける) */
    private val lastArrival = ConcurrentHashMap<String, String>()

    private fun pushReadTime(channelId: String, time: String) {
        readTimeJobs[channelId]?.cancel()
        readTimeJobs[channelId] = scope.launch {
            delay(300)
            runCatching { api.updateReadTime(channelId, time) }
        }
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    /**
     * 送信を押した直後に、応答を待たずに仮のメッセージを一覧の先頭に出す (D-071)。出した仮のものの id を返す。
     * 最新側を出していない・キャッシュを出しているときは出さず null (応答の後に今までどおり足す。[addMessage] と同じ条件)。
     * 仮のものは、応答か WebSocket で本物が届いたら置き換わり、送信に失敗したら消える ([send])
     */
    fun addPending(channelId: String, text: String, replyTo: String?): String? {
        val myId = me.value?.id ?: return null
        val flow = histories[channelId] ?: return null
        val local = Message(
            id = "local-" + java.util.UUID.randomUUID(), channelId = channelId, userId = myId, content = text,
            replyingMessageId = replyTo, createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(), pending = true,
        )
        var added = false
        flow.update { s ->
            added = s.loaded && s.atEnd && !s.fromCache
            if (added) s.copy(messages = listOf(local) + s.messages, version = s.version + 1) else s
        }
        return if (added) local.id else null
    }

    /**
     * 送ったメッセージを返す (画面が、送ったものを見える位置へ送るため。#58)。
     * [localId] は [addPending] で出した仮のもの。届いたら本物に置き換え、失敗 (取り消しを含む) したら消す。
     * [trace] は送信の記録 ([SendLog]) の番号。0 なら新しく振る (送信待ちからの送り直し)
     */
    suspend fun send(channelId: String, text: String, fileIds: List<String>, replyTo: String?, localId: String? = null, trace: Int = 0): Message {
        val n = if (trace != 0) trace else SendLog.next().also { SendLog.log(it, "start", "from=outbox") }
        val t0 = android.os.SystemClock.elapsedRealtime()
        SendLog.log(n, "request", "pending=${localId != null} files=${fileIds.size}")
        val msg = try {
            api.send(channelId, text, fileIds, replyTo)
        } catch (e: Throwable) {
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (e is CancellationException) SendLog.log(n, "cancelled", "ms=$ms")
            else SendLog.log(n, "failure", "ms=$ms type=${e.javaClass.name} code=${(e as? ApiException)?.code} network=${Outbox.isNetworkFailure(e)}")
            if (localId != null) removePending(channelId, localId)
            throw e
        }
        SendLog.log(n, "response", "ms=${android.os.SystemClock.elapsedRealtime() - t0} ok=true")
        SendTrace.mark("main_resumed") // 応答の解析の後、呼んだ側 (画面ならメインスレッド) に戻った時点 (#70)
        // WS より先に応答が来た場合でも即座に表示する (重複は除く)
        confirmSent(msg, localId)
        return msg
    }

    /** 本物に置き換わった仮のものの id → 本物の id。画面が、送ったものへ動かす前に置き換わったときに使う ([resolveSent]) */
    private val confirmedIds = ConcurrentHashMap<String, String>()

    /** 仮のものの id なら、置き換わった本物の id を返す (まだなら・仮のものでなければそのまま) */
    fun resolveSent(id: String): String = confirmedIds[id] ?: id

    private fun removePending(channelId: String, localId: String) {
        histories[channelId]?.update { s ->
            if (s.messages.none { it.id == localId }) s
            else s.copy(messages = s.messages.filterNot { it.id == localId }, version = s.version + 1)
        }
    }

    /** 送信の応答で届いた本物を一覧に入れる。仮のもの [localId] が残っていれば置き換える */
    private fun confirmSent(m: Message, localId: String?) {
        arrivedWhileFetching[m.channelId]?.add(m)
        val flow = histories[m.channelId] ?: return
        flow.update { s ->
            val hasReal = s.messages.any { it.id == m.id }
            val i = if (localId == null) -1 else s.messages.indexOfFirst { it.id == localId }
            when {
                // WebSocket で先に届いて、別の仮のものと置き換わっていた (同じ本文の仮のものは、古い方から置き換わる)
                i >= 0 && hasReal -> s.copy(messages = s.messages.filterIndexed { j, _ -> j != i }, version = s.version + 1)
                    .also { confirmedIds[localId!!] = m.id }
                i >= 0 -> s.copy(messages = insertConfirmed(s.messages.filterIndexed { j, _ -> j != i }, m), version = s.version + 1)
                    .also { confirmedIds[localId!!] = m.id }
                hasReal -> s
                !s.loaded || !s.atEnd || s.fromCache -> s
                else -> s.copy(messages = insertConfirmed(s.messages, m), version = s.version + 1)
            }
        }
    }

    suspend fun toggleReaction(message: Message, emojiCode: String) {
        val mine = message.reactionSummary.firstOrNull { it.emojiCode == emojiCode }?.includingYou == true
        if (mine) api.unreact(message.id, message.channelId, emojiCode)
        else api.react(message.id, message.channelId, emojiCode)
    }

    suspend fun readInbox(messageId: String) {
        inbox.update { l -> l.filterNot { it.message.id == messageId } }
        runCatching { api.inboxRead(messageId) }
    }

    suspend fun clearInbox() {
        inbox.value = emptyList()
        api.inboxClear()
    }

    suspend fun setMuted(channelId: String, muted: Boolean) {
        if (muted) api.muteChannel(channelId) else api.unmuteChannel(channelId)
        mutedChannels.update { if (muted) it + channelId else it - channelId }
    }

    /**
     * 1 つのチャンネルの情報を取り直して、一覧に入れる (無ければ足し、あれば置き換える)。
     * 取ってくる前に読んだ一覧に足して書き戻すと、待つ間に届いた全件 (起動時の読み込み) を、
     * そのチャンネルだけの一覧で上書きしてしまう。ホームの参加チャンネルが 1 つしか出なくなっていた
     */
    suspend fun fetchChannelInfo(channelId: String): Channel {
        val info = api.channelInfo(channelId)
        channels.update { l -> if (l.any { it.id == info.id }) l.map { if (it.id == info.id) info else it } else l + info }
        return info
    }

    suspend fun refreshChannels() {
        channels.value = api.channelList()
        me.value?.id?.let { me.value = api.userInfo(it).also(::putUser) }
    }

    // ------------------------------------------------------------------
    // WebSocket イベント
    // ------------------------------------------------------------------

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, e: JsonElement?): T? =
        e?.let { runCatching { AppJson.decodeFromJsonElement(s, it) }.getOrNull() }

    private fun str(e: JsonElement?, key: String): String? =
        (e as? kotlinx.serialization.json.JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

    internal suspend fun handle(f: WsFrame) {
        val d = f.data
        when (f.signal) {
            "message::SendMessage" -> decode(Message.serializer(), d)?.let { onNewMessage(it) }
            "message::UpdateMessage" -> {
                val obj = d?.jsonObject ?: return
                val id = str(d, "id") ?: return
                val ch = str(d, "channelId") ?: return
                val patch = decode(Message.serializer(), d)
                updateMessage(ch, id) { old ->
                    // URL プレビュー更新時など、一部の項目しか来ないことがあるので、来たものだけ上書き
                    old.copy(
                        content = if ("content" in obj) patch?.content ?: old.content else old.content,
                        isEdited = if ("isEdited" in obj) patch?.isEdited ?: old.isEdited else old.isEdited,
                        urlPreviews = if ("MessageUrlPreview" in obj) patch?.urlPreviews ?: old.urlPreviews else old.urlPreviews,
                        files = if ("MessageFileAttached" in obj) patch?.files ?: old.files else old.files,
                    )
                }
            }
            "message::MessageDeleted" -> {
                val id = str(d, "messageId") ?: return
                val ch = str(d, "channelId") ?: return
                arrivedWhileFetching[ch]?.removeIf { it.id == id }
                hist(ch).update { s -> s.copy(messages = s.messages.filterNot { it.id == id }, version = s.version + 1) }
                inbox.update { l -> l.filterNot { it.message.id == id } }
            }
            "message::AddReaction", "message::DeleteReaction" -> {
                val r = decode(Reaction.serializer(), d) ?: return
                val add = f.signal == "message::AddReaction"
                val isMe = r.userId == me.value?.id
                updateMessage(r.channelId, r.messageId) { m ->
                    val list = m.reactionSummary.toMutableList()
                    val i = list.indexOfFirst { it.emojiCode == r.emojiCode }
                    if (add) {
                        if (i >= 0) list[i] = list[i].copy(count = list[i].count + 1, includingYou = list[i].includingYou || isMe)
                        else list += ReactionSummary(r.emojiCode, 1, isMe)
                    } else if (i >= 0) {
                        val c = list[i].count - 1
                        if (c <= 0) list.removeAt(i) else list[i] = list[i].copy(count = c, includingYou = list[i].includingYou && !isMe)
                    }
                    m.copy(reactionSummary = list)
                }
            }
            "message::ReadTimeUpdated" -> {
                val rt = decode(ReadTime.serializer(), d) ?: return
                if (rt.userId.isNotEmpty() && rt.userId != me.value?.id) return
                readTimes.update { it + (rt.channelId to rt.readTime) }
                // 別端末で既読にしたら未読表示も消す
                hasNew.update { it - rt.channelId }
            }
            "inbox::Added" -> {
                val obj = d?.jsonObject ?: return
                val msg = decode(Message.serializer(), obj["message"]) ?: return
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "mention"
                inbox.update { l -> if (l.any { it.message.id == msg.id }) l else listOf(InboxItem(type = type, messageId = msg.id, message = msg, happendAt = msg.createdAt)) + l }
                ensureUser(msg.userId)
                if (prefs.settings.value.notifyInbox && msg.channelId !in mutedChannels.value && notificationConfig.value.let { it.enabled && it.mode != "off" }) {
                    val name = users.value[msg.userId]?.displayName ?: "誰か"
                    val title = if (type == "reply") "$name さんからの返信" else "$name さんからのメンション"
                    _notices.tryEmit(InAppNotice(title, plainText(msg.content), msg.channelId, msg.id, sender = name, senderId = msg.userId, createdAt = msg.createdAt))
                }
            }
            "inbox::Deleted" -> {
                val id = str(d, "messageId") ?: return
                inbox.update { l -> l.filterNot { it.message.id == id } }
            }
            "inbox::Clear" -> inbox.value = emptyList()
            "channel::Join" -> {
                val id = str(d, "channelId") ?: return
                me.update { u -> u?.takeIf { x -> x.channelJoin.none { it.channelId == id } }?.copy(channelJoin = u.channelJoin + ChannelRef(id)) ?: u }
                if (channels.value.none { it.id == id }) runCatching { channels.update { it + api.channelInfo(id) } }
            }
            "channel::Left" -> {
                val id = str(d, "channelId") ?: return
                me.update { u -> u?.copy(channelJoin = u.channelJoin.filterNot { it.channelId == id }) }
                histories.remove(id)
                hasNew.update { it - id }
            }
            "channel::UpdateChannel" -> {
                val c = decode(Channel.serializer(), d) ?: return
                channels.update { l -> if (l.any { it.id == c.id }) l.map { if (it.id == c.id) c else it } else l + c }
            }
            "channel::Deleted" -> {
                val id = str(d, "channelId") ?: return
                channels.update { l -> l.filterNot { it.id == id } }
                me.update { u -> u?.copy(channelJoin = u.channelJoin.filterNot { it.channelId == id }) }
                histories.remove(id)
            }
            "user::Connected" -> (d as? JsonPrimitive)?.contentOrNull?.let { id -> online.update { it + id } }
            "user::Disconnected" -> (d as? JsonPrimitive)?.contentOrNull?.let { id -> if (id != me.value?.id) online.update { it - id } }
            "user::ProfileUpdate" -> {
                val u = decode(User.serializer(), d) ?: return
                // 部分的な応答のときは既存値を残す
                val old = users.value[u.id]
                val merged = if (old != null) u.copy(
                    channelJoin = if (u.channelJoin.isEmpty()) old.channelJoin else u.channelJoin,
                    roleLink = if (u.roleLink.isEmpty()) old.roleLink else u.roleLink,
                ) else u
                putUser(merged)
                if (u.id == me.value?.id) me.update { cur -> cur?.copy(name = merged.name, selfIntroduction = merged.selfIntroduction, isBanned = merged.isBanned) }
            }
            "user::Deleted" -> str(d, "userId")?.let { id -> users.update { m -> m[id]?.let { m + (id to it.copy(isDeleted = true)) } ?: m } }
            "role::Created", "role::Updated" -> decode(Role.serializer(), d)?.let { r -> roles.update { it + (r.id to r) } }
            "role::Deleted" -> {
                val id = (d as? JsonPrimitive)?.contentOrNull ?: str(d, "roleId") ?: return
                roles.update { it - id }
            }
            "role::Linked", "role::Unlinked" -> {
                val uid = str(d, "userId") ?: return
                val rid = str(d, "roleId") ?: return
                val link = f.signal == "role::Linked"
                fun apply(u: User) = u.copy(roleLink = if (link) (u.roleLink.filterNot { it.roleId == rid } + RoleRef(rid)) else u.roleLink.filterNot { it.roleId == rid })
                users.update { m -> m[uid]?.let { m + (uid to apply(it)) } ?: m }
                if (uid == me.value?.id) me.update { it?.let(::apply) }
            }
            "server::ConfigUpdate" -> decode(ServerConfig.serializer(), d)?.let { c -> serverConfig.update { c.copy(isFirstUser = it.isFirstUser) } }
            "server::CustomEmojiUploaded" -> decode(CustomEmoji.serializer(), d)?.let { e -> customEmoji.update { l -> l.filterNot { it.code == e.code } + e } }
            "server::CustomEmojiDeleted" -> decode(CustomEmoji.serializer(), d)?.let { e -> customEmoji.update { l -> l.filterNot { it.code == e.code } } }
        }
    }

    private fun onNewMessage(m: Message) {
        val myId = me.value?.id
        val viewing = currentChannelId == m.channelId && appInForeground
        if (m.userId == myId) SendTrace.mark("ws_arrived", "id=${m.id}")
        addMessage(m)
        ensureUser(m.userId)
        if (m.userId == myId) {
            // 自分の送信は既読扱い (Web 版と同じくサーバーにも送る)
            readTimes.update { it + (m.channelId to m.createdAt) }
            readTimesBefore.update { it + (m.channelId to m.createdAt) }
            pushReadTime(m.channelId, m.createdAt)
            return
        }
        lastArrival[m.channelId] = m.createdAt
        if (!viewing) {
            hasNew.update { it + (m.channelId to true) }
            val cfg = notificationConfig.value
            val wantsAll = cfg.mode == "all" || prefs.settings.value.notifyAll
            if (!m.isSystemMessage && cfg.enabled && cfg.mode != "off" && wantsAll && m.channelId !in mutedChannels.value) {
                val known = users.value[m.userId]?.displayName
                val name = known ?: "新着メッセージ"
                val ch = channels.value.firstOrNull { it.id == m.channelId }?.name
                _notices.tryEmit(InAppNotice(if (ch != null) "$name (#$ch)" else name, plainText(m.content), m.channelId, m.id, sender = known, senderId = m.userId, channelName = ch, createdAt = m.createdAt))
            }
        }
    }

    /** 新着を履歴に入れる。最新側を表示していないチャンネルには入れない (間が抜けるため) */
    fun addMessage(m: Message) {
        // 取り直しの最中なら控えておく。結果に入っていなければ、取り直しの終わりに足す (#84)
        arrivedWhileFetching[m.channelId]?.add(m)
        val flow = histories[m.channelId] ?: return
        val myId = me.value?.id
        flow.update { s ->
            val hasReal = s.messages.any { it.id == m.id }
            // 自分の送信が WebSocket で応答より先に届いたら、手元に出している仮のもの (D-071) と置き換える。
            // サーバーに重複を防ぐ ID が無い (S-8) ので、同じ本文・同じ返信先で、いちばん古い仮のものと突き合わせる。
            // 本物がもう一覧にあっても (取り直しの結果で入った)、仮のものが残っていれば外す
            if (m.userId == myId) {
                val i = s.messages.indexOfLast { it.pending && sameSend(it, m) }
                if (i >= 0) {
                    confirmedIds[s.messages[i].id] = m.id
                    val rest = s.messages.filterIndexed { j, _ -> j != i }
                    return@update s.copy(messages = if (hasReal) rest else insertConfirmed(rest, m), version = s.version + 1)
                }
            }
            if (hasReal) return@update s
            // キャッシュには足さない (切れていた間の投稿が抜けたまま、新着だけが続いて見えるため。開いたときに取り直す)
            if (!s.loaded || !s.atEnd || s.fromCache) s
            else s.copy(messages = insertConfirmed(s.messages, m), version = s.version + 1)
        }
    }

    /** 仮のもの [pending] と、届いた本物 [real] が同じ送信か (本文と返信先で見る。S-8 が無いため) */
    private fun sameSend(pending: Message, real: Message) =
        pending.channelId == real.channelId && pending.content == real.content && pending.replyingMessageId == real.replyingMessageId

    /** [pend] (仮のもの) から、[confirmed] の本物と同じ送信のものを、本物 1 件につき古い方から 1 件ずつ外す */
    private fun withoutConfirmed(pend: List<Message>, confirmed: List<Message>): List<Message> {
        if (pend.isEmpty()) return pend
        val myId = me.value?.id
        val left = pend.toMutableList()
        for (r in confirmed) {
            if (r.userId != myId) continue
            val i = left.indexOfLast { sameSend(it, r) }
            if (i >= 0) confirmedIds[left.removeAt(i).id] = r.id
        }
        return left
    }

    /**
     * 新しい順の一覧 [list] に、届いたもの [m] を入れる。先頭の仮のもの (送信中) より後ろで、[m] より新しいものの後ろに置く。
     * ふつうは仮のものの直後 (仮のものが無ければ先頭) になる
     */
    private fun insertConfirmed(list: List<Message>, m: Message): List<Message> {
        var i = 0
        while (i < list.size && (list[i].pending || isAfter(list[i].createdAt, m.createdAt))) i++
        return ArrayList<Message>(list.size + 1).apply { addAll(list.subList(0, i)); add(m); addAll(list.subList(i, list.size)) }
    }

    private fun updateMessage(channelId: String, id: String, f: (Message) -> Message) {
        histories[channelId]?.update { s ->
            val i = s.messages.indexOfFirst { it.id == id }
            if (i < 0) s else s.copy(messages = s.messages.toMutableList().also { it[i] = f(it[i]) }, version = s.version + 1)
        }
        inbox.update { l -> if (l.none { it.message.id == id }) l else l.map { if (it.message.id == id) it.copy(message = f(it.message)) else it } }
    }

    /** 通知用に、メンション・チャンネルリンクを名前に置き換えた文 */
    fun plainText(content: String): String {
        if (content.startsWith("{") && content.contains("messageTerm")) return "システムメッセージ"
        return MENTION.replace(content) { mr -> "@" + (users.value[mr.groupValues[1]]?.displayName ?: "不明なユーザー") }
            .let { CHANNEL.replace(it) { mr -> "#" + (channels.value.firstOrNull { c -> c.id == mr.groupValues[1] }?.name ?: "不明なチャンネル") } }
    }

    companion object {
        private const val TAG = "ChatStore"
        /** 再同期・復元の記録。配布版でも出す (e2e の手がかり。GiracleNav と同じ扱い) */
        private const val SYNC_TAG = "GiracleSync"
        private const val MAX_IN_MEMORY = 600
        private const val KEEP_AFTER_TRIM = 450
        private const val KEEP_WHEN_LEFT = 60
        private val MENTION = Regex("@<([a-zA-Z0-9-]+)>")
        private val CHANNEL = Regex("#<([a-zA-Z0-9-]+)>")

        fun isAfter(a: String, b: String): Boolean = runCatching { Instant.parse(a).isAfter(Instant.parse(b)) }.getOrDefault(a > b)
    }
}
