package com.giracle.android.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * バックエンド (giracle-backend) の応答に合わせたモデル。
 * 余計なキーは無視し (Json.ignoreUnknownKeys)、欠けやすいキーには既定値を置く。
 * Compose の再コンポジションを減らすため、表示に使うものは @Immutable を付ける。
 */

@Serializable
data class ApiResponse<T>(val message: String = "", val data: T)

@Serializable
data class MessageOnly(val message: String = "")

@Immutable
@Serializable
data class ServerConfig(
    val name: String = "Giracle",
    val introduction: String = "",
    @SerialName("RegisterAvailable") val registerAvailable: Boolean = true,
    @SerialName("RegisterInviteOnly") val registerInviteOnly: Boolean = false,
    @SerialName("RegisterAnnounceChannelId") val registerAnnounceChannelId: String = "",
    @SerialName("MessageMaxLength") val messageMaxLength: Int = 3000,
    @SerialName("MessageMaxFileSize") val messageMaxFileSize: Long = 512_000,
    @SerialName("BotEnabled") val botEnabled: Boolean = false,
    val isFirstUser: Boolean = false,
    val defaultJoinChannel: List<Channel> = emptyList(),
)

@Immutable
@Serializable
data class ChannelRef(val channelId: String)

@Immutable
@Serializable
data class RoleRef(val roleId: String)

@Immutable
@Serializable
data class User(
    val id: String,
    val name: String? = null,
    val selfIntroduction: String = "",
    val isBanned: Boolean = false,
    val isBot: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: String? = null,
    @SerialName("ChannelJoin") val channelJoin: List<ChannelRef> = emptyList(),
    @SerialName("RoleLink") val roleLink: List<RoleRef> = emptyList(),
) {
    val displayName: String get() = name ?: "不明なユーザー"
}

@Immutable
@Serializable
data class Channel(
    val id: String,
    val name: String = "",
    val description: String = "",
    val isArchived: Boolean = false,
    val createdUserId: String = "",
    @SerialName("ChannelViewableRole") val viewableRole: List<RoleRef> = emptyList(),
)

@Immutable
@Serializable
data class UrlPreview(
    val id: Int = 0,
    val url: String = "",
    val type: String = "UNKNOWN",
    val title: String = "",
    val description: String = "",
    val faviconLink: String? = null,
    val imageLink: String? = null,
    val videoLink: String? = null,
)

@Immutable
@Serializable
data class FileAttached(
    val id: String,
    val userId: String = "",
    val channelId: String = "",
    val messageId: String? = null,
    val actualFileName: String = "",
    val savedFileName: String = "",
    val size: Long = 0,
    val type: String = "",
) {
    val isImage: Boolean get() = type.startsWith("image/")
}

@Immutable
@Serializable
data class ReactionSummary(
    val emojiCode: String,
    val count: Int,
    val includingYou: Boolean = false,
)

@Immutable
@Serializable
data class Message(
    val id: String,
    val channelId: String,
    val userId: String,
    val content: String = "",
    val isSystemMessage: Boolean = false,
    val isEdited: Boolean = false,
    val replyingMessageId: String? = null,
    val createdAt: String,
    @SerialName("MessageUrlPreview") val urlPreviews: List<UrlPreview> = emptyList(),
    @SerialName("MessageFileAttached") val files: List<FileAttached> = emptyList(),
    val reactionSummary: List<ReactionSummary> = emptyList(),
    /**
     * 送信を押した直後に、サーバーの応答を待たずに手元で出している仮のもの (D-071)。id はサーバーのものではない。
     * 保存しない (スナップショットにも入れない)
     */
    @kotlinx.serialization.Transient val pending: Boolean = false,
)

@Immutable
@Serializable
data class ImageSize(val width: Int, val height: Int)

@Serializable
data class HistoryResult(
    val history: List<Message> = emptyList(),
    @SerialName("ImageDimensions") val imageDimensions: Map<String, ImageSize> = emptyMap(),
    val atTop: Boolean = false,
    val atEnd: Boolean = false,
)

@Immutable
@Serializable
data class Reaction(
    val id: String = "",
    val userId: String,
    val channelId: String,
    val messageId: String,
    val emojiCode: String,
)

@Immutable
@Serializable
data class InboxItem(
    val type: String = "mention",
    val userId: String = "",
    val messageId: String,
    @SerialName("Message") val message: Message,
    val happendAt: String = "",
)

@Serializable
data class ReadTime(val userId: String = "", val channelId: String, val readTime: String)

@Immutable
@Serializable
data class Role(
    val id: String,
    val name: String = "",
    val color: String = "#ffffff",
    val createdUserId: String = "",
    val createdAt: String? = null,
    val manageServer: Boolean = false,
    val manageChannel: Boolean = false,
    val manageRole: Boolean = false,
    val manageUser: Boolean = false,
    val manageEmoji: Boolean = false,
)

@Immutable
@Serializable
data class CustomEmoji(val id: String = "", val code: String, val uploadedUserId: String = "")

@Serializable
data class NotificationConfig(
    val userId: String = "",
    val enabled: Boolean = true,
    val mode: String = "mention",
)

@Serializable
data class MutedChannel(val channelId: String, val mutedAt: String = "")

@Immutable
@Serializable
data class Session(
    val id: Int,
    val name: String = "",
    val userId: String = "",
    val thisIsYou: Boolean = false,
    val createdAt: String = "",
)

@Immutable
@Serializable
data class Invite(
    val id: Int,
    val inviteCode: String,
    val isActive: Boolean = true,
    val createdUserId: String = "",
    val usedCount: Int = 0,
    val maxUsage: Int = 0,
)

@Immutable
@Serializable
data class BotInfo(
    val id: String,
    val remoteUserId: String = "",
    val createdAt: String = "",
    val createdBy: String = "",
    val isApproved: Boolean = false,
    val remoteUserName: String? = null,
    val tokenCode: String? = null,
)

@Serializable
data class RequestLog(
    val id: String,
    val method: String = "",
    val path: String = "",
    val status: Int = 0,
    val userId: String? = null,
    val createdAt: String = "",
)

@Serializable
data class RequestLogCount(
    val date: String,
    val successCount: Int = 0,
    val errorCount: Int = 0,
    val otherCount: Int = 0,
)

@Serializable
data class LogGroupResult(
    val group: List<RequestLogCount> = emptyList(),
    val firstDayLog: List<RequestLog>? = null,
)

@Serializable
data class SignInResult(val userId: String)

@Serializable
data class ChannelIdResult(val channelId: String)

/** システムメッセージ本文 (JSON 文字列) */
@Serializable
data class SystemMessageBody(val targetUserId: String = "", val messageTerm: String = "")

/** WebSocket の受信フレーム */
@Serializable
data class WsFrame(val signal: String, val data: kotlinx.serialization.json.JsonElement? = null)

@Serializable
data class VapidKey(val publicKey: String)

/** 管理者がパスワードをリセットしたときの、新しいパスワード (`POST /user/reset-password`) */
@Serializable
data class NewPassword(val newPassword: String)

/** サーバーが Web Push で送る通知の中身 (giracle-backend の PushPayload) */
@Serializable
data class PushPayload(
    val title: String = "",
    val body: String = "",
    val tag: String? = null,
    val data: PushData? = null,
)

@Serializable
data class PushData(val channelId: String? = null, val messageId: String? = null)
