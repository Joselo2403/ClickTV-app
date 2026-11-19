package com.tvnetsolutions.jr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Capa de "destellos de fibra optica" para la pantalla de inicio (splash).
 * Dibuja unos pocos puntos de luz que titilan cerca de la onda azul del logo,
 * mas un par de pulsos que viajan por la onda (como una senal por la fibra).
 * Es muy liviano: unos pocos elementos por cuadro, sin video ni imagenes extra.
 * Nivel "Sutil": apenas unos destellos.
 */
class SparkleView(context: Context) : View(context) {

    private class Spark(
        val xf: Float, val yf: Float, val color: Int,
        val r: Float, var t: Float, val sp: Float, val mx: Float
    )

    private class Pulse(var p: Float, val sp: Float, val color: Int, val len: Float)

    private val colors = intArrayOf(
        Color.rgb(120, 215, 255), Color.rgb(60, 170, 255),
        Color.rgb(200, 240, 255), Color.rgb(90, 140, 255)
    )

    private val sparkCount = 16   // "Sutil"
    private val pulseCount = 2

    private val sparks = ArrayList<Spark>()
    private val pulses = ArrayList<Pulse>()

    private val glow: Bitmap = makeGlow(64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var w = 0f
    private var h = 0f
    private var last = 0L

    private fun makeGlow(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rad = size / 2f
        val g = RadialGradient(
            rad, rad, rad,
            intArrayOf(Color.WHITE, Color.argb(130, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = g
        c.drawCircle(rad, rad, rad, p)
        return bmp
    }

    // Curva aproximada de la onda azul del logo (en fracciones 0..1)
    private fun waveY(xf: Float): Float {
        val d = (xf - 0.47f) / 0.26f
        val base = 0.775f
        val crest = 0.052f * exp(-d * d)
        val ripple = 0.012f * sin(xf * 7.5f + 0.4f)
        return base - crest + ripple
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        build()
    }

    private fun build() {
        sparks.clear()
        for (i in 0 until sparkCount) sparks.add(newSpark())
        pulses.clear()
        for (i in 0 until pulseCount) {
            pulses.add(
                Pulse(
                    i.toFloat() / pulseCount,
                    Random.nextFloat() * 0.035f + 0.055f,
                    colors[Random.nextInt(2)],
                    Random.nextFloat() * 0.05f + 0.06f
                )
            )
        }
    }

    private fun newSpark(): Spark {
        val nearWave = Random.nextFloat() < 0.7f
        val xf = Random.nextFloat() * 0.91f + 0.05f
        val yf = if (nearWave) waveY(xf) + (Random.nextFloat() - 0.5f) * 0.06f
        else Random.nextFloat() * 0.78f + 0.12f
        return Spark(
            xf, yf, colors[Random.nextInt(colors.size)],
            Random.nextFloat() * 1.4f + 0.8f,
            Random.nextFloat() * 6.28f,
            Random.nextFloat() * 1.0f + 0.6f,
            Random.nextFloat() * 0.5f + 0.5f
        )
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        if (a <= 0 || r <= 0f) return
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        paint.alpha = a
        val scale = (r * 2f) / glow.width
        canvas.save()
        canvas.translate(x - r, y - r)
        canvas.scale(scale, scale)
        canvas.drawBitmap(glow, 0f, 0f, paint)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        if (last == 0L) last = now
        var dt = (now - last) / 1_000_000_000f
        last = now
        if (dt > 0.05f) dt = 0.05f

        // Destellos que titilan
        for (s in sparks) {
            s.t += dt * s.sp * 2.2f
            val tw = (sin(s.t) * 0.5f + 0.5f)
            val a = tw * tw * s.mx
            val x = s.xf * w
            val y = s.yf * h
            val r = (s.r + tw * s.r * 1.4f)
            drawGlow(canvas, x, y, r * 4f, s.color, a * 0.9f)
            corePaint.alpha = (a * 230f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, max(0.6f, r * 0.5f), corePaint)
        }

        // Pulsos que viajan por la onda (senal de fibra)
        for (p in pulses) {
            p.p += dt * p.sp
            if (p.p > 1.05f) p.p = -0.05f
            val steps = 10
            for (i in 0 until steps) {
                val back = p.p - (i / steps.toFloat()) * p.len
                if (back < 0f || back > 1f) continue
                val x = back * w
                val y = waveY(back) * h
                val a = 1f - i / steps.toFloat()
                drawGlow(canvas, x, y, 7f - i * 0.5f, p.color, a * 0.5f)
            }
            if (p.p in 0f..1f) {
                val x = p.p * w
                val y = waveY(p.p) * h
                drawGlow(canvas, x, y, 14f, p.color, 0.8f)
                corePaint.alpha = 240
                canvas.drawCircle(x, y, 2.2f, corePaint)
            }
        }

        postInvalidateOnAnimation()
    }
}
