package com.gt8d.launcher.link

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object SiteLabel {
    fun label(siteName: String?, url: String?): String {
        return label(siteName, null, null, url)
    }

    fun fromTitle(title: String?): String? {
        if (title == null) return null
        val separator = listOf(" - ", " | ", " – ", " — ", " ｜ ")
            .map { it to title.lastIndexOf(it) }
            .maxByOrNull { it.second } ?: return null
        if (separator.second < 0) return null
        return title.substring(separator.second + separator.first.length).trim()
            .takeIf { it.length in 1..30 }
    }

    fun label(siteName: String?, appName: String?, title: String?, url: String?): String {
        siteName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        appName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        fromTitle(title)?.let { return it }
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
