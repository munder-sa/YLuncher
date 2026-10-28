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
        if (decoded.version > 1) null else decoded
    }.getOrNull()
}
