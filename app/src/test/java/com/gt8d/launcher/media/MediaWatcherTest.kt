package com.gt8d.launcher.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.diag.DiagLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaWatcherTest {
    private val apple = "com.apple.android.music"
    private val favorite = "com.apple.android.music.playback.action.FAVORITE"

    @Test fun validatedNetworkTrackerRetriesOnAvailableAndValidatedRisingEdgeOnly() {
        var retries = 0
        val tracker = ValidatedNetworkTracker<String> { retries++ }
        tracker.onAvailable("net1")
        assertEquals(1, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = false)
        assertEquals(1, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = true)
        assertEquals(2, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = true)
        assertEquals(2, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = false)
        assertEquals(2, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = true)
        assertEquals(3, retries)
        tracker.onLost("net1")
        assertEquals(3, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = true)
        assertEquals(4, retries)

        tracker.onCapabilitiesChanged("net2", isValidated = true)
        assertEquals(5, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = true)
        assertEquals(5, retries)
        tracker.clear()
        assertEquals(5, retries)
        tracker.onCapabilitiesChanged("net1", isValidated = true)
        assertEquals(6, retries)
        tracker.onCapabilitiesChanged("net2", isValidated = true)
        assertEquals(7, retries)
    }

    @Test fun notificationArtworkDiagResetsAfterArtworkRecoveryRemovalAndClear() {
        val logs = mutableListOf<Pair<String, Boolean>>()
        val diag = NotificationArtworkDiag { pkg, hasLargeIcon -> logs += pkg to hasLargeIcon }
        val missing = setOf(apple)
        diag.onPosted(apple, missing, hasMediaSession = false, hasLargeIcon = true)
        diag.onPosted("other", missing, hasMediaSession = true, hasLargeIcon = true)
        assertTrue(logs.isEmpty())

        repeat(2) {
            diag.onPosted(apple, missing, hasMediaSession = true, hasLargeIcon = true)
        }
        assertEquals(listOf(apple to true), logs)

        diag.onPosted("other", emptySet(), hasMediaSession = false, hasLargeIcon = false)
        assertEquals(listOf(apple to true), logs)
        diag.onPosted(apple, missing, hasMediaSession = true, hasLargeIcon = true)
        assertEquals(List(2) { apple to true }, logs)

        diag.onRemoved(null)
        diag.onPosted(apple, missing, hasMediaSession = true, hasLargeIcon = true)
        assertEquals(List(2) { apple to true }, logs)
        diag.onRemoved(apple)
        diag.onPosted(apple, missing, hasMediaSession = true, hasLargeIcon = true)
        assertEquals(List(3) { apple to true }, logs)

        diag.clear()
        diag.onPosted(apple, missing, hasMediaSession = true, hasLargeIcon = true)
        assertEquals(List(4) { apple to true }, logs)
    }

    @Test fun hundredPositionCallbacksDoNotReadAnyControllerOrLabelAgain() {
        val art = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val sessions = listOf(
            Controller(apple, metadata("First", "123", art), state()),
            Controller("second", metadata("Second"), state(PlaybackState.STATE_PAUSED)),
            Controller("third", metadata("Third"), state(PlaybackState.STATE_STOPPED)),
        )
        val h = Harness()
        h.cache.replaceControllers(sessions)
        assertEquals(listOf(1, 1, 1), sessions.map { it.metadataReads })
        assertEquals(listOf(1, 1, 1), sessions.map { it.stateReads })
        assertEquals(mapOf(apple to 1, "second" to 1, "third" to 1), h.access.labelReads)
        val logs = DiagLog.dump()
        repeat(100) { index ->
            val position = index * 100L
            sessions[0].callback.onPlaybackStateChanged(state(position = position, updatedAt = 5000 + index.toLong()))
            assertEquals(position, h.now!!.positionMs)
            assertEquals(5000 + index.toLong(), h.now!!.positionUpdatedAt)
            assertEquals("First", h.now!!.title)
            assertEquals("123", h.now!!.lyricsIdentity.appleMusicId)
            assertSame(art, h.now!!.artwork)
        }
        assertSame(sessions[0], h.cache.selectedController)
        assertEquals(listOf(1, 1, 1), sessions.map { it.metadataReads })
        assertEquals(listOf(1, 1, 1), sessions.map { it.stateReads })
        assertEquals(mapOf(apple to 1, "second" to 1, "third" to 1), h.access.labelReads)
        assertEquals(logs, DiagLog.dump())
        assertFalse(art.isRecycled)
    }

    @Test fun artworkDiagLogsDelayedSameTitleMetadataWithoutLoggingPositionCallbacks() {
        DiagLog.clearForTest()
        val initialMetadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.com/a.jpg?token=secret")
            .build()
        val controller = Controller(apple, initialMetadata, state())
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        val initialLogs = DiagLog.dump().filter { "[Artwork]" in it }
        assertEquals(1, initialLogs.size)
        assertTrue(initialLogs.single().contains("[Artwork] meta pkg=$apple "))
        assertTrue(initialLogs.single().contains("bmp=null"))
        assertTrue(initialLogs.single().contains("uri=ALBUM_ART_URI:https"))
        assertTrue(initialLogs.none { "example.com" in it || "secret" in it })
        assertNull(h.now!!.artwork)

        val art = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val delayedMetadata = MediaMetadata.Builder(initialMetadata)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .build()
        controller.callback.onMetadataChanged(delayedMetadata)
        val delayedLogs = DiagLog.dump().filter { "[Artwork]" in it }
        assertEquals(2, delayedLogs.size)
        assertTrue(delayedLogs.last().contains("[Artwork] meta pkg=$apple "))
        assertTrue(delayedLogs.last().contains("bmp=ALBUM_ART:2x3"))
        assertEquals("Song", h.now!!.title)
        assertSame(art, h.now!!.artwork)

        repeat(2) {
            controller.callback.onMetadataChanged(delayedMetadata)
        }
        assertEquals(delayedLogs, DiagLog.dump().filter { "[Artwork]" in it })
        repeat(10) { position ->
            controller.callback.onPlaybackStateChanged(state(position = position.toLong()))
        }
        val finalLogs = DiagLog.dump().filter { "[Artwork]" in it }
        assertEquals(delayedLogs, finalLogs)
        assertTrue(finalLogs.none { "example.com" in it || "secret" in it })
    }

    @Test fun uriFallbackFillsArtworkUntilInlineBitmapArrives() {
        val uri = "https://example.test/art.png"
        val initial = MediaMetadata.Builder(metadata("Song"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, uri)
            .build()
        val controller = Controller(apple, initial, state())
        val fallback = FakeArtworkFallback()
        val h = Harness(fallback)
        h.cache.replaceControllers(listOf(controller))
        assertEquals(listOf(uri), fallback.requests.map { it.uri })
        assertNull(h.now!!.artwork)
        assertEquals(setOf(apple), h.cache.activePackagesMissingArtwork())
        controller.callback.onMetadataChanged(initial)
        assertEquals(1, fallback.requests.size)
        val logs = DiagLog.dump()
        val downloaded = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val request = fallback.requests.single()
        request.onResult(downloaded)
        assertSame(downloaded, h.now!!.artwork)
        assertTrue(h.cache.activePackagesMissingArtwork().isEmpty())
        controller.callback.onMetadataChanged(initial)
        assertSame(downloaded, h.now!!.artwork)
        repeat(5) { position ->
            controller.callback.onPlaybackStateChanged(state(position = position.toLong()))
        }
        assertEquals(1, fallback.requests.size)
        assertEquals(1, controller.metadataReads)
        assertEquals(1, controller.stateReads)
        assertEquals(logs, DiagLog.dump())
        assertFalse(request.cancelled)
        val inline = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        controller.callback.onMetadataChanged(MediaMetadata.Builder(initial)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, inline).build())
        assertSame(inline, h.now!!.artwork)
        assertTrue(request.cancelled)
        val published = h.published
        request.onResult(downloaded)
        assertSame(inline, h.now!!.artwork)
        assertEquals(published, h.published)
        assertEquals(1, fallback.requests.size)
    }

    @Test fun uriFallbackIgnoresStaleResultsAndNonHttpUris() {
        val first = MediaMetadata.Builder(metadata("First"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.test/first.png")
            .build()
        val second = MediaMetadata.Builder(metadata("Second"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.test/second.png")
            .build()
        val controller = Controller(apple, first, state())
        val fallback = FakeArtworkFallback()
        val h = Harness(fallback)
        h.cache.replaceControllers(listOf(controller))
        val old = fallback.requests.single()
        controller.callback.onMetadataChanged(second)
        assertTrue(old.cancelled)
        assertEquals(2, fallback.requests.size)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val changed = h.published
        old.onResult(bitmap)
        assertNull(h.now!!.artwork)
        assertEquals(changed, h.published)
        controller.callback.onMetadataChanged(MediaMetadata.Builder(second)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://artwork/second")
            .putString(MediaMetadata.METADATA_KEY_ART_URI, "https://example.test/ignored.png")
            .build())
        assertEquals(2, fallback.requests.size)
        assertTrue(fallback.requests[1].cancelled)
        assertNull(h.now!!.artwork)
        fallback.requests[1].onResult(bitmap)
        assertNull(h.now!!.artwork)
        controller.callback.onMetadataChanged(second)
        assertEquals(3, fallback.requests.size)
        val last = fallback.requests.last()
        controller.callback.onSessionDestroyed()
        assertTrue(last.cancelled)
        assertNull(h.now)
        val destroyed = h.published
        last.onResult(bitmap)
        assertNull(h.now)
        assertEquals(destroyed, h.published)
        assertTrue(h.cache.controllers.isEmpty())
    }

    @Test fun uriFallbackUsesUriPriorityAndCancelsOnMissingMetadataReplacementAndClear() {
        val initial = MediaMetadata.Builder(metadata("Song"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "")
            .putString(MediaMetadata.METADATA_KEY_ART_URI, "HTTP://example.test/art.png")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, "https://example.test/icon.png")
            .build()
        val controller = Controller(apple, initial, state())
        val fallback = FakeArtworkFallback()
        val h = Harness(fallback)
        h.cache.replaceControllers(listOf(controller))
        assertEquals("HTTP://example.test/art.png", fallback.requests.single().uri)
        val iconOnly = MediaMetadata.Builder(initial)
            .putString(MediaMetadata.METADATA_KEY_ART_URI, "").build()
        controller.callback.onMetadataChanged(iconOnly)
        assertTrue(fallback.requests[0].cancelled)
        assertEquals("https://example.test/icon.png", fallback.requests[1].uri)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        fallback.requests[1].onResult(bitmap)
        assertSame(bitmap, h.now!!.artwork)
        controller.callback.onMetadataChanged(null)
        assertTrue(fallback.requests[1].cancelled)
        assertNull(h.now!!.artwork)
        controller.callback.onMetadataChanged(initial)
        val replaced = fallback.requests.last()
        h.cache.replaceControllers(listOf(controller))
        assertTrue(replaced.cancelled)
        val published = h.published
        replaced.onResult(bitmap)
        assertEquals(published, h.published)
        assertNull(h.now!!.artwork)
        val cleared = fallback.requests.last()
        h.cache.clear()
        assertTrue(cleared.cancelled)
        val stopped = h.published
        cleared.onResult(bitmap)
        assertNull(h.now)
        assertEquals(stopped, h.published)
    }

    @Test fun initialMetadataFailureRecoversFullSnapshotOnPlaybackCallback() {
        val art = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val controller = Controller(apple, metadata("Recovered", "123", art), state(position = 42))
        controller.metadataFailures = 1
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        assertNull(h.now)
        assertEquals(1, controller.metadataReads)
        assertEquals(0, controller.stateReads)

        controller.callback.onPlaybackStateChanged(state(position = 7000, updatedAt = 5000, action = favorite))
        assertEquals("Recovered", h.now!!.title)
        assertEquals("Artist Recovered", h.now!!.artist)
        assertEquals("Album Recovered", h.now!!.album)
        assertEquals("Recovered", h.now!!.metaTitle)
        assertEquals(180000L, h.now!!.durationMs)
        assertSame(art, h.now!!.artwork)
        assertEquals("123", h.now!!.lyricsIdentity.appleMusicId)
        assertNotNull(h.now!!.lyricsRequest)
        assertEquals(7000L, h.now!!.positionMs)
        assertEquals(5000L, h.now!!.positionUpdatedAt)
        assertTrue(h.now!!.playing)
        assertTrue(h.now!!.hasFavorite)
        assertSame(controller, h.cache.selectedController)
        assertEquals(2, controller.metadataReads)
        assertEquals(1, controller.stateReads)

        controller.callback.onPlaybackStateChanged(state(position = 8000))
        assertEquals("Recovered", h.now!!.title)
        assertEquals(8000L, h.now!!.positionMs)
        assertEquals(2, controller.metadataReads)
        assertEquals(1, controller.stateReads)
    }

    @Test fun failedSnapshotRecoveryStillAppliesPlaybackCallback() {
        val controller = Controller(apple, metadata("Song", "123"), state())
        controller.metadataFailures = 2
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        assertNull(h.now)
        controller.callback.onPlaybackStateChanged(state(position = 7000, updatedAt = 5000, action = favorite))
        assertEquals("Label $apple", h.now!!.title)
        assertNull(h.now!!.metaTitle)
        assertNull(h.now!!.lyricsRequest)
        assertEquals(7000L, h.now!!.positionMs)
        assertEquals(5000L, h.now!!.positionUpdatedAt)
        assertTrue(h.now!!.playing)
        assertTrue(h.now!!.hasFavorite)
        assertEquals(2, controller.metadataReads)
        assertEquals(0, controller.stateReads)
    }

    @Test fun metadataAndNullCallbacksReplaceOnlyMetadataIncludingLyricsAndArtwork() {
        val firstArt = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val nextArt = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val controller = Controller(apple, metadata("First", "123", firstArt), state(position = 42))
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        controller.callback.onMetadataChanged(metadata("Next", "456", nextArt))
        assertEquals("Next", h.now!!.title)
        assertEquals("Artist Next", h.now!!.artist)
        assertEquals("Album Next", h.now!!.album)
        assertEquals("Next", h.now!!.metaTitle)
        assertEquals(180000L, h.now!!.durationMs)
        assertEquals("456", h.now!!.lyricsIdentity.appleMusicId)
        assertSame(nextArt, h.now!!.artwork)
        assertEquals(42L, h.now!!.positionMs)
        assertTrue(h.now!!.playing)
        controller.callback.onMetadataChanged(null)
        assertEquals("Label $apple", h.now!!.title)
        assertNull(h.now!!.metaTitle)
        assertNull(h.now!!.artist)
        assertNull(h.now!!.album)
        assertNull(h.now!!.durationMs)
        assertNull(h.now!!.artwork)
        assertNull(h.now!!.lyricsIdentity.appleMusicId)
        assertNull(h.now!!.lyricsRequest)
        assertEquals(42L, h.now!!.positionMs)
        assertTrue(h.now!!.playing)
        assertEquals(1, controller.metadataReads)
        assertEquals(1, controller.stateReads)
        assertEquals(mapOf(apple to 1), h.access.labelReads)
        assertFalse(firstArt.isRecycled)
        assertFalse(nextArt.isRecycled)
    }

    @Test fun nullPlaybackCallbackClearsStateWithoutRestoringControllerValues() {
        val controller = Controller(apple, metadata("Song", "123"), state(position = 42, action = favorite))
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        assertTrue(h.now!!.hasFavorite)
        controller.callback.onPlaybackStateChanged(null)
        assertFalse(h.now!!.playing)
        assertFalse(h.now!!.hasFavorite)
        assertEquals(0L, h.now!!.positionMs)
        assertEquals(0L, h.now!!.positionUpdatedAt)
        assertEquals(1f, h.now!!.speed)
        assertEquals("Song", h.now!!.title)
        assertEquals("123", h.now!!.lyricsIdentity.appleMusicId)
        assertEquals(1, controller.stateReads)
        assertEquals(1, controller.metadataReads)
    }

    @Test fun pauseResumeSeekAndFavoriteUseCallbackStateAndOriginalClock() {
        val controller = Controller(apple, metadata("Song"), state())
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        controller.callback.onPlaybackStateChanged(state(PlaybackState.STATE_PAUSED, 3000, 1000, 0f, favorite))
        assertFalse(h.now!!.playing)
        assertTrue(h.now!!.hasFavorite)
        assertEquals(3000L, h.now!!.positionAt(6000))
        controller.callback.onPlaybackStateChanged(state(position = 4000, updatedAt = 2000, speed = 2f))
        assertTrue(h.now!!.playing)
        assertFalse(h.now!!.hasFavorite)
        assertEquals(10000L, h.now!!.positionAt(5000))
        controller.callback.onPlaybackStateChanged(state(position = 70000, updatedAt = 5000, action = "unrelated"))
        assertFalse(h.now!!.hasFavorite)
        assertEquals(71000L, h.now!!.positionAt(6000))
        assertEquals(1, controller.stateReads)
        assertEquals(1, controller.metadataReads)
    }

    @Test fun selectionPreservesPausedFallbackAndLastPlayingPackage() {
        val first = Controller("first", metadata("First"), state(PlaybackState.STATE_PAUSED, updatedAt = 100))
        val second = Controller("second", metadata("Second"), state(PlaybackState.STATE_PAUSED, updatedAt = 200))
        val h = Harness()
        h.cache.replaceControllers(listOf(first, second))
        assertSame(second, h.cache.selectedController)
        first.callback.onPlaybackStateChanged(state())
        assertSame(first, h.cache.selectedController)
        second.callback.onPlaybackStateChanged(state())
        assertSame(first, h.cache.selectedController)
        first.callback.onPlaybackStateChanged(state(PlaybackState.STATE_PAUSED))
        assertSame(second, h.cache.selectedController)
        second.callback.onPlaybackStateChanged(state(PlaybackState.STATE_STOPPED))
        assertSame(second, h.cache.selectedController)
        second.callback.onSessionDestroyed()
        assertSame(first, h.cache.selectedController)
        assertEquals(listOf(first), h.cache.controllers)
    }

    @Test fun samePackageSessionsStayIndependentAndDestroyedSessionCannotReturn() {
        val art = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val first = Controller(apple, metadata("First"), state(PlaybackState.STATE_PAUSED))
        val second = Controller(apple, metadata("Second", artwork = art), state())
        val h = Harness()
        h.cache.replaceControllers(listOf(first, second))
        val destroyedCallback = second.callback
        assertSame(second, h.cache.selectedController)
        assertEquals(mapOf(apple to 1), h.access.labelReads)
        first.callback.onMetadataChanged(metadata("Changed first"))
        assertEquals("Second", h.now!!.title)
        destroyedCallback.onSessionDestroyed()
        assertSame(first, h.cache.selectedController)
        assertEquals("Changed first", h.now!!.title)
        assertNull(h.now!!.artwork)
        assertEquals(listOf(destroyedCallback), second.unregistered)
        val published = h.published
        destroyedCallback.onPlaybackStateChanged(state())
        destroyedCallback.onMetadataChanged(metadata("Stale", artwork = art))
        destroyedCallback.onSessionDestroyed()
        assertEquals(published, h.published)
        assertEquals("Changed first", h.now!!.title)
        first.callback.onSessionDestroyed()
        assertNull(h.cache.selectedController)
        assertNull(h.now)
        assertTrue(h.cache.controllers.isEmpty())
        assertFalse(art.isRecycled)
    }

    @Test fun replacementInvalidatesCallbacksEvenWhenControllerObjectIsReused() {
        val controller = Controller(apple, metadata("First"), state())
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        val old = controller.callback
        controller.metadata = metadata("Replacement")
        h.access.labels[apple] = "Replacement label"
        h.cache.replaceControllers(listOf(controller))
        assertEquals(listOf(old), controller.unregistered)
        assertNotSame(old, controller.callback)
        val published = h.published
        old.onMetadataChanged(null)
        old.onPlaybackStateChanged(null)
        old.onSessionDestroyed()
        assertEquals(published, h.published)
        assertEquals("Replacement", h.now!!.title)
        controller.callback.onMetadataChanged(null)
        assertEquals("Replacement label", h.now!!.title)
        assertEquals(2, controller.metadataReads)
        assertEquals(2, controller.stateReads)
        assertEquals(mapOf(apple to 2), h.access.labelReads)
    }

    @Test fun stopAndRestartReleaseOutputAndRejectOldCallbacks() {
        val art = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val controller = Controller(apple, metadata("Song", artwork = art), state())
        val h = Harness()
        h.cache.replaceControllers(listOf(controller))
        val old = controller.callback
        h.cache.clear()
        assertNull(h.now)
        assertNull(h.cache.selectedController)
        assertTrue(h.cache.controllers.isEmpty())
        val stopped = h.published
        old.onMetadataChanged(metadata("Stale", artwork = art))
        old.onPlaybackStateChanged(state())
        old.onSessionDestroyed()
        assertEquals(stopped, h.published)
        h.access.labels[apple] = "Restarted label"
        controller.metadata = null
        h.cache.replaceControllers(listOf(controller))
        assertEquals("Restarted label", h.now!!.title)
        assertNull(h.now!!.artwork)
        val restarted = h.published
        old.onSessionDestroyed()
        assertEquals(restarted, h.published)
        assertSame(controller, h.cache.selectedController)
        assertEquals(2, controller.metadataReads)
        assertEquals(2, controller.stateReads)
        assertEquals(mapOf(apple to 2), h.access.labelReads)
        assertFalse(art.isRecycled)
    }

    @Test fun labelInvalidationRefreshesAffectedPackagesWithoutControllerReads() {
        val first = Controller(apple, null, state())
        val samePackage = Controller(apple, null, state(PlaybackState.STATE_PAUSED))
        val other = Controller("other", null, state(PlaybackState.STATE_PAUSED))
        val h = Harness()
        val controllers = listOf(first, samePackage, other)
        h.cache.replaceControllers(controllers)
        h.access.labels[apple] = "Updated application"
        h.cache.invalidateLabels(apple)
        assertEquals("Updated application", h.now!!.title)
        assertEquals(mapOf(apple to 2, "other" to 1), h.access.labelReads)
        h.access.labels[apple] = "Localized application"
        h.access.labels["other"] = "Localized other"
        h.cache.invalidateLabels()
        assertEquals("Localized application", h.now!!.title)
        assertEquals(mapOf(apple to 3, "other" to 2), h.access.labelReads)
        h.cache.invalidateLabels("not.active")
        assertEquals(mapOf(apple to 3, "other" to 2), h.access.labelReads)
        first.callback.onSessionDestroyed()
        assertEquals("Localized application", h.now!!.title)
        samePackage.callback.onSessionDestroyed()
        other.callback.onPlaybackStateChanged(state())
        assertEquals("Localized other", h.now!!.title)
        assertEquals(listOf(1, 1, 1), controllers.map { it.metadataReads })
        assertEquals(listOf(1, 1, 1), controllers.map { it.stateReads })
        h.cache.clear()
        h.cache.invalidateLabels()
        assertEquals(mapOf(apple to 3, "other" to 2), h.access.labelReads)
    }

    @Test
    @Config(shadows = [ControllerShadow::class, TransportShadow::class])
    fun watcherWiringRoutesTransportAndPairsReceiverLifecycleAcrossRestart() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(MediaSessionManager::class.java)
        val managerShadow = Shadows.shadowOf(manager)
        val first = MediaSession(app, "first")
        val second = MediaSession(app, "second")
        val firstController = Shadow.extract<ControllerShadow>(first.controller)
        val secondController = Shadow.extract<ControllerShadow>(second.controller)
        firstController.state = state(PlaybackState.STATE_PAUSED)
        secondController.state = state(action = favorite)
        firstController.metadataValue = metadata("First")
        secondController.metadataValue = metadata("Second")
        val registered = mutableListOf<BroadcastReceiver>()
        val unregistered = mutableListOf<BroadcastReceiver>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
                if (receiver != null) registered += receiver
                return super.registerReceiver(receiver, filter, flags)
            }
            override fun unregisterReceiver(receiver: BroadcastReceiver) {
                unregistered += receiver
                super.unregisterReceiver(receiver)
            }
        }
        try {
            managerShadow.addController(first.controller)
            managerShadow.addController(second.controller)
            MediaWatcher.start(context)
            assertEquals("Second", MediaWatcher.nowPlaying.value!!.title)
            assertEquals(2, registered.size)
            assertSame(registered[0], registered[1])
            assertTrue(unregistered.isEmpty())
            val oldCallback = secondController.callbacks.single()
            repeat(100) { position ->
                oldCallback.onPlaybackStateChanged(state(position = position.toLong(), action = favorite))
            }
            assertEquals(99L, MediaWatcher.nowPlaying.value!!.positionMs)
            assertEquals(1, firstController.metadataReads)
            assertEquals(1, firstController.stateReads)
            assertEquals(1, secondController.metadataReads)
            assertEquals(1, secondController.stateReads)
            MediaWatcher.play()
            MediaWatcher.pause()
            MediaWatcher.next()
            MediaWatcher.previous()
            MediaWatcher.seekTo(42000)
            MediaWatcher.favorite()
            val firstTransport = Shadow.extract<TransportShadow>(firstController.controls)
            val secondTransport = Shadow.extract<TransportShadow>(secondController.controls)
            assertTrue(firstTransport.actions.isEmpty())
            assertEquals(listOf("play", "pause", "next", "previous", "seek:42000", favorite), secondTransport.actions)

            oldCallback.onMetadataChanged(null)
            val info = Shadows.shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).applicationInfo!!
            info.nonLocalizedLabel = "Updated label"
            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:${app.packageName}")))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Updated label", MediaWatcher.nowPlaying.value!!.title)
            info.nonLocalizedLabel = "Localized label"
            app.sendBroadcast(Intent(Intent.ACTION_LOCALE_CHANGED))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Localized label", MediaWatcher.nowPlaying.value!!.title)
            assertEquals(1, secondController.metadataReads)
            assertEquals(2, secondController.stateReads) // favorite() reads the current transport action.

            val listenerField = MediaWatcher::class.java.getDeclaredField("sessionsChangedListener").apply { isAccessible = true }
            val oldListener = listenerField.get(MediaWatcher) as MediaSessionManager.OnActiveSessionsChangedListener
            MediaWatcher.stop()
            assertNull(MediaWatcher.nowPlaying.value)
            assertEquals(listOf(registered[0]), unregistered)
            assertTrue(firstController.callbacks.isEmpty())
            assertTrue(secondController.callbacks.isEmpty())
            oldListener.onActiveSessionsChanged(mutableListOf(second.controller))
            oldCallback.onPlaybackStateChanged(state())
            assertNull(MediaWatcher.nowPlaying.value)
            MediaWatcher.play()
            assertEquals(6, secondTransport.actions.size)

            MediaWatcher.start(context)
            assertEquals("Second", MediaWatcher.nowPlaying.value!!.title)
            assertEquals(4, registered.size)
            assertNotSame(registered[0], registered[2])
            oldListener.onActiveSessionsChanged(mutableListOf())
            oldCallback.onMetadataChanged(null)
            oldCallback.onSessionDestroyed()
            assertEquals("Second", MediaWatcher.nowPlaying.value!!.title)
            secondController.callbacks.single().onSessionDestroyed()
            assertEquals("First", MediaWatcher.nowPlaying.value!!.title)
            MediaWatcher.play()
            assertEquals(listOf("play"), firstTransport.actions)
        } finally {
            MediaWatcher.stop()
            first.release()
            second.release()
        }
        assertEquals(listOf(registered[0], registered[2]), unregistered)
    }

    /** The session/controller construction is real; these shadows model remote callbacks and IPC. */
    @Implements(MediaController::class)
    class ControllerShadow {
        var metadataValue: MediaMetadata? = null
        var state: PlaybackState? = null
        var metadataReads = 0
        var stateReads = 0
        val callbacks = mutableListOf<MediaController.Callback>()
        val controls: MediaController.TransportControls = Shadow.newInstanceOf(MediaController.TransportControls::class.java)
        @Implementation fun getPackageName(): String = ApplicationProvider.getApplicationContext<Application>().packageName
        @Implementation fun getMetadata(): MediaMetadata? { metadataReads++; return metadataValue }
        @Implementation fun getPlaybackState(): PlaybackState? { stateReads++; return state }
        @Implementation fun getTransportControls() = controls
        @Implementation fun registerCallback(callback: MediaController.Callback) { callbacks += callback }
        @Implementation fun unregisterCallback(callback: MediaController.Callback) { callbacks -= callback }
    }

    @Implements(MediaController.TransportControls::class)
    class TransportShadow {
        val actions = mutableListOf<String>()
        @Implementation fun play() { actions += "play" }
        @Implementation fun pause() { actions += "pause" }
        @Implementation fun skipToNext() { actions += "next" }
        @Implementation fun skipToPrevious() { actions += "previous" }
        @Implementation fun seekTo(position: Long) { actions += "seek:$position" }
        @Implementation fun sendCustomAction(action: PlaybackState.CustomAction, extras: Bundle?) { actions += action.action }
    }

    private class Controller(
        val pkg: String,
        var metadata: MediaMetadata?,
        var state: PlaybackState?,
    ) {
        var metadataFailures = 0
        var metadataReads = 0
        var stateReads = 0
        lateinit var callback: MediaController.Callback
        val unregistered = mutableListOf<MediaController.Callback>()
    }

    private class Access : MediaSessionCache.Access<Controller> {
        val labelReads = mutableMapOf<String, Int>()
        val labels = mutableMapOf<String, String>()
        override fun packageName(controller: Controller) = controller.pkg
        override fun metadata(controller: Controller): MediaMetadata? {
            controller.metadataReads++
            if (controller.metadataFailures > 0) {
                controller.metadataFailures--
                throw IllegalStateException("temporary metadata read failure")
            }
            return controller.metadata
        }
        override fun playbackState(controller: Controller): PlaybackState? {
            controller.stateReads++
            return controller.state
        }
        override fun appLabel(pkg: String): String {
            labelReads[pkg] = (labelReads[pkg] ?: 0) + 1
            return labels[pkg] ?: "Label $pkg"
        }
        override fun register(controller: Controller, callback: MediaController.Callback) {
            controller.callback = callback
        }
        override fun unregister(controller: Controller, callback: MediaController.Callback) {
            controller.unregistered += callback
        }
    }

    private class FakeArtworkFallback : ArtworkFallback {
        class Request(val uri: String, val onResult: (Bitmap) -> Unit) {
            var cancelled = false
        }
        val requests = mutableListOf<Request>()
        override fun request(uri: String, onResult: (Bitmap) -> Unit): ArtworkFallback.Cancel {
            val request = Request(uri, onResult)
            requests += request
            return ArtworkFallback.Cancel { request.cancelled = true }
        }
    }

    private class Harness(fallback: ArtworkFallback? = null) {
        val access = Access()
        var now: NowPlaying? = null
        var published = 0
        val cache = if (fallback == null) {
            MediaSessionCache(access) { value -> now = value; published++ }
        } else {
            MediaSessionCache(access, { value -> now = value; published++ }, fallback)
        }
    }

    private fun metadata(title: String, id: String? = null, artwork: Bitmap? = null) =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist $title")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Album $title")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            .build()

    private fun state(
        status: Int = PlaybackState.STATE_PLAYING,
        position: Long = 1000,
        updatedAt: Long = 1000,
        speed: Float = 1f,
        action: String? = null,
    ): PlaybackState = PlaybackState.Builder()
        .setState(status, position, speed, updatedAt)
        .apply { if (action != null) addCustomAction(action, "Action", 1) }
        .build()
}
