package com.gt8d.launcher.media

import android.media.MediaMetadata
import android.os.Parcel
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsMetadataProbeTest {
    private val standard = MediaMetadata.METADATA_KEY_MEDIA_ID
    private val apple = "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"

    @Test fun readsOnlyTwoAllowedKeys() {
        val keys = mutableListOf<String>()
        val result = LyricsMetadataProbe.readValues { key ->
            keys += key
            when (key) { standard -> "123"; apple -> "456"; else -> error("Unexpected key") }
        }
        assertEquals(listOf(standard, apple), keys)
        assertEquals("123", result.standard.value)
        assertEquals("456", result.apple.value)
        assertEquals("java.lang.String", result.standard.type)
    }

    @Test fun missingAndWrongTypeAreSafe() {
        val missing = LyricsMetadataProbe.read(null)
        assertNull(missing.standard.value)
        assertNull(missing.apple.type)
        val wrong = LyricsMetadataProbe.read(metadata(Bundle().apply {
            putLong(standard, 123L)
            putBoolean(apple, true)
        }))
        assertNull(wrong.standard.value)
        assertNull(wrong.apple.value)
        assertEquals("java.lang.Long", wrong.standard.type)
        assertEquals("123", wrong.standard.diagnosticValue)
        assertEquals("java.lang.Boolean", wrong.apple.type)
        val failed = LyricsMetadataProbe.readValues { throw IllegalStateException("private data") }
        assertNull(failed.standard.value)
        assertFalse(failed.toString().contains("private data"))
    }

    @Test fun doesNotExposeLyricsOrOtherMetadata() {
        val longId = "x".repeat(81)
        val result = LyricsMetadataProbe.read(metadata(Bundle().apply {
            putString(standard, longId)
            putString(apple, "456")
            putString("lyrics", "PRIVATE LYRICS")
            putString(MediaMetadata.METADATA_KEY_ARTIST, "PRIVATE ARTIST")
        }))
        assertEquals(longId, result.standard.value)
        assertEquals("x".repeat(80), result.standard.diagnosticValue)
        assertEquals("456", result.apple.value)
        assertFalse(result.toString().contains("PRIVATE"))
    }

    @Test fun probeUnchangedForPositionUpdates() {
        val original = MediaMetadata.Builder().putString(standard, "123").putString(apple, "456").build()
        val first = LyricsMetadataProbe.read(original)
        repeat(10) { position ->
            val updated = MediaMetadata.Builder(original).putLong("position", position.toLong()).build()
            assertEquals(first, LyricsMetadataProbe.read(updated))
        }
        assertNotEquals(first, LyricsMetadataProbe.read(MediaMetadata.Builder(original).putString(apple, "789").build()))
        assertNotEquals(first, LyricsMetadataProbe.read(MediaMetadata.Builder(original).putString(standard, "789").build()))
    }

    private fun metadata(bundle: Bundle): MediaMetadata {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            MediaMetadata.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
