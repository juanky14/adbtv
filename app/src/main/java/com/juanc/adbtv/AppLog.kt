package com.juanc.adbtv

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Registro en memoria que la pantalla del TV muestra en vivo. */
object AppLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) {
        Log.i("AdbTv", msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        listeners.forEach { it() }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
}
