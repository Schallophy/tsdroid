package dev.tsdroid.model

/** Where a chat message was sent or received. */
sealed interface ChatTarget {
    data object Server : ChatTarget
    data object Channel : ChatTarget
    data class Private(val clientId: Int, val name: String) : ChatTarget
}

enum class ChatScope { Server, Channel, Private }

/** One line in the chat view. */
data class ChatMessage(
    val key: Long,
    val scope: ChatScope,
    val fromId: Int,
    val fromName: String,
    val text: String,
    val timestamp: Long,
    val mine: Boolean,
    /** Client id of the other side for private messages, 0 otherwise. */
    val peerId: Int = 0,
    /**
     * A poke rather than typed text.
     *
     * Pokes live in the private conversation with the person who sent them: they
     * are addressed to one client, they are part of that conversation's history,
     * and showing them in the channel scroll made them look like public messages.
     */
    val poke: Boolean = false,
) {
    val subline: String
        get() = when (scope) {
            ChatScope.Server -> "服务器"
            ChatScope.Channel -> "当前频道"
            ChatScope.Private -> "私信"
        }
}

/** A file transfer as shown in the files tab. */
data class Transfer(
    val id: String,
    val name: String,
    val direction: TransferDirection,
    val done: Long,
    val total: Long,
    val state: TransferState,
    val localPath: String? = null,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

enum class TransferDirection { Download, Upload }

enum class TransferState { Running, Done, Failed }
