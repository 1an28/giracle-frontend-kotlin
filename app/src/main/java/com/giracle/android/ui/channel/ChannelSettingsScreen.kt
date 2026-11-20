package com.giracle.android.ui.channel

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.model.User
import com.giracle.android.ui.LocalNavigator
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.RoleChip
import com.giracle.android.ui.common.SubScreen
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.userMessage
import com.giracle.android.ui.members.UserRow
import com.giracle.android.ui.members.UserSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** チャンネル情報 (Web 版 ChannelHeader の管理ダイアログ)。情報タブとメンバータブ */
@Composable
fun ChannelSettingsScreen(channelId: String) {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val channels by store.channels.collectAsStateWithLifecycle()
    val roles by store.roles.collectAsStateWithLifecycle()
    val muted by store.mutedChannels.collectAsStateWithLifecycle()
    val me by store.me.collectAsStateWithLifecycle()
    val online by store.online.collectAsStateWithLifecycle()
    val c = channels.firstOrNull { it.id == channelId }
    val canManage = store.can("manageChannel")
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(channelId) {
        // 閲覧可能ロールは get-info にだけ含まれるので取り直す
        runCatching { store.fetchChannelInfo(channelId) }
    }
    fun act(f: suspend () -> Unit) = scope.launch { runCatching { f() }.onSuccess { ctx.toast("更新しました") }.onFailure { ctx.toast(it.userMessage()) } }

    SubScreen(c?.let { "#" + it.name } ?: "チャンネル") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("情報") })
                Tab(tab == 1, { tab = 1 }, text = { Text("メンバー") })
            }
            if (c == null) { Text("読み込み中…", Modifier.padding(16.dp)); return@Column }
            if (tab == 0) {
                var name by remember(c.id) { mutableStateOf(c.name) }
                var desc by remember(c.id) { mutableStateOf(c.description) }
                var viewable by remember(c.id, c.viewableRole) { mutableStateOf(c.viewableRole.map { it.roleId }.toSet()) }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名前") }, enabled = canManage, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(desc, { desc = it }, label = { Text("説明") }, enabled = canManage, modifier = Modifier.fillMaxWidth())
                    if (canManage) Button(onClick = { act { graph.api.updateChannel(channelId, name = name.trim(), description = desc) } }, enabled = name.isNotBlank()) { Text("名前と説明を保存") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知をミュート", Modifier.weight(1f))
                        Switch(checked = channelId in muted, onCheckedChange = { on -> scope.launch { runCatching { store.setMuted(channelId, on) }.onFailure { ctx.toast(it.userMessage()) } } })
                    }
                    if (canManage) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("アーカイブ (書き込み停止)", Modifier.weight(1f))
                            Switch(checked = c.isArchived, onCheckedChange = { on -> act { graph.api.updateChannel(channelId, isArchived = on) } })
                        }
                        Text("閲覧できるロール (未選択なら全員)", style = MaterialTheme.typography.titleSmall)
                        roles.values.filter { it.id != "HOST" }.sortedBy { it.name }.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(r.id in viewable, { on -> viewable = if (on) viewable + r.id else viewable - r.id })
                                RoleChip(r)
                            }
                        }
                        OutlinedButton(onClick = { act { graph.api.updateChannel(channelId, viewableRole = viewable.toList()) } }) { Text("閲覧ロールを保存") }
                    }
                    Text("チャンネル ID: $channelId", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("id", "#<$channelId>"))) }; ctx.toast("チャンネルへの参照をコピーしました") }) { Text("チャンネルへの参照 (#<ID>) をコピー") }
                    if (me?.channelJoin?.any { it.channelId == channelId } == true) {
                        OutlinedButton(onClick = { scope.launch { runCatching { graph.api.leaveChannel(channelId) }.onSuccess { nav.reset() }.onFailure { ctx.toast(it.userMessage()) } } }) {
                            Text("チャンネルから退出", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                val members = remember { mutableStateListOf<User>() }
                var reload by remember { mutableStateOf(0) }
                var inviting by remember { mutableStateOf(false) }
                var sheet by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(reload) {
                    members.clear()
                    var cursor: String? = null
                    while (true) {
                        val got = runCatching { graph.api.userList(length = 50, cursorUserId = cursor, joinedChannel = channelId) }.getOrDefault(emptyList())
                        got.forEach(store::putUser)
                        members.addAll(got)
                        if (got.size < 50) break
                        cursor = got.last().id
                    }
                }
                Column {
                    if (canManage) Button(onClick = { inviting = true }, modifier = Modifier.padding(12.dp)) { Text("ユーザーを招待") }
                    LazyColumn {
                        items(members, key = { it.id }) { u ->
                            UserRow(u, u.id in online, trailing = if (canManage && u.id != me?.id) ({
                                TextButton(onClick = { act { graph.api.kick(channelId, u.id); reload++ } }) { Text("キック", color = MaterialTheme.colorScheme.error) }
                            }) else null) { sheet = u.id }
                        }
                    }
                }
                if (inviting) InviteDialog(exclude = members.map { it.id }.toSet(), onDismiss = { inviting = false }) { u -> act { graph.api.invite(channelId, u.id); reload++ } }
                sheet?.let { UserSheet(it) { sheet = null } }
            }
        }
    }
}

@Composable
private fun InviteDialog(exclude: Set<String>, onDismiss: () -> Unit, onInvite: (User) -> Unit) {
    val graph = LocalGraph.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(q) {
        delay(250)
        results = runCatching { graph.api.userList(length = 30, username = q.ifBlank { null }) }.getOrDefault(emptyList()).filter { it.id !in exclude }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("招待するユーザー") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("名前で検索") }, singleLine = true)
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(results, key = { it.id }) { u -> UserRow(u, false) { onInvite(u); onDismiss() } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
