package com.giracle.android.ui.channel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange

/*
 * 入力欄のメンションを `@名前` で見せる (#143、D-081)。
 *
 * 入力欄の状態 (TextFieldState) は送る本文と同じ `@<id>` を持ったままにし、表示だけを OutputTransformation で
 * `@名前` に変える。下書き・送信待ち・編集の本文は今までどおり `@<id>` のまま扱える。
 * IME (キーボード) には表示側の文字 (`@名前`) が見え、IME の編集は Compose が元の `@<id>` の位置に写して当てる。
 * 写した結果がメンションの中に入る編集 (Backspace でメンションの末尾を消す・メンションの中をタップする) を
 * InputTransformation で直し、メンションは 1 つのまとまりとして消える・カーソルは中に入らない、にする。
 *
 * 日本語入力 (変換中) を壊さないため、InputTransformation は編集がメンションの中に触れたときだけ本文や選択を変える。
 * 本文や選択を変えると、Compose は変換中の文字を確定させてしまうため (TextFieldState.syncMainBufferToTemporaryBuffer)。
 * メンションの外 (前後の端を含む) での変換は、何も変えずに通す。
 */

/** 入力欄の本文の中の、名前の分かっているメンション 1 つ。[start]..[end] は本文 (`@<id>`) での位置 */
internal data class MentionSpan(val start: Int, val end: Int, val name: String)

/** 受け取ったメッセージの描画 (MessageParser) と同じ形 */
private val MENTION = Regex("""@<([a-zA-Z0-9-]+)>""")

/** 本文の中のメンションのうち、名前の分かるものを返す。名前が分からない id は `@<id>` のまま見せるので含めない */
internal fun findMentions(text: CharSequence, nameOf: (String) -> String?): List<MentionSpan> {
    if (!text.contains("@<")) return emptyList()
    return MENTION.findAll(text).mapNotNull { m ->
        nameOf(m.groupValues[1])?.let { MentionSpan(m.range.first, m.range.last + 1, it) }
    }.toList()
}

/** 本文に含まれるメンションの id (名前を取りに行くため) */
internal fun mentionIds(text: CharSequence): Set<String> =
    if (!text.contains("@<")) emptySet() else MENTION.findAll(text).map { it.groupValues[1] }.toSet()

/**
 * 選択 (カーソル) がメンションの中に入っていたら、端へ寄せる。入っていなければ [sel] をそのまま返す。
 * - メンションの中をタップする・IME でカーソルを動かすと、Compose は `@名前` の中の位置を `@<id>` 全体
 *   (start..end) に写す (OffsetMappingCalculator の置き換えの扱い)。直前がカーソル (幅 0) で、
 *   ちょうど 1 つのメンションを前向きに選んだ形になったら、これをカーソルに戻す。
 *   本文 (`@<id>`) の位置で中に置かれた場合 (読み上げなど) も同じ。
 *   直前のカーソルが末尾にあれば先頭へ (左へ動かした)、先頭にあれば末尾へ (右へ動かした)、それ以外は末尾へ寄せる。
 * - メンションをまたぐ選択は、メンション全体を含むように広げる (選んで消すと、まとめて消える)。
 */
internal fun snapSelection(previous: TextRange, sel: TextRange, mentions: List<MentionSpan>): TextRange {
    if (mentions.isEmpty()) return sel
    fun toEdge(m: MentionSpan): TextRange = TextRange(
        when {
            previous.collapsed && previous.start == m.end -> m.start
            previous.collapsed && previous.start == m.start -> m.end
            else -> m.end
        },
    )
    if (previous.collapsed && !sel.collapsed && !sel.reversed) {
        mentions.firstOrNull { it.start == sel.start && it.end == sel.end }?.let { return toEdge(it) }
    }
    fun inside(offset: Int) = mentions.firstOrNull { offset > it.start && offset < it.end }
    val a = inside(sel.start)
    val b = if (sel.collapsed) a else inside(sel.end)
    if (a == null && b == null) return sel
    if (a != null && a == b) return toEdge(a)
    // 向き (reversed) を保ったまま、外側へ広げる
    return if (!sel.reversed) {
        TextRange(a?.start ?: sel.start, b?.end ?: sel.end)
    } else {
        TextRange(a?.end ?: sel.start, b?.start ?: sel.end)
    }
}

/**
 * 1 回の編集を、メンションを壊さない形に直した結果。直す必要がなければ null。
 * [replaceStart]..[replaceEnd] (編集前の本文の位置) を [text] に置き換え、カーソルを [cursor] に置く。
 */
internal data class MentionRepair(val replaceStart: Int, val replaceEnd: Int, val text: String, val cursor: Int)

/**
 * 編集前の本文で [oMin]..[oMax] を [inserted] に置き換えた編集が、メンションを壊すなら直し方を返す。
 * [cursorEdit] は、編集の前が選択ではなくカーソルだったか (IME の変換・削除はこの形。選んで打ち替えたときは false)。
 *
 * 1. メンションの一部だけにかかる編集 (本文の位置で中を消すなど): メンションごと消し、入れた文字は残す
 * 2. メンションの中に文字を入れるだけの編集: メンションの直後に入れる
 * 3. IME が `@名前` の単語を変換し直した編集 (Gboard などは、カーソルの前の単語を変換中に戻すことがある。
 *    Compose はそれをメンション全体の置き換えとして当てる): 入れた文字が名前で始まれば、メンションを残して
 *    後ろだけ足す。名前を途中まで消した形なら、メンションごと消す (Backspace でまとめて消えるのと同じ)
 * 端 (`@` の前・`>` の後) での編集は、メンションの外として何もしない (変換中の文字がメンションに続いていても触らない)。
 */
internal fun repairEdit(
    oMin: Int,
    oMax: Int,
    inserted: String,
    mentions: List<MentionSpan>,
    cursorEdit: Boolean,
): MentionRepair? {
    if (oMin == oMax) {
        val t = mentions.firstOrNull { oMin > it.start && oMin < it.end } ?: return null
        return MentionRepair(t.end, t.end, inserted, t.end + inserted.length)
    }
    // 一部だけにかかるメンション (すべてを含むものは 3. で見る)
    val damaged = mentions.filter { it.start < oMax && it.end > oMin && !(oMin <= it.start && oMax >= it.end) }
    if (damaged.isNotEmpty()) {
        val start = minOf(oMin, damaged.first().start)
        val end = maxOf(oMax, damaged.last().end)
        return MentionRepair(start, end, inserted, start + inserted.length)
    }
    if (!cursorEdit || inserted.isEmpty()) return null
    val t = mentions.firstOrNull { it.start == oMin && it.end <= oMax } ?: return null
    // 置き換えた範囲に、ほかのメンションが含まれるなら触らない
    if (mentions.any { it !== t && it.start < oMax && it.end > oMin }) return null
    for (label in listOf("@" + t.name, t.name)) {
        if (inserted.startsWith(label)) {
            val rest = inserted.substring(label.length)
            return MentionRepair(t.end, oMax, rest, t.end + rest.length)
        }
    }
    if (oMax == t.end && (("@" + t.name).startsWith(inserted) || t.name.startsWith(inserted))) {
        return MentionRepair(t.start, t.end, "", t.start)
    }
    return null
}

/**
 * 入力欄に付ける 2 つの変換。[nameOf] は利用者の名前を引く (名前が無い・分からない id は null)。[style] は `@名前` の見た目。
 * [nameOf] の中で Compose の State を読むと、名前が届いたときに表示が作り直される (OutputTransformation の結果は
 * 中で読んだ State が変わると計算し直される)。
 */
// 編集の範囲 (TextFieldBuffer.changes) が実験的な API のため
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class MentionTransformations(private val nameOf: (String) -> String?, private val style: SpanStyle) {

    val output = OutputTransformation {
        val mentions = findMentions(asCharSequence(), nameOf)
        // 後ろから置き換えて、前の位置をずらさない
        for (m in mentions.asReversed()) {
            val label = "@" + m.name
            replace(m.start, m.end, label)
            addStyle(style, m.start, m.start + label.length)
        }
    }

    val input = InputTransformation { fixMentions(this) }

    private fun fixMentions(buf: TextFieldBuffer) {
        val before = buf.originalText
        val mentions = findMentions(before, nameOf)
        if (mentions.isEmpty()) return
        val changes = buf.changes
        if (changes.changeCount == 0) {
            val snapped = snapSelection(buf.originalSelection, buf.selection, mentions)
            if (snapped != buf.selection) buf.selection = snapped
            return
        }
        // 編集の範囲をまとめる。範囲の外は編集の前後で変わらないので、前は同じ位置から、後ろは同じ長さだけ残る
        var oMin = Int.MAX_VALUE
        var oMax = Int.MIN_VALUE
        var nMin = Int.MAX_VALUE
        var nMax = Int.MIN_VALUE
        for (i in 0 until changes.changeCount) {
            val o = changes.getOriginalRange(i)
            val n = changes.getRange(i)
            oMin = minOf(oMin, o.min); oMax = maxOf(oMax, o.max)
            nMin = minOf(nMin, n.min); nMax = maxOf(nMax, n.max)
        }
        val inserted = buf.asCharSequence().substring(nMin, nMax)
        val repair = repairEdit(oMin, oMax, inserted, mentions, cursorEdit = buf.originalSelection.collapsed)
        if (repair == null) {
            // 本文は直さない。選択がメンションの中に入っていれば端へ寄せる (編集の後の本文で探す)
            val after = findMentions(buf.asCharSequence(), nameOf)
            val snapped = snapSelection(buf.originalSelection, buf.selection, after)
            if (snapped != buf.selection) buf.selection = snapped
            return
        }
        buf.revertAllChanges()
        buf.replace(repair.replaceStart, repair.replaceEnd, repair.text)
        buf.selection = TextRange(repair.cursor)
    }
}
