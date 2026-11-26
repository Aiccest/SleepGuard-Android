package com.sleepmate.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SleepMateLog {
    private const val PREFS = "sleepmate_diagnostic_logs"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 300

    @Synchronized
    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entries = prefs.getString(KEY_ENTRIES, "").orEmpty().lines().filter { it.isNotBlank() }.toMutableList()
        entries.add("$time  $message")
        prefs.edit().putString(KEY_ENTRIES, entries.takeLast(MAX_ENTRIES).joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, "").orEmpty().lines().filter { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }
}
