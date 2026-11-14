package com.tvnetsolutions.jr

import java.net.URI

/** Parser aislado de Android para poder probarlo con kotlinc. Nunca altera una lista válida guardada. */
data class TvChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val kind: StreamKind = StreamKind.HLS
)
// JR.009: HTTP_TS = MPEG-TS por HTTP (.ts). AUTO = dirección sin extensión:
// se prueba primero como transmisión directa (TS/MP4) y, si no, como HLS.
enum class StreamKind { HLS, MP4, UDP_TS, HTTP_TS, AUTO, RTP_UNSUPPORTED }

object M3uParser {
    // JR.009: hasta 99 canales (el control numérico admite 2 cifras).
    const val MAX_CHANNELS = 99
    private val attr = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")

    fun parse(content: String, origin: String? = null): List<TvChannel> {
        if (content.length > 1_048_576) return emptyList()
        val channels = mutableListOf<TvChannel>()
        var title = ""
        var logo = ""
        var group = ""
        var typeHint = ""
        val seen = HashSet<String>()
        for (raw in content.trimStart('\uFEFF').lineSequence()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val fields = attr.findAll(line.substringBeforeLast(',', line)).associate {
                    it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } })
                }
                title = line.substringAfter(',', "").trim().ifBlank { fields["tvg-name"].orEmpty() }
                logo = fields["tvg-logo"].orEmpty()
                group = fields["group-title"].orEmpty()
                typeHint = fields["type"].orEmpty()
                continue
            }
            if (line.startsWith("#EXTGRP", ignoreCase = true)) {
                group = line.substringAfter(':', "").trim()
                continue
            }
            if (line.startsWith('#')) continue
            val normalized = resolve(origin, line)
            val lower = normalized.lowercase()
            val schemeOk = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("udp://") || lower.startsWith("rtp://")
            if (schemeOk && seen.add(normalized)) {
                val kind = when {
                    lower.startsWith("rtp://") -> StreamKind.RTP_UNSUPPORTED
                    lower.startsWith("udp://") -> StreamKind.UDP_TS
                    typeHint.equals("mp4", true) || lower.substringBefore('?').endsWith(".mp4") -> StreamKind.MP4
                    typeHint.equals("ts", true) || lower.substringBefore('?').endsWith(".ts") -> StreamKind.HTTP_TS
                    typeHint.equals("hls", true) || typeHint.equals("m3u8", true) || lower.contains(".m3u8") -> StreamKind.HLS
                    else -> StreamKind.AUTO
                }
                channels.add(TvChannel(title.ifBlank { "Canal ${channels.size + 1}" }.take(80), normalized, resolve(origin, logo), group.take(50), kind))
                if (channels.size >= MAX_CHANNELS) break
            }
            title = ""; logo = ""; group = ""; typeHint = ""
        }
        return channels
    }

    private fun resolve(origin: String?, value: String): String {
        val input = value.trim()
        if (input.isBlank()) return ""
        return runCatching {
            val uri = URI(input)
            if (uri.isAbsolute || origin.isNullOrBlank()) input
            else URI(origin).resolve(uri).toASCIIString()
        }.getOrDefault(input)
    }
}
