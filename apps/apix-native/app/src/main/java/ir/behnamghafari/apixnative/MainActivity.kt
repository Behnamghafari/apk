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
import androidx.compose.material3.ColorScheme
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
import ir.behnamghafari.apixnative.data.network.HttpKeyValue
import ir.behnamghafari.apixnative.data.network.HttpRequestEngine
import ir.behnamghafari.apixnative.data.network.HttpRequestSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val ApiXColors: ColorScheme = darkColorScheme()

class MainActivity : ComponentActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val requestEngine by lazy { HttpRequestEngine() }

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
            val output = try {
                val parsedHeaders = rawHeaders.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { line ->
                        val separator = line.indexOf(':')
                        require(separator > 0) { "فرمت هدر معتبر نیست: $line" }
                        HttpKeyValue(
                            key = line.substring(0, separator).trim(),
                            value = line.substring(separator + 1).trim()
                        )
                    }.toList()
                requestEngine.execute(
                    HttpRequestSpec(
                        method = method,
                        url = rawUrl,
                        headers = parsedHeaders,
                        body = body,
                        bodyType = if (method in setOf("POST", "PUT", "PATCH")) "json" else "none"
                    )
                ).asDisplayText()
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
