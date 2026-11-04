package com.gt8d.launcher.media

import android.media.MediaMetadata
import com.gt8d.launcher.media.lyrics.LyricsIdentity
import org.junit.Assert.*
import org.junit.Test

class LyricsIdentityTest {
    private fun ids(standard: Any?, apple: Any?) = LyricsMetadataProbe.readValues {
        if (it == MediaMetadata.METADATA_KEY_MEDIA_ID) standard else apple
    }

    @Test fun unknownPackageAndConflictingIdsRejected() {
        val pkg = "com.apple.android.music"
        assertEquals(LyricsIdentity("123"), lyricsIdentity(pkg, ids("123", "123")))
        assertEquals(LyricsIdentity("123"), lyricsIdentity(pkg, ids("123", null)))
        assertEquals(LyricsIdentity(), lyricsIdentity("unknown.pkg", ids("123", "123")))
        assertEquals(LyricsIdentity(), lyricsIdentity(pkg, ids("123", "456")))
        assertEquals(LyricsIdentity(), lyricsIdentity(pkg, ids(null, "123")))
        assertEquals(LyricsIdentity(), lyricsIdentity(pkg, ids("123", 123L)))
        for (value in listOf(null, "", "0", "000", "-1", " 123", "１２３", "song:123", 123L)) {
            assertEquals("value=$value", LyricsIdentity(), lyricsIdentity(pkg, ids(value, null)))
        }
    }
}
