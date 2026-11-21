package com.giracle.android

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import com.giracle.android.ui.channel.MentionRepair
import com.giracle.android.ui.channel.MentionSpan
import com.giracle.android.ui.channel.MentionTransformations
import com.giracle.android.ui.channel.findMentions
import com.giracle.android.ui.channel.repairEdit
import com.giracle.android.ui.channel.snapSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 入力欄のメンションを `@名前` で見せる (#143、D-081) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class MentionInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val id = "0b6f6c2e-1111-4a2b-9c3d-000000000001"
    private val names = mapOf(id to "太郎")
    private val raw = "こんにちは @<$id> です"
    // 本文での位置: "こんにちは " は 6 文字、メンションは 2 + 36 + 1 = 39 文字
    private val ms = 6
    private val me = 6 + 39

    // ---- 変換の計算 (純粋な関数) ----

    @Test fun findsOnlyKnownNames() {
        val other = "0b6f6c2e-1111-4a2b-9c3d-000000000002"
        assertEquals(listOf(MentionSpan(ms, me, "太郎")), findMentions("$raw @<$other>", names::get))
        assertEquals(emptyList<MentionSpan>(), findMentions("@名前 だけ", names::get))
    }

    @Test fun snapCursorOutOfMention() {
        val m = listOf(MentionSpan(ms, me, "太郎"))
        // メンションの中をタップ (Compose は表示の中の位置をメンション全体に写す) → 末尾へ
        assertEquals(TextRange(me), snapSelection(TextRange(0), TextRange(ms, me), m))
        assertEquals(TextRange(ms), snapSelection(TextRange(me), TextRange(ms, me), m))
        // 選択からメンション全体を選び直したとき・後ろ向きに選んだとき (Shift+←) は、選んだままにする
        assertEquals(TextRange(ms, me), snapSelection(TextRange(2, 4), TextRange(ms, me), m))
        assertEquals(TextRange(me, ms), snapSelection(TextRange(me), TextRange(me, ms), m))
        // 本文の位置で中に置かれた → 末尾へ
        assertEquals(TextRange(me), snapSelection(TextRange(0), TextRange(ms + 1, me - 1), m))
        // 末尾から左へ → 先頭へ (末尾に戻して動けなくならない)
        assertEquals(TextRange(ms), snapSelection(TextRange(me), TextRange(ms + 1, me - 1), m))
        // 先頭から右へ → 末尾へ
        assertEquals(TextRange(me), snapSelection(TextRange(ms), TextRange(ms + 1, me - 1), m))
        // またぐ選択はメンション全体へ広げる
        assertEquals(TextRange(2, me), snapSelection(TextRange(2), TextRange(2, ms + 3), m))
        assertEquals(TextRange(me, 2), snapSelection(TextRange(2), TextRange(ms + 3, 2), m))
        // 端は動かさない
        assertEquals(TextRange(ms), snapSelection(TextRange(0), TextRange(ms), m))
        assertEquals(TextRange(me), snapSelection(TextRange(0), TextRange(me), m))
    }

    @Test fun repairDeletesWholeMention() {
        val m = listOf(MentionSpan(ms, me, "太郎"))
        // 末尾の 1 文字 (`>`) を消す → メンションごと消す
        assertEquals(ms, repairEdit(me - 1, me, "", m, true)!!.replaceStart)
        assertEquals(me, repairEdit(me - 1, me, "", m, true)!!.replaceEnd)
        // 中に文字を入れる → 直後に入れる
        val ins = repairEdit(ms + 5, ms + 5, "x", m, true)!!
        assertEquals(listOf(me, me, me + 1), listOf(ins.replaceStart, ins.replaceEnd, ins.cursor))
        // 端での入力・変換 (メンションに続けて打つ) は触らない
        assertNull(repairEdit(me, me, "か", m, true))
        assertNull(repairEdit(me, me + 1, "かな", m, true))
        assertNull(repairEdit(ms - 1, ms, "", m, true))
        // メンション全体を含む削除はそのまま
        assertNull(repairEdit(ms, me, "", m, true))
    }

    @Test fun repairImeRecomposition() {
        val m = listOf(MentionSpan(ms, me, "太郎"))
        // IME が「太郎」を変換中に戻して 1 文字足した → メンションを残して後ろに足す
        assertEquals(MentionRepair(me, me, "さん", me + 2), repairEdit(ms, me, "太郎さん", m, true))
        assertEquals(MentionRepair(me, me, "", me), repairEdit(ms, me, "@太郎", m, true))
        // 変換中の名前を 1 文字消した → メンションごと消す
        assertEquals(MentionRepair(ms, me, "", ms), repairEdit(ms, me, "太", m, true))
        // メンションを選んで打ち替えた (編集の前が選択) → そのまま
        assertNull(repairEdit(ms, me, "太", m, false))
        assertNull(repairEdit(ms, me, "花子", m, false))
        // 名前と関係ない文字への置き換え → そのまま
        assertNull(repairEdit(ms, me, "花子", m, true))
    }

    // ---- 実際の入力欄 ----

    private fun setUp(text: String = raw): TextFieldState {
        val state = TextFieldState(text, TextRange(text.length))
        val t = MentionTransformations(names::get, androidx.compose.ui.text.SpanStyle(color = Color.Blue))
        rule.setContent {
            BasicTextField(state, Modifier.testTag("f"), inputTransformation = t.input, outputTransformation = t.output)
        }
        return state
    }

    private fun shown(): String =
        rule.onNodeWithTag("f").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun showsNameButKeepsId() {
        val state = setUp()
        assertEquals("こんにちは @太郎 です", shown())
        assertEquals(raw, state.text.toString())
        // メンションの部分に色が付く
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("f").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val spans = layouts.single().layoutInput.text.spanStyles.filter { it.item.color == Color.Blue }.map { it.start to it.end }
        assertEquals(listOf(ms to ms + 3), spans)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun backspaceRemovesWholeMention() {
        val state = setUp("ねえ @<$id>")
        rule.onNodeWithTag("f").performClick()
        rule.runOnIdle { state.edit { selection = TextRange(length) } }
        rule.onNodeWithTag("f").performKeyInput { pressKey(Key.Backspace) }
        rule.runOnIdle { assertEquals("ねえ ", state.text.toString()) }
    }

    @Test fun cursorInsideMentionMovesToEdge() {
        val state = setUp()
        rule.onNodeWithTag("f").performClick()
        // 本文 (`@<id>`) の位置で中に置く (読み上げなどの経路)
        rule.onNodeWithTag("f").performTextInputSelection(TextRange(ms + 2))
        rule.runOnIdle { assertEquals(TextRange(me), state.selection) }
        // IME が表示の位置「@太|郎」にカーソルを置く (タップ・スペースキーでのカーソル移動と同じく、表示の位置を写す経路)
        val ic = inputConnection()
        rule.runOnIdle { ic.setSelection(ms + 2, ms + 2) }
        // 直前のカーソルが末尾なので、左へ動かしたとみて先頭へ
        rule.runOnIdle { assertEquals(TextRange(ms), state.selection) }
        rule.runOnIdle { ic.setSelection(ms + 1, ms + 1) }
        rule.runOnIdle { assertEquals(TextRange(me), state.selection) }
    }

    /** Gboard などがメンションの名前を単語として変換中に戻したとき (英字の名前で起きやすい) */
    @Test fun imeRecomposingNameKeepsMention() {
        val state = setUp("ねえ @<$id>")
        rule.onNodeWithTag("f").performClick()
        val ic = inputConnection()
        // 表示「ねえ @太郎」の「太郎」(4..6) を変換中にして、続けて打つ
        rule.runOnIdle { ic.setComposingRegion(4, 6) }
        rule.runOnIdle { ic.setComposingText("太郎さ", 1) }
        rule.runOnIdle { assertEquals("ねえ @<$id>さ", state.text.toString()) }
        // 戻した名前を 1 文字消す → メンションごと消える
        rule.runOnIdle { ic.deleteSurroundingText(1, 0) }
        rule.runOnIdle { ic.setComposingRegion(4, 6) }
        rule.runOnIdle { ic.setComposingText("太", 1) }
        rule.runOnIdle { assertEquals("ねえ ", state.text.toString()) }
    }

    /** キーボード (IME) からの編集。IME には `@名前` が見え、その位置での編集が元の本文に当たる */
    @Test fun imeEditsAroundMention() {
        val state = setUp("ねえ @<$id>")
        rule.onNodeWithTag("f").performClick()
        val ic = inputConnection()
        rule.runOnIdle {
            // IME には表示の文字が見える
            assertEquals("ねえ @太郎", ic.getTextBeforeCursor(100, 0).toString())
            // メンションに続けて変換中の文字を打ち、確定する (メンションは触られない)
            ic.setComposingText("か", 1)
            ic.setComposingText("かな", 1)
        }
        rule.runOnIdle { assertEquals("ねえ @<$id>かな", state.text.toString()) }
        rule.runOnIdle { ic.commitText("仮名", 1) }
        rule.runOnIdle { assertEquals("ねえ @<$id>仮名", state.text.toString()) }
        // 確定した 2 文字を IME の削除で消してから、もう 1 文字消すとメンションごと消える
        rule.runOnIdle { ic.deleteSurroundingText(2, 0) }
        rule.runOnIdle { assertEquals("ねえ @<$id>", state.text.toString()) }
        rule.runOnIdle { ic.deleteSurroundingText(1, 0) }
        rule.runOnIdle { assertEquals("ねえ ", state.text.toString()) }
    }

    private fun inputConnection(): InputConnection {
        var ic: InputConnection? = null
        rule.runOnIdle {
            val view = rule.activity.window.decorView.findFocus() ?: error("入力欄にフォーカスが無い")
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return ic ?: error("InputConnection が作れない")
    }
}
