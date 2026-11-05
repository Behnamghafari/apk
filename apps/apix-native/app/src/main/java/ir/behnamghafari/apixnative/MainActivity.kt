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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.behnamghafari.apixnative.data.model.SavedRequest
import ir.behnamghafari.apixnative.data.network.HttpKeyValue
import ir.behnamghafari.apixnative.data.network.HttpRequestEngine
import ir.behnamghafari.apixnative.data.network.HttpRequestSpec
import ir.behnamghafari.apixnative.data.network.HttpResponseSnapshot
import ir.behnamghafari.apixnative.data.repository.ApiXWorkspaceRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

private val ApiXColors: ColorScheme = darkColorScheme()

class MainActivity : ComponentActivity() {
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val requestEngine by lazy { HttpRequestEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ApiXColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ApiXScreen { spec, callback ->
                        networkExecutor.execute {
                            val result = runCatching { requestEngine.execute(spec) }
                            runOnUiThread { callback(result) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun ApiXScreen(
    onSend: (HttpRequestSpec, (Result<HttpResponseSnapshot>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { ApiXWorkspaceRepository(context) }
    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    val authTypes = listOf("none", "bearer", "basic", "apikey")
    val bodyTypes = listOf("none", "json", "raw", "form")

    var method by remember { mutableStateOf("GET") }
    var methodMenuOpen by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://httpbin.org/get") }
    var params by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("{\n  \"hello\": \"ApiX Native\"\n}") }
    var bodyType by remember { mutableStateOf("json") }
    var authType by remember { mutableStateOf("none") }
    var authToken by remember { mutableStateOf("") }
    var authUsername by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var apiKeyName by remember { mutableStateOf("") }
    var apiKeyValue by remember { mutableStateOf("") }
    var apiKeyIn by remember { mutableStateOf("header") }
    var requestName by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("پاسخ درخواست اینجا نمایش داده می‌شود.") }
    var loading by remember { mutableStateOf(false) }
    var savedRequests by remember { mutableStateOf(repository.savedRequests()) }
    var history by remember { mutableStateOf(repository.history(8)) }
    var folders by remember { mutableStateOf(repository.folders()) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }

    fun keyValues(source: String, separator: Char): List<HttpKeyValue> =
        source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val index = line.indexOf(separator)
            require(index > 0) { "فرمت مقدار نامعتبر است: $line" }
            HttpKeyValue(line.substring(0, index).trim(), line.substring(index + 1).trim())
        }.toList()

    fun restoreRequest(saved: SavedRequest) {
        method = saved.method
        url = saved.url
        headers = JSONArray(saved.headersJson).let { array ->
            (0 until array.length()).joinToString("\n") { i ->
                val item = array.getJSONObject(i)
                "${item.optString("key")}:${item.optString("value")}"
            }
        }
        params = JSONArray(saved.paramsJson).let { array ->
            (0 until array.length()).joinToString("\n") { i ->
                val item = array.getJSONObject(i)
                "${item.optString("key")}=${item.optString("value")}"
            }
        }
        body = saved.body
        bodyType = saved.bodyType
        authType = saved.authType
        val auth = JSONObject(saved.authConfigJson)
        authToken = auth.optString("token")
        authUsername = auth.optString("username")
        authPassword = auth.optString("password")
        apiKeyName = auth.optString("apiKeyName")
        apiKeyValue = auth.optString("apiKeyValue")
        apiKeyIn = auth.optString("apiKeyIn", "header")
        requestName = saved.name
        response = "درخواست «${saved.name}» بارگذاری شد."
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ApiX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Native API Client · Kotlin + Compose + OkHttp", style = MaterialTheme.typography.bodySmall)

        Text("پوشه درخواست‌ها", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = folderName, onValueChange = { folderName = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("نام پوشه جدید") }
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    repository.createFolder(folderName)
                    folderName = ""
                    folders = repository.folders()
                    response = "پوشه ساخته شد."
                }.onFailure { response = "خطا در ساخت پوشه: ${it.message}" }
            },
            enabled = folderName.isNotBlank(), modifier = Modifier.fillMaxWidth()
        ) { Text("+ ساخت پوشه") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                selectedFolderId = null
                savedRequests = repository.savedRequests()
            }) { Text(if (selectedFolderId == null) "✓ بدون پوشه" else "بدون پوشه") }
            folders.forEach { folder ->
                OutlinedButton(onClick = {
                    selectedFolderId = folder.id
                    savedRequests = repository.savedRequests(folder.id)
                }) { Text(if (selectedFolderId == folder.id) "✓ ${folder.name}" else folder.name) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { methodMenuOpen = true }) { Text(method) }
                DropdownMenu(expanded = methodMenuOpen, onDismissRequest = { methodMenuOpen = false }) {
                    methods.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            method = option
                            methodMenuOpen = false
                        })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it }, modifier = Modifier.weight(1f),
                singleLine = true, label = { Text("آدرس API") }
            )
        }

        Text("Query Parameters · هر خط key=value", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = params, onValueChange = { params = it }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, label = { Text("پارامترها") }
        )

        Text("Headers · هر خط Name: Value", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = headers, onValueChange = { headers = it }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, label = { Text("هدرهای درخواست") }
        )

        Text("Authorization", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            authTypes.forEach { type ->
                OutlinedButton(onClick = { authType = type }) {
                    Text(if (type == authType) "✓ $type" else type)
                }
            }
        }
        when (authType) {
            "bearer" -> OutlinedTextField(
                value = authToken, onValueChange = { authToken = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer Token") }
            )
            "basic" -> {
                OutlinedTextField(value = authUsername, onValueChange = { authUsername = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Username") })
                OutlinedTextField(value = authPassword, onValueChange = { authPassword = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Password") })
            }
            "apikey" -> {
                OutlinedTextField(value = apiKeyName, onValueChange = { apiKeyName = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("نام API Key") })
                OutlinedTextField(value = apiKeyValue, onValueChange = { apiKeyValue = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("مقدار API Key") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { apiKeyIn = "header" }) { Text(if (apiKeyIn == "header") "✓ Header" else "Header") }
                    OutlinedButton(onClick = { apiKeyIn = "query" }) { Text(if (apiKeyIn == "query") "✓ Query" else "Query") }
                }
            }
        }

        Text("Request Body", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            bodyTypes.forEach { type ->
                OutlinedButton(onClick = { bodyType = type }) {
                    Text(if (bodyType == type) "✓ $type" else type)
                }
            }
        }
        if (bodyType != "none") {
            OutlinedTextField(
                value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(),
                minLines = 4, label = { Text(if (bodyType == "form") "Form · هر خط key=value" else "Body") }
            )
        }

        Button(
            onClick = {
                loading = true
                response = "در حال ارسال درخواست..."
                val resultSpec = runCatching {
                    HttpRequestSpec(
                        method = method,
                        url = url,
                        queryParams = keyValues(params, '='),
                        headers = keyValues(headers, ':'),
                        body = body,
                        bodyType = bodyType,
                        authType = authType,
                        authToken = authToken,
                        authUsername = authUsername,
                        authPassword = authPassword,
                        apiKeyName = apiKeyName,
                        apiKeyValue = apiKeyValue,
                        apiKeyIn = apiKeyIn
                    )
                }
                resultSpec.onSuccess { spec ->
                    onSend(spec) { result ->
                        result.onSuccess { snapshot ->
                            response = snapshot.asDisplayText()
                            repository.recordHistory(
                                method, url, snapshot.statusCode, snapshot.durationMs,
                                snapshot.body.toByteArray().size.toLong()
                            )
                            history = repository.history(8)
                        }.onFailure { error ->
                            response = "خطا: ${error.message ?: error.javaClass.simpleName}"
                        }
                        loading = false
                    }
                }.onFailure { error ->
                    response = "خطا: ${error.message}"
                    loading = false
                }
            },
            enabled = !loading, modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("ارسال درخواست")
        }

        Text("ذخیره درخواست", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = requestName, onValueChange = { requestName = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("نام درخواست") }
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    val now = System.currentTimeMillis()
                    val headersJson = JSONArray().apply {
                        keyValues(headers, ':').forEach { put(JSONObject().put("key", it.key).put("value", it.value)) }
                    }.toString()
                    val paramsJson = JSONArray().apply {
                        keyValues(params, '=').forEach { put(JSONObject().put("key", it.key).put("value", it.value)) }
                    }.toString()
                    val authJson = JSONObject()
                        .put("token", authToken).put("username", authUsername).put("password", authPassword)
                        .put("apiKeyName", apiKeyName).put("apiKeyValue", apiKeyValue).put("apiKeyIn", apiKeyIn)
                    repository.saveRequest(
                        SavedRequest(
                            id = ir.behnamghafari.apixnative.data.local.ApiXDatabase.newId(),
                            name = requestName.ifBlank { "$method $url" },
                            method = method, url = url, headersJson = headersJson, paramsJson = paramsJson,
                            body = body, bodyType = bodyType, authType = authType,
                            authConfigJson = authJson.toString(), folderId = selectedFolderId,
                            createdAt = now, updatedAt = now
                        )
                    )
                    savedRequests = repository.savedRequests(selectedFolderId)
                    response = "درخواست ذخیره شد."
                }.onFailure { response = "خطا در ذخیره: ${it.message}" }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("☆ ذخیره درخواست") }

        Text("درخواست‌های ذخیره‌شده (${savedRequests.size})", style = MaterialTheme.typography.titleMedium)
        savedRequests.forEach { saved ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(saved.name, fontWeight = FontWeight.SemiBold)
                    Text("${saved.method} · ${saved.url}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { restoreRequest(saved) }) { Text("بازکردن") }
            }
        }

        Text("تاریخچه اخیر", style = MaterialTheme.typography.titleMedium)
        history.forEach { item ->
            Text(
                "${item.method} · ${item.statusCode ?: "خطا"} · ${item.url}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("پاسخ سرور", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(response, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
