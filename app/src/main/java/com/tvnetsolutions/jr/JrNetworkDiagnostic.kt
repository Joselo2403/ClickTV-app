package com.tvnetsolutions.jr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Diagnostico integrado: lectura solamente, sin modificar DNS, Wi-Fi ni el reproductor. */
object JrNetworkDiagnostic {
    private const val HOST = "raw.githubusercontent.com"
    private const val SAMPLE = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ar.m3u"

    fun run(context: Context): String {
        val report = StringBuilder()
        report.appendLine("DIAGNÓSTICO CLICK TV")
        report.appendLine("(Solo lectura; no cambia ajustes)")
        report.appendLine()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val capabilities = active?.let { cm?.getNetworkCapabilities(it) }
        val properties = active?.let { cm?.getLinkProperties(it) }
        val connected = active != null
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        report.appendLine("1. RED ANDROID")
        report.appendLine("Red activa: ${if (connected) "SI" else "NO"}")
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Movil"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> "No identificada"
        }
        report.appendLine("Transporte: $transport")
        report.appendLine("Internet validado: ${if (validated) "SI" else "NO"}")
        report.appendLine("IP local: ${properties?.linkAddresses?.joinToString(", ") { it.address.hostAddress.orEmpty() }?.ifBlank { "Sin IP" } ?: "Sin IP"}")
        report.appendLine("DNS asignados: ${properties?.dnsServers?.joinToString(", ") { it.hostAddress.orEmpty() }?.ifBlank { "No visibles" } ?: "No visibles"}")
        report.appendLine()

        report.appendLine("2. LECTOR M3U LOCAL (SIN INTERNET)")
        val sampleM3u = "#EXTM3U\n#EXTINF:-1,Prueba JR\nhttps://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\n"
        val parsed = M3uParser.parse(sampleM3u)
        val parserOk = parsed.size == 1 && parsed[0].name == "Prueba JR"
        report.appendLine("Parser: ${if (parserOk) "CORRECTO" else "ERROR"}")
        report.appendLine()

        report.appendLine("3. DNS DESDE ESTA APLICACION")
        val dns = runCatching { InetAddress.getAllByName(HOST).map { it.hostAddress.orEmpty() } }
        val dnsOk = dns.isSuccess && dns.getOrNull()?.isNotEmpty() == true
        if (dnsOk) report.appendLine("$HOST: ${dns.getOrNull()?.take(3)?.joinToString(", ")}")
        else report.appendLine("$HOST: ERROR (${brief(dns.exceptionOrNull())})")
        report.appendLine()

        report.appendLine("4. DESCARGA HTTP(S) DESDE ESTA APLICACION")
        var httpOk = false
        if (!dnsOk) {
            report.appendLine("Sin prueba HTTPS: DNS no resuelto.")
        } else {
            val result = runCatching {
                val conn = URL(SAMPLE).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 6500
                    conn.readTimeout = 6500
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "JR-Intercom-Player/002")
                    val code = conn.responseCode
                    if (code !in 200..299) "HTTP $code"
                    else {
                        val head = conn.inputStream.use { input ->
                            val bytes = ByteArray(16)
                            val count = input.read(bytes)
                            if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else ""
                        }
                        httpOk = head.startsWith("#EXTM3U")
                        "HTTP $code; contenido M3U: ${if (httpOk) "SI" else "NO"}"
                    }
                } finally { conn.disconnect() }
            }
            report.appendLine(result.getOrElse { "ERROR (${brief(it)})" })
        }
        report.appendLine()
        report.appendLine("5. RESULTADO ORIENTATIVO")
        val summary = when {
            !connected -> "Android no tiene una red activa. Revisar configuracion del TV Stick."
            !validated -> "Android no confirma salida a Internet. Revisar conectividad, puerta de enlace o DNS."
            !parserOk -> "Error del lector M3U. Revisar configuración de Click TV."
            !dnsOk -> "La red figura validada, pero DNS falla desde Click TV. Revisar DNS/red y comparar con otras apps."
            !httpOk -> "DNS funciona, pero la lista no se descarga. Revisar acceso HTTPS/servidor."
            else -> "Red, DNS, HTTPS y parser operativos en Click TV. Si falla un canal, revisar su URL o decodificación."
        }
        report.appendLine(summary)
        return report.toString()
    }

    private fun brief(error: Throwable?): String {
        if (error == null) return "desconocido"
        val klass = error.javaClass.simpleName
        val detail = error.message.orEmpty().replace('\n', ' ').take(120)
        return if (detail.isBlank()) klass else "$klass: $detail"
    }
}
