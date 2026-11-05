package ir.behnamghafari.apixnative.data.importer

import ir.behnamghafari.apixnative.data.local.ApiXDatabase
import ir.behnamghafari.apixnative.data.model.SavedRequest
import org.json.JSONArray
import org.json.JSONObject

/** Imports common curl command forms without invoking a shell. */
object CurlCommandParser {
    fun parse(command: String): SavedRequest {
        val tokens = tokenize(command.trim())
        require(tokens.isNotEmpty() && tokens.first().equals("curl", true)) {
            "دستور باید با curl شروع شود."
        }
        var method: String? = null
        var url = ""
        var user: String? = null
        var body: String? = null
        var getWithData = false
        val headers = JSONArray()

        fun nextValue(index: Int, flag: String): String =
            tokens.getOrNull(index + 1) ?: throw IllegalArgumentException("مقدار $flag مشخص نشده است.")

        var i = 1
        while (i < tokens.size) {
            val token = tokens[i]
            when (token) {
                "-X", "--request" -> { method = nextValue(i, token).uppercase(); i++ }
                "-H", "--header" -> {
                    val value = nextValue(i, token)
                    val split = value.indexOf(':')
                    require(split > 0) { "هدر نامعتبر در دستور cURL: $value" }
                    headers.put(JSONObject().put("key", value.substring(0, split).trim())
                        .put("value", value.substring(split + 1).trim()).put("enabled", true))
                    i++
                }
                "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode" -> {
                    val value = nextValue(i, token)
                    body = listOfNotNull(body, value).joinToString("&")
                    i++
                }
                "-u", "--user" -> { user = nextValue(i, token); i++ }
                "-G", "--get" -> getWithData = true
                "-I", "--head" -> method = "HEAD"
                "--url" -> { url = nextValue(i, token); i++ }
                "--compressed", "--location", "-L", "--insecure", "-k", "-s", "--silent", "-v", "--verbose" -> Unit
                else -> if (!token.startsWith("-") && url.isBlank()) url = token
            }
            i++
        }
        require(url.isNotBlank()) { "آدرس درخواست در دستور cURL پیدا نشد." }
        if (getWithData && !body.isNullOrBlank()) {
            url += (if (url.contains("?")) "&" else "?") + body
            body = null
        }
        if (method == null) method = if (body != null) "POST" else "GET"

        val auth = JSONObject()
        var authType = "none"
        if (!user.isNullOrBlank()) {
            val separator = user.indexOf(':')
            authType = "basic"
            auth.put("username", if (separator >= 0) user.substring(0, separator) else user)
            auth.put("password", if (separator >= 0) user.substring(separator + 1) else "")
        }
        val contentType = (0 until headers.length()).map { headers.getJSONObject(it) }
            .firstOrNull { it.optString("key").equals("Content-Type", true) }?.optString("value").orEmpty()
        val bodyType = when {
            body == null -> "none"
            contentType.contains("application/json", true) -> "json"
            contentType.contains("application/x-www-form-urlencoded", true) -> "form"
            else -> "raw"
        }
        val now = System.currentTimeMillis()
        return SavedRequest(
            id = ApiXDatabase.newId(),
            name = "${method} ${url}",
            method = method,
            url = url,
            headersJson = headers.toString(),
            body = body.orEmpty(),
            bodyType = bodyType,
            authType = authType,
            authConfigJson = auth.toString(),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun tokenize(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (char in command) {
            if (escaped) {
                current.append(char)
                escaped = false
            } else if (char == '\\' && quote != '\'') {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null else current.append(char)
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char.isWhitespace()) {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.setLength(0)
                }
            } else current.append(char)
        }
        require(quote == null) { "علامت نقل‌قول در دستور cURL بسته نشده است." }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
