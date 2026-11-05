package ir.behnamghafari.apixnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DarkColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val ApiXColors: DarkColorScheme = darkColorScheme()

class MainActivity : ComponentActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ApiXColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ApiXScreen { method, url, headers, body, callback ->
                        executeRequest(method, url, headers, body, callback)
                    }
                }
            }
        }
    }

    private fun executeRequest(
        method: String,
        rawUrl: String,
        rawHeaders: String,
        body: String,
        callback: (String) -> Unit
    ) {
        networkExecutor.execute {
            val startedAt = System.nanoTime()
            val output = try {
                val url = rawUrl.trim()
                require(url.startsWith("https://") || url.startsWith("http://")) {
                    "آدرس باید با http:// یا https:// شروع شود."
                }

                val builder = Request.Builder().url(url)
                rawHeaders.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        val separator = line.indexOf(':')
                        require(separator > 0) { "فرمت هدر معتبر نیست: $line" }
                        builder.addHeader(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
                    }

                val requestBody = if (method in setOf("POST", "PUT", "PATCH")) {
                    body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                } else {
                    null
                }

                httpClient.newCall(builder.method(method, requestBody).build()).execute().use { response ->
                    val buffer = Buffer()
                    val source = response.body?.source()
                    val maxBytes = 2L * 1024L * 1024L
                    while (source != null && buffer.size <= maxBytes) {
                        if (source.read(buffer, 8192L) == -1L) break
                    }
                    val truncated = buffer.size > maxBytes
                    val bytes = buffer.readByteArray(minOf(buffer.size, maxBytes))
                    val responseText = bytes.toString(Charsets.UTF_8)
                    val headersText = response.headers.names().sorted().joinToString("\n") { name ->
                        "$name: ${response.headers.values(name).joinToString(", ")}"
                    }
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    buildString {
                        append("HTTP ${response.code} ${response.message}\n")
                        append("زمان پاسخ: ${elapsed} ms\n")
                        append("آدرس نهایی: ${response.request.url}\n\n")
                        append("HEADERS\n")
                        append(headersText)
                        append("\n\nBODY\n")
                        append(responseText)
                        if (truncated) append("\n\n[پاسخ در محدودیت ۲ مگابایت بریده شد]")
                    }
                }
            } catch (error: Exception) {
                "خطا: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread { callback(output) }
        }
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun ApiXScreen(
    onSend: (String, String, String, String, (String) -> Unit) -> Unit
) {
    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    var method by remember { mutableStateOf("GET") }
    var methodMenuOpen by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://httpbin.org/get") }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("{\n  \"hello\": \"ApiX Native\"\n}") }
    var response by remember { mutableStateOf("پاسخ درخواست اینجا نمایش داده می‌شود.") }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ApiX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("نسخه کاملاً نیتیو · Kotlin + Jetpack Compose + OkHttp", style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { methodMenuOpen = true }) { Text(method) }
                DropdownMenu(expanded = methodMenuOpen, onDismissRequest = { methodMenuOpen = false }) {
                    methods.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                method = option
                                methodMenuOpen = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("آدرس API") }
            )
        }

        OutlinedTextField(
            value = headers,
            onValueChange = { headers = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Headers · هر هدر در یک خط، مثل Authorization: Bearer token") }
        )

        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("Body · JSON") }
        )

        Button(
            onClick = {
                loading = true
                response = "در حال ارسال درخواست..."
                onSend(method, url, headers, body) {
                    response = it
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("ارسال درخواست")
        }

        Text("پاسخ سرور", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                response,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
