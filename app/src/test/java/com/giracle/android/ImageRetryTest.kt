package com.giracle.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.giracle.android.data.net.WsClient
import com.giracle.android.ui.channel.AttachedImage
import com.giracle.android.ui.common.TestTags
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 添付画像の読み込みに失敗したとき (#129、D-074): 印が出ること、押す・接続が戻ると出し直すこと、
 * サーバーに無い (404) なら出し直さないことを、読み込み役を差し替えて確かめる (サーバーは要らない)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ImageRetryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = AtomicInteger(0)

    /** [results] の順に結果を返す読み込み役 (null は成功)。尽きたら最後のものを返し続ける */
    private fun loader(vararg results: Throwable?): ImageLoader =
        ImageLoader.Builder(rule.activity).components {
            add(Interceptor { chain ->
                val n = calls.getAndIncrement()
                result(chain.request, results[minOf(n, results.size - 1)])
            })
        }.build()

    private fun result(req: ImageRequest, t: Throwable?): ImageResult =
        if (t == null) SuccessResult(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage(), req, DataSource.NETWORK)
        else ErrorResult(null, req, t)

    private val connection = MutableStateFlow(WsClient.State.Connected)
    private var opened = 0

    private fun show(loader: ImageLoader) {
        rule.setContent {
            val req = ImageRequest.Builder(rule.activity).data("http://example.invalid/file/1").size(100).build()
            AttachedImage(req, "写真", 1.5f, connection, onOpen = { opened++ }, tag = { if (it) "loaded" else "loading" }, imageLoader = loader)
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) {
        runCatching { rule.waitUntil(5_000) { exists(tag) } }.onFailure {
            rule.onRoot(useUnmergedTree = true).printToLog("ImageRetryTest")
            throw AssertionError("$tag が出ない (読み込み ${calls.get()} 回)", it)
        }
    }

    @Test fun failedThenTapRetries() {
        show(loader(IOException("offline"), null))
        waitTag(TestTags.IMAGE_RETRY)
        rule.onNodeWithText("読み込めませんでした", useUnmergedTree = true).assertExists()
        assertEquals(1, calls.get())
        // 失敗中に押すと、全画面表示ではなく出し直しになる
        rule.onNodeWithTag("loading").performClick()
        waitTag("loaded")
        assertEquals(2, calls.get())
        assertEquals(0, opened)
        assertEquals(false, exists(TestTags.IMAGE_RETRY))
        // 読めた後に押すと全画面表示を開く
        rule.onNodeWithTag("loaded").performClick()
        assertEquals(1, opened)
    }

    @Test fun notFoundIsNotRetried() {
        show(loader(HttpException(NetworkResponse(code = 404))))
        waitTag(TestTags.IMAGE_GONE)
        rule.onNodeWithText("ファイルがサーバーにありません", useUnmergedTree = true).assertExists()
        assertEquals(false, exists(TestTags.IMAGE_RETRY))
        // 押しても出し直さず、全画面表示も開かない。接続が戻っても出し直さない
        rule.onNodeWithTag("loading").performClick()
        connection.value = WsClient.State.Disconnected; rule.waitForIdle()
        connection.value = WsClient.State.Connected; rule.waitForIdle()
        assertEquals(1, calls.get())
        assertEquals(0, opened)
    }

    /** 取れたが表示できない (復号の失敗など) なら、出し直さずに「表示できません」を出し、押すと全画面表示を開く (保存・共有できるように) */
    @Test fun undecodableOpensViewer() {
        show(loader(IllegalStateException("decode failed")))
        waitTag(TestTags.IMAGE_UNSUPPORTED)
        rule.onNodeWithTag("loading").performClick()
        connection.value = WsClient.State.Disconnected; rule.waitForIdle()
        connection.value = WsClient.State.Connected; rule.waitForIdle()
        assertEquals(1, calls.get())
        assertEquals(1, opened)
    }

    @Test fun serverErrorIsRetryable() {
        show(loader(HttpException(NetworkResponse(code = 503))))
        waitTag(TestTags.IMAGE_RETRY)
    }

    /** 接続が「切れた → つながった」と移ったら、押さなくても出し直す。つながったままでは繰り返さない */
    @Test fun reconnectRetriesAutomatically() {
        show(loader(IOException("offline"), null))
        waitTag(TestTags.IMAGE_RETRY)
        rule.waitForIdle()
        assertEquals(1, calls.get())
        connection.value = WsClient.State.Connecting; rule.waitForIdle()
        assertEquals(1, calls.get())
        connection.value = WsClient.State.Connected
        waitTag("loaded")
        assertEquals(2, calls.get())
    }
}
