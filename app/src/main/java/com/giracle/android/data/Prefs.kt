package com.giracle.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.giracle.android.data.net.AppJson
import com.giracle.android.data.net.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** 端末内だけの表示・操作設定 (Web 版の ClientConfig に相当 + 独自項目) */
@Immutable
@Serializable
data class ClientSettings(
    /** "system" | "light" | "dark" */
    val theme: String = "system",
    /** true: Enter で送信 / false: 送信ボタンのみ (スマホでは改行を優先するのが既定) */
    val sendWithEnter: Boolean = false,
    /** メッセージ間の余白 0..6 (Web 版の messageGapLevel) */
    val messageGapLevel: Int = 0,
    /** URL プレビューの説明文の最大文字数 (Web 版の maxUrlPreviewTextLength) */
    val maxUrlPreviewTextLength: Int = 50,
    /** メンション・返信をアプリ内通知する (Web 版の notifyInbox) */
    val notifyInbox: Boolean = true,
    /** すべての新着をアプリ内通知する (Web 版の notifyAll) */
    val notifyAll: Boolean = false,
    /** バックグラウンドでも接続を保って通知を受ける (常駐通知が出る) */
    val keepConnection: Boolean = false,
    /** 画像を自動で読み込む (通信量節約用に OFF にできる) */
    val autoLoadImages: Boolean = true,
    /** 送る写真を縮小する (長辺 2048px の JPEG。#28) */
    val shrinkImages: Boolean = true,
    /** 文字サイズの倍率 */
    val fontScale: Float = 1f,
    /** 壁紙から作った色 (Material You。Android 12 以降) を使う。既定は Web 版に近い白黒基調 */
    val dynamicColor: Boolean = false,
    /** 新しい版を GitHub に確かめて知らせる (#147、D-086) */
    val checkUpdates: Boolean = true,
)

/**
 * SharedPreferences ベースの保存先。
 * 起動を速くするため、DataStore ではなく同期読み出しできる SharedPreferences を使う (値は少量)。
 */
class Prefs(context: Context, name: String = "giracle", private val cipher: TokenCipher = KeystoreTokenCipher()) : TokenStore {
    private val sp: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = sp.getString(K_SERVER, null)
        set(v) = sp.edit().putString(K_SERVER, v).apply()

    var directBackend: Boolean
        get() = sp.getBoolean(K_DIRECT, false)
        set(v) = sp.edit().putBoolean(K_DIRECT, v).apply()

    // トークンは端末の鍵で暗号化して保存する (#27、D-027)。復号は最初に使うときまで遅らせる
    // (起動直後の画面を出すには「保存されているか」だけ分かればよく、復号は最初の通信の前、裏のスレッドで済む)
    private val tokenLock = Any()
    @Volatile private var tokenLoaded = false
    @Volatile private var tokenCache: String? = null

    /** トークンが保存されているか (復号はしない) */
    val hasToken: Boolean
        get() = if (tokenLoaded) tokenCache != null else sp.contains(K_TOKEN_ENC) || sp.contains(K_TOKEN)

    private fun loadToken(): String? = synchronized(tokenLock) {
        if (tokenLoaded) return tokenCache
        val enc = sp.getString(K_TOKEN_ENC, null)
        val plain = sp.getString(K_TOKEN, null)
        val v = when {
            // 復号できない (端末の鍵が消えた) ときは、ログインし直してもらう
            enc != null -> cipher.decrypt(enc) ?: null.also { sp.edit().remove(K_TOKEN_ENC).apply() }
            plain != null -> {
                // 以前の版が平文で保存したものは、暗号化して置き換える (端末の鍵が使えなければ平文のまま)
                cipher.encrypt(plain)?.let { sp.edit().putString(K_TOKEN_ENC, it).remove(K_TOKEN).apply() }
                plain
            }
            else -> null
        }
        tokenCache = v
        tokenLoaded = true
        v
    }

    override var token: String?
        get() = if (tokenLoaded) tokenCache else loadToken()
        set(v) {
            synchronized(tokenLock) { setTokenLocked(v) }
        }

    private fun setTokenLocked(v: String?) {
        if (tokenLoaded && v == tokenCache) return
        tokenCache = v
        tokenLoaded = true
        val e = sp.edit()
        if (v == null) e.remove(K_TOKEN_ENC).remove(K_TOKEN)
        else {
            val enc = cipher.encrypt(v)
            if (enc != null) e.putString(K_TOKEN_ENC, enc).remove(K_TOKEN)
            // 端末の鍵が使えないとき (JVM のテストなど) は平文で保存する
            else e.putString(K_TOKEN, v).remove(K_TOKEN_ENC)
        }
        e.apply()
    }

    var userId: String?
        get() = sp.getString(K_USER, null)
        set(v) = sp.edit().putString(K_USER, v).apply()

    var lastChannelId: String?
        get() = sp.getString(K_LAST_CHANNEL, null)
        set(v) = sp.edit().putString(K_LAST_CHANNEL, v).apply()

    private val _pushEndpoint = MutableStateFlow(sp.getString(K_PUSH_ENDPOINT, null))

    /** プッシュ通知の宛先として、サーバーに登録済みの URL (解除時に使う)。null なら未登録 */
    val pushEndpointFlow: StateFlow<String?> = _pushEndpoint
    var pushEndpoint: String?
        get() = _pushEndpoint.value
        set(v) {
            _pushEndpoint.value = v
            sp.edit().putString(K_PUSH_ENDPOINT, v).apply()
        }

    /** 利用者が設定でプッシュ通知を無効にした (自動では有効にしない)。D-019 */
    var pushOptOut: Boolean
        get() = sp.getBoolean(K_PUSH_OPT_OUT, false)
        set(v) = sp.edit().putBoolean(K_PUSH_OPT_OUT, v).apply()

    /** チャンネル一覧の並び順 (ユーザーが並べ替えたもの) */
    var channelOrder: List<String>
        get() = sp.getString(K_ORDER, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit().putString(K_ORDER, v.joinToString(",")).apply()

    private val _settings = MutableStateFlow(
        sp.getString(K_SETTINGS, null)?.let {
            runCatching { AppJson.decodeFromString(ClientSettings.serializer(), it) }.getOrNull()
        } ?: ClientSettings(),
    )
    val settings: StateFlow<ClientSettings> = _settings

    fun updateSettings(f: (ClientSettings) -> ClientSettings) {
        val next = f(_settings.value)
        _settings.value = next
        sp.edit().putString(K_SETTINGS, AppJson.encodeToString(ClientSettings.serializer(), next)).apply()
    }

    fun clearSession() {
        synchronized(tokenLock) {
            tokenCache = null
            tokenLoaded = true
        }
        sp.edit().remove(K_TOKEN).remove(K_TOKEN_ENC).remove(K_USER).remove(K_LAST_CHANNEL).apply()
    }

    private companion object {
        const val K_SERVER = "serverUrl"
        const val K_DIRECT = "directBackend"
        const val K_TOKEN = "token"
        const val K_TOKEN_ENC = "tokenEnc"
        const val K_USER = "userId"
        const val K_LAST_CHANNEL = "lastChannel"
        const val K_ORDER = "channelOrder"
        const val K_SETTINGS = "clientSettings"
        const val K_PUSH_ENDPOINT = "pushEndpoint"
        const val K_PUSH_OPT_OUT = "pushOptOut"
    }
}
