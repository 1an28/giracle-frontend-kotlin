package com.giracle.android.notify

import android.app.Activity
import android.content.Context
import android.util.Log
import com.giracle.android.AppGraph
import com.giracle.android.data.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * プッシュ通知の登録・解除をまとめたもの (D-019)。
 *
 * 受け取りは UnifiedPush。配信アプリ (ntfy など) が入っていればそれを使い、無ければアプリに組み込んだ
 * 配信 (embedded FCM distributor) が Google Play 開発者サービス経由で受け取る。どちらでも宛先は Web Push と同じ形式なので、
 * サーバーには platform="web" の端末として登録し、サーバーの既存の Web Push (VAPID) がそのまま届く。
 * 組み込みの配信は Firebase プロジェクトも google-services.json も使わない (VAPID 公開鍵を送信者として登録する)。
 *
 * 登録が済むと [PushServiceImpl.onNewEndpoint] が呼ばれ、そこでサーバーに端末を登録する。
 */
object PushRegistrar {
    private const val TAG = "GiraclePush"

    sealed interface Result {
        /** 配信先に登録を頼んだ。数秒後に [PushServiceImpl.onNewEndpoint] で有効になる */
        data object Requested : Result
        /** 使える配信先が無い (Google Play 開発者サービスも配信アプリも無い) */
        data object NoDistributor : Result
        /** サーバーに VAPID 鍵が設定されていない */
        data object ServerNotConfigured : Result
        data class Failed(val error: Throwable) : Result
    }

    /** 自動で有効にするのを試したユーザー (同じプロセスで同じユーザーに何度も試さない) */
    @Volatile private var autoTriedFor: String? = null

    /**
     * ログイン後に自動で有効にする。利用者が無効にしていない・未登録・配信先が 1 つに決まる、の 3 つを満たすときだけ。
     * 配信先を選ぶ画面が要る場合 (配信アプリが複数あるなど) は何もしない (設定画面から選んでもらう)。
     */
    suspend fun autoEnable(context: Context, graph: AppGraph, userId: String) {
        val prefs = graph.prefs
        if (prefs.pushOptOut || prefs.pushEndpoint != null) return
        // 通知の権限がまだなら、許可された後にもう一度呼ばれる
        if (!Notifier.canPost(context)) return
        if (autoTriedFor == userId) return
        autoTriedFor = userId
        val resolved = runCatching { UnifiedPush.getAckDistributor(context) }.getOrNull()
            ?: when (val r = runCatching { UnifiedPush.resolveDefaultDistributor(context) }.getOrNull()) {
                is ResolvedDistributor.Found -> r.packageName
                else -> return
            }
        val key = try {
            graph.api.vapidPublicKey()
        } catch (e: Exception) {
            Log.i(TAG, "auto enable skipped: ${e.message}")
            return
        }
        // 登録は Android Keystore で鍵を作るので、時間がかかることがある (エミュレータの起動直後に 20 秒以上かかり、
        // 画面が固まった)。画面の処理の流れ (メインスレッド) から外して行う
        graph.pushRetry.start(key) { vapid ->
            withContext(Dispatchers.IO) {
                UnifiedPush.saveDistributor(context, resolved)
                UnifiedPush.register(context, vapid = vapid)
            }
        }
        Log.i(TAG, "auto enable requested ($resolved)")
    }

    /** 設定画面の「有効にする」。配信アプリが複数あれば、OS の選択画面が出ることがある */
    suspend fun enable(activity: Activity, graph: AppGraph, onResult: (Result) -> Unit) {
        val key = try {
            graph.api.vapidPublicKey()
        } catch (e: ApiException) {
            onResult(if (e.code == 503) Result.ServerNotConfigured else Result.Failed(e))
            return
        } catch (e: Exception) {
            onResult(Result.Failed(e))
            return
        }
        graph.prefs.pushOptOut = false
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                // 鍵の生成に時間がかかることがあるので、画面の処理の流れから外す (autoEnable と同じ)
                val app = activity.applicationContext
                graph.scope.launch(Dispatchers.IO) { graph.pushRetry.start(key) { UnifiedPush.register(app, vapid = it) } }
                onResult(Result.Requested)
            } else {
                onResult(Result.NoDistributor)
            }
        }
    }

    /** 設定画面の「無効にする」。以後、自動では有効にしない */
    fun disable(context: Context, graph: AppGraph) {
        graph.prefs.pushOptOut = true
        graph.pushRetry.reset()
        val app = context.applicationContext
        graph.scope.launch(Dispatchers.IO) { UnifiedPush.unregister(app) }
    }

    /**
     * ログアウトの前に呼ぶ。サーバーから端末の登録を消し (ログアウト後は消せない)、配信先の登録も解く。
     * 次にログインしたときは、また自動で有効にする。
     */
    suspend fun onSignOut(context: Context, graph: AppGraph) {
        val ep = graph.prefs.pushEndpoint
        if (ep != null) runCatching { graph.api.deviceUnregister(ep) }
        graph.prefs.pushEndpoint = null
        graph.pushRetry.reset()
        withContext(Dispatchers.IO) { runCatching { UnifiedPush.unregister(context) } }
        autoTriedFor = null
    }

    /** 設定画面に出す、今の状態の説明。[retry] は登録のやり直しの状態 (#93、D-077) */
    fun status(context: Context, endpoint: String?, retry: PushRegistrationRetry.State): String {
        if (endpoint == null) retryStatus(retry, embedded = runCatching { UnifiedPush.getSavedDistributor(context) }.getOrNull() == context.packageName)?.let { return it }
        val dists = runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())
        return when {
            endpoint != null -> "有効 (" + distributorLabel(context, UnifiedPush.getAckDistributor(context)) + ")"
            dists.isEmpty() -> "使えません: Google Play 開発者サービスも、UnifiedPush の配信アプリ (ntfy など) もありません。" +
                "「バックグラウンドでも接続を保つ」を使ってください"
            else -> "無効"
        }
    }

    /**
     * 登録の途中・失敗の説明。説明が要らない (登録を頼んでいない) ときは null。
     * [embedded] は組み込みの配信 (Google Play 開発者サービス経由) を使っているか
     */
    fun retryStatus(retry: PushRegistrationRetry.State, embedded: Boolean): String? {
        val via = if (embedded) "Google Play 開発者サービス" else "配信アプリ"
        return when (retry) {
            PushRegistrationRetry.State.Idle -> null
            is PushRegistrationRetry.State.Registering ->
                if (retry.retry == 0) REGISTERING else "登録をやり直しています (${retry.retry} 回目)"
            is PushRegistrationRetry.State.Waiting ->
                "登録できませんでした ($via が準備中の可能性があります)。" +
                    "${formatDelay(retry.delayMs)}後に自動でやり直します (${retry.retry} 回目)"
            is PushRegistrationRetry.State.GaveUp ->
                "登録できませんでした。何度かやり直しましたが、$via が応答しません。" +
                    "時間をおいて「有効にする」を押してください。直らなければ「バックグラウンドでも接続を保つ」を使ってください"
            PushRegistrationRetry.State.ServerFailed ->
                "登録できませんでした: Giracle のサーバーに端末を登録できませんでした。回線を確かめてから「有効にする」を押してください"
            is PushRegistrationRetry.State.NeedsAction -> when (retry.reason) {
                org.unifiedpush.android.connector.FailedReason.VAPID_REQUIRED ->
                    "登録できませんでした: 配信先が、このサーバーの方式 (VAPID) に対応していません"
                else -> if (embedded) "登録できませんでした: Google Play 開発者サービスが使えない状態です。" +
                    "入っているか・有効になっているかを確かめてから、もう一度押してください"
                else "登録できませんでした: 配信アプリで操作 (ログインなど) が必要です。配信アプリを開いてから、もう一度押してください"
            }
        }
    }

    /** 「数秒で有効になります」の案内 (e2e もこの文言の表示を手がかりにする) */
    const val REGISTERING = "登録しています。数秒で有効になります"

    private fun formatDelay(ms: Long): String = if (ms < 60_000) "${ms / 1000} 秒" else "${ms / 60_000} 分"

    /** 組み込みの配信 (自分自身) なら「Google 経由」、外部の配信アプリならその名前 */
    fun distributorLabel(context: Context, pkg: String?): String = when (pkg) {
        null -> "配信先: 不明"
        context.packageName -> "Google Play 開発者サービス経由"
        else -> "配信アプリ: " + runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
