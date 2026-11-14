package com.tvnetsolutions.jr

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Misma idea que VELIA 0.9.97: ExoPlayer único + cookies HLS + normalización LL-HLS. */
@UnstableApi
class JrEngine(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onEnd: () -> Unit
) {
    private val cookieJar = HlsCookieJar()
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(6000)
        .setReadTimeoutMs(6000)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("JR-Intercom-Player/002 (Android TV)")
    private val dataSourceFactory = DefaultDataSource.Factory(context, CookieAwareHttpDataSourceFactory(httpFactory, cookieJar))
    private val selection = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    // JR.009: canales sin extensión. Se recuerda cuáles resultaron ser HLS.
    private val hlsDetected = HashSet<String>()
    private var currentChannel: TvChannel? = null
    private var currentSurface: Surface? = null
    private var autoRetriedAsHls = false
    private var multicastLock: WifiManager.MulticastLock? = null
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(selection)
        .setRenderersFactory(DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 20000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(false).build())
        .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    onStatus(when (state) {
                        Player.STATE_READY -> "REPRODUCIENDO"
                        Player.STATE_BUFFERING -> "CONECTANDO…"
                        Player.STATE_IDLE -> "PREPARANDO…"
                        Player.STATE_ENDED -> "FINALIZADO"
                        else -> "ESPERANDO…"
                    })
                    if (state == Player.STATE_ENDED) onEnd()
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.w("JR_Player", "Error ${error.errorCodeName}", error)
                    if (tryAutoAsHls(error)) return
                    onFailure(error.errorCodeName)
                }
            })
        }

    fun play(channel: TvChannel, surface: Surface?) {
        if (channel.kind == StreamKind.RTP_UNSUPPORTED) {
            player.stop()
            onFailure("RTP NO DISPONIBLE EN ESTA VERSIÓN")
            return
        }
        releaseMulticast()
        if (channel.kind == StreamKind.UDP_TS) {
            try {
                multicastLock = wifi?.createMulticastLock("JR-TS").also { it?.setReferenceCounted(false); it?.acquire() }
            } catch (e: Exception) {
                Log.w("JR_Player", "No se pudo obtener multicast lock", e)
            }
        }
        currentChannel = channel
        currentSurface = surface
        autoRetriedAsHls = false
        val mime = when (channel.kind) {
            StreamKind.HLS -> MimeTypes.APPLICATION_M3U8
            StreamKind.MP4 -> MimeTypes.VIDEO_MP4
            StreamKind.UDP_TS, StreamKind.HTTP_TS -> MimeTypes.VIDEO_MP2T
            // Sin MIME: ExoPlayer reconoce solo si es TS, MP4, etc. (como VLC).
            StreamKind.AUTO -> if (channel.url in hlsDetected) MimeTypes.APPLICATION_M3U8 else null
            StreamKind.RTP_UNSUPPORTED -> null
        }
        start(channel, mime)
    }

    private fun start(channel: TvChannel, mime: String?) {
        val builder = MediaItem.Builder().setUri(channel.url)
        if (mime != null) builder.setMimeType(mime)
        cookieJar.clear()
        player.stop()
        val surface = currentSurface
        if (surface != null && surface.isValid) player.setVideoSurface(surface)
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
    }

    /** JR.009: si un canal sin extensión no es una transmisión directa, se reintenta como HLS una vez. */
    private fun tryAutoAsHls(error: PlaybackException): Boolean {
        val channel = currentChannel ?: return false
        if (channel.kind != StreamKind.AUTO || autoRetriedAsHls || channel.url in hlsDetected) return false
        val parsing = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        if (!parsing) return false
        autoRetriedAsHls = true
        hlsDetected.add(channel.url)
        Log.i("JR_Player", "Canal sin extensión detectado como HLS: ${channel.url}")
        start(channel, MimeTypes.APPLICATION_M3U8)
        return true
    }

    fun attach(surface: Surface?) {
        currentSurface = surface
        if (surface == null) player.clearVideoSurface()
        else if (surface.isValid) player.setVideoSurface(surface)
    }

    fun release() {
        releaseMulticast()
        player.release()
    }

    private fun releaseMulticast() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
    }
}
