package ir.behnamghafari.apixnative.data.repository

import android.content.Context
import ir.behnamghafari.apixnative.data.local.ApiXDatabase
import ir.behnamghafari.apixnative.data.model.ApiEnvironment
import ir.behnamghafari.apixnative.data.model.RequestFolder
import ir.behnamghafari.apixnative.data.model.RequestHistoryEntry
import ir.behnamghafari.apixnative.data.model.SavedRequest
import ir.behnamghafari.apixnative.data.security.SecretVault

/** Repository boundary with transparent at-rest encryption for request and environment secrets. */
class ApiXWorkspaceRepository(context: Context) {
    private val database = ApiXDatabase(context)
    private val vault = SecretVault()

    fun saveRequest(request: SavedRequest) = database.saveRequest(protectRequest(request))
    fun savedRequests(folderId: String? = null) =
        database.getSavedRequests(folderId).map(::revealRequest)
    fun allSavedRequests() = database.getAllSavedRequests().map(::revealRequest)
    fun allFolders() = database.getAllFolders()
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

    fun saveEnvironment(environment: ApiEnvironment) =
        database.saveEnvironment(environment.copy(variablesJson = vault.protect(environment.variablesJson)))

    fun environments() = database.getEnvironments().map { stored ->
        if (stored.variablesJson.isNotBlank() && !vault.isEncrypted(stored.variablesJson)) {
            database.saveEnvironment(stored.copy(variablesJson = vault.protect(stored.variablesJson)))
        }
        stored.copy(variablesJson = vault.reveal(stored.variablesJson))
    }

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
            url = vault.protect(url),
            statusCode = statusCode,
            durationMs = durationMs,
            responseSize = responseSize,
            createdAt = System.currentTimeMillis()
        )
    )

    fun history(limit: Int = 200) = database.getHistory(limit).map { stored ->
        if (stored.url.isNotBlank() && !vault.isEncrypted(stored.url)) {
            database.updateHistoryUrl(stored.id, vault.protect(stored.url))
        }
        stored.copy(url = vault.reveal(stored.url))
    }
    fun clearHistory() = database.clearHistory()

    private fun protectRequest(request: SavedRequest): SavedRequest = request.copy(
        url = vault.protect(request.url),
        headersJson = vault.protect(request.headersJson),
        paramsJson = vault.protect(request.paramsJson),
        body = vault.protect(request.body),
        authConfigJson = vault.protect(request.authConfigJson)
    )

    private fun revealRequest(stored: SavedRequest): SavedRequest {
        val values = listOf(stored.url, stored.headersJson, stored.paramsJson, stored.body, stored.authConfigJson)
        if (values.any { it.isNotBlank() && !vault.isEncrypted(it) }) {
            database.saveRequest(protectRequest(stored))
        }
        return stored.copy(
            url = vault.reveal(stored.url),
            headersJson = vault.reveal(stored.headersJson),
            paramsJson = vault.reveal(stored.paramsJson),
            body = vault.reveal(stored.body),
            authConfigJson = vault.reveal(stored.authConfigJson)
        )
    }

    fun close() = database.close()
}
