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
 * image found inside them, cached while the provider parses each folder
 * listing). smb:// entries are decoded through the jcifs client on a small
 * dedicated executor. Negative results are remembered briefly so entries
 * without a decodable image are not retried on every rebind.
 *
 * A row bind is speculation: the user never asked for that particular bitmap.
 * So when a share host is known to be unreachable, its probes are skipped
 * entirely rather than queued, which keeps the two network threads free for
 * hosts that do answer.
 */
object BrowserThumbnails {

    private const val THUMB_W = 320
    private const val THUMB_H = 480
    private const val NEGATIVE_TTL_MS = 60L * 1000L

    /**
     * How long a generated thumbnail is trusted. smb:// has no cheap
     * size/mtime in the browser listing, and folders/files can change under
     * the same uri (delete + recreate being the extreme case), so after this
     * delay the disk entry is dropped and the bitmap is regenerated when the
     * row is bound again.
     */
    private const val THUMB_STALE_MS = 10L * 60L * 1000L

    private val localExecutor = Executors.newFixedThreadPool(2)
    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inflight = ConcurrentHashMap<String, Boolean>()
    private val negatives = ConcurrentHashMap<String, Long>()

    /** key -> epoch ms of the bitmap generation backing the memory cache */
    private val thumbTimes = ConcurrentHashMap<String, Long>()

    private fun isFresh(key: String) = System.currentTimeMillis() - (thumbTimes[key] ?: 0L) < THUMB_STALE_MS

    /** folders whose listing has no image but contains videos: folderKey -> first video */
    private val folderVideoCandidates = ConcurrentHashMap<String, MediaWrapper>()

    /** folders with images: folderKey -> first image (cover generated async at bind time) */
    private val folderImageCandidates = ConcurrentHashMap<String, MediaWrapper>()

    /** folderKey -> uri of the candidate backing the current cover, to detect content changes */
    private val folderSignatures = ConcurrentHashMap<String, String>()

    fun isImage(media: MediaWrapper): Boolean {
        when (media.type) {
            MediaWrapper.TYPE_DIR, MediaWrapper.TYPE_AUDIO, MediaWrapper.TYPE_VIDEO,
            MediaWrapper.TYPE_SUBTITLE, MediaWrapper.TYPE_PLAYLIST -> return false
        }
        return ImageRepository.isImageFile(media.uri?.lastPathSegment)
    }

    private fun mediaKey(media: MediaWrapper) = "bimg_${media.uri}_${THUMB_W}x$THUMB_H"

    private fun folderKey(folder: MediaWrapper) = "bimgdir_${folder.uri}_${THUMB_W}x$THUMB_H"

    private fun videoKey(media: MediaWrapper) = "bvid_${media.uri}_$THUMB_W"

    /**
     * True for smb:// entries whose host recently refused to answer at all.
     * Such probes are dropped, not cached as negatives, so they resume on their
     * own once the host is reachable again.
     */
    private fun unreachableShare(uri: Uri?): Boolean {
        return uri?.scheme == "smb" && SmbImageLoader.isKnownDead(uri.host)
    }

    private fun isNegative(key: String): Boolean {
        val since = negatives[key] ?: return false
        if (System.currentTimeMillis() - since > NEGATIVE_TTL_MS) {
            negatives.remove(key)
            return false
        }
        return true
    }

    /**
     * Called from BrowserProvider.parseSubDirectoriesImpl once a folder listing
     * is available. No IO happens here (it runs on the parse thread): the first
     * image/video is only registered as a cover candidate and the actual
     * decode/frame extraction happens asynchronously when the folder row binds.
     */
    fun cacheFolderPreview(context: Context, folder: MediaLibraryItem?, listing: List<MediaLibraryItem>) {
        if (folder !is MediaWrapper) return
        val key = folderKey(folder)
        if (isNegative(key)) return
        val wrappers = listing.filterIsInstance<MediaWrapper>()
        val firstImage = wrappers.firstOrNull { isImage(it) }
        val firstVideo = if (firstImage == null) wrappers.firstOrNull { it.type == MediaWrapper.TYPE_VIDEO } else null
        val signature = (firstImage ?: firstVideo)?.uri?.toString().orEmpty()
        if (folderSignatures.containsKey(key) && folderSignatures[key] != signature) {
            // same folder uri, different content: the cached cover is stale
            invalidate(context.applicationContext, key)
        }
        folderSignatures[key] = signature
        if (firstImage != null) {
            folderImageCandidates[key] = firstImage
            folderVideoCandidates.remove(key)
            return
        }
        if (firstVideo == null) {
            folderImageCandidates.remove(key)
            folderVideoCandidates.remove(key)
            negatives[key] = System.currentTimeMillis()
        } else {
            folderImageCandidates.remove(key)
            folderVideoCandidates[key] = firstVideo
        }
    }

    /** Drops every cached copy of [key] (memory, disk, freshness stamp) */
    private fun invalidate(context: Context, key: String) {
        thumbTimes.remove(key)
        BitmapCache.removeBitmapFromMemCache(key)
        NetworkThumbStore.delete(context, key)
    }

    private fun getCachedFolderPreview(context: Context, folder: MediaWrapper): Bitmap? {
        val key = folderKey(folder)
        if (isNegative(key)) return null
        BitmapCache.getBitmapFromMemCache(key)?.let {
            if (isFresh(key)) return it
            BitmapCache.removeBitmapFromMemCache(key)
        }
        val fromDisk = NetworkThumbStore.get(context, key, THUMB_STALE_MS) ?: return null
        BitmapCache.addBitmapToMemCache(key, fromDisk)
        thumbTimes[key] = NetworkThumbStore.lastModified(context, key)
        return fromDisk
    }

    /**
     * Called on every media row bind. Applies the cached thumbnail/preview when
     * available, otherwise starts an async decode for image files and patches
     * the row when done.
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
            isImage(media) -> {
                val key = mediaKey(media)
                if (isNegative(key)) return
                val cached = BitmapCache.getBitmapFromMemCache(key)
                if (cached != null) {
                    if (isFresh(key)) {
                        apply(cached)
                        return
                    }
                    BitmapCache.removeBitmapFromMemCache(key)
                }
                NetworkThumbStore.get(icon.context, key, THUMB_STALE_MS)?.let { fromDisk ->
                    BitmapCache.addBitmapToMemCache(key, fromDisk)
                    thumbTimes[key] = NetworkThumbStore.lastModified(icon.context, key)
                    apply(fromDisk)
                    return
                }
                loadAsync(icon, media, apply)
            }
            media.type == MediaWrapper.TYPE_VIDEO -> {
                val key = videoKey(media)
                if (!Settings.showVideoThumbs || isNegative(key)) return
                val cached = BitmapCache.getBitmapFromMemCache(key)
                if (cached != null) {
                    if (isFresh(key)) {
                        apply(cached)
                        return
                    }
                    BitmapCache.removeBitmapFromMemCache(key)
                }
                NetworkThumbStore.get(icon.context, key, THUMB_STALE_MS)?.let { fromDisk ->
                    BitmapCache.addBitmapToMemCache(key, fromDisk)
                    thumbTimes[key] = NetworkThumbStore.lastModified(icon.context, key)
                    apply(fromDisk)
                    return
                }
                loadAsync(icon, media, apply)
            }
            media.type == MediaWrapper.TYPE_DIR -> {
                val key = folderKey(media)
                val preview = getCachedFolderPreview(icon.context, media)
                if (preview != null) {
                    apply(preview)
                    return
                }
                if (isNegative(key)) return
                // cover candidates were registered while the parent folder was
                // parsed; decode the first image (or extract the first video's
                // frame) asynchronously, with the result persisted to disk
                val imageCandidate = folderImageCandidates[key]
                val videoCandidate = folderVideoCandidates[key]
                if (imageCandidate == null && videoCandidate == null) return
                val candidate = imageCandidate ?: videoCandidate!!
                if (unreachableShare(candidate.uri)) return
                icon.setTag(R.id.browser_thumb_key, key)
                if (inflight.putIfAbsent(key, true) != null) return
                val isImageCover = imageCandidate != null
                val executor = if (candidate.uri?.scheme == "smb") networkExecutor else localExecutor
                executor.execute {
                    val bitmap = try {
                        if (isImageCover) loadBitmap(icon.context, candidate)
                        else loadVideoBitmap(icon.context, candidate)
                    } catch (e: SmbImageLoader.SmbAuthRequiredException) {
                        null
                    } catch (ignored: Exception) {
                        null
                    }
                    if (bitmap != null) {
                        BitmapCache.addBitmapToMemCache(key, bitmap)
                        thumbTimes[key] = System.currentTimeMillis()
                        NetworkThumbStore.put(icon.context, key, bitmap)
                    } else negatives[key] = System.currentTimeMillis()
                    mainHandler.post {
                        inflight.remove(key)
                        val tagMatch = icon.getTag(R.id.browser_thumb_key) == key
                        if (bitmap != null && tagMatch) apply(bitmap)
                    }
                }
            }
        }
    }

    private fun loadAsync(icon: ImageView, media: MediaWrapper, apply: (Bitmap) -> Unit) {
        if (unreachableShare(media.uri)) return
        val key = media.uri?.toString() ?: return
        icon.setTag(R.id.browser_thumb_key, key)
        if (inflight.putIfAbsent(key, true) != null) return
        val isVideo = media.type == MediaWrapper.TYPE_VIDEO
        val cacheKey = if (isVideo) videoKey(media) else mediaKey(media)
        val executor = if (media.uri?.scheme == "smb") networkExecutor else localExecutor
        executor.execute {
            val bitmap = try {
                if (isVideo) loadVideoBitmap(icon.context, media) else loadBitmap(icon.context, media)
            } catch (e: SmbImageLoader.SmbAuthRequiredException) {
                null
            }
            if (bitmap != null) {
                BitmapCache.addBitmapToMemCache(cacheKey, bitmap)
                thumbTimes[cacheKey] = System.currentTimeMillis()
                NetworkThumbStore.put(icon.context, cacheKey, bitmap)
            } else negatives[cacheKey] = System.currentTimeMillis()
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
