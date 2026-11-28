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

/**
 * 入力欄の本文の中の、名前の分かっているメンション 1 つ。[start]..[end] は本文 (`@<id>`・`#<id>`) での位置。
 * [prefix] は `@` (利用者) か `#` (チャンネル。Web 版 PR #252 に合わせて同じ扱いにした、D-088)
 */
internal data class MentionSpan(val start: Int, val end: Int, val name: String, val prefix: Char = '@')

/** 受け取ったメッセージの描画 (MessageParser・ChatStore.plainText) と同じ形 */
private val MENTION = Regex("""@<([a-zA-Z0-9-]+)>""")
private val MENTION_OR_CHANNEL = Regex("""([@#])<([a-zA-Z0-9-]+)>""")

/**
 * 本文の中のメンションのうち、名前の分かるものを返す。名前が分からない id は `@<id>`・`#<id>` のまま見せるので含めない。
 * [nameOf] は利用者の名前、[channelNameOf] はチャンネルの名前を引く
 */
internal fun findMentions(
    text: CharSequence,
    nameOf: (String) -> String?,
    channelNameOf: (String) -> String? = { null },
): List<MentionSpan> {
    if (!text.contains("@<") && !text.contains("#<")) return emptyList()
    return MENTION_OR_CHANNEL.findAll(text).mapNotNull { m ->
        val prefix = m.groupValues[1][0]
        val id = m.groupValues[2]
        val name = if (prefix == '@') nameOf(id) else channelNameOf(id)
        name?.let { MentionSpan(m.range.first, m.range.last + 1, it, prefix) }
    }.toList()
}

/** 本文に含まれるメンションとチャンネルへの参照 (`@<id>`・`#<id>` の形のまま)。名前が変わったかを見るため */
internal fun mentionTokens(text: CharSequence): Set<String> =
    if (!text.contains("@<") && !text.contains("#<")) emptySet() else MENTION_OR_CHANNEL.findAll(text).map { it.value }.toSet()

/** 本文に含まれるメンションの id (名前を取りに行くため) */
internal fun mentionIds(text: CharSequence): Set<String> =
    if (!text.contains("@<")) emptySet() else MENTION.findAll(text).map { it.groupValues[1] }.toSet()

/**
 * 表示 (`@名前`) の中の位置と、本文 (`@<id>`) の中の位置の対応。[MentionTransformations.output] の置き換え方と同じ。
 * 本文の `@<id>` のうち `@` はそのまま、`<id>` (長さ [bodyLen]) の先頭から名前を 1 文字ずつ写し、
 * - 名前が短いとき: 名前の最後の 1 文字を、本文の残り全部と置き換える
 * - 名前が長いとき: 本文の最後の 1 文字の前に、名前の余りを差し込む
 * こうすると、表示のどの位置も本文の 1 つの位置に写る (Compose は、2 文字以上を置き換えた範囲の中の位置を、範囲全体に写す。
 * 1 文字ずつなら範囲にならず、タップした位置がメンション全体の選択にならない)。
 * [rawOffset] はメンションの先頭からの本文の位置 (0..bodyLen+1)、返すのは `@名前` の先頭からの表示の位置
 */
internal fun labelOffset(rawOffset: Int, nameLen: Int, bodyLen: Int): Int = when {
    rawOffset <= 0 -> 0
    rawOffset >= bodyLen + 1 -> nameLen + 1
    nameLen == 0 -> 1
    nameLen <= bodyLen -> minOf(rawOffset, nameLen)
    else -> rawOffset // 名前が長いとき、本文の最後の 1 文字の前 (bodyLen) は差し込んだ範囲の先頭
}

/** [labelOffset] の置き換えを、出力用のバッファに当てる。[from]..[to] は `<id>` の範囲 */
private fun TextFieldBuffer.replaceCharByChar(from: Int, to: Int, name: String) {
    val bodyLen = to - from
    val n = name.length
    if (n == 0) { replace(from, to, ""); return }
    if (n <= bodyLen) {
        for (i in 0 until n - 1) replace(from + i, from + i + 1, name[i].toString())
        replace(from + n - 1, to, name[n - 1].toString())
    } else {
        for (i in 0 until bodyLen - 1) replace(from + i, from + i + 1, name[i].toString())
        val mid = name.substring(bodyLen - 1, n - 1)
        replace(to - 1, to - 1, mid)
        val last = to - 1 + mid.length
        replace(last, last + 1, name[n - 1].toString())
    }
}

/**
 * 選択 (カーソル) がメンションの中に入っていたら、端へ寄せる。入っていなければ [sel] をそのまま返す。
 * - カーソルが中に入った (メンションの中をタップした・矢印キーや IME で動かした): 端へ寄せる。
 *   直前のカーソルが末尾にあれば先頭へ (左へ動かした)、先頭にあれば末尾へ (右へ動かした)、それ以外は末尾へ寄せる
 * - 端が中にある選択 (Shift+→・単語の選択・選択のつまみ): メンション全体を含むように広げる (選んで消すと、まとめて消える)。
 *   全体の選択など、端がメンションの外にある選択はそのまま
 */
internal fun snapSelection(previous: TextRange, sel: TextRange, mentions: List<MentionSpan>): TextRange {
    if (mentions.isEmpty()) return sel
    fun inside(offset: Int) = mentions.firstOrNull { offset > it.start && offset < it.end }
    if (sel.collapsed) {
        val m = inside(sel.start) ?: return sel
        return TextRange(
            when {
                previous.collapsed && previous.start == m.end -> m.start
                previous.collapsed && previous.start == m.start -> m.end
                else -> m.end
            },
        )
    }
    val a = inside(sel.start)
    val b = inside(sel.end)
    if (a == null && b == null) return sel
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
 * 編集前の本文 [original] の [oMin]..[oMax] を [inserted] に置き換えた編集が、メンションを壊すなら直し方を返す。
 * [cursorEdit] は、編集の前が選択ではなくカーソルだったか (IME の変換・自動修正・削除はこの形。選んで打ち替えたときは false)。
 * 端 (`@` の前・`>` の後) だけに触れる編集は、メンションの外として何もしない (変換中の文字がメンションに続いていても触らない)。
 *
 * - メンションの中に文字を入れるだけ: メンションの直後に入れる
 * - 選んで打ち替えた (cursorEdit = false): 一部だけにかかったメンションはまとめて消し、入れた文字は残す
 * - IME の編集 (cursorEdit = true)。Gboard などは、カーソルの前の単語 (`@名前` の名前) を変換中に戻して、
 *   打ち足し・自動修正・削除をすることがある。消すと通知が飛ばなくなるので、迷ったらメンションを残す:
 *   - 消すだけ (Backspace など) で、メンションの一部にかかる: メンションごと消す
 *   - 入れた文字に名前 (大文字・小文字は区別しない) が含まれる: 名前の前後だけを入れ、メンションは残す
 *   - 名前を後ろから縮めた形 (変換中の名前を Backspace で消した): メンションごと消す
 *   - それ以外 (名前と関係ない自動修正など): 編集を取り消してメンションを残す
 */
internal fun repairEdit(
    original: CharSequence,
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
    val touched = mentions.filter { it.start < oMax && it.end > oMin }
    if (touched.isEmpty()) return null
    val partial = touched.any { oMin > it.start || oMax < it.end }
    val start = minOf(oMin, touched.first().start)
    val end = maxOf(oMax, touched.last().end)
    val deleteWhole = MentionRepair(start, end, inserted, start + inserted.length)
    if (!cursorEdit || inserted.isEmpty() || touched.size > 1) return if (partial) deleteWhole else null

    val t = touched.single()
    val token = original.substring(t.start, t.end)
    val left = original.substring(start, t.start) // メンションより前の、置き換えた範囲
    val right = original.substring(t.end, end) // メンションより後の、置き換えた範囲
    for (label in listOf(t.prefix + t.name, t.name)) {
        val i = inserted.indexOf(label, ignoreCase = true)
        if (i >= 0) {
            val text = inserted.substring(0, i) + token + inserted.substring(i + label.length)
            return MentionRepair(start, end, text, start + text.length)
        }
    }
    // IME から見えていた文字 (置き換えた範囲の表示)
    val label = t.prefix + t.name
    val bodyLen = t.end - t.start - 1
    val seenLabel = label.substring(
        labelOffset(maxOf(oMin, t.start) - t.start, t.name.length, bodyLen),
        labelOffset(minOf(oMax, t.end) - t.start, t.name.length, bodyLen),
    )
    val seen = left + seenLabel + right
    if (right.isEmpty() && inserted.length < seen.length && seen.startsWith(inserted)) {
        val keep = inserted.take(left.length)
        return MentionRepair(start, end, keep, start + keep.length)
    }
    val kept = original.substring(oMin, oMax)
    return MentionRepair(oMin, oMax, kept, oMax)
}

/**
 * 入力欄に付ける 2 つの変換。[nameOf] は利用者の名前を引く (名前が無い・分からない id は null)。[style] は `@名前` の見た目。
 * [nameOf] の中で Compose の State を読むと、名前が届いたときに表示が作り直される (OutputTransformation の結果は
 * 中で読んだ State が変わると計算し直される)。
 */
// 編集の範囲 (TextFieldBuffer.changes) が実験的な API のため
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class MentionTransformations(
    private val nameOf: (String) -> String?,
    private val style: SpanStyle,
    private val channelNameOf: (String) -> String? = { null },
) {

    val output = OutputTransformation {
        val mentions = findMentions(asCharSequence(), nameOf, channelNameOf)
        // 後ろから置き換えて、前の位置をずらさない
        for (m in mentions.asReversed()) {
            replaceCharByChar(m.start + 1, m.end, m.name)
            addStyle(style, m.start, m.start + 1 + m.name.length)
        }
    }

    val input = InputTransformation { fixMentions(this) }

    private fun fixMentions(buf: TextFieldBuffer) {
        val before = buf.originalText
        val mentions = findMentions(before, nameOf, channelNameOf)
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
        val repair = repairEdit(before, oMin, oMax, inserted, mentions, cursorEdit = buf.originalSelection.collapsed)
        if (repair == null) {
            // 本文は直さない。選択がメンションの中に入っていれば端へ寄せる (編集の後の本文で探す)
            val after = findMentions(buf.asCharSequence(), nameOf, channelNameOf)
            val snapped = snapSelection(buf.originalSelection, buf.selection, after)
            if (snapped != buf.selection) buf.selection = snapped
            return
        }
        buf.revertAllChanges()
        buf.replace(repair.replaceStart, repair.replaceEnd, repair.text)
        buf.selection = TextRange(repair.cursor)
    }
}

/**
 * 入力欄にある id の名前が変わったら (後から届いたら) [onChanged] を呼ぶ。本文が変わったとき (メンションを足した・消した) は呼ばない。
 * 表示 (`@名前`) が変わっても、Compose が IME に知らせるのは TextFieldState の本文・選択が変わったときだけ
 * (TransformedTextFieldState.collectImeNotifications)。IME が古い表示の位置を持ち続けないよう、呼んだ側で IME に読み直させる
 */
@androidx.compose.runtime.Composable
internal fun OnMentionNamesChanged(
    state: androidx.compose.foundation.text.input.TextFieldState,
    nameOf: (String) -> String?,
    channelNameOf: (String) -> String?,
    onChanged: () -> Unit,
) {
    val latest = androidx.compose.runtime.rememberUpdatedState(onChanged)
    androidx.compose.runtime.LaunchedEffect(state, nameOf, channelNameOf) {
        var prevIds: Set<String>? = null
        var prevNames: List<String?>? = null
        androidx.compose.runtime.snapshotFlow {
            val ids = mentionTokens(state.text)
            // ids は `@<id>`・`#<id>`。先頭の 2 文字と最後の 1 文字を外すと id
            ids to ids.map { t -> t.substring(2, t.length - 1).let { if (t[0] == '@') nameOf(it) else channelNameOf(it) } }
        }.collect { (ids, names) ->
            if (ids == prevIds && names != prevNames) latest.value()
            prevIds = ids
            prevNames = names
        }
    }
}
