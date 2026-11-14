package com.tvnetsolutions.jr

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * HTTP DataSource that keeps Set-Cookie values across every request made by one HLS session.
 *
 * MediaMTX can return a master playlist that creates session-specific child playlists. Some
 * servers also require the cookie set while resolving the master to be sent again when ExoPlayer
 * requests audio/video playlists and media segments. DefaultHttpDataSource follows redirects but
 * does not act as a browser cookie jar, so JR keeps the small cookie set explicitly.
 */
@UnstableApi
class HlsCookieJar {
    private val values = LinkedHashMap<String, String>()

    @Synchronized fun clear() { values.clear() }

    @Synchronized fun seed(header: String) {
        if (header.isBlank()) return
        header.split(';').forEach { raw ->
            val pair = raw.trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isNotBlank()) values[name] = value
            }
        }
    }

    @Synchronized fun collect(headers: Map<String, List<String>>) {
        headers.forEach { (name, list) ->
            if (!name.equals("Set-Cookie", ignoreCase = true)) return@forEach
            list.forEach { raw ->
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val cookieName = pair.substring(0, eq).trim()
                    val cookieValue = pair.substring(eq + 1).trim()
                    if (cookieName.isNotBlank()) values[cookieName] = cookieValue
                }
            }
        }
    }

    @Synchronized fun header(): String = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

@UnstableApi
class CookieAwareHttpDataSourceFactory(
    private val base: DefaultHttpDataSource.Factory,
    private val jar: HlsCookieJar
) : DataSource.Factory {
    override fun createDataSource(): DataSource = CookieAwareHttpDataSource(base.createDataSource(), jar)
}

/**
 * JR prioriza estabilidad sobre latencia mínima. Algunos servidores anuncian LL-HLS pero
 * responden 404 a las recargas bloqueantes (_HLS_msn/_HLS_part/_HLS_skip). En esas solicitudes
 * conservamos cualquier parámetro normal (por ejemplo session=) y quitamos sólo los de LL-HLS,
 * forzando una recarga HLS clásica de la misma playlist.
 */
private fun classicHlsUri(uri: Uri): Uri {
    val names = runCatching { uri.queryParameterNames }.getOrDefault(emptySet<String>())
    if (names.none { it.startsWith("_HLS_") }) return uri
    val builder = uri.buildUpon().clearQuery()
    names.filterNot { it.startsWith("_HLS_") }.forEach { name ->
        uri.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
    }
    return builder.build()
}

@UnstableApi
private class CookieAwareHttpDataSource(
    private val delegate: DefaultHttpDataSource,
    private val jar: HlsCookieJar
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val cookie = jar.header()
        if (cookie.isNotBlank()) delegate.setRequestProperty("Cookie", cookie)
        val originalUri = dataSpec.uri
        val normalizedUri = classicHlsUri(originalUri)
        val effectiveSpec = if (normalizedUri == originalUri) dataSpec else dataSpec.withUri(normalizedUri)
        return try {
            val length = delegate.open(effectiveSpec)
            val headers = delegate.responseHeaders
            jar.collect(headers)
            length
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            jar.collect(e.headerFields)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun close() = delegate.close()
}
