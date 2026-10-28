package com.gt8d.launcher.sets

object SetNavigator {
    const val HOME = 0

    fun next(current: Int, setCount: Int): Int {
        if (setCount <= 0) return HOME
        val total = setCount + 1
        val normalized = clamp(current, setCount)
        return (normalized + 1) % total
    }

    fun prev(current: Int, setCount: Int): Int {
        if (setCount <= 0) return HOME
        val total = setCount + 1
        val normalized = clamp(current, setCount)
        return (normalized - 1 + total) % total
    }

    fun clamp(saved: Int, setCount: Int): Int {
        if (setCount <= 0) return HOME
        return if (saved in HOME..setCount) saved else HOME
    }
}
