package com.gt8d.launcher.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import com.gt8d.launcher.diag.DiagLog

object ConfigCodec {
    private val format = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(c: LauncherConfig): String =
        format.encodeToString(LauncherConfig.serializer(), c)

    fun decode(json: String): LauncherConfig? = runCatching {
        val root = format.parseToJsonElement(json) as JsonObject
        val version = root["version"]?.jsonPrimitive?.int ?: 1
        if (version > CONFIG_VERSION) return@runCatching null
        val migrated = if (version < 3) JsonObject(root + ("screenSets" to JsonArray(
            (root["screenSets"] as JsonArray).map { element ->
                val set = element as JsonObject
                val left = set["left"]
                if (left is JsonObject && "type" !in left) {
                    JsonObject(set + ("left" to JsonObject(left +
                        ("type" to JsonPrimitive("com.gt8d.launcher.config.Slot.App")))))
                } else set
            }
        ))) else root
        val decoded = format.decodeFromJsonElement(LauncherConfig.serializer(), migrated)
        when {
            decoded.version > CONFIG_VERSION -> null
            else -> decoded.copy(version = CONFIG_VERSION, screenSets = decoded.screenSets.filter {
                if (!it.isValid) DiagLog.d("ConfigCodec", "drop set id=${it.id}: both sides are panels")
                it.isValid
            })
        }
    }.getOrNull()
}
