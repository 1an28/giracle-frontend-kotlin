package com.giracle.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.LruCache
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.giracle.android.ui.ChannelShortcuts
import kotlinx.coroutines.withTimeoutOrNull
import com.giracle.android.AppGraph
import com.giracle.android.GiracleApp
import com.giracle.android.MainActivity
import com.giracle.android.R
import com.giracle.android.data.InAppNotice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android の通知。
 * - WebSocket で受け取ったメンション・返信・(設定次第で) 全新着を、アプリが背面にあるときだけ通知にする
 *   (前面にあるときは画面内のバナーで知らせる。Root.kt)
 * - アプリを閉じているときは、プッシュ通知 ([PushServiceImpl]、D-019) から呼ばれる
 * 通知には「返信」「既読にする」のボタンを付ける ([NotificationActionReceiver]、D-022)。
 */
object Notifier {
    const val CH_MESSAGES = "messages"
    const val CH_CONNECTION = "connection"
    // プッシュと WebSocket の両方から同時に呼ばれるので、番号はスレッドをまたいで重ならないようにする
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1000)

    fun init(context: Context, graph: AppGraph) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "メッセージ", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "メンション・返信・新着メッセージ"
        })
        nm.createNotificationChannel(NotificationChannel(CH_CONNECTION, "接続の維持", NotificationManager.IMPORTANCE_MIN).apply {
            description = "バックグラウンドで接続を保っている間に表示されます"
            setShowBadge(false)
        })
        graph.scope.launch {
            graph.storeFlow.collectLatest { store ->
                store.notices.collect { n -> if (!store.appInForeground) post(context, n) }
            }
        }
        graph.scope.launch {
            // 別の端末で既読にした (既読時刻が通知のメッセージ以降になった) チャンネルの通知を消す (#49)。
            // 既読時刻は、前面に戻ったときの読み込み直しと、つながっている間の WebSocket (ReadTimeUpdated) で更新される
            graph.storeFlow.collectLatest { store ->
                store.readTimes.collect { rt -> cancelRead(context, rt) }
            }
        }
        graph.scope.launch {
            // アカウントが変わったら、前のアカウントのアイコンを使わない
            graph.storeFlow.collect { synchronized(icons) { icons.evictAll() } }
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** チャンネルごとの、通知に出している最近のメッセージ (会話の形でまとめる。D-022) */
    private class Line(val messageId: String?, val senderId: String?, val sender: String, val text: String, val time: Long, val isMe: Boolean = false)

    /** 送信者のアイコン (丸く切り抜いたもの)。通知のたびに読み込まないよう覚えておく (#26) */
    private val icons = LruCache<String, IconCompat>(32)
    private const val ICON_PX = 128
    private const val ICON_TIMEOUT_MS = 3000L

    /** 送信者のアイコンを読み込む。間に合わない・失敗したときは null (名前だけで出す) */
    private suspend fun senderIcon(context: Context, userId: String): IconCompat? {
        val graph = (context.applicationContext as GiracleApp).graph
        val url = graph.api.iconUrl(userId)
        // アイコンが替わっていないか裏で確かめる。替わっていれば次の通知から新しいものを使う (#148、D-082)
        graph.avatars.check(url)
        val key = "$userId#${graph.avatars.versionOf(url)}"
        synchronized(icons) { icons.get(key) }?.let { return it }
        val bmp = withTimeoutOrNull(ICON_TIMEOUT_MS) {
            val req = ImageRequest.Builder(context).data(url).size(ICON_PX).allowHardware(false).build()
            (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
        } ?: return null
        val icon = IconCompat.createWithBitmap(circle(bmp))
        synchronized(icons) { icons.put(key, icon) }
        return icon
    }

    private fun circle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, ((size - src.width) / 2f), ((size - src.height) / 2f), paint)
        return out
    }
    private val conversations = HashMap<String, ArrayDeque<Line>>()
    /** チャンネルごとの、最後に出した通知の元 (自分の返信を足して出し直すときに使う。#38) */
    private val lastNotices = HashMap<String, InAppNotice>()
    private const val MAX_LINES = 8

    private fun channelTag(channelId: String) = "ch:$channelId"

    /**
     * @param ownReply 通知から送った自分の返信 (#38)。会話に「自分」の発言として足し、音は鳴らさずに出し直す
     */
    suspend fun post(context: Context, n: InAppNotice, tag: String? = null, ownReply: String? = null) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CHANNEL, n.channelId)
            putExtra(MainActivity.EXTRA_MESSAGE, n.messageId)
        }
        val id = nextId.getAndIncrement()
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ch = n.channelId
        val notifTag: String
        val builder = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (ch == null) {
            notifTag = tag ?: n.messageId ?: id.toString()
            builder.setContentTitle(n.title).setContentText(n.body).setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
        } else {
            // チャンネルごとに 1 つの通知にまとめ、会話の形 (MessagingStyle) で最近のメッセージを並べる。
            // 同じメッセージ (WebSocket とプッシュの両方から来た場合など) は 1 回だけ足す
            notifTag = channelTag(ch)
            val lines = synchronized(conversations) {
                val q = conversations.getOrPut(ch) { ArrayDeque() }
                if (ownReply != null) {
                    q.addLast(Line(null, null, "自分", ownReply, System.currentTimeMillis(), isMe = true))
                } else {
                    if (n.messageId != null && q.any { it.messageId == n.messageId }) return
                    q.addLast(Line(n.messageId, n.senderId, n.sender ?: n.title, n.body, System.currentTimeMillis()))
                    lastNotices[ch] = n
                }
                while (q.size > MAX_LINES) q.removeFirst()
                q.toList()
            }
            val graph = (context.applicationContext as GiracleApp).graph
            val channelName = n.channelName ?: runCatching {
                graph.store.channels.value.firstOrNull { it.id == ch }?.name
            }.getOrNull()
            // 送信者ごとに 1 回だけアイコンを読む (#26)
            val iconsById = lines.mapNotNull { it.senderId }.distinct().associateWith { senderIcon(context, it) }
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName("自分").build())
                .setConversationTitle(channelName?.let { "#$it" })
                .setGroupConversation(channelName != null)
            lines.forEach { l ->
                // 自分の発言は送り手を null にする (MessagingStyle の決まり: null は利用者自身)
                if (l.isMe) { style.addMessage(l.text, l.time, null as Person?); return@forEach }
                val person = Person.Builder().setName(l.sender).apply {
                    if (l.senderId != null) setKey(l.senderId)
                    l.senderId?.let { iconsById[it] }?.let { setIcon(it) }
                }.build()
                style.addMessage(l.text, l.time, person)
            }
            builder.setStyle(style).setContentTitle(n.title).setContentText(n.body)
            if (ownReply != null) builder.setOnlyAlertOnce(true).setSilent(true)
            n.senderId?.let { iconsById[it] }?.let { builder.setLargeIcon(it.toIcon(context)) }
            // チャンネルのショートカットと結び付けると、Android 11 以降で「会話」として通知欄の上段に出る (#26)
            ChannelShortcuts.publish(context, ch, channelName)
            builder.setShortcutId(ChannelShortcuts.shortcutId(ch))
            // 通知から直接「返信」「既読にする」 (D-022)。対象は一番新しいメッセージ
            fun action(act: String, req: Int) = PendingIntent.getBroadcast(
                context, req,
                Intent(context, NotificationActionReceiver::class.java).setAction(act)
                    .putExtra(NotificationActionReceiver.EXTRA_CHANNEL, ch)
                    .putExtra(NotificationActionReceiver.EXTRA_MESSAGE, n.messageId)
                    .putExtra(NotificationActionReceiver.EXTRA_TAG, notifTag),
                // 返信は入力した文字を受け取るため、書き換え可能 (MUTABLE) にする必要がある
                PendingIntent.FLAG_UPDATE_CURRENT or (if (act == NotificationActionReceiver.ACTION_REPLY) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
            )
            // 通知を払って消したら、覚えている会話も忘れる。以前は忘れず、次の通知に払ったはずの発言まで並んでいた
            builder.setDeleteIntent(action(NotificationActionReceiver.ACTION_DISMISSED, id * 2 + 1_000_000))
            val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("返信").build()
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "返信", action(NotificationActionReceiver.ACTION_REPLY, id * 2))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
            if (n.messageId != null) builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "既読にする", action(NotificationActionReceiver.ACTION_MARK_READ, id * 2 + 1))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        try {
            NotificationManagerCompat.from(context).notify(notifTag, 0, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 通知から送った返信を、その通知の会話に「自分」の発言として足す (#38)。
     * 元の通知が分からない (プロセスが作り直された) ときは false (呼び出し側で通知を消す)
     */
    suspend fun appendOwnReply(context: Context, channelId: String, text: String): Boolean {
        val last = synchronized(conversations) { lastNotices[channelId]?.takeIf { conversations.containsKey(channelId) } } ?: return false
        post(context, last, ownReply = text)
        return true
    }

    /** 既読時刻 [readTimes] が、通知に出している一番新しいメッセージ以降になったチャンネルの通知を消す (#49) */
    fun cancelRead(context: Context, readTimes: Map<String, String>) {
        val done = synchronized(conversations) {
            lastNotices.filter { (ch, n) ->
                val t = n.createdAt ?: return@filter false
                val read = readTimes[ch] ?: return@filter false
                !com.giracle.android.data.ChatStore.isAfter(t, read)
            }.keys.toList()
        }
        done.forEach { cancelChannel(context, it) }
    }

    /**
     * 通知から返信したチャンネルの通知を、既読による消去 (#49) の対象から外す。返信すると既読時刻が進むが、
     * 返信した会話の通知は残す決まり (#38) のため。次に新しい通知が来たら、また対象になる
     */
    fun keepAfterReply(channelId: String) {
        synchronized(conversations) { lastNotices[channelId]?.let { lastNotices[channelId] = it.copy(createdAt = null) } }
    }

    /** 通知が払って消されたとき。通知はもう無いので、覚えている会話だけ忘れる */
    fun forgetChannel(channelId: String) {
        synchronized(conversations) {
            conversations.remove(channelId)
            lastNotices.remove(channelId)
        }
    }

    /** チャンネルの通知を消す (そのチャンネルを開いたとき・通知のボタンで操作したとき) */
    fun cancelChannel(context: Context, channelId: String) {
        synchronized(conversations) {
            conversations.remove(channelId)
            lastNotices.remove(channelId)
        }
        NotificationManagerCompat.from(context).cancel(channelTag(channelId), 0)
    }

    /** 通知のボタンでの操作が失敗したことを、元の通知を置き換えて知らせる (タップでアプリを開く) */
    fun postError(context: Context, message: String, tag: String?, detail: String = "アプリを開いてもう一度試してください") {
        if (!canPost(context)) return
        val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(message)
            .setContentText(detail)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag ?: "giracle-error", 0, notif)
        } catch (_: SecurityException) {
        }
    }
}
