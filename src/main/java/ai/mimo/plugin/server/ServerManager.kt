package ai.mimo.plugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets

class ServerManager : Disposable {

    private var process: Process? = null
    var port: Int = -1
        private set

    private val lock: Any = Any()

    fun isRunning(): Boolean = process?.isAlive == true && port > 0

    override fun dispose() {
        process?.destroyForcibly()
        process = null
    }

    fun start(): Int {
        synchronized(lock) {
            if (isRunning()) return port

            val existing = readLock()
            if (existing != null && isHealthy(existing.port)) {
                port = existing.port
                return port
            }

            val bin = resolvedBin()
            port = freePort()

            LOG.info("Starting mimocode binary=$bin port=$port")

            val pb = ProcessBuilder(bin, "serve", "--port", port.toString())
                .directory(File(System.getProperty("user.home")))
                .redirectErrorStream(true)

            process = pb.start()

            Thread {
                BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))
                    .lines()
                    .forEach { LOG.debug("[mimo] $it") }
            }.apply {
                isDaemon = true
                start()
            }

            waitUntilHealthy(port, 15_000)
            writeLock(port)
            return port
        }
    }

    private fun resolvedBin(): String {
        extractBundled()?.let { return it }

        val home = System.getProperty("user.home")
        val exeExt = if (SystemInfo.isWindows) ".exe" else ""
        val candidates = listOf(
            "$home/.mimocode/bin/mimo$exeExt",
            "$home/.local/bin/mimo$exeExt",
            "/usr/local/bin/mimo$exeExt",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }

        val pathEnv = System.getenv("PATH") ?: ""
        val pathDirs = pathEnv.split(File.pathSeparator)
        for (dir in pathDirs) {
            val exeName = if (SystemInfo.isWindows) "mimo.exe" else "mimo"
            val f = File(dir, exeName)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }

        throw IllegalStateException("mimo binary not found. Cannot start server. Please install MiMoCode: npm install -g @mimo-ai/cli")
    }

    private fun extractBundled(): String? {
        val resource = javaClass.getResourceAsStream("/bin/mimo") ?: return null
        val dest = File(System.getProperty("user.home"), ".mimocode-plugin/bin/mimo")
        dest.parentFile.mkdirs()
        if (!dest.exists()) {
            LOG.info("Extracting bundled mimocode binary to ${dest.absolutePath}")
            resource.use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out)
                }
            }
            dest.setExecutable(true)
        } else {
            resource.close()
        }
        return dest.absolutePath
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun lockFile(): File {
        val dir = File(System.getProperty("user.home"), ".mimocode-plugin")
        dir.mkdirs()
        return File(dir, "server.json")
    }

    private data class Lock(val port: Int, val pid: Long)

    private fun readLock(): Lock? {
        val file = lockFile()
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val portMatch = Regex(""""port"\s*:\s*(\d+)""").find(text) ?: return null
            val pidMatch = Regex(""""pid"\s*:\s*(\d+)""").find(text) ?: return null
            Lock(
                port = portMatch.groupValues[1].toInt(),
                pid = pidMatch.groupValues[1].toLong(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLock(port: Int) {
        val pid = ProcessHandle.current().pid()
        lockFile().writeText("""{"port":$port,"pid":$pid}""")
    }

    private fun isHealthy(port: Int): Boolean {
        return try {
            val conn = URI("http://localhost:$port/").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun waitUntilHealthy(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URI("http://localhost:$port/").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                if (code > 0) return
            } catch (_: Exception) {
                Thread.sleep(300)
            }
        }
        LOG.warn("mimocode server did not become healthy within ${timeoutMs}ms")
    }

    companion object {
        private val LOG = Logger.getInstance(ServerManager::class.java)
    }
}
