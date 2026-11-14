package com.tvnetsolutions.jr

import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/** Lista cacheada para operar incluso si el servidor de la M3U no responde. */
class PlaylistRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("jr_channels", Context.MODE_PRIVATE)
    val url: String get() = prefs.getString("playlist_url", "").orEmpty()
    var lastIndex: Int
        get() = prefs.getInt("last_index", 0)
        set(value) { prefs.edit().putInt("last_index", value).apply() }

    // 'last': recuperar el último canal visto; 'fixed': abrir un canal elegido por URL.
    val startupMode: String get() = prefs.getString("start_mode", "last").orEmpty()
    val fixedChannelName: String get() = prefs.getString("fixed_channel_name", "").orEmpty()
    val fixedChannelUrl: String get() = prefs.getString("fixed_channel_url", "").orEmpty()

    fun useLastChannelOnStartup() {
        prefs.edit().putString("start_mode", "last").apply()
    }

    fun useFixedChannelOnStartup(channel: TvChannel) {
        prefs.edit()
            .putString("start_mode", "fixed")
            .putString("fixed_channel_url", channel.url)
            .putString("fixed_channel_name", channel.name)
            .apply()
    }

    fun startupIndex(channels: List<TvChannel>): Int {
        if (channels.isEmpty()) return 0
        if (startupMode == "fixed" && fixedChannelUrl.isNotEmpty()) {
            val fixed = channels.indexOfFirst { it.url == fixedChannelUrl }
            if (fixed >= 0) return fixed
        }
        return lastIndex.coerceIn(0, channels.lastIndex)
    }

    fun setUrl(newUrl: String) {
        prefs.edit().putString("playlist_url", newUrl.trim()).apply()
    }

    fun cached(): List<TvChannel> {
        val file = context.getFileStreamPath("canales.m3u")
        if (!file.exists()) return emptyList()
        return runCatching {
            M3uParser.parse(file.readText(Charsets.UTF_8), prefs.getString("playlist_origin", null))
        }.getOrDefault(emptyList())
    }

    fun install(text: String, origin: String?): List<TvChannel> {
        val channels = M3uParser.parse(text, origin)
        if (channels.isEmpty()) throw IllegalArgumentException("La lista no contiene canales HLS/MP4/UDP válidos")
        context.openFileOutput("canales.m3u", Context.MODE_PRIVATE).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        prefs.edit().putString("playlist_origin", origin).apply()
        return channels
    }

    fun download(): List<TvChannel> {
        val remote = url
        require(remote.startsWith("https://", true) || remote.startsWith("http://", true)) { "Ingresá una URL HTTP o HTTPS de una lista M3U" }
        val connection = URL(remote).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain,*/*")
            connection.setRequestProperty("User-Agent", "JR-Intercom-Player/002")
            if (connection.responseCode !in 200..299) throw IllegalStateException("Servidor M3U: HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) throw IllegalArgumentException("La lista supera 1 MB")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return install(String(bytes, Charsets.UTF_8), remote)
        } finally { connection.disconnect() }
    }

    fun import(uri: Uri): List<TvChannel> {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > 1_048_576) throw IllegalArgumentException("El archivo supera 1 MB")
                out.write(buffer, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } ?: throw IllegalArgumentException("No se pudo abrir el archivo")
        return install(text, null)
    }
}
