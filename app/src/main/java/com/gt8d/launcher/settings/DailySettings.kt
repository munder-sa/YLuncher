package com.gt8d.launcher.settings

object DailySettings {
    val stripWidthChoices = listOf(24, 48, 72, 96)
    val longPressChoices = listOf(500, 750, 1000, 1500)

    fun longPressLabel(ms: Int): String = when (ms) {
        500 -> "0.5秒"
        750 -> "0.75秒"
        1000 -> "1秒"
        1500 -> "1.5秒"
        else -> "${ms}ミリ秒"
    }

    fun add(list: List<String>, pkg: String, max: Int?): List<String> =
        if (pkg in list || (max != null && list.size >= max)) list else list + pkg

    fun remove(list: List<String>, pkg: String): List<String> = list.filterNot { it == pkg }

    fun moveUp(list: List<String>, pkg: String): List<String> = move(list, pkg, -1)
    fun moveDown(list: List<String>, pkg: String): List<String> = move(list, pkg, 1)

    private fun move(list: List<String>, pkg: String, delta: Int): List<String> {
        val index = list.indexOf(pkg)
        val target = index + delta
        if (index < 0 || target !in list.indices) return list
        return list.toMutableList().apply {
            this[index] = list[target]
            this[target] = pkg
        }
    }

    fun normalizeDomain(input: String): String? {
        val trimmed = input.trim().lowercase()
        if (trimmed.any(Char::isWhitespace)) return null
        return trimmed.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').takeIf { it.isNotEmpty() }
    }
}
