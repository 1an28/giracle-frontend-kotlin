package com.giracle.android.data

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 送信の経路の記録 (#94、D-079)。押した・要求を出した・応答・失敗・送信待ちへ回した・取り消された、を 1 行ずつ残す。
 * 送ったものがサーバーに届かなかったときに、どこまで進んだかを配布版のログから切り分けるため。
 * 1 回の送信で数行しか出さないので、配布版でも残す。本文・宛先などの中身は記録しない (長さと有無だけ)。
 * 同じ送信の行は `send#番号` でつなぐ (番号はプロセスの中の通し番号)。
 */
object SendLog {
    const val TAG = "GiracleSend"
    private val seq = AtomicInteger()

    /** 新しい送信の番号 */
    fun next(): Int = seq.incrementAndGet()

    fun log(n: Int, event: String, detail: String = "") {
        Log.i(TAG, "send#$n $event" + if (detail.isEmpty()) "" else " $detail")
    }
}
