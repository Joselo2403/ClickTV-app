package com.tvnetsolutions.jr

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import androidx.media3.common.C
import androidx.media3.common.Player
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import kotlin.math.min
import android.util.Log

@UnstableApi
class MainActivity : Activity() {
    private val sampleUrl = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ar.m3u"
    private val accent = Color.rgb(61, 209, 199)
    private val appVersion = BuildConfig.VERSION_NAME
    private val dark = Color.rgb(13, 23, 31)
    private val panel = Color.rgb(23, 38, 47)
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val diagnostics = Executors.newSingleThreadExecutor()
    private val images = Executors.newFixedThreadPool(3)
    private val logos = LruCache<String, Bitmap>(48)
    private lateinit var root: FrameLayout
    private var splash: View? = null
    private var sparkles: View? = null
    private val dismissSplash = Runnable { removeSplash() }
    private lateinit var repo: PlaylistRepository
    private lateinit var engine: JrEngine
    private lateinit var remote: JrRemoteServer
    private var pairingDialog: AlertDialog? = null
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var welcome: LinearLayout
    private lateinit var guide: LinearLayout
    private lateinit var grid: GridView
    private lateinit var guideTitle: TextView
    private lateinit var guideChoice: TextView
    private var channels = emptyList<TvChannel>()
    private var index = 0
    private var guideOpen = false
    private var guideCursor = 0
    private var opened = false
    private var bufferStarted = 0L
    private var retryCount = 0
    private var retryPending = false
    private var numeric = ""
    // Click TV 012: gesto aislado; ninguna pulsación individual de MENU abre ajustes.
    private val technicalMenuGesture = TechnicalMenuGesture()
    // Sólo cuando no hay canales y existe URL, reintentar sin exponer el panel técnico.
    private val retryEmptyPlaylist = object : Runnable {
        override fun run() {
            if (!opened) return
            if (channels.isEmpty() && repo.url.isNotBlank()) refreshList(false)
            main.postDelayed(this, 60_000L)
        }
    }
    private val retry = Runnable { retryPending = false; if (channels.isNotEmpty()) playCurrent(false) }
    private val hideInfo = Runnable { if (!guideOpen && channels.isNotEmpty()) info.visibility = View.GONE }
    private val hideStatus = Runnable { if (channels.isNotEmpty() && !guideOpen) status.visibility = View.GONE }
    private val clearNumeric = Runnable {
        val n = numeric.toIntOrNull()
        numeric = ""
        if (n != null && n in 1..channels.size) switchTo(n - 1)
        else if (n != null) toast("Canal $n no disponible")
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (opened && !retryPending && channels.isNotEmpty() && engine.player.playWhenReady && bufferStarted > 0L &&
                SystemClock.elapsedRealtime() - bufferStarted > 25_000L) {
                failure("Tiempo de espera agotado")
            }
            if (opened) main.postDelayed(this, 5_000L)
        }
    }
    private val syncEvery15m = object : Runnable {
        override fun run() {
            if (!opened) return
            if (repo.url.isNotBlank()) refreshList(false)
            main.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        repo = PlaylistRepository(this)
        buildUi()
        showSplash()
        engine = JrEngine(this, { state -> runOnUiThread { playerStatus(state) } },
            { reason -> runOnUiThread { failure(reason) } },
            { main.post {
                if (!opened) return@post
                if (channels.getOrNull(index)?.kind == StreamKind.MP4) playCurrent(false)
                else failure("Terminó la transmisión")
            } })
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = engine.attach(h.surface)
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(h: SurfaceHolder) = engine.attach(null)
        })
        opened = true
        // Un servicio LAN pequeño, separado del motor HLS/MP4 y de la guía.
        // Las credenciales quedan en SharedPreferences aunque se reinicie la Kanji.
        remote = JrRemoteServer(applicationContext,
            onCommand = { action, number -> remoteCommand(action, number) },
            onConnected = { pairingDialog?.takeIf { it.isShowing }?.dismiss() },
            onPlaylist = { url -> if (opened) { repo.setUrl(url); refreshList(true) } },
            getState = { remoteSnapshot() })
        remote.start()
        val cached = repo.cached()
        if (cached.isNotEmpty()) setChannels(cached, preserve = false)
        else showWelcome(if (repo.url.isNotBlank()) "SIN SEÑAL\nEsperando conexión" else "SIN CANALES\nContactá al administrador")
        if (repo.url.isNotBlank()) refreshList(false)
        main.postDelayed(watchdog, 5_000L)
        main.postDelayed(syncEvery15m, 15 * 60 * 1000L)
        main.postDelayed(retryEmptyPlaylist, 60_000L)
        main.postDelayed(dismissSplash, 3200L)
        Updater.start(this)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true }
        surface = SurfaceView(this).apply { holder.setKeepScreenOn(true) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        // Aviso discreto: el video siempre sigue a pantalla completa.
        status = text("$appVersion  •  SIN CANALES", 13, Color.WHITE, false).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(215, 13, 23, 31), 10)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(15); rightMargin = dp(17)
        })
        info = text("", 21, Color.WHITE, true).apply {
            setPadding(dp(19), dp(10), dp(20), dp(10))
            background = rounded(Color.argb(227, 13, 23, 31), 11)
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
            bottomMargin = dp(28); leftMargin = dp(28)
        })
        welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            background = rounded(dark, 20)
        }
        // La pantalla pública nunca expone herramientas de instalación o diagnóstico.
        welcome.addView(text("CLICK TV", 38, accent, true), linear(-2, -2, Gravity.CENTER_HORIZONTAL))
        welcome.addView(text("", 19, Color.WHITE, false).apply {
            tag = "welcomeMessage"
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(22), dp(10), dp(12))
        }, linear(dp(420), -2))
        root.addView(welcome, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = rounded(Color.argb(248, 18, 31, 40), 18)
            visibility = View.GONE
        }
        guideTitle = text("CANALES", 23, accent, true)
        guide.addView(guideTitle, linear(-1, dp(40)))
        guide.addView(text("FLECHAS  mover cursor     OK  sintonizar     VOLVER  cerrar", 13, Color.LTGRAY, false), linear(-1, dp(34)))
        guideChoice = text("", 16, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(Color.rgb(29, 63, 72), 9)
        }
        guide.addView(guideChoice, linear(-1, dp(40)).apply { bottomMargin = dp(8) })
        grid = GridView(this).apply {
            numColumns = 5
            horizontalSpacing = dp(8)
            verticalSpacing = dp(9)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isFocusable = true
            isFocusableInTouchMode = true
            // El selector se dibuja encima de las tarjetas. La tarjeta NO debe
            // tomar el foco: lo conserva la grilla y OK confirma su selección.
            selector = rounded(Color.TRANSPARENT, 9, accent)
            setDrawSelectorOnTop(true)
            setOnItemClickListener { _, _, position, _ ->
                guideCursor = position
                selectGuideChannel()
            }
        }
        guide.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        guide.addView(button("Cerrar guía") { toggleGuide(false) }, linear(dp(170), dp(46), Gravity.END))
        root.addView(guide, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(32); rightMargin = dp(32); topMargin = dp(27); bottomMargin = dp(27)
        })
        setContentView(root)
    }

    private fun showSplash() {
        val splashImage = ImageView(this).apply {
            setImageResource(R.drawable.jr_splash)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "Click TV"
        }
        root.addView(splashImage, FrameLayout.LayoutParams(-1, -1))
        splash = splashImage
        // Capa de destellos de fibra optica sobre el logo (muy liviana).
        val fx = SparkleView(this)
        root.addView(fx, FrameLayout.LayoutParams(-1, -1))
        sparkles = fx
    }

    private fun removeSplash() {
        val overlay = splash ?: return
        splash = null
        val fx = sparkles
        sparkles = null
        fx?.animate()?.alpha(0f)?.setDuration(600L)?.withEndAction {
            (fx.parent as? FrameLayout)?.removeView(fx)
        }?.start()
        overlay.animate().alpha(0f).setDuration(600L).withEndAction {
            (overlay.parent as? FrameLayout)?.removeView(overlay)
            if (channels.isEmpty()) welcome.requestFocus() else root.requestFocus()
        }.start()
    }

    private fun setChannels(updated: List<TvChannel>, preserve: Boolean) {
        val currentUrl = if (preserve) channels.getOrNull(index)?.url else null
        channels = updated.take(M3uParser.MAX_CHANNELS)
        index = currentUrl?.let { u -> channels.indexOfFirst { it.url == u } }?.takeIf { it >= 0 }
            ?: repo.startupIndex(channels)
        welcome.visibility = View.GONE
        guideTitle.text = "CANALES  (${channels.size}/${M3uParser.MAX_CHANNELS})"
        guideCursor = index
        grid.adapter = GuideAdapter()
        if (guideOpen) updateGuideSelection()
        if (!preserve || currentUrl != channels[index].url) switchTo(index)
        else status.text = "$appVersion  •  ${channels[index].name}"
    }

    private fun switchTo(target: Int) {
        if (target !in channels.indices) return
        main.removeCallbacks(retry)
        main.removeCallbacks(clearNumeric)
        retryPending = false
        retryCount = 0
        numeric = ""
        index = target
        guideCursor = target
        repo.lastIndex = index
        playCurrent(false)
        grid.setSelection(index)
    }

    private fun playCurrent(retrying: Boolean) {
        if (channels.isEmpty()) return
        val channel = channels[index]
        if (channel.kind == StreamKind.RTP_UNSUPPORTED) {
            engine.player.stop()
            bufferStarted = 0L
            status.text = "$appVersion  •  RTP NO SOPORTADO"
            showInfo("${index + 1}  ${channel.name}  •  RTP pendiente de validación", permanent = true)
            return
        }
        if (retrying) retryCount++
        main.removeCallbacks(hideStatus)
        status.visibility = View.VISIBLE
        status.text = "$appVersion  •  CONECTANDO"
        bufferStarted = SystemClock.elapsedRealtime()
        showInfo("${index + 1}   ${channel.name}${if (retrying) "  •  REINTENTO" else ""}")
        try { engine.play(channel, surface.holder.surface.takeIf { it.isValid }) }
        catch (e: Exception) { failure(e.message ?: "No se pudo iniciar el canal") }
    }

    private fun playerStatus(state: String) {
        status.text = "$appVersion  •  $state"
        if (state == "REPRODUCIENDO") {
            bufferStarted = 0L; retryCount = 0
            main.removeCallbacks(hideStatus)
            main.postDelayed(hideStatus, 4_000L)
        } else if (state == "CONECTANDO…" && bufferStarted == 0L) {
            bufferStarted = SystemClock.elapsedRealtime()
        }
    }

    private fun failure(reason: String) {
        if (channels.isEmpty() || retryPending || channels.getOrNull(index)?.kind == StreamKind.RTP_UNSUPPORTED) return
        bufferStarted = 0L
        retryPending = true
        val delay = min(30_000L, 5_000L * (1L shl min(retryCount, 3)))
        retryCount++
        main.removeCallbacks(hideStatus)
        status.visibility = View.VISIBLE
        status.text = "$appVersion  •  SIN SEÑAL — REINTENTANDO"
        showInfo("${channels[index].name}: $reason. Reintento en ${delay / 1000}s")
        main.postDelayed(retry, delay)
    }

    private fun refreshList(manual: Boolean) {
        if (repo.url.isBlank()) { if (manual) showSetup(); return }
        if (manual) {
            if (channels.isEmpty()) showWelcome("Dirección guardada. Descargando lista de canales…")
            else showInfo("Lista guardada. Actualizando canales…", permanent = true)
        }
        network.execute {
            val result = runCatching { repo.download() }
            main.post {
                if (!opened) return@post
                result.onSuccess { fresh ->
                    setChannels(fresh, preserve = channels.isNotEmpty())
                    if (manual) toast("Lista actualizada: ${fresh.size} canales")
                }.onFailure { error ->
                    if (channels.isEmpty()) showWelcome("SIN SEÑAL\nReintentando conexión")
                    else if (manual) {
                        toast("Sin cambios: ${error.message}. Se conserva la lista anterior.")
                        showInfo("No se pudo actualizar: ${error.message}", permanent = true)
                    }
                }
            }
        }
    }

    private fun showWelcome(message: String) {
        welcome.findViewWithTag<TextView>("welcomeMessage")?.text = message
        welcome.visibility = View.VISIBLE
        // El único acceso técnico es el gesto MENU x5, incluso sin señal.
        status.visibility = View.GONE
        root.requestFocus()
    }

    // La lista pública se probó desde el propio TV Stick en el diagnóstico 002.
    // Sólo se ofrece como prueba de descarga/canales; no garantiza que todos los streams estén activos.
    private fun loadTestList() {
        repo.setUrl(sampleUrl)
        refreshList(true)
    }

    private fun showSetup() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(repo.url)
            hint = "https://servidor/lista.m3u"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(text("Ingresá una URL HTTP/HTTPS o usá el botón de prueba sin escribir.", 15, Color.DKGRAY, false))
            addView(input)
        }
        // El listener se coloca después de show() para impedir que Android cierre
        // el cuadro automáticamente cuando el enlace no es válido.
        val dialog = AlertDialog.Builder(this)
            .setTitle("Click TV • Configurar canales")
            .setView(wrap)
            .setPositiveButton("Guardar y actualizar", null)
            .setNeutralButton("Abrir archivo M3U") { _, _ -> importM3u() }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newUrl = input.text.toString().trim()
                val isValid = runCatching {
                    val parsed = URL(newUrl)
                    (parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) && parsed.host.isNotBlank()
                }.getOrDefault(false)
                if (!isValid) {
                    input.error = "Ingresá una dirección HTTP/HTTPS completa"
                    input.requestFocus()
                } else {
                    repo.setUrl(newUrl)
                    dialog.dismiss()
                    refreshList(true)
                }
            }
        }
        dialog.show()
    }

    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("CLICK TV • AJUSTES")
            .setItems(arrayOf(
                "Configurar Wi-Fi (Android)",
                "Ajustes completos de Android",
                "Control desde celular",
                "Canal al encender",
                "Controles de reproducción",
                "Cargar canales de prueba",
                "Configurar URL M3U",
                "Actualizar canales ahora",
                "Importar archivo M3U",
                "Diagnosticar conexion",
                "Acerca de Click TV"
            )) { _, which ->
                when (which) {
                    0 -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                    1 -> openSystemSettings(Settings.ACTION_SETTINGS)
                    2 -> showRemoteSettings()
                    3 -> showStartChannelSettings()
                    4 -> showPlaybackControls()
                    5 -> loadTestList()
                    6 -> showSetup()
                    7 -> refreshList(true)
                    8 -> importM3u()
                    9 -> showNetworkDiagnosis()
                    10 -> AlertDialog.Builder(this).setTitle("Click TV • $appVersion")
                        .setMessage("Hasta ${M3uParser.MAX_CHANNELS} canales. HLS/M3U8, MPEG-TS por HTTP y MP4. Control LAN con QR temporal y teléfonos autorizados; controles compatibles con DVR/MP4. UDP experimental, RTP pendiente.")
                        .setPositiveButton("Cerrar", null).show()
                }
            }.show()
    }

    /** Los ajustes del sistema se abren de manera local desde el control remoto.
     * No dependen de Internet, del QR, de un celular autorizado ni de ADB.
     * Si el firmware Kanji no implementa la pantalla específica de Wi-Fi,
     * intentar la pantalla general de Android como respaldo.
     */
    private fun openSystemSettings(action: String) {
        val actions = if (action == Settings.ACTION_WIFI_SETTINGS) {
            listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
        } else {
            listOf(Settings.ACTION_SETTINGS)
        }
        for (candidate in actions) {
            val opened = runCatching {
                startActivity(Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
        AlertDialog.Builder(this)
            .setTitle("AJUSTES DE ANDROID NO DISPONIBLES")
            .setMessage("El firmware de este equipo no permite abrir los ajustes del sistema desde Click TV. La reproducción no se modificó. No cambies de red hasta resolver este acceso.")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** El QR sólo se muestra a pedido; las autorizaciones no dependen de IP ni de QR. */
    private fun showRemoteSettings() {
        AlertDialog.Builder(this)
            .setTitle("CLICK TV • CONTROL DESDE CELULAR")
            .setItems(arrayOf("Vincular nuevo celular (QR)", "Ver / revocar celulares", "Dirección de acceso")) { _, selection ->
                when (selection) {
                    0 -> showPairingQr()
                    1 -> showAuthorizedPhones()
                    2 -> AlertDialog.Builder(this).setTitle("DIRECCIÓN DEL CONTROL")
                        .setMessage(remote.baseUrl()?.let { "Desde un celular previamente autorizado, conectado a esta misma red, abrí:\n\n$it\n\nGuardá un acceso directo en el navegador. Conviene reservar la IP del equipo en el router." }
                            ?: "SIN RED / Esperando conexión. Verificá Wi-Fi o Ethernet.")
                        .setPositiveButton("Cerrar", null).show()
                }
            }.setNegativeButton("Cerrar", null).show()
    }

    /** QR sin Bitmap intermedio: el dibujo se realiza directamente en el Canvas.
     *  Evita fallos de algunas TV Box al crear o convertir bitmaps desde una matriz.
     */
    private fun qrView(url: String): View {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
        return object : View(this) {
            private val black = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
                isAntiAlias = false
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.WHITE)
                val side = min(width, height).toFloat()
                val left = (width - side) / 2f
                val top = (height - side) / 2f
                val cell = side / matrix.width.toFloat()
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix.get(x, y)) {
                            canvas.drawRect(left + x * cell, top + y * cell,
                                left + (x + 1) * cell, top + (y + 1) * cell, black)
                        }
                    }
                }
            }
        }.apply { contentDescription = "QR para vincular el celular con Click TV" }
    }

    private fun showPairingQr() {
        if (!remote.running) {
            AlertDialog.Builder(this).setTitle("CONTROL NO DISPONIBLE")
                .setMessage(remote.error ?: "El servidor local todavía está iniciando. Volvé a intentarlo.")
                .setPositiveButton("Cerrar", null).show()
            return
        }
        val url = remote.beginPairing()
        if (url == null) {
            AlertDialog.Builder(this).setTitle("SIN RED / Esperando conexión")
                .setMessage("Para vincular el celular, conectá el equipo y el teléfono a la misma red Wi-Fi o LAN.")
                .setPositiveButton("Cerrar", null).show()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        layout.addView(TextView(this).apply {
            text = "Escaneá una sola vez desde el teléfono"
            textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }, linear(-1, -2))
        try {
            layout.addView(qrView(url), linear(dp(330), dp(330), Gravity.CENTER_HORIZONTAL))
        } catch (e: Exception) {
            // El enlace de emergencia permite emparejar incluso si falla la librería QR.
            // Registramos clase y stack trace: e.message puede ser null en Android.
            Log.e("JR_QR", "Error generando QR de vinculación", e)
            layout.addView(TextView(this).apply {
                text = "NO SE PUDO MOSTRAR EL QR (${e.javaClass.simpleName})\n" +
                    "Enlace temporal para abrir desde el celular:\n$url"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTextIsSelectable(true)
            }, linear(-1, -2))
        }
        layout.addView(TextView(this).apply {
            text = "QR válido durante 10 minutos. Se cerrará cuando el teléfono quede autorizado.\n\nAcceso posterior: ${remote.baseUrl()}"
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY)
        }, linear(-1, -2))
        val dialog = AlertDialog.Builder(this).setTitle("VINCULAR CELULAR • $appVersion")
            .setView(layout).setPositiveButton("Cerrar", null).create()
        pairingDialog = dialog
        dialog.setOnDismissListener {
            remote.endPairing()
            if (pairingDialog === dialog) pairingDialog = null
        }
        dialog.show()
    }

    private fun showAuthorizedPhones() {
        val phones = remote.phones()
        if (phones.isEmpty()) {
            AlertDialog.Builder(this).setTitle("CELULARES AUTORIZADOS")
                .setMessage("Todavía no hay teléfonos vinculados.")
                .setPositiveButton("Cerrar", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("CELULARES AUTORIZADOS (${phones.size}/10)")
            .setItems(phones.map { it.name }.toTypedArray()) { _, selected ->
                val phone = phones[selected]
                AlertDialog.Builder(this).setTitle("REVOCAR ACCESO")
                    .setMessage("¿Quitar el acceso de «${phone.name}»? No podrá controlar Click TV hasta volver a escanear un QR.")
                    .setPositiveButton("Revocar") { _, _ ->
                        remote.revoke(phone.id)
                        toast("Acceso revocado: ${phone.name}")
                    }
                    .setNegativeButton("Cancelar", null).show()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun remoteSnapshot(): JrRemoteSnapshot {
        // ExoPlayer y canales sólo se leen en el hilo principal de Android.
        val signal = CountDownLatch(1)
        var snapshot = JrRemoteSnapshot(emptyList(), -1, false, false, false, 100)
        main.post {
            try {
                if (opened) snapshot = JrRemoteSnapshot(channels.map { it.name },
                    if (channels.isEmpty()) -1 else index,
                    engine.player.isPlaying,
                    engine.player.isCurrentMediaItemSeekable,
                    engine.player.isCurrentMediaItemLive,
                    (engine.player.volume * 100f).toInt())
            } finally { signal.countDown() }
        }
        signal.await(2, TimeUnit.SECONDS)
        return snapshot
    }

    private fun remoteCommand(action: String, number: Int?) {
        // Ya estamos en el hilo UI: compartir exactamente la lógica del mando físico.
        if (!opened) return
        when (action) {
            "channel" -> if (number != null && number in channels.indices) {
                if (guideOpen) toggleGuide(false)
                switchTo(number)
            }
            "next" -> if (channels.isNotEmpty()) {
                if (guideOpen) toggleGuide(false)
                switchTo((index + 1) % channels.size)
            }
            "prev" -> if (channels.isNotEmpty()) {
                if (guideOpen) toggleGuide(false)
                switchTo((index - 1 + channels.size) % channels.size)
            }
            "pause" -> pauseOrResume()
            "back" -> seekRelative(-10_000L)
            "forward" -> seekRelative(10_000L)
            "live" -> returnToLive()
            "volup" -> engine.player.volume = (engine.player.volume + 0.10f).coerceAtMost(1f)
            "voldown" -> engine.player.volume = (engine.player.volume - 0.10f).coerceAtLeast(0f)
            "mute" -> engine.player.volume = if (engine.player.volume > 0f) 0f else 1f
        }
    }

    private fun showStartChannelSettings() {
        val chosen = if (repo.startupMode == "fixed") 1 else 0
        val fixed = repo.fixedChannelName.ifBlank { "sin seleccionar" }
        AlertDialog.Builder(this)
            .setTitle("CANAL AL ENCENDER")
            .setSingleChoiceItems(arrayOf("Último canal visto", "Canal fijo (actual: $fixed)"), chosen) { dlg, which ->
                dlg.dismiss()
                if (which == 0) {
                    repo.useLastChannelOnStartup()
                    toast("Al reiniciar: último canal visto")
                } else {
                    showFixedChannelPicker()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFixedChannelPicker() {
        if (channels.isEmpty()) {
            toast("Primero cargá una lista de canales")
            return
        }
        val labels = channels.mapIndexed { position, channel ->
            "${position + 1}. ${channel.name}"
        }.toTypedArray()
        val selected = channels.indexOfFirst { it.url == repo.fixedChannelUrl }
        AlertDialog.Builder(this)
            .setTitle("ELEGIR CANAL FIJO DE INICIO")
            .setSingleChoiceItems(labels, selected) { dlg, which ->
                repo.useFixedChannelOnStartup(channels[which])
                toast("Al reiniciar: ${channels[which].name}")
                dlg.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Pausa/seek sólo cuando la fuente conserva una ventana seekable real.
     *  No simula TimeShift en HLS sin DVR.
     */
    private fun canTimeShift(): Boolean {
        if (channels.isEmpty()) return false
        if (engine.player.isCurrentMediaItemSeekable) return true
        showInfo("Este canal en vivo no ofrece DVR/TimeShift", permanent = false)
        return false
    }

    private fun pauseOrResume() {
        if (!canTimeShift()) return
        val player = engine.player
        if (player.playWhenReady) {
            player.pause()
            bufferStarted = 0L
            main.removeCallbacks(retry)
            retryPending = false
            status.text = "$appVersion  •  PAUSADO"
            status.visibility = View.VISIBLE
            showInfo("PAUSA  •  PLAY para continuar")
        } else {
            player.play()
            if (player.playbackState == Player.STATE_BUFFERING) bufferStarted = SystemClock.elapsedRealtime()
            showInfo("REANUDANDO")
        }
    }

    private fun seekRelative(offsetMs: Long) {
        if (!canTimeShift()) return
        val player = engine.player
        val duration = player.duration
        val max = if (duration == C.TIME_UNSET || duration <= 0) Long.MAX_VALUE else (duration - 500L).coerceAtLeast(0)
        val position = (player.currentPosition + offsetMs).coerceAtLeast(0).coerceAtMost(max)
        player.seekTo(position)
        showInfo(if (offsetMs < 0) "RETROCEDER 10 s" else "ADELANTAR 10 s")
    }

    private fun returnToLive() {
        if (channels.isEmpty()) return
        val player = engine.player
        if (!player.isCurrentMediaItemLive) {
            showInfo("Este canal no es una transmisión en vivo")
            return
        }
        if (!player.isCurrentMediaItemSeekable) {
            player.play()
            showInfo("EN VIVO")
            return
        }
        player.seekToDefaultPosition()
        player.play()
        showInfo("VOLVER AL VIVO")
    }

    private fun showPlaybackControls() {
        AlertDialog.Builder(this).setTitle("Click TV • CONTROLES")
            .setItems(arrayOf("Pausar / Reanudar", "Retroceder 10 segundos", "Adelantar 10 segundos", "Volver al vivo", "Cerrar")) { _, which ->
                when (which) {
                    0 -> pauseOrResume()
                    1 -> seekRelative(-10_000L)
                    2 -> seekRelative(10_000L)
                    3 -> returnToLive()
                }
            }
            .show()
    }

    private fun showNetworkDiagnosis() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("$appVersion | Diagnostico")
            .setMessage("Comprobando red, DNS, descarga M3U y lector local...\nNo hace falta escribir ninguna direccion.")
            .setPositiveButton("Cerrar", null)
            .create()
        dialog.show()
        diagnostics.execute {
            val result = runCatching { JrNetworkDiagnostic.run(applicationContext) }
                .getOrElse { "Fallo el diagnostico: ${it.javaClass.simpleName}: ${it.message}" }
            main.post { if (opened && dialog.isShowing) dialog.setMessage(result) }
        }
    }

    private fun importM3u() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.apple.mpegurl", "audio/x-mpegurl", "text/plain", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 101)
    }

    @Deprecated("Android utiliza este callback para el selector de archivos en API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            network.execute {
                val result = runCatching { repo.import(uri) }
                main.post {
                    if (!opened) return@post
                    result.onSuccess {
                        repo.setUrl("") // La importación local no debe ser sobreescrita por una URL anterior.
                        setChannels(it, preserve = false)
                        toast("Importados ${it.size} canales")
                    }.onFailure { toast("Error de importación: ${it.message}") }
                }
            }
        }
    }

    private fun toggleGuide(show: Boolean) {
        if (channels.isEmpty()) return
        guideOpen = show
        status.visibility = if (show) View.GONE else status.visibility
        guide.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            main.removeCallbacks(hideStatus)
            info.visibility = View.GONE
            guideCursor = index
            grid.requestFocus()
            updateGuideSelection()
            // Se ejecuta tras el layout inicial para centrar la selección
            // incluso en sticks que crean la grilla de forma diferida.
            grid.post { if (guideOpen && opened) updateGuideSelection() }
        } else {
            root.requestFocus()
            showInfo("${index + 1}   ${channels[index].name}")
        }
    }

    private fun updateGuideSelection() {
        if (!guideOpen || channels.isEmpty()) return
        guideCursor = guideCursor.coerceIn(0, channels.lastIndex)
        val selected = channels[guideCursor]
        guideChoice.text = "CURSOR ${guideCursor + 1}/${channels.size}: ${selected.name}     •     OK: VER CANAL"
        grid.setSelection(guideCursor)
    }

    private fun moveGuideCursor(keyCode: Int) {
        if (channels.isEmpty()) return
        val last = channels.lastIndex
        guideCursor = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> (guideCursor - 5).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> (guideCursor + 5).coerceAtMost(last)
            KeyEvent.KEYCODE_DPAD_LEFT -> (guideCursor - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> (guideCursor + 1).coerceAtMost(last)
            else -> guideCursor
        }
        updateGuideSelection()
    }

    private fun selectGuideChannel() {
        if (!guideOpen || guideCursor !in channels.indices) return
        val selected = guideCursor
        if (selected != index) switchTo(selected)
        toggleGuide(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Al cerrar la guía, consumir también la liberación de VOLVER impide
        // que algunos controles remotos cierren accidentalmente la Activity.
        if (guideOpen && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleGuide(false)
            return true
        }
        // Consumir DOWN y UP de MENU/SETTINGS evita que Android abra otro menú.
        // Sólo cinco pulsaciones físicas rápidas (no repetición de tecla larga).
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                technicalMenuGesture.tap(SystemClock.elapsedRealtime())) {
                showOptions()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        technicalMenuGesture.reset()
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.repeatCount == 0 && channels.isNotEmpty() && !guideOpen) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> pauseOrResume()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> if (engine.player.playWhenReady) pauseOrResume()
                        KeyEvent.KEYCODE_MEDIA_PLAY -> if (!engine.player.playWhenReady) pauseOrResume()
                        KeyEvent.KEYCODE_MEDIA_REWIND -> seekRelative(-10_000L)
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekRelative(10_000L)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (channels.isNotEmpty()) toggleGuide(true)
                // Sin canales, BACK no debe exponer el HOME de emergencia de Android.
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (channels.isNotEmpty()) {
                    if (event.repeatCount == 0) {
                        if (guideOpen) selectGuideChannel() else toggleGuide(true)
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (guideOpen) {
                    moveGuideCursor(event.keyCode)
                    return true
                }
                if (channels.isNotEmpty()) {
                    val next = when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> (index + 1) % channels.size
                        else -> (index - 1 + channels.size) % channels.size
                    }
                    switchTo(next)
                    return true
                }
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!guideOpen && channels.isNotEmpty()) {
                    numeric = (numeric + (event.keyCode - KeyEvent.KEYCODE_0)).takeLast(2)
                    showInfo("CANAL  $numeric")
                    main.removeCallbacks(clearNumeric)
                    main.postDelayed(clearNumeric, 1_400L)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showInfo(message: String, permanent: Boolean = false) {
        info.text = message
        info.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo)
        if (!permanent) main.postDelayed(hideInfo, 5_000L)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, sp: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }
    private fun button(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 16f
        setTextColor(Color.WHITE)
        background = selector(rounded(Color.rgb(32, 85, 91), 11), rounded(accent, 11))
        isFocusable = true
        setOnClickListener { click() }
    }
    private fun selector(normal: GradientDrawable, focused: GradientDrawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }
    private fun linear(width: Int, height: Int, gravity: Int = Gravity.NO_GRAVITY) =
        LinearLayout.LayoutParams(width, height).apply { this.gravity = gravity }

    private inner class GuideAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int): Any = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val channel = channels[position]
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = selector(rounded(panel, 10, if (position == index) accent else Color.TRANSPARENT), rounded(Color.rgb(25, 104, 111), 10, accent))
                // Los hijos de GridView no deben capturar el foco:
                // impedían que OK confirmara la selección del canal.
                isFocusable = false
                isClickable = false
                minimumHeight = dp(102)
            }
            val picture = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            item.addView(picture, linear(dp(90), dp(48), Gravity.CENTER_HORIZONTAL))
            val label = text("${position + 1}. ${channel.name}", 14, Color.WHITE, position == index).apply {
                gravity = Gravity.CENTER; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            item.addView(label, linear(-1, dp(38)))
            if (channel.logo.startsWith("http://") || channel.logo.startsWith("https://")) setLogo(picture, channel.logo)
            else picture.setImageDrawable(null)
            return item
        }
    }

    private fun setLogo(target: ImageView, url: String) {
        target.tag = url
        logos.get(url)?.let { target.setImageBitmap(it); return }
        images.execute {
            val bmp = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 4000; c.readTimeout = 5000
                    if (c.responseCode !in 200..299 || c.contentLength > 700_000) return@runCatching null
                    c.inputStream.use { stream ->
                        val limited = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (true) {
                            val n = stream.read(bytes)
                            if (n < 0) break
                            if (limited.size() + n > 700_000) return@use null
                            limited.write(bytes, 0, n)
                        }
                        BitmapFactory.decodeByteArray(limited.toByteArray(), 0, limited.size())
                    }
                } finally { c.disconnect() }
            }.getOrNull()
            if (bmp != null) {
                synchronized(logos) { logos.put(url, bmp) }
                main.post { if (opened && target.tag == url) target.setImageBitmap(bmp) }
            }
        }
    }

    override fun onDestroy() {
        opened = false
        main.removeCallbacksAndMessages(null)
        pairingDialog?.dismiss()
        remote.stop()
        engine.release()
        network.shutdownNow(); images.shutdownNow(); diagnostics.shutdownNow()
        super.onDestroy()
    }
}
