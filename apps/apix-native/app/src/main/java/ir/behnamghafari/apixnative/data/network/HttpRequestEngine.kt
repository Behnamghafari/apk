package ir.behnamghafari.apixnative.data.network

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.util.concurrent.TimeUnit

data class HttpKeyValue(val key: String, val value: String, val enabled: Boolean = true)

data class HttpRequestSpec(
    val method: String,
    val url: String,
    val queryParams: List<HttpKeyValue> = emptyList(),
    val headers: List<HttpKeyValue> = emptyList(),
    val body: String = "",
    val bodyType: String = "none",
    val authType: String = "none",
    val authUsername: String = "",
    val authPassword: String = "",
    val authToken: String = "",
    val apiKeyName: String = "",
    val apiKeyValue: String = "",
    val apiKeyIn: String = "header"
)

data class HttpResponseSnapshot(
    val statusCode: Int,
    val reason: String,
    val durationMs: Long,
    val finalUrl: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val truncated: Boolean
) {
    fun asDisplayText(): String = buildString {
        append("HTTP $statusCode $reason\n")
        append("زمان پاسخ: ${durationMs} ms\n")
        append("آدرس نهایی: $finalUrl\n\nHEADERS\n")
        headers.forEach { (name, value) -> append("$name: $value\n") }
        append("\nBODY\n")
        append(body)
        if (truncated) append("\n\n[پاسخ در محدودیت ۲ مگابایت بریده شد]")
    }
}

class HttpRequestEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    fun execute(spec: HttpRequestSpec): HttpResponseSnapshot {
        val method = spec.method.trim().uppercase()
        require(method in METHODS) { "متد HTTP پشتیبانی نمی‌شود: $method" }
        val parsedUrl = spec.url.trim()
        require(parsedUrl.startsWith("https://") || parsedUrl.startsWith("http://")) {
            "آدرس باید با http:// یا https:// شروع شود."
        }

        val urlBuilder = requireNotNull(okhttp3.HttpUrl.Companion.toHttpUrlOrNull(parsedUrl)) {
            "آدرس URL معتبر نیست."
        }.newBuilder()
        spec.queryParams.filter { it.enabled && it.key.isNotBlank() }
            .forEach { urlBuilder.addQueryParameter(it.key, it.value) }

        val builder = Request.Builder().url(urlBuilder.build())
        spec.headers.filter { it.enabled && it.key.isNotBlank() }
            .forEach { builder.addHeader(it.key.trim(), it.value) }

        when (spec.authType.lowercase()) {
            "bearer" -> if (spec.authToken.isNotBlank()) builder.header("Authorization", "Bearer ${spec.authToken}")
            "basic" -> {
                val credentials = okhttp3.Credentials.basic(spec.authUsername, spec.authPassword)
                builder.header("Authorization", credentials)
            }
            "apikey" -> if (spec.apiKeyName.isNotBlank()) {
                if (spec.apiKeyIn == "query") urlBuilder.addQueryParameter(spec.apiKeyName, spec.apiKeyValue)
                else builder.header(spec.apiKeyName, spec.apiKeyValue)
            }
        }

        val requestUrl = if (spec.authType.equals("apikey", true) && spec.apiKeyIn == "query") {
            builder.url(urlBuilder.build())
            urlBuilder.build()
        } else urlBuilder.build()

        val requestBody = when (spec.bodyType.lowercase()) {
            "none" -> null
            "json" -> spec.body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            "raw" -> spec.body.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
            "form" -> {
                val form = FormBody.Builder()
                spec.body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val split = line.indexOf('=')
                    require(split > 0) { "فرمت فرم باید key=value در هر خط باشد." }
                    form.add(line.substring(0, split).trim(), line.substring(split + 1))
                }
                form.build()
            }
            else -> throw IllegalArgumentException("نوع بدنه درخواست شناخته‌شده نیست.")
        }

        val bodyRequired = method in setOf("POST", "PUT", "PATCH")
        builder.method(method, if (bodyRequired && requestBody == null) ByteArray(0).toRequestBody(null) else requestBody)
        if (requestBody != null && spec.bodyType.equals("json", true) &&
            spec.headers.none { it.enabled && it.key.equals("Content-Type", true) }) {
            builder.header("Content-Type", "application/json; charset=utf-8")
        }

        val startedAt = System.nanoTime()
        client.newCall(builder.build()).execute().use { response ->
            val buffer = Buffer()
            val source = response.body?.source()
            val maxBytes = MAX_RESPONSE_BYTES
            while (source != null && buffer.size <= maxBytes) {
                if (source.read(buffer, 8192L) == -1L) break
            }
            val truncated = buffer.size > maxBytes
            val bytes = buffer.readByteArray(minOf(buffer.size, maxBytes))
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            HttpResponseSnapshot(
                statusCode = response.code,
                reason = response.message,
                durationMs = elapsed,
                finalUrl = response.request.url.toString(),
                headers = response.headers.toMultimap().toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .flatMap { (name, values) -> values.map { name to it } },
                body = bytes.toString(Charsets.UTF_8),
                truncated = truncated
            )
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    }
}
