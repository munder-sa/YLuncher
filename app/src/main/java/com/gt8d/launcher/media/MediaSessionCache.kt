package com.gt8d.launcher.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import com.gt8d.launcher.diag.DiagLog

/** Callbacks update cached fields; controller I/O is repeated only to recover a missing snapshot. */
internal class MediaSessionCache<C : Any>(
    private val access: Access<C>,
    private val onChanged: (NowPlaying?) -> Unit,
    private val artworkFallback: ArtworkFallback? = null,
) {
    // Preserve existing MediaSessionCache(access) { ... } call sites.
    constructor(access: Access<C>, onChanged: (NowPlaying?) -> Unit) : this(access, onChanged, null)

    internal interface Access<C> {
        fun packageName(controller: C): String
        fun metadata(controller: C): MediaMetadata?
        fun playbackState(controller: C): PlaybackState?
        fun appLabel(pkg: String): String
        fun register(controller: C, callback: MediaController.Callback)
        fun unregister(controller: C, callback: MediaController.Callback)
    }

    private inner class Entry(controller: C) {
        var controller: C? = controller
        val pkg = access.packageName(controller)
        var active = true
        var registered = false
        var snapshot: SessionSnapshot? = null
        var metadataSeen = false
        var lastTitle: String? = null
        var lastArtworkDiag: String? = null
        var fallbackUri: String? = null
        var fallbackBitmap: Bitmap? = null
        var fallbackCancel: ArtworkFallback.Cancel? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                update(this@Entry) { it.withPlaybackState(state) }
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                update(this@Entry) { withMetadata(this@Entry, it, metadata) }
            }

            override fun onSessionDestroyed() {
                if (!active) return
                entries.remove(this@Entry)
                dispose(this@Entry)
                if (entries.none { it.pkg == pkg }) labels.remove(pkg)
                publish()
            }
        }
    }

    private val entries = mutableListOf<Entry>()
    private val labels = mutableMapOf<String, String>()
    // Preserve the previous watcher's package preference across stop/start.
    private var lastActivePkg: String? = null
    private var selected: Entry? = null
    val selectedController: C? get() = selected?.controller
    val controllers: List<C> get() = entries.mapNotNull { it.controller }

    fun activePackagesMissingArtwork(): Set<String> = entries.mapNotNull { entry ->
        val snapshot = entry.snapshot
        if (snapshot != null && snapshot.artwork == null) entry.pkg else null
    }.toSet()

    fun replaceControllers(controllers: List<C>) {
        discardEntries()
        for (controller in controllers) {
            val entry = Entry(controller)
            entries += entry
            try {
                access.register(controller, entry.callback)
                entry.registered = true
            } catch (e: Exception) {
                Log.w("GT8D", "Failed to register MediaController callback for ${entry.pkg}", e)
            }
            try {
                entry.snapshot = readSnapshot(entry, controller)
            } catch (e: Exception) {
                Log.w("GT8D", "Failed to read media snapshot for ${entry.pkg}", e)
            }
        }
        publish()
    }

    fun clear() {
        discardEntries()
        onChanged(null)
    }

    /** A package broadcast refreshes that package; a locale change refreshes all labels. */
    fun invalidateLabels(pkg: String? = null) {
        if (pkg == null) labels.clear() else labels.remove(pkg)
        for (entry in entries) {
            if (pkg == null || entry.pkg == pkg) {
                entry.snapshot = entry.snapshot?.copy(appLabel = label(entry.pkg))
            }
        }
        publish()
    }

    private fun discardEntries() {
        val old = entries.toList()
        entries.clear()
        selected = null
        // Invalidate every callback before unregistering any of them.
        old.forEach { it.active = false }
        old.forEach(::dispose)
        labels.clear()
    }

    private fun dispose(entry: Entry) {
        entry.active = false
        clearFallback(entry)
        val controller = entry.controller
        // Queued callbacks may still own Entry: release its bitmap and controller references too.
        entry.snapshot = null
        entry.controller = null
        entry.lastTitle = null
        entry.lastArtworkDiag = null
        entry.metadataSeen = false
        if (entry.registered && controller != null) {
            try {
                access.unregister(controller, entry.callback)
            } catch (e: Exception) {
                Log.w("GT8D", "Failed to unregister MediaController callback", e)
            }
        }
        entry.registered = false
    }

    private inline fun update(entry: Entry, change: (SessionSnapshot) -> SessionSnapshot) {
        if (!entry.active) return
        try {
            val snapshot = entry.snapshot ?: try {
                readSnapshot(entry, checkNotNull(entry.controller))
            } catch (e: Exception) {
                Log.w("GT8D", "Failed to recover media snapshot for ${entry.pkg}", e)
                emptySnapshot(entry)
            }
            entry.snapshot = change(snapshot)
        } catch (e: Exception) {
            entry.snapshot = null
            Log.w("GT8D", "Failed to update media snapshot for ${entry.pkg}", e)
        }
        publish()
    }

    private fun readSnapshot(entry: Entry, controller: C) =
        withMetadata(entry, emptySnapshot(entry), access.metadata(controller))
            .withPlaybackState(access.playbackState(controller))

    private fun publish() {
        val picked = pickSession(entries.mapNotNull { it.snapshot }, lastActivePkg)
        selected = if (picked == null) null else entries.firstOrNull { it.snapshot === picked }
        if (picked?.playing == true) lastActivePkg = picked.pkg
        onChanged(picked?.toNowPlaying())
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) { access.appLabel(pkg) }

    private fun emptySnapshot(entry: Entry) = SessionSnapshot(
        pkg = entry.pkg,
        appLabel = label(entry.pkg),
        title = null,
        artist = null,
        artwork = null,
        durationMs = 0L,
        positionMs = 0L,
        positionUpdatedAt = 0L,
        speed = 1f,
        playing = false,
        hasFavoriteAction = false,
    )

    private fun withMetadata(entry: Entry, snapshot: SessionSnapshot, metadata: MediaMetadata?): SessionSnapshot {
        val title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
        if (!entry.metadataSeen || entry.lastTitle != title) {
            entry.metadataSeen = true
            entry.lastTitle = title
            DiagLog.d("Lyrics", "metadata keys pkg=${entry.pkg} ${metadata?.keySet()?.sorted()?.joinToString(",").orEmpty()}")
        }
        val bmpDesc = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
            "ALBUM_ART:${it.width}x${it.height}"
        } ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let {
            "ART:${it.width}x${it.height}"
        } ?: "null"
        val dispDesc = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let {
            "${it.width}x${it.height}"
        } ?: "null"
        val uriDesc = listOf(
            "ALBUM_ART_URI" to MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            "ART_URI" to MediaMetadata.METADATA_KEY_ART_URI,
            "DISPLAY_ICON_URI" to MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { (which, key) ->
            metadata?.getString(key)?.takeIf { it.isNotEmpty() }?.let { raw ->
                "$which:${Uri.parse(raw).scheme?.lowercase() ?: "noscheme"}"
            }
        } ?: "none"
        val keys = metadata?.keySet()?.sorted()?.joinToString(",").orEmpty()
        val diag = "meta pkg=${entry.pkg} hasTitle=${!title.isNullOrBlank()} bmp=$bmpDesc dispIcon=$dispDesc uri=$uriDesc keys=$keys"
        if (entry.lastArtworkDiag != diag) {
            entry.lastArtworkDiag = diag
            DiagLog.d("Artwork", diag)
        }
        return snapshot.copy(
            title = title,
            artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString(),
            artwork = artwork(entry, metadata),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            album = metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString(),
            lyricsIdentity = lyricsIdentity(entry.pkg, LyricsMetadataProbe.read(metadata)),
        )
    }

    private fun clearFallback(entry: Entry) {
        val cancel = entry.fallbackCancel
        entry.fallbackUri = null
        entry.fallbackBitmap = null
        entry.fallbackCancel = null
        cancel?.cancel()
    }

    private fun artwork(entry: Entry, metadata: MediaMetadata?): Bitmap? {
        val inline = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (inline != null) {
            clearFallback(entry)
            return inline
        }
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { key -> metadata?.getString(key)?.takeIf { it.isNotEmpty() } }
            ?.takeIf { Uri.parse(it).scheme?.lowercase() in setOf("https", "http") }
        if (uri == null) {
            clearFallback(entry)
            return null
        }
        if (uri != entry.fallbackUri) {
            clearFallback(entry)
            entry.fallbackUri = uri
            entry.fallbackCancel = artworkFallback?.request(uri) { bitmap ->
                if (entry.active && entry.fallbackUri == uri && entry.snapshot?.artwork == null) {
                    entry.fallbackBitmap = bitmap
                    entry.snapshot = entry.snapshot?.copy(artwork = bitmap)
                    publish()
                }
            }
        }
        return entry.fallbackBitmap
    }

    private fun SessionSnapshot.withPlaybackState(state: PlaybackState?) = copy(
        positionMs = state?.position ?: 0L,
        positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
        speed = state?.playbackSpeed ?: 1f,
        playing = state?.state == PlaybackState.STATE_PLAYING,
        paused = state?.state == PlaybackState.STATE_PAUSED,
        hasFavoriteAction = state?.customActions?.any { it.action in FAVORITE_ACTION_IDS } == true,
    )

    companion object {
        val FAVORITE_ACTION_IDS: Set<String> = setOf("com.apple.android.music.playback.action.FAVORITE")
    }
}
