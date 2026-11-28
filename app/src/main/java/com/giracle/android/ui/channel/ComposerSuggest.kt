package com.giracle.android.ui.channel

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import com.giracle.android.data.model.Channel
import com.giracle.android.data.model.CustomEmoji

/*
 * 入力欄の候補 (`@` 利用者・`#` チャンネル・`:` カスタム絵文字)。Web 版の GiracleTextinput (PR #252) の
 * getTriggerQuery と同じ決まりで、カーソルの直前の打ちかけの語を探す (D-088)。
 */

internal enum class SuggestKind { USER, CHANNEL, EMOJI }

/** 打ちかけの候補の語。[start] はトリガー文字 (`@`・`#`・`:`) の位置、[query] はその後ろの語 (空のこともある) */
@Immutable
internal data class SuggestQuery(val kind: SuggestKind, val start: Int, val query: String)

/** カーソルの前のこの文字数だけを見る (Web 版と同じ。長い貼り付けで打つたびに本文全体を調べないため) */
private const val WINDOW = 128

/** 絵文字の候補を出す、`:` の後の最小の文字数 (Web 版は 0。D-088) */
private const val EMOJI_MIN_QUERY = 2

// `[^\s\p{Z}]` = 空白でも Unicode の区切り文字 (全角の空白・NBSP など) でもない文字。Web 版の JavaScript の `\S` に近づける。
// `(?U)` は使わない: Android の正規表現 (ICU) はインラインの `U` を受け付けず、配布版がチャンネルを開くと落ちた (JVM のテストでは通る。D-088)
private val PATTERNS = listOf(
    SuggestKind.USER to Regex("""@([^\s\p{Z}]*)$"""),
    SuggestKind.CHANNEL to Regex("""#([^\s\p{Z}]*)$"""),
    // 絵文字のコードは英数字と `_+-` だけ
    SuggestKind.EMOJI to Regex(""":([A-Za-z0-9_+-]*)$"""),
)

private fun isAsciiWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/**
 * カーソルの直前の打ちかけの候補の語を返す。無ければ null。
 * - 選択しているとき (カーソルでないとき) は出さない
 * - 確定済みの `@<id>`・`#<id>` は対象外
 * - トリガーの直前が ASCII の英数字・`_` なら出さない (`foo@bar`・`12:30`・`https://`)。
 *   日本語の直後は出す (`こんにちは#一般`)
 * - 絵文字は `:` の後に 2 文字以上打ったときだけ (Web 版は `:` だけで出す)
 * - 複数あてはまるときは、カーソルに最も近いものを返す
 */
internal fun suggestQueryAt(text: CharSequence, selection: TextRange): SuggestQuery? {
    if (!selection.collapsed) return null
    val end = selection.start
    if (end <= 0 || end > text.length) return null
    // 行をまたがない (Web 版は段落の中だけを見る)。改行の無い長い行でも、窓より前へは探しに行かない
    val floor = maxOf(0, end - WINDOW)
    var start = end
    while (start > floor && text[start - 1] != '\n') start--
    // 窓の先頭まで行の始まりが無かった = 行は窓より前から続いている (直前の文字が見えない)
    val truncated = start == floor && floor > 0 && text[floor - 1] != '\n'
    val before = text.subSequence(start, end).toString()
    var best: SuggestQuery? = null
    var bestIndex = -1
    for ((kind, pattern) in PATTERNS) {
        val m = pattern.find(before) ?: continue
        // Java の `$` は最後の改行の手前にも合うので、本当の末尾かを確かめる
        if (m.range.last + 1 != before.length) continue
        val query = m.groupValues[1]
        if (query.startsWith("<")) continue
        // 絵文字は `:` の後に 2 文字打ってから出す (「注意:」のようなコロンで候補が開かないよう。Teams のスマホ版と同じ。D-088)
        if (kind == SuggestKind.EMOJI && query.length < EMOJI_MIN_QUERY) continue
        val index = m.range.first
        // 窓の先頭で切れた = 直前の文字が見えない、長すぎる語
        if (index == 0 && truncated) continue
        if (index > 0 && isAsciiWord(before[index - 1])) continue
        if (index <= bestIndex) continue
        best = SuggestQuery(kind, start + index, query)
        bestIndex = index
    }
    return best
}

/**
 * チャンネル名の部分一致 (大文字・小文字は区別しない)。名前が [query] で始まるものを先に並べる
 * (Web 版は並べ替えない。先頭一致を前にするのは D-088 で決めた)
 */
internal fun filterChannels(channels: List<Channel>, query: String): List<Channel> =
    channels.filter { it.name.contains(query, ignoreCase = true) }
        .sortedBy { if (it.name.startsWith(query, ignoreCase = true)) 0 else 1 }

/** カスタム絵文字のコードの部分一致 (大文字・小文字は区別しない)。コードが [query] で始まるものを先に並べる */
internal fun filterEmojiCodes(emojis: List<CustomEmoji>, query: String): List<String> =
    emojis.asSequence().map { it.code }.filter { it.contains(query, ignoreCase = true) }
        .sortedBy { if (it.startsWith(query, ignoreCase = true)) 0 else 1 }.toList()

/** 候補を選んだときに入れる文字。利用者とチャンネルは `@<id>`・`#<id>`、絵文字は `:code:` の素の文字。後ろに空白を 1 つ付ける */
internal fun suggestToken(kind: SuggestKind, value: String): String = when (kind) {
    SuggestKind.USER -> "@<$value> "
    SuggestKind.CHANNEL -> "#<$value> "
    SuggestKind.EMOJI -> ":$value: "
}
