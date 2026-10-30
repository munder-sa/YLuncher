package com.gt8d.launcher.link

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object SiteLabel {
    fun label(siteName: String?, url: String?): String {
        siteName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            url?.let { URI(it).host }?.removePrefix("www.").orEmpty()
        } catch (_: java.net.URISyntaxException) {
            ""
        }
    }

    fun parseJsString(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val value = try {
            Json.parseToJsonElement(raw) as? JsonPrimitive
        } catch (_: IllegalArgumentException) {
            null
        }
        return value?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
    }
}
