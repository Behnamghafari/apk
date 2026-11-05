package ir.behnamghafari.apixnative.data.model

data class SavedRequest(
    val id: String,
    val name: String,
    val method: String,
    val url: String,
    val headersJson: String = "[]",
    val paramsJson: String = "[]",
    val body: String = "",
    val bodyType: String = "none",
    val authType: String = "none",
    val authConfigJson: String = "{}",
    val folderId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class RequestFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long
)

data class ApiEnvironment(
    val id: String,
    val name: String,
    val variablesJson: String = "[]",
    val isActive: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

data class RequestHistoryEntry(
    val id: String,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val durationMs: Long,
    val responseSize: Long,
    val createdAt: Long
)
