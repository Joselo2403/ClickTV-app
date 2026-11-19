package com.tvnetsolutions.jr

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-actualizacion de Click TV.
 * Consulta un version.json en el hosting de TVNet; si hay un versionCode mayor
 * al instalado, baja el APK y lo instala (silencioso por root en cajas Kanji,
 * con respaldo via PackageInstaller). Nunca interrumpe la reproduccion: todo
 * corre en segundo plano y cualquier error solo se registra en el log.
 */
object Updater {
    private const val TAG = "JR_Update"
    private val VERSION_URLS = listOf(
        "https://tvnetsolutions.com/clicktv-update/version.json",
        "https://www.tvnetsolutions.com/clicktv-update/version.json"
    )
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000  // cada 6 horas
    private val running = AtomicBoolean(false)

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            try { Thread.sleep(40_000L) } catch (e: InterruptedException) { return@Thread }
            while (true) {
                runCatching { checkOnce(app) }.onFailure { Log.w(TAG, "ciclo de actualizacion", it) }
                try { Thread.sleep(INTERVAL_MS) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "jr-updater" }.start()
    }

    private fun checkOnce(context: Context) {
        val json = fetchVersionJson() ?: return
        val remote = json.optInt("versionCode", -1)
        val current = BuildConfig.VERSION_CODE
        if (remote <= current) { Log.i(TAG, "Al dia (instalada=$current, servidor=$remote)"); return }
        val apkUrl = json.optString("apkUrl", "").trim()
        if (apkUrl.isBlank()) { Log.w(TAG, "version.json sin apkUrl"); return }
        val sha = json.optString("sha256", "").trim()
        Log.i(TAG, "Nueva version $remote > $current. Descargando $apkUrl")
        val apk = download(context, apkUrl) ?: return
        if (sha.isNotEmpty() && !sha.equals(sha256(apk), ignoreCase = true)) {
            Log.w(TAG, "SHA256 no coincide; se descarta"); apk.delete(); return
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (info == null || info.packageName != context.packageName) {
            Log.w(TAG, "APK invalido o de otro paquete; se descarta"); apk.delete(); return
        }
        Log.i(TAG, "Instalando actualizacion...")
        install(context, apk)
    }

    private fun fetchVersionJson(): JSONObject? {
        // Se agrega un parametro unico (cache-buster) para saltear cualquier
        // cache o CDN del hosting y recibir siempre el version.json mas nuevo.
        val bust = System.currentTimeMillis()
        for (u in VERSION_URLS) {
            val sep = if (u.contains("?")) "&" else "?"
            val txt = runCatching { httpGet("$u${sep}t=$bust") }.getOrNull()
            if (!txt.isNullOrBlank()) runCatching { return JSONObject(txt) }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 7000; c.readTimeout = 7000
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(context: Context, url: String): File? {
        val out = File(context.cacheDir, "clicktv_update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10000; c.readTimeout = 30000
            if (c.responseCode !in 200..299) return null
            out.outputStream().use { o -> c.inputStream.use { it.copyTo(o) } }
            if (out.length() < 100_000L) { out.delete(); null } else out
        } catch (e: Exception) { Log.w(TAG, "descarga", e); out.delete(); null }
        finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(8192)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(context: Context, apk: File) {
        if (tryRootInstall(apk)) { Log.i(TAG, "Actualizacion instalada (root)"); return }
        runCatching { packageInstaller(context, apk) }
            .onFailure { Log.w(TAG, "PackageInstaller fallo", it) }
    }

    private fun tryRootInstall(apk: File): Boolean = try {
        val tmp = "/data/local/tmp/clicktv_update.apk"
        val cmd = "cp '${apk.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp"
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        Log.i(TAG, "root install -> $out $err")
        (out + err).contains("Success", ignoreCase = true)
    } catch (e: Exception) { Log.w(TAG, "sin root o fallo root", e); false }

    private fun packageInstaller(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("clicktv", 0, apk.length()).use { o ->
                    input.copyTo(o); s.fsync(o)
                }
            }
            val intent = android.content.Intent(context, MainActivity::class.java)
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val pending = android.app.PendingIntent.getActivity(context, 0, intent, flags)
            s.commit(pending.intentSender)
        }
    }
}
