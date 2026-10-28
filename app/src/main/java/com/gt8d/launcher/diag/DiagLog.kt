package com.gt8d.launcher.diag

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object DiagLog {
    private const val MAX_LINES = 500
    private const val LOGCAT_TAG = "GT8D"
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val buffer = ArrayDeque<String>(MAX_LINES)

    fun d(tag: String, msg: String) {
        val timestamp = LocalTime.now().format(timeFormatter)
        val line = "$timestamp [$tag] $msg"
        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }
        runCatching { Log.d(LOGCAT_TAG, "[$tag] $msg") }
    }

    fun dump(): List<String> = synchronized(buffer) {
        buffer.toList()
    }

    internal fun clearForTest() {
        synchronized(buffer) {
            buffer.clear()
        }
    }
}
