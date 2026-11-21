package com.giracle.android

import com.giracle.android.notify.PushRegistrar
import com.giracle.android.notify.PushRegistrationRetry
import com.giracle.android.notify.PushRegistrationRetry.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.unifiedpush.android.connector.FailedReason

/**
 * プッシュ通知の登録のやり直し (#93、D-076)。間隔・打ち切り・やり直さない失敗・止め方を、仮の時計で確かめる。
 * e2e (API 35) では、Google Play 開発者サービスが AUTHENTICATION_FAILED を返し、1 回の失敗で「登録しています」のまま止まっていた。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationRetryTest {
    /** register が呼ばれた時刻 (仮の時計) */
    private val calls = mutableListOf<Long>()

    private fun TestScope.retry(failEach: Boolean = false): PushRegistrationRetry {
        lateinit var r: PushRegistrationRetry
        r = PushRegistrationRetry(backgroundScope) { vapid ->
            assertEquals("KEY", vapid)
            calls += currentTime
            // 配信先がすぐ失敗を返す (e2e で見た約 10ms の失敗と同じ)
            if (failEach) r.failed(FailedReason.INTERNAL_ERROR)
        }
        return r
    }

    @Test fun retriesTemporaryFailureWithGrowingDelaysThenGivesUp() = runTest {
        val r = retry(failEach = true)
        r.start("KEY") {}
        r.failed(FailedReason.INTERNAL_ERROR) // 最初の 1 回の失敗
        assertEquals(State.Waiting(1, 5_000, FailedReason.INTERNAL_ERROR), r.state.value)
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        // 5 秒・30 秒・2 分・10 分の間をおいて 4 回やり直し、使い切ったら止まる
        assertEquals(listOf(5_000L, 35_000L, 155_000L, 755_000L), calls)
        assertEquals(State.GaveUp(FailedReason.INTERNAL_ERROR), r.state.value)
        assertTrue(PushRegistrar.retryStatus(r.state.value, embedded = true)!!.contains("時間をおいて「有効にする」"))
    }

    @Test fun networkFailureIsAlsoRetried() = runTest {
        val r = retry()
        r.start("KEY") {}
        r.failed(FailedReason.NETWORK)
        advanceTimeBy(5_001)
        assertEquals(listOf(5_000L), calls)
        assertEquals(State.Registering(1), r.state.value)
    }

    @Test fun successDuringWaitStopsRetry() = runTest {
        val r = retry()
        r.start("KEY") {}
        r.failed(FailedReason.INTERNAL_ERROR)
        advanceTimeBy(5_001)
        assertEquals(State.Registering(1), r.state.value)
        r.succeeded() // 1 回目のやり直しで宛先が届いた
        assertEquals(State.Idle, r.state.value)
        assertNull(PushRegistrar.retryStatus(r.state.value, embedded = true))
        advanceTimeBy(60 * 60_000L)
        assertEquals(1, calls.size)
    }

    @Test fun resetCancelsPendingRetryAndIgnoresLateFailure() = runTest {
        val r = retry()
        r.start("KEY") {}
        r.failed(FailedReason.INTERNAL_ERROR)
        r.reset() // 待つ間に無効にした
        advanceTimeBy(60 * 60_000L)
        assertTrue(calls.isEmpty())
        r.failed(FailedReason.INTERNAL_ERROR) // 前の登録の失敗が遅れて届いても、やり直しを始めない
        assertEquals(State.Idle, r.state.value)
    }

    @Test fun actionRequiredIsNotRetried() = runTest {
        val r = retry()
        r.start("KEY") {}
        r.failed(FailedReason.ACTION_REQUIRED)
        advanceTimeBy(60 * 60_000L)
        assertTrue(calls.isEmpty())
        assertEquals(State.NeedsAction(FailedReason.ACTION_REQUIRED), r.state.value)
        r.start("KEY") {}
        r.failed(FailedReason.VAPID_REQUIRED)
        assertEquals(State.NeedsAction(FailedReason.VAPID_REQUIRED), r.state.value)
    }

    @Test fun manualRequestRestartsCount() = runTest {
        val r = retry(failEach = true)
        r.start("KEY") {}
        r.failed(FailedReason.INTERNAL_ERROR)
        advanceTimeBy(36_000) // 2 回やり直して、3 回目 (2 分後) を待っている
        assertEquals(State.Waiting(3, 120_000, FailedReason.INTERNAL_ERROR), r.state.value)
        r.start("KEY") {} // 利用者が「有効にする」を押した
        r.failed(FailedReason.INTERNAL_ERROR)
        assertEquals(State.Waiting(1, 5_000, FailedReason.INTERNAL_ERROR), r.state.value)
        val before = calls.size
        advanceTimeBy(5_001)
        assertEquals(before + 1, calls.size) // 古い待ち (2 分) は捨て、5 秒後にやり直す
    }

    @Test fun firstRegisterExceptionCountsAsFailure() = runTest {
        // 最初の 1 回が例外で終わっても、「登録しています」のまま残さずにやり直しへ進む
        val r = retry()
        r.start("KEY") { error("keystore") }
        assertEquals(State.Waiting(1, 5_000, FailedReason.INTERNAL_ERROR), r.state.value)
        advanceTimeBy(5_001)
        assertEquals(listOf(5_000L), calls)
    }

    @Test fun cancelledStartDoesNotStayRegistering() = runTest {
        val r = retry()
        val job = launch { r.start("KEY") { kotlinx.coroutines.awaitCancellation() } }
        runCurrent()
        assertEquals(State.Registering(0), r.state.value)
        job.cancel()
        runCurrent()
        assertEquals(State.Idle, r.state.value)
    }

    @Test fun serverFailureIsShownAndStopsRetry() = runTest {
        val r = retry()
        r.start("KEY") {}
        r.serverFailed()
        assertEquals(State.ServerFailed, r.state.value)
        assertTrue(PushRegistrar.retryStatus(r.state.value, embedded = true)!!.contains("サーバー"))
        r.failed(FailedReason.INTERNAL_ERROR) // 返事を待っていないので無視する
        assertEquals(State.ServerFailed, r.state.value)
    }

    @Test fun registerExceptionCountsAsFailure() = runTest {
        lateinit var r: PushRegistrationRetry
        r = PushRegistrationRetry(backgroundScope) { calls += currentTime; error("keystore") }
        r.start("KEY") {}
        r.failed(FailedReason.INTERNAL_ERROR)
        advanceTimeBy(5_001)
        assertEquals(State.Waiting(2, 30_000, FailedReason.INTERNAL_ERROR), r.state.value)
    }
}
