package com.behnamghafari.tester

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.core.view.WindowCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val client = OkHttpClient.Builder()
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
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            addJavascriptInterface(Bridge(), "BehnamNative")
            webViewClient = WebViewClient()
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
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        val contentType = response.header("Content-Type") ?: ""
                        val isText = contentType.contains("json", true) || contentType.startsWith("text/", true) ||
                            contentType.contains("xml", true) || contentType.contains("javascript", true) ||
                            contentType.contains("x-www-form-urlencoded", true) || contentType.isBlank()
                        val responseBody = if (isText) String(bytes, Charsets.UTF_8) else "[Binary response: ${bytes.size} bytes]"
                        val result = JSONObject()
                            .put("id", id).put("ok", response.isSuccessful).put("status", response.code)
                            .put("statusText", response.message).put("duration", System.currentTimeMillis() - start)
                            .put("size", bytes.size).put("headers", responseHeaders)
                            .put("body", responseBody).put("contentType", contentType)
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
        @JavascriptInterface fun toast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }
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
