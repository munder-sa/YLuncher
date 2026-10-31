package com.gt8d.launcher.ui.home

import android.content.Context

class HomeHintStore(context: Context) {
    private val preferences = context.getSharedPreferences("home_hint", Context.MODE_PRIVATE)

    fun shouldShow(): Boolean = preferences.getInt("shown_count", 0) < 5

    fun recordShown() {
        val count = preferences.getInt("shown_count", 0)
        if (count < 5) preferences.edit().putInt("shown_count", count + 1).apply()
    }
}
