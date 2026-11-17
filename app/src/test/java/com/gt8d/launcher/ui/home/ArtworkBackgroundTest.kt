package com.gt8d.launcher.ui.home

import android.graphics.Bitmap
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.ArtworkColors
import com.gt8d.launcher.ui.theme.ArtworkColorReference
import com.gt8d.launcher.ui.theme.extractArtworkColors
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
    private val redStart = ArtworkColorReference.brightest(0xff990f0f.toInt())
    private val red = ArtworkColors(redStart,
        ArtworkColorReference.mix(0xff000000.toInt(), redStart, 0.6),
        ArtworkColorReference.mix(0xff000000.toInt(), redStart, 0.8))
    private fun bitmap() = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }
    private fun song(bitmap: Bitmap?) = NowPlaying("music", "song", "artist", bitmap, 100L, true, false)

    @Test fun samplePreservesAspectRatio() {
        assertEquals(32 to 16, artworkSampleSize(64, 32))
        assertEquals(1 to 32, artworkSampleSize(1, 64))
        assertEquals(16 to 8, artworkSampleSize(16, 8))
    }

    @Test fun largeArgbMatchesPreviousSampling() {
        for ((width, height) in listOf(96 to 48, 48 to 96)) {
            val art = patternedBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                assertEquals(Bitmap.Config.ARGB_8888, art.config)
                val originalPixels = readPixels(art)
                assertArgbSamplingPixelsMatchCopy(art)
                val expected = previousSampling(art)
                assertNotNull(expected)

                val actual = sampleArtworkColors(art)
                assertNotNull(actual)
                assertEquals(expected, actual)
                assertFalse(art.isRecycled)
                assertArrayEquals(originalPixels, readPixels(art))
            } finally {
                art.recycle()
            }
        }
    }

    @Test fun smallImmutableArgbRemainsUsable() {
        for ((width, height) in listOf(16 to 8, 32 to 16)) {
            val mutable = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
            }
            val art = try {
                requireNotNull(mutable.copy(Bitmap.Config.ARGB_8888, false))
            } finally {
                mutable.recycle()
            }
            try {
                assertEquals(Bitmap.Config.ARGB_8888, art.config)
                assertFalse(art.isMutable)
                val originalPixels = readPixels(art)
                assertArgbSamplingPixelsMatchCopy(art)
                repeat(2) {
                    assertEquals(red, sampleArtworkColors(art))
                    assertFalse(art.isRecycled)
                    assertArrayEquals(originalPixels, readPixels(art))
                }
            } finally {
                art.recycle()
            }
        }
    }

    @Test fun rgb565MatchesPreviousSampling() {
        for ((width, height) in listOf(65 to 37, 16 to 8)) {
            val art = patternedBitmap(width, height, Bitmap.Config.RGB_565)
            try {
                assertEquals(Bitmap.Config.RGB_565, art.config)
                val originalPixels = readPixels(art)
                val expected = previousSampling(art)
                assertNotNull(expected)

                val actual = sampleArtworkColors(art)
                assertNotNull(actual)
                assertEquals(expected, actual)
                assertFalse(art.isRecycled)
                assertArrayEquals(originalPixels, readPixels(art))
            } finally {
                art.recycle()
            }
        }
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
        assertEquals(1, calls)
        assertEquals(red, holder.colors.value)
    }

    @Test fun failureIsRetained() = runTest {
        var calls = 0
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; null })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        holder.update(this, now.copy(title = "new", artwork = bitmap())); runCurrent()
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

    @Test fun staleBackgroundClientDoesNotClearColorsOnPanelClose() = runTest {
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { red })
        val home = Any(); val panel = Any()
        holder.update(home, song(null))
        holder.update(panel, song(bitmap())); runCurrent()
        assertEquals(red, holder.colors.value)
        holder.remove(panel)
        assertEquals(red, holder.colors.value)
        holder.remove(home)
        assertNull(holder.colors.value)
    }

    @Test fun staleBackgroundClientKeepsPendingExtractionOnPanelClose() = runTest {
        var calls = 0
        val pending = CompletableDeferred<ArtworkColors?>()
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; pending.await() })
        val home = Any(); val panel = Any()
        holder.update(home, song(null))
        holder.update(panel, song(bitmap())); runCurrent()
        assertEquals(1, calls)
        assertNull(holder.colors.value)
        holder.remove(panel)
        pending.complete(red); runCurrent()
        assertEquals(red, holder.colors.value)
        assertEquals(1, calls)
        holder.remove(home)
        assertNull(holder.colors.value)
    }

    @Test fun newerRemainingClientIsSelectedOnRemove() = runTest {
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { red })
        val home = Any(); val panel = Any()
        holder.update(panel, song(bitmap())); runCurrent()
        assertEquals(red, holder.colors.value)
        holder.update(home, song(null))
        holder.remove(panel)
        assertNull(holder.colors.value)
        holder.remove(home)
    }

    @Test fun lateResultIsDropped() = runTest {
        val pending = mutableListOf<CompletableDeferred<ArtworkColors?>>()
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), {
            val result = CompletableDeferred<ArtworkColors?>()
            pending.add(result)
            withContext(NonCancellable) { result.await() }
        })
        val a = song(bitmap()); val b = song(bitmap()).copy(metaTitle = "other")
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
        assertEquals(2, calls)
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
        holder.update(this, song(bitmap()).copy(metaTitle = "next"))
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

    @Test fun sameTrackNewBitmapInstanceDoesNotRestart() = runTest {
        var calls = 0
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; red })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        assertEquals(red, holder.colorsFor(now))
        repeat(5) {
            advanceTimeBy(1000)
            val updated = now.copy(artwork = bitmap())
            assertNotSame(now.artwork, updated.artwork)
            holder.update(this, updated)
            assertEquals(red, holder.colorsFor(updated))
            runCurrent()
            assertEquals(1, calls)
            assertEquals(red, holder.colorsFor(updated))
        }
    }

    @Test fun differentTrackRestartsExtraction() = runTest {
        var calls = 0
        val pending = CompletableDeferred<ArtworkColors?>()
        val blue = ArtworkColors(0xff181838.toInt(), 0xff0c0c1c.toInt())
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), {
            calls++
            if (calls == 1) red else pending.await()
        })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        assertEquals(red, holder.colorsFor(now))
        val next = now.copy(metaTitle = "next")
        holder.update(this, next)
        assertNull(holder.colorsFor(next))
        runCurrent()
        assertEquals(2, calls)
        assertNull(holder.colorsFor(next))
        pending.complete(blue); runCurrent()
        assertEquals(blue, holder.colorsFor(next))
        assertNull(holder.colorsFor(now))
    }

    @Test fun sizeChangeRestartsExtraction() = runTest {
        var calls = 0
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), { calls++; red })
        val now = song(bitmap())
        holder.update(this, now); runCurrent()
        val next = now.copy(artwork = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888))
        holder.update(this, next)
        assertNull(holder.colorsFor(next))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(red, holder.colorsFor(next))
        assertNull(holder.colorsFor(now))
    }

    @Test fun sameTrackSizeChangeKeepsImageColorsWhileReextracting() = runTest {
        val blue = ArtworkColors(0xff181838.toInt(), 0xff0c0c1c.toInt())
        val holder = ArtworkBackgroundHolder(this, StandardTestDispatcher(testScheduler), {
            if (it.width == 16) red else blue
        })
        val now = song(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
        holder.update(this, now); runCurrent()
        assertEquals(red, holder.colorsFor(now))

        val next = now.copy(artwork = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888))
        holder.update(this, next)
        assertEquals(red, holder.colors.value)
        assertEquals(red, holder.imageColorsFor(next))
        assertNull(holder.colorsFor(next))

        runCurrent()
        assertEquals(blue, holder.colors.value)
        assertEquals(blue, holder.imageColorsFor(next))
        assertEquals(blue, holder.colorsFor(next))
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

    private fun patternedBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        val colors = intArrayOf(
            0xffff4000.toInt(), 0x80ff4f00.toInt(), 0xc020b060.toInt(),
            0xff3050d0.toInt(), 0x7fff00ff, 0x00000000,
        )
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            colors[(x / 7 + y / 5) % colors.size]
        }
        return Bitmap.createBitmap(width, height, config).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun readPixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun assertArgbSamplingPixelsMatchCopy(bitmap: Bitmap) {
        val copy = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        val (width, height) = artworkSampleSize(bitmap.width, bitmap.height)
        val directSample = Bitmap.createScaledBitmap(bitmap, width, height, false)
        val copiedSample = Bitmap.createScaledBitmap(copy, width, height, false)
        try {
            // Compare the extraction inputs, so a color-selection coincidence cannot hide a change.
            assertArrayEquals(readPixels(copiedSample), readPixels(directSample))
        } finally {
            if (directSample !== bitmap) directSample.recycle()
            if (copiedSample !== copy) copiedSample.recycle()
            copy.recycle()
        }
    }

    private fun previousSampling(bitmap: Bitmap): ArtworkColors? {
        val copy = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        var sampled: Bitmap? = null
        return try {
            val (width, height) = artworkSampleSize(copy.width, copy.height)
            sampled = Bitmap.createScaledBitmap(copy, width, height, false)
            extractArtworkColors(readPixels(sampled))
        } finally {
            if (sampled !== bitmap && sampled !== copy) sampled?.recycle()
            if (copy !== bitmap) copy.recycle()
        }
    }
}
