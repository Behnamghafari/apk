package ir.behnamghafari.apixnative

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import ir.behnamghafari.apixnative.data.collection.PostmanCollectionCodec
import ir.behnamghafari.apixnative.data.importer.CurlCommandParser
import ir.behnamghafari.apixnative.data.model.ApiEnvironment
import ir.behnamghafari.apixnative.data.model.SavedRequest
import ir.behnamghafari.apixnative.data.network.HttpKeyValue
import ir.behnamghafari.apixnative.data.network.HttpMultipartFile
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
    private val requestEngine by lazy { HttpRequestEngine(applicationContext) }

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
    val bodyTypes = listOf("none", "json", "raw", "form", "multipart")

    var requestMode by remember { mutableStateOf("rest") }
    var graphQlQuery by remember { mutableStateOf("query {\n  __typename\n}") }
    var graphQlVariables by remember { mutableStateOf("{}") }
    var graphQlOperationName by remember { mutableStateOf("") }
    var multipartFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var multipartFieldName by remember { mutableStateOf("file") }
    var curlInput by remember { mutableStateOf("") }
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
    var responseBody by remember { mutableStateOf("") }
    var responseHeaders by remember { mutableStateOf("") }
    var responseFinalUrl by remember { mutableStateOf("") }
    var responseTab by remember { mutableStateOf("body") }
    var responseSearch by remember { mutableStateOf("") }
    var prettyResponseJson by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var savedRequests by remember { mutableStateOf(repository.savedRequests()) }
    var history by remember { mutableStateOf(repository.history(8)) }
    var folders by remember { mutableStateOf(repository.folders()) }
    var allFolders by remember { mutableStateOf(repository.allFolders()) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var environments by remember { mutableStateOf(repository.environments()) }
    var activeEnvironmentId by remember { mutableStateOf(environments.firstOrNull { it.isActive }?.id) }
    var environmentName by remember { mutableStateOf(environments.firstOrNull { it.isActive }?.name ?: "Development") }
    val importCollectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val source = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("فایل انتخاب‌شده قابل خواندن نیست.")
                val collection = PostmanCollectionCodec.parse(source)
                val root = repository.createFolder(collection.name)
                val folderCache = mutableMapOf<List<String>, String>(emptyList<String>() to root.id)
                collection.requests.forEach { imported ->
                    var parentId = root.id
                    val path = mutableListOf<String>()
                    imported.folderPath.forEach { segment ->
                        path += segment
                        val key = path.toList()
                        parentId = folderCache.getOrPut(key) { repository.createFolder(segment, parentId).id }
                    }
                    repository.saveRequest(imported.request.copy(folderId = parentId))
                }
                allFolders = repository.allFolders()
                folders = repository.folders(selectedFolderId)
                savedRequests = repository.savedRequests(selectedFolderId)
                response = "Collection «${collection.name}» وارد شد؛ ${collection.requests.size} درخواست اضافه شد."
            }.onFailure { response = "خطای Import: ${it.message}" }
        }
    }
    val exportCollectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val json = PostmanCollectionCodec.export(
                    "ApiX Collection", repository.allFolders(), repository.allSavedRequests()
                )
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    ?: error("فایل خروجی ساخته نشد.")
                response = "خروجی Postman با موفقیت ذخیره شد."
            }.onFailure { response = "خطای Export: ${it.message}" }
        }
    }

    val multipartFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selected ->
        multipartFiles = selected
        response = "${selected.size} فایل برای ارسال انتخاب شد."
    }

    var environmentVariables by remember {
        mutableStateOf(
            environments.firstOrNull { it.isActive }?.variablesJson?.let { json ->
                JSONArray(json).let { array ->
                    (0 until array.length()).joinToString("\n") { i ->
                        val item = array.getJSONObject(i)
                        "${item.optString("key")}=${item.optString("value")}"
                    }
                }
            } ?: ""
        )
    }

    fun fileDisplayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "upload.bin"

    fun keyValues(source: String, separator: Char): List<HttpKeyValue> =
        source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val index = line.indexOf(separator)
            require(index > 0) { "فرمت مقدار نامعتبر است: $line" }
            HttpKeyValue(line.substring(0, index).trim(), line.substring(index + 1).trim())
        }.toList()

    fun activeVariables(): Map<String, String> {
        val active = environments.firstOrNull { it.id == activeEnvironmentId } ?: return emptyMap()
        val array = JSONArray(active.variablesJson)
        return buildMap {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                put(item.getString("key"), item.optString("value"))
            }
        }
    }

    fun resolveVariables(value: String, variables: Map<String, String>): String {
        val pattern = Regex("\\{\\{\\s*([^{}]+?)\\s*}}")
        return pattern.replace(value) { match ->
            val key = match.groupValues[1].trim()
            require(variables.containsKey(key)) { "متغیر محیطی تعریف نشده است: $key" }
            variables.getValue(key)
        }
    }

    fun effectiveBody(): String {
        if (requestMode != "graphql") return body
        val variables = activeVariables()
        require(graphQlQuery.isNotBlank()) { "متن GraphQL خالی است." }
        val variablesObject = JSONObject(resolveVariables(graphQlVariables, variables))
        return JSONObject()
            .put("query", resolveVariables(graphQlQuery, variables))
            .put("variables", variablesObject)
            .apply {
                if (graphQlOperationName.isNotBlank()) put("operationName", graphQlOperationName.trim())
            }.toString()
    }

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
        multipartFiles = emptyList()
        requestMode = "rest"
        if (saved.bodyType == "json") {
            runCatching {
                val graphQl = JSONObject(saved.body)
                if (graphQl.has("query")) {
                    requestMode = "graphql"
                    graphQlQuery = graphQl.optString("query")
                    graphQlVariables = graphQl.optJSONObject("variables")?.toString(2) ?: "{}"
                    graphQlOperationName = graphQl.optString("operationName")
                }
            }
        }
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { requestMode = "rest" }, modifier = Modifier.weight(1f)) {
                Text(if (requestMode == "rest") "✓ REST" else "REST")
            }
            OutlinedButton(onClick = { requestMode = "graphql"; method = "POST"; bodyType = "json" }, modifier = Modifier.weight(1f)) {
                Text(if (requestMode == "graphql") "✓ GraphQL" else "GraphQL")
            }
        }

        Text("محیط‌ها (Environments)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = environmentName, onValueChange = { environmentName = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("نام محیط") }
        )
        OutlinedTextField(
            value = environmentVariables, onValueChange = { environmentVariables = it },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("متغیرها · هر خط KEY=VALUE") }
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    val parsed = environmentVariables.lineSequence().map { it.trim() }
                        .filter { it.isNotEmpty() }.map { line ->
                            val split = line.indexOf('=')
                            require(split > 0) { "فرمت متغیر نامعتبر است: $line" }
                            val key = line.substring(0, split).trim()
                            require(key.isNotBlank()) { "نام متغیر خالی است." }
                            JSONObject().put("key", key).put("value", line.substring(split + 1))
                        }.toList()
                    require(parsed.map { it.getString("key") }.distinct().size == parsed.size) {
                        "نام متغیرها باید یکتا باشد."
                    }
                    val now = System.currentTimeMillis()
                    val existing = environments.firstOrNull { it.id == activeEnvironmentId }
                    val environment = ApiEnvironment(
                        id = existing?.id ?: ir.behnamghafari.apixnative.data.local.ApiXDatabase.newId(),
                        name = environmentName.trim().ifBlank { "Environment" },
                        variablesJson = JSONArray().apply { parsed.forEach { put(it) } }.toString(),
                        isActive = true,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                    repository.saveEnvironment(environment)
                    environments = repository.environments()
                    activeEnvironmentId = environment.id
                    response = "محیط «${environment.name}» ذخیره و فعال شد."
                }.onFailure { response = "خطا در ذخیره محیط: ${it.message}" }
            },
            enabled = environmentName.isNotBlank(), modifier = Modifier.fillMaxWidth()
        ) { Text("ذخیره و فعال‌سازی محیط") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            environments.forEach { env ->
                OutlinedButton(onClick = {
                    repository.activateEnvironment(env.id)
                    environments = repository.environments()
                    activeEnvironmentId = env.id
                    environmentName = env.name
                    environmentVariables = JSONArray(env.variablesJson).let { array ->
                        (0 until array.length()).joinToString("\n") { i ->
                            val item = array.getJSONObject(i)
                            "${item.optString("key")}=${item.optString("value")}"
                        }
                    }
                }) { Text(if (activeEnvironmentId == env.id) "✓ ${env.name}" else env.name) }
            }
        }
        Text(
            "متغیرها با قالب {{نام}} در URL، پارامترها، هدرها، بدنه و مقادیر احراز هویت جایگزین می‌شوند.",
            style = MaterialTheme.typography.bodySmall
        )

        Text("Import cURL", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = curlInput, onValueChange = { curlInput = it },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("دستور curl را اینجا Paste کن") }
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    val imported = CurlCommandParser.parse(curlInput)
                    restoreRequest(imported)
                    curlInput = ""
                    response = "دستور cURL وارد شد؛ درخواست آماده ارسال است."
                }.onFailure { response = "خطای cURL: ${it.message}" }
            },
            enabled = curlInput.isNotBlank(), modifier = Modifier.fillMaxWidth()
        ) { Text("تبدیل cURL به درخواست") }

        Text("Collections · Postman v2.1", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { importCollectionLauncher.launch("*/*") }, modifier = Modifier.weight(1f)) {
                Text("Import Collection")
            }
            OutlinedButton(onClick = { exportCollectionLauncher.launch("ApiX-collection.json") }, modifier = Modifier.weight(1f)) {
                Text("Export Collection")
            }
        }
        Text(
            "ورود ساختار پوشه‌ها و درخواست‌ها را حفظ می‌کند؛ خروجی شامل تمام درخواست‌های ذخیره‌شده است.",
            style = MaterialTheme.typography.bodySmall
        )

        Text("پوشه‌های درخواست", style = MaterialTheme.typography.titleMedium)
        Text(
            if (selectedFolderId == null) "مسیر فعلی: ریشه" else {
                val current = allFolders.firstOrNull { it.id == selectedFolderId }
                "مسیر فعلی: ${current?.name ?: "پوشه"}"
            },
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = folderName, onValueChange = { folderName = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("نام پوشه / نام جدید") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        repository.createFolder(folderName, selectedFolderId)
                        folderName = ""
                        allFolders = repository.allFolders()
                        folders = repository.folders(selectedFolderId)
                        response = "زیرپوشه ساخته شد."
                    }.onFailure { response = "خطا در ساخت پوشه: ${it.message}" }
                },
                enabled = folderName.isNotBlank(), modifier = Modifier.weight(1f)
            ) { Text("+ زیرپوشه") }
            if (selectedFolderId != null) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            repository.renameFolder(selectedFolderId!!, folderName)
                            folderName = ""
                            allFolders = repository.allFolders()
                            folders = repository.folders(selectedFolderId)
                            response = "نام پوشه تغییر کرد."
                        }.onFailure { response = "خطا در تغییر نام: ${it.message}" }
                    },
                    enabled = folderName.isNotBlank(), modifier = Modifier.weight(1f)
                ) { Text("تغییر نام") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                val parentId = allFolders.firstOrNull { it.id == selectedFolderId }?.parentId
                selectedFolderId = parentId
                folders = repository.folders(parentId)
                savedRequests = repository.savedRequests(parentId)
            }, enabled = selectedFolderId != null) { Text("بازگشت") }
            OutlinedButton(onClick = {
                selectedFolderId = null
                folders = repository.folders()
                savedRequests = repository.savedRequests()
            }) { Text("ریشه") }
            if (selectedFolderId != null) {
                OutlinedButton(onClick = {
                    runCatching {
                        val parentId = allFolders.firstOrNull { it.id == selectedFolderId }?.parentId
                        repository.deleteFolder(selectedFolderId!!)
                        selectedFolderId = parentId
                        allFolders = repository.allFolders()
                        folders = repository.folders(parentId)
                        savedRequests = repository.savedRequests(parentId)
                        response = "پوشه حذف شد."
                    }.onFailure { response = "خطا در حذف پوشه: ${it.message}" }
                }) { Text("حذف پوشه") }
            }
        }
        folders.forEach { folder ->
            OutlinedButton(
                onClick = {
                    selectedFolderId = folder.id
                    folders = repository.folders(folder.id)
                    savedRequests = repository.savedRequests(folder.id)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("📁 ${folder.name}  ›") }
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

        if (requestMode == "graphql") {
            Text("GraphQL Query", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = graphQlQuery, onValueChange = { graphQlQuery = it },
                modifier = Modifier.fillMaxWidth(), minLines = 6,
                label = { Text("Query / Mutation") }
            )
            OutlinedTextField(
                value = graphQlOperationName, onValueChange = { graphQlOperationName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Operation Name (اختیاری)") }
            )
            OutlinedTextField(
                value = graphQlVariables, onValueChange = { graphQlVariables = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Variables · JSON Object") }
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

        if (requestMode == "rest") {
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
                minLines = 4,
                label = { Text(if (bodyType == "form" || bodyType == "multipart") "Form fields · هر خط key=value" else "Body") }
            )
        }
        if (bodyType == "multipart") {
            OutlinedTextField(
                value = multipartFieldName, onValueChange = { multipartFieldName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("نام فیلد فایل") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { multipartFilePicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text("انتخاب فایل‌ها")
                }
                OutlinedButton(onClick = { multipartFiles = emptyList() }, enabled = multipartFiles.isNotEmpty()) {
                    Text("پاک‌کردن")
                }
            }
            multipartFiles.forEach { uri ->
                Text(fileDisplayName(uri), style = MaterialTheme.typography.bodySmall)
            }
            Text("فایل‌ها پس از بارگذاری مجدد درخواست باید دوباره انتخاب شوند.", style = MaterialTheme.typography.bodySmall)
        }

        }

        Button(
            onClick = {
                loading = true
                response = "در حال ارسال درخواست..."
                val resultSpec = runCatching {
                    val variables = activeVariables()
                    val multipartPayload = if (bodyType == "multipart") {
                        require(multipartFiles.size <= 10) { "حداکثر ۱۰ فایل را می‌توانید هم‌زمان ارسال کنید." }
                        multipartFiles.map { uri ->
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("فایل ${fileDisplayName(uri)} قابل خواندن نیست.")
                            require(bytes.size <= 20 * 1024 * 1024) { "حجم هر فایل نباید بیشتر از ۲۰ مگابایت باشد." }
                            HttpMultipartFile(
                                fieldName = multipartFieldName,
                                fileName = fileDisplayName(uri),
                                contentType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                                bytes = bytes
                            )
                        }.also { files ->
                            require(files.sumOf { it.bytes.size.toLong() } <= 50L * 1024 * 1024) {
                                "حجم کل فایل‌ها نباید بیشتر از ۵۰ مگابایت باشد."
                            }
                        }
                    } else emptyList()
                    HttpRequestSpec(
                        method = if (requestMode == "graphql") "POST" else method,
                        url = resolveVariables(url, variables),
                        queryParams = keyValues(resolveVariables(params, variables), '='),
                        headers = keyValues(resolveVariables(headers, variables), ':'),
                        body = resolveVariables(effectiveBody(), variables),
                        bodyType = if (requestMode == "graphql") "json" else bodyType,
                        authType = authType,
                        authToken = resolveVariables(authToken, variables),
                        authUsername = resolveVariables(authUsername, variables),
                        authPassword = resolveVariables(authPassword, variables),
                        apiKeyName = resolveVariables(apiKeyName, variables),
                        apiKeyValue = resolveVariables(apiKeyValue, variables),
                        apiKeyIn = apiKeyIn,
                        files = multipartPayload
                    )
                }
                resultSpec.onSuccess { spec ->
                    onSend(spec) { result ->
                        result.onSuccess { snapshot ->
                            response = "HTTP ${snapshot.statusCode} ${snapshot.reason} · ${snapshot.durationMs} ms"
                            responseBody = snapshot.body
                            responseHeaders = snapshot.headers.joinToString("\n") { (name, value) -> "$name: $value" }
                            responseFinalUrl = snapshot.finalUrl
                            responseTab = "body"
                            responseSearch = ""
                            repository.recordHistory(
                                if (requestMode == "graphql") "POST" else method, url, snapshot.statusCode, snapshot.durationMs,
                                snapshot.body.toByteArray().size.toLong()
                            )
                            history = repository.history(8)
                        }.onFailure { error ->
                            response = "خطا: ${error.message ?: error.javaClass.simpleName}"
                            responseBody = response
                            responseHeaders = ""
                            responseFinalUrl = ""
                        }
                        loading = false
                    }
                }.onFailure { error ->
                    response = "خطا: ${error.message}"
                    responseBody = response
                    responseHeaders = ""
                    responseFinalUrl = ""
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
                            body = effectiveBody(), bodyType = if (requestMode == "graphql") "json" else bodyType, authType = authType,
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
            var moveMenuOpen by remember(saved.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(saved.name, fontWeight = FontWeight.SemiBold)
                    Text("${saved.method} · ${saved.url}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { restoreRequest(saved) }) { Text("بازکردن") }
                Box {
                    OutlinedButton(onClick = { moveMenuOpen = true }) { Text("انتقال") }
                    DropdownMenu(expanded = moveMenuOpen, onDismissRequest = { moveMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("بدون پوشه") }, onClick = {
                            repository.saveRequest(saved.copy(folderId = null, updatedAt = System.currentTimeMillis()))
                            moveMenuOpen = false
                            savedRequests = repository.savedRequests(selectedFolderId)
                        })
                        allFolders.forEach { target ->
                            DropdownMenuItem(text = { Text(target.name) }, onClick = {
                                repository.saveRequest(saved.copy(folderId = target.id, updatedAt = System.currentTimeMillis()))
                                moveMenuOpen = false
                                savedRequests = repository.savedRequests(selectedFolderId)
                            })
                        }
                    }
                }
                OutlinedButton(onClick = {
                    repository.deleteRequest(saved.id)
                    savedRequests = repository.savedRequests(selectedFolderId)
                    response = "درخواست حذف شد."
                }) { Text("حذف") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("تاریخچه اخیر", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = {
                repository.clearHistory()
                history = emptyList()
            }, enabled = history.isNotEmpty()) { Text("پاک‌کردن همه") }
        }
        history.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${item.method} · ${item.statusCode ?: "خطا"}", style = MaterialTheme.typography.bodySmall)
                    Text(item.url, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    requestMode = "rest"
                    method = item.method
                    url = item.url
                    params = ""
                    headers = ""
                    body = ""
                    bodyType = "none"
                    requestName = ""
                    response = "درخواست از تاریخچه بارگذاری شد."
                    responseBody = ""
                    responseHeaders = ""
                    responseFinalUrl = ""
                }) { Text("بازکردن") }
                OutlinedButton(onClick = {
                    repository.deleteHistory(item.id)
                    history = repository.history(8)
                }) { Text("حذف") }
            }
        }

        Text("Response Inspector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(response, style = MaterialTheme.typography.bodySmall)
        if (responseFinalUrl.isNotBlank()) {
            Text(responseFinalUrl, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { responseTab = "body" }, modifier = Modifier.weight(1f)) {
                Text(if (responseTab == "body") "✓ Body" else "Body")
            }
            OutlinedButton(onClick = { responseTab = "headers" }, modifier = Modifier.weight(1f)) {
                Text(if (responseTab == "headers") "✓ Headers" else "Headers")
            }
        }
        if (responseTab == "body") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { prettyResponseJson = !prettyResponseJson }, modifier = Modifier.weight(1f)) {
                    Text(if (prettyResponseJson) "Pretty JSON" else "Raw")
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ApiX response", responseBody.ifBlank { response }))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Copy Body") }
            }
        } else {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ApiX response headers", responseHeaders))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Copy Headers") }
        }
        OutlinedTextField(
            value = responseSearch, onValueChange = { responseSearch = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("جستجو در پاسخ") }
        )
        val renderedResponseBody = if (prettyResponseJson && responseBody.isNotBlank()) {
            runCatching { JSONObject(responseBody).toString(2) }.getOrElse {
                runCatching { JSONArray(responseBody).toString(2) }.getOrDefault(responseBody)
            }
        } else responseBody
        val activeResponseText = if (responseTab == "body") renderedResponseBody.ifBlank { response } else responseHeaders
        val responseMatches = if (responseSearch.isBlank()) 0 else {
            Regex(Regex.escape(responseSearch), RegexOption.IGNORE_CASE).findAll(activeResponseText).count()
        }
        if (responseSearch.isNotBlank()) {
            Text("$responseMatches مورد پیدا شد", style = MaterialTheme.typography.bodySmall)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                if (responseSearch.isBlank()) activeResponseText else activeResponseText.lines()
                    .filter { it.contains(responseSearch, true) }.joinToString("\n"),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
