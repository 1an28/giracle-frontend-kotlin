package com.giracle.android.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.giracle.android.data.NewVersion
import com.giracle.android.ui.settings.SwitchRow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** リリースのページをブラウザで開く。ブラウザが無い端末では落とさずに知らせる (#147) */
fun openReleasePage(ctx: Context, url: String?) {
    if (url == null) return
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ctx.toast("ページを開けるアプリがありません: $url")
    } catch (_: SecurityException) {
        ctx.toast("ページを開けませんでした: $url")
    }
}

/** ホームの、新しい版の控えめな 1 行の知らせ (#147、D-086)。閉じたらその版では二度と出さない */
@Composable
fun UpdateNoticeRow(v: NewVersion, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(TestTags.HOME_UPDATE_NOTICE)
            .clickable(enabled = v.htmlUrl != null) { openReleasePage(ctx, v.htmlUrl) }
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "新しい版 v${v.version} があります",
            Modifier.weight(1f).padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.HOME_UPDATE_DISMISS)) {
            Icon(Icons.Default.Close, "この版の知らせを閉じる", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val checkedFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
private fun checkedTime(ms: Long): String = checkedFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 設定の「このアプリ」の、版と新しい版の確認 (#147、D-086) */
@Composable
fun UpdateSettingsSection(currentVersion: String) {
    val graph = LocalGraph.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val updates = graph.updates
    val s by updates.state.collectAsStateWithLifecycle()
    val checking by updates.checking.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    Text("アプリ: Giracle Native $currentVersion", style = MaterialTheme.typography.bodySmall)
    if (!updates.available) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val newer = updates.newVersion(s)
        val status = when {
            newer != null -> "新しい版 v${newer.version} があります (押すとリリースのページを開きます)"
            s.checkedAt <= 0L -> "新しい版はまだ確かめていません"
            s.lastOk -> "最新です (最後に確かめた: ${checkedTime(s.checkedAt)})"
            else -> "確かめられませんでした (${checkedTime(s.checkedAt)})"
        }
        Text(
            status,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TestTags.SETTINGS_UPDATE_STATUS)
                .then(if (newer?.htmlUrl != null) Modifier.clickable { openReleasePage(ctx, newer.htmlUrl) } else Modifier)
                .padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (newer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !checking,
            onClick = { scope.launch { updates.checkNow() } },
            modifier = Modifier.testTag(TestTags.SETTINGS_UPDATE_CHECK_NOW),
        ) { Text(if (checking) "確かめています…" else "今すぐ確かめる") }
    }
    SwitchRow(
        "新しい版を確かめる", settings.checkUpdates,
        "1 日に 1 回ほど、新しい版があるかを GitHub に問い合わせ、あればホームに知らせます。" +
            "GitHub には、この端末の IP アドレスと問い合わせた時刻が伝わります",
        tag = TestTags.SETTINGS_CHECK_UPDATES,
    ) { v ->
        graph.prefs.updateSettings { it.copy(checkUpdates = v) }
        if (v) updates.onForeground() // 間隔が空いていれば、すぐ確かめる
    }
}
