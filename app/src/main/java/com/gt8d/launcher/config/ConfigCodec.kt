package com.gt8d.launcher.config

import kotlinx.serialization.json.Json

object ConfigCodec {
    private val format = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(c: LauncherConfig): String =
        format.encodeToString(LauncherConfig.serializer(), c)

    fun decode(json: String): LauncherConfig? = runCatching {
        val decoded = format.decodeFromString(LauncherConfig.serializer(), json)
        when {
            decoded.version > CONFIG_VERSION -> null
            decoded.version < CONFIG_VERSION -> decoded.copy(version = CONFIG_VERSION)
            else -> decoded
        }
    }.getOrNull()
}
