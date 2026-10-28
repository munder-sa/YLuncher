package com.gt8d.launcher.split

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class SplitMode { Vendor, AppPair, LeftOnly }

@Serializable
data class SplitModeState(
    val vendorUsable: Boolean = true,
    val consecutiveFailures: Int = 0,
    val lastFailure: FailReason? = null,
)

interface SplitModeStore {
    fun load(): SplitModeState
    fun save(state: SplitModeState)
}

class FileSplitModeStore(private val file: File) : SplitModeStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun load(): SplitModeState = runCatching {
        if (!file.exists()) return SplitModeState()
        json.decodeFromString(SplitModeState.serializer(), file.readText())
    }.getOrDefault(SplitModeState())

    override fun save(state: SplitModeState) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SplitModeState.serializer(), state))
        }
    }
}

class SplitModeSelector(
    private val store: SplitModeStore,
    private val dReady: () -> Boolean,
) {
    var state: SplitModeState = store.load()
        private set

    fun current(): SplitMode = when {
        state.vendorUsable -> SplitMode.Vendor
        dReady() -> SplitMode.AppPair
        else -> SplitMode.LeftOnly
    }

    fun onSuccess() {
        update(state.copy(consecutiveFailures = 0))
    }

    fun onFailure(reason: FailReason, countable: Boolean) {
        val next = if (reason == FailReason.EntryMissing) {
            state.copy(
                vendorUsable = false,
                lastFailure = reason,
            )
        } else {
            val nextFailures = if (countable) state.consecutiveFailures + 1 else state.consecutiveFailures
            val nextUsable = if (nextFailures >= 3) false else state.vendorUsable
            state.copy(
                vendorUsable = nextUsable,
                consecutiveFailures = nextFailures,
                lastFailure = reason,
            )
        }
        update(next)
    }

    fun onBoot(entryFound: Boolean) {
        val next = if (entryFound) {
            state.copy(
                vendorUsable = true,
                consecutiveFailures = 0,
            )
        } else {
            state.copy(vendorUsable = false)
        }
        update(next)
    }

    fun restoreVendor() {
        update(
            state.copy(
                vendorUsable = true,
                consecutiveFailures = 0,
            ),
        )
    }

    private fun update(newState: SplitModeState) {
        state = newState
        store.save(newState)
    }
}
