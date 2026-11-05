package ir.behnamghafari.apitester

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val maxResponseBytes = 5 * 1024 * 1024
    private val uploadRequestCode = 4812
    private val backupSaveRequestCode = 4813
    private val backupRestoreRequestCode = 4814
    private val jsonImportRequestCode = 4815
    private val sqliteBackupSaveRequestCode = 4816
    private val sqliteBackupRestoreRequestCode = 4817
    @Volatile private var pendingJsonImportType: String = ""
    @Volatile private var pendingBackupJson: String? = null
    @Volatile private var pendingBackupName: String = "apix-backup.json"
    @Volatile private var pendingSqliteBackupFile: java.io.File? = null
    @Volatile private var pendingSqliteRestoreFile: java.io.File? = null
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var client: OkHttpClient
    @Volatile private var mockServer: ServerSocket? = null
    @Volatile private var mockRoutes = JSONArray()
    private val webSockets = ConcurrentHashMap<String, okhttp3.WebSocket>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cookieJar = PersistentCookieJar(getSharedPreferences("apix_http_cookies", MODE_PRIVATE))
        client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val appSurface = android.graphics.Color.rgb(11, 17, 32)
        window.decorView.setBackgroundColor(appSurface)
        window.statusBarColor = appSurface
        window.navigationBarColor = appSurface
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
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

    private val storageDb by lazy {
        openOrCreateDatabase("apix_storage.db", MODE_PRIVATE, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }
    }

    inner class Bridge {
        @JavascriptInterface fun getStorageSnapshot(): String {
            val rows = JSONArray()
            storageDb.rawQuery("SELECT key, value FROM kv", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val parsed = org.json.JSONTokener(cursor.getString(1)).nextValue()
                    rows.put(JSONObject().put("key", cursor.getString(0)).put("value", parsed))
                }
            }
            return rows.toString()
        }

        @JavascriptInterface fun setStorageValue(key: String, value: String) {
            if (!key.startsWith("bt_") || key.length > 160 || value.length > 8 * 1024 * 1024) return
            val values = android.content.ContentValues().apply { put("key", key); put("value", value) }
            storageDb.insertWithOnConflict("kv", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }

        @JavascriptInterface fun importStorageRecords(raw: String): Int {
            val records = JSONArray(raw)
            var imported = 0
            val db = storageDb
            db.beginTransaction()
            try {
                for (i in 0 until records.length()) {
                    val record = records.optJSONObject(i) ?: continue
                    val key = record.optString("key")
                    if (!key.startsWith("bt_") || key.length > 160) continue
                    val encoded = JSONObject().put("v", record.opt("value")).getString("v")
                    val isImportantList = key == "bt_folders" || key == "bt_collections"
                    var mergedLegacyValue: String? = null
                    if (isImportantList) {
                        db.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { cursor ->
                            val existing = if (cursor.moveToFirst()) try { JSONArray(cursor.getString(0)) } catch (_: Exception) { JSONArray() } else JSONArray()
                            val incoming = try { JSONArray(encoded) } catch (_: Exception) { JSONArray() }
                            if (incoming.length() > 0) {
                                val merged = JSONArray()
                                val seen = HashSet<String>()
                                for (source in listOf(existing, incoming)) {
                                    for (index in 0 until source.length()) {
                                        val item = source.opt(index) ?: continue
                                        val id = (item as? JSONObject)?.optString("id")?.takeIf { it.isNotBlank() } ?: item.toString()
                                        if (seen.add(id)) merged.put(item)
                                    }
                                }
                                if (merged.length() > existing.length()) mergedLegacyValue = merged.toString()
                            }
                        }
                    }
                    val finalValue = mergedLegacyValue ?: encoded
                    val values = android.content.ContentValues().apply { put("key", key); put("value", finalValue) }
                    val conflict = if (mergedLegacyValue != null) android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE else android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                    if (db.insertWithOnConflict("kv", null, values, conflict) != -1L) imported++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return imported
        }

        @JavascriptInterface fun replaceStorageSnapshot(raw: String): Int {
            require(raw.length <= 32 * 1024 * 1024) { "Backup file is too large" }
            val records = JSONArray(raw)
            require(records.length() <= 5000) { "Too many backup records" }
            val prepared = ArrayList<Pair<String, String>>(records.length())
            val seen = HashSet<String>()
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: throw IllegalArgumentException("Invalid backup record")
                val key = record.optString("key")
                require(key.startsWith("bt_") && key.length <= 160 && seen.add(key)) { "Invalid or duplicate storage key" }
                require(record.has("value")) { "Backup record has no value" }
                val encoded = JSONObject().put("v", record.opt("value")).getString("v")
                require(encoded.length <= 8 * 1024 * 1024) { "A backup value is too large" }
                prepared.add(key to encoded)
            }
            val db = storageDb
            db.beginTransaction()
            try {
                db.delete("kv", null, null)
                for ((key, value) in prepared) {
                    val values = android.content.ContentValues().apply {
                        put("key", key)
                        put("value", value)
                    }
                    db.insertOrThrow("kv", null, values)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            // Verify the committed snapshot inside the native layer instead of
            // making a second synchronous JavaScript-bridge read from WebView.
            val actual = HashMap<String, String>()
            db.rawQuery("SELECT key, value FROM kv", null).use { cursor ->
                while (cursor.moveToNext()) actual[cursor.getString(0)] = cursor.getString(1)
            }
            require(actual.size == prepared.size && prepared.all { (key, value) -> actual[key] == value }) {
                "بازیابی در پایگاه داده تأیید نشد"
            }
            return prepared.size
        }



        @JavascriptInterface fun runMonitorCheck(raw: String) {
            Thread {
                val result = JSONObject()
                val monitorId = try { JSONObject(raw).optString("id") } catch (_: Exception) { "" }
                result.put("id", monitorId).put("checkedAt", System.currentTimeMillis())
                val started = System.nanoTime()
                try {
                    val data = JSONObject(raw)
                    val url = data.getString("url").trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) { "آدرس باید با http یا https شروع شود" }
                    val method = data.optString("method", "GET").uppercase()
                    require(method == "GET" || method == "HEAD") { "متد مانیتورینگ پشتیبانی نمی‌شود" }
                    val builder = Request.Builder().url(url).method(method, null)
                    val headers = data.optJSONArray("headers")
                    if (headers != null) for (i in 0 until headers.length()) {
                        val header = headers.optJSONObject(i) ?: continue
                        val name = header.optString("key").trim()
                        if (name.isNotEmpty() && header.optBoolean("enabled", true)) {
                            builder.header(name, header.optString("value"))
                        }
                    }
                    val timeout = data.optLong("timeoutSeconds", 15L).coerceIn(5L, 30L)
                    client.newBuilder().connectTimeout(timeout, TimeUnit.SECONDS)
                        .readTimeout(timeout, TimeUnit.SECONDS).callTimeout(timeout, TimeUnit.SECONDS)
                        .build().newCall(builder.build()).execute().use { response ->
                            val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            val expected = data.optInt("expectedStatus", 0)
                            val healthy = if (expected > 0) response.code == expected else response.code in 200..399
                            result.put("healthy", healthy).put("status", response.code)
                                .put("duration", duration).put("finalUrl", response.request.url.toString())
                        }
                } catch (e: Exception) {
                    result.put("healthy", false).put("status", JSONObject.NULL)
                        .put("duration", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .put("error", e.message ?: e.javaClass.simpleName)
                }
                runOnUiThread {
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.BehnamTester && window.BehnamTester.onMonitorResult(" + JSONObject.quote(result.toString()) + ")", null
                    )
                }
            }.start()
        }

        @JavascriptInterface fun runLoadTest(raw: String) {
            Thread {
                val result = JSONObject()
                try {
                    val data = JSONObject(raw)
                    val url = data.getString("url").trim()
                    require(url.startsWith("http://") || url.startsWith("https://")) { "URL معتبر نیست" }
                    val method = data.optString("method", "GET").uppercase()
                    require(method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { "متد پشتیبانی نمی‌شود" }
                    val total = data.optInt("total", 30).coerceIn(1, 500)
                    val concurrency = data.optInt("concurrency", 5).coerceIn(1, 20).coerceAtMost(total)
                    val headers = data.optJSONArray("headers") ?: JSONArray()
                    val body = data.optString("body", "")
                    val contentType = data.optString("contentType", "application/json")
                    val pool = Executors.newFixedThreadPool(concurrency)
                    val latch = CountDownLatch(total)
                    val durations = ConcurrentLinkedQueue<Long>()
                    val statuses = ConcurrentLinkedQueue<Int>()
                    val errors = ConcurrentLinkedQueue<String>()
                    val started = System.nanoTime()
                    repeat(total) {
                        pool.execute {
                            val tick = System.nanoTime()
                            try {
                                val builder = Request.Builder().url(url)
                                for (i in 0 until headers.length()) {
                                    val h = headers.optJSONObject(i) ?: continue
                                    val key = h.optString("key").trim()
                                    if (key.isNotEmpty()) builder.addHeader(key, h.optString("value"))
                                }
                                val requestBody = if (method in listOf("POST", "PUT", "PATCH"))
                                    body.toRequestBody(contentType.toMediaTypeOrNull()) else null
                                val req = builder.method(method, requestBody).build()
                                client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(req).execute().use { response ->
                                    statuses.add(response.code)
                                    response.body?.close()
                                }
                            } catch (e: Exception) { errors.add(e.message ?: e.javaClass.simpleName) }
                            finally {
                                durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tick))
                                latch.countDown()
                            }
                        }
                    }
                    val completedInTime = latch.await(180, TimeUnit.SECONDS)
                    pool.shutdownNow()
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1)
                    val sorted = durations.toList().sorted()
                    val success = statuses.count { it in 200..399 }
                    val completed = durations.size
                    val networkErrors = errors.size
                    val notCompleted = (total - completed).coerceAtLeast(0)
                    val avg = if (sorted.isEmpty()) 0L else sorted.average().toLong()
                    result.put("total", total).put("completed", completed).put("success", success)
                        .put("httpErrors", statuses.size - success).put("networkErrors", networkErrors)
                        .put("notCompleted", notCompleted).put("timedOut", !completedInTime)
                        .put("avg", avg).put("min", sorted.firstOrNull() ?: 0L)
                        .put("max", sorted.lastOrNull() ?: 0L)
                        .put("p95", if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * 0.95).toInt()])
                        .put("elapsed", elapsed).put("rps", completed * 1000.0 / elapsed)
                        .put("statuses", JSONArray(statuses.toList()))
                        .put("errors", JSONArray(errors.toList().take(10)))
                } catch (e: Exception) { result.put("error", e.message ?: "Load test failed") }
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(
                    "window.BehnamTester && window.BehnamTester.onLoadTestResult(" + JSONObject.quote(result.toString()) + ")", null
                ) }
            }.start()
        }

        @JavascriptInterface fun runSecurityAudit(raw: String) {
            Thread {
                val result = JSONObject()
                try {
                    val input = JSONObject(raw)
                    val url = input.getString("url").trim()
                    val origin = input.optString("origin", "https://apix.invalid").trim()
                    val requestedMethod = input.optString("method", "PUT").uppercase()
                    val tokenA = input.optString("tokenA").trim()
                    val tokenB = input.optString("tokenB").trim()
                    val probeParam = input.optString("probeParam").trim()
                    val probeValue = input.optString("probeValue", "apix-test")
                    require(probeParam.length <= 80 && !probeParam.contains("\\r") && !probeParam.contains("\\n")) { "نام پارامتر نامعتبر است" }
                    require(probeValue.length <= 300) { "مقدار پایه بیش از حد طولانی است" }
                    require(url.startsWith("http://") || url.startsWith("https://")) { "URL معتبر نیست" }
                    require(origin.startsWith("http://") || origin.startsWith("https://")) { "Origin معتبر نیست" }
                    require(requestedMethod in setOf("POST", "PUT", "PATCH", "DELETE")) { "متد آزمایشی مجاز نیست" }
                    require(tokenA.isEmpty() == tokenB.isEmpty()) { "برای مقایسه دسترسی هر دو توکن را وارد کن" }
                    val checks = JSONArray()
                    fun check(name: String, ok: Boolean, detail: String, severity: String = "REVIEW",
                        confidence: String = if (severity == "HIGH") "متوسط" else if (severity == "MEDIUM") "پایین" else "اطلاعاتی",
                        remediation: String = "پیکربندی و رفتار endpoint را با مستندات امنیتی سرویس تطبیق بده.",
                        evidence: JSONObject? = null,
                        classification: String = if (ok) "بدون نشانه" else "نیازمند بررسی") {
                        val item = JSONObject().put("name", name).put("ok", ok).put("detail", detail)
                            .put("severity", severity).put("confidence", confidence)
                            .put("remediation", remediation).put("classification", classification)
                        if (evidence != null) item.put("evidence", evidence)
                        checks.put(item)
                    }
                    var status = 0
                    var finalUrl = url
                    client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                        .callTimeout(15, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        val headers = response.headers
                        status = response.code
                        finalUrl = response.request.url.toString()
                        val https = response.request.url.isHttps
                        check("HTTPS", https, if (https) "ارتباط نهایی با HTTPS برقرار شد" else "آدرس نهایی HTTPS نیست", "HIGH")
                        check("Strict-Transport-Security", !https || headers["Strict-Transport-Security"] != null, headers["Strict-Transport-Security"] ?: "هدر HSTS وجود ندارد")
                        check("X-Content-Type-Options", headers["X-Content-Type-Options"]?.equals("nosniff", true) == true, headers["X-Content-Type-Options"] ?: "هدر وجود ندارد")
                        check("Clickjacking protection", headers["X-Frame-Options"] != null || headers["Content-Security-Policy"]?.contains("frame-ancestors", true) == true, headers["X-Frame-Options"] ?: headers["Content-Security-Policy"] ?: "سیاست frame-ancestors یا X-Frame-Options دیده نشد")
                        check("Content-Security-Policy", headers["Content-Security-Policy"] != null, headers["Content-Security-Policy"] ?: "هدر CSP وجود ندارد")
                        check("Referrer-Policy", headers["Referrer-Policy"] != null, headers["Referrer-Policy"] ?: "هدر وجود ندارد")
                        check("Permissions-Policy", headers["Permissions-Policy"] != null, headers["Permissions-Policy"] ?: "هدر وجود ندارد")
                        val server = headers["Server"]
                        check("Server disclosure", server.isNullOrBlank(), server ?: "هدر Server افشا نشده")
                        val powered = headers["X-Powered-By"]
                        check("Technology disclosure", powered.isNullOrBlank(), powered ?: "هدر X-Powered-By افشا نشده")
                        val cookieHeaders = headers.values("Set-Cookie")
                        val cookiesSecure = cookieHeaders.all { it.contains("secure", true) }
                        val cookiesHttpOnly = cookieHeaders.all { it.contains("httponly", true) }
                        check("Cookie Secure flag", cookieHeaders.isEmpty() || cookiesSecure, if (cookieHeaders.isEmpty()) "کوکی تنظیم نشد" else if (cookiesSecure) "تمام کوکی‌های پاسخ Secure هستند" else "حداقل یک کوکی فاقد Secure است", "MEDIUM")
                        check("Cookie HttpOnly flag", cookieHeaders.isEmpty() || cookiesHttpOnly, if (cookieHeaders.isEmpty()) "کوکی تنظیم نشد" else if (cookiesHttpOnly) "تمام کوکی‌های پاسخ HttpOnly هستند" else "حداقل یک کوکی فاقد HttpOnly است", "MEDIUM")
                    }
                    val preflight = Request.Builder().url(url).method("OPTIONS", null)
                        .header("Origin", origin).header("Access-Control-Request-Method", requestedMethod)
                        .header("Access-Control-Request-Headers", "authorization,content-type").build()
                    client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                        .callTimeout(15, TimeUnit.SECONDS).build().newCall(preflight).execute().use { response ->
                        val h = response.headers
                        val allowOrigin = h["Access-Control-Allow-Origin"]
                        val originAllowed = allowOrigin == "*" || allowOrigin == origin
                        check("CORS origin policy", originAllowed, allowOrigin ?: "Access-Control-Allow-Origin در پاسخ OPTIONS وجود ندارد", "MEDIUM")
                        val allowMethods = h["Access-Control-Allow-Methods"].orEmpty()
                        val methodAllowed = allowMethods.split(",").any { it.trim().equals(requestedMethod, true) || it.trim() == "*" }
                        check("CORS requested method", methodAllowed, allowMethods.ifBlank { "Access-Control-Allow-Methods ارسال نشده" }, "MEDIUM")
                        val allowHeaders = h["Access-Control-Allow-Headers"].orEmpty()
                        val headersAllowed = allowHeaders == "*" || (allowHeaders.contains("authorization", true) && allowHeaders.contains("content-type", true))
                        check("CORS requested headers", headersAllowed, allowHeaders.ifBlank { "Access-Control-Allow-Headers ارسال نشده" }, "MEDIUM")
                        val credentials = h["Access-Control-Allow-Credentials"]?.equals("true", true) == true
                        check("CORS wildcard with credentials", !(allowOrigin == "*" && credentials), if (allowOrigin == "*" && credentials) "استفاده از wildcard همراه credentials معتبر نیست" else "ترکیب wildcard و credentials مشاهده نشد", "HIGH")
                        val allowed = (h["Allow"] ?: allowMethods).split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
                        check("Dangerous HTTP methods", "TRACE" !in allowed && "TRACK" !in allowed, if (allowed.isEmpty()) "فهرست متدها از پاسخ OPTIONS قابل استخراج نبود" else "متدهای اعلام‌شده: " + allowed.joinToString(", "), "HIGH")
                        check("OPTIONS response", response.code in 200..299 || response.code == 204, "کد پاسخ OPTIONS: ${response.code}", "LOW")
                    }
                    if (tokenA.isNotEmpty() && tokenB.isNotEmpty()) {
                        fun fetchWithToken(token: String): Pair<Int, String> {
                            val req = Request.Builder().url(url).get().header("Authorization", "Bearer " + token).build()
                            client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                                .callTimeout(15, TimeUnit.SECONDS).build().newCall(req).execute().use { response ->
                                val source = response.body?.source()
                                val buffer = okio.Buffer()
                                if (source != null) source.read(buffer, 256 * 1024L)
                                return response.code to buffer.readUtf8()
                            }
                        }
                        val anonymous = fetchWithToken("")
                        val a = fetchWithToken(tokenA)
                        val b = fetchWithToken(tokenB)
                        val anonymousExposed = anonymous.first in 200..299 && a.first in 200..299 && anonymous.second == a.second
                        check("Unauthenticated resource access", !anonymousExposed,
                            if (anonymousExposed) "درخواست بدون توکن همان پاسخ موفق کاربر A را گرفت؛ احتمال نبود کنترل احراز هویت" else "پاسخ بدون توکن: ${anonymous.first}، پاسخ کاربر A: ${a.first}", "HIGH")
                        val bDenied = b.first == 401 || b.first == 403
                        val sameSuccessfulBody = a.first in 200..299 && b.first in 200..299 && a.second == b.second
                        val bolaEvidence = JSONObject().put("anonymousStatus", anonymous.first)
                            .put("accountAStatus", a.first).put("accountBStatus", b.first)
                            .put("successfulBodiesIdentical", sameSuccessfulBody)
                        check("Cross-account access (BOLA/IDOR signal)", bDenied || !sameSuccessfulBody,
                            when {
                                bDenied -> "کاربر B با پاسخ ${b.first} رد شد؛ نشانه مثبت کنترل دسترسی"
                                sameSuccessfulBody -> "هر دو حساب پاسخ موفق و بدنه کاملاً یکسان گرفتند؛ احتمال دسترسی بین‌حسابی را دستی بررسی کن"
                                b.first in 200..299 -> "کاربر B نیز پاسخ موفق گرفت؛ محتوای دو حساب متفاوت است، مالکیت داده را دستی بررسی کن"
                                else -> "درخواست A: ${a.first}، درخواست B: ${b.first}"
                            }, "HIGH", "پایین",
                            "در سمت سرور مالکیت هر شیء را برای هر درخواست بررسی کن؛ صرفاً به شناسه ارسالی یا احراز هویت اکتفا نکن.",
                            bolaEvidence, if (sameSuccessfulBody) "نشانه احتمالی؛ تأیید مالکیت لازم است" else "با این مقایسه نشانه‌ای مشاهده نشد")
                    } else {
                        check("Cross-account access (BOLA/IDOR signal)", true, "برای این آزمون دو توکن مجزا وارد نشده است؛ بررسی انجام نشد", "INFO")
                    }
                    if (probeParam.isNotBlank()) {
                        val baseUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("URL معتبر نیست")
                        require(baseUrl.queryParameterNames.size < 100) { "تعداد پارامترهای URL بیش از حد است" }
                        fun probe(value: String): Pair<Int, String> {
                            val target = baseUrl.newBuilder().setQueryParameter(probeParam, value).build()
                            val request = Request.Builder().url(target).get().build()
                            client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                                .callTimeout(12, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                                val source = response.body?.source()
                                val buffer = okio.Buffer()
                                if (source != null) source.read(buffer, 96 * 1024L)
                                return response.code to buffer.readUtf8()
                            }
                        }
                        val baseline = probe(probeValue)
                        val singleQuote = probe(probeValue + "'")
                        val doubleQuote = probe(probeValue + "\"")
                        val sqlErrors = listOf("sql syntax", "sqlite_exception", "sqlstate", "unterminated quoted", "mysql_fetch", "postgresql", "odbc", "ora-")
                        val quoteError = (singleQuote.second + " " + doubleQuote.second).lowercase()
                        val sqlSignal = sqlErrors.any { quoteError.contains(it) } || (singleQuote.first in 500..599 && baseline.first !in 500..599)
                        val sqlEvidence = JSONObject().put("parameter", probeParam)
                            .put("baselineStatus", baseline.first).put("singleQuoteStatus", singleQuote.first)
                            .put("doubleQuoteStatus", doubleQuote.first)
                            .put("databaseErrorSignature", sqlErrors.firstOrNull { quoteError.contains(it) } ?: "")
                            .put("responseExcerpt", (singleQuote.second + "\\n" + doubleQuote.second).take(1200))
                        check("SQL injection (error signal)", !sqlSignal,
                            if (sqlSignal) "پس از افزودن کوتیشن، نشانه خطای پایگاه داده یا تغییر غیرعادی کد پاسخ دیده شد؛ exploit تأیید نشده است" else "با probe کوتیشن، نشانه واضح خطای SQL مشاهده نشد؛ نبود SQLi ثابت نمی‌شود",
                            if (sqlSignal) "HIGH" else "INFO", if (sqlSignal) "پایین" else "اطلاعاتی",
                            "از query پارامتری و prepared statement استفاده کن؛ جزئیات خطای پایگاه داده را به پاسخ عمومی ارسال نکن.",
                            sqlEvidence, if (sqlSignal) "نشانه احتمالی؛ تأیید دستی لازم است" else "نشانه‌ای مشاهده نشد")
                        val marker = "apixxssprobe" + System.currentTimeMillis().toString(36)
                        val inertMarker = "<apix-probe>$marker</apix-probe>"
                        val reflected = probe(probeValue + inertMarker)
                        val isReflected = reflected.second.contains(marker, true)
                        val xssEvidence = JSONObject().put("parameter", probeParam).put("status", reflected.first)
                            .put("marker", marker).put("markerReflected", isReflected)
                            .put("responseExcerpt", reflected.second.take(1200))
                        check("Reflected input (XSS indicator)", !isReflected,
                            if (isReflected) "marker بی‌خطر ورودی در پاسخ بازتاب شد؛ اجرای کد بررسی نشده و XSS تأیید نشده است" else "marker آزمایشی عیناً در پاسخ دیده نشد",
                            if (isReflected) "MEDIUM" else "INFO", if (isReflected) "پایین" else "اطلاعاتی",
                            "ورودی را متناسب با context خروجی HTML، attribute، JavaScript یا URL کدگذاری کن؛ برای داده متنی از textContent استفاده کن.",
                            xssEvidence, if (isReflected) "بازتاب ورودی؛ نیازمند بررسی context و encoding" else "نشانه‌ای مشاهده نشد")
                    } else {
                        check("SQL injection (error signal)", true, "نام پارامتر Query تعیین نشده؛ آزمون اجرا نشد", "INFO")
                        check("Reflected input (XSS indicator)", true, "نام پارامتر Query تعیین نشده؛ آزمون اجرا نشد", "INFO")
                    }
                    result.put("status", status).put("finalUrl", finalUrl).put("origin", origin).put("method", requestedMethod)
                        .put("checks", checks)
                        .put("passed", (0 until checks.length()).count { checks.getJSONObject(it).optBoolean("ok") })
                        .put("failed", (0 until checks.length()).count { !checks.getJSONObject(it).optBoolean("ok") })
                } catch (e: Exception) { result.put("error", e.message ?: "Security assessment failed") }
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(
                    "window.BehnamTester && window.BehnamTester.onSecurityAuditResult(" + JSONObject.quote(result.toString()) + ")", null
                ) }
            }.start()
        }

        @JavascriptInterface fun startMockServer(raw: String): String {
            return try {
                val routes = JSONArray(raw)
                require(routes.length() in 1..100) { "تعداد مسیرها باید بین ۱ تا ۱۰۰ باشد" }
                stopMockServer()
                mockRoutes = routes
                val socket = ServerSocket(8787, 32, InetAddress.getByName("127.0.0.1"))
                mockServer = socket
                Thread {
                    while (!socket.isClosed) {
                        try {
                            val clientSocket = socket.accept()
                            Thread { serveMockRequest(clientSocket) }.start()
                        } catch (_: Exception) { if (!socket.isClosed) continue }
                    }
                }.start()
                JSONObject().put("ok", true).put("url", "http://127.0.0.1:8787").toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "خطا در راه‌اندازی Mock Server").toString()
            }
        }

        @JavascriptInterface fun stopMockServer() {
            try { mockServer?.close() } catch (_: Exception) { }
            mockServer = null
        }

        @JavascriptInterface fun mockServerStatus(): Boolean = mockServer?.isBound == true && mockServer?.isClosed == false

        private fun serveMockRequest(socket: Socket) {
            socket.use { s ->
                try {
                    s.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val first = reader.readLine() ?: return
                    val parts = first.split(" ")
                    val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                    val path = parts.getOrNull(1)?.substringBefore("?") ?: "/"
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    val body = StringBuilder()
                    repeat(length.coerceIn(0, 1024 * 1024)) {
                        val ch = reader.read()
                        if (ch >= 0) body.append(ch.toChar())
                    }
                    var status = 404
                    var contentType = "application/json; charset=utf-8"
                    var responseBody = JSONObject().put("error", "Mock route not found").put("path", path).toString()
                    var extraHeaders = ""
                    if (path == "/mock/echo" && method == "POST") {
                        status = 200
                        responseBody = JSONObject().put("method", method).put("path", path).put("body", body.toString()).toString()
                    } else {
                        for (i in 0 until mockRoutes.length()) {
                            val route = mockRoutes.optJSONObject(i) ?: continue
                            if (route.optString("method", "GET").uppercase() == method && route.optString("path") == path) {
                                status = route.optInt("status", 200).coerceIn(100, 599)
                                contentType = route.optString("contentType", contentType)
                                responseBody = route.optString("body", "{}")
                                val headers = route.optJSONObject("headers")
                                if (headers != null) for (key in headers.keys()) {
                                    val safeKey = key.replace("\r", "").replace("\n", "")
                                    val safeValue = headers.optString(key).replace("\r", "").replace("\n", "")
                                    extraHeaders += "$safeKey: $safeValue\r\n"
                                }
                                break
                            }
                        }
                    }
                    val bytes = responseBody.toByteArray(Charsets.UTF_8)
                    val reason = when (status) { 200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Mock" }
                    val output = s.getOutputStream()
                    output.write(("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n$extraHeaders\r\n").toByteArray(Charsets.UTF_8))
                    output.write(bytes); output.flush()
                } catch (_: Exception) { }
            }
        }

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
                    val isCorsPreflight = data.optBoolean("corsPreflight", false)
                    val requestClient = client.newBuilder()
                        .callTimeout(data.optLong("timeout", 60L).coerceIn(5L, 300L), TimeUnit.SECONDS)
                        .apply {
                            if (isCorsPreflight) {
                                cookieJar(CookieJar.NO_COOKIES)
                                followRedirects(false)
                                followSslRedirects(false)
                                retryOnConnectionFailure(false)
                            }
                        }
                        .build()
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
                        val displayBody = if (truncated) responseBody + "\n\n[Response truncated at 5 MB]" else responseBody
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

        @JavascriptInterface fun wsConnect(raw: String) {
            try {
                val data = JSONObject(raw)
                val id = data.optString("id").take(80)
                require(id.isNotBlank()) { "Connection id is required" }
                val url = data.getString("url").trim()
                val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid WebSocket URL")
                require(parsed.scheme == "ws" || parsed.scheme == "wss") { "Only ws:// and wss:// are supported" }
                val requestBuilder = Request.Builder().url(url)
                val headers = data.optJSONArray("headers") ?: JSONArray()
                require(headers.length() <= 40) { "Maximum 40 headers allowed" }
                val blocked = setOf("host", "connection", "upgrade", "content-length", "transfer-encoding")
                for (i in 0 until headers.length()) {
                    val item = headers.optJSONObject(i) ?: continue
                    val name = item.optString("key").trim()
                    val value = item.optString("value")
                    if (name.isBlank()) continue
                    require(name.length <= 200 && value.length <= 8192) { "Header is too long" }
                    require(name.lowercase() !in blocked && !name.startsWith("sec-websocket-", true)) { "Header is managed by WebSocket protocol: $name" }
                    requestBuilder.addHeader(name, value)
                }
                val pingSeconds = data.optInt("pingInterval", 25).coerceIn(0, 120)
                webSockets.remove(id)?.close(1000, "Replaced")
                val wsClient = client.newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .connectTimeout(25, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(25, TimeUnit.SECONDS)
                    .pingInterval(pingSeconds.toLong(), TimeUnit.SECONDS)
                    .build()
                val terminal = AtomicBoolean(false)
                val socket = wsClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                        deliverWebSocket(JSONObject().put("id", id).put("event", "open").put("protocol", response.header("Sec-WebSocket-Protocol", "")))
                    }
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        val maxChars = 32 * 1024
                        val clipped = text.length > maxChars
                        deliverWebSocket(JSONObject().put("id", id).put("event", "message")
                            .put("message", if (clipped) text.take(maxChars) else text)
                            .put("truncated", clipped).put("originalChars", text.length))
                    }
                    override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                        deliverWebSocket(JSONObject().put("id", id).put("event", "message").put("message", "[Binary message · ${bytes.size} bytes]").put("binary", true))
                    }
                    override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        deliverWebSocket(JSONObject().put("id", id).put("event", "closing").put("code", code).put("reason", reason))
                    }
                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        terminal.set(true)
                        webSockets.remove(id, webSocket)
                        deliverWebSocket(JSONObject().put("id", id).put("event", "closed").put("code", code).put("reason", reason))
                    }
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                        terminal.set(true)
                        webSockets.remove(id, webSocket)
                        val responseBody = try { response?.peekBody(4096L)?.string()?.take(2000) ?: "" } catch (_: Exception) { "" }
                        val causes = generateSequence(t as Throwable?) { it.cause }.take(5).joinToString(" <- ") { cause -> cause.javaClass.simpleName + (cause.message?.let { ": $it" } ?: "") }
                        deliverWebSocket(JSONObject()
                            .put("id", id).put("event", "failure")
                            .put("error", t.message ?: t.javaClass.simpleName)
                            .put("cause", causes)
                            .put("httpCode", response?.code ?: 0)
                            .put("httpMessage", response?.message ?: "")
                            .put("responseBody", responseBody)
                            .put("url", parsed.toString()))
                    }
                })
                // A very fast handshake failure can arrive before newWebSocket() returns.
                // Track terminal callbacks so a dead socket is never reinserted afterward.
                if (!terminal.get()) {
                    webSockets[id] = socket
                    if (terminal.get()) webSockets.remove(id, socket)
                }
            } catch (e: Exception) {
                val id = try { JSONObject(raw).optString("id") } catch (_: Exception) { "" }
                deliverWebSocket(JSONObject().put("id", id).put("event", "failure").put("error", e.message ?: "WebSocket connection failed"))
            }
        }

        @JavascriptInterface fun wsSend(id: String, message: String): Boolean {
            if (message.length > 1024 * 1024) return false
            return webSockets[id]?.send(message) == true
        }

        @JavascriptInterface fun wsClose(id: String, code: Int, reason: String) {
            val safeCode = if (code in 1000..4999 && code !in listOf(1004, 1005, 1006, 1015)) code else 1000
            webSockets.remove(id)?.close(safeCode, reason.take(120))
        }

        private fun deliverWebSocket(event: JSONObject) {
            runOnUiThread {
                if (::web.isInitialized) web.evaluateJavascript(
                    "window.BehnamTester && window.BehnamTester.onWebSocketEvent(" + JSONObject.quote(event.toString()) + ")", null
                )
            }
        }

        @JavascriptInterface fun saveBackupFile(raw: String, fileName: String) {
            require(raw.length <= 32 * 1024 * 1024) { "Backup file is too large" }
            runOnUiThread {
                try {
                    pendingBackupJson = raw
                    pendingBackupName = fileName.substringAfterLast('/').take(120).ifBlank { "apix-backup.json" }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, pendingBackupName)
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                android.provider.DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents", "primary:Download"
                                ))
                        }
                    }
                    startActivityForResult(intent, backupSaveRequestCode)
                } catch (e: Exception) {
                    pendingBackupJson = null
                    Toast.makeText(this@MainActivity, "باز کردن محل ذخیره بکاپ ممکن نیست: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface fun chooseBackupFile() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json", "application/octet-stream"))
                    }
                    startActivityForResult(intent, backupRestoreRequestCode)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "باز کردن انتخاب‌گر فایل ممکن نیست: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface fun createSqliteBackup() {
            Thread {
                var backup: java.io.File? = null
                try {
                    backup = java.io.File(cacheDir, "apix-full-backup-${System.currentTimeMillis()}.db")
                    if (backup.exists()) backup.delete()
                    val outputDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(backup, null)
                    try {
                        outputDb.execSQL("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                        outputDb.execSQL("CREATE TABLE IF NOT EXISTS app_data (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                        outputDb.execSQL("CREATE TABLE IF NOT EXISTS apix_backup_meta (format TEXT NOT NULL, version INTEGER NOT NULL, created_at TEXT NOT NULL)")
                        outputDb.beginTransaction()
                        try {
                            storageDb.rawQuery("SELECT key, value FROM kv ORDER BY key", null).use { cursor ->
                                while (cursor.moveToNext()) {
                                    val values = android.content.ContentValues().apply {
                                        put("key", cursor.getString(0))
                                        put("value", cursor.getString(1))
                                    }
                                    outputDb.insertOrThrow("kv", null, values)
                                }
                            }
                            val appData = android.content.ContentValues().apply { put("key", "cookies"); put("value", cookieJar.exportJson().toString()) }
                            outputDb.insertOrThrow("app_data", null, appData)
                            val meta = android.content.ContentValues().apply {
                                put("format", "apix-sqlite-backup")
                                put("version", 1)
                                put("created_at", System.currentTimeMillis().toString())
                            }
                            outputDb.insertOrThrow("apix_backup_meta", null, meta)
                            outputDb.setTransactionSuccessful()
                        } finally { outputDb.endTransaction() }
                    } finally { outputDb.close() }
                    val completedBackup = backup ?: throw IllegalStateException("SQLite backup file was not created")
                    runOnUiThread {
                        try {
                            pendingSqliteBackupFile = completedBackup
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/vnd.sqlite3"
                                putExtra(Intent.EXTRA_TITLE, completedBackup.name)
                            }
                            startActivityForResult(intent, sqliteBackupSaveRequestCode)
                        } catch (e: Exception) {
                            pendingSqliteBackupFile = null
                            completedBackup.delete()
                            Toast.makeText(this@MainActivity, "باز کردن محل ذخیره بکاپ SQLite ممکن نیست: " + e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    backup?.delete()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "ساخت بکاپ SQLite ناموفق بود: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        if (::web.isInitialized) web.evaluateJavascript("window.onBackupSaved && window.onBackupSaved(" + JSONObject.quote(JSONObject().put("ok", false).toString()) + ")", null)
                    }
                }
            }.start()
        }

        @JavascriptInterface fun chooseSqliteBackupFile() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream", "application/json", "text/json"))
                    }
                    startActivityForResult(intent, sqliteBackupRestoreRequestCode)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "انتخاب فایل SQLite ممکن نیست: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface fun restoreSqliteBackup(): String {
            return try {
                val file = pendingSqliteRestoreFile ?: throw IllegalStateException("ابتدا فایل بکاپ را انتخاب کن")
                val source = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                val records = JSONArray()
                var folderCount = 0
                var collectionCount = 0
                var cookiesJson = "[]"
                try {
                    source.rawQuery("SELECT format, version FROM apix_backup_meta LIMIT 1", null).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(0) == "apix-sqlite-backup" && cursor.getInt(1) == 1) { "فرمت یا نسخه فایل SQLite پشتیبانی نمی‌شود" }
                    }
                    source.rawQuery("SELECT key, value FROM kv ORDER BY key", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0)
                            val encoded = cursor.getString(1)
                            require(key.startsWith("bt_") && key.length <= 160) { "کلید نامعتبر در فایل بکاپ" }
                            require(encoded.length <= 8 * 1024 * 1024) { "یکی از رکوردهای بکاپ بیش از حد بزرگ است" }
                            val value = org.json.JSONTokener(encoded).nextValue()
                            records.put(JSONObject().put("key", key).put("value", value))
                            if (key == "bt_folders" && value is JSONArray) folderCount = value.length()
                            if (key == "bt_collections" && value is JSONArray) collectionCount = value.length()
                            require(records.length() <= 5000) { "تعداد رکوردهای بکاپ بیش از حد مجاز است" }
                        }
                    }
                    source.rawQuery("SELECT value FROM app_data WHERE key='cookies' LIMIT 1", null).use { cursor ->
                        if (cursor.moveToFirst()) cookiesJson = cursor.getString(0)
                    }
                    org.json.JSONArray(cookiesJson)
                } finally { source.close() }
                val count = replaceStorageSnapshot(records.toString())
                cookieJar.importJson(cookiesJson)
                pendingSqliteRestoreFile = null
                file.delete()
                JSONObject().put("ok", true).put("count", count).put("folders", folderCount)
                    .put("collections", collectionCount).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
            }
        }
        @JavascriptInterface fun chooseJsonFile(importType: String) {
            runOnUiThread {
                try {
                    require(importType == "collections" || importType == "environments") { "نوع فایل نامعتبر است" }
                    pendingJsonImportType = importType
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json", "application/octet-stream"))
                    }
                    startActivityForResult(intent, jsonImportRequestCode)
                } catch (e: Exception) {
                    pendingJsonImportType = ""
                    Toast.makeText(this@MainActivity, "انتخاب فایل ممکن نیست: " + e.message, Toast.LENGTH_LONG).show()
                }
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
                            .put("body", if (truncated) body + "\n\n[Response truncated at 5 MB]" else body)
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

        @JavascriptInterface fun getAppVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface fun openPrivacyPolicy() {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Behnamghafari/apk/blob/main/apps/behnam-tester/PRIVACY.md")))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "مرورگر برای نمایش سیاست حریم خصوصی در دسترس نیست", Toast.LENGTH_SHORT).show()
                }
            }
        }

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
        if (requestCode == backupSaveRequestCode) {
            val payload = pendingBackupJson
            pendingBackupJson = null
            if (resultCode == RESULT_OK && data?.data != null && payload != null) {
                try {
                    val uri = data.data!!
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("Cannot open destination")
                    val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else pendingBackupName
                    } ?: pendingBackupName
                    val documentPath = try { android.provider.DocumentsContract.getDocumentId(uri) } catch (_: Exception) { uri.toString() }
                    val result = JSONObject().put("ok", true).put("name", name).put("path", documentPath).put("uri", uri.toString())
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.onBackupSaved && window.onBackupSaved(" + JSONObject.quote(result.toString()) + ")", null
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "ذخیره بکاپ ناموفق بود: " + e.message, Toast.LENGTH_LONG).show()
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.onBackupSaved && window.onBackupSaved(" + JSONObject.quote(JSONObject().put("ok", false).toString()) + ")", null
                    )
                }
            } else {
                Toast.makeText(this, "ذخیره بکاپ لغو شد", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == sqliteBackupSaveRequestCode) {
            val file = pendingSqliteBackupFile
            pendingSqliteBackupFile = null
            if (resultCode == RESULT_OK && data?.data != null && file != null) {
                try {
                    contentResolver.openOutputStream(data.data!!, "wt")?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
                        ?: throw IllegalStateException("Cannot open SQLite backup destination")
                    val result = JSONObject().put("ok", true).put("name", file.name).put("path", data.data.toString())
                    if (::web.isInitialized) web.evaluateJavascript("window.onBackupSaved && window.onBackupSaved(" + JSONObject.quote(result.toString()) + ")", null)
                } catch (e: Exception) {
                    Toast.makeText(this, "ذخیره فایل SQLite ناموفق بود: " + e.message, Toast.LENGTH_LONG).show()
                    if (::web.isInitialized) web.evaluateJavascript("window.onBackupSaved && window.onBackupSaved(" + JSONObject.quote(JSONObject().put("ok", false).toString()) + ")", null)
                } finally { file.delete() }
            } else { file?.delete() }
            return
        }
        if (requestCode == sqliteBackupRestoreRequestCode) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val file = java.io.File(cacheDir, "apix-restore-${System.currentTimeMillis()}.db")
                    contentResolver.openInputStream(data.data!!)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                        ?: throw IllegalStateException("Cannot read SQLite backup file")
                    require(file.length() in 1..(128L * 1024L * 1024L)) { "Backup file size is invalid or exceeds 128 MB" }
                    val prefix = file.inputStream().use { input -> ByteArray(64).also { input.read(it) } }
                    val prefixText = String(prefix, Charsets.UTF_8).trimStart()
                    if (prefixText.startsWith("{")) {
                        require(file.length() <= 32L * 1024L * 1024L) { "Legacy JSON backup exceeds 32 MB" }
                        val legacyJson = file.readText(Charsets.UTF_8)
                        file.delete()
                        if (::web.isInitialized) web.evaluateJavascript("window.onBackupFileSelected && window.onBackupFileSelected(" + JSONObject.quote(legacyJson) + ")", null)
                        return
                    }
                    val source = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                    var count = 0
                    var folders = 0
                    var collections = 0
                    try {
                        source.rawQuery("PRAGMA quick_check", null).use { cursor ->
                            require(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) { "ساختار فایل SQLite سالم نیست" }
                        }
                        source.rawQuery("SELECT format, version FROM apix_backup_meta LIMIT 1", null).use { cursor ->
                            require(cursor.moveToFirst() && cursor.getString(0) == "apix-sqlite-backup" && cursor.getInt(1) == 1) { "این فایل بکاپ SQLite معتبر ApiX نیست" }
                        }
                        source.rawQuery("SELECT key, value FROM kv", null).use { cursor ->
                            while (cursor.moveToNext()) {
                                val key = cursor.getString(0)
                                require(key.startsWith("bt_") && key.length <= 160) { "کلید نامعتبر در فایل بکاپ" }
                                val value = org.json.JSONTokener(cursor.getString(1)).nextValue()
                                if (key == "bt_folders" && value is JSONArray) folders = value.length()
                                if (key == "bt_collections" && value is JSONArray) collections = value.length()
                                count++
                                require(count <= 5000) { "تعداد رکوردهای بکاپ بیش از حد مجاز است" }
                            }
                        }
                    } finally { source.close() }
                    pendingSqliteRestoreFile?.delete()
                    pendingSqliteRestoreFile = file
                    val summary = JSONObject().put("count", count).put("folders", folders).put("collections", collections)
                    if (::web.isInitialized) web.evaluateJavascript("window.onSqliteBackupSelected && window.onSqliteBackupSelected(" + JSONObject.quote(summary.toString()) + ")", null)
                } catch (e: Exception) {
                    Toast.makeText(this, "فایل SQLite قابل بازیابی نیست: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (requestCode == backupRestoreRequestCode) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val uri = data.data!!
                    val raw = contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= 32 * 1024 * 1024) { "Backup file is larger than 32 MB" }
                        String(bytes, Charsets.UTF_8)
                    } ?: throw IllegalStateException("Cannot read backup file")
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.onBackupFileSelected && window.onBackupFileSelected(" + JSONObject.quote(raw) + ")", null
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "خواندن فایل بکاپ ناموفق بود: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (requestCode == jsonImportRequestCode) {
            val importType = pendingJsonImportType
            pendingJsonImportType = ""
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val uri = data.data!!
                    val raw = contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= 10 * 1024 * 1024) { "JSON file is larger than 10 MB" }
                        String(bytes, Charsets.UTF_8)
                    } ?: throw IllegalStateException("Cannot read selected JSON file")
                    if (::web.isInitialized) web.evaluateJavascript(
                        "window.onJsonFileSelected && window.onJsonFileSelected(" +
                            JSONObject.quote(importType) + "," + JSONObject.quote(raw) + ")", null
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "خواندن فایل ناموفق بود: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
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
        try { mockServer?.close() } catch (_: Exception) { }
        webSockets.values.forEach { try { it.close(1001, "App destroyed") } catch (_: Exception) { } }
        webSockets.clear()
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

    fun importJson(raw: String) {
        val incoming = JSONArray(raw)
        synchronized(lock) {
            cookies.clear()
            for (i in 0 until incoming.length()) {
                val item = incoming.optJSONObject(i) ?: continue
                try {
                    val builder = Cookie.Builder().name(item.getString("name")).value(item.getString("value"))
                        .path(item.optString("path", "/")).expiresAt(item.optLong("expiresAt", Long.MAX_VALUE))
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
