package com.spectre.studio

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var catalogFile: File
    private lateinit var workspaceDir: File
    private val catalogLock = Any()
    private val executor = Executors.newCachedThreadPool()

    companion object {
        private const val DEFAULT_BASE_URL = "https://coarena.ai/api/v1"
        private const val DEFAULT_API_KEY = "coa_60d6228246236af449b8aaaa24df1e23"
        private const val FALLBACK_MODEL = "claude-fable-5"

        private val RISKY_PATTERNS = listOf(
            Regex("""\beval\s*\("""),
            Regex("""\bexec\s*\("""),
            Regex("""shell\s*=\s*True"""),
            Regex("""unsafe\s*\{""")
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        workspaceDir = File(filesDir, "spectre_workspace").apply { mkdirs() }
        catalogFile = File(workspaceDir, "catalog.json").apply {
            if (!exists()) writeText("[]")
        }

        webView = findViewById(R.id.spectreWebView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                if (request.isForMainFrame && !url.startsWith("file:///android_asset/")) {
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        executor.shutdownNow()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun readCatalog(): JSONArray {
        synchronized(catalogLock) {
            return try {
                JSONArray(catalogFile.readText())
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }

    private fun writeCatalog(arr: JSONArray) {
        synchronized(catalogLock) {
            catalogFile.writeText(arr.toString(2))
        }
    }

    private fun addEntryInternal(title: String, tag: String, targetPath: String): JSONObject {
        synchronized(catalogLock) {
            val cleanTitle = title.trim().ifEmpty { "adsiz_kayit" }
            val cleanTag = tag.trim().lowercase(Locale.US).ifEmpty { "genel" }
            val cleanPath = targetPath.trim().ifEmpty { workspaceDir.absolutePath }

            val list = readCatalog()
            val prefix = cleanTag.replace(Regex("[^a-z0-9]"), "")
                .uppercase(Locale.US)
                .take(4)
                .ifEmpty { "ITEM" }
            val nextNum = String.format(Locale.US, "%03d", list.length() + 1)
            val code = "$prefix-$nextNum"

            val entry = JSONObject().apply {
                put("code", code)
                put("title", cleanTitle)
                put("tag", cleanTag)
                put("path", cleanPath)
            }
            list.put(entry)
            writeCatalog(list)
            return entry
        }
    }

    private fun searchCatalogInternal(query: String): JSONArray {
        val q = query.trim().lowercase(Locale.US)
        val list = readCatalog()
        val matches = JSONArray()

        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val code = item.optString("code").lowercase(Locale.US)
            val title = item.optString("title").lowercase(Locale.US)
            val tag = item.optString("tag").lowercase(Locale.US)
            val path = item.optString("path").lowercase(Locale.US)

            if (q.isEmpty() || code.contains(q) || title.contains(q) || tag.contains(q) || path.contains(q)) {
                matches.put(item)
            }
        }
        return matches
    }

    private fun scanCodeRulesInternal(sourceText: String): JSONArray {
        val findings = JSONArray()
        val lines = sourceText.lines()

        lines.forEachIndexed { index, line ->
            val lineNo = index + 1
            RISKY_PATTERNS.forEach { pattern ->
                if (pattern.containsMatchIn(line)) {
                    findings.put(
                        JSONObject().apply {
                            put("line", lineNo)
                            put("level", "Uyarı")
                            put("message", "Potansiyel riskli desen bulundu: ${pattern.pattern}")
                        }
                    )
                }
            }
            val trimmed = line.trim()
            if (trimmed == "catch" || trimmed.startsWith("except:")) {
                findings.put(
                    JSONObject().apply {
                        put("line", lineNo)
                        put("level", "Kontrol")
                        put("message", "Geniş hata yakalama bloğu; açık tip/koşul belirtmek daha güvenli olur.")
                    }
                )
            }
        }
        return findings
    }

    private fun preparePackageUpdateInternal(pkgName: String, confirmed: Boolean): JSONObject {
        val cleanPkg = pkgName.trim()
        if (cleanPkg.isEmpty()) {
            return JSONObject()
                .put("status", "error")
                .put("message", "Paket adı boş olamaz.")
        }
        if (!confirmed) {
            return JSONObject()
                .put("status", "needs_confirmation")
                .put("package", cleanPkg)
                .put("message", "'$cleanPkg' paketi için güncelleme hazır. Onay verirsen güncelleme adımı başlatılacak.")
        }
        return JSONObject()
            .put("status", "queued")
            .put("package", cleanPkg)
            .put("message", "'$cleanPkg' paketi için güncelleme komutu onaylandı ve kuyruğa alındı.")
    }

    private fun normalizeV1Base(baseUrl: String): String {
        val clean = baseUrl.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/')
        return if (clean.endsWith("/v1")) clean else "$clean/v1"
    }

    private fun httpRequest(
        method: String,
        urlStr: String,
        headers: Map<String, String>,
        bodyJson: String? = null
    ): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = 25000
            readTimeout = 90000
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (bodyJson != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        return try {
            if (bodyJson != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(bodyJson)
                    writer.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            } ?: ""
            Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFreeModelIds(baseUrl: String, apiKey: String): List<String> {
        val v1 = normalizeV1Base(baseUrl)
        val headers = mutableMapOf("Accept" to "application/json")
        if (apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${apiKey.trim()}"
        }
        val (code, raw) = httpRequest("GET", "$v1/models", headers)
        if (code !in 200..299) {
            throw RuntimeException("Model listesi alınamadı (HTTP $code): $raw")
        }

        val parsed = JSONObject(raw)
        val arr = parsed.optJSONArray("data") ?: parsed.optJSONArray("models") ?: JSONArray()
        val allIds = mutableListOf<String>()
        val explicitFreeIds = mutableListOf<String>()

        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> {
                    val id = item.optString("id").trim()
                    if (id.isNotEmpty()) {
                        allIds.add(id)
                        val isFree = item.optBoolean("free", false) ||
                            id.contains("free", ignoreCase = true)
                        if (isFree) explicitFreeIds.add(id)
                    }
                }
                is String -> if (item.isNotBlank()) allIds.add(item.trim())
            }
        }

        return if (explicitFreeIds.isNotEmpty()) explicitFreeIds else allIds
    }

    private fun buildOpenAiTools(): JSONArray {
        fun toolDef(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
            val params = JSONObject()
                .put("type", "object")
                .put("properties", props)
            if (required.isNotEmpty()) {
                params.put("required", JSONArray(required))
            }
            return JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put("parameters", params)
                )
        }

        return JSONArray().apply {
            put(
                toolDef(
                    "catalog_add",
                    "Yeni kayıt ekler ve otomatik çağrı kodu üretir.",
                    JSONObject()
                        .put("title", JSONObject().put("type", "string").put("description", "Kayıt başlığı"))
                        .put("tag", JSONObject().put("type", "string").put("description", "Etiket"))
                        .put("path", JSONObject().put("type", "string").put("description", "Hedef yol")),
                    listOf("title")
                )
            )
            put(
                toolDef(
                    "catalog_search",
                    "Kod, etiket, başlık veya yol üzerinden katalogda arama yapar.",
                    JSONObject().put("query", JSONObject().put("type", "string").put("description", "Arama kelimesi"))
                )
            )
            put(
                toolDef(
                    "code_scan",
                    "Verilen kaynak kod veya metinde riskli kalıpları ve geniş hata yakalama bloklarını tarar.",
                    JSONObject().put("source", JSONObject().put("type", "string").put("description", "Taranacak kaynak kod")),
                    listOf("source")
                )
            )
            put(
                toolDef(
                    "package_update",
                    "Paket için onay mekanizmalı güncelleme adımı hazırlar.",
                    JSONObject()
                        .put("package", JSONObject().put("type", "string").put("description", "Paket adı"))
                        .put("confirmed", JSONObject().put("type", "boolean").put("description", "Onay durumu")),
                    listOf("package")
                )
            )
            put(
                toolDef(
                    "workspace_info",
                    "Çalışma dizini ve katalog dosyası bilgilerini döndürür.",
                    JSONObject()
                )
            )
        }
    }

    private fun executeLocalTool(toolName: String, argsJson: String): JSONObject {
        val cleanTool = toolName.trim()
        return try {
            val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
            val resultData: Any = when (cleanTool) {
                "catalog_add" -> addEntryInternal(
                    args.optString("title", ""),
                    args.optString("tag", ""),
                    args.optString("path", "")
                )
                "catalog_search" -> searchCatalogInternal(args.optString("query", ""))
                "code_scan" -> scanCodeRulesInternal(args.optString("source", ""))
                "package_update" -> preparePackageUpdateInternal(
                    args.optString("package", ""),
                    args.optBoolean("confirmed", false)
                )
                "workspace_info" -> JSONObject()
                    .put("workspace_dir", workspaceDir.absolutePath)
                    .put("catalog_file", catalogFile.absolutePath)
                    .put("total_records", readCatalog().length())
                else -> return JSONObject()
                    .put("ok", false)
                    .put("tool", cleanTool)
                    .put("error", "Bilinmeyen tool: $cleanTool")
            }
            JSONObject()
                .put("ok", true)
                .put("tool", cleanTool)
                .put("result", resultData)
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("tool", cleanTool)
                .put("error", e.message ?: "Araç hatası")
        }
    }

    private fun invokeJsCallback(callbackName: String, payload: JSONObject) {
        if (isDestroyed || isFinishing) return
        val safeCb = callbackName.trim()
        if (!Regex("""^[A-Za-z0-9_.]+$""").matches(safeCb)) return
        val jsonStr = payload.toString()
        webView.post {
            if (!isDestroyed && !isFinishing) {
                webView.evaluateJavascript("$safeCb(${JSONObject.quote(jsonStr)})", null)
            }
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun listTools(): String {
            val tools = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("name", "catalog_add")
                        put("description", "Yeni kayıt ekler ve otomatik çağrı kodu üretir.")
                        put("input_schema", JSONObject()
                            .put("title", "string")
                            .put("tag", "string")
                            .put("path", "string"))
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "catalog_search")
                        put("description", "Kod, etiket, başlık veya yol üzerinden katalogda arama yapar.")
                        put("input_schema", JSONObject().put("query", "string"))
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "code_scan")
                        put("description", "Verilen kaynak metinde riskli kalıpları ve hata yakalama bloklarını tarar.")
                        put("input_schema", JSONObject().put("source", "string"))
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "package_update")
                        put("description", "Herhangi bir paket için onay mekanizmalı güncelleme adımı hazırlar.")
                        put("input_schema", JSONObject()
                            .put("package", "string")
                            .put("confirmed", "boolean"))
                    }
                )
                put(
                    JSONObject().apply {
                        put("name", "workspace_info")
                        put("description", "Çalışma dizini ve katalog dosyası bilgilerini döndürür.")
                        put("input_schema", JSONObject())
                    }
                )
            }
            return JSONObject().put("tools", tools).toString()
        }

        @JavascriptInterface
        fun callTool(toolName: String, argsJson: String): String {
            return executeLocalTool(toolName, argsJson).toString()
        }

        @JavascriptInterface
        fun fetchModelsAsync(baseUrl: String, apiKey: String, callbackName: String) {
            executor.execute {
                val res = try {
                    val ids = fetchFreeModelIds(baseUrl, apiKey)
                    JSONObject()
                        .put("ok", true)
                        .put("model_ids", JSONArray(ids))
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: "Model listesi alınamadı.")
                }
                invokeJsCallback(callbackName, res)
            }
        }

        @JavascriptInterface
        fun runAgentAsync(
            prompt: String,
            model: String,
            baseUrl: String,
            apiKey: String,
            callbackName: String
        ) {
            executor.execute {
                val out = try {
                    val v1 = normalizeV1Base(baseUrl)
                    val key = apiKey.trim().ifEmpty { DEFAULT_API_KEY }
                    var chosenModel = model.trim()
                    if (chosenModel.isEmpty()) {
                        chosenModel = runCatching { fetchFreeModelIds(v1, key).firstOrNull() }
                            .getOrNull() ?: FALLBACK_MODEL
                    }

                    val messages = JSONArray().apply {
                        put(
                            JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    "Sen Spectre AI Studio'nun yerel Android kod ve MCP ajanısın. " +
                                        "Kullanıcının isteğine göre gerektiğinde catalog_add, catalog_search, " +
                                        "code_scan, package_update ve workspace_info araçlarını çağır. " +
                                        "Kod incelerken veya üretirken gerekirse code_scan ile tara. Kısa ve net Türkçe yanıt ver."
                                )
                        )
                        put(JSONObject().put("role", "user").put("content", prompt))
                    }

                    val toolsSchema = buildOpenAiTools()
                    val executedTools = JSONArray()
                    var useTools = true
                    var finalReply = ""
                    var finalModel = chosenModel
                    var responseId = ""

                    for (step in 0 until 5) {
                        val payload = JSONObject()
                            .put("model", chosenModel)
                            .put("messages", messages)
                        if (useTools) {
                            payload.put("tools", toolsSchema)
                            payload.put("tool_choice", "auto")
                        }

                        val (code, raw) = httpRequest(
                            method = "POST",
                            urlStr = "$v1/chat/completions",
                            headers = mapOf(
                                "Authorization" to "Bearer $key",
                                "Accept" to "application/json"
                            ),
                            bodyJson = payload.toString()
                        )

                        if (code !in 200..299) {
                            if (useTools && step == 0 && (code == 400 || code == 422)) {
                                useTools = false
                                continue
                            }
                            throw RuntimeException("API hatası (HTTP $code): $raw")
                        }

                        val resp = JSONObject(raw)
                        responseId = resp.optString("id", responseId)
                        finalModel = resp.optString("model", chosenModel)

                        val message = resp.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?: throw RuntimeException("Modelden geçerli mesaj alınamadı.")

                        val toolCalls = message.optJSONArray("tool_calls")
                        if (toolCalls != null && toolCalls.length() > 0) {
                            messages.put(message)
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val tcId = tc.optString("id", "call_$i")
                                val fn = tc.optJSONObject("function") ?: JSONObject()
                                val fnName = fn.optString("name", "")
                                val fnArgs = fn.optString("arguments", "{}")

                                val toolOut = executeLocalTool(fnName, fnArgs)
                                executedTools.put(
                                    JSONObject()
                                        .put("tool", fnName)
                                        .put("ok", toolOut.optBoolean("ok", false))
                                )

                                messages.put(
                                    JSONObject()
                                        .put("role", "tool")
                                        .put("tool_call_id", tcId)
                                        .put("content", toolOut.toString())
                                )
                            }
                        } else {
                            finalReply = message.optString("content", "")
                            break
                        }
                    }

                    JSONObject()
                        .put("ok", true)
                        .put(
                            "result",
                            JSONObject()
                                .put("reply", finalReply)
                                .put("model", finalModel)
                                .put("response_id", responseId)
                                .put("tool_calls_executed", executedTools)
                        )
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: "Ajan çalıştırılırken hata oluştu.")
                }

                invokeJsCallback(callbackName, out)
            }
        }

        @JavascriptInterface
        fun sendFeedbackAsync(
            responseId: String,
            rating: Int,
            note: String,
            baseUrl: String,
            apiKey: String,
            callbackName: String
        ) {
            executor.execute {
                val out = try {
                    val v1 = normalizeV1Base(baseUrl)
                    val key = apiKey.trim().ifEmpty { DEFAULT_API_KEY }
                    val payload = JSONObject().put("request_id", responseId.trim())
                    when {
                        rating > 0 -> payload.put("rating", "up")
                        rating < 0 -> payload.put("rating", "down")
                        else -> payload.put("rating", JSONObject.NULL)
                    }
                    if (note.isNotBlank()) {
                        payload.put("note", note.trim().take(2000))
                    }

                    val (code, raw) = httpRequest(
                        method = "POST",
                        urlStr = "$v1/feedback",
                        headers = mapOf(
                            "Authorization" to "Bearer $key",
                            "Accept" to "application/json"
                        ),
                        bodyJson = payload.toString()
                    )
                    if (code !in 200..299) {
                        throw RuntimeException("Feedback hatası (HTTP $code): $raw")
                    }
                    JSONObject().put("ok", true)
                } catch (e: Exception) {
                    JSONObject()
                        .put("ok", false)
                        .put("error", e.message ?: "Feedback gönderilemedi.")
                }

                invokeJsCallback(callbackName, out)
            }
        }
    }
}