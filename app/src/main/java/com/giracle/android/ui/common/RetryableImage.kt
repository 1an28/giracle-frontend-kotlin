package com.giracle.android.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.graphics.ImageDecoder
import android.os.Build
import coil3.compose.AsyncImagePainter
import coil3.network.HttpException
import com.giracle.android.data.net.WsClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import java.io.IOException

/** 画像の読み込みの失敗の種類 (#129) */
enum class ImageFailure {
    /** 失敗していない (読み込み中・済み) */
    None,
    /** 回線・サーバーの一時的な不調など。出し直せば読める見込みがある */
    Retryable,
    /** サーバーに無い (404)。出し直しても読めない (管理者が古い添付を消したなど) */
    Gone,
    /** 取れたが表示できない (端末が読めない形式・壊れたファイルなど)。出し直しても変わらない */
    Unsupported,
}

/** Coil の失敗の結果を [ImageFailure] に分ける */
fun imageFailureOf(state: AsyncImagePainter.State): ImageFailure =
    if (state is AsyncImagePainter.State.Error) imageFailureOf(state.result.throwable) else ImageFailure.None

/**
 * HTTP の 404 は「無い」。ほかの HTTP の失敗と、取得中の入出力の失敗 (圏外・時間切れ・途中で切れたなど) は出し直せるもの。
 * 復号の失敗 (ImageDecoder の DecodeException は IOException の仲間なので先に除く) とその他は「表示できない」
 */
fun imageFailureOf(t: Throwable): ImageFailure = when {
    t is HttpException -> if (t.response.code == 404) ImageFailure.Gone else ImageFailure.Retryable
    Build.VERSION.SDK_INT >= 28 && t is ImageDecoder.DecodeException -> ImageFailure.Unsupported
    t is IOException -> ImageFailure.Retryable
    else -> ImageFailure.Unsupported
}

/**
 * 出し直せる失敗のあと、リアルタイム接続が「切れている → つながった」と移ったら [retry] を呼ぶ。
 * 回線の見張りを画像ごとに増やさず、アプリが既に持っている接続の状態 (送信待ちの送り直しと同じ合図、#30) を使う。
 * 失敗した時点でつながったままなら、一度切れてつながり直すまで待つ (読めないまま出し直しを繰り返さない)
 */
@Composable
fun RetryOnReconnect(failure: ImageFailure, connection: StateFlow<WsClient.State>, retry: () -> Unit) {
    val latestRetry by rememberUpdatedState(retry)
    LaunchedEffect(failure, connection) {
        if (failure != ImageFailure.Retryable) return@LaunchedEffect
        connection.dropWhile { it == WsClient.State.Connected }.first { it == WsClient.State.Connected }
        latestRetry()
    }
}

/**
 * 読み込みに失敗したときに画像の枠の中へ出す印。出し直せるなら「押して再読み込み」を添える。
 * 押したときの出し直しは、[onRetry] を渡せば印が受け持つ。渡さなければ枠の側の clickable が受け持つ
 */
@Composable
fun ImageFailureMark(failure: ImageFailure, modifier: Modifier = Modifier, onDark: Boolean = false, onRetry: (() -> Unit)? = null) {
    if (failure == ImageFailure.None) return
    val color = if (onDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.then(if (onRetry != null && failure == ImageFailure.Retryable) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry) else Modifier)
            .padding(8.dp).testTag(
                when (failure) {
                    ImageFailure.Gone -> TestTags.IMAGE_GONE
                    ImageFailure.Unsupported -> TestTags.IMAGE_UNSUPPORTED
                    else -> TestTags.IMAGE_RETRY
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (failure == ImageFailure.Gone) {
            Icon(Icons.Default.Warning, null, Modifier.size(24.dp), tint = color)
            Text("ファイルがサーバーにありません", color = color, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        } else if (failure == ImageFailure.Unsupported) {
            Icon(Icons.Default.Warning, null, Modifier.size(24.dp), tint = color)
            Text("この画像は表示できません", color = color, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        } else {
            Icon(Icons.Default.Refresh, null, Modifier.size(24.dp), tint = color)
            Text("読み込めませんでした", color = color, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Text("押して再読み込み", color = color, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}
