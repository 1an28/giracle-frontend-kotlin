package com.giracle.android

import androidx.compose.ui.text.TextRange
import com.giracle.android.data.model.Channel
import com.giracle.android.data.model.CustomEmoji
import com.giracle.android.ui.channel.SuggestKind
import com.giracle.android.ui.channel.SuggestQuery
import com.giracle.android.ui.channel.filterChannels
import com.giracle.android.ui.channel.filterEmojiCodes
import com.giracle.android.ui.channel.suggestQueryAt
import com.giracle.android.ui.channel.suggestToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 入力欄の候補 (`@`・`#`・`:`) の語の見つけ方。Web 版 GiracleTextinput の getTriggerQuery と同じ決まり (D-088) */
class ComposerSuggestTest {
    private fun at(text: String, cursor: Int = text.length) = suggestQueryAt(text, TextRange(cursor))

    @Test fun user() {
        assertEquals(SuggestQuery(SuggestKind.USER, 0, "ab"), at("@ab"))
        assertEquals(SuggestQuery(SuggestKind.USER, 3, "x"), at("hi @x"))
        // `@` だけでも出す (Web 版と同じ)
        assertEquals(SuggestQuery(SuggestKind.USER, 3, ""), at("hi @"))
        assertNull(at("mail@x"))
        assertNull(at("@a b"))
        // 日本語の直後は出す
        assertEquals(SuggestQuery(SuggestKind.USER, 5, "田中"), at("こんにちは@田中"))
    }

    @Test fun channel() {
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 0, "gen"), at("#gen"))
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 5, "一般"), at("こんにちは#一般"))
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 3, ""), at("見て #"))
        assertNull(at("issue#12"))
        // 確定済みの `#<id>` は対象外
        assertNull(at("#<abc>"))
        // 全角の空白で切る
        assertNull(at("#一般　です"))
    }

    @Test fun emoji() {
        assertEquals(SuggestQuery(SuggestKind.EMOJI, 3, "sm"), at("hi :sm"))
        assertEquals(SuggestQuery(SuggestKind.EMOJI, 5, "code"), at("こんにちは:code"))
        // 2 文字打つまでは出さない (「注意:」で候補が開かないように)
        assertNull(at(":"))
        assertNull(at("注意:"))
        assertNull(at("hi :s"))
        // 時刻・URL・確定済みの `:code:` の後ろのコロンは出さない
        assertNull(at("12:30"))
        assertNull(at("https://"))
        assertNull(at("見て :smile:"))
        // コードに使えない文字が入ったら出さない
        assertNull(at(":あ"))
    }

    @Test fun nearestTriggerWins() {
        assertEquals(SuggestQuery(SuggestKind.EMOJI, 4, "xy"), at("@ab :xy"))
        // 確定済みメンションの直後の `#` (Web 版ではチップの直後に当たる)
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 6, "g"), at("@<abc>#g"))
    }

    @Test fun cursorAndLines() {
        // カーソルの後ろは見ない
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 0, "ge"), at("#gen", 3))
        // 選択しているときは出さない
        assertNull(suggestQueryAt("#gen", TextRange(1, 4)))
        // 行をまたがない
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 4, "b"), at("abc\n#b"))
        assertNull(at("#a\nb"))
        // 128 文字より長い語は出さない (窓の先頭で切れる)
        assertNull(at("#" + "a".repeat(200)))
        assertEquals(SuggestQuery(SuggestKind.CHANNEL, 1, "a".repeat(100)), at(" #" + "a".repeat(100)))
        assertNull(at(""))
    }

    @Test fun filters() {
        val chs = listOf(Channel("1", "雑談-general"), Channel("2", "General"), Channel("3", "random"))
        assertEquals(listOf("2", "1"), filterChannels(chs, "gen").map { it.id })
        assertEquals(listOf("1", "2", "3"), filterChannels(chs, "").map { it.id })
        val emojis = listOf(CustomEmoji(code = "party_parrot"), CustomEmoji(code = "Parrot"), CustomEmoji(code = "cat"))
        assertEquals(listOf("Parrot", "party_parrot"), filterEmojiCodes(emojis, "parrot"))
        assertEquals(emptyList<String>(), filterEmojiCodes(emojis, "dog"))
    }

    @Test fun tokens() {
        assertEquals("@<u1> ", suggestToken(SuggestKind.USER, "u1"))
        assertEquals("#<c1> ", suggestToken(SuggestKind.CHANNEL, "c1"))
        assertEquals(":cat: ", suggestToken(SuggestKind.EMOJI, "cat"))
    }
}
