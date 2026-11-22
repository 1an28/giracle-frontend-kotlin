package com.giracle.android

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.test.withKeyDown
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import com.giracle.android.ui.channel.MentionRepair
import com.giracle.android.ui.channel.MentionSpan
import com.giracle.android.ui.channel.MentionTransformations
import com.giracle.android.ui.channel.findMentions
import com.giracle.android.ui.channel.labelOffset
import com.giracle.android.ui.channel.OnMentionNamesChanged
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
    private val id2 = "0b6f6c2e-1111-4a2b-9c3d-000000000003"
    // 名前の表。名前が後から届く確かめのため、Compose の State にする
    private val names = mutableStateMapOf(id to "太郎", id2 to "taro")
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

    @Test fun labelOffsets() {
        // 名前 2 文字・`<id>` 38 文字: `@`=0、名前の 1 文字目=1、最後の 1 文字は本文の残り全部、終わり=3
        assertEquals(listOf(0, 1, 2, 2, 2, 3), listOf(0, 1, 2, 3, 38, 39).map { labelOffset(it, 2, 38) })
        // 名前が `<id>` より長い (5 文字・`<id>` 3 文字): 本文の最後の 1 文字の前に余りを差し込む
        assertEquals(listOf(0, 1, 2, 3, 6), listOf(0, 1, 2, 3, 4).map { labelOffset(it, 5, 3) })
    }

    @Test fun snapCursorOutOfMention() {
        val m = listOf(MentionSpan(ms, me, "太郎"))
        // カーソルが中に入った (タップ) → 末尾へ
        assertEquals(TextRange(me), snapSelection(TextRange(0), TextRange(ms + 1), m))
        // 末尾から左へ → 先頭へ (末尾に戻して動けなくならない)
        assertEquals(TextRange(ms), snapSelection(TextRange(me), TextRange(ms + 2), m))
        // 先頭から右へ → 末尾へ
        assertEquals(TextRange(me), snapSelection(TextRange(ms), TextRange(ms + 1), m))
        // 端が中にある選択はメンション全体へ広げる (向きは保つ)
        assertEquals(TextRange(ms, me), snapSelection(TextRange(ms), TextRange(ms, ms + 1), m))
        assertEquals(TextRange(ms, me), snapSelection(TextRange(0), TextRange(ms + 1, ms + 2), m))
        assertEquals(TextRange(2, me), snapSelection(TextRange(2), TextRange(2, ms + 3), m))
        assertEquals(TextRange(me, 2), snapSelection(TextRange(2), TextRange(ms + 3, 2), m))
        // 端がメンションの外にある選択 (全体の選択・メンションちょうど) はそのまま
        assertEquals(TextRange(0, raw.length), snapSelection(TextRange(raw.length), TextRange(0, raw.length), m))
        assertEquals(TextRange(ms, me), snapSelection(TextRange(ms), TextRange(ms, me), m))
        // 端のカーソルは動かさない
        assertEquals(TextRange(ms), snapSelection(TextRange(0), TextRange(ms), m))
        assertEquals(TextRange(me), snapSelection(TextRange(0), TextRange(me), m))
    }

    @Test fun repairDeletesWholeMention() {
        val m = listOf(MentionSpan(ms, me, "太郎"))
        // 末尾 (表示の「郎」= 本文の残り全部) を消す → メンションごと消す
        assertEquals(MentionRepair(ms, me, "", ms), repairEdit(raw, ms + 2, me, "", m, true))
        // 中に文字を入れる → 直後に入れる
        assertEquals(MentionRepair(me, me, "x", me + 1), repairEdit(raw, ms + 5, ms + 5, "x", m, true))
        // 選んで打ち替えた (一部にかかる) → まとめて消して、打った文字は残す
        assertEquals(MentionRepair(2, me, "x", 3), repairEdit(raw, 2, ms + 2, "x", m, false))
        // 端での入力・変換 (メンションに続けて打つ) は触らない
        assertNull(repairEdit(raw, me, me, "か", m, true))
        assertNull(repairEdit(raw, me, me + 1, "かな", m, true))
        assertNull(repairEdit(raw, ms - 1, ms, "", m, true))
        // メンション全体を含む削除・選んで打ち替えはそのまま
        assertNull(repairEdit(raw, ms, me, "", m, true))
        assertNull(repairEdit(raw, ms, me, "花子", m, false))
    }

    @Test fun repairImeRecomposition() {
        val m = listOf(MentionSpan(ms, me, "太郎"))
        val token = raw.substring(ms, me)
        // IME が名前 (本文の ms+1..me) を変換中に戻して 1 文字足した → メンションを残して後ろに足す
        assertEquals(MentionRepair(ms, me, token + "さん", me + 2), repairEdit(raw, ms + 1, me, "太郎さん", m, true))
        assertEquals(MentionRepair(ms, me, token, me), repairEdit(raw, ms, me, "@太郎", m, true))
        // 前の文字ごと変換し直した → 前と後ろだけ入れる
        assertEquals(MentionRepair(2, me, "あ" + token + "x", 2 + 1 + token.length + 1), repairEdit(raw, 2, me, "あ@太郎x", m, true))
        // 変換中の名前を 1 文字消した → メンションごと消す
        assertEquals(MentionRepair(ms, me, "", ms), repairEdit(raw, ms + 1, me, "太", m, true))
        // 名前と関係ない自動修正 → 取り消してメンションを残す
        assertEquals(MentionRepair(ms + 1, me, raw.substring(ms + 1, me), me), repairEdit(raw, ms + 1, me, "花子", m, true))
        // 大文字・小文字だけ変わった自動修正 → メンションを残す
        val m2 = listOf(MentionSpan(ms, me, "taro"))
        assertEquals(MentionRepair(ms, me, token, me), repairEdit(raw, ms + 1, me, "Taro", m2, true))
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

    @OptIn(ExperimentalTestApi::class)
    @Test fun keysAroundMention() {
        val state = setUp()
        rule.onNodeWithTag("f").performClick()
        // 先頭から → で末尾へ、← で先頭へ (中に止まらない)
        rule.runOnIdle { state.edit { selection = TextRange(ms) } }
        rule.onNodeWithTag("f").performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals(TextRange(me), state.selection) }
        rule.onNodeWithTag("f").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.runOnIdle { assertEquals(TextRange(ms), state.selection) }
        // Shift+→ はメンション全体を選ぶ (カーソル移動にならない)
        rule.onNodeWithTag("f").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        rule.runOnIdle { assertEquals(TextRange(ms, me), state.selection) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun selectAllOnlyMention() {
        val text = "@<$id>"
        val state = setUp(text)
        rule.onNodeWithTag("f").performClick()
        // 長押しメニュー・キーボードの「すべて選択」(TransformedTextFieldState.selectAll を通る)
        val ic = inputConnection()
        rule.runOnIdle { ic.performContextMenuAction(android.R.id.selectAll) }
        rule.runOnIdle { assertEquals(TextRange(0, text.length), state.selection) }
        // IME が表示の位置で全体を選んだときも同じ
        rule.runOnIdle { ic.setSelection(3, 3) }
        rule.runOnIdle { ic.setSelection(0, 3) }
        rule.runOnIdle { assertEquals(TextRange(0, text.length), state.selection) }
    }

    /** 単語の選択 (長押し・ダブルタップ) と同じく、表示の「太郎」だけを選ぶ → メンション全体を選ぶ */
    @Test fun selectingNameSelectsMention() {
        val state = setUp()
        rule.onNodeWithTag("f").performClick()
        val ic = inputConnection()
        rule.runOnIdle { ic.setSelection(ms + 1, ms + 3) }
        rule.runOnIdle { assertEquals(TextRange(ms, me), state.selection) }
    }

    /** 変換し直した名前を IME が自動修正したとき (主セッションのレビュー 2) */
    @Test fun imeAutocorrectKeepsMention() {
        // (a) 名前 "taro" を変換中に戻して "Taro" に確定する
        val state = setUp("ねえ @<$id2>")
        rule.onNodeWithTag("f").performClick()
        val ic = inputConnection()
        rule.runOnIdle { assertEquals("ねえ @taro", ic.getTextBeforeCursor(100, 0).toString()) }
        rule.runOnIdle { ic.setComposingRegion(4, 8) }
        rule.runOnIdle { ic.commitText("Taro", 1) }
        rule.runOnIdle { assertEquals("ねえ @<$id2>", state.text.toString()) }
        // 名前と関係ない言葉への自動修正 → メンションを残す
        rule.runOnIdle { ic.setComposingRegion(4, 8) }
        rule.runOnIdle { ic.commitText("tarot", 1) }
        rule.runOnIdle { assertEquals("ねえ @<$id2>t", state.text.toString()) }
        rule.runOnIdle { ic.deleteSurroundingText(1, 0) }
        rule.runOnIdle { ic.setComposingRegion(4, 8) }
        rule.runOnIdle { ic.commitText("花子", 1) }
        rule.runOnIdle { assertEquals("ねえ @<$id2>", state.text.toString()) }
    }

    @Test fun imeRecomposingWithoutSpace() {
        // (b) 空白なしの "abc@太郎" を丸ごと変換中にして打ち足す
        val state = setUp("abc@<$id>")
        rule.onNodeWithTag("f").performClick()
        val ic = inputConnection()
        rule.runOnIdle { ic.setComposingRegion(0, 6) }
        rule.runOnIdle { ic.setComposingText("abc@太郎d", 1) }
        rule.runOnIdle { assertEquals("abc@<$id>d", state.text.toString()) }
    }

    /** 名前が後から届いて表示が変わったとき (主セッションのレビュー 3) */
    @Test fun nameArrivesWhileImeConnected() {
        names.remove(id)
        val state = TextFieldState("ねえ @<$id>", TextRange(42))
        var changed = 0
        rule.setContent {
            val t = remember { MentionTransformations(names::get, androidx.compose.ui.text.SpanStyle(color = Color.Blue)) }
            BasicTextField(state, Modifier.testTag("f"), inputTransformation = t.input, outputTransformation = t.output)
            OnMentionNamesChanged(state, names::get) { changed++ }
        }
        rule.onNodeWithTag("f").performClick()
        val ic = inputConnection()
        rule.runOnIdle { assertEquals("ねえ @<$id>", ic.getTextBeforeCursor(100, 0).toString()) }
        rule.runOnIdle { names[id] = "太郎" }
        rule.runOnIdle {
            assertEquals("ねえ @太郎", shown())
            // 呼んだ側 (Composer) は、ここで IME に読み直させる
            assertEquals(1, changed)
        }
        // 新しい表示の位置での IME の編集が、正しく当たる
        rule.runOnIdle { ic.setSelection(6, 6) }
        rule.runOnIdle { assertEquals(TextRange(state.text.length), state.selection) }
        rule.runOnIdle { ic.setSelection(4, 4) }
        rule.runOnIdle { assertEquals(TextRange(3), state.selection) }
        rule.runOnIdle { state.edit { selection = TextRange(length) } }
        rule.runOnIdle { ic.setComposingRegion(4, 6) }
        rule.runOnIdle { ic.setComposingText("太郎さ", 1) }
        rule.runOnIdle { assertEquals("ねえ @<$id>さ", state.text.toString()) }
        // 本文が変わったとき (メンションを足した) は知らせない
        rule.runOnIdle { state.edit { append(" @<$id2>") } }
        rule.runOnIdle { assertEquals(1, changed) }
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
