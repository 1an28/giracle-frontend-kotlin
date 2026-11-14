package com.giracle.android.ui.channel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.ui.PendingShare
import com.giracle.android.ui.common.TestTags
import com.giracle.android.data.model.Message
import com.giracle.android.data.model.User
import com.giracle.android.data.net.UploadFile
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.fileSizeOf
import com.giracle.android.ui.common.humanSize
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.uploadFileFrom
import com.giracle.android.data.ImageShrinker
import com.giracle.android.data.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** 添付ファイル 1 つのアップロード状況 */
@Stable
class Attachment(val uri: Uri, val name: String, val size: Long) {
    var progress by mutableStateOf(0f)
    var fileId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** 電波が無くて失敗した (つながったら自動でやり直す。#36) */
    var waitingNetwork by mutableStateOf(false)
    var job: Job? = null
}

/**
 * チャンネルごとの下書き。打っている間はメモリに持ち、少し間を置いて端末に保存する
 * (アプリのプロセスが終わっても残る。ログアウトで消す)。
 */
object Drafts {
    private val mem = HashMap<String, String>()
    // 手元に持たない。Android が Context ごとに覚えている (持つと、テストで作り直した Application の前のものを使い続ける)
    private fun prefs(ctx: android.content.Context) =
        ctx.applicationContext.getSharedPreferences("drafts", android.content.Context.MODE_PRIVATE)

    fun get(ctx: android.content.Context, channelId: String): String = mem[channelId] ?: prefs(ctx).getString(channelId, null).orEmpty()

    fun set(channelId: String, text: String) { mem[channelId] = text }

    /** メモリの内容を端末に書く */
    fun persist(ctx: android.content.Context, channelId: String) {
        val t = mem[channelId] ?: return
        prefs(ctx).edit().apply { if (t.isBlank()) remove(channelId) else putString(channelId, t) }.apply()
    }

    /**
     * 送信を始めた。入力欄を空にしたのに合わせて、送る本文を下書きから外す。
     * 送信中に打った文は、この後の [set] で入る。
     */
    fun startSend(channelId: String) { mem[channelId] = "" }

    /**
     * 送れた (または送信待ちに入れた)。送った本文は下書きに戻さず、送信中に打った文だけを残す (#79)。
     * 入力欄 (画面) ではなく下書きの記録を見るので、送信中にチャンネルを離れていても打った文が残る。
     */
    fun finishSend(ctx: android.content.Context, channelId: String) {
        val t = mem[channelId]
        if (t.isNullOrBlank()) remove(ctx, channelId) else persist(ctx, channelId)
    }

    /** 送れなかった本文 (と添付) を、見えている入力欄に戻すための知らせ */
    class Restored(val channelId: String, val text: String, val attachments: List<Attachment>)

    private val _restored = kotlinx.coroutines.flow.MutableSharedFlow<Restored>(extraBufferCapacity = 8)
    /** [failSend] の知らせ。開いている入力欄が受け取る (開いていなければ、次に開いたとき下書きから出る) */
    val restored: kotlinx.coroutines.flow.SharedFlow<Restored> = _restored

    /**
     * 送れなかった (電波以外の失敗)。送った本文を下書きの前に戻し、送信中に打った文は後ろに残す。
     * 入力欄 (画面) ではなく下書きの記録に戻すので、送信中に編集に入っていても (#97)、
     * チャンネルを開き直して入力欄が作り直されていても (#89)、戻す先を間違えない。
     */
    fun failSend(ctx: android.content.Context, channelId: String, text: String, attachments: List<Attachment>) {
        set(channelId, joinRestored(text, get(ctx, channelId)))
        persist(ctx, channelId)
        _restored.tryEmit(Restored(channelId, text, attachments))
    }

    /** 送れなかった本文 [kept] を、今の下書き [cur] の前に置く */
    fun joinRestored(kept: String, cur: String): String = if (cur.isBlank()) kept else "$kept\n$cur"

    fun remove(ctx: android.content.Context, channelId: String) {
        mem.remove(channelId)
        prefs(ctx).edit().remove(channelId).apply()
    }

    fun clear(ctx: android.content.Context) {
        mem.clear()
        prefs(ctx).edit().clear().apply()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    channelId: String,
    replyTo: Message?,
    onCancelReply: () -> Unit,
    editing: Message?,
    onCancelEdit: () -> Unit,
    /** 送れた (サーバーが受け取った) メッセージの ID を知らせる。画面がそこまで送る (#58) */
    onSent: (String) -> Unit = {},
) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    /** 画面より長く生きる処理 (送信) で使う。Activity を抱え込まないため */
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val server by store.serverConfig.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    // 入力欄の状態。キーボードからの画像の挿入 (contentReceiver) を受けるため、TextFieldState を使う (D-020)
    val state = remember(channelId) { TextFieldState(Drafts.get(ctx, channelId)) }
    val attachments = remember(channelId) { mutableStateListOf<Attachment>() }
    var sending by remember { mutableStateOf(false) }
    // 送信はアプリの scope で続くので、終わったときに最新の知らせ先を呼ぶ
    val latestOnSent by rememberUpdatedState(onSent)

    // 編集の間、入力欄は編集している本文になる。抜けたら (保存・取り消し・返信への切り替え) 下書きに戻す (#92)。
    // 戻すのと下書きの記録を同じ効果にまとめ、戻す前に編集の本文で下書きを上書きしないようにする
    val wasEditing = remember(state) { booleanArrayOf(false) }
    LaunchedEffect(state, editing?.id) {
        if (editing != null) {
            wasEditing[0] = true
            state.setTextAndPlaceCursorAtEnd(editing.content)
            return@LaunchedEffect
        }
        if (wasEditing[0]) {
            wasEditing[0] = false
            state.setTextAndPlaceCursorAtEnd(Drafts.get(ctx, channelId))
        }
        snapshotFlow { state.text.toString() }.collectLatest {
            Drafts.set(channelId, it)
            delay(500) // 打つたびに書かないよう、手が止まってから保存する
            Drafts.persist(ctx, channelId)
        }
    }

    // 送れなかった本文を戻す (Drafts.failSend)。下書きの記録は戻してあるので、ここでは見えている入力欄に出すだけ。
    // 編集中は入力欄を触らない (編集を抜けたときに、戻した下書きが出る。#97)
    val latestEditing by rememberUpdatedState(editing)
    LaunchedEffect(state) {
        Drafts.restored.collect { r ->
            if (r.channelId != channelId) return@collect
            attachments.addAll(r.attachments)
            if (latestEditing != null) return@collect
            // 打った直後の文は、まだ下書きの記録に入っていないことがある (記録は入力欄の変化を後から受け取る) ので、入力欄から作る
            val restored = Drafts.joinRestored(r.text, state.text.toString())
            state.setTextAndPlaceCursorAtEnd(restored)
            Drafts.set(channelId, restored)
            Drafts.persist(appCtx, channelId)
        }
    }

    // ---- メンション候補 ----
    val mentionQuery by remember(state) { derivedStateOf { mentionQueryAt(TextFieldValue(state.text.toString(), state.selection)) } }
    var candidates by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(mentionQuery) {
        val q = mentionQuery?.second
        if (q == null) { candidates = emptyList(); return@LaunchedEffect }
        delay(200) // 入力ごとの API 呼び出しを避ける (Web 版と同じ 200ms)
        candidates = runCatching { graph.api.userList(length = 20, username = q, joinedChannel = channelId) }.getOrDefault(emptyList())
        candidates.forEach(store::putUser)
    }

    fun insertMention(u: User) {
        val (start, _) = mentionQuery ?: return
        val end = state.selection.start
        val token = "@<${u.id}> "
        state.edit {
            replace(start, end, token)
            selection = TextRange(start + token.length)
        }
        candidates = emptyList()
    }

    // ---- 添付 ----
    fun upload(a: Attachment) {
        a.job = scope.launch {
            try {
                var base = ctx.uploadFileFrom(a.uri)
                // 写真は送る前に縮める (#28、D-025)。縮めた方が小さいときだけ差し替える
                if (graph.prefs.settings.value.shrinkImages && ImageShrinker.isTarget(base.mime)) {
                    val shrunk = withContext(Dispatchers.Default) { ImageShrinker.shrink(ctx.contentResolver, a.uri, base.mime, a.size) }
                    if (shrunk != null) base = UploadFile(ImageShrinker.jpegName(base.name), "image/jpeg", shrunk.bytes.toRequestBody("image/jpeg".toMediaType()))
                }
                if (base.body.contentLength() >= server.messageMaxFileSize) {
                    a.error = "ファイルが大きすぎます (上限 ${humanSize(server.messageMaxFileSize)})"
                    return@launch
                }
                val counting = UploadFile(base.name, base.mime, ProgressBody(base.body) { a.progress = it })
                a.fileId = graph.api.uploadFile(channelId, counting)
                a.progress = 1f
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Outbox.isNetworkFailure(e)) {
                    a.waitingNetwork = true
                    a.error = "つながったら自動でやり直します"
                } else a.error = e.userMessage()
            }
        }
    }
    // 電波が無くて失敗した添付は、つながったら (WebSocket の接続) やり直す (#36)
    LaunchedEffect(attachments) {
        graph.ws.state.collect { st ->
            if (st == com.giracle.android.data.net.WsClient.State.Connected) {
                attachments.filter { it.waitingNetwork }.forEach { a ->
                    a.waitingNetwork = false
                    a.error = null
                    a.progress = 0f
                    upload(a)
                }
            }
        }
    }

    fun addUris(uris: List<Uri>) {
        for (u in uris) {
            val size = ctx.fileSizeOf(u)
            val f = ctx.uploadFileFrom(u)
            val a = Attachment(u, f.name, size)
            // 縮める写真は、縮めた後の大きさで上限を確かめる (upload の中)
            val willShrink = settings.shrinkImages && ImageShrinker.isTarget(f.mime)
            if (!willShrink && size >= 0 && size >= server.messageMaxFileSize) a.error = "ファイルが大きすぎます (上限 ${humanSize(server.messageMaxFileSize)})"
            else if (!com.giracle.android.ui.common.isUploadable(f.mime)) a.error = "この形式 (${f.mime}) はサーバーが受け付けません"
            attachments += a
            if (a.error == null) upload(a)
        }
    }
    // 他のアプリの「共有」から来たもの (送り先にこのチャンネルが選ばれた)。D-020
    val share = PendingShare.content
    val shareTarget = PendingShare.target
    LaunchedEffect(share, shareTarget, editing) {
        if (share == null || shareTarget != channelId || editing != null) return@LaunchedEffect
        PendingShare.content = null
        PendingShare.target = null
        if (share.uris.isNotEmpty()) addUris(share.uris)
        share.text?.takeIf { it.isNotBlank() }?.let { t ->
            val cur = state.text.toString()
            state.setTextAndPlaceCursorAtEnd(if (cur.isBlank()) t else "$cur\n$t")
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { addUris(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    var attachMenu by remember { mutableStateOf(false) }

    // 電波待ちの添付があるときも送らせない (添付が抜けたまま送られないように。外せば送れる)
    val uploading = attachments.any { (it.fileId == null && it.error == null) || it.waitingNetwork }
    val text = state.text.toString()
    val tooLong = text.length > server.messageMaxLength
    val canSend = !sending && !uploading && !tooLong && (text.isNotBlank() || attachments.any { it.fileId != null })

    fun submit() {
        if (!canSend) return
        val body = text
        if (editing != null) {
            sending = true
            // 送信と同じく、画面を離れても取り消さない (下の submit の説明)
            graph.scope.launch(Dispatchers.Main.immediate) {
                try {
                    graph.api.edit(editing.id, body)
                    onCancelEdit() // 入力欄は、編集を抜けたときに下書きへ戻る (上の LaunchedEffect)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) { appCtx.toast(e.userMessage()) } finally { sending = false }
            }
            return
        }
        com.giracle.android.data.SendTrace.mark("tap")
        com.giracle.android.data.SendTrace.profileAfterTap()
        val ids = attachments.mapNotNull { it.fileId }
        val reply = replyTo?.id
        // 送信前に入力欄を空にして、すぐ次を打てるようにする (失敗したら戻す)
        val keep = body
        val keepAttach = attachments.toList()
        state.clearText()
        Drafts.startSend(channelId)
        attachments.clear()
        onCancelReply()
        sending = true
        // 送信はアプリの scope で行い、送った直後に画面を離れても取り消さない。
        // 以前は画面の scope で送っており、送ってすぐ「戻る」と送信が取り消され、入力欄も空にした後なので本文が消えていた
        // 送信中にログアウトしたら、後始末 (送信待ち・下書き・ショートカットへの書き込み) をしない。
        // 前のアカウントの本文が残り、次のアカウントで送られるのを防ぐ (ログアウトでストアが作り直される)
        val sessionStore = graph.store
        // 送信待ちに入れるときのユーザー。押した時点で読む (失敗の後だと、ログアウトで消えていることがある、#74)
        val sessionUser = sessionStore.me.value?.id ?: graph.prefs.userId
        graph.scope.launch(Dispatchers.Main.immediate) {
            try {
                val sent = store.send(channelId, body, ids, reply)
                com.giracle.android.data.SendTrace.mark("http_done", "id=${sent.id}")
                if (graph.store !== sessionStore) return@launch
                latestOnSent(sent.id)
                // 共有シート・ランチャーに、このチャンネルを直接の送り先として出す (#25)
                com.giracle.android.ui.ChannelShortcuts.reportSent(appCtx, channelId, store.channels.value.firstOrNull { it.id == channelId }?.name)
                // 送信中に次の文を打ち始めていたら、それは下書きとして残す (#79)
                Drafts.finishSend(appCtx, channelId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (graph.store !== sessionStore) return@launch
                if (Outbox.isNetworkFailure(e)) {
                    // 電波が無いなど。送信待ちに入れ、つながったら送る (#30)
                    graph.outbox.add(channelId, body, ids, reply, userId = sessionUser)
                    Drafts.finishSend(appCtx, channelId)
                    appCtx.toast("送信待ちにしました。つながったら送ります")
                } else {
                    // 下書きに戻す。送信中に次の文を打ち始めていたら、消さずに後ろへ残す。開いている入力欄にも出る
                    Drafts.failSend(appCtx, channelId, keep, keepAttach)
                    appCtx.toast("送信できませんでした: ${e.userMessage()}")
                }
            } finally { sending = false }
        }
    }

    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            if (candidates.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(candidates, key = { it.id }) { u ->
                        Row(Modifier.fillMaxWidth().clickable { insertMention(u) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(u.id, 28.dp)
                            Text(u.displayName)
                        }
                    }
                }
                HorizontalDivider()
            }
            OutboxBar(channelId)
            if (editing != null) Banner(Icons.Default.Edit, "メッセージを編集中", onCancelEdit)
            replyTo?.let { r ->
                val u = rememberUser(r.userId)
                Banner(AppIcons.Reply, "${u?.name ?: ""} へ返信: ${store.plainText(r.content).replace('\n', ' ')}", onCancelReply, Modifier.testTag(TestTags.COMPOSER_REPLY))
            }
            if (attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in attachments) AttachmentChip(a) {
                        a.job?.cancel()
                        attachments.remove(a)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                if (editing == null) {
                    Column {
                        IconButton(onClick = { attachMenu = !attachMenu }) { Icon(AppIcons.AttachFile, "ファイルを添付") }
                        androidx.compose.material3.DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("写真・動画") }, leadingIcon = { Icon(AppIcons.Image, null) }, onClick = {
                                attachMenu = false
                                pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("ファイル") }, leadingIcon = { Icon(AppIcons.File, null) }, onClick = {
                                attachMenu = false
                                pickFile.launch(arrayOf("*/*"))
                            })
                        }
                    }
                }
                TextField(
                    state = state,
                    modifier = Modifier.weight(1f).heightIn(max = 180.dp).testTag(TestTags.COMPOSER_INPUT).onPreviewKeyEvent { e ->
                        // ハードウェアキーボード: Enter で送信 (設定 ON 時)、Shift+Enter で改行
                        if (settings.sendWithEnter && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) { submit(); true } else false
                    }.then(
                        // キーボード (Gboard など) の画像・GIF・ステッカーの挿入と、長押しメニューの「貼り付け」の画像を添付として受ける。
                        // 編集中は添付できないので受けない (文字の貼り付けはそのまま入力欄に入る)
                        if (editing == null) Modifier.contentReceiver { content -> receiveMedia(content) { addUris(it) } } else Modifier
                    ),
                    placeholder = { Text("メッセージを入力") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    supportingText = if (tooLong) ({ Text("${text.length} / ${server.messageMaxLength} 文字", color = MaterialTheme.colorScheme.error) }) else null,
                )
                IconButton(onClick = ::submit, enabled = canSend, modifier = Modifier.testTag(TestTags.COMPOSER_SEND)) {
                    if (sending || uploading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (editing != null) Icons.Default.Edit else Icons.AutoMirrored.Filled.Send, if (editing != null) "保存" else "送信")
                }
            }
        }
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp))
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "取り消す") }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    Row(
        Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            a.error != null -> Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            a.fileId == null -> CircularProgressIndicator(progress = { a.progress }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Icon(AppIcons.File, null, Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Text(a.error ?: if (a.size >= 0) humanSize(a.size) else "", style = MaterialTheme.typography.labelSmall, color = if (a.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "外す", Modifier.size(16.dp)) }
    }
}

/**
 * 入力欄に届いた内容 (キーボードの画像挿入・貼り付け) のうち、URI を持つもの (画像など) を取り出して [onUris] に渡す。
 * 文字だけのものは残して返し、入力欄にそのまま入れさせる。
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun receiveMedia(content: TransferableContent, onUris: (List<Uri>) -> Unit): TransferableContent? {
    val uris = ArrayList<Uri>()
    val rest = content.consume { item -> item.uri?.let { uris += it; true } ?: false }
    if (uris.isNotEmpty()) onUris(uris)
    return rest
}

/** カーソル直前の "@xxx" を探す。(開始位置, 検索語) を返す */
internal fun mentionQueryAt(v: TextFieldValue): Pair<Int, String>? {
    val cursor = v.selection.start
    if (cursor <= 0 || v.selection.start != v.selection.end) return null
    val before = v.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    val q = before.substring(at + 1)
    if (q.isEmpty() || q.any { it.isWhitespace() } || q.startsWith("<")) return null
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    return at to q
}

/** アップロードの進み具合を数える RequestBody */
private class ProgressBody(private val inner: okhttp3.RequestBody, private val onProgress: (Float) -> Unit) : okhttp3.RequestBody() {
    override fun contentType() = inner.contentType()
    override fun contentLength() = inner.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().toFloat()
        var written = 0L
        val fs = object : ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written / total).coerceAtMost(0.99f))
            }
        }.buffer()
        inner.writeTo(fs)
        fs.flush()
    }
}

/** このチャンネルの送信待ち (#30)。電波が戻ったら自動で送る。断られたものは理由を出す */
@Composable
private fun OutboxBar(channelId: String) {
    val graph = LocalGraph.current
    val all by graph.outbox.items.collectAsStateWithLifecycle()
    val me by LocalStore.current.me.collectAsStateWithLifecycle()
    // 前のアカウントで入れたものは出さない (送るときにも捨てる、#74)
    val items = remember(all, channelId, me) { all.filter { it.channelId == channelId && (it.userId == null || me == null || it.userId == me?.id) } }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(TestTags.COMPOSER_OUTBOX)) {
        for (item in items) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (item.error == null) "送信待ち" else "送れませんでした: ${item.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.text.replace('\n', ' ').ifBlank { "(添付ファイル ${item.fileIds.size} 件)" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = { graph.flushOutbox(item.localId) }) { Text("今すぐ送る") }
                // 画面を離れても取り消しは保存し終える (アプリの scope)
                TextButton(onClick = { graph.scope.launch { graph.outbox.remove(item.localId) } }) { Text("取り消す") }
            }
        }
        HorizontalDivider()
    }
}
