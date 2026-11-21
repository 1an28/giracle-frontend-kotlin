package com.giracle.android.ui.settings

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import com.giracle.android.ui.common.TestTags
import com.giracle.android.notify.PushRegistrar
import com.giracle.android.notify.PushRegistrationRetry
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.BuildConfig
import com.giracle.android.data.model.BotInfo
import com.giracle.android.data.model.Session
import com.giracle.android.ui.common.Avatar
import com.giracle.android.ui.common.Badge
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.RoleChip
import com.giracle.android.ui.common.SubScreen
import com.giracle.android.ui.common.fullTime
import com.giracle.android.ui.common.toast
import com.giracle.android.ui.common.uploadFileFrom
import com.giracle.android.ui.common.userMessage
import kotlinx.coroutines.launch

private val TABS = listOf("プロフィール", "表示", "チャット", "通知", "セッション", "Bot", "このアプリ")

/** 設定 (Web 版 routes/config.tsx) + このアプリ独自の設定 */
@Composable
fun SettingsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SubScreen("設定") { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> ProfileTab()
                    1 -> DisplayTab()
                    2 -> ChatTab()
                    3 -> NotificationTab()
                    4 -> SessionTab()
                    5 -> BotTab()
                    6 -> AppTab()
                }
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, description: String? = null, tag: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ColumnScope.ProfileTab() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by store.me.collectAsStateWithLifecycle()
    val roles by store.roles.collectAsStateWithLifecycle()
    val u = me ?: return
    var name by remember(u.id) { mutableStateOf(u.name ?: "") }
    var intro by remember(u.id) { mutableStateOf(u.selfIntroduction) }
    var iconVersion by remember { mutableIntStateOf(0) }
    var pw by remember { mutableStateOf(false) }

    fun act(f: suspend () -> Unit) = scope.launch { runCatching { f() }.onSuccess { ctx.toast("更新しました") }.onFailure { ctx.toast(it.userMessage()) } }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) act { graph.api.changeIcon(ctx.uploadFileFrom(uri)); clearImageCache(ctx, graph.api.iconUrl(u.id)); iconVersion++ }
    }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) act { graph.api.changeBanner(ctx.uploadFileFrom(uri)); clearImageCache(ctx, graph.api.bannerUrl(u.id)) }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(u.id, 64.dp, version = iconVersion)
        Column {
            Text(u.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { u.roleLink.mapNotNull { roles[it.roleId] }.forEach { RoleChip(it) } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("アイコンを変更") }
        OutlinedButton(onClick = { pickBanner.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("バナーを変更") }
    }
    OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(intro, { if (it.length <= 128) intro = it }, label = { Text("自己紹介 (128 文字まで)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { act { graph.api.profileUpdate(name.trim(), intro) } }, enabled = name.isNotBlank()) { Text("プロフィールを保存") }
    HorizontalDivider()
    OutlinedButton(onClick = { pw = true }) { Text("パスワードを変更") }
    if (pw) {
        var cur by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        var new2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pw = false },
            title = { Text("パスワードを変更") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cur, { cur = it }, label = { Text("現在のパスワード") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(new, { new = it }, label = { Text("新しいパスワード (4 文字以上)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(new2, { new2 = it }, label = { Text("新しいパスワード (確認)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = new2.isNotEmpty() && new != new2)
                }
            },
            confirmButton = { TextButton(enabled = cur.length >= 4 && new.length >= 4 && new == new2, onClick = { pw = false; act { graph.api.changePassword(cur, new) } }) { Text("変更") } },
            dismissButton = { TextButton(onClick = { pw = false }) { Text("キャンセル") } },
        )
    }
}

/** アイコン変更後に古い画像が残らないよう、Coil のキャッシュから消す */
private fun clearImageCache(ctx: android.content.Context, url: String) {
    val loader = coil3.SingletonImageLoader.get(ctx)
    loader.diskCache?.remove(url)
    loader.memoryCache?.keys?.filter { it.key.startsWith(url) }?.forEach { loader.memoryCache?.remove(it) }
}

@Composable
private fun ColumnScope.DisplayTab() {
    val graph = LocalGraph.current
    val s by graph.prefs.settings.collectAsStateWithLifecycle()
    Text("テーマ", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("system" to "端末に合わせる", "light" to "ライト", "dark" to "ダーク").forEach { (k, l) ->
            FilterChip(s.theme == k, { graph.prefs.updateSettings { it.copy(theme = k) } }, label = { Text(l) })
        }
    }
    if (com.giracle.android.ui.theme.supportsDynamicColor()) {
        SwitchRow("壁紙の色を使う", s.dynamicColor, "端末の壁紙から作った色 (Material You) で表示します") { v -> graph.prefs.updateSettings { it.copy(dynamicColor = v) } }
    }
    Text("メッセージの間隔: ${s.messageGapLevel}", style = MaterialTheme.typography.titleSmall)
    Slider(s.messageGapLevel.toFloat(), { v -> graph.prefs.updateSettings { it.copy(messageGapLevel = v.toInt()) } }, valueRange = 0f..6f, steps = 5)
    Text("URL プレビューの説明文: ${s.maxUrlPreviewTextLength} 文字まで", style = MaterialTheme.typography.titleSmall)
    Slider(s.maxUrlPreviewTextLength.toFloat(), { v -> graph.prefs.updateSettings { it.copy(maxUrlPreviewTextLength = v.toInt()) } }, valueRange = 10f..500f)
    Text("文字の大きさ: ${"%.0f".format(s.fontScale * 100)}%", style = MaterialTheme.typography.titleSmall)
    Slider(s.fontScale, { v -> graph.prefs.updateSettings { it.copy(fontScale = (v * 20).toInt() / 20f) } }, valueRange = 0.8f..1.4f)
    SwitchRow("画像を自動で読み込む", s.autoLoadImages, "OFF にすると画像はタップしたときだけ読み込みます (通信量の節約)") { v -> graph.prefs.updateSettings { it.copy(autoLoadImages = v) } }
}

@Composable
private fun ColumnScope.ChatTab() {
    val graph = LocalGraph.current
    val s by graph.prefs.settings.collectAsStateWithLifecycle()
    SwitchRow("送る写真を縮小する", s.shrinkImages, "長辺 2048px の JPEG にして送ります (通信量と待ち時間を減らす)。位置情報などの撮影情報は送られません。透過のある画像・GIF・動画はそのまま送ります") { v -> graph.prefs.updateSettings { it.copy(shrinkImages = v) } }
    SwitchRow("Enter キーで送信する", s.sendWithEnter, "外付けキーボード向け。Shift+Enter で改行します。OFF のときは送信ボタンで送ります") { v -> graph.prefs.updateSettings { it.copy(sendWithEnter = v) } }
}

@Composable
private fun ColumnScope.NotificationTab() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by graph.prefs.settings.collectAsStateWithLifecycle()
    val cfg by store.notificationConfig.collectAsStateWithLifecycle()
    val muted by store.mutedChannels.collectAsStateWithLifecycle()
    val channels by store.channels.collectAsStateWithLifecycle()
    fun update(enabled: Boolean?, mode: String?) = scope.launch {
        runCatching { store.notificationConfig.value = graph.api.updateNotificationConfig(enabled, mode) }.onFailure { ctx.toast(it.userMessage()) }
    }
    Text("サーバーの通知設定 (全端末共通)", style = MaterialTheme.typography.titleSmall)
    SwitchRow("通知を受け取る", cfg.enabled) { update(it, null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("mention" to "メンション・返信のみ", "all" to "すべて", "off" to "オフ").forEach { (k, l) ->
            FilterChip(cfg.mode == k, { update(null, k) }, label = { Text(l) })
        }
    }
    HorizontalDivider()
    Text("この端末", style = MaterialTheme.typography.titleSmall)
    SwitchRow("メンション・返信を通知する", s.notifyInbox) { v -> graph.prefs.updateSettings { it.copy(notifyInbox = v) } }
    SwitchRow("すべての新着を通知する", s.notifyAll) { v -> graph.prefs.updateSettings { it.copy(notifyAll = v) } }
    SwitchRow(
        "バックグラウンドでも接続を保つ", s.keepConnection,
        "アプリを閉じても通知を受け取れるよう、常駐して接続を保ちます。電池を多めに使います。" +
            "下のプッシュ通知が使えるなら不要です",
        tag = TestTags.SETTINGS_KEEP_CONNECTION,
    ) { v -> graph.prefs.updateSettings { it.copy(keepConnection = v) } }
    HorizontalDivider()
    PushSection()
    HorizontalDivider()
    Text("ミュート中のチャンネル", style = MaterialTheme.typography.titleSmall)
    if (muted.isEmpty()) Text("なし", color = MaterialTheme.colorScheme.onSurfaceVariant)
    muted.forEach { id ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#" + (channels.firstOrNull { it.id == id }?.name ?: id), Modifier.weight(1f))
            TextButton(onClick = { scope.launch { runCatching { store.setMuted(id, false) } } }) { Text("解除") }
        }
    }
}

@Composable
private fun ColumnScope.SessionTab() {
    val graph = LocalGraph.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions = remember { mutableStateListOf<Session>() }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    suspend fun load(reset: Boolean) {
        val p = if (reset) 1 else page + 1
        val got = runCatching { graph.api.sessions(p) }.getOrDefault(emptyList())
        if (reset) sessions.clear()
        sessions.addAll(got); page = p; more = got.isNotEmpty()
    }
    LaunchedEffect(Unit) { load(true) }
    Text("ログイン中の端末", style = MaterialTheme.typography.titleSmall)
    sessions.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.name, fontWeight = FontWeight.Medium)
                    if (s.thisIsYou) Badge("この端末", strong = true)
                }
                Text(fullTime(s.createdAt), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { renaming = s }) { Text("名前") }
            if (!s.thisIsYou) TextButton(onClick = { scope.launch { runCatching { graph.api.deleteSession(s.id) }.onSuccess { load(true) }.onFailure { ctx.toast(it.userMessage()) } } }) { Text("削除", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (more) TextButton(onClick = { scope.launch { load(false) } }) { Text("さらに表示") }
    renaming?.let { s ->
        var name by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("セッション名") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; scope.launch { runCatching { graph.api.changeSessionName(s.id, name.trim()) }.onSuccess { load(true) }.onFailure { ctx.toast(it.userMessage()) } } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ColumnScope.BotTab() {
    val graph = LocalGraph.current
    val store = LocalStore.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val server by store.serverConfig.collectAsStateWithLifecycle()
    val bots = remember { mutableStateListOf<BotInfo>() }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BotInfo?>(null) }
    var deleting by remember { mutableStateOf<BotInfo?>(null) }
    LaunchedEffect(reload) { bots.clear(); bots.addAll(runCatching { graph.api.myBots() }.getOrDefault(emptyList())) }
    fun act(f: suspend () -> Unit) = scope.launch { runCatching { f() }.onSuccess { reload++ }.onFailure { ctx.toast(it.userMessage()) } }

    if (!server.botEnabled) Text("このサーバーでは Bot が無効です (管理者がサーバー設定で有効にできます)", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { creating = true }) { Text("Bot を作成") }
    bots.forEach { b ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(b.remoteUserId, 32.dp)
                Text(b.remoteUserName ?: "(名前なし)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Badge(if (b.isApproved) "承認済み" else "承認待ち", strong = b.isApproved)
            }
            b.tokenCode?.let { token ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("トークン: ${token.take(6)}…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("token", token))) }; ctx.toast("トークンをコピーしました") }) { Text("コピー") }
                }
            }
            Row {
                TextButton(onClick = { editing = b }) { Text("編集") }
                TextButton(onClick = { deleting = b }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var intro by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false }, title = { Text("Bot を作成") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { if (it.length <= 32) name = it }, label = { Text("名前 (32 文字まで)") }, singleLine = true)
                OutlinedTextField(intro, { if (it.length <= 128) intro = it }, label = { Text("紹介文") })
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { creating = false; act { graph.api.createBot(name.trim(), intro) } }) { Text("作成") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("キャンセル") } },
        )
    }
    editing?.let { b ->
        var name by remember { mutableStateOf(b.remoteUserName ?: "") }
        var intro by remember { mutableStateOf("") }
        val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) act { graph.api.changeBotIcon(b.id, ctx.uploadFileFrom(uri)) }
        }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Bot を編集") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { if (it.length <= 32) name = it }, label = { Text("名前 (変更すると再承認が必要)") }, singleLine = true)
                OutlinedTextField(intro, { if (it.length <= 128) intro = it }, label = { Text("紹介文 (空なら変更しない)") })
                OutlinedButton(onClick = { pickIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("アイコンを変更") }
            } },
            confirmButton = { TextButton(onClick = {
                editing = null
                act { graph.api.updateBot(b.id, name.trim().takeIf { it.length >= 2 && it != b.remoteUserName }, intro.ifBlank { null }) }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("キャンセル") } },
        )
    }
    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("${b.remoteUserName} を削除しますか？") },
            confirmButton = { TextButton(onClick = { deleting = null; act { graph.api.deleteBot(b.id) } }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ColumnScope.AppTab() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val server by LocalStore.current.serverConfig.collectAsStateWithLifecycle()
    Text("接続先: ${graph.prefs.serverUrl}", style = MaterialTheme.typography.bodyMedium)
    Text("サーバー: ${server.name}")
    Text("アプリ: Giracle Native ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        val loader = coil3.SingletonImageLoader.get(graph.appContext)
        loader.memoryCache?.clear(); loader.diskCache?.clear()
    }) { Text("画像キャッシュを消す") }
    Button(onClick = { scope.launch { graph.signOut() } }) { Text("ログアウト") }
}

/**
 * プッシュ通知 (D-019)。サーバーの Web Push を UnifiedPush で受け取る。
 * 配信アプリ (ntfy など) があればそれを、無ければ Google Play 開発者サービス経由 (アプリに組み込んだ配信) を使う。
 */
@Composable
private fun PushSection() {
    val graph = LocalGraph.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val endpoint by graph.prefs.pushEndpointFlow.collectAsStateWithLifecycle()
    val retry by graph.pushRetry.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("プッシュ通知", style = MaterialTheme.typography.titleSmall)
    Text(
        "アプリを閉じていても通知を受け取れます。常時接続より電池にやさしい方法です。" +
            "Google Play 開発者サービスがあればそのまま使えます。UnifiedPush の配信アプリ (ntfy など) を入れている場合はそちらを使います。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        message ?: PushRegistrar.status(ctx, endpoint, retry),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(TestTags.SETTINGS_PUSH_STATUS),
    )
    // 登録が済んだら (宛先が変わったら) 一時的な案内を消して、今の状態を出す
    LaunchedEffect(endpoint) { if (endpoint != null) message = null }
    // やり直しの状態が変わったら (失敗した・やり直している)、そちらを出す。「登録しています」のまま残さない (#93)
    LaunchedEffect(retry) { if (retry !is PushRegistrationRetry.State.Idle) message = null }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, modifier = Modifier.testTag(TestTags.SETTINGS_PUSH_ENABLE), onClick = {
            busy = true
            scope.launch {
                val activity = ctx as? android.app.Activity
                if (activity == null) { busy = false; return@launch }
                PushRegistrar.enable(activity, graph) { r ->
                    message = when (r) {
                        PushRegistrar.Result.Requested -> null // 状態 (やり直しを含む) から出す
                        PushRegistrar.Result.NoDistributor -> "使える配信先がありません。Google Play 開発者サービスの無い端末では、" +
                            "ntfy などの UnifiedPush 配信アプリを入れてから、もう一度押してください"
                        PushRegistrar.Result.ServerNotConfigured -> "このサーバーはプッシュ通知 (VAPID 鍵) が設定されていません。管理者に設定を依頼してください"
                        is PushRegistrar.Result.Failed -> r.error.userMessage()
                    }
                    busy = false
                }
            }
        }) { Text(if (endpoint != null) "再登録" else "有効にする") }
        if (endpoint != null) OutlinedButton(onClick = {
            PushRegistrar.disable(ctx, graph)
            message = "無効にしました"
        }) { Text("無効にする") }
    }
}
