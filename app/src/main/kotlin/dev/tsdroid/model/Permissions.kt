package dev.tsdroid.model

/** One entry from the server's permission catalogue. */
data class PermissionEntry(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
)

/** One permission currently set on the target. */
data class PermissionValue(
    val name: String = "",
    val value: Int = 0,
    val negated: Boolean = false,
    val skip: Boolean = false,
)

/**
 * The permission editor's data. The catalogue is server-wide and stays after a
 * target change; the values belong to the channel currently open.
 */
data class PermissionEditorState(
    val channelId: Long? = null,
    val catalog: List<PermissionEntry> = emptyList(),
    val values: List<PermissionValue> = emptyList(),
    val loading: Boolean = false,
)
