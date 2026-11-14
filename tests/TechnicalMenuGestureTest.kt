package com.tvnetsolutions.jr

fun main() {
    val g = TechnicalMenuGesture()
    check(!g.tap(1000))
    check(!g.tap(1600))
    check(!g.tap(2200))
    check(!g.tap(2800))
    check(g.tap(3400))
    check(!g.tap(4000))  // reiniciado, no queda abierto
    g.reset()
    check(!g.tap(10000))
    check(!g.tap(10500))
    check(!g.tap(11000))
    check(!g.tap(13000)) // intervalo >1,4 s: reinicia
    check(!g.tap(13500))
    g.reset()
    for (t in longArrayOf(10, 1010, 2010, 3010, 4010)) {
        check(g.tap(t) == (t == 4010L))
    }
    println("TechnicalMenuGestureTest: OK")
}
