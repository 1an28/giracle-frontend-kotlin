package com.giracle.android.data.net

import com.giracle.android.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_TYPE = "application/json".toMediaType()

/** アップロードするファイル (端末内の Uri を読み出した結果) */
class UploadFile(val name: String, val mime: String, val body: RequestBody)

/**
 * giracle-backend の HTTP API。
 * 関数名・引数は Web 版 (giracle-frontend-solid/src/api/domains) にそろえている。
 * JSON の解析は Dispatchers.Default で行い、UI スレッドを止めない。
 */
class GiracleApi(private val client: OkHttpClient, @Volatile var endpoint: Endpoint) {

    // ---------- 低レベル ----------

    private fun url(path: String, query: Map<String, Any?> = emptyMap()): HttpUrl =
        endpoint.api(path).apply {
            for ((k, v) in query) if (v != null) addQueryParameter(k, v.toString())
        }.build()

    private suspend fun raw(method: String, path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap()): String {
        val rb = when {
            body != null -> AppJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_TYPE)
            method == "GET" -> null
            else -> "{}".toRequestBody(JSON_TYPE)
        }
        val req = Request.Builder().url(url(path, query)).method(method, rb).build()
        return client.fetchString(req)
    }

    // 通信から解析までを 1 つの withContext で囲み、呼んだ側 (多くはメインスレッド) へ戻るのを終わりの 1 回にする。
    // 別々に囲むと間で一度メインに戻り、メインが描画などで塞がっている間は解析が始まらない (D-068 の記録、D-074)。
    // 本文の読み出しは fetchString の中で IO に移り、終われば Default に戻る (メインは通らない)
    private suspend inline fun <reified T> call(method: String, path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap()): T =
        withContext(Dispatchers.Default) {
            val text = raw(method, path, body, query)
            AppJson.decodeFromString(ApiResponse.serializer(serializer<T>()), text).data
        }

    private suspend fun callUnit(method: String, path: String, body: JsonElement? = null) {
        withContext(Dispatchers.Default) { raw(method, path, body) }
    }

    private suspend fun multipart(method: String, path: String, fields: Map<String, String>, fileField: String, file: UploadFile): String {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            for ((k, v) in fields) addFormDataPart(k, v)
            addFormDataPart(fileField, file.name, file.body)
        }.build()
        return client.fetchString(Request.Builder().url(url(path)).method(method, mb).build())
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
            else -> put(k, v.toString())
        }
    }

    // ---------- サーバー ----------

    suspend fun serverConfig(): ServerConfig = call("GET", "server/config")
    suspend fun customEmoji(): List<CustomEmoji> = call("GET", "server/custom-emoji")
    suspend fun changeServerInfo(name: String, introduction: String) =
        callUnit("POST", "server/change-info", obj("name" to name, "introduction" to introduction))

    suspend fun changeServerConfig(
        registerAvailable: Boolean, registerInviteOnly: Boolean, announceChannelId: String,
        messageMaxLength: Int, messageMaxFileSize: Long, defaultJoinChannel: List<String>, botEnabled: Boolean,
    ) = callUnit(
        "POST", "server/change-config",
        obj(
            "RegisterAvailable" to registerAvailable, "RegisterInviteOnly" to registerInviteOnly,
            "RegisterAnnounceChannelId" to announceChannelId, "MessageMaxLength" to messageMaxLength,
            "MessageMaxFileSize" to messageMaxFileSize, "DefaultJoinChannel" to defaultJoinChannel,
            "BotEnabled" to botEnabled,
        ),
    )

    suspend fun changeServerBanner(file: UploadFile) { multipart("POST", "server/change-banner", emptyMap(), "banner", file) }
    suspend fun invites(): List<Invite> = call("GET", "server/get-invite")
    suspend fun createInvite(code: String, maxUsage: Int?) =
        callUnit("PUT", "server/create-invite", obj("inviteCode" to code, "maxUsage" to maxUsage))

    suspend fun deleteInvite(id: Int) = callUnit("DELETE", "server/delete-invite", obj("inviteId" to id))
    suspend fun uploadCustomEmoji(code: String, file: UploadFile) {
        multipart("PUT", "server/custom-emoji/upload", mapOf("emojiCode" to code), "emoji", file)
    }

    suspend fun deleteCustomEmoji(code: String) = callUnit("DELETE", "server/custom-emoji/delete", obj("emojiCode" to code))
    suspend fun storageUsage(): Long = call("GET", "server/storage-usage")

    /**
     * [olderThan] 以前 (ちょうども含む) に上げられた添付ファイルを、ファイルと記録の両方とも消す (manageServer)。
     * サーバーは ISO 8601 の文字列を受け付ける (LiveServerTest で確認)。まだ残っていれば true を返す
     */
    suspend fun deleteOldFiles(olderThan: java.time.Instant): Boolean =
        call<DeleteOldFilesResult>("DELETE", "server/delete-old-files", obj("deleteOlderThan" to olderThan.toString())).hasMoreFiles
    suspend fun logs(targetDate: String, cursorLogId: String?): List<RequestLog> =
        call("GET", "server/log", query = mapOf("targetDate" to targetDate, "cursorLogId" to cursorLogId))

    suspend fun logGroup(type: String?, userId: String?, cursorLogDate: String?, includeFirstLogs: Boolean): LogGroupResult =
        call(
            "GET", "server/log-group",
            query = mapOf("type" to type, "userId" to userId, "cursorLogDate" to cursorLogDate, "includeFirstLogs" to includeFirstLogs),
        )

    suspend fun setBotApproved(botId: String, approved: Boolean) =
        callUnit("POST", "server/bot/set-approve", obj("botId" to botId, "isApproved" to approved))

    // ---------- ユーザー ----------

    suspend fun signIn(username: String, password: String): SignInResult =
        call("POST", "user/sign-in", obj("username" to username, "password" to password))

    suspend fun signUp(username: String, password: String, inviteCode: String?) =
        callUnit("PUT", "user/sign-up", obj("username" to username, "password" to password, "inviteCode" to inviteCode?.ifBlank { null }))

    suspend fun signOut() = callUnit("POST", "user/sign-out")
    suspend fun verifyToken(): SignInResult = call("GET", "user/verify-token")
    suspend fun userInfo(userId: String): User = call("GET", "user/info/$userId")
    suspend fun onlineUsers(): List<String> = call("GET", "user/get-online")
    suspend fun userList(length: Int = 30, cursorUserId: String? = null, username: String? = null, joinedChannel: String? = null): List<User> =
        call("GET", "user/list", query = mapOf("length" to length, "cursorUserId" to cursorUserId, "username" to username, "joinedChannel" to joinedChannel))

    suspend fun changeIcon(file: UploadFile) { multipart("POST", "user/change-icon", emptyMap(), "icon", file) }
    suspend fun changeBanner(file: UploadFile) { multipart("POST", "user/change-banner", emptyMap(), "banner", file) }
    suspend fun changePassword(current: String, new: String) =
        callUnit("POST", "user/change-password", obj("currentPassword" to current, "newPassword" to new))

    suspend fun profileUpdate(name: String?, selfIntroduction: String?) =
        callUnit("POST", "user/profile-update", obj("name" to name, "selfIntroduction" to selfIntroduction))

    suspend fun sessions(cursor: Int = 1): List<Session> = call("GET", "user/session", query = mapOf("cursor" to cursor))
    suspend fun changeSessionName(sessionId: Int, name: String) =
        callUnit("POST", "user/change-session-name", obj("sessionId" to sessionId, "name" to name))

    suspend fun deleteSession(sessionId: Int) = callUnit("DELETE", "user/session", obj("sessionId" to sessionId))
    suspend fun ban(userId: String) = callUnit("POST", "user/ban", obj("userId" to userId))
    suspend fun unban(userId: String) = callUnit("POST", "user/unban", obj("userId" to userId))
    /** ユーザーの削除 (論理削除。メッセージは残り、セッション・通知・その人の Bot は消える)。manageUser 権限。#43 */
    suspend fun deleteUser(userId: String) = callUnit("DELETE", "user", obj("userId" to userId))

    /** 管理者 (manageServer) が、自分より下のロールのユーザーのパスワードを作り直す。新しいパスワードを返す */
    suspend fun resetPassword(userId: String): String =
        call<com.giracle.android.data.model.NewPassword>("POST", "user/reset-password", obj("targetUserId" to userId)).newPassword

    fun iconUrl(userId: String) = endpoint.apiUrl("user/icon/$userId")
    fun bannerUrl(userId: String) = endpoint.apiUrl("user/banner/$userId")
    fun fileUrl(fileId: String) = endpoint.apiUrl("message/file/$fileId")
    fun customEmojiUrl(code: String) = endpoint.apiUrl("server/custom-emoji/$code")
    fun serverBannerUrl() = endpoint.apiUrl("server/banner")
    fun urlThumbnail(target: String, favicon: Boolean = false): String =
        endpoint.api("message/url-thumbnail").addQueryParameter("targetUrl", target)
            .addQueryParameter("forFavicon", favicon.toString()).build().toString()

    // ---------- チャンネル ----------

    suspend fun channelList(): List<Channel> = call("GET", "channel/list")
    suspend fun channelInfo(channelId: String): Channel = call("GET", "channel/get-info/$channelId")
    suspend fun joinChannel(channelId: String) = callUnit("POST", "channel/join", obj("channelId" to channelId))
    suspend fun leaveChannel(channelId: String) = callUnit("POST", "channel/leave", obj("channelId" to channelId))
    suspend fun createChannel(name: String, description: String): ChannelIdResult =
        call("PUT", "channel/create", obj("channelName" to name, "description" to description))

    suspend fun deleteChannel(channelId: String) = callUnit("DELETE", "channel/delete", obj("channelId" to channelId))
    suspend fun updateChannel(channelId: String, name: String? = null, description: String? = null, isArchived: Boolean? = null, viewableRole: List<String>? = null) =
        callUnit(
            "POST", "channel/update",
            obj("channelId" to channelId, "name" to name, "description" to description, "isArchived" to isArchived, "viewableRole" to viewableRole),
        )

    suspend fun invite(channelId: String, userId: String) = callUnit("POST", "channel/invite", obj("channelId" to channelId, "userId" to userId))
    suspend fun kick(channelId: String, userId: String) = callUnit("POST", "channel/kick", obj("channelId" to channelId, "userId" to userId))

    /**
     * 履歴取得。応答は常に新しい順。fetchLength はサーバー側で最大 30。
     */
    suspend fun history(channelId: String, messageIdFrom: String? = null, messageTimeFrom: String? = null, fetchLength: Int = 30, direction: String = "older"): HistoryResult =
        call(
            "POST", "channel/get-history/$channelId",
            obj("messageIdFrom" to messageIdFrom, "messageTimeFrom" to messageTimeFrom, "fetchLength" to fetchLength, "fetchDirection" to direction),
        )

    // ---------- メッセージ ----------

    suspend fun message(messageId: String): Message = call("GET", "message/$messageId")
    suspend fun hasNew(): Map<String, Boolean> = call("GET", "message/get-new")
    suspend fun readTimes(): List<ReadTime> = call("GET", "message/read-time/get")
    suspend fun updateReadTime(channelId: String, readTime: String) =
        callUnit("POST", "message/read-time/update", obj("channelId" to channelId, "readTime" to readTime))

    suspend fun send(channelId: String, text: String, fileIds: List<String>, replyingMessageId: String?): Message {
        // 通信から解析までを 1 つの withContext で囲み、メインスレッドへ戻るのを終わりの 1 回にする (D-074)。
        // 以前は通信 (IO) と解析 (Default) を別々に囲んでいて、間でメインに戻っていた。押した直後はメインが描画で塞がりやすく、
        // その間解析が始まらなかった (D-068 の記録)。http_body は本文を読み終えた時点、parsed は解析を終えた時点で、どちらもメインに戻る前に記録する (#70)
        return withContext(Dispatchers.Default) {
            val res = raw("POST", "message/send", obj("channelId" to channelId, "message" to text, "fileIds" to fileIds, "replyingMessageId" to replyingMessageId))
                .also { com.giracle.android.data.SendTrace.mark("http_body") }
            AppJson.decodeFromString(ApiResponse.serializer(Message.serializer()), res).data
                .also { com.giracle.android.data.SendTrace.mark("parsed") }
        }
    }

    suspend fun edit(messageId: String, text: String) = callUnit("POST", "message/edit", obj("messageId" to messageId, "message" to text))
    suspend fun delete(messageId: String) = callUnit("DELETE", "message/delete", obj("messageId" to messageId))
    suspend fun react(messageId: String, channelId: String, emojiCode: String) =
        callUnit("POST", "message/emoji-reaction", obj("messageId" to messageId, "channelId" to channelId, "emojiCode" to emojiCode))

    suspend fun unreact(messageId: String, channelId: String, emojiCode: String) =
        callUnit("DELETE", "message/delete-emoji-reaction", obj("messageId" to messageId, "channelId" to channelId, "emojiCode" to emojiCode))

    suspend fun whoReacted(messageId: String, emojiCode: String, cursor: Int = 1): List<String> =
        call("GET", "message/who-reacted", query = mapOf("messageId" to messageId, "emojiCode" to emojiCode, "cursor" to cursor))

    suspend fun inbox(): List<InboxItem> = call("GET", "message/inbox")
    suspend fun inboxRead(messageId: String) = callUnit("POST", "message/inbox/read", obj("messageId" to messageId))
    suspend fun inboxClear() = callUnit("POST", "message/inbox/clear")

    suspend fun search(
        content: String?, channelId: String?, userId: String?, hasUrlPreview: Boolean?, hasFileAttachment: Boolean?,
        loadIndex: Int, sort: String,
    ): List<Message> = call(
        "GET", "message/search",
        query = mapOf(
            "content" to content?.ifBlank { null }, "channelId" to channelId?.ifBlank { null },
            "userId" to userId?.ifBlank { null }, "hasUrlPreview" to hasUrlPreview,
            "hasFileAttachment" to hasFileAttachment, "loadIndex" to loadIndex, "sort" to sort,
        ),
    )

    /** ファイルをアップロードし、ファイル ID を返す */
    suspend fun uploadFile(channelId: String, file: UploadFile): String {
        // call と同じく、通信から解析までを 1 つの withContext で囲む (D-074)
        return withContext(Dispatchers.Default) {
            val text = multipart("POST", "message/file/upload", mapOf("channelId" to channelId), "file", file)
            val root = AppJson.parseToJsonElement(text).jsonObject
            root["data"]!!.jsonObject["fileId"]!!.jsonObject["id"]!!.jsonPrimitive.content
        }
    }

    // ---------- ロール ----------

    suspend fun roles(): List<Role> = call("GET", "role/list")
    suspend fun role(roleId: String): Role = call("GET", "role/$roleId")
    suspend fun createRole(name: String) = callUnit("PUT", "role/create", obj("roleName" to name, "rolePower" to JsonObject(emptyMap())))
    suspend fun updateRole(role: Role) = callUnit(
        "POST", "role/update",
        obj(
            "roleId" to role.id,
            "roleData" to obj(
                "name" to role.name, "color" to role.color, "manageServer" to role.manageServer,
                "manageChannel" to role.manageChannel, "manageRole" to role.manageRole,
                "manageUser" to role.manageUser, "manageEmoji" to role.manageEmoji,
            ),
        ),
    )

    suspend fun deleteRole(roleId: String) = callUnit("DELETE", "role/delete", obj("roleId" to roleId))
    suspend fun linkRole(userId: String, roleId: String) = callUnit("POST", "role/link", obj("userId" to userId, "roleId" to roleId))
    suspend fun unlinkRole(userId: String, roleId: String) = callUnit("POST", "role/unlink", obj("userId" to userId, "roleId" to roleId))

    // ---------- 通知 ----------

    suspend fun notificationConfig(): NotificationConfig = call("GET", "notification/config")
    suspend fun updateNotificationConfig(enabled: Boolean?, mode: String?): NotificationConfig =
        call("POST", "notification/config", obj("enabled" to enabled, "mode" to mode))

    /** Web Push 用の VAPID 公開鍵。サーバーで未設定なら 503 */
    suspend fun vapidPublicKey(): String = call<VapidKey>("GET", "notification/vapid-public-key").publicKey

    /** プッシュ通知の端末登録。UnifiedPush の宛先は Web Push と同じ形式なので platform は "web" */
    suspend fun deviceRegister(endpoint: String, p256dh: String, auth: String, deviceName: String) = callUnit(
        "POST", "notification/device/register",
        obj("token" to endpoint, "platform" to "web", "keys" to obj("p256dh" to p256dh, "auth" to auth), "deviceName" to deviceName),
    )

    suspend fun deviceUnregister(endpoint: String) = callUnit("POST", "notification/device/unregister", obj("token" to endpoint))

    suspend fun mutedChannels(): List<MutedChannel> = call("GET", "notification/muted-channels")
    suspend fun muteChannel(channelId: String) = callUnit("POST", "notification/mute-channel", obj("channelId" to channelId))
    suspend fun unmuteChannel(channelId: String) = callUnit("POST", "notification/unmute-channel", obj("channelId" to channelId))

    // ---------- Bot ----------

    suspend fun myBots(query: String? = null, cursorBotId: String? = null): List<BotInfo> =
        call("GET", "bot/list", query = mapOf("length" to 50, "query" to query?.ifBlank { null }, "cursorBotId" to cursorBotId))

    suspend fun allBots(query: String? = null, cursorBotId: String? = null): List<BotInfo> =
        call("GET", "bot/all", query = mapOf("length" to 50, "query" to query?.ifBlank { null }, "cursorBotId" to cursorBotId))

    suspend fun botByRemoteUser(remoteUserId: String): BotInfo = call("GET", "bot/$remoteUserId")
    suspend fun createBot(name: String, introduction: String?) =
        callUnit("PUT", "bot", obj("name" to name, "introduction" to introduction?.ifBlank { null }))

    suspend fun updateBot(botId: String, name: String?, selfIntroduction: String?) =
        callUnit("POST", "bot", obj("botId" to botId, "name" to name, "selfIntroduction" to selfIntroduction))

    suspend fun deleteBot(botId: String) = callUnit("DELETE", "bot", obj("botId" to botId))
    suspend fun changeBotIcon(botId: String, file: UploadFile) { multipart("POST", "bot/change-icon", mapOf("botId" to botId), "icon", file) }
}
