package com.gt8d.launcher.media

import android.media.MediaMetadata
import android.os.Parcel

/** 指定した2キーだけを返す。IDとして取得できるのはStringの場合だけ。ログは出さない。 */
object LyricsMetadataProbe {
    private const val APPLE_MEDIA_ID =
        "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"

    data class Value(
        val value: String?,
        val type: String?,
        val diagnosticValue: String?,
    )

    data class Result(val standard: Value, val apple: Value)

    fun read(metadata: MediaMetadata?): Result {
        if (metadata == null) return readValues { null }
        val parcel = Parcel.obtain()
        return try {
            // 型付きgetterによる変換を避け、Parcelable表現から元の型を読む。
            metadata.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val bundle = parcel.readBundle(MediaMetadata::class.java.classLoader)
            @Suppress("DEPRECATION")
            readValues { key -> bundle?.get(key) }
        } catch (_: RuntimeException) {
            unreadableResult()
        } finally {
            parcel.recycle()
        }
    }

    internal fun readValues(readValue: (String) -> Any?): Result = Result(
        readValueSafely(MediaMetadata.METADATA_KEY_MEDIA_ID, readValue),
        readValueSafely(APPLE_MEDIA_ID, readValue),
    )

    private fun readValueSafely(key: String, readValue: (String) -> Any?): Value = try {
        val raw = readValue(key)
        Value(
            value = raw as? String,
            type = raw?.javaClass?.name,
            diagnosticValue = when (raw) {
                is String -> raw.take(80)
                is Long, is Int, is Short, is Byte, is Double, is Float, is Boolean -> raw.toString()
                else -> null // 任意オブジェクトのtoStringや他のmetadataは公開しない。
            },
        )
    } catch (_: RuntimeException) {
        unreadableValue()
    }

    private fun unreadableValue() = Value(null, "unreadable", null)
    private fun unreadableResult() = Result(unreadableValue(), unreadableValue())
}
