package br.com.plusapps.s23logvideo.remote

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** O que o servidor precisa do app. As chamadas chegam em threads de rede. */
interface RemoteApi {
    fun stateJson(): JSONObject
    fun command(cmd: JSONObject): JSONObject

    /** Último JPEG do preview (já com LUT/false color etc.) e um contador que muda a cada quadro novo. */
    fun latestJpeg(): Pair<ByteArray, Long>?

    /** Liga/desliga a produção de quadros JPEG (só enquanto houver alguém assistindo). */
    fun setStreaming(on: Boolean)
}

/**
 * Servidor HTTP mínimo (sem dependências) para controlar a câmera de outro aparelho:
 *  - `GET /`               página de controle (assets/web/index.html)
 *  - `GET /api/state?k=`   estado em JSON
 *  - `POST /api/cmd?k=`    comando em JSON (ver CameraViewModel.remoteCommand)
 *  - `GET /preview.mjpeg?k=` preview ao vivo (multipart MJPEG, ~10 fps)
 * Todas as rotas da API exigem o PIN em `k` (ou no cabeçalho X-Key).
 */
class RemoteServer(private val context: Context, private val api: RemoteApi, val pin: String) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val viewers = AtomicInteger(0)

    @Volatile var port = 0
        private set

    val running get() = server?.isClosed == false

    fun start(preferredPort: Int = 8080) {
        if (running) return
        val s = (preferredPort until preferredPort + 10).firstNotNullOfOrNull { p ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } }.getOrNull()
        } ?: error("nenhuma porta livre entre $preferredPort e ${preferredPort + 9}")
        server = s
        port = s.localPort
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(c) }
            }
        }, "remote-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        api.setStreaming(false)
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            runCatching {
                s.soTimeout = 15_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = readRequest(input) ?: return
                val authed = req.query["k"] == pin || req.headers["x-key"] == pin
                when {
                    req.path == "/" || req.path == "/index.html" ->
                        send(out, 200, "text/html; charset=utf-8", page())
                    req.path == "/favicon.ico" -> send(out, 404, "text/plain", "".toByteArray())
                    !authed -> send(out, 401, "application/json", """{"erro":"PIN inválido"}""".toByteArray())
                    req.path == "/api/state" -> sendJson(out, api.stateJson())
                    req.path == "/api/cmd" && req.method == "POST" -> {
                        val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
                        if (body == null) send(out, 400, "application/json", """{"erro":"JSON inválido"}""".toByteArray())
                        else sendJson(out, api.command(body))
                    }
                    req.path == "/preview.mjpeg" -> stream(s, out)
                    req.path == "/preview.jpg" -> {
                        val f = api.latestJpeg()
                        if (f == null) send(out, 503, "text/plain", "sem quadro".toByteArray())
                        else send(out, 200, "image/jpeg", f.first)
                    }
                    else -> send(out, 404, "text/plain", "não encontrado".toByteArray())
                }
            }
        }
    }

    /** MJPEG: manda cada quadro novo até o cliente fechar. */
    private fun stream(s: Socket, out: OutputStream) {
        if (viewers.incrementAndGet() == 1) api.setStreaming(true)
        try {
            s.soTimeout = 0
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").toByteArray()
            )
            var last = -1L
            var idle = 0
            while (!s.isClosed) {
                val f = api.latestJpeg()
                if (f == null || f.second == last) {
                    Thread.sleep(15)
                    // Sem quadro novo por muito tempo (câmera fechada): manda o último de novo para manter viva.
                    if (++idle < 200 || f == null) continue
                }
                idle = 0
                last = f!!.second
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.first.size}\r\n\r\n".toByteArray())
                out.write(f.first)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            if (viewers.decrementAndGet() == 0) api.setStreaming(false)
        }
    }

    private fun page(): ByteArray =
        runCatching { context.assets.open("web/index.html").use { it.readBytes() } }
            .getOrDefault("<h1>LogCam</h1><p>Página não encontrada nos assets.</p>".toByteArray())

    private fun sendJson(out: OutputStream, o: JSONObject) =
        send(out, 200, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private class Request(
        val method: String, val path: String, val query: Map<String, String>,
        val headers: Map<String, String>, val body: ByteArray,
    )

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = try { input.read() } catch (_: SocketTimeoutException) { return null }
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val q = target.substringAfter('?', "")
        val query = q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0], target.substringBefore('?'), query, headers, body)
    }

    companion object {
        /** Endereços IPv4 do aparelho, com o nome da interface (wlan0 = Wi-Fi; swlan0/ap0/wlan1 = hotspot). */
        fun addresses(): List<Pair<String, String>> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress!! }
            }
        }.getOrDefault(emptyList())

        fun newPin(): String = (1000..9999).random().toString()
    }
}
