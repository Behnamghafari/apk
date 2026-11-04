package com.behnamghafari.tester

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.core.view.WindowCompat
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val maxResponseBytes = 5 * 1024 * 1024
    private val uploadRequestCode = 4812
    private val cookieJar by lazy { PersistentCookieJar(getSharedPreferences("apix_http_cookies", MODE_PRIVATE)) }
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.rgb(11, 17, 32)
        window.navigationBarColor = android.graphics.Color.rgb(11, 17, 32)
        web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(11, 17, 32))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            addJavascriptInterface(Bridge(), "BehnamNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    return !(target.scheme == "file" && target.path?.startsWith("/android_asset/") == true)
                }
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/tester.html")
    }

    inner class Bridge {
        @JavascriptInterface fun sendRequest(raw: String) {
            Thread {
                val id = try { JSONObject(raw).optString("id") } catch (_: Exception) { "" }
                try {
                    val data = JSONObject(raw)
                    val url = data.getString("url").trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) { "URL must start with http:// or https://" }
                    val method = data.optString("method", "GET").uppercase()
                    val builder = Request.Builder().url(url)
                    val headers = data.optJSONArray("headers")
                    if (headers != null) for (i in 0 until headers.length()) {
                        val h = headers.optJSONObject(i) ?: continue
                        val name = h.optString("key").trim()
                        if (name.isNotEmpty() && h.optBoolean("enabled", true)) builder.addHeader(name, h.optString("value"))
                    }
                    val bodyType = data.optString("bodyType", "none")
                    val bodyText = data.optString("body", "")
                    val mediaType = data.optString("contentType", "application/json").toMediaTypeOrNull()
                    val requestBody: RequestBody? = when (bodyType) {
                        "json", "raw" -> bodyText.toRequestBody(mediaType)
                        "form" -> {
                            val form = data.optJSONArray("form")
                            val fb = FormBody.Builder()
                            if (form != null) for (i in 0 until form.length()) {
                                val item = form.optJSONObject(i) ?: continue
                                if (item.optBoolean("enabled", true) && item.optString("key").isNotBlank())
                                    fb.add(item.optString("key"), item.optString("value"))
                            }
                            fb.build()
                        }
                        "none" -> null
                        else -> bodyText.toRequestBody(mediaType)
                    }
                    val start = System.currentTimeMillis()
                    val req = builder.method(method, if (method in listOf("GET", "HEAD")) null else requestBody).build()
                    val requestClient = client.newBuilder().callTimeout(data.optLong("timeout", 60L).coerceIn(5L, 300L), TimeUnit.SECONDS).build()
                    requestClient.newCall(req).execute().use { response ->
                        val responseHeaders = JSONObject()
                        response.headers.names().sorted().forEach { key ->
                            responseHeaders.put(key, response.headers.values(key).joinToString(", "))
                        }
                        val source = response.body?.source()
                        val buffer = okio.Buffer()
                        while (source != null && buffer.size <= maxResponseBytes) {
                            if (source.read(buffer, 8192L) == -1L) break
                        }
                        val truncated = buffer.size > maxResponseBytes
                        val bytes = buffer.readByteArray(minOf(buffer.size, maxResponseBytes.toLong()))
                        val contentType = response.header("Content-Type") ?: ""
                        val isText = contentType.contains("json", true) || contentType.startsWith("text/", true) ||
                            contentType.contains("xml", true) || contentType.contains("javascript", true) ||
                            contentType.contains("x-www-form-urlencoded", true) || contentType.isBlank()
                        val responseBody = if (isText) String(bytes, Charsets.UTF_8) else "[Binary response: ${bytes.size} bytes]"
                        val displayBody = if (truncated) responseBody + "\\n\\n[Response truncated at 5 MB]" else responseBody
                        val result = JSONObject()
                            .put("id", id).put("ok", response.isSuccessful).put("status", response.code)
                            .put("statusText", response.message).put("duration", System.currentTimeMillis() - start)
                            .put("size", bytes.size).put("headers", responseHeaders)
                            .put("body", displayBody).put("contentType", contentType)
                            .put("truncated", truncated)
                            .put("finalUrl", response.request.url.toString())
                        deliver(result)
                    }
                } catch (e: Exception) {
                    deliver(JSONObject().put("id", id).put("error", e.message ?: e.javaClass.simpleName))
                }
            }.start()
        }

        private fun deliver(result: JSONObject) {
            runOnUiThread {
                if (::web.isInitialized) web.evaluateJavascript(
                    "window.BehnamTester && window.BehnamTester.onResult(" + JSONObject.quote(result.toString()) + ")", null
                )
            }
        }
        @JavascriptInterface fun chooseUploadFiles() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(intent, uploadRequestCode)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "انتخاب فایل ممکن نیست", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface fun sendUploadRequest(raw: String) {
            Thread {
                val id = try { JSONObject(raw).optString("id") } catch (_: Exception) { "" }
                try {
                    val data = JSONObject(raw)
                    val url = data.getString("url").trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) { "URL must start with http:// or https://" }
                    val files = data.optJSONArray("files") ?: throw IllegalArgumentException("فایلی انتخاب نشده")
                    require(files.length() > 0) { "حداقل یک فایل انتخاب کن" }
                    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    val fields = data.optJSONArray("fields")
                    if (fields != null) for (i in 0 until fields.length()) {
                        val item = fields.optJSONObject(i) ?: continue
                        val name = item.optString("key").trim()
                        if (name.isNotEmpty()) multipart.addFormDataPart(name, item.optString("value"))
                    }
                    for (i in 0 until files.length()) {
                        val item = files.optJSONObject(i) ?: continue
                        val uri = Uri.parse(item.getString("uri"))
                        val filename = item.optString("name", "upload.bin").replace("\\", "_").replace("/", "_")
                        val mime = (item.optString("mime").takeIf { it.isNotBlank() } ?: "application/octet-stream").toMediaTypeOrNull()
                        val partBody = object : RequestBody() {
                            override fun contentType() = mime
                            override fun writeTo(sink: okio.BufferedSink) {
                                contentResolver.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) }
                                    ?: throw IllegalStateException("Cannot read selected file")
                            }
                        }
                        multipart.addFormDataPart(item.optString("field", "file").ifBlank { "file" }, filename, partBody)
                    }
                    val builder = Request.Builder().url(url)
                    val headers = data.optJSONArray("headers")
                    if (headers != null) for (i in 0 until headers.length()) {
                        val h = headers.optJSONObject(i) ?: continue
                        val name = h.optString("key").trim()
                        if (name.isNotEmpty()) builder.addHeader(name, h.optString("value"))
                    }
                    val method = data.optString("method", "POST").uppercase()
                    require(method in listOf("POST", "PUT", "PATCH")) { "Upload method must be POST, PUT or PATCH" }
                    val start = System.currentTimeMillis()
                    val request = builder.method(method, multipart.build()).build()
                    val timeout = data.optLong("timeout", 120L).coerceIn(5L, 600L)
                    client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                        val responseHeaders = JSONObject()
                        response.headers.names().sorted().forEach { key -> responseHeaders.put(key, response.headers.values(key).joinToString(", ")) }
                        val source = response.body?.source()
                        val buffer = okio.Buffer()
                        while (source != null && buffer.size <= maxResponseBytes) if (source.read(buffer, 8192L) == -1L) break
                        val truncated = buffer.size > maxResponseBytes
                        val bytes = buffer.readByteArray(minOf(buffer.size, maxResponseBytes.toLong()))
                        val contentType = response.header("Content-Type") ?: ""
                        val textResponse = contentType.contains("json", true) || contentType.startsWith("text/", true) || contentType.contains("xml", true) || contentType.isBlank()
                        val body = if (textResponse) String(bytes, Charsets.UTF_8) else "[Binary response: ${bytes.size} bytes]"
                        val result = JSONObject().put("id", id).put("ok", response.isSuccessful).put("status", response.code)
                            .put("statusText", response.message).put("duration", System.currentTimeMillis() - start)
                            .put("size", bytes.size).put("headers", responseHeaders)
                            .put("body", if (truncated) body + "\\n\\n[Response truncated at 5 MB]" else body)
                            .put("contentType", contentType).put("truncated", truncated).put("finalUrl", response.request.url.toString())
                        deliverUpload(result)
                    }
                } catch (e: Exception) {
                    deliverUpload(JSONObject().put("id", id).put("error", e.message ?: e.javaClass.simpleName))
                }
            }.start()
        }

        private fun deliverUpload(result: JSONObject) {
            runOnUiThread {
                if (::web.isInitialized) web.evaluateJavascript(
                    "window.BehnamTester && window.BehnamTester.onUploadResult(" + JSONObject.quote(result.toString()) + ")", null
                )
            }
        }

        @JavascriptInterface fun getCookies(): String = cookieJar.exportJson().toString()
        @JavascriptInterface fun addCookie(url: String, setCookie: String): String {
            return try {
                cookieJar.addCookie(url, setCookie)
                JSONObject().put("ok", true).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Invalid cookie").toString()
            }
        }
        @JavascriptInterface fun deleteCookie(name: String, domain: String, path: String): Boolean =
            cookieJar.deleteCookie(name, domain, path)
        @JavascriptInterface fun clearCookies() { cookieJar.clear() }

        @JavascriptInterface fun openTelegram() {
            runOnUiThread {
                val profileUrl = Uri.parse("https://t.me/behnamghafari")
                val telegramPackages = listOf("org.telegram.messenger", "org.thunderdog.challegram")
                var opened = false
                for (packageName in telegramPackages) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, profileUrl).setPackage(packageName)
                        startActivity(intent)
                        opened = true
                        break
                    } catch (_: Exception) {
                        // Try another installed Telegram client, then the default browser.
                    }
                }
                if (!opened) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, profileUrl))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "تلگرام یا مرورگر در دسترس نیست", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface fun copyText(value: String) {
            runOnUiThread {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ApiX", value))
                Toast.makeText(this@MainActivity, "گزارش در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface fun toast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != uploadRequestCode) return
        val selected = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
            if (selected.isEmpty()) data.data?.let { selected.add(it) }
        }
        val items = org.json.JSONArray()
        selected.forEach { uri ->
            var name = "upload.bin"; var size = -1L
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) size = cursor.getLong(index)
                }
            }
            items.put(JSONObject().put("uri", uri.toString()).put("name", name).put("size", size).put("mime", contentResolver.getType(uri) ?: "application/octet-stream"))
        }
        if (::web.isInitialized) web.evaluateJavascript(
            "window.BehnamTester && window.BehnamTester.onUploadFilesSelected(" + JSONObject.quote(items.toString()) + ")", null
        )
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("window.BehnamTester && window.BehnamTester.back()", null)
    }
    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}

private class PersistentCookieJar(private val preferences: SharedPreferences) : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    init {
        synchronized(lock) {
            val stored = try { JSONArray(preferences.getString("cookies", "[]")) } catch (_: Exception) { JSONArray() }
            for (i in 0 until stored.length()) {
                val item = stored.optJSONObject(i) ?: continue
                try {
                    val builder = Cookie.Builder()
                        .name(item.getString("name"))
                        .value(item.getString("value"))
                        .path(item.optString("path", "/"))
                        .expiresAt(item.optLong("expiresAt", Long.MAX_VALUE))
                    val domain = item.getString("domain")
                    if (item.optBoolean("hostOnly", false)) builder.hostOnlyDomain(domain) else builder.domain(domain)
                    if (item.optBoolean("secure")) builder.secure()
                    if (item.optBoolean("httpOnly")) builder.httpOnly()
                    val cookie = builder.build()
                    if (cookie.expiresAt > System.currentTimeMillis()) cookies.add(cookie)
                } catch (_: Exception) { }
            }
            persistLocked()
        }
    }

    override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
        synchronized(lock) {
            received.forEach { incoming ->
                cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) cookies.add(incoming)
            }
            cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
            persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        persistLocked()
        cookies.filter { it.matches(url) }.toList()
    }

    fun addCookie(url: String, raw: String) {
        val parsedUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("آدرس معتبر نیست")
        val cookie = Cookie.parse(parsedUrl, raw) ?: throw IllegalArgumentException("فرمت Set-Cookie معتبر نیست")
        saveFromResponse(parsedUrl, listOf(cookie))
    }

    fun deleteCookie(name: String, domain: String, path: String): Boolean = synchronized(lock) {
        val removed = cookies.removeAll { it.name == name && it.domain == domain && it.path == path }
        if (removed) persistLocked()
        removed
    }

    fun clear() = synchronized(lock) {
        cookies.clear()
        persistLocked()
    }

    fun exportJson(): JSONArray = synchronized(lock) {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        persistLocked()
        JSONArray().also { result ->
            cookies.sortedWith(compareBy<Cookie> { it.domain }.thenBy { it.name }).forEach { cookie ->
                result.put(JSONObject()
                    .put("name", cookie.name)
                    .put("value", cookie.value)
                    .put("domain", cookie.domain)
                    .put("path", cookie.path)
                    .put("expiresAt", cookie.expiresAt)
                    .put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly)
                    .put("hostOnly", cookie.hostOnly)
                    .put("persistent", cookie.persistent))
            }
        }
    }

    private fun persistLocked() {
        val data = JSONArray()
        cookies.forEach { cookie ->
            data.put(JSONObject()
                .put("name", cookie.name)
                .put("value", cookie.value)
                .put("domain", cookie.domain)
                .put("path", cookie.path)
                .put("expiresAt", cookie.expiresAt)
                .put("secure", cookie.secure)
                .put("httpOnly", cookie.httpOnly)
                .put("hostOnly", cookie.hostOnly)
                .put("persistent", cookie.persistent))
        }
        preferences.edit().putString("cookies", data.toString()).apply()
    }
}
