package ir.behnamghafari.apixnative.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ir.behnamghafari.apixnative.data.model.ApiEnvironment
import ir.behnamghafari.apixnative.data.model.RequestFolder
import ir.behnamghafari.apixnative.data.model.RequestHistoryEntry
import ir.behnamghafari.apixnative.data.model.SavedRequest
import java.util.UUID

/**
 * Durable local store for ApiX workspaces. Schema changes must be added as
 * forward-only migrations; never drop user data during an upgrade.
 */
class ApiXDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE request_folders (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                parent_id TEXT NULL REFERENCES request_folders(id) ON DELETE RESTRICT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_folders_parent ON request_folders(parent_id, sort_order)")
        db.execSQL("""
            CREATE TABLE saved_requests (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                method TEXT NOT NULL,
                url TEXT NOT NULL,
                headers_json TEXT NOT NULL DEFAULT '[]',
                params_json TEXT NOT NULL DEFAULT '[]',
                body TEXT NOT NULL DEFAULT '',
                body_type TEXT NOT NULL DEFAULT 'none',
                auth_type TEXT NOT NULL DEFAULT 'none',
                auth_config_json TEXT NOT NULL DEFAULT '{}',
                folder_id TEXT NULL REFERENCES request_folders(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_saved_requests_folder ON saved_requests(folder_id, updated_at DESC)")
        db.execSQL("""
            CREATE TABLE environments (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                variables_json TEXT NOT NULL DEFAULT '[]',
                is_active INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX idx_one_active_environment ON environments(is_active) WHERE is_active = 1")
        db.execSQL("""
            CREATE TABLE request_history (
                id TEXT PRIMARY KEY NOT NULL,
                method TEXT NOT NULL,
                url TEXT NOT NULL,
                status_code INTEGER NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                response_size INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_history_created ON request_history(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial schema. Add explicit ALTER/CREATE migrations here.
        // Intentionally do not drop tables or user content.
    }

    fun saveRequest(request: SavedRequest) {
        writableDatabase.insertWithOnConflict(
            "saved_requests", null, request.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getAllSavedRequests(): List<SavedRequest> = readableDatabase.query(
        "saved_requests", null, null, null, null, null, "updated_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSavedRequest()) } }

    fun getAllFolders(): List<RequestFolder> = readableDatabase.query(
        "request_folders", null, null, null, null, null, "sort_order ASC, name COLLATE NOCASE ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toFolder()) } }

    fun getSavedRequests(folderId: String? = null): List<SavedRequest> {
        val selection = if (folderId == null) "folder_id IS NULL" else "folder_id = ?"
        val args = if (folderId == null) null else arrayOf(folderId)
        return readableDatabase.query(
            "saved_requests", null, selection, args, null, null, "updated_at DESC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSavedRequest()) } }
    }

    fun deleteSavedRequest(id: String): Int =
        writableDatabase.delete("saved_requests", "id = ?", arrayOf(id))

    fun saveFolder(folder: RequestFolder) {
        writableDatabase.insertWithOnConflict(
            "request_folders", null, folder.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getFolders(parentId: String? = null): List<RequestFolder> {
        val selection = if (parentId == null) "parent_id IS NULL" else "parent_id = ?"
        val args = if (parentId == null) null else arrayOf(parentId)
        return readableDatabase.query(
            "request_folders", null, selection, args, null, null, "sort_order ASC, name COLLATE NOCASE ASC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toFolder()) } }
    }

    fun renameFolder(id: String, name: String): Int =
        writableDatabase.update("request_folders", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id))

    fun deleteFolder(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val childCount = db.rawQuery(
                "SELECT COUNT(*) FROM request_folders WHERE parent_id = ?", arrayOf(id)
            ).use { it.moveToFirst(); it.getInt(0) }
            check(childCount == 0) { "ابتدا پوشه‌های زیرمجموعه را جابه‌جا یا حذف کنید." }
            val deleted = db.delete("request_folders", "id = ?", arrayOf(id)) > 0
            db.setTransactionSuccessful()
            deleted
        } finally {
            // If validation or deletion fails, endTransaction rolls back automatically.
            db.endTransaction()
        }
    }

    fun saveEnvironment(environment: ApiEnvironment) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (environment.isActive) {
                db.execSQL("UPDATE environments SET is_active = 0")
            }
            db.insertWithOnConflict(
                "environments", null, environment.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getEnvironments(): List<ApiEnvironment> =
        readableDatabase.query("environments", null, null, null, null, null, "name COLLATE NOCASE ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toEnvironment()) } }

    fun setActiveEnvironment(id: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE environments SET is_active = 0")
            if (id != null) {
                db.update("environments", ContentValues().apply { put("is_active", 1) }, "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addHistory(entry: RequestHistoryEntry) {
        writableDatabase.insert("request_history", null, entry.toContentValues())
    }

    fun getHistory(limit: Int = 200): List<RequestHistoryEntry> =
        readableDatabase.query(
            "request_history", null, null, null, null, null, "created_at DESC", limit.coerceIn(1, 2000).toString()
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toHistory()) } }

    fun clearHistory() {
        writableDatabase.delete("request_history", null, null)
    }

    private fun SavedRequest.toContentValues() = ContentValues().apply {
        put("id", id); put("name", name); put("method", method); put("url", url)
        put("headers_json", headersJson); put("params_json", paramsJson); put("body", body)
        put("body_type", bodyType); put("auth_type", authType); put("auth_config_json", authConfigJson)
        put("folder_id", folderId); put("created_at", createdAt); put("updated_at", updatedAt)
    }

    private fun RequestFolder.toContentValues() = ContentValues().apply {
        put("id", id); put("name", name); put("parent_id", parentId)
        put("sort_order", sortOrder); put("created_at", createdAt)
    }

    private fun ApiEnvironment.toContentValues() = ContentValues().apply {
        put("id", id); put("name", name); put("variables_json", variablesJson)
        put("is_active", if (isActive) 1 else 0); put("created_at", createdAt); put("updated_at", updatedAt)
    }

    private fun RequestHistoryEntry.toContentValues() = ContentValues().apply {
        put("id", id); put("method", method); put("url", url); put("status_code", statusCode)
        put("duration_ms", durationMs); put("response_size", responseSize); put("created_at", createdAt)
    }

    private fun Cursor.toSavedRequest() = SavedRequest(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        method = getString(getColumnIndexOrThrow("method")),
        url = getString(getColumnIndexOrThrow("url")),
        headersJson = getString(getColumnIndexOrThrow("headers_json")),
        paramsJson = getString(getColumnIndexOrThrow("params_json")),
        body = getString(getColumnIndexOrThrow("body")),
        bodyType = getString(getColumnIndexOrThrow("body_type")),
        authType = getString(getColumnIndexOrThrow("auth_type")),
        authConfigJson = getString(getColumnIndexOrThrow("auth_config_json")),
        folderId = getString(getColumnIndexOrThrow("folder_id")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toFolder() = RequestFolder(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        parentId = getString(getColumnIndexOrThrow("parent_id")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )

    private fun Cursor.toEnvironment() = ApiEnvironment(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        variablesJson = getString(getColumnIndexOrThrow("variables_json")),
        isActive = getInt(getColumnIndexOrThrow("is_active")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toHistory() = RequestHistoryEntry(
        id = getString(getColumnIndexOrThrow("id")),
        method = getString(getColumnIndexOrThrow("method")),
        url = getString(getColumnIndexOrThrow("url")),
        statusCode = if (isNull(getColumnIndexOrThrow("status_code"))) null else getInt(getColumnIndexOrThrow("status_code")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        responseSize = getLong(getColumnIndexOrThrow("response_size")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )

    companion object {
        private const val DATABASE_NAME = "apix_workspace.db"
        private const val DATABASE_VERSION = 1
        fun newId(): String = UUID.randomUUID().toString()
    }
}
