package com.giracle.android.ui.channel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import com.giracle.android.data.net.WsClient
import kotlinx.coroutines.flow.StateFlow
import coil3.compose.rememberAsyncImagePainter
import com.giracle.android.ui.common.ImageFailure
import com.giracle.android.ui.common.ImageFailureMark
import com.giracle.android.ui.common.RetryOnReconnect
import com.giracle.android.ui.common.imageFailureOf
import coil3.request.ImageRequest
import com.giracle.android.ui.common.TestTags
import com.giracle.android.data.ChatStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.giracle.android.data.Emojis
import com.giracle.android.data.MessageParser
import com.giracle.android.data.Segment
import com.giracle.android.data.model.FileAttached
import com.giracle.android.data.model.ImageSize
import com.giracle.android.data.model.Message
import com.giracle.android.data.model.SystemMessageBody
import com.giracle.android.data.model.UrlPreview
import com.giracle.android.data.net.AppJson
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.PressArea
import androidx.compose.ui.layout.layout
import com.giracle.android.ui.common.dayLabel
import com.giracle.android.ui.common.friendlyTime
import com.giracle.android.ui.common.humanSize
import com.giracle.android.ui.common.rememberUser

/** メッセージ 1 件分の操作 (画面側から渡す) */
class MessageCallbacks(
    val onLongPress: (Message) -> Unit,
    val onUserClick: (String) -> Unit,
    val onChannelClick: (String) -> Unit,
    val onMessageLink: (String, String) -> Unit,
    val onToggleReaction: (Message, String) -> Unit,
    val onReactionLongPress: (Message, String) -> Unit,
    val onImageClick: (FileAttached) -> Unit,
    val onFileDownload: (FileAttached) -> Unit,
    val onExpand: (Message) -> Unit,
    /** 右へすべらせて返信する (D-043)。null なら (チャンネルに参加していないなど) すべらせない */
    val onSwipeReply: ((Message) -> Unit)? = null,
)

/**
 * 履歴の 1 行。日付線・返信元・本文・添付・リアクション・新着線を含む。
 * 引数はすべて不変値なので、内容が変わらない行は再描画されない。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    m: Message,
    showHeader: Boolean,
    showDate: Boolean,
    newLineBelow: Boolean,
    highlighted: Boolean,
    gap: Int,
    cb: MessageCallbacks,
) {
    Column(Modifier.fillMaxWidth().padding(top = (gap * 2).dp)) {
        if (showDate) DateLine(m.createdAt)
        if (m.isSystemMessage) {
            SystemMessage(m, cb)
        } else {
            m.replyingMessageId?.let { ReplyPreview(it, m.channelId, cb) }
            // 送信中の仮のもの (D-071) は、サーバーに無い id なので操作 (返信・リアクション・編集など) を受けない
            SwipeToReply(enabled = cb.onSwipeReply != null && !m.pending, onReply = { cb.onSwipeReply?.invoke(m) }) {
            Row(
                Modifier.fillMaxWidth()
                    .testTag(TestTags.message(m.id))
                    .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (m.pending) Modifier.alpha(0.6f) else Modifier)
                    .combinedClickable(onClick = {}, onLongClick = { if (!m.pending) cb.onLongPress(m) })
                    .padding(start = 12.dp, end = 12.dp, top = if (showHeader) 8.dp else 1.dp, bottom = 2.dp),
            ) {
                Box(Modifier.width(44.dp)) {
                    if (showHeader) Box(Modifier.clickable { cb.onUserClick(m.userId) }) { Avatar(m.userId, 36.dp) }
                }
                Column(Modifier.weight(1f)) {
                    if (showHeader) Header(m, cb)
                    Body(m, cb)
                }
            }
            }
        }
        if (newLineBelow) NewLine()
    }
}

@Composable
private fun Header(m: Message, cb: MessageCallbacks) {
    val u = rememberUser(m.userId)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            u?.name ?: "…", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clickable { cb.onUserClick(m.userId) },
        )
        if (u?.isBot == true) Badge("BOT")
        // 送信中は時刻の代わりに印を出す (高さを変えないため、同じ場所・同じ文字の大きさ)
        Text(if (m.pending) "送信中…" else friendlyTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Body(m: Message, cb: MessageCallbacks) {
    val store = LocalStore.current
    val me by store.me.collectAsStateWithLifecycle()
    if (m.content.isNotEmpty()) {
        if (MessageParser.isLong(m.content)) {
            LongText(m, me?.id, cb)
        } else {
            MessageText(m.content, me?.id, cb)
        }
    }
    for (link in remember(m.content) { MessageParser.messageLinks(m.content) }) {
        MessageLinkPreview(link.channelId, link.messageId, cb)
    }
    for (p in m.urlPreviews) UrlPreviewCard(p)
    if (m.files.isNotEmpty()) Files(m.files, cb)
    if (m.isEdited) Text("編集済み", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (m.reactionSummary.isNotEmpty()) Reactions(m, cb)
}

@Composable
private fun LongText(m: Message, myId: String?, cb: MessageCallbacks) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(10.dp),
    ) {
        // Web 版同様、先頭だけ見せて全文は別画面で開く
        val preview = if (m.content.count { it == '\n' } > 5) m.content.take(10) + "…" else m.content.take(100) + "…"
        MessageText(preview, myId, cb)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { cb.onExpand(m) }, modifier = Modifier.testTag(TestTags.expandLongText(m.id))) { Text("長文メッセージを展開する") }
            Spacer(Modifier.weight(1f))
            if (myId != null && MessageParser.mentions(m.content, myId)) Badge("@メンション", strong = true)
            Badge("${m.content.length}文字")
        }
    }
}

/**
 * 本文。URL・メンション・チャンネル・メッセージリンク・インラインコードを AnnotatedString にする。
 * 解析結果はキャッシュされ、名前の解決はこの行で使う分だけ購読する。
 */
@Composable
fun MessageText(content: String, myId: String?, cb: MessageCallbacks, maxLines: Int = Int.MAX_VALUE) {
    val store = LocalStore.current
    val segments = remember(content) { MessageParser.parse(content) }
    // この本文に出てくる人の名前だけを購読する (他人の情報更新で再描画しない)
    val mentionIds = remember(segments) { segments.filterIsInstance<Segment.Mention>().map { it.userId }.distinct() }
    val namesFlow = remember(store, mentionIds) {
        mentionIds.forEach(store::ensureUser)
        store.users.map { m -> mentionIds.associateWith { m[it]?.name } }.distinctUntilChanged()
    }
    val names by namesFlow.collectAsStateWithLifecycle(mentionIds.associateWith { store.users.value[it]?.name })
    val hasChannelRef = remember(segments) { segments.any { it is Segment.ChannelRef || it is Segment.MessageLink } }
    val channelsFlow = remember(store, hasChannelRef) { if (hasChannelRef) store.channels else kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }
    val channels by channelsFlow.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    val text: AnnotatedString = remember(segments, names, channels, myId, colors) {
        buildAnnotatedString {
            for (s in segments) when (s) {
                is Segment.Text -> append(s.text)
                is Segment.Link -> withLink(LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = colors.tertiary, textDecoration = TextDecoration.Underline)))) { append(s.url) }
                is Segment.Mention -> {
                    val isMe = s.userId == myId
                    val name = names[s.userId] ?: s.userId.take(8)
                    withLink(LinkAnnotation.Clickable("u:${s.userId}", TextLinkStyles(SpanStyle(background = if (isMe) colors.primary else colors.surfaceVariant, color = if (isMe) colors.onPrimary else colors.onSurface, fontWeight = FontWeight.Medium))) { cb.onUserClick(s.userId) }) {
                        append("@$name")
                    }
                }
                is Segment.ChannelRef -> {
                    val name = channels.firstOrNull { it.id == s.channelId }?.name ?: "不明なチャンネル"
                    withLink(LinkAnnotation.Clickable("c:${s.channelId}", TextLinkStyles(SpanStyle(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))) { cb.onChannelClick(s.channelId) }) {
                        append("#$name")
                    }
                }
                is Segment.MessageLink -> {
                    val name = channels.firstOrNull { it.id == s.channelId }?.name ?: "チャンネル"
                    withLink(LinkAnnotation.Clickable("m:${s.messageId}", TextLinkStyles(SpanStyle(color = colors.tertiary))) { cb.onMessageLink(s.channelId, s.messageId) }) {
                        append("#$name のメッセージ")
                    }
                }
                is Segment.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)) { append(s.code) }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DateLine(iso: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(dayLabel(iso), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun NewLine() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        Text("新着", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SystemMessage(m: Message, cb: MessageCallbacks) {
    val body = remember(m.content) { runCatching { AppJson.decodeFromString(SystemMessageBody.serializer(), m.content) }.getOrNull() }
    val u = body?.targetUserId?.let { rememberUser(it) }
    val name = u?.name ?: "誰か"
    val text = when (body?.messageTerm) {
        "WELCOME" -> "さんが参加しました！ようこそ"
        "CHANNEL_JOIN" -> "さんがチャンネルへ参加しました！"
        "CHANNEL_LEFT" -> "さんがチャンネルを退出しました。"
        "CHANNEL_INVITED" -> "さんが招待されました！"
        "CHANNEL_KICKED" -> "さんがチャンネルからキックされました。"
        else -> " (システムメッセージ)"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { body?.targetUserId?.let(cb.onUserClick) })
        Text(text, Modifier.weight(1f))
        Text(friendlyTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 返信元メッセージの 1 行表示。履歴に無ければサーバーから取る */
@Composable
private fun ReplyPreview(replyId: String, channelId: String, cb: MessageCallbacks) {
    val target = rememberMessage(replyId, channelId)
    Row(
        Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 6.dp)
            .clickable { cb.onMessageLink(channelId, replyId) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(AppIcons.Reply, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (target == null) {
            Text("(読み込み中または削除されたメッセージ)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Avatar(target.userId, 16.dp)
            val u = rememberUser(target.userId)
            Text(u?.name ?: "", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(LocalStore.current.plainText(target.content).replace('\n', ' '), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ID からメッセージを得る。履歴内にあればそれを、無ければ API (結果はプロセス内でキャッシュ) */
@Composable
fun rememberMessage(messageId: String, channelId: String): Message? {
    val store = LocalStore.current
    val graph = LocalGraph.current
    val local = remember(messageId) { store.history(channelId).value.messages.firstOrNull { it.id == messageId } ?: MessageCache.get(messageId) }
    val m by produceState(local, messageId) {
        if (value == null) {
            value = runCatching { graph.api.message(messageId) }.getOrNull()?.also { MessageCache.put(it) }
        }
    }
    return m
}

object MessageCache {
    private val map = object : LinkedHashMap<String, Message>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Message>?) = size > 200
    }
    @Synchronized fun get(id: String) = map[id]
    @Synchronized fun put(m: Message) { map[m.id] = m }
}

/** メッセージリンク (&<ch:msg>) の埋め込み表示 */
@Composable
private fun MessageLinkPreview(channelId: String, messageId: String, cb: MessageCallbacks) {
    val target = rememberMessage(messageId, channelId)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable { cb.onMessageLink(channelId, messageId) }.padding(8.dp),
    ) {
        if (target == null) {
            Text("メッセージを読み込めません", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(target.userId, 20.dp)
                Text(rememberUser(target.userId)?.name ?: "", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(friendlyTime(target.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(LocalStore.current.plainText(target.content), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UrlPreviewCard(p: UrlPreview) {
    val graph = LocalGraph.current
    val uri = LocalUriHandler.current
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    var full by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(vertical = 4.dp).widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clickable { runCatching { uri.openUri(p.url) } },
    ) {
        val img = p.imageLink
        if (img != null && settings.autoLoadImages) {
            // 外部画像はサーバーのサムネイル中継 (url-thumbnail) を使う (Web 版と同じ。端末の IP を外部へ出さない)
            AsyncImage(
                model = graph.api.urlThumbnail(img), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(140.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.faviconLink != null && settings.autoLoadImages) {
                    AsyncImage(model = graph.api.urlThumbnail(p.faviconLink, favicon = true), contentDescription = null, modifier = Modifier.size(16.dp))
                } else Icon(AppIcons.Link, null, Modifier.size(16.dp))
                Text(p.title.ifBlank { p.url }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (p.videoLink != null) Icon(AppIcons.OpenInNew, "動画", Modifier.size(16.dp))
            }
            if (p.description.isNotBlank()) {
                val limit = settings.maxUrlPreviewTextLength
                val long = p.description.length > limit
                Text(if (long && !full) p.description.take(limit) + "…" else p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (long) TextButton(onClick = { full = !full }) { Text(if (full) "閉じる" else "全文を表示") }
            }
        }
    }
}

@Composable
private fun Files(files: List<FileAttached>, cb: MessageCallbacks) {
    val store = LocalStore.current
    val sizes by store.imageSizes.collectAsStateWithLifecycle()
    for (f in files) {
        if (f.isImage) ImageAttachment(f, sizes[f.id], cb) else FileCard(f, cb)
    }
}

@Composable
private fun ImageAttachment(f: FileAttached, size: ImageSize?, cb: MessageCallbacks) {
    val graph = LocalGraph.current
    val ctx = LocalContext.current
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    if (!settings.autoLoadImages) {
        FileCard(f, cb, onOpen = { cb.onImageClick(f) })
        return
    }
    // 縦横比が分かっていれば先に枠を確保し、読み込み後にレイアウトが跳ねないようにする
    val ratio = size?.let { (it.width.toFloat() / it.height.toFloat()).coerceIn(0.4f, 3f) }
    val maxPx = with(LocalDensity.current) { 320.dp.roundToPx() }
    val url = graph.api.fileUrl(f.id)
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(maxPx).precision(coil3.size.Precision.INEXACT).build() }
    AttachedImage(req, f.actualFileName, ratio, graph.ws.state, onOpen = { cb.onImageClick(f) }, tag = { TestTags.image(f.id, it) })
}

/**
 * 添付画像の枠。失敗したら枠の中に印を出し、押す・接続が戻ると出し直す。サーバーに無い (404) なら出し直さない (#129、D-076)。
 * 読み込みが済んだかを testTag ([tag]) で外から分かるようにする (画像の読み込みは画面の情報に出ないため。D-016、#20)。
 * [imageLoader] はテストで差し替える
 */
@Composable
internal fun AttachedImage(
    request: ImageRequest,
    contentDescription: String?,
    ratio: Float?,
    connection: StateFlow<WsClient.State>,
    onOpen: () -> Unit,
    tag: (loaded: Boolean) -> String,
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
) {
    val painter = rememberAsyncImagePainter(request, imageLoader, contentScale = ContentScale.Fit)
    val state by painter.state.collectAsStateWithLifecycle()
    val loaded = state is AsyncImagePainter.State.Success
    val failure = imageFailureOf(state)
    RetryOnReconnect(failure, connection) { painter.restart() }
    Box(
        Modifier.testTag(tag(loaded)).padding(vertical = 4.dp).widthIn(max = 320.dp).heightIn(max = 320.dp)
            .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.heightIn(min = 120.dp))
            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            // 表示できない形式 (Unsupported) は、全画面表示から保存・共有してほかのアプリで開けるよう、押したら開く
            .clickable(enabled = failure != ImageFailure.Gone) {
                if (failure == ImageFailure.Retryable) painter.restart() else onOpen()
            },
        contentAlignment = Alignment.Center,
    ) {
        // 縦横比が分かっていれば枠いっぱいに、分からなければ画像の大きさで描く
        Image(painter, contentDescription, if (ratio != null) Modifier.fillMaxSize() else Modifier, contentScale = ContentScale.Fit)
        ImageFailureMark(failure)
    }
}

@Composable
private fun FileCard(f: FileAttached, cb: MessageCallbacks, onOpen: (() -> Unit)? = null) {
    Row(
        Modifier.padding(vertical = 4.dp).widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable { onOpen?.invoke() ?: cb.onFileDownload(f) }.padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (f.isImage) AppIcons.Image else AppIcons.File, null, Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(f.actualFileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(humanSize(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { cb.onFileDownload(f) }) { Icon(AppIcons.Download, "ダウンロード") }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Reactions(m: Message, cb: MessageCallbacks) {
    // 見た目 (高さ 28dp) はそのままで、押せる範囲を 48dp にする。リアクションの列のすぐ下は次のメッセージの行で、
    // Compose の自動の広がりは下の行に取られるため (#68)。下側の 10dp は次の行と取り合わないために要るので、行はその分高くなる。
    // 上側は自分の本文に 6dp 重ねて、本文と見た目の間を前と同じ 4dp に保つ (本文は同じ行の中なので、重なった所はリアクションが取る)。
    // 2 行に折り返したときの見た目の縦の間は 4dp → 20dp になる
    FlowRow(
        Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val up = 6.dp.roundToPx()
            layout(p.width, (p.height - up).coerceAtLeast(0)) { p.place(0, -up) }
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (r in m.reactionSummary) {
            val mine = r.includingYou
            PressArea(
                onClick = { cb.onToggleReaction(m, r.emojiCode) },
                onLongClick = { cb.onReactionLongPress(m, r.emojiCode) },
                modifier = Modifier.testTag(TestTags.reaction(m.id, r.emojiCode)),
            ) { visual ->
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .then(visual)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EmojiView(r.emojiCode, 18.dp)
                    Text(r.count.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** ショートコードの絵文字を表示する。Unicode に無ければカスタム絵文字として画像を出す */
@Composable
fun EmojiView(code: String, size: androidx.compose.ui.unit.Dp) {
    val graph = LocalGraph.current
    val uni = Emojis.unicodeOf(code)
    if (uni != null && !Emojis.canDraw(uni)) {
        // 端末のフォントに無い新しい絵文字はコードで出す (□ を避ける)
        Text(":$code:", style = MaterialTheme.typography.labelSmall)
    } else if (uni != null) {
        Text(uni, fontSize = with(LocalDensity.current) { (size.toPx() * 0.8f).toSp() })
    } else {
        val store = LocalStore.current
        val customs by store.customEmoji.collectAsStateWithLifecycle()
        if (customs.any { it.code == code }) {
            AsyncImage(model = graph.api.customEmojiUrl(code), contentDescription = code, modifier = Modifier.size(size))
        } else {
            Text(":${code.take(8)}:", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 同じ人が 5 分以内に続けて送ったか (Web 版 sameSenderAsNext と同じ) */
fun sameSender(newer: Message, older: Message?): Boolean {
    if (older == null || older.isSystemMessage || newer.isSystemMessage) return false
    if (newer.userId != older.userId) return false
    val a = com.giracle.android.ui.common.parseInstant(newer.createdAt) ?: return false
    val b = com.giracle.android.ui.common.parseInstant(older.createdAt) ?: return false
    return a.toEpochMilli() - b.toEpochMilli() <= 5 * 60 * 1000
}

@Suppress("unused")
private fun ChatStore.dummy() = Unit
