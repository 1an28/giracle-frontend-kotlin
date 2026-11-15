package com.giracle.android.ui.channel

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.giracle.android.ui.common.SmallFab48
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.giracle.android.ui.common.exposeTestTags
import com.giracle.android.ui.common.TestTags
import com.giracle.android.data.ChatStore
import com.giracle.android.data.SendTrace
import com.giracle.android.data.model.FileAttached
import com.giracle.android.data.model.Message
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.Screen
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.fullTime
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.userMessage
import com.giracle.android.ui.members.UserSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(channelId: String, jumpTo: String?) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val history by store.history(channelId).collectAsStateWithLifecycle()
    val channels by store.channels.collectAsStateWithLifecycle()
    val me by store.me.collectAsStateWithLifecycle()
    val muted by store.mutedChannels.collectAsStateWithLifecycle()
    val readBefore by store.readTimesBefore.collectAsStateWithLifecycle()
    val inbox by store.inbox.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val channel = channels.firstOrNull { it.id == channelId }
    val joined = me?.channelJoin?.any { it.channelId == channelId } == true

    val listState = rememberLazyListState()
    var error by remember { mutableStateOf<String?>(null) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var pickerFor by remember { mutableStateOf<Message?>(null) }
    var whoReacted by remember { mutableStateOf<Pair<Message, String>?>(null) }
    var viewing by remember { mutableStateOf<FileAttached?>(null) }
    var expanded by remember { mutableStateOf<Message?>(null) }
    var userSheet by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }

    // ---- 開く・離れる ----
    LaunchedEffect(channelId) {
        graph.prefs.lastChannelId = channelId
        // 開いたチャンネルの通知は、もう要らないので消す
        com.giracle.android.notify.Notifier.cancelChannel(ctx, channelId)
        if (channel == null) runCatching { store.channels.value = store.channels.value + graph.api.channelInfo(channelId) }
        try {
            store.openChannel(channelId)
        } catch (e: Exception) {
            // キャッシュを出しているときは画面の中央に案内が出ないので、取り直せなかったことを知らせる (D-047)
            if (store.history(channelId).value.loaded) ctx.toast("最新のメッセージを取得できませんでした: ${e.userMessage()}")
            else error = e.userMessage()
        }
    }
    DisposableEffect(channelId) { onDispose { store.leaveChannel(channelId) } }

    // 添付を他のアプリへ共有する (#50)。取ってくる間に画面を離れても続ける
    fun shareFiles(files: List<FileAttached>) {
        val act = ctx
        graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                com.giracle.android.ui.common.ShareFiles.share(act, graph, files)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                act.applicationContext.toast("共有できませんでした: ${e.userMessage()}")
            }
        }
    }

    // ---- 指定メッセージへ移動 ----
    suspend fun jump(messageId: String) {
        // 圏外などで取れないときは、落ちずに理由を出す (以前は通知から開いたときに圏外だとアプリが落ちていた)
        val ok = try {
            store.jumpToMessage(channelId, messageId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.toast(e.userMessage()); return
        }
        if (!ok) { ctx.toast("対象のメッセージを取得できませんでした。削除された可能性があります。"); return }
        val idx = store.history(channelId).value.messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            highlight = messageId
            delay(2500)
            highlight = null
        }
    }
    LaunchedEffect(jumpTo) { if (jumpTo != null) jump(jumpTo) }

    // ---- 初回表示位置: 新着線の少し下 (未読の始まりが見えるように) ----
    // キャッシュ (切れていた間の投稿が無い) では未読の始まりが分からないので、取り直した後に合わせる (D-047)。
    // キャッシュを見ている間に利用者がスクロールしていたら、その位置を優先して動かさない
    var positioned by remember(channelId) { mutableStateOf(jumpTo != null) }
    val ready = history.loaded && !history.fromCache
    LaunchedEffect(ready) {
        if (!ready || positioned) return@LaunchedEffect
        positioned = true
        if (listState.firstVisibleItemIndex > 0) return@LaunchedEffect
        val before = readBefore[channelId]
        if (before.isNullOrEmpty()) return@LaunchedEffect
        val firstUnread = history.messages.indexOfLast { ChatStore.isAfter(it.createdAt, before) }
        if (firstUnread > 2) listState.scrollToItem(firstUnread - 2)
    }

    // ---- 未読の始まりへ戻る (docs/UI_PRINCIPLES.md の C-5) ----
    // 未読の始まり (開いた時点の既読より新しい、いちばん古いメッセージ) の、一覧での位置。分からなければ -1。
    // 分からないのは: 既読側のメッセージを 1 件も読み込んでいない (未読の始まりはもっと古い側にある。通知から飛んだ後・「最新へ」の後など) とき、
    // キャッシュを出している (切れていた間の投稿が無い。D-047) とき
    val unreadIndex by remember(channelId) {
        derivedStateOf {
            val before = readBefore[channelId]
            if (before.isNullOrEmpty() || history.fromCache) -1
            else {
                val msgs = history.messages
                val i = msgs.indexOfLast { ChatStore.isAfter(it.createdAt, before) }
                when {
                    i < 0 -> -1
                    i == msgs.lastIndex && !history.atTop -> -1
                    // 一覧の先頭に「新しい側を読み込み中」の行があると、位置が 1 つずれる
                    else -> i + (if (history.atEnd) 0 else 1)
                }
            }
        }
    }
    // 一度画面に出たか。出た後は、上 (古い側) へ読み進めて未読の始まりが下に隠れたときだけ出す
    var unreadSeen by remember(channelId) { mutableStateOf(false) }
    val unreadPlace by remember(channelId) {
        derivedStateOf {
            val i = unreadIndex
            val visible = listState.layoutInfo.visibleItemsInfo
            when {
                i < 0 || visible.isEmpty() -> UnreadPlace.None
                visible.any { it.index == i } -> UnreadPlace.Visible
                i < visible.first().index -> UnreadPlace.Below
                else -> UnreadPlace.Above
            }
        }
    }
    LaunchedEffect(unreadPlace) { if (unreadPlace == UnreadPlace.Visible) unreadSeen = true }

    // ---- ページング: 端に近づいたら続きを読む ----
    LaunchedEffect(channelId) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            Triple(first, last, info.totalItemsCount)
        }.distinctUntilChanged().collect { (first, last, total) ->
            val s = store.history(channelId).value
            if (!s.loaded) return@collect
            try {
                if (!s.atTop && total > 0 && last >= total - 8) store.loadOlder(channelId)
                if (!s.atEnd && first <= 3) store.loadNewer(channelId)
            } catch (e: Exception) {
                error = e.userMessage()
            }
        }
    }

    // ---- 既読: 最新を表示しているとき ----
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 200 } }
    LaunchedEffect(channelId) {
        snapshotFlow { atBottom to store.history(channelId).value.messages.firstOrNull()?.id }
            .filter { it.first }
            .collect { store.markRead(channelId) }
    }
    // 自分の送信・新着で最下部にいれば追従する
    val newestId = history.messages.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1 && history.atEnd) listState.animateScrollToItem(0)
    }

    // ---- 自分の送信: 読んでいる位置に関係なく、送ったものが見えるところへ送る (#58、D-051) ----
    // Element X と同じ考え方 (docs/RESEARCH.md)。送ったものが画面の外にあると、送れたかを確かめるためにスクロールし直すことになる。
    // 一覧に載るのを待ってから動かす。載る前に動かすと、LazyColumn が今の先頭の行 (キー) に位置を合わせ直し、送ったものが画面の外に残る
    var sentId by remember(channelId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sentId, history.version) {
        // 仮のもの (D-071) が、動かす前に本物へ置き換わっていたら、本物を探す
        val id = sentId?.let(store::resolveSent) ?: return@LaunchedEffect
        val i = history.messages.indexOfFirst { it.id == id }
        if (i < 0) return@LaunchedEffect
        sentId = null
        // 一覧の先頭に「新しい側を読み込み中」の行があると、位置が 1 つずれる
        val target = i + (if (history.atEnd) 0 else 1)
        // 遠ければ一気に、近ければ動かして (Element X と同じく 10 行を境にする)
        val jump = kotlin.math.abs(listState.firstVisibleItemIndex - target) > 10
        // 送信が見えるまでの内訳の記録 (#70)。e2e の reply がこれを読む
        SendTrace.mark("in_list", "id=$id index=$i first=${listState.firstVisibleItemIndex} target=$target jump=$jump")
        try {
            if (jump) listState.scrollToItem(target) else listState.animateScrollToItem(target)
            SendTrace.mark("scroll_done", "first=${listState.firstVisibleItemIndex}")
            withFrameNanos { }
            SendTrace.mark("frame", "first=${listState.firstVisibleItemIndex}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            SendTrace.mark("scroll_cancelled", "first=${listState.firstVisibleItemIndex}")
            throw e
        }
    }
    val onSent: (String) -> Unit = { id ->
        sentId = id
        // 最新側を読み込んでいないときと、キャッシュを出しているとき (取り直しに失敗した後。D-047) は、
        // 送ったものは一覧に載らない (addMessage が足さない)。最新を読み込み直す。
        // 読み込み直さないと、後の再同期で載ったときに、そのとき読んでいる位置から急に動いてしまう (レビューの指摘)
        val h = store.history(channelId).value
        if (!h.atEnd || h.fromCache) scope.launch {
            try {
                store.jumpToLatest(channelId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                sentId = null
                ctx.toast(e.userMessage())
            }
        }
    }

    // メンションされたメッセージを表示したら通知を既読にする (Web 版 MentionReadWrapper)
    LaunchedEffect(channelId, inbox, history.version) {
        val ids = history.messages.mapTo(HashSet()) { it.id }
        inbox.filter { it.message.channelId == channelId && it.message.id in ids }.forEach { store.readInbox(it.message.id) }
    }

    // すべらせて返信できるのは、参加しているチャンネルだけ (入力欄があるとき)
    val cb = remember(channelId, joined) {
        MessageCallbacks(
            onLongPress = { actionsFor = it },
            onUserClick = { userSheet = it },
            onChannelClick = { nav.openChannel(it) },
            onMessageLink = { ch, id -> if (ch == channelId) scope.launch { jump(id) } else nav.openChannel(ch, id) },
            onToggleReaction = { m, code -> scope.launch { runCatching { store.toggleReaction(m, code) }.onFailure { ctx.toast(it.userMessage()) } } },
            onReactionLongPress = { m, code -> whoReacted = m to code },
            onImageClick = { viewing = it },
            onFileDownload = { f -> download(ctx, graph.api.fileUrl(f.id), f, graph.prefs.token) },
            onExpand = { expanded = it },
            onSwipeReply = if (joined) { m -> replyTo = m; editing = null } else null,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                title = {
                    Column(Modifier.clickable { nav.push(Screen.ChannelSettings(channelId)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (channel?.viewableRole?.isNotEmpty() == true) Icons.Default.Lock else AppIcons.Tag, null, Modifier.size(18.dp))
                            Text(channel?.name ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        }
                        if (!channel?.description.isNullOrBlank()) {
                            Text(channel.description, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { runCatching { store.setMuted(channelId, channelId !in muted) }.onFailure { ctx.toast(it.userMessage()) } } }) {
                        if (channelId in muted) Icon(AppIcons.NotificationsOff, "ミュート解除") else Icon(Icons.Default.Notifications, "ミュート")
                    }
                    IconButton(onClick = { nav.push(Screen.Search(channelId = channelId)) }) { Icon(Icons.Default.Search, "このチャンネルを検索") }
                    IconButton(onClick = { nav.push(Screen.ChannelSettings(channelId)) }) { Icon(Icons.Default.Info, "チャンネル情報") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!history.loaded) {
                    if (error != null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!)
                        TextButton(onClick = { error = null; scope.launch { runCatching { store.openChannel(channelId) }.onFailure { error = it.userMessage() } } }) { Text("再試行") }
                    } else CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    val messages = history.messages
                    val before = readBefore[channelId]
                    LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag(TestTags.CHANNEL_MESSAGES)) {
                        if (!history.atEnd) item(key = "newer", contentType = "loader") { Loader() }
                        itemsIndexed(messages, key = { _, m -> m.id }, contentType = { _, m -> if (m.isSystemMessage) "sys" else "msg" }) { i, m ->
                            val older = messages.getOrNull(i + 1)
                            val newer = messages.getOrNull(i - 1)
                            // 新着線: この行が既読最後で、より新しい行がある
                            val newLine = before != null && before.isNotEmpty() && newer != null && m.createdAt == before && history.atEnd
                            MessageItem(
                                m = m,
                                showHeader = !sameSender(m, older) || m.replyingMessageId != null,
                                showDate = older == null || !com.giracle.android.ui.common.sameDay(m.createdAt, older.createdAt),
                                newLineBelow = newLine,
                                highlighted = m.id == highlight,
                                gap = settings.messageGapLevel,
                                cb = cb,
                            )
                        }
                        item(key = "top", contentType = "loader") {
                            if (history.atTop) Text("履歴の末端まで到達しました。", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            else Loader()
                        }
                    }
                    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 10 } }
                    // 未読の始まりが下に隠れたとき (上へ読み進めた後) と、まだ一度も見ていないのに画面の外にあるとき
                    val showUnread = unreadPlace == UnreadPlace.Below || (unreadPlace == UnreadPlace.Above && !unreadSeen)
                    Column(
                        // 押せる範囲は見た目の周り 4dp まで (SmallFab48) なので、余白と間隔を 4dp ずつ減らして見た目の位置を保つ
                        Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (showUnread) {
                            SmallFab48(
                                onClick = {
                                    // 開いたときと同じ位置 (未読の始まりの少し下まで見える) へ
                                    scope.launch { listState.scrollToItem((unreadIndex - 2).coerceAtLeast(0)) }
                                },
                                modifier = Modifier.testTag(TestTags.CHANNEL_JUMP_UNREAD),
                            ) { Text("未読の始まりへ", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge) }
                        }
                        if (!history.atEnd || showJump) {
                            SmallFab48(
                                onClick = {
                                    scope.launch {
                                        if (!store.history(channelId).value.atEnd) runCatching { store.jumpToLatest(channelId) }
                                        listState.scrollToItem(0)
                                    }
                                },
                                modifier = Modifier.testTag(TestTags.CHANNEL_JUMP_LATEST),
                            ) { Icon(AppIcons.ArrowDown, "最新へ") }
                        }
                    }
                }
            }
            if (joined) {
                Box(Modifier.navigationBarsPadding()) {
                    Composer(channelId, replyTo, { replyTo = null }, editing, { editing = null }, onSent)
                }
            } else if (channel != null) {
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("チャンネルプレビュー中", Modifier.weight(1f))
                        Button(onClick = { scope.launch { runCatching { graph.api.joinChannel(channelId) }.onFailure { ctx.toast(it.userMessage()) } } }) { Text("参加する") }
                    }
                }
            }
        }
    }

    // ---- メッセージの操作 (長押し) ----
    actionsFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            val u = rememberUser(m.userId)
            Column(Modifier.exposeTestTags().testTag(TestTags.MESSAGE_ACTIONS).padding(bottom = 24.dp)) {
                Text("${u?.name ?: ""} ・ ${fullTime(m.createdAt)}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // よく使うリアクション
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val quick = remember { (RecentEmoji.get(ctx) + listOf("thumbsup", "heart", "joy", "eyes", "pray", "tada")).distinct().take(6) }
                    for (code in quick) Box(Modifier.size(44.dp).clickable { cb.onToggleReaction(m, code); RecentEmoji.push(ctx, code); actionsFor = null }, contentAlignment = Alignment.Center) { EmojiView(code, 28.dp) }
                    Box(Modifier.size(44.dp).testTag(TestTags.MESSAGE_ACTIONS_EMOJI_PICKER).clickable { pickerFor = m; actionsFor = null }, contentAlignment = Alignment.Center) { Icon(AppIcons.Mood, "絵文字を選ぶ") }
                }
                HorizontalDivider()
                if (joined) Action(AppIcons.Reply, "返信", tag = TestTags.MESSAGE_ACTIONS_REPLY) { replyTo = m; editing = null; actionsFor = null }
                Action(AppIcons.Link, "リンクをコピー") {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("link", "&<${m.channelId}:${m.id}>"))) }
                    ctx.toast("リンクをコピーしました"); actionsFor = null
                }
                Action(AppIcons.Copy, "本文をコピー") {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", store.plainText(m.content)))) }
                    actionsFor = null
                }
                if (m.files.isNotEmpty()) Action(Icons.Default.Share, if (m.files.size == 1) "添付を共有" else "添付 (${m.files.size} 件) を共有") {
                    shareFiles(m.files); actionsFor = null
                }
                if (m.userId == me?.id) Action(Icons.Default.Edit, "編集") { editing = m; replyTo = null; actionsFor = null }
                if (m.userId == me?.id || store.can("manageUser")) Action(Icons.Default.Delete, "削除", danger = true) { confirmDelete = m; actionsFor = null }
            }
        }
    }
    pickerFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { pickerFor = null }) {
            EmojiPicker(Modifier.exposeTestTags()) { code ->
                val mine = m.reactionSummary.count { it.includingYou }
                val already = m.reactionSummary.any { it.emojiCode == code && it.includingYou }
                if (!already && mine >= 10) ctx.toast("同一ユーザーによるリアクションは10個までです。")
                else cb.onToggleReaction(m, code)
                pickerFor = null
            }
        }
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("メッセージを削除しますか？") },
            text = { Text(store.plainText(m.content), maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { runCatching { graph.api.delete(m.id) }.onFailure { ctx.toast(it.userMessage()) } }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
    whoReacted?.let { (m, code) -> WhoReactedDialog(m, code, onUser = { userSheet = it; whoReacted = null }) { whoReacted = null } }
    viewing?.let { f ->
        ImageViewer(f, graph.api.fileUrl(f.id), onDismiss = { viewing = null }, onSave = { download(ctx, graph.api.fileUrl(f.id), f, graph.prefs.token) }, onShare = { shareFiles(listOf(f)) })
    }
    expanded?.let { m ->
        Dialog(onDismissRequest = { expanded = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().exposeTestTags().testTag(TestTags.LONG_TEXT_DIALOG).padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(m.userId, 32.dp)
                        Text(rememberUser(m.userId)?.name ?: "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { expanded = null }) { Text("閉じる") }
                    }
                    Text(fullTime(m.createdAt), style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MessageText(m.content, me?.id, cb)
                }
            }
        }
    }
    userSheet?.let { UserSheet(it) { userSheet = null } }
}

@Composable
private fun Loader() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val c = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = c) },
        leadingContent = { Icon(icon, null, tint = c) },
        modifier = Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).clickable(onClick = onClick),
    )
}

@Composable
private fun WhoReactedDialog(m: Message, code: String, onUser: (String) -> Unit, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    var page by remember { mutableStateOf(1) }
    val ids by produceState<List<String>?>(null, page) {
        val got = runCatching { graph.api.whoReacted(m.id, code, page) }.getOrDefault(emptyList())
        value = (value ?: emptyList()) + got
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { EmojiView(code, 24.dp); Text(code, style = MaterialTheme.typography.titleSmall) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (ids == null) CircularProgressIndicator()
                ids?.distinct()?.forEach { id ->
                    val u = rememberUser(id)
                    Row(Modifier.fillMaxWidth().clickable { onUser(id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(id, 28.dp); Text(u?.name ?: "…")
                    }
                }
                val total = m.reactionSummary.firstOrNull { it.emojiCode == code }?.count ?: 0
                if ((ids?.size ?: 0) in 1 until total) TextButton(onClick = { page++ }) { Text("もっと見る") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/**
 * 画像の全画面表示。ピンチ・ダブルタップで拡大し、拡大中は画像の外まで動かせない。タップで閉じる。
 * 拡大しても粗くならないよう、画面の 2 倍 (長辺 4096px まで) の大きさで読み込む。上の「保存」で端末に保存する
 */
@Composable
fun ImageViewer(f: FileAttached, url: String, onDismiss: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val ctx = LocalContext.current
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // 画像そのものの大きさ (読み込めたら分かる)。画面に収めたとき (Fit) の表示の大きさを求めるのに使う
        var intrinsic by remember { mutableStateOf<androidx.compose.ui.geometry.Size?>(null) }
        // 拡大した分だけ動かせる。上限は「表示している画像の大きさ × 倍率 - 画面の大きさ」の半分。
        // 画面と縦横比が違う画像 (上下・左右に余白が出る) でも、画像の端より外へは動かない
        fun clamp(o: androidx.compose.ui.geometry.Offset, s: Float): androidx.compose.ui.geometry.Offset {
            val bw = box.width.toFloat()
            val bh = box.height.toFloat()
            val i = intrinsic
            val (dw, dh) = if (i != null && i.width > 0f && i.height > 0f) {
                val fit = minOf(bw / i.width, bh / i.height)
                i.width * fit to i.height * fit
            } else bw to bh
            val mx = ((dw * s - bw) / 2f).coerceAtLeast(0f)
            val my = ((dh * s - bh) / 2f).coerceAtLeast(0f)
            return androidx.compose.ui.geometry.Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        // 画面の大きさは最初から分かる値 (displayMetrics) を使い、開いた直後に読み直さないようにする
        val maxSide = remember {
            val dm = ctx.resources.displayMetrics
            (maxOf(dm.widthPixels, dm.heightPixels) * 2).coerceIn(1024, 4096)
        }
        // INEXACT: 元より大きくは読まない (小さい画像を 4096px に引き伸ばしてメモリを使わない)
        val req = remember(url, maxSide) {
            coil3.request.ImageRequest.Builder(ctx).data(url).size(maxSide).precision(coil3.size.Precision.INEXACT)
                // 一覧の小さい画像と、キャッシュを分ける (一覧が、ここで読んだ大きい画像を使い回してメモリを握り続けないように)
                .memoryCacheKey("$url#full").build()
        }
        Box(
            Modifier.fillMaxSize().exposeTestTags().testTag(TestTags.IMAGE_VIEWER).background(Color.Black)
                .onSizeChanged { box = it; offset = clamp(offset, scale) }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = { p ->
                            if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }
                            else {
                                // 押した所を中心に 2.5 倍にする
                                val s = 2.5f
                                val c = androidx.compose.ui.geometry.Offset(box.width / 2f, box.height / 2f)
                                scale = s
                                offset = clamp((c - p) * (s - 1f), s)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else clamp(offset + pan, scale)
                    }
                },
        ) {
            AsyncImage(
                model = req, contentDescription = f.actualFileName, contentScale = ContentScale.Fit,
                onSuccess = { st ->
                    intrinsic = st.painter.intrinsicSize
                    // 読み込む前に拡大していたら、画像の大きさが分かったところで動かせる幅に収め直す
                    offset = clamp(offset, scale)
                },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "共有", tint = Color.White) }
                IconButton(onClick = onSave) { Icon(AppIcons.Download, "保存", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "閉じる", tint = Color.White) }
            }
        }
    }
}

/** 添付ファイルを端末の「ダウンロード」へ保存する。認証 Cookie を付けて DownloadManager に任せる */
fun download(ctx: Context, url: String, f: FileAttached, token: String?) {
    try {
        val req = DownloadManager.Request(url.toUri())
            .setTitle(f.actualFileName)
            .setMimeType(f.type)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.actualFileName.replace('/', '_'))
        if (token != null) req.addRequestHeader("Cookie", "token=$token")
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        ctx.toast("ダウンロードを開始しました")
    } catch (e: Exception) {
        ctx.toast("ダウンロードできません: ${e.message}")
    }
}

/** 未読の始まりが、画面のどこにあるか (一覧は新しいものが下) */
private enum class UnreadPlace { None, Visible, Above, Below }
