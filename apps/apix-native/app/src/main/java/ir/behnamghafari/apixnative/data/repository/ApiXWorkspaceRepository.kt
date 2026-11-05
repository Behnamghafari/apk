package ir.behnamghafari.apixnative.data.repository

import android.content.Context
import ir.behnamghafari.apixnative.data.local.ApiXDatabase
import ir.behnamghafari.apixnative.data.model.ApiEnvironment
import ir.behnamghafari.apixnative.data.model.RequestFolder
import ir.behnamghafari.apixnative.data.model.RequestHistoryEntry
import ir.behnamghafari.apixnative.data.model.SavedRequest

/** Small repository boundary between Compose state and the local workspace database. */
class ApiXWorkspaceRepository(context: Context) {
    private val database = ApiXDatabase(context)

    fun saveRequest(request: SavedRequest) = database.saveRequest(request)
    fun savedRequests(folderId: String? = null) = database.getSavedRequests(folderId)
    fun deleteRequest(id: String) = database.deleteSavedRequest(id)

    fun createFolder(name: String, parentId: String? = null): RequestFolder {
        require(name.isNotBlank()) { "نام پوشه نمی‌تواند خالی باشد." }
        val folder = RequestFolder(
            id = ApiXDatabase.newId(),
            name = name.trim(),
            parentId = parentId,
            createdAt = System.currentTimeMillis()
        )
        database.saveFolder(folder)
        return folder
    }

    fun folders(parentId: String? = null) = database.getFolders(parentId)
    fun renameFolder(id: String, name: String): Int {
        require(name.isNotBlank()) { "نام پوشه نمی‌تواند خالی باشد." }
        return database.renameFolder(id, name.trim())
    }
    fun deleteFolder(id: String) = database.deleteFolder(id)

    fun saveEnvironment(environment: ApiEnvironment) = database.saveEnvironment(environment)
    fun environments() = database.getEnvironments()
    fun activateEnvironment(id: String?) = database.setActiveEnvironment(id)

    fun recordHistory(
        method: String,
        url: String,
        statusCode: Int?,
        durationMs: Long,
        responseSize: Long
    ) = database.addHistory(
        RequestHistoryEntry(
            id = ApiXDatabase.newId(),
            method = method,
            url = url,
            statusCode = statusCode,
            durationMs = durationMs,
            responseSize = responseSize,
            createdAt = System.currentTimeMillis()
        )
    )

    fun history(limit: Int = 200) = database.getHistory(limit)
    fun clearHistory() = database.clearHistory()
    fun close() = database.close()
}
