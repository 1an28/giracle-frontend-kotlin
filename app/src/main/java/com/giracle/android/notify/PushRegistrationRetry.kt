package com.giracle.android.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason

/**
 * 配信先への登録 (UnifiedPush の register) が失敗したときの、やり直しと状態 (#93、D-076)。
 *
 * 組み込みの FCM の配信は、Google Play 開発者サービスの準備 (端末の登録) が済んでいないと
 * `error=AUTHENTICATION_FAILED` を受け取り、UnifiedPush には INTERNAL_ERROR として返る。以前は 1 回の失敗で止まり、
 * 「登録しています」のまま有効にならなかった。一時的な失敗 (INTERNAL_ERROR・NETWORK) は [delaysMs] の間をおいてやり直し、
 * 使い切ったら [State.GaveUp] にする。利用者の操作が要る失敗 (ACTION_REQUIRED・VAPID_REQUIRED) はやり直さない。
 *
 * 持ち主は [scope] (アプリ全体の裏の処理) で、始まりは [requested]、止まるのは [succeeded]・[reset]・使い切り。
 * プロセスが終わればやり直しも消える (次の起動で、自動の有効化がまた 1 回目から試す)。
 */
class PushRegistrationRetry(
    private val scope: CoroutineScope,
    private val delaysMs: List<Long> = DEFAULT_DELAYS_MS,
    private val register: suspend (vapid: String) -> Unit,
) {
    sealed interface State {
        /** 登録を頼んでいない (または有効になった後・無効にした後) */
        data object Idle : State
        /** 配信先に登録を頼み、返事を待っている。[retry] は何回目のやり直しか (0 は最初の 1 回) */
        data class Registering(val retry: Int) : State
        /** 一時的な失敗。[delayMs] 後に [retry] 回目のやり直しをする */
        data class Waiting(val retry: Int, val delayMs: Long, val reason: FailedReason) : State
        /** やり直しを使い切った */
        data class GaveUp(val reason: FailedReason) : State
        /** 利用者の操作が要る失敗 (やり直さない) */
        data class NeedsAction(val reason: FailedReason) : State
        /** 配信先からは宛先を受け取ったが、Giracle のサーバーへの端末の登録が失敗した (やり直さない) */
        data object ServerFailed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var vapid: String? = null
    private var job: Job? = null

    /**
     * 配信先に登録を頼む (最初の 1 回)。やり直しの回数を数え直してから [doRegister] を呼ぶ。
     * 頼むこと自体が例外で終われば失敗として数え、取り消されたら状態を戻す (「登録しています」のまま残さない)
     */
    suspend fun start(vapid: String, doRegister: suspend (vapid: String) -> Unit = register) {
        requested(vapid)
        try {
            doRegister(vapid)
        } catch (e: CancellationException) {
            synchronized(lock) { if (_state.value == State.Registering(0)) _state.value = State.Idle }
            throw e
        } catch (e: Exception) {
            failed(FailedReason.INTERNAL_ERROR)
        }
    }

    /** やり直しの回数を数え直し、返事を待つ状態にする。登録は呼び出し側が頼む ([start] を使う) */
    internal fun requested(vapid: String): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        this.vapid = vapid
        _state.value = State.Registering(0)
    }

    /** 配信先から失敗が返った ([PushServiceImpl.onRegistrationFailed])。返事を待っていないとき (無効にした後など) は何もしない */
    fun failed(reason: FailedReason): Unit = synchronized(lock) {
        val current = _state.value as? State.Registering ?: return@synchronized
        val key = vapid ?: return@synchronized
        if (!isTemporary(reason)) {
            _state.value = State.NeedsAction(reason)
            return@synchronized
        }
        val next = current.retry + 1
        val wait = delaysMs.getOrNull(next - 1)
        if (wait == null) {
            _state.value = State.GaveUp(reason)
            return@synchronized
        }
        _state.value = State.Waiting(next, wait, reason)
        job = scope.launch {
            delay(wait)
            synchronized(lock) {
                // 待つ間に数え直された・止められたなら、やり直さない
                if (_state.value != State.Waiting(next, wait, reason)) return@launch
                _state.value = State.Registering(next)
            }
            try {
                register(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 頼むこと自体が失敗した (鍵の生成など)。配信先の失敗と同じに数える
                failed(FailedReason.INTERNAL_ERROR)
            }
        }
    }

    /** 新しい宛先を受け取り、サーバーへの端末の登録も済んだ ([PushServiceImpl.onNewEndpoint]) */
    fun succeeded(): Unit = reset()

    /** 宛先は受け取ったが、サーバーへの端末の登録が失敗した */
    fun serverFailed(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        _state.value = State.ServerFailed
    }

    /** やり直しを止める (無効にした・ログアウトした) */
    fun reset(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    companion object {
        /** やり直しの間隔: 5 秒・30 秒・2 分・10 分 (合わせて約 13 分)。見立ては D-076 */
        val DEFAULT_DELAYS_MS = listOf(5_000L, 30_000L, 120_000L, 600_000L)

        /** UnifiedPush の説明で「すぐにやり直してよい」(INTERNAL_ERROR)・「回線が戻ったら」(NETWORK) とされるもの */
        fun isTemporary(reason: FailedReason) = reason == FailedReason.INTERNAL_ERROR || reason == FailedReason.NETWORK
    }
}
