package com.gt8d.launcher.ui.home

import android.graphics.Bitmap
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.ArtworkColors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ArtworkBackgroundTest {
    private val red = ArtworkColors(0xff381818.toInt(), 0xff1c0c0c.toInt())
    private fun bitmap() = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }
    private fun song(bitmap: Bitmap?) = NowPlaying("music", "song", "artist", bitmap, 100L, true, false)

    @Test fun samplePreservesAspectRatio() {
        assertEquals(32 to 16, artworkSampleSize(64, 32))
        assertEquals(1 to 32, artworkSampleSize(1, 64))
        assertEquals(16 to 8, artworkSampleSize(16, 8))
    }

    @Test fun sameArtworkDoesNotReextract() = runTest {
        var calls = 0
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; red })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        holder.update(this, now.copy(positionMs = 20, playing = false, title = "new")); runCurrent()
        assertEquals(1, calls)
        assertEquals(red, holder.colors.value)
        val other = Any()
        holder.update(other, now); runCurrent()
        holder.remove(this)
        assertEquals(1, calls)
        assertEquals(red, holder.colors.value)
        holder.remove(other)
        assertNull(holder.colors.value)
        holder.update(this, now); runCurrent()
        assertEquals(red, holder.colors.value)
        assertEquals(1, calls)
    }

    @Test fun generationChangeReextracts() = runTest {
        var calls = 0
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; red })
        val art = bitmap()
        holder.update(this, song(art)); runCurrent()
        art.eraseColor(android.graphics.Color.BLUE)
        holder.update(this, song(art)); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun failureIsRetained() = runTest {
        var calls = 0
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; null })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        holder.update(this, now.copy(title = "new")); runCurrent()
        assertNull(holder.colors.value)
        assertEquals(1, calls)
    }

    @Test fun simultaneousClientsSharePendingExtraction() = runTest {
        var calls = 0
        val pending = CompletableDeferred<ArtworkColors?>()
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; pending.await() })
        val now = song(bitmap())
        val home = Any(); val panel = Any()
        holder.update(home, now); runCurrent()
        holder.update(panel, now); runCurrent()
        holder.remove(home)
        pending.complete(red); runCurrent()
        assertEquals(1, calls)
        assertEquals(red, holder.colorsFor(now))
        holder.remove(panel)
    }

    @Test fun lateResultIsDropped() = runTest {
        val pending = mutableListOf<CompletableDeferred<ArtworkColors?>>()
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), {
            val result = CompletableDeferred<ArtworkColors?>()
            pending.add(result)
            withContext(NonCancellable) { result.await() }
        })
        val a = song(bitmap()); val b = song(bitmap())
        holder.update(this, a); runCurrent()
        holder.update(this, b); runCurrent()
        holder.update(this, a); runCurrent()
        val blue = ArtworkColors(0xff181838.toInt(), 0xff0c0c1c.toInt())
        pending[2].complete(blue); runCurrent()
        pending[1].complete(red); pending[0].complete(red); runCurrent()
        assertEquals(blue, holder.colors.value)
    }

    @Test fun trackChangesDuringExtraction() = runTest {
        var calls = 0
        val pending = CompletableDeferred<ArtworkColors?>()
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; pending.await() })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        holder.update(this, now.copy(metaTitle = "next", album = "next")); runCurrent()
        pending.complete(red); runCurrent()
        assertEquals(1, calls)
        assertEquals(red, holder.colorsFor(now.copy(metaTitle = "next", album = "next")))
        assertNull(holder.colorsFor(now))
    }

    @Test fun missingArtworkImmediatelyUsesPanel() = runTest {
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { red })
        holder.update(this, song(bitmap())); runCurrent()
        holder.update(this, song(null))
        assertNull(holder.colors.value)
    }

    @Test fun newArtworkUsesPanelWhileLoading() = runTest {
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { red })
        holder.update(this, song(bitmap())); runCurrent()
        holder.update(this, song(bitmap()))
        assertNull(holder.colors.value)
        runCurrent()
        assertEquals(red, holder.colors.value)
    }

    @Test fun leavingScreenCancelsAndRejectsResult() = runTest {
        val pending = CompletableDeferred<ArtworkColors?>()
        var cancelled = false
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), {
            try { withContext(NonCancellable) { pending.await() } }
            finally { cancelled = !currentCoroutineContext().isActive }
        })
        holder.update(this, song(bitmap())); runCurrent()
        holder.remove(this)
        pending.complete(red); runCurrent()
        assertTrue(cancelled)
        assertNull(holder.colors.value)
    }

    @Test fun unreadableBitmapFallsBack() {
        val art = bitmap(); art.recycle()
        assertNull(sampleArtworkColors(art))
    }

    @Test fun originalBitmapNeverRecycled() {
        val art = bitmap()
        assertEquals(red, sampleArtworkColors(art))
        assertFalse(art.isRecycled)
        assertEquals(android.graphics.Color.RED, art.getPixel(0, 0))
    }

    @Test fun extractionRunsOffMain() = runBlocking {
        val caller = Thread.currentThread()
        val worker = CompletableDeferred<Thread>()
        val holder = ArtworkBackgroundHolder(this, Dispatchers.Default, { worker.complete(Thread.currentThread()); red })
        holder.update(this, song(bitmap()))
        assertNotSame(caller, withTimeout(5000) { worker.await() })
        holder.remove(this)
    }
}
