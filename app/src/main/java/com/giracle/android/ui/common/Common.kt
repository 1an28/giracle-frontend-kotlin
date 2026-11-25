package com.giracle.android.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.giracle.android.AppGraph
import com.giracle.android.data.ChatStore
import com.giracle.android.data.model.Role
import com.giracle.android.data.model.User
import com.giracle.android.data.net.ApiException
import com.giracle.android.data.net.UploadFile
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("graph") }
val LocalStore = staticCompositionLocalOf<ChatStore> { error("store") }

/**
 * 1 人分のユーザー情報を購読する。ユーザー表全体ではなくその人の変更でだけ再描画される。
 * 未取得なら取得を依頼する。
 */
@Composable
fun rememberUser(userId: String): User? {
    val store = LocalStore.current
    val flow = remember(store, userId) {
        store.ensureUser(userId)
        store.users.map { it[userId] }.distinctUntilChanged()
    }
    val u by flow.collectAsStateWithLifecycle(store.users.value[userId])
    return u
}

/**
 * ユーザーのアイコン。読み込み前と失敗時は名前の頭文字を出す。
 * 表示するたびに、アイコンが変わっていないかを裏で確かめる (期限内なら何もしない。#148、D-082)。
 */
@Composable
fun Avatar(userId: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val name = rememberUser(userId)?.name
    val ctx = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val url = graph.api.iconUrl(userId)
    val versionFlow = remember(graph, url) {
        graph.avatars.check(url)
        graph.avatars.versionsFlow.map { it[url] ?: 0 }.distinctUntilChanged()
    }
    val version by versionFlow.collectAsStateWithLifecycle(graph.avatars.versionOf(url))
    // アイコンは小さく縮めて読み込み、メモリと描画コストを抑える。アイコンが替わったら (version) 作り直して読み込み直す
    val req = remember(url, px, version) {
        ImageRequest.Builder(ctx).data(url).size(px).crossfade(false).build()
    }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text((name ?: userId).take(2), fontSize = (size.value / 2.8f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(model = req, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
    }
}

/** ロールの色付きラベル */
@Composable
fun RoleChip(role: Role, modifier: Modifier = Modifier) {
    val c = parseColor(role.color) ?: MaterialTheme.colorScheme.outline
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Text(role.name, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(6.dp))
            .background(if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (strong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun parseColor(s: String?): Color? {
    if (s.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s")) }.getOrNull()
}

fun User?.nameOrUnknown(): String = this?.name ?: "不明なユーザー"

// ---------- 日時 ----------

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
private val fullFmt = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
private val dayFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun parseInstant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

/** Web 版 displayDate と同じ: 今日なら時刻のみ */
fun friendlyTime(iso: String): String {
    val t = parseInstant(iso) ?: return iso
    val z = ZoneId.systemDefault()
    val d = t.atZone(z)
    val now = Instant.now().atZone(z)
    return when {
        d.year != now.year -> fullFmt.format(d)
        d.toLocalDate() != now.toLocalDate() -> dateTimeFmt.format(d)
        else -> timeFmt.format(d)
    }
}

fun fullTime(iso: String): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.let { fullFmt.format(it) } ?: iso
fun dayLabel(iso: String): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.let { dayFmt.format(it) } ?: iso
fun sameDay(a: String, b: String): Boolean {
    val z = ZoneId.systemDefault()
    val x = parseInstant(a)?.atZone(z)?.toLocalDate()
    val y = parseInstant(b)?.atZone(z)?.toLocalDate()
    return x == y
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2fGB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

// ---------- エラー表示 ----------

fun Throwable.userMessage(): String = when (this) {
    is ApiException -> when (code) {
        401 -> "権限がないか、ログインが切れています ($body)"
        404 -> "見つかりません ($body)"
        413 -> "ファイルが大きすぎます"
        429 -> "操作が多すぎます。少し待ってください"
        else -> body.ifBlank { "エラー ($code)" }
    }
    is java.net.UnknownHostException -> "サーバーが見つかりません"
    is java.net.ConnectException -> "サーバーに接続できません"
    is java.net.SocketTimeoutException -> "通信がタイムアウトしました"
    else -> message ?: toString()
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

// ---------- ファイル ----------

/** 端末のファイル (Uri) をアップロード用に読む。大きなファイルでもメモリに載せないよう、ストリームのまま送る */
fun Context.uploadFileFrom(uri: Uri): UploadFile {
    var name = "file"
    var size = -1L
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    val mime = normalizeMime(name, contentResolver.getType(uri))
    val resolver = contentResolver
    val body = object : okhttp3.RequestBody() {
        override fun contentType() = mime.toMediaTypeOrNull()
        override fun contentLength() = size
        override fun writeTo(sink: okio.BufferedSink) {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                }
            }
        }
    }
    return UploadFile(name, mime, body)
}

/** サーバーが画像以外で受け付ける MIME (giracle-backend の Utils/SafeString.ts と同じ一覧) */
val SERVER_SAFE_MIMES = setOf(
    "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain", "text/csv", "application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
    "application/gzip", "application/x-tar", "application/x-bzip2", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav",
    "audio/webm", "audio/aac", "audio/flac", "video/mp4", "video/webm", "video/ogg", "video/quicktime", "video/x-msvideo", "video/x-matroska",
)

/** 端末が返す MIME をサーバーの一覧に合わせる (不明・別名なら拡張子から推測) */
fun normalizeMime(name: String, reported: String?): String {
    val r = reported?.lowercase()?.substringBefore(';')?.trim()
    val alias = mapOf("audio/x-wav" to "audio/wav", "audio/x-flac" to "audio/flac", "application/x-zip-compressed" to "application/zip", "audio/mp4a-latm" to "audio/mp4", "application/x-gzip" to "application/gzip")
    if (r != null && (r.startsWith("image/") || r in SERVER_SAFE_MIMES)) return r
    if (r != null && alias[r] != null) return alias[r]!!
    val ext = name.substringAfterLast('.', "").lowercase()
    val guess = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.lowercase()
    return when {
        guess != null && (guess.startsWith("image/") || guess in SERVER_SAFE_MIMES) -> guess
        guess != null && alias[guess] != null -> alias[guess]!!
        else -> r ?: guess ?: "application/octet-stream"
    }
}

fun isUploadable(mime: String) = mime.startsWith("image/") || mime in SERVER_SAFE_MIMES

fun Context.fileSizeOf(uri: Uri): Long =
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L

@Suppress("unused")
private fun bytesBody(b: ByteArray, mime: String) = b.toRequestBody(mime.toMediaTypeOrNull())
