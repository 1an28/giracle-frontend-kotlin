package com.giracle.android

import com.giracle.android.data.net.WsClient.State
import com.giracle.android.ui.common.ConnectionNotice
import com.giracle.android.ui.common.connectionNotices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 接続の知らせ (D-073): 短い切れ目では出さず、続いたら出し、戻ったら「つながりました」を少し出して消す */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionNoticeTest {
    private fun kotlinx.coroutines.test.TestScope.collect(states: MutableStateFlow<State>): MutableList<ConnectionNotice> {
        val seen = mutableListOf<ConnectionNotice>()
        backgroundScope.launch { connectionNotices(states, graceMs = 3_000, reconnectedMs = 1_500).collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test fun shortDropIsNotShown() = runTest {
        val s = MutableStateFlow(State.Connected)
        val seen = collect(s)
        s.value = State.Disconnected; runCurrent()
        advanceTimeBy(1_200); s.value = State.Connecting; runCurrent()
        advanceTimeBy(500); s.value = State.Connected; runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf(ConnectionNotice.None), seen)
    }

    @Test fun longDropIsShownThenReconnectedThenHidden() = runTest {
        val s = MutableStateFlow(State.Connected)
        val seen = collect(s)
        s.value = State.Disconnected; runCurrent()
        // 切れた → 接続中 と移っても、最初に切れた時から数える
        advanceTimeBy(2_000); s.value = State.Connecting; runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(ConnectionNotice.None, seen.last())
        advanceTimeBy(2); runCurrent()
        assertEquals(ConnectionNotice.Connecting, seen.last())
        // 出した後は待たずに切り替える
        s.value = State.Disconnected; runCurrent()
        assertEquals(ConnectionNotice.Offline, seen.last())
        s.value = State.Connected; runCurrent()
        assertEquals(ConnectionNotice.Reconnected, seen.last())
        advanceTimeBy(1_501); runCurrent()
        assertEquals(
            listOf(ConnectionNotice.None, ConnectionNotice.Connecting, ConnectionNotice.Offline, ConnectionNotice.Reconnected, ConnectionNotice.None),
            seen,
        )
    }

    /** 画面を開いた・前面に戻った直後は、切れていても待つ (前面に戻ったときのつなぎ直しでちらつかせない) */
    @Test fun startingDisconnectedWaitsToo() = runTest {
        val s = MutableStateFlow(State.Disconnected)
        val seen = collect(s)
        advanceTimeBy(800); s.value = State.Connecting; runCurrent()
        advanceTimeBy(800); s.value = State.Connected; runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf(ConnectionNotice.None), seen)
    }

    @Test fun authErrorIsShownAtOnce() = runTest {
        val s = MutableStateFlow(State.Connected)
        val seen = collect(s)
        s.value = State.AuthError; runCurrent()
        assertEquals(ConnectionNotice.AuthError, seen.last())
    }
}
