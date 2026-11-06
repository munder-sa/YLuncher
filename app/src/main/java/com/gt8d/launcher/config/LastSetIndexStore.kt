package com.gt8d.launcher.config

import com.gt8d.launcher.sets.LastIndexStore
import java.io.File

internal interface LastSetIndexIo {
    /** null means that no persisted value exists; read/parse failures may throw. */
    fun read(): Int?
    fun write(value: Int)
}

internal class LastSetIndexStore(private val io: LastSetIndexIo) : LastIndexStore {
    constructor(file: File) : this(LastSetIndexFile(file))

    private val lock = Any()
    private var loaded = false
    private var cachedValue = 0
    private var persistedValue: Int? = null

    override var lastSetIndex: Int
        get() = synchronized(lock) {
            loadIfNeeded()
            cachedValue
        }
        set(value) = synchronized(lock) {
            loadIfNeeded()
            if (persistedValue == value) return@synchronized

            // A failed write may have truncated the file, so no value is safe to skip.
            persistedValue = null
            io.write(value)
            cachedValue = value
            persistedValue = value
            loaded = true
        }

    private fun loadIfNeeded() {
        if (loaded) return
        persistedValue = try {
            io.read()
        } catch (_: NumberFormatException) {
            null
        } catch (_: Exception) {
            return
        }
        cachedValue = persistedValue ?: 0
        loaded = true
    }
}

private class LastSetIndexFile(private val file: File) : LastSetIndexIo {
    override fun read(): Int? = if (file.exists()) file.readText().trim().toInt() else null

    override fun write(value: Int) {
        file.parentFile?.mkdirs()
        file.writeText(value.toString())
    }
}
