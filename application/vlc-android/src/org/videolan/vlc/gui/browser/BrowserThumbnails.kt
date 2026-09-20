/*
 * *************************************************************************
 *  BrowserThumbnails.kt
 * **************************************************************************
 *  Copyright © 2026 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */
package org.videolan.vlc.gui.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.image.ImageRepository
import org.videolan.vlc.gui.image.NetworkThumbStore
import org.videolan.vlc.gui.image.SmbImageLoader
import org.videolan.vlc.gui.image.SmbMediaDataSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Row thumbnails for the file and network browsers.
 *
 * Image files get a decoded thumbnail, folders get a cover preview (the first
 * image found inside them, registered while the provider parses each folder
 * listing). smb:// entries are decoded through the jcifs client on a small
 * dedicated executor.
 *
 * A cached bitmap is always shown first, even once it is expired: the refresh
 * then runs behind it and replaces it atomically. That keeps a slow or failing
 * share from blanking rows that already have a usable picture, while a folder
 * cover cannot go stale silently because its key carries its content.
 * Failed decodes are retried with a doubling delay instead of on every rebind.
 *
 * A row bind is speculation: the user never asked for that particular bitmap.
 * So when a share host is known to be unreachable, its probes are skipped
 * entirely rather than queued, which keeps the two network threads free for
 * hosts that do answer.
 */
object BrowserThumbnails {

    private const val THUMB_W = 320
    private const val THUMB_H = 480

    /**
     * How long a generated thumbnail is trusted. smb:// has no cheap
     * size/mtime in the browser listing, so an expired entry is regenerated
     * in the background - the cached bitmap stays on screen meanwhile.
     */
    private const val THUMB_STALE_MS = 60L * 60L * 1000L

    /** First retry delay after a failed decode, doubled per strike up to the cap */
    private const val NEGATIVE_BASE_MS = 60L * 1000L
    private const val NEGATIVE_MAX_MS = 30L * 60L * 1000L

    /** How long a forced-refresh mark stays pending before it is considered lost */
    private const val FORCED_WINDOW_MS = 5 * 60 * 1000L

    private val localExecutor = Executors.newFixedThreadPool(2)
    private val networkExecutor = Executors.newFixedThreadPool(2)
    /** Lazily built: the unit tests exercise the pure logic without a Looper */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val inflight = ConcurrentHashMap<String, Boolean>()

    /** key -> epoch ms of the bitmap generation backing the caches */
    private val thumbTimes = ConcurrentHashMap<String, Long>()

    /** key -> epoch ms until which a failed decode is not retried again */
    private val negativeUntil = ConcurrentHashMap<String, Long>()

    /** key -> consecutive decode failures, drives the retry backoff */
    private val negativeStrikes = ConcurrentHashMap<String, Int>()

    /**
     * key -> epoch ms when the user explicitly asked to regenerate it (browser
     * overflow action). Cleared when the re-decode it triggers lands - success
     * or failure - so a failed refresh stays pending instead of being swallowed.
     * The stamp is the safety net: binds that bail out before the decode (host
     * unreachable, negative cache, folder candidate not parsed yet) would
     * otherwise leave the key pending forever and re-decode it on every rebind.
     */
    private val forced = ConcurrentHashMap<String, Long>()

    /**
     * Upper bound for every state map above. The keys carry the full media uri
     * (~200B each), so an unbounded map would grow for the whole session while
     * browsing large shares; entries past the cap are simply recomputed later.
     */
    private const val MAP_CAP = 4096

    /**
     * put() with a size cap: past [cap] entries the eldest is evicted. The
     * ConcurrentHashMap key order is only an approximation of insertion order,
     * so this is near-LRU rather than exact LRU - fine for caches and failure
     * bookkeeping, where a wrong victim just costs one recompute or re-probe.
     */
    internal fun <K, V> ConcurrentHashMap<K, V>.putCapped(key: K, value: V, cap: Int = MAP_CAP) {
        put(key, value)
        if (size > cap) keys.firstOrNull()?.let { remove(it) }
    }

    /** folderUri -> the image or video whose cover is currently cached for it */
    private val folderCandidates = ConcurrentHashMap<String, MediaWrapper>()

    fun isImage(media: MediaWrapper): Boolean {
        when (media.type) {
            MediaWrapper.TYPE_DIR, MediaWrapper.TYPE_AUDIO, MediaWrapper.TYPE_VIDEO,
            MediaWrapper.TYPE_SUBTITLE, MediaWrapper.TYPE_PLAYLIST -> return false
        }
        return ImageRepository.isImageFile(media.uri?.lastPathSegment)
    }

    private fun mediaKey(media: MediaWrapper) = "bimg_${media.uri}_${THUMB_W}x$THUMB_H"

    private fun videoKey(media: MediaWrapper) = "bvid_${media.uri}_$THUMB_W"

    private fun folderBase(folder: MediaWrapper) = "bimgdir_${folder.uri}"

    /**
     * The cover candidate is part of the key, so a folder that gained, lost or
     * swapped its first media item simply resolves to a different entry: no
     * invalidation needed, and it holds across process restarts.
     */
    private fun folderKey(folder: MediaWrapper, candidate: MediaWrapper) =
            "${folderBase(folder)}_${candidate.uri}_${THUMB_W}x$THUMB_H"

    /**
     * Pure stale predicate, extracted for tests: the trust anchor is the newer
     * of the in-memory timestamp and the disk entry's mtime. A bitmap can
     * outlive [THUMB_STALE_MS] inside the memory LRU while its disk copy is
     * still young, and re-decoding then would just fetch the same bytes again.
     */
    internal fun isStaleForTest(memTime: Long, diskModified: Long, forced: Boolean, now: Long) =
            forced || now - max(memTime, diskModified) > THUMB_STALE_MS

    /**
     * True when the cached bitmap should be regenerated behind what is on screen.
     * A forced key is stale only until its re-decode lands; the mark is cleared
     * in loadAsync, not consumed here, so a failed refresh stays pending - but
     * only for [FORCED_WINDOW_MS], see [isForced].
     */
    internal fun isStale(key: String, diskModified: Long = 0L, now: Long = System.currentTimeMillis()) =
            isStaleForTest(thumbTimes[key] ?: 0L, diskModified, isForced(key, now), now)

    /**
     * A pending refresh request, dropped once it goes stale on its own: binds
     * that return before queueing a decode never reach the code that clears the
     * mark, and a permanent one would mean re-decoding the row on every rebind.
     */
    private fun isForced(key: String, now: Long): Boolean {
        val requestedAt = forced[key] ?: return false
        if (now - requestedAt < FORCED_WINDOW_MS) return true
        forced.remove(key)
        return false
    }

    private fun isNegative(key: String) = (negativeUntil[key] ?: 0L) > System.currentTimeMillis()

    /**
     * Backoff after a failed decode: the delay doubles per strike up to the cap.
     * A failure of a user-forced refresh is pinned to a single strike (1 minute)
     * instead of joining the exponential curve - an explicit request must stay
     * retryable almost immediately.
     */
    internal fun noteFailure(key: String, forced: Boolean = false, now: Long = System.currentTimeMillis()) {
        val strikes = if (forced) 1 else ((negativeStrikes[key] ?: 0) + 1).coerceAtMost(6)
        negativeStrikes.putCapped(key, strikes)
        negativeUntil.putCapped(key, now + negativeDelayMs(strikes))
    }

    /** Retry delay for [strikes] consecutive failures: 1min doubling to 30min */
    internal fun negativeDelayMs(strikes: Int) =
            (NEGATIVE_BASE_MS shl (strikes - 1)).coerceAtMost(NEGATIVE_MAX_MS)

    @VisibleForTesting internal fun debugStrikes(key: String) = negativeStrikes[key] ?: 0
    @VisibleForTesting internal fun debugNegativeUntil(key: String) = negativeUntil[key] ?: 0L
    @VisibleForTesting internal fun debugForce(key: String, at: Long) {
        forced.putCapped(key, at)
    }

    @VisibleForTesting internal fun debugForced(key: String) = forced.containsKey(key)
    @VisibleForTesting internal fun debugReset(key: String) {
        negativeStrikes.remove(key)
        negativeUntil.remove(key)
        forced.remove(key)
        thumbTimes.remove(key)
    }

    private fun noteSuccess(key: String) {
        negativeStrikes.remove(key)
        negativeUntil.remove(key)
    }

    /**
     * True for smb:// entries whose host recently refused to answer at all.
     * Such probes are dropped, not cached as negatives, so they resume on their
     * own once the host is reachable again.
     */
    private fun unreachableShare(uri: Uri?): Boolean {
        return uri?.scheme == "smb" && SmbImageLoader.isKnownDead(uri.host)
    }

    /**
     * Called from BrowserProvider.parseSubDirectoriesImpl once a folder listing
     * is available. No IO happens here (it runs on the parse thread): the first
     * image/video is only registered as the cover candidate, and the actual
     * decode/frame extraction happens when the folder row binds.
     */
    fun cacheFolderPreview(folder: MediaLibraryItem?, listing: List<MediaLibraryItem>) {
        if (folder !is MediaWrapper) return
        val wrappers = listing.filterIsInstance<MediaWrapper>()
        val candidate = wrappers.firstOrNull { isImage(it) }
                ?: wrappers.firstOrNull { it.type == MediaWrapper.TYPE_VIDEO }
        if (candidate == null) folderCandidates.remove(folderBase(folder))
        else folderCandidates[folderBase(folder)] = candidate
    }

    /**
     * Browser overflow action: regenerate the thumbnails of [items]. Rows keep
     * showing what is cached while the new bitmap is fetched, so pressing this
     * on a big folder is safe.
     */
    fun forceRefresh(items: List<MediaLibraryItem>) {
        items.filterIsInstance<MediaWrapper>().forEach { media ->
            cacheKeysFor(media).forEach { key ->
                forced.putCapped(key, System.currentTimeMillis())
                negativeUntil.remove(key)
                negativeStrikes.remove(key)
            }
        }
    }

    private fun cacheKeysFor(media: MediaWrapper): List<String> = when {
        isImage(media) -> listOf(mediaKey(media))
        media.type == MediaWrapper.TYPE_VIDEO -> listOf(videoKey(media))
        media.type == MediaWrapper.TYPE_DIR ->
            folderCandidates[folderBase(media)]?.let { listOf(folderKey(media, it)) } ?: emptyList()
        else -> emptyList()
    }

    /**
     * Called on every media row bind. Applies the cached thumbnail/preview when
     * available - even when expired, so a refresh never blanks the row - and
     * decodes asynchronously whatever is missing or due for one.
     */
    fun bind(container: BrowserItemBindingContainer, media: MediaWrapper) {
        bindInternal(container.itemIcon, media) { bitmap -> applyDrawable(container, bitmap) }
    }

    /**
     * View-targeted entry for rows that are not backed by
     * [BrowserItemBindingContainer] (TV grid/list cards). The bitmap is set
     * directly on the view, center-cropped like the stock TV cover flow.
     */
    fun bind(view: ImageView, media: MediaWrapper) {
        bindInternal(view, media) { bitmap ->
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageDrawable(BitmapDrawable(view.resources, bitmap))
        }
    }

    private fun bindInternal(icon: ImageView, media: MediaWrapper, apply: (Bitmap) -> Unit) {
        when {
            isImage(media) -> bindRow(icon, media, mediaKey(media), apply)
            media.type == MediaWrapper.TYPE_VIDEO ->
                if (Settings.showVideoThumbs) bindRow(icon, media, videoKey(media), apply)
            media.type == MediaWrapper.TYPE_DIR -> bindFolder(icon, media, apply)
        }
    }

    private fun bindRow(icon: ImageView, media: MediaWrapper, key: String, apply: (Bitmap) -> Unit) {
        val isVideo = media.type == MediaWrapper.TYPE_VIDEO
        bindCached(icon, key, media.uri, { context ->
            if (isVideo) loadVideoBitmap(context, media) else loadBitmap(context, media)
        }, apply)
    }

    private fun bindFolder(icon: ImageView, folder: MediaWrapper, apply: (Bitmap) -> Unit) {
        val candidate = folderCandidates[folderBase(folder)] ?: return
        bindCached(icon, folderKey(folder, candidate), candidate.uri, { context ->
            if (isImage(candidate)) loadBitmap(context, candidate) else loadVideoBitmap(context, candidate)
        }, apply)
    }

    /** Cache first, decode behind it: an expired bitmap still beats a blank row */
    private fun bindCached(
            icon: ImageView,
            key: String,
            uri: Uri?,
            decode: (Context) -> Bitmap?,
            apply: (Bitmap) -> Unit
    ) {
        if (isNegative(key)) return
        val context = icon.context
        val cached = BitmapCache.getBitmapFromMemCache(key) ?: NetworkThumbStore.get(context, key)?.also {
            BitmapCache.addBitmapToMemCache(key, it)
            thumbTimes.putCapped(key, NetworkThumbStore.lastModified(context, key))
        }
        if (cached == null) {
            loadAsync(icon, key, uri, decode, apply)
            return
        }
        apply(cached)
        // The disk mtime is only worth its stat once the memory anchor says
        // stale: a younger disk entry then spares this row a re-decode, while a
        // fresh hit stays a pure map lookup on the bind path.
        var stale = isStale(key)
        if (stale) stale = isStale(key, NetworkThumbStore.lastModified(context, key))
        if (!stale) return
        loadAsync(icon, key, uri, decode, apply)
    }

    private fun loadAsync(
            icon: ImageView,
            key: String,
            uri: Uri?,
            decode: (Context) -> Bitmap?,
            apply: (Bitmap) -> Unit
    ) {
        if (unreachableShare(uri)) return
        icon.setTag(R.id.browser_thumb_key, key)
        if (inflight.putIfAbsent(key, true) != null) return
        val context = icon.context
        val executor = if (uri?.scheme == "smb") networkExecutor else localExecutor
        executor.execute {
            // SmbAuthRequiredException ends up here too: without credentials the
            // row keeps its plain icon, and the viewer prompts once on open
            val bitmap = try {
                decode(context)
            } catch (ignored: Exception) {
                null
            }
            if (bitmap != null) {
                // the memory cache never overwrites, so drop what it holds first
                BitmapCache.removeBitmapFromMemCache(key)
                BitmapCache.addBitmapToMemCache(key, bitmap)
                thumbTimes.putCapped(key, System.currentTimeMillis())
                NetworkThumbStore.put(context, key, bitmap)
                forced.remove(key)
                noteSuccess(key)
            } else {
                // the forced mark is consumed by this attempt either way; its
                // failure only earns a 1-minute pause, not the backoff curve
                val wasForced = forced.remove(key) != null
                noteFailure(key, forced = wasForced)
            }
            mainHandler.post {
                inflight.remove(key)
                if (bitmap != null && icon.getTag(R.id.browser_thumb_key) == key) apply(bitmap)
            }
        }
    }

    private fun loadBitmap(context: Context, media: MediaWrapper): Bitmap? {
        return try {
            ImageRepository.decodeSampledBitmap(context.applicationContext, media.uri, THUMB_W, THUMB_H)
        } catch (e: SmbImageLoader.SmbAuthRequiredException) {
            // No credentials for this host yet: stay on the plain icon; the
            // viewer will prompt once and the credentials get stored globally.
            null
        }
    }

    /**
     * Preview frame for a video, with a duration badge drawn in the corner.
     * Remote shares are read through the jcifs MediaDataSource bridge.
     */
    private fun loadVideoBitmap(context: Context, media: MediaWrapper): Bitmap? {
        val uri = media.uri ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            when (uri.scheme) {
                "smb" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) retriever.setDataSource(SmbMediaDataSource(uri)) else return null
                "content" -> retriever.setDataSource(context, uri)
                else -> retriever.setDataSource(if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString())
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // skip dark intros: sample at 10% of the duration (>= 1s, <= 3s)
            val sampleUs = if (durationMs > 0) {
                max((durationMs * 100L) / 1000, 1_000_000L).coerceAtMost(3_000_000L)
            } else 0L
            var frame = retriever.getFrameAtTime(sampleUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            // if the frame is nearly black and the video is long enough, retry at 25%
            if (frame != null && isMostlyBlack(frame) && durationMs > 4000) {
                val retry = retriever.getFrameAtTime(durationMs * 1000L / 4, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (retry != null && !isMostlyBlack(retry)) {
                    if (retry !== frame) frame.recycle()
                    frame = retry
                }
            }
            frame ?: return null
            withDurationBadge(frame, durationMs)
        } catch (ignored: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }

    /** average luma of a downscaled copy, to detect black intro frames */
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val w = 8
        val h = 8
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        var total = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            total += (r * 299L + g * 587L + b * 114L) / 1000L
        }
        return total / (w * h) < 14L
    }

    private fun withDurationBadge(frame: Bitmap, durationMs: Long): Bitmap {
        val targetWidth = THUMB_W
        val scaled = if (frame.width > targetWidth) {
            val scaledFrame = Bitmap.createScaledBitmap(frame, targetWidth, (frame.height * targetWidth / frame.width).coerceAtLeast(1), true)
            if (scaledFrame != frame) frame.recycle()
            scaledFrame
        } else frame
        if (durationMs <= 0L) return scaled
        val result = scaled.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val text = formatDuration(durationMs)
        val textSize = result.width / 16f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
        }
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.bottom - metrics.top
        val margin = result.width / 40f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        val right = result.width - margin
        val bottom = result.height - margin
        canvas.drawRoundRect(right - textWidth - margin, bottom - textHeight - margin, right, bottom, margin / 2, margin / 2, bgPaint)
        canvas.drawText(text, right - textWidth - margin / 2, bottom - margin / 2 - metrics.bottom, textPaint)
        return result
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
    }

    private fun applyDrawable(container: BrowserItemBindingContainer, bitmap: Bitmap) {
        container.setCover(BitmapDrawable(container.itemIcon.resources, bitmap))
    }
}
