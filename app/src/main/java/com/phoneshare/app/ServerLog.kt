package com.phoneshare.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Process-wide rolling log used by the UI's terminal view. */
object ServerLog {

    enum class Level { INFO, OK, WARN, ERR }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val message: String,
    )

    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private val listeners = mutableListOf<(Entry) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun snapshot(): List<Entry> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
        notify(Entry(System.currentTimeMillis(), Level.INFO, "(log cleared)"))
    }

    @Synchronized
    fun addListener(l: (Entry) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (Entry) -> Unit) { listeners.remove(l) }

    fun info(msg: String) = add(Level.INFO, msg)
    fun ok(msg: String)   = add(Level.OK, msg)
    fun warn(msg: String) = add(Level.WARN, msg)
    fun err(msg: String)  = add(Level.ERR, msg)

    @Synchronized
    private fun add(level: Level, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, msg)
        if (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
        buffer.addLast(e)
        notify(e)
    }

    private fun notify(e: Entry) {
        val snapshot = listeners.toList()
        main.post { for (l in snapshot) l(e) }
    }

    fun format(e: Entry): String =
        df.format(Date(e.timestamp)) + "  " + e.message
}
