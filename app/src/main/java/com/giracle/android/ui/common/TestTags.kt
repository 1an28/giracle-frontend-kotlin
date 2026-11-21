package com.giracle.android.ui.common

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * 画面確認テストと、配布版を外から操作する確認 (UI Automator など、#12) で使う要素の名前 (testTag)。
 * 名前は「画面.要素」の形にし、一覧の行のように同じ要素が並ぶものは「:ID」を付けて区別する (D-016)。
 * 名前を変えるとテストと外部の確認手順が壊れるので、見た目の文言とは独立に保つ。
 */
object TestTags {
    // 画面全体 (Root で各画面を包む)
    const val SCREEN_LOGIN = "screen.login"
    const val SCREEN_HOME = "screen.home"
    /** 読み込みに失敗し、前回の内容を出しているときの帯 (#82) */
    const val OFFLINE_BANNER = "app.offline_banner"
    const val OFFLINE_RETRY = "app.offline_retry"
    /** リアルタイム接続が切れている・つなぎ直した、の知らせ (D-073) */
    const val CONNECTION_NOTICE = "app.connection_notice"
    /** 出せる内容が何も無いときの、画面全体の読み込みの失敗 */
    const val LOAD_ERROR = "app.load_error"
    /** 幅の広い画面で、チャンネルを開いていないときの右側 (#29) */
    const val TWO_PANE_EMPTY = "twopane.empty"
    const val SCREEN_CHANNEL = "screen.channel"
    const val SCREEN_CHANNEL_SETTINGS = "screen.channel_settings"
    const val SCREEN_INBOX = "screen.inbox"
    const val SCREEN_SEARCH = "screen.search"
    const val SCREEN_MEMBERS = "screen.members"
    const val SCREEN_CHANNEL_BROWSER = "screen.channel_browser"
    const val SCREEN_SETTINGS = "screen.settings"
    const val SCREEN_MANAGE_SERVER = "screen.manage_server"
    const val MANAGE_DELETE_OLD_FILES = "manage.delete_old_files"

    // ログイン
    const val LOGIN_SERVER_URL = "login.server_url"
    const val LOGIN_CONNECT = "login.connect"
    const val LOGIN_USERNAME = "login.username"
    const val LOGIN_PASSWORD = "login.password"
    const val LOGIN_SUBMIT = "login.submit"

    // ホーム
    const val HOME_SETTINGS = "home.settings"
    const val HOME_READ_ALL = "home.read_all"
    const val HOME_READ_ALL_CONFIRM = "home.read_all_confirm"
    const val HOME_INBOX = "home.inbox"
    const val HOME_CHANNEL_BROWSER = "home.channel_browser"
    const val HOME_MEMBERS = "home.members"
    const val HOME_MANAGE_SERVER = "home.manage_server"
    const val HOME_SHARE_BANNER = "home.share_banner"
    fun homeChannel(channelId: String) = "home.channel:$channelId"
    fun homeChannelDrag(channelId: String) = "home.channel_drag:$channelId"
    const val HOME_REORDER = "home.reorder"
    const val HOME_SEARCH = "home.search"

    // 通知 (受信箱) と検索
    fun inboxItem(messageId: String) = "inbox.item:$messageId"
    const val SEARCH_INPUT = "search.input"
    const val SEARCH_SUBMIT = "search.submit"
    fun searchResult(messageId: String) = "search.result:$messageId"

    // チャンネル
    const val CHANNEL_MESSAGES = "channel.messages"
    /** 「最新へ」と「未読の始まりへ」のボタン */
    const val CHANNEL_JUMP_LATEST = "channel.jump_latest"
    const val CHANNEL_JUMP_UNREAD = "channel.jump_unread"
    fun message(messageId: String) = "channel.message:$messageId"
    fun reaction(messageId: String, emojiCode: String) = "channel.reaction:$messageId:$emojiCode"
    /** 添付画像。読み込みが済むと「channel.image_loaded:」に変わる */
    fun image(fileId: String, loaded: Boolean) = (if (loaded) "channel.image_loaded:" else "channel.image:") + fileId
    /** 画像の全画面表示 */
    const val IMAGE_VIEWER = "channel.image_viewer"
    /** 画像の読み込みに失敗した印 (押すと出し直す、#129) */
    const val IMAGE_RETRY = "channel.image_retry"
    /** 画像がサーバーに無い (404) 印 (#129) */
    const val IMAGE_GONE = "channel.image_gone"
    /** 画像を取れたが表示できない印 (#129) */
    const val IMAGE_UNSUPPORTED = "channel.image_unsupported"
    fun expandLongText(messageId: String) = "channel.expand:$messageId"
    const val LONG_TEXT_DIALOG = "channel.long_text"
    const val COMPOSER_INPUT = "composer.input"
    /** 送信待ちの一覧 (#30) */
    const val COMPOSER_OUTBOX = "composer.outbox"
    const val COMPOSER_SEND = "composer.send"
    /** 返信先の表示 (入力欄の上) */
    const val COMPOSER_REPLY = "composer.reply"
    /** メンションの候補 (入力欄の上、`@` を打つと出る) */
    fun composerMention(userId: String) = "composer.mention:$userId"

    // 設定
    const val SETTINGS_KEEP_CONNECTION = "settings.keep_connection"
    const val SETTINGS_PUSH_STATUS = "settings.push_status"
    const val SETTINGS_PUSH_ENABLE = "settings.push_enable"

    // メッセージの操作 (長押しで出るシート) と絵文字ピッカー
    const val MESSAGE_ACTIONS = "message_actions"
    const val MESSAGE_ACTIONS_EMOJI_PICKER = "message_actions.emoji_picker"
    const val MESSAGE_ACTIONS_REPLY = "message_actions.reply"
    const val EMOJI_PICKER = "emoji_picker"
    const val EMOJI_PICKER_SEARCH = "emoji_picker.search"
    fun emojiPickerItem(emojiCode: String) = "emoji_picker.item:$emojiCode"

    // プロフィール (管理者の操作。#43)
    const val PROFILE_DELETE_USER = "profile.delete_user"
    const val PROFILE_DELETE_USER_CONFIRM = "profile.delete_user_confirm"
}

/**
 * testTag を Android の resource-id としても公開する (UI Automator から By.res で探せるようにする)。
 * 画面の根元に 1 回付ければ子孫に効くが、ダイアログやボトムシートは別ウィンドウなので、その中身にも付ける。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }
