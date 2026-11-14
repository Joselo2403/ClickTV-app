package com.tvnetsolutions.jr

/**
 * Gesto del menú técnico sin dependencias Android. Un MENU sostenido produce
 * repeatCount > 0, que MainActivity no envía aquí.
 * Cinco taps separados, cada uno antes de 1,4 s, dentro de 5,5 s.
 */
internal class TechnicalMenuGesture {
    private var taps = 0
    private var firstAt = 0L
    private var lastAt = 0L

    fun tap(atMs: Long): Boolean {
        if (taps == 0 || atMs - lastAt > 1_400L || atMs - firstAt > 5_500L || atMs < lastAt) {
            taps = 1
            firstAt = atMs
        } else {
            taps += 1
        }
        lastAt = atMs
        if (taps == 5) {
            reset()
            return true
        }
        return false
    }

    fun reset() {
        taps = 0
        firstAt = 0L
        lastAt = 0L
    }
}
