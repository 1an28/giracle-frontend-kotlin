package com.giracle.android.data

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自分の送信が画面に見えるまでの区切りを記録する (#70、D-056)。
 * e2e (`uxTasks` の reply) がこの記録を読み、送信を押してから見えるまでの内訳を出す。
 * 時刻は端末全体で共通の `SystemClock.uptimeMillis()` なので、e2e のプロセスの時刻と引き算できる。
 * 1 回の送信で数行しか出さないので、配布版でも残す。
 */
object SendTrace {
    const val TAG = "GiraclePerf"

    /**
     * 送信を押した直後に何がスレッドを塞いでいるかの記録 (D-068)。
     * `adb shell setprop log.tag.GiraclePerfTrace VERBOSE` のときだけ動く (e2e が付ける。利用者の端末では動かない)
     */
    const val PROFILE_TAG = "GiraclePerfTrace"
    private const val PROFILE_MS = 500L
    private const val SAMPLE_MS = 20L
    private const val MAX_FRAMES = 14
    private val profiling = AtomicBoolean(false)

    fun mark(phase: String, detail: String = "") {
        Log.i(TAG, "send phase=$phase t=${SystemClock.uptimeMillis()}" + if (detail.isEmpty()) "" else " $detail")
    }

    /**
     * 押した時点から [PROFILE_MS] の間、(1) メインスレッドの描画の間隔と、(2) メインスレッドと Dispatchers.Default の
     * スレッドのスタックを [SAMPLE_MS] ごとに記録する。メインスレッドから呼ぶ。
     * スタックを取る側は、塞がれているかもしれない Dispatchers.Default では動かせないので、自分で終わる専用のスレッドにする
     * (長くても [PROFILE_MS] で終わり、失敗はログに出す)。前の記録が終わっていなければ何もしない
     */
    fun profileAfterTap() {
        if (!Log.isLoggable(PROFILE_TAG, Log.VERBOSE)) return
        if (!profiling.compareAndSet(false, true)) return
        val t0 = SystemClock.uptimeMillis()
        recordFrames(t0)
        val main = Looper.getMainLooper().thread
        Thread({
            try {
                sampleStacks(t0, main)
            } catch (e: Throwable) {
                Log.w(PROFILE_TAG, "sampling failed", e)
            } finally {
                profiling.set(false)
            }
        }, "GiraclePerfSampler").apply { isDaemon = true }.start()
    }

    /** 描画ごとに、押してからの時刻 (ms) を集め、終わりに 1 行で出す。間が 16ms を大きく超えたところが、メインスレッドの塞がり */
    private fun recordFrames(t0: Long) {
        val times = ArrayList<Long>()
        val choreographer = Choreographer.getInstance()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = SystemClock.uptimeMillis()
                times += now - t0
                if (now - t0 < PROFILE_MS) choreographer.postFrameCallback(this)
                else Log.i(PROFILE_TAG, "frames t0=$t0 at=$times")
            }
        }
        choreographer.postFrameCallback(cb)
    }

    private fun sampleStacks(t0: Long, main: Thread) {
        // Default のスレッドは後から増えることがあるので、取るたびに探し直す (数が少ないので重くない)
        val last = HashMap<Thread, List<StackTraceElement>>()
        var n = 0
        while (true) {
            val at = SystemClock.uptimeMillis() - t0
            if (at > PROFILE_MS) break
            val threads = listOf(main) + defaultWorkers()
            for (th in threads) {
                val st = th.stackTrace.take(MAX_FRAMES)
                if (st.isEmpty() || isIdle(st)) { last.remove(th); continue }
                if (last[th] == st) {
                    Log.i(PROFILE_TAG, "sample n=$n at=$at th=${th.name} same")
                    continue
                }
                last[th] = st
                Log.i(PROFILE_TAG, "sample n=$n at=$at th=${th.name} state=${th.state}")
                // 1 行に 1 つの呼び出し。CI で retrace (R8 の名前を元に戻す) にかけられる形にする
                st.forEach { Log.i(PROFILE_TAG, "    at $it") }
            }
            n++
            SystemClock.sleep(SAMPLE_MS)
        }
        Log.i(PROFILE_TAG, "sample end t0=$t0 samples=$n")
    }

    /** Dispatchers.Default のスレッド。Thread.getAllStackTraces は全スレッドのスタックを取って重いので、スレッドの一覧だけを読む */
    private fun defaultWorkers(): List<Thread> {
        var root = Thread.currentThread().threadGroup
        while (root?.parent != null) root = root.parent
        val all = arrayOfNulls<Thread>((root?.activeCount() ?: 0) * 2 + 16)
        val count = root?.enumerate(all, true) ?: 0
        return (0 until count).mapNotNull { all[it] }.filter { it.name.startsWith("DefaultDispatcher-worker") }
    }

    /** 仕事を待っているだけのスレッド (メインの nativePollOnce、Default の park) */
    private fun isIdle(st: List<StackTraceElement>): Boolean =
        st.take(4).any { it.methodName == "nativePollOnce" || it.methodName.startsWith("park") }
}
