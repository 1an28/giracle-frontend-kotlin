package com.giracle.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.AppGraph
import com.giracle.android.AppPhase
import com.giracle.android.data.InAppNotice
import com.giracle.android.notify.ConnectionService
import com.giracle.android.notify.PushRegistrar
import com.giracle.android.ui.browser.ChannelBrowserScreen
import com.giracle.android.ui.channel.ChannelScreen
import com.giracle.android.ui.channel.ChannelSettingsScreen
import com.giracle.android.ui.common.LocalGraph
import com.giracle.android.ui.common.LocalStore
import com.giracle.android.ui.common.TestTags
import com.giracle.android.ui.common.exposeTestTags
import com.giracle.android.ui.home.HomeScreen
import com.giracle.android.ui.inbox.InboxScreen
import com.giracle.android.ui.login.LoginScreen
import com.giracle.android.ui.manage.ManageServerScreen
import com.giracle.android.ui.members.MembersScreen
import com.giracle.android.ui.search.SearchScreen
import com.giracle.android.ui.settings.SettingsScreen
import com.giracle.android.ui.theme.GiracleTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Root(graph: AppGraph, pendingOpen: MutableState<Pair<String, String?>?>) {
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    GiracleTheme(theme = settings.theme, fontScale = settings.fontScale, dynamicColor = settings.dynamicColor) {
        // testTag を resource-id として外から見えるようにする (D-016)
        Surface(Modifier.fillMaxSize().exposeTestTags(), color = MaterialTheme.colorScheme.background) {
            val phase by graph.phase.collectAsStateWithLifecycle()
            val store by graph.storeFlow.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalGraph provides graph, LocalStore provides store) {
                when (val p = phase) {
                    AppPhase.Starting -> Unit
                    AppPhase.SignedOut -> Box(Modifier.fillMaxSize().testTag(TestTags.SCREEN_LOGIN)) { LoginScreen() }
                    is AppPhase.SignedIn -> SignedIn(p, graph, pendingOpen)
                }
            }
        }
    }
}

@Composable
private fun SignedIn(p: AppPhase.SignedIn, graph: AppGraph, pendingOpen: MutableState<Pair<String, String?>?>) {
    // 画面の履歴は、プロセスが終わって戻ったときにも戻す (D-032)
    val nav = rememberSaveable(saver = NavigatorCodec.saver) { Navigator() }
    // 履歴を戻したときは、前回のチャンネルを開き直さない
    val restoredNav = remember { nav.stack.size > 1 }
    val ctx = LocalContext.current
    val store = LocalStore.current
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val restored by store.restoredFromSnapshot.collectAsStateWithLifecycle()
    val loading by graph.loading.collectAsStateWithLifecycle()

    // 通知の許可 (Android 13+)。初回ログイン時に 1 度だけ尋ねる
    var asked by rememberSaveable { mutableStateOf(false) }
    // 権限の答えが出たら、プッシュ通知の自動登録を試し直す (D-019)
    var permAnswered by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permAnswered++ }
    // LAN 内のサーバーなら、Android 17 のローカルネットワーク権限を先に求める (#42、D-031)。
    // 前の版から更新した人は、ここで初めて尋ねられる。許可されたら、失敗していた読み込みをやり直す
    val gate = com.giracle.android.ui.common.rememberLocalNetworkGate()
    var localAsked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val ok = gate.ensure(graph.endpoint.origin.host)
        localAsked = true
        if (ok) (graph.phase.value as? AppPhase.SignedIn)?.takeIf { it.error != null }?.let { graph.retryLoad() }
    }
    LaunchedEffect(localAsked) {
        if (!localAsked) return@LaunchedEffect
        if (!asked && Build.VERSION.SDK_INT >= 33) {
            asked = true
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(settings.keepConnection, p.ready) {
        if (p.ready) ConnectionService.sync(ctx, settings.keepConnection)
    }
    // プッシュ通知を自動で有効にする (配信先が 1 つに決まり、利用者が無効にしていなければ)。D-019
    LaunchedEffect(p.ready, permAnswered) {
        if (p.ready) runCatching { PushRegistrar.autoEnable(ctx.applicationContext, graph, p.userId) }
    }

    // 前回開いていたチャンネルを復元 (起動直後に即座に表示する)
    LaunchedEffect(Unit) {
        // 共有から開かれたときは、送り先を選んでもらうためホームのままにする
        if (PendingShare.content != null) return@LaunchedEffect
        if (restoredNav) return@LaunchedEffect
        graph.prefs.lastChannelId?.let { if (pendingOpen.value == null) { android.util.Log.i("GiracleNav", "前回のチャンネルを開く"); nav.openChannel(it) } }
    }
    // 共有を受け取ったら、送り先のチャンネルを選べるようホームに戻る (D-020)
    val share = PendingShare.content
    LaunchedEffect(share) {
        // 共有シートで直接チャンネルが選ばれた (#25) ときは、そのチャンネルを開くのでホームに戻らない
        if (share != null && PendingShare.target == null) nav.reset()
    }
    // 通知から開かれたとき
    val pending = pendingOpen.value
    LaunchedEffect(pending) {
        if (pending != null) {
            android.util.Log.i("GiracleNav", "通知・ショートカットから開く")
            nav.openChannel(pending.first, pending.second)
            pendingOpen.value = null
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    CompositionLocalProvider(LocalNavigator provides nav) {
        Box(Modifier.fillMaxSize()) {
            // 読み込みに失敗しても、前回の内容 (スナップショット) があれば出し続け、上の帯で知らせる (#82、P-2)。
            // 画面全体を失敗の表示にするのは、出せる内容が何も無いときだけ
            val failed = !p.ready && p.error != null
            // me の有無では決めない (ログイン直後に、一部の読み込みだけ成功して me が入る場合がある)
            if (failed && !restored) {
                LoadError(p.error, loading = loading, onRetry = graph::retryLoad)
            } else androidx.compose.runtime.CompositionLocalProvider(com.giracle.android.ui.common.LocalLoadFailedBannerShown provides failed) { Column(Modifier.fillMaxSize()) {
                if (failed) OfflineBanner(loading = loading, onRetry = graph::retryLoad)
                val screen = nav.current
                // 帯が上の余白 (状態バー) を受け持つので、下の画面の上の帯 (TopAppBar) が二重に空けないようにする
                val contentInsets = if (failed) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(contentInsets)) {
                    // チャンネルの画面は、1 ペインと 2 ペインで描く場所が変わる。回転や折りたたみの開閉で切り替わっても
                    // 作り直さず、スクロール位置などの状態ごと移すため、movableContentOf で包む (#39、D-032)
                    val channelKey = (screen as? Screen.Channel)?.channelId
                    val channelContent = remember(channelKey) {
                        androidx.compose.runtime.movableContentOf { ch: String, msg: String? -> ChannelScreen(ch, msg) }
                    }
                    // 幅の広い画面 (タブレット・折りたたみ・横画面) では、ホームとチャンネルを左右に並べる (#29、D-028)
                    val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH && (screen is Screen.Home || screen is Screen.Channel)
                    if (twoPane) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(HOME_PANE_WIDTH).fillMaxHeight().testTag(TestTags.SCREEN_HOME)) {
                                androidx.compose.runtime.key(Screen.Home.stableKey()) { HomeScreen(loading = !p.ready && !failed, showConnection = !failed) }
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                if (screen is Screen.Channel) {
                                    androidx.compose.runtime.key(screen.stableKey()) {
                                        // 1 行が長くなりすぎないよう、読みやすい幅に抑える
                                        Box(Modifier.widthIn(max = CHANNEL_MAX_WIDTH).fillMaxSize().testTag(TestTags.SCREEN_CHANNEL)) {
                                            channelContent(screen.channelId, screen.messageId)
                                        }
                                    }
                                } else {
                                    Text(
                                        "チャンネルを選んでください",
                                        Modifier.align(Alignment.Center).testTag(TestTags.TWO_PANE_EMPTY),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        // 画面ごとに状態 (スクロール位置など) を保つため、キーを付けて描く
                        androidx.compose.runtime.key(screen.stableKey()) {
                            Box(Modifier.fillMaxSize().testTag(screen.testTag())) {
                                when (screen) {
                                    Screen.Home -> HomeScreen(loading = !p.ready && !failed, showConnection = !failed)
                                    is Screen.Channel -> channelContent(screen.channelId, screen.messageId)
                                    is Screen.ChannelSettings -> ChannelSettingsScreen(screen.channelId)
                                    Screen.Inbox -> InboxScreen()
                                    is Screen.Search -> SearchScreen(screen.channelId, screen.userId)
                                    Screen.Members -> MembersScreen()
                                    Screen.ChannelBrowser -> ChannelBrowserScreen()
                                    Screen.Settings -> SettingsScreen()
                                    Screen.ManageServer -> ManageServerScreen()
                                }
                            }
                        }
                    }
                }
            } }
            NoticeBanner(store.notices, nav)
        }
    }
}

private val TWO_PANE_MIN_WIDTH = 600.dp
private val HOME_PANE_WIDTH = 340.dp
private val CHANNEL_MAX_WIDTH = 840.dp

private fun Screen.testTag(): String = when (this) {
    Screen.Home -> TestTags.SCREEN_HOME
    is Screen.Channel -> TestTags.SCREEN_CHANNEL
    is Screen.ChannelSettings -> TestTags.SCREEN_CHANNEL_SETTINGS
    Screen.Inbox -> TestTags.SCREEN_INBOX
    is Screen.Search -> TestTags.SCREEN_SEARCH
    Screen.Members -> TestTags.SCREEN_MEMBERS
    Screen.ChannelBrowser -> TestTags.SCREEN_CHANNEL_BROWSER
    Screen.Settings -> TestTags.SCREEN_SETTINGS
    Screen.ManageServer -> TestTags.SCREEN_MANAGE_SERVER
}

private fun Screen.stableKey(): String = when (this) {
    is Screen.Channel -> "ch:$channelId"
    else -> toString()
}

/**
 * 読み込みに失敗したが、前回の内容を出しているときの帯 (#82)。回線が戻れば自動でやり直す (AppGraph.retryLoadIfFailed) ので、
 * 「再試行」は急ぐ人のためのもの。やり直している間は、押せないようにして進み具合を出す
 */
@Composable
internal fun OfflineBanner(loading: Boolean, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag(TestTags.OFFLINE_BANNER)) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (loading) "サーバーにつないでいます…" else "サーバーにつながりません — 前回の内容を表示しています。つながったら更新します",
                Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                androidx.compose.material3.TextButton(onClick = onRetry, modifier = Modifier.testTag(TestTags.OFFLINE_RETRY)) { Text("再試行") }
            }
        }
    }
}

@Composable
private fun LoadError(error: String, loading: Boolean, onRetry: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp).testTag(TestTags.LOAD_ERROR), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("サーバーから読み込めませんでした", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ctx = LocalContext.current
        if (!com.giracle.android.data.net.LocalNetwork.granted(ctx) && com.giracle.android.data.net.LocalNetwork.looksLocal(graph.endpoint.origin.host)) {
            // LAN 内のサーバーで権限が無いと、タイムアウトとしか出ないため、理由の候補を出す (#42)
            Spacer(Modifier.height(8.dp))
            Text(com.giracle.android.data.net.LocalNetwork.DENIED_MESSAGE, style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.TextButton(onClick = { com.giracle.android.ui.common.openAppSettings(ctx) }) { Text("アプリの設定を開く") }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, enabled = !loading) { Text(if (loading) "読み込んでいます…" else "再試行") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { scope.launch { graph.signOut() } }) { Text("ログアウト") }
    }
}

/** 前面にいるときの通知バナー。タップでその場所へ移動 */
@Composable
private fun NoticeBanner(flow: kotlinx.coroutines.flow.SharedFlow<InAppNotice>, nav: Navigator) {
    var current by remember { mutableStateOf<InAppNotice?>(null) }
    LaunchedEffect(flow) {
        flow.collect { n ->
            val top = nav.current
            // 今見ているチャンネルの通知は出さない
            if (top is Screen.Channel && top.channelId == n.channelId) return@collect
            current = n
        }
    }
    LaunchedEffect(current) {
        if (current != null) {
            delay(4000)
            current = null
        }
    }
    AnimatedVisibility(visible = current != null, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        val n = current ?: return@AnimatedVisibility
        Surface(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)
                .clickable {
                    n.channelId?.let { android.util.Log.i("GiracleNav", "画面内の通知から開く"); nav.openChannel(it, n.messageId) }
                    current = null
                },
            tonalElevation = 6.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(n.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.body, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
