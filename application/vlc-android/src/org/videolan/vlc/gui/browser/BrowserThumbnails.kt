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
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.image.ImageRepository
import org.videolan.vlc.gui.image.SmbImageLoader
import org.videolan.vlc.gui.image.SmbMediaDataSource
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Row thumbnails for the file and network browsers.
 *
 * Image files get a decoded thumbnail, folders get a cover preview (the first
 * image found inside them, cached while the provider parses each folder
 * listing). smb:// entries are decoded through the jcifs client on a small
 * dedicated executor. Negative results are remembered briefly so entries
 * without a decodable image are not retried on every rebind.
 */
object BrowserThumbnails {

    private const val THUMB_W = 320
    private const val THUMB_H = 480
    private const val NEGATIVE_TTL_MS = 60L * 1000L

    private val localExecutor = Executors.newFixedThreadPool(2)
    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inflight = ConcurrentHashMap<String, Boolean>()
    private val negatives = ConcurrentHashMap<String, Long>()

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
     * is available: caches the first image found inside as the folder cover.
     */
    fun cacheFolderPreview(context: Context, folder: MediaLibraryItem?, listing: List<MediaLibraryItem>) {
        if (folder !is MediaWrapper) return
        val key = folderKey(folder)
        if (BitmapCache.getBitmapFromMemCache(key) != null || isNegative(key)) return
        val firstImage = listing.filterIsInstance<MediaWrapper>().firstOrNull { isImage(it) } ?: run {
            negatives[key] = System.currentTimeMillis()
            return
        }
        val bitmap = loadBitmap(context, firstImage)
        if (bitmap != null) BitmapCache.addBitmapToMemCache(key, bitmap)
        else negatives[key] = System.currentTimeMillis()
    }

    private fun getCachedFolderPreview(folder: MediaWrapper): Bitmap? {
        val key = folderKey(folder)
        if (isNegative(key)) return null
        return BitmapCache.getBitmapFromMemCache(key)
    }

    /**
     * Called on every media row bind. Applies the cached thumbnail/preview when
     * available, otherwise starts an async decode for image files and patches
     * the row when done.
     */
    fun bind(container: BrowserItemBindingContainer, media: MediaWrapper) {
        when {
            isImage(media) -> {
                if (isNegative(mediaKey(media))) return
                val cached = BitmapCache.getBitmapFromMemCache(mediaKey(media))
                if (cached != null) applyDrawable(container, cached)
                else loadAsync(container, media)
            }
            media.type == MediaWrapper.TYPE_VIDEO -> {
                if (!Settings.showVideoThumbs || isNegative(videoKey(media))) return
                val cached = BitmapCache.getBitmapFromMemCache(videoKey(media))
                if (cached != null) applyDrawable(container, cached)
                else loadAsync(container, media)
            }
            media.type == MediaWrapper.TYPE_DIR -> {
                val preview = getCachedFolderPreview(media) ?: return
                applyDrawable(container, preview)
            }
        }
    }

    private fun loadAsync(container: BrowserItemBindingContainer, media: MediaWrapper) {
        val icon = container.itemIcon
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
            if (bitmap != null) BitmapCache.addBitmapToMemCache(cacheKey, bitmap)
            else negatives[cacheKey] = System.currentTimeMillis()
            mainHandler.post {
                inflight.remove(key)
                if (bitmap != null && icon.getTag(R.id.browser_thumb_key) == key) applyDrawable(container, bitmap)
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
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
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
