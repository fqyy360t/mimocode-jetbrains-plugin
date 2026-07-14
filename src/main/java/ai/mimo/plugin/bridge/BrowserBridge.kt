package ai.mimo.plugin.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import ai.mimo.plugin.server.ServerManager
import ai.mimo.plugin.settings.MiMoConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder

class BrowserBridge(private val project: Project) : Disposable {

    val browser: JBCefBrowser by lazy { createBrowser() }

    private var query: JBCefJSQuery? = null
    private var fileServer: HttpServer? = null
    private var fileServerPort: Int = -1
    @Volatile
    private var sessionId: String? = null

    private fun createBrowser(): JBCefBrowser {
        check(JBCefApp.isSupported()) { "JCEF is not supported in this IDE" }

        val b = JBCefBrowser.createBuilder()
            .setOffScreenRendering(false)
            .build()

        query = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
            addHandler { msg ->
                handleFromWebapp(msg)
                null
            }
        }

        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, code: Int) {
                if (!frame.isMain) return
                injectQueryBridge(b)
            }
        }, b.cefBrowser)

        return b
    }

    fun load() {
        val apiPort = ApplicationManager.getApplication().getService(ServerManager::class.java).start()
        fileServerPort = startFileServer(apiPort)
        browser.loadURL("http://localhost:$fileServerPort/")
    }

    override fun dispose() {
        query?.dispose()
        fileServer?.stop(0)
        browser.dispose()
    }

    private fun startFileServer(apiPort: Int): Int {
        val port = freePort()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        fileServer = server

        val dir = project.basePath ?: ""
        val name = project.name

        val configScript = """
            <script>
            window.__MIMO_CONFIG = {
                apiPort: $apiPort,
                projectDir: ${json(dir)},
                projectName: ${json(name)}
            };
            </script>
        """.trimIndent()

        server.createContext("/") { exchange ->
            var path = exchange.requestURI.path.removePrefix("/")
            if (path.isEmpty() || path == "index.html") path = "index.html"

            val resource: InputStream? = javaClass.getResourceAsStream("/webview/$path")
            if (resource == null) {
                val body = "Not found: $path".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }

            val mime = mimeFor(path)
            val bytes = resource.use { it.readBytes() }

            val response = if (path == "index.html") {
                val html = String(bytes, Charsets.UTF_8)
                html.replace("</head>", "$configScript\n</head>")
                    .toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }

            exchange.responseHeaders.add("Content-Type", mime)
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.executor = null
        server.start()
        return port
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".wasm") -> "application/wasm"
        else -> "application/octet-stream"
    }

    fun send(payload: String) {
        browser.cefBrowser.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('mimo:ide',{detail:$payload}))",
            browser.cefBrowser.url,
            0
        )
    }

    fun sendActiveEditor(path: String, language: String, line: Int, col: Int, lineCount: Int, surroundingCode: String) {
        send("""{"type":"activeEditor","path":${json(path)},"language":${json(language)},"line":$line,"col":$col,"lineCount":$lineCount,"surroundingCode":${json(surroundingCode)}}""")
    }

    fun sendTabs(tabs: List<TabPayload>) {
        val arr = tabs.joinToString(",") {
            """{"path":${json(it.path)},"name":${json(it.name)},"active":${it.active},"modified":${it.modified}}"""
        }
        send("""{"type":"openTabs","tabs":[$arr]}""")
    }

    fun sendSelection(path: String, startLine: Int, endLine: Int, code: String, language: String) {
        val id = "${path}:${startLine}".hashCode().toString()
        send("""{"type":"selectionAdded","id":${json(id)},"path":${json(path)},"name":${json(path.substringAfterLast('/'))},"startLine":$startLine,"endLine":$endLine,"code":${json(code)},"language":${json(language)}}""")
    }

    fun sendTheme(dark: Boolean, bg: String, fg: String, accent: String, border: String) {
        send("""{"type":"theme","dark":$dark,"bg":${json(bg)},"fg":${json(fg)},"accent":${json(accent)},"border":${json(border)}}""")
    }

    fun sendProjectInfo(path: String, name: String, branch: String?) {
        send("""{"type":"projectInfo","path":${json(path)},"name":${json(name)},"branch":${json(branch ?: "")}}""")
    }

    private fun handleFromWebapp(msg: String) {
        val typeMatch = Regex(""""type"\s*:\s*"([^"]+)""").find(msg) ?: return
        val type = typeMatch.groupValues[1]

        when (type) {
            "ready" -> {
                ApplicationManager.getApplication().invokeLater {
                    project.getService(IdeContextService::class.java).sendCurrentState()
                }
            }
            "openFile" -> {
                val pathMatch = Regex(""""path"\s*:\s*"([^"]+)""").find(msg) ?: return
                val lineMatch = Regex(""""line"\s*:\s*(\d+)""").find(msg)
                val path = pathMatch.groupValues[1]
                val line = lineMatch?.groupValues?.get(1)?.toIntOrNull()
                openFileInEditor(path, line)
            }
            "message" -> {
                val contentMatch = Regex(""""content"\s*:\s*"([^"]*?)"""").find(msg) ?: return
                val content = contentMatch.groupValues[1]
                val mode = extractJsonValue(msg, "mode") ?: "chat"
                val model = extractJsonValue(msg, "model") ?: "mimo-auto"
                val context = extractJsonValue(msg, "context") ?: "default"
                Thread {
                    forwardToServer(content, mode, model, context)
                }.start()
            }
            "settingChange" -> {
                val setting = extractJsonValue(msg, "setting") ?: return
                val value = extractJsonValue(msg, "value") ?: return
                handleSettingChange(setting, value)
            }
            "openSettings" -> {
                ApplicationManager.getApplication().invokeLater {
                    // Open settings dialog
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, MiMoConfigurable::class.java)
                }
            }
            "addContext" -> {
                ApplicationManager.getApplication().invokeLater {
                    project.service<IdeContextService>().addCurrentSelectionToContext()
                }
            }
            "addActiveFile" -> {
                ApplicationManager.getApplication().invokeLater {
                    project.service<IdeContextService>().addActiveFileToContext()
                }
            }
        }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun handleSettingChange(setting: String, value: String) {
        when (setting) {
            "mode" -> {
                println("[MiMoCode] Mode changed to: $value")
                // Store mode preference
            }
            "model" -> {
                println("[MiMoCode] Model changed to: $value")
                // Store model preference
            }
            "context" -> {
                println("[MiMoCode] Context mode changed to: $value")
                // Store context preference
            }
        }
    }

    private var eventConnection: HttpURLConnection? = null
    @Volatile
    private var eventListenerRunning = false

    private fun forwardToServer(content: String, mode: String = "chat", model: String = "mimo-auto", context: String = "default") {
        try {
            val apiPort = ApplicationManager.getApplication().getService(ServerManager::class.java).port

            // Create session if needed
            if (sessionId == null) {
                val createUrl = URL("http://localhost:$apiPort/session")
                val createConn = createUrl.openConnection() as HttpURLConnection
                createConn.requestMethod = "POST"
                createConn.setRequestProperty("Content-Type", "application/json")
                createConn.doOutput = true
                createConn.connectTimeout = 5000
                createConn.readTimeout = 10000
                OutputStreamWriter(createConn.outputStream, Charsets.UTF_8).use { it.write("{}") }
                if (createConn.responseCode in 200..299) {
                    val resp = createConn.inputStream.bufferedReader().readText()
                    val idMatch = Regex(""""(?:id|sessionID)"\s*:\s*"(ses[^"]+)"""").find(resp)
                    sessionId = idMatch?.groupValues?.get(1)
                    println("[MiMoCode] Session created: $sessionId")
                }
                createConn.disconnect()
            }

            val sid = sessionId ?: throw IllegalStateException("No session ID")

            // Start SSE event listener if not already connected
            if (!eventListenerRunning) {
                startEventListener(apiPort, sid)
            }

            // Send message via prompt_async (non-blocking, returns 204)
            val url = URL("http://localhost:$apiPort/session/$sid/prompt_async")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val body = """{"parts":[{"type":"text","text":${json(content)}}],"options":{"mode":"$mode","model":"$model","context":"$context"}}"""
            println("[MiMoCode] Sending message with mode=$mode, model=$model")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val responseCode = conn.responseCode
            conn.disconnect()

            if (responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "Unknown error"
                println("[MiMoCode] Send failed: $responseCode $error")
                sendToWebview("Error $responseCode: $error")
            } else {
                println("[MiMoCode] Message sent successfully")
            }
        } catch (e: Exception) {
            println("[MiMoCode] Send error: ${e.message}")
            sendToWebview("Error: ${e.message?.replace("\n", " ") ?: "Unknown error"}")
        }
    }

    private fun startEventListener(apiPort: Int, sessionID: String) {
        // Close existing connection if any
        eventConnection?.disconnect()
        eventListenerRunning = true

        Thread {
            var retryCount = 0
            val maxRetries = 3

            while (eventListenerRunning && retryCount < maxRetries) {
                try {
                    println("[MiMoCode] Connecting to event stream (attempt ${retryCount + 1})")
                    val url = URL("http://localhost:$apiPort/event")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 300000 // 5 minutes
                    eventConnection = conn

                    val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    val textBuilder = StringBuilder()
                    retryCount = 0 // Reset on successful connection

                    println("[MiMoCode] Event stream connected")

                    var line: String?
                    while (reader.readLine().also { line = it } != null && eventListenerRunning) {
                        val l = line ?: continue

                        if (l.startsWith("data: ")) {
                            val json = l.removePrefix("data: ").trim()
                            if (json.isEmpty()) continue

                            try {
                                val event = parseEvent(json)
                                when (event?.type) {
                                    "message.part.delta" -> {
                                        val delta = event.textDelta
                                        if (delta.isNotEmpty()) {
                                            textBuilder.append(delta)
                                            sendStreamingUpdate(textBuilder.toString())
                                        }
                                    }
                                    "message.part.updated" -> {
                                        if (event.text.isNotEmpty()) {
                                            textBuilder.clear()
                                            textBuilder.append(event.text)
                                        }
                                    }
                                    "message.updated" -> {
                                        if (event.finish == "stop") {
                                            val finalText = textBuilder.toString()
                                            sendToWebview(finalText.ifEmpty { "" })
                                            textBuilder.clear()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("[MiMoCode] Event parse error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[MiMoCode] Event stream error: ${e.message}")
                    retryCount++
                    if (retryCount < maxRetries) {
                        println("[MiMoCode] Reconnecting in 2 seconds...")
                        Thread.sleep(2000)
                    }
                }
            }
            eventListenerRunning = false
            println("[MiMoCode] Event listener stopped")
        }.apply {
            isDaemon = true
            name = "MiMoCode-EventListener"
            start()
        }
    }

    private data class ServerEvent(
        val type: String?,
        val textDelta: String = "",
        val text: String = "",
        val finish: String? = null
    )

    private fun parseEvent(json: String): ServerEvent? {
        val typeMatch = Regex(""""type"\s*:\s*"([^"]+)"""").find(json) ?: return null
        val type = typeMatch.groupValues[1]

        val textDelta = if (type == "message.part.delta") {
            Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\t", "\t")?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
        } else ""

        val text = if (type == "message.part.updated") {
            Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\t", "\t")?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
        } else ""

        val finish = if (type == "message.updated") {
            Regex(""""finish"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        } else null

        return ServerEvent(type, textDelta, text, finish)
    }

    private fun sendStreamingUpdate(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "window.dispatchEvent(new CustomEvent('mimo:ide',{detail:{type:'streaming',content:'$escaped'}}))",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun extractTextFromResponse(json: String): String {
        // Response format: { info: {...}, parts: [...] }
        // Parts can be: { type: "text", text: "..." }, { type: "tool-use", ... }, etc.
        
        // Strategy 1: Find "text" type parts with "text" field
        val textParts = Regex(""""type"\s*:\s*"text"\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json)
        val result = textParts.map { it.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        }.joinToString("\n")
        if (result.isNotEmpty()) return result
        
        // Strategy 2: Find any "text" field in parts
        val anyText = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(json)
        val fallback = anyText.map { it.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
        }.joinToString("\n")
        if (fallback.isNotEmpty()) return fallback
        
        // Strategy 3: Return a summary of what we got
        val infoMatch = Regex(""""finish"\s*:\s*"([^"]+)"""").find(json)
        val modelMatch = Regex(""""modelID"\s*:\s*"([^"]+)"""").find(json)
        val finish = infoMatch?.groupValues?.get(1) ?: "unknown"
        val model = modelMatch?.groupValues?.get(1) ?: ""
        return "(Response received, model: $model, status: $finish)"
    }

    private fun sendToWebview(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "window.dispatchEvent(new CustomEvent('mimo:ide',{detail:{type:'response',content:'$escaped'}}))",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun openFileInEditor(path: String, line: Int?) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return@invokeLater
            val desc = if (line != null) {
                OpenFileDescriptor(project, vf, line - 1, 0)
            } else {
                OpenFileDescriptor(project, vf)
            }
            FileEditorManager.getInstance(project).openTextEditor(desc, true)
        }
    }

    private fun injectQueryBridge(b: JBCefBrowser) {
        val q = query ?: return
        b.cefBrowser.executeJavaScript(
            """
            window.__sendToIDE = function(msg) {
                ${q.inject("msg")}
            };
            """.trimIndent(),
            b.cefBrowser.url,
            0
        )
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun json(s: String): String {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }

    data class TabPayload(val path: String, val name: String, val active: Boolean, val modified: Boolean)
}
