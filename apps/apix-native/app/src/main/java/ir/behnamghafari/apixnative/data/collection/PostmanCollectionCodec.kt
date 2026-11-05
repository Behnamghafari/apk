package ir.behnamghafari.apixnative.data.collection

import ir.behnamghafari.apixnative.data.local.ApiXDatabase
import ir.behnamghafari.apixnative.data.model.RequestFolder
import ir.behnamghafari.apixnative.data.model.SavedRequest
import org.json.JSONArray
import org.json.JSONObject

data class ImportedPostmanCollection(
    val name: String,
    val requests: List<ImportedPostmanRequest>
)

data class ImportedPostmanRequest(
    val folderPath: List<String>,
    val request: SavedRequest
)

/** Postman Collection v2.1 JSON codec. Import is non-destructive and creates new local IDs. */
object PostmanCollectionCodec {
    fun parse(source: String): ImportedPostmanCollection {
        val root = JSONObject(source)
        val info = root.optJSONObject("info") ?: throw IllegalArgumentException("فایل، Collection معتبر Postman نیست.")
        val schema = info.optString("schema")
        require(schema.isBlank() || schema.contains("collection/v2.1")) {
            "فقط Postman Collection نسخه 2.1 پشتیبانی می‌شود."
        }
        val items = root.optJSONArray("item") ?: throw IllegalArgumentException("لیست درخواست‌های Collection پیدا نشد.")
        val imported = mutableListOf<ImportedPostmanRequest>()
        fun walk(array: JSONArray, path: List<String>) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val children = item.optJSONArray("item")
                if (children != null) {
                    val folder = item.optString("name").ifBlank { "Folder" }
                    walk(children, path + folder)
                } else {
                    val requestObject = item.optJSONObject("request") ?: continue
                    imported += ImportedPostmanRequest(path, parseRequest(item.optString("name"), requestObject))
                }
            }
        }
        walk(items, emptyList())
        return ImportedPostmanCollection(info.optString("name").ifBlank { "Imported Collection" }, imported)
    }

    private fun parseRequest(name: String, source: JSONObject): SavedRequest {
        val method = source.optString("method", "GET").uppercase()
        val urlObject = source.opt("url")
        val url = when (urlObject) {
            is JSONObject -> urlObject.optString("raw")
            is String -> urlObject
            else -> ""
        }
        val query = JSONArray()
        val headers = JSONArray()
        if (urlObject is JSONObject) {
            urlObject.optJSONArray("query")?.let { values ->
                for (i in 0 until values.length()) {
                    val entry = values.optJSONObject(i) ?: continue
                    query.put(JSONObject().put("key", entry.optString("key"))
                        .put("value", entry.optString("value")).put("enabled", entry.optBoolean("disabled").not()))
                }
            }
        }
        source.optJSONArray("header")?.let { values ->
            for (i in 0 until values.length()) {
                val entry = values.optJSONObject(i) ?: continue
                headers.put(JSONObject().put("key", entry.optString("key"))
                    .put("value", entry.optString("value")).put("enabled", !entry.optBoolean("disabled")))
            }
        }
        var body = ""
        var bodyType = "none"
        source.optJSONObject("body")?.let { payload ->
            when (payload.optString("mode")) {
                "raw" -> {
                    body = payload.optString("raw")
                    bodyType = if (payload.optString("options").contains("json", true)) "json" else "raw"
                }
                "urlencoded", "formdata" -> {
                    bodyType = "form"
                    val values = payload.optJSONArray(payload.optString("mode")) ?: JSONArray()
                    body = (0 until values.length()).mapNotNull { index ->
                        val entry = values.optJSONObject(index) ?: return@mapNotNull null
                        if (entry.optBoolean("disabled")) null else "${entry.optString("key")}=${entry.optString("value")}"
                    }.joinToString("\n")
                }
            }
        }
        val auth = JSONObject()
        var authType = "none"
        source.optJSONObject("auth")?.let { authObject ->
            authType = authObject.optString("type", "none").lowercase()
            val values = authObject.optJSONArray(authType)
            if (values != null) for (i in 0 until values.length()) {
                val entry = values.optJSONObject(i) ?: continue
                when (entry.optString("key")) {
                    "token" -> auth.put("token", entry.optString("value"))
                    "username" -> auth.put("username", entry.optString("value"))
                    "password" -> auth.put("password", entry.optString("value"))
                    "key" -> auth.put("apiKeyName", entry.optString("value"))
                    "value" -> auth.put("apiKeyValue", entry.optString("value"))
                    "in" -> auth.put("apiKeyIn", entry.optString("value"))
                }
            }
            if (authType !in setOf("bearer", "basic", "apikey")) authType = "none"
        }
        val now = System.currentTimeMillis()
        return SavedRequest(
            id = ApiXDatabase.newId(),
            name = name.ifBlank { "$method $url" },
            method = method,
            url = url,
            headersJson = headers.toString(),
            paramsJson = query.toString(),
            body = body,
            bodyType = bodyType,
            authType = authType,
            authConfigJson = auth.toString(),
            createdAt = now,
            updatedAt = now
        )
    }

    fun export(name: String, folders: List<RequestFolder>, requests: List<SavedRequest>): String {
        val folderByParent = folders.groupBy { it.parentId }
        val requestByFolder = requests.groupBy { it.folderId }
        fun postmanRequest(saved: SavedRequest): JSONObject {
            val headers = JSONArray(saved.headersJson).let { values ->
                JSONArray().apply {
                    for (i in 0 until values.length()) {
                        val entry = values.getJSONObject(i)
                        if (entry.optBoolean("enabled", true)) put(JSONObject()
                            .put("key", entry.optString("key")).put("value", entry.optString("value")))
                    }
                }
            }
            val query = JSONArray(saved.paramsJson).let { values ->
                JSONArray().apply {
                    for (i in 0 until values.length()) {
                        val entry = values.getJSONObject(i)
                        if (entry.optBoolean("enabled", true)) put(JSONObject()
                            .put("key", entry.optString("key")).put("value", entry.optString("value")))
                    }
                }
            }
            val authConfig = JSONObject(saved.authConfigJson)
            val auth = JSONObject().put("type", saved.authType)
            when (saved.authType) {
                "bearer" -> auth.put("bearer", JSONArray().put(JSONObject().put("key", "token").put("value", authConfig.optString("token"))))
                "basic" -> auth.put("basic", JSONArray()
                    .put(JSONObject().put("key", "username").put("value", authConfig.optString("username")))
                    .put(JSONObject().put("key", "password").put("value", authConfig.optString("password"))))
                "apikey" -> auth.put("apikey", JSONArray()
                    .put(JSONObject().put("key", "key").put("value", authConfig.optString("apiKeyName")))
                    .put(JSONObject().put("key", "value").put("value", authConfig.optString("apiKeyValue")))
                    .put(JSONObject().put("key", "in").put("value", authConfig.optString("apiKeyIn", "header"))))
            }
            val request = JSONObject().put("method", saved.method).put("header", headers)
                .put("url", JSONObject().put("raw", saved.url).put("query", query))
                .put("auth", auth)
            if (saved.bodyType != "none") {
                val mode = if (saved.bodyType == "form") "urlencoded" else "raw"
                val body = JSONObject().put("mode", mode)
                if (mode == "raw") body.put("raw", saved.body)
                    .put("options", JSONObject().put("raw", JSONObject().put("language", if (saved.bodyType == "json") "json" else "text")))
                else body.put("urlencoded", JSONArray().apply {
                    saved.body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val split = line.indexOf('=')
                        if (split > 0) put(JSONObject().put("key", line.substring(0, split)).put("value", line.substring(split + 1)))
                    }
                })
                request.put("body", body)
            }
            return JSONObject().put("method", saved.method).put("header", headers).put("url", request.getJSONObject("url"))
                .put("auth", auth).put("body", request.optJSONObject("body"))
        }
        fun requestItem(saved: SavedRequest) = JSONObject().put("name", saved.name).put("request", postmanRequest(saved))
        fun folderItems(parentId: String?): JSONArray {
            val result = JSONArray()
            (folderByParent[parentId] ?: emptyList()).forEach { folder ->
                result.put(JSONObject().put("name", folder.name).put("item", folderItems(folder.id)))
            }
            (requestByFolder[parentId] ?: emptyList()).forEach { result.put(requestItem(it)) }
            return result
        }
        val rootItems = folderItems(null)
        return JSONObject().put("info", JSONObject()
            .put("name", name.ifBlank { "ApiX Collection" })
            .put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
            .put("item", rootItems).toString(2)
    }
}
