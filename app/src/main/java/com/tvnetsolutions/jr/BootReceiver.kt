package com.tvnetsolutions.jr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Arranque dedicado Kanji. HOME como launcher principal sigue siendo respaldo importante.
 * Android 10+ puede limitar launches desde background: validar en el hardware real.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launch)
            } catch (e: Exception) {
                Log.w("JR_Boot", "Android no permitió abrir JR en el arranque", e)
            } finally {
                pending.finish()
            }
        }, 3500L)
    }
}
