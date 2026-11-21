package com.giracle.android.ui.manage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.giracle.android.data.model.BotInfo
import com.giracle.android.data.model.Invite
import com.giracle.android.data.model.RequestLog
import com.giracle.android.data.model.RequestLogCount
import com.giracle.android.data.model.Role
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.RoleChip
import com.giracle.android.ui.common.SubScreen
import com.giracle.android.ui.common.TestTags
import com.giracle.android.ui.common.fullTime
import com.giracle.android.ui.common.humanSize
import com.giracle.android.ui.common.parseColor
import com.giracle.android.ui.common.rememberUser
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.uploadFileFrom
import com.giracle.android.ui.common.userMessage
import com.giracle.android.ui.settings.SwitchRow
import kotlinx.coroutines.launch

/** サーバー管理 (Web 版 routes/manage-server.tsx)。権限に応じてタブを出す */
@Composable
fun ManageServerScreen() {
    val store = LocalStore.current
    val tabs = buildList {
        if (store.can("manageServer")) { add("サーバー"); add("招待") }
        if (store.can("manageRole")) add("ロール")
        if (store.can("manageEmoji")) add("絵文字")
        if (store.can("manageServer")) { add("Bot"); add("ログ") }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SubScreen("サーバー管理") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (tabs.isEmpty()) { Text("管理権限がありません", Modifier.padding(16.dp)); return@Column }
            PrimaryScrollableTabRow(selectedTabIndex = tab.coerceAtMost(tabs.lastIndex), edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tabs.getOrNull(tab)) {
                    "サーバー" -> CommunityTab()
                    "招待" -> InviteTab()
                    "ロール" -> RoleTab()
                    "絵文字" -> EmojiTab()
                    "Bot" -> BotApprovalTab()
                    "ログ" -> LogTab()
                }
            }
        }
    }
}

@Composable
private fun rememberAct(onDone: () -> Unit = {}): (suspend () -> Unit) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return { f -> scope.launch { runCatching { f() }.onSuccess { ctx.toast("更新しました"); onDone() }.onFailure { ctx.toast(it.userMessage()) } } }
}

@Composable
private fun ColumnScope.CommunityTab() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    val s by store.serverConfig.collectAsStateWithLifecycle()
    val channels by store.channels.collectAsStateWithLifecycle()
    val act = rememberAct()
    var name by remember(s.name) { mutableStateOf(s.name) }
    var intro by remember(s.introduction) { mutableStateOf(s.introduction) }
    var regAvail by remember(s) { mutableStateOf(s.registerAvailable) }
    var regInvite by remember(s) { mutableStateOf(s.registerInviteOnly) }
    var announce by remember(s) { mutableStateOf(s.registerAnnounceChannelId) }
    var maxLen by remember(s) { mutableStateOf(s.messageMaxLength.toString()) }
    var maxFile by remember(s) { mutableStateOf(s.messageMaxFileSize.toString()) }
    var botEnabled by remember(s) { mutableStateOf(s.botEnabled) }
    val defaults = remember(s) { mutableStateListOf<String>().apply { addAll(s.defaultJoinChannel.map { it.id }) } }
    var storage by remember { mutableStateOf<Long?>(null) }
    var bannerVer by remember { mutableIntStateOf(0) }
    var storageReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(storageReload) { storage = runCatching { graph.api.storageUsage() }.getOrNull() }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) act { graph.api.changeServerBanner(ctx.uploadFileFrom(uri)); coil3.SingletonImageLoader.get(ctx).diskCache?.remove(graph.api.serverBannerUrl()); bannerVer++ }
    }

    AsyncImage(model = graph.api.serverBannerUrl() + "?v=$bannerVer", contentDescription = "バナー", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    OutlinedButton(onClick = { pickBanner.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("バナーを変更") }
    storage?.let { Text("ストレージ使用量: ${humanSize(it)}", style = MaterialTheme.typography.bodySmall) }
    OldFilesCleanup(onDeleted = { storageReload++ })
    OutlinedTextField(name, { name = it }, label = { Text("サーバー名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(intro, { intro = it }, label = { Text("紹介文") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { act { graph.api.changeServerInfo(name.trim(), intro.trim()) } }, enabled = name.isNotBlank() && intro.isNotBlank()) { Text("名前と紹介文を保存") }
    HorizontalDivider()
    SwitchRow("新規登録を受け付ける", regAvail) { regAvail = it }
    SwitchRow("招待コード必須", regInvite) { regInvite = it }
    SwitchRow("Bot を有効にする", botEnabled) { botEnabled = it }
    OutlinedTextField(maxLen, { maxLen = it.filter(Char::isDigit) }, label = { Text("メッセージ最大文字数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    OutlinedTextField(maxFile, { maxFile = it.filter(Char::isDigit) }, label = { Text("添付ファイル最大サイズ (バイト)") }, supportingText = { Text(humanSize(maxFile.toLongOrNull() ?: 0)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    Text("新規ユーザーの参加告知チャンネル", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(announce.isEmpty(), { announce = "" }, label = { Text("なし") })
    }
    channels.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(announce == c.id, { if (it) announce = c.id else if (announce == c.id) announce = "" }); Text("#" + c.name) } }
    Text("新規ユーザーが自動で参加するチャンネル", style = MaterialTheme.typography.titleSmall)
    channels.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(c.id in defaults, { on -> if (on) defaults.add(c.id) else defaults.remove(c.id) }); Text("#" + c.name) } }
    Button(onClick = {
        act { graph.api.changeServerConfig(regAvail, regInvite, announce, maxLen.toIntOrNull() ?: s.messageMaxLength, maxFile.toLongOrNull() ?: s.messageMaxFileSize, defaults.toList(), botEnabled); store.serverConfig.value = graph.api.serverConfig() }
    }) { Text("サーバー設定を保存") }
}

/** 古い添付ファイルを消す選択肢 (日数)。D-074 */
private val OLD_FILE_DAYS = listOf(30, 90, 180, 365)

/**
 * 古い添付ファイルの削除 (DELETE server/delete-old-files、D-074)。
 * 期間を選び、取り消せないことを確認してから消す。消した後は [onDeleted] で使用量を取り直す。
 */
@Composable
private fun OldFilesCleanup(onDeleted: () -> Unit) {
    val graph = LocalGraph.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableIntStateOf(OLD_FILE_DAYS.last()) }
    var confirming by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    Text("古いファイルを削除 (送られてから経った日数)", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OLD_FILE_DAYS.forEach { d -> FilterChip(days == d, { days = d; hasMore = false }, label = { Text("$d 日") }) }
    }
    OutlinedButton(
        enabled = !running, onClick = { confirming = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(TestTags.MANAGE_DELETE_OLD_FILES),
    ) { Text(if (running) "削除しています…" else "$days 日より前のファイルを削除") }
    if (hasMore) Text("まだ残っています。もう一度実行してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("$days 日より前のファイルを削除しますか？") },
            text = {
                Text(
                    "$days 日より前に送られた添付ファイル (画像やファイル) を、サーバーから削除します。" +
                        "古いメッセージの本文は残りますが、添付は見られなくなります。この操作は取り消せません。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    running = true
                    val cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(days.toLong()))
                    scope.launch {
                        runCatching { graph.api.deleteOldFiles(cutoff) }
                            .onSuccess { more ->
                                hasMore = more
                                ctx.toast(if (more) "一部を削除しました。まだ残っています" else "古いファイルを削除しました")
                                onDeleted()
                            }
                            .onFailure { ctx.toast(it.userMessage()) }
                        running = false
                    }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ColumnScope.InviteTab() {
    val graph = LocalGraph.current
    val invites = remember { mutableStateListOf<Invite>() }
    var reload by remember { mutableIntStateOf(0) }
    val act = rememberAct { reload++ }
    var code by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    LaunchedEffect(reload) { invites.clear(); invites.addAll(runCatching { graph.api.invites() }.getOrDefault(emptyList())) }
    OutlinedTextField(code, { code = it }, label = { Text("招待コード") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(max, { max = it.filter(Char::isDigit) }, label = { Text("最大使用回数 (空なら無制限)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { code = java.util.UUID.randomUUID().toString().take(8) }) { Text("ランダム生成") }
        Button(enabled = code.isNotBlank(), onClick = { act { graph.api.createInvite(code.trim(), max.toIntOrNull()); code = ""; max = "" } }) { Text("作成") }
    }
    HorizontalDivider()
    invites.forEach { inv ->
        val creator = rememberUser(inv.createdUserId)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(inv.inviteCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("使用 ${inv.usedCount} / ${if (inv.maxUsage == 0) "∞" else inv.maxUsage} ・ 作成 ${creator?.displayName ?: ""}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { act { graph.api.deleteInvite(inv.id) } }) { Text("削除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ColumnScope.RoleTab() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val roles by store.roles.collectAsStateWithLifecycle()
    val act = rememberAct()
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Role?>(null) }
    var deleting by remember { mutableStateOf<Role?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(newName, { newName = it }, label = { Text("新しいロール名") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = newName.isNotBlank(), onClick = { act { graph.api.createRole(newName.trim()); newName = "" } }) { Text("作成") }
    }
    roles.values.sortedBy { it.createdAt }.forEach { r ->
        Row(Modifier.fillMaxWidth().clickable(enabled = r.id !in setOf("HOST", "MEMBER", "BOT")) { editing = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoleChip(r)
            Text(listOfNotNull("サーバー".takeIf { r.manageServer }, "チャンネル".takeIf { r.manageChannel }, "ロール".takeIf { r.manageRole }, "ユーザー".takeIf { r.manageUser }, "絵文字".takeIf { r.manageEmoji }).joinToString(" ").ifEmpty { "権限なし" },
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
            if (r.id !in setOf("HOST", "MEMBER", "BOT")) TextButton(onClick = { deleting = r }) { Text("削除", color = MaterialTheme.colorScheme.error) } else Badge("既定")
        }
    }
    editing?.let { r ->
        var e by remember(r.id) { mutableStateOf(r) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("ロールを編集") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(e.name, { e = e.copy(name = it) }, label = { Text("名前 (2 文字以上)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(e.color, { e = e.copy(color = it) }, label = { Text("色 (#rrggbb)") }, singleLine = true, modifier = Modifier.weight(1f))
                        Box(Modifier.size(28.dp).clip(CircleShape).background(parseColor(e.color) ?: MaterialTheme.colorScheme.outline))
                    }
                    Row { listOf("#ef4444", "#f97316", "#eab308", "#22c55e", "#3b82f6", "#a855f7", "#ec4899").forEach { c -> Box(Modifier.padding(2.dp).size(28.dp).clip(CircleShape).background(parseColor(c)!!).clickable { e = e.copy(color = c) }) } }
                    SwitchRow("サーバー管理", e.manageServer) { e = e.copy(manageServer = it) }
                    SwitchRow("チャンネル管理", e.manageChannel) { e = e.copy(manageChannel = it) }
                    SwitchRow("ロール管理", e.manageRole) { e = e.copy(manageRole = it) }
                    SwitchRow("ユーザー管理", e.manageUser) { e = e.copy(manageUser = it) }
                    SwitchRow("絵文字管理", e.manageEmoji) { e = e.copy(manageEmoji = it) }
                }
            },
            confirmButton = { TextButton(enabled = e.name.length >= 2, onClick = { editing = null; act { graph.api.updateRole(e) } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("キャンセル") } },
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("ロール「${r.name}」を削除しますか？") },
            confirmButton = { TextButton(onClick = { deleting = null; act { graph.api.deleteRole(r.id) } }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ColumnScope.EmojiTab() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    val emojis by store.customEmoji.collectAsStateWithLifecycle()
    val act = rememberAct()
    var code by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<android.net.Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it }
    Text("絵文字コードは英数字と _ (例: party_parrot)", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(code, { code = it.filter { c -> c.isLetterOrDigit() || c == '_' } }, label = { Text("コード") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (uri == null) "画像" else "選択済み") }
    }
    Button(enabled = code.isNotBlank() && uri != null, onClick = { val u = uri!!; act { graph.api.uploadCustomEmoji(code, ctx.uploadFileFrom(u)); code = ""; uri = null } }) { Text("追加") }
    HorizontalDivider()
    emojis.sortedBy { it.code }.forEach { e ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(model = graph.api.customEmojiUrl(e.code), contentDescription = e.code, modifier = Modifier.size(32.dp))
            Text(":${e.code}:", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            TextButton(onClick = { act { graph.api.deleteCustomEmoji(e.code) } }) { Text("削除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ColumnScope.BotApprovalTab() {
    val graph = LocalGraph.current
    val bots = remember { mutableStateListOf<BotInfo>() }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val act = rememberAct { reload++ }
    LaunchedEffect(reload, query) { bots.clear(); bots.addAll(runCatching { graph.api.allBots(query) }.getOrDefault(emptyList())) }
    OutlinedTextField(query, { query = it }, label = { Text("Bot 名で絞り込み") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (bots.isEmpty()) Text("Bot はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
    bots.forEach { b ->
        val creator = rememberUser(b.createdBy)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(b.remoteUserId, 32.dp)
            Column(Modifier.weight(1f)) {
                Text(b.remoteUserName ?: "(名前なし)", fontWeight = FontWeight.Bold)
                Text("作成: ${creator?.displayName ?: ""} ・ ${fullTime(b.createdAt)}", style = MaterialTheme.typography.labelSmall)
            }
            if (b.isApproved) OutlinedButton(onClick = { act { graph.api.setBotApproved(b.id, false) } }) { Text("承認取消") }
            else Button(onClick = { act { graph.api.setBotApproved(b.id, true) } }) { Text("承認") }
        }
    }
}

@Composable
private fun ColumnScope.LogTab() {
    val graph = LocalGraph.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf<String?>(null) }
    val groups = remember { mutableStateListOf<RequestLogCount>() }
    var selected by remember { mutableStateOf<String?>(null) }
    val logs = remember { mutableStateListOf<RequestLog>() }
    var moreLogs by remember { mutableStateOf(false) }
    LaunchedEffect(type) {
        groups.clear()
        runCatching { graph.api.logGroup(type, null, null, false) }.onSuccess { groups.addAll(it.group) }.onFailure { ctx.toast(it.userMessage()) }
    }
    suspend fun loadLogs(date: String, reset: Boolean) {
        val got = runCatching { graph.api.logs(date, if (reset) null else logs.lastOrNull()?.id) }.getOrDefault(emptyList())
        if (reset) logs.clear()
        logs.addAll(got); moreLogs = got.size >= 50
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(type == null, { type = null }, label = { Text("すべて") })
        FilterChip(type == "success", { type = "success" }, label = { Text("成功") })
        FilterChip(type == "error", { type = "error" }, label = { Text("エラー") })
    }
    groups.forEach { g ->
        Row(Modifier.fillMaxWidth().clickable { selected = g.date; scope.launch { loadLogs(g.date, true) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.date, Modifier.weight(1f), fontWeight = if (selected == g.date) FontWeight.Bold else FontWeight.Normal)
            Text("成功 ${g.successCount}  エラー ${g.errorCount}  他 ${g.otherCount}", style = MaterialTheme.typography.labelSmall)
        }
    }
    selected?.let { d ->
        HorizontalDivider()
        Text("$d のリクエスト", style = MaterialTheme.typography.titleSmall)
        logs.forEach { l ->
            Text("${l.status} ${l.method} ${l.path}  ${fullTime(l.createdAt)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = if (l.status >= 400) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (moreLogs) TextButton(onClick = { scope.launch { loadLogs(d, false) } }) { Text("さらに読み込む") }
    }
}
