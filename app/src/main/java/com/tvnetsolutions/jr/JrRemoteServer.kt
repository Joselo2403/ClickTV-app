package com.tvnetsolutions.jr

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Panel LAN: emparejamiento físico temporal; credenciales persistentes por teléfono.
 *  NO publica el control en Internet ni usa nube. Usar sólo en redes locales confiables.
 */
class JrRemoteServer(
    private val context: Context,
    private val onCommand: (action: String, number: Int?) -> Unit,
    private val onConnected: () -> Unit,
    private val getState: () -> JrRemoteSnapshot,
    // JR.009: el celular autorizado puede cargar la dirección de la lista M3U.
    private val onPlaylist: (String) -> Unit = {}
) {
    data class Phone(val id: String, val name: String, val created: Long)
    private val lock = Any()
    private val prefs = context.getSharedPreferences("jr_remote_auth_v1", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val acceptor = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var listener: ServerSocket? = null
    @Volatile var running = false
        private set
    @Volatile var error: String? = null
        private set
    private var pairing: String? = null
    private var pairingExpiry = 0L

    companion object {
        const val PORT = 8096
        private const val MAX_PHONES = 10
        private const val PAIR_MS = 10 * 60 * 1000L
    }

    fun start() {
        acceptor.execute {
            try {
                val server = ServerSocket(PORT, 12)
                synchronized(lock) { listener = server }
                running = true
                while (!server.isClosed && !Thread.currentThread().isInterrupted) {
                    val socket = try { server.accept() } catch (e: SocketException) { break }
                    try { workers.execute { handle(socket) } }
                    catch (_: Exception) { socket.close() }
                }
            } catch (e: Exception) {
                error = "No se pudo abrir el puerto $PORT: ${e.message}"
                Log.w("JR_Remote", error ?: "Error del servidor", e)
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        endPairing()
        try { synchronized(lock) { listener?.close(); listener = null } } catch (_: Exception) {}
        acceptor.shutdownNow()
        workers.shutdownNow()
    }

    fun lanAddress(): String? {
        // IP del enlace activo: evita elegir una interfaz p2p/AP secundaria.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val direct = active?.let { cm?.getLinkProperties(it) }?.linkAddresses?.mapNotNull {
            it.address as? Inet4Address
        }?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        if (direct != null) return direct.hostAddress
        return runCatching {
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement()
                if (!nic.isUp || nic.isLoopback) continue
                val n = nic.name.lowercase()
                if (n.startsWith("p2p") || n.startsWith("ap") || n.startsWith("dummy")) continue
                val priority = when {
                    n.startsWith("wlan") || n.startsWith("wifi") -> 0
                    n.startsWith("eth") || n.startsWith("en") -> 1
                    else -> 2
                }
                for (a in nic.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) {
                    candidates.add(priority to a.hostAddress.orEmpty())
                }
            }
            candidates.sortedBy { it.first }.firstOrNull()?.second
        }.getOrNull()
    }

    fun baseUrl(): String? = lanAddress()?.let { "http://$it:$PORT/" }

    fun beginPairing(): String? {
        val base = baseUrl() ?: return null
        synchronized(lock) {
            pairing = token(24)
            pairingExpiry = System.currentTimeMillis() + PAIR_MS
            return base + "#pair=" + pairing
        }
    }

    fun endPairing() = synchronized(lock) { pairing = null; pairingExpiry = 0L }

    fun phones(): List<Phone> = synchronized(lock) {
        val list = readPhones()
        (0 until list.length()).mapNotNull { i ->
            runCatching {
                val p = list.getJSONObject(i)
                Phone(p.getString("id"), p.optString("name", "Celular"), p.optLong("created"))
            }.getOrNull()
        }
    }

    fun revoke(id: String) = synchronized(lock) {
        val current = readPhones()
        val next = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.getJSONObject(i)
            if (item.optString("id") != id) next.put(item)
        }
        prefs.edit().putString("phones", next.toString()).commit()
    }

    private fun token(nBytes: Int): String {
        val bytes = ByteArray(nBytes)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun readPhones(): JSONArray = runCatching {
        JSONArray(prefs.getString("phones", "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    private fun authorized(cookie: String?): Boolean {
        val credential = cookie?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("jr_session=") }?.substringAfter('=') ?: return false
        if (!credential.matches(Regex("[a-f0-9]{64}"))) return false
        val digest = hash(credential)
        return synchronized(lock) {
            val items = readPhones()
            (0 until items.length()).any { items.getJSONObject(it).optString("id") == digest }
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readLine(input: BufferedInputStream, limit: Int = 8192): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < limit) {
            val c = input.read()
            if (c == -1) return null
            if (c == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(c)
        }
        return null
    }

    private fun parse(socket: Socket): Request? {
        val inp = BufferedInputStream(socket.getInputStream())
        val first = readLine(inp, 3000)?.split(' ') ?: return null
        if (first.size != 3 || first[0] !in listOf("GET", "POST") || !first[2].startsWith("HTTP/1.")) return null
        val headers = mutableMapOf<String, String>()
        var size = 0
        repeat(50) {
            val line = readLine(inp, 4000) ?: return null
            size += line.length
            if (size > 12000) return null
            if (line.isEmpty()) {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len < 0 || len > 4096) return null
                val bytes = ByteArray(len)
                var offset = 0
                while (offset < len) {
                    val n = inp.read(bytes, offset, len - offset)
                    if (n <= 0) return null
                    offset += n
                }
                return Request(first[0], first[1].substringBefore('?'), headers, String(bytes, Charsets.UTF_8))
            }
            val split = line.indexOf(':')
            if (split <= 0) return null
            headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
        }
        return null
    }

    private fun params(raw: String): Map<String, String> = raw.split('&').mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx < 0) null else runCatching {
            URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.getOrNull()
    }.toMap()

    private fun respond(socket: Socket, status: String, type: String, data: ByteArray, cookie: String? = null) {
        val out = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${data.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'\r\n")
            if (cookie != null) append("Set-Cookie: jr_session=$cookie; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun reply(socket: Socket, status: String, message: String, cookie: String? = null) =
        respond(socket, status, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8), cookie)

    private fun json(socket: Socket, json: JSONObject) = respond(socket, "200 OK", "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 5000
                // No atender solicitudes que vengan desde redes públicas.
                if (!it.inetAddress.isSiteLocalAddress) { reply(it, "403 Forbidden", "Fuera de red local"); return }
                val req = parse(it) ?: run { reply(it, "400 Bad Request", "Solicitud inválida"); return }
                val host = req.headers["host"] ?: ""
                val own = lanAddress()
                if (own == null || host != "$own:$PORT") { reply(it, "403 Forbidden", "Host no permitido"); return }
                if (req.method == "POST" && req.headers["origin"] != "http://$own:$PORT") {
                    reply(it, "403 Forbidden", "Origen no permitido"); return
                }
                if (req.method == "GET" && (req.path == "/" || req.path == "/remote.js" || req.path == "/remote.css")) {
                    val asset = when(req.path) { "/remote.js" -> "remote.js"; "/remote.css" -> "remote.css"; else -> "remote.html" }
                    val mime = when(asset) { "remote.js" -> "application/javascript"; "remote.css" -> "text/css"; else -> "text/html" }
                    val bytes = context.assets.open(asset).use { assetStream -> assetStream.readBytes() }
                    respond(it, "200 OK", "$mime; charset=utf-8", bytes)
                    return
                }
                if (req.method == "POST" && req.path == "/api/pair") {
                    if (req.headers["content-type"]?.startsWith("application/x-www-form-urlencoded") != true) {
                        reply(it, "415 Unsupported Media Type", "Formato inválido"); return
                    }
                    val p = params(req.body)
                    val provided = p["token"].orEmpty()
                    val name = (p["name"] ?: "Celular").trim().take(40).ifBlank { "Celular" }
                    val session = synchronized(lock) {
                        if (pairing == null || System.currentTimeMillis() >= pairingExpiry ||
                            !MessageDigest.isEqual(pairing!!.toByteArray(), provided.toByteArray())) null
                        else {
                            val list = readPhones()
                            if (list.length() >= MAX_PHONES) null else {
                                val value = token(32)
                                list.put(JSONObject().put("id", hash(value)).put("name", name).put("created", System.currentTimeMillis()))
                                if (!prefs.edit().putString("phones", list.toString()).commit()) null
                                else { pairing = null; pairingExpiry = 0; value }
                            }
                        }
                    }
                    if (session == null) { reply(it, "403 Forbidden", "QR vencido, inválido o límite de celulares alcanzado"); return }
                    reply(it, "200 OK", "Celular vinculado", session)
                    main.post { onConnected() }
                    return
                }
                if (!authorized(req.headers["cookie"])) { reply(it, "401 Unauthorized", "Celular no autorizado"); return }
                if (req.method == "GET" && req.path == "/api/state") {
                    val snapshot = getState()
                    json(it, JSONObject()
                        .put("channel", snapshot.channel)
                        .put("channels", JSONArray(snapshot.channels))
                        .put("playing", snapshot.playing)
                        .put("seekable", snapshot.seekable)
                        .put("live", snapshot.live)
                        .put("volume", snapshot.volume))
                    return
                }
                if (req.method == "POST" && req.path == "/api/cmd") {
                    if (req.headers["content-type"]?.startsWith("application/x-www-form-urlencoded") != true) {
                        reply(it, "415 Unsupported Media Type", "Formato inválido"); return
                    }
                    val p = params(req.body)
                    val action = p["action"].orEmpty()
                    val allowed = setOf("next", "prev", "pause", "back", "forward", "live", "volup", "voldown", "mute", "channel")
                    val number = p["number"]?.toIntOrNull()
                    if (action !in allowed || (action == "channel" && (number == null || number !in 0 until M3uParser.MAX_CHANNELS))) {
                        reply(it, "400 Bad Request", "Comando inválido"); return
                    }
                    main.post { onCommand(action, number) }
                    reply(it, "200 OK", "OK")
                    return
                }
                if (req.method == "POST" && req.path == "/api/playlist") {
                    if (req.headers["content-type"]?.startsWith("application/x-www-form-urlencoded") != true) {
                        reply(it, "415 Unsupported Media Type", "Formato inválido"); return
                    }
                    val url = params(req.body)["url"].orEmpty().trim()
                    val valid = url.length in 10..600 && runCatching {
                        val parsed = java.net.URL(url)
                        (parsed.protocol == "http" || parsed.protocol == "https") && parsed.host.isNotBlank()
                    }.getOrDefault(false)
                    if (!valid) { reply(it, "400 Bad Request", "Dirección inválida: tiene que empezar con http:// o https://"); return }
                    main.post { onPlaylist(url) }
                    reply(it, "200 OK", "Lista enviada a la TV. Descargando canales…")
                    return
                }
                reply(it, "404 Not Found", "No encontrado")
            } catch (e: Exception) {
                Log.w("JR_Remote", "Solicitud fallida", e)
            }
        }
    }
}

data class JrRemoteSnapshot(
    val channels: List<String>,
    val channel: Int,
    val playing: Boolean,
    val seekable: Boolean,
    val live: Boolean,
    val volume: Int
)
