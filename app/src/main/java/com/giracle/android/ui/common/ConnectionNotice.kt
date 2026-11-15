package com.giracle.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.net.WsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * リアルタイム接続 (WebSocket) の状態のうち、利用者に見せるもの (D-073)。
 * 切れてすぐには出さず ([CONNECTION_NOTICE_GRACE_MS] 続いたら出す)、出した後につながったら「つながりました」を少し出して消す
 */
@Immutable
enum class ConnectionNotice(val label: String?, val shortLabel: String? = label) {
    None(null),
    Connecting("接続中… 新着はつながってから届きます"),
    Offline("オフライン — 再接続を待っています"),
    Reconnected("つながりました"),
    /** チャンネルの題名の下は 1 行なので短くする。直し方はホームの行に任せる */
    AuthError("リアルタイム接続が拒否されました (サーバーの CORS_ORIGIN 設定を確認)", "リアルタイム接続が拒否されました"),
}

/** 切れてから見せるまでの時間。つなぎ直しは普段 1〜2 秒で済む (WsClient の最初の待ち時間は 1〜1.5 秒) ので、その間はちらつかせない */
const val CONNECTION_NOTICE_GRACE_MS = 3_000L
/** つながったことを見せておく時間 */
const val CONNECTION_NOTICE_RECONNECTED_MS = 1_500L

/**
 * 接続の状態を、見せる知らせに変える。
 * 集め始めた時点 (画面を開いた・前面に戻った) から数えるので、前面に戻った直後のつなぎ直しでも出さない。
 * 切れている間 (切れた → 接続中 → 切れた… をくり返す) は、最初に切れた時から数える。出した後は待たずに中身を切り替える
 */
fun connectionNotices(
    states: Flow<WsClient.State>,
    graceMs: Long = CONNECTION_NOTICE_GRACE_MS,
    reconnectedMs: Long = CONNECTION_NOTICE_RECONNECTED_MS,
): Flow<ConnectionNotice> = channelFlow {
    var shown = false
    var current = WsClient.State.Connected
    var graceJob: Job? = null
    var reconnectedJob: Job? = null
    fun notice(s: WsClient.State) = if (s == WsClient.State.Connecting) ConnectionNotice.Connecting else ConnectionNotice.Offline
    states.distinctUntilChanged().collect { s ->
        current = s
        reconnectedJob?.cancel()
        when (s) {
            WsClient.State.Connected -> {
                graceJob?.cancel(); graceJob = null
                if (shown) {
                    shown = false
                    send(ConnectionNotice.Reconnected)
                    reconnectedJob = launch { delay(reconnectedMs); send(ConnectionNotice.None) }
                } else send(ConnectionNotice.None)
            }
            WsClient.State.AuthError -> {
                graceJob?.cancel(); graceJob = null
                shown = true
                send(ConnectionNotice.AuthError)
            }
            WsClient.State.Connecting, WsClient.State.Disconnected -> when {
                shown -> send(notice(s))
                graceJob == null -> {
                    send(ConnectionNotice.None)
                    graceJob = launch {
                        delay(graceMs)
                        shown = true
                        send(notice(current))
                    }
                }
                // 待っている途中: 待ち終えたときの状態で出す
            }
        }
    }
}.distinctUntilChanged()

/** 読み込みに失敗した帯 (Root の OfflineBanner、D-057) が出ているか。出ている間は、接続の知らせを重ねて出さない */
val LocalLoadFailedBannerShown = staticCompositionLocalOf { false }

@Composable
fun rememberConnectionNotice(ws: WsClient): State<ConnectionNotice> {
    val flow = remember(ws) { connectionNotices(ws.state) }
    return flow.collectAsStateWithLifecycle(ConnectionNotice.None)
}
