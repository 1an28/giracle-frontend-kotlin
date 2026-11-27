package com.giracle.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.model.Channel
import com.giracle.android.data.net.WsClient
import com.giracle.android.ui.LocalNavigator
import kotlinx.coroutines.launch
import com.giracle.android.ui.PendingShare
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.Screen
import com.giracle.android.ui.common.AppIcons
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.TestTags

/**
 * ホーム = Web 版のサイドバー。参加チャンネルと各機能への入り口。
 * [showConnection] が false のときは接続の状態の行を出さない (読み込みの失敗を上の帯で知らせているとき。#82)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(loading: Boolean, showConnection: Boolean = true) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val me by store.me.collectAsStateWithLifecycle()
    val server by store.serverConfig.collectAsStateWithLifecycle()
    val channels by store.channels.collectAsStateWithLifecycle()
    val hasNew by store.hasNew.collectAsStateWithLifecycle()
    val inbox by store.inbox.collectAsStateWithLifecycle()
    val online by store.online.collectAsStateWithLifecycle()
    val muted by store.mutedChannels.collectAsStateWithLifecycle()
    val roles by store.roles.collectAsStateWithLifecycle()
    val connection by com.giracle.android.ui.common.rememberConnectionNotice(graph.ws)
    val updateState by graph.updates.state.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val newVersion = graph.updates.noticeFor(updateState, settings.checkUpdates)
    var reorder by rememberSaveable { mutableStateOf(false) }
    var confirmReadAll by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (confirmReadAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReadAll = false },
            title = { Text("すべて既読にする") },
            text = { Text("未読のあるチャンネル (${hasNew.count { it.value }} 個) をすべて既読にします。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        confirmReadAll = false
                        // 途中でホームから離れても (チャンネルを開くなど) 続ける
                        val appCtx = ctx.applicationContext
                        graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            val failed = store.markAllRead()
                            if (failed > 0) appCtx.toast("$failed 個のチャンネルを既読にできませんでした")
                        }
                    },
                    modifier = Modifier.testTag(TestTags.HOME_READ_ALL_CONFIRM),
                ) { Text("既読にする") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmReadAll = false }) { Text("キャンセル") } },
        )
    }
    var order by remember { mutableStateOf(graph.prefs.channelOrder) }

    val joinedIds = me?.channelJoin?.map { it.channelId }?.toSet() ?: emptySet()
    val joined = remember(channels, joinedIds, order) {
        val byId = channels.filter { it.id in joinedIds }.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] }
        ordered + byId.values.filter { it.id !in order }.sortedBy { it.name }
    }
    val canManage = remember(me, roles) { listOf("manageServer", "manageRole", "manageEmoji").any { store.can(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                },
                actions = {
                    if (hasNew.any { it.value }) {
                        IconButton(onClick = { confirmReadAll = true }, modifier = Modifier.testTag(TestTags.HOME_READ_ALL)) { Icon(AppIcons.DoneAll, "すべて既読にする") }
                    }
                    IconButton(onClick = { nav.push(Screen.Search()) }, modifier = Modifier.testTag(TestTags.HOME_SEARCH)) { Icon(Icons.Default.Search, "検索") }
                    IconButton(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.testTag(TestTags.HOME_SETTINGS).clearAndSetSemantics { contentDescription = "設定"; role = Role.Button }) {
                        me?.let { Avatar(it.id, size = 30.dp) } ?: Icon(Icons.Default.Settings, "設定")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item(key = "status", contentType = "status") {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                // 切れてすぐには出さない (ちらつかせない)。チャンネルの画面と同じ知らせを使う (D-073)
                else if (showConnection) connection.label?.let { label ->
                    Text(label, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp).testTag(TestTags.CONNECTION_NOTICE), style = MaterialTheme.typography.bodySmall)
                }
            }
            // 新しい版の知らせ (#147、D-086)
            newVersion?.let { v ->
                item(key = "update", contentType = "update") {
                    com.giracle.android.ui.common.UpdateNoticeRow(v) { graph.updates.dismiss(v.version) }
                }
            }
            // 他のアプリから共有されたものがあるとき、送り先のチャンネルを選んでもらう (D-020)
            PendingShare.content?.let { share ->
                item(key = "share", contentType = "share") {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp)
                            .testTag(TestTags.HOME_SHARE_BANNER),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共有された${share.description}を送るチャンネルを選んでください",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = { PendingShare.content = null }) { Text("やめる") }
                    }
                }
            }
            item(key = "online", contentType = "nav") {
                NavRow(AppIcons.People, "オンライン: ${online.size} 人") { nav.push(Screen.Members) }
            }
            item(key = "inbox", contentType = "nav") {
                NavRow(Icons.Default.Notifications, "通知", TestTags.HOME_INBOX, trailing = if (inbox.isNotEmpty()) inbox.size.toString() else null) { nav.push(Screen.Inbox) }
            }
            item(key = "browser", contentType = "nav") { NavRow(Icons.AutoMirrored.Filled.List, "チャンネル一覧", TestTags.HOME_CHANNEL_BROWSER) { nav.push(Screen.ChannelBrowser) } }
            item(key = "members", contentType = "nav") { NavRow(AppIcons.People, "メンバー", TestTags.HOME_MEMBERS) { nav.push(Screen.Members) } }
            if (canManage) item(key = "manage", contentType = "nav") { NavRow(AppIcons.Storage, "サーバー管理", TestTags.HOME_MANAGE_SERVER) { nav.push(Screen.ManageServer) } }
            item(key = "hdr", contentType = "hdr") {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("参加チャンネル", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { reorder = !reorder }, modifier = Modifier.testTag(TestTags.HOME_REORDER)) { Text(if (reorder) "完了" else "並べ替え") }
                }
            }
            if (joined.isEmpty() && !loading) {
                item(key = "empty", contentType = "hdr") {
                    Text("まだチャンネルに参加していません。「チャンネル一覧」から参加できます。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(joined, key = { it.id }, contentType = { "channel" }) { c ->
                ChannelRow(
                    c, unread = hasNew[c.id] == true, muted = c.id in muted, reorder = reorder,
                    // 2 ペイン (#29) で右に開いているチャンネル
                    selected = (nav.current as? com.giracle.android.ui.Screen.Channel)?.channelId == c.id,
                    onClick = {
                        if (PendingShare.content != null) PendingShare.target = c.id
                        nav.openChannel(c.id)
                    },
                    onMove = { delta ->
                        // 今の並び (order の最新の値) から計算する。描き直しの前に続けて呼ばれても、古い並びを使わない
                        val shown = joined.map { it.id }.toSet()
                        val ids = (order.filter { it in shown } + joined.map { it.id }.filter { it !in order }).toMutableList()
                        val i = ids.indexOf(c.id)
                        val j = (i + delta).coerceIn(0, ids.lastIndex)
                        if (i >= 0 && i != j) {
                            ids.removeAt(i); ids.add(j, c.id)
                            order = ids
                            graph.prefs.channelOrder = ids
                            true
                        } else false
                    },
                )
            }
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, tag: String? = null, trailing: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        trailingContent = trailing?.let { { Badge(it, strong = true) } },
        modifier = Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ChannelRow(c: Channel, unread: Boolean, muted: Boolean, reorder: Boolean, selected: Boolean, onClick: () -> Unit, onMove: (Int) -> Boolean) {
    // 並べ替え中は、右端のつまみを上下に引いて動かせる (D-044)。1 行分動かすごとに入れ替え、指の下に行が付いてくる
    var rowHeight by remember { mutableStateOf(1) }
    var drag by remember { mutableStateOf(0f) }
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    Row(
        Modifier.fillMaxWidth().testTag(TestTags.homeChannel(c.id))
            .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
            .graphicsLayer { translationY = drag }
            .then(if (drag != 0f) Modifier.zIndex(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .clickable(enabled = !reorder, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (c.viewableRole.isNotEmpty()) Icons.Default.Lock else AppIcons.Tag, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (unread && !muted) FontWeight.Bold else FontWeight.Normal,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (c.isArchived) Badge("アーカイブ")
        if (muted) Icon(AppIcons.NotificationsOff, "ミュート中", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (unread && !reorder) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        if (reorder) {
            IconButton(onClick = { onMove(-1) }) { Icon(Icons.Default.KeyboardArrowUp, "上へ") }
            IconButton(onClick = { onMove(1) }) { Icon(Icons.Default.KeyboardArrowDown, "下へ") }
            Icon(
                AppIcons.DragUpDown, "引いて並べ替え",
                // 指が反応する範囲は 48dp、見た目のアイコンは 24dp (H-4)。pointerInput を padding より前に置き、余白も反応させる
                Modifier.testTag(TestTags.homeChannelDrag(c.id)).size(48.dp).pointerInput(c.id) {
                    detectDragGestures(
                        onDragEnd = { drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { change, amount ->
                        change.consume()
                        drag += amount.y
                        // 半行を越えたら 1 行動かす。動かした分だけ見た目の位置を戻す (行が新しい位置に来るので)。
                        // 1 回の動きで動かすのは 1 行まで (次の行の入れ替えは、並びが描き直されてから)
                        // 端の行で動かせなかったときは、半行より先へは引けないようにする (指から行が離れないように)
                        if (drag > rowHeight / 2f) { if (move(1)) drag -= rowHeight else drag = rowHeight / 2f }
                        else if (drag < -rowHeight / 2f) { if (move(-1)) drag += rowHeight else drag = -rowHeight / 2f }
                    }
                }.padding(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(0.dp))
    }
}
