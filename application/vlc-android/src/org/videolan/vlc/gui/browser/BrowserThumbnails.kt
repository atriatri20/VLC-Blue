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
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.BitmapCache
import org.videolan.vlc.R
import org.videolan.vlc.gui.image.ImageRepository
import org.videolan.vlc.gui.image.SmbImageLoader
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

    private const val THUMB_SIZE = 128
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

    private fun mediaKey(media: MediaWrapper) = "bimg_${media.uri}_$THUMB_SIZE"

    private fun folderKey(folder: MediaWrapper) = "bimgdir_${folder.uri}_$THUMB_SIZE"

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
        val executor = if (media.uri?.scheme == "smb") networkExecutor else localExecutor
        executor.execute {
            val bitmap = loadBitmap(icon.context, media)
            if (bitmap != null) BitmapCache.addBitmapToMemCache(mediaKey(media), bitmap)
            else negatives[mediaKey(media)] = System.currentTimeMillis()
            mainHandler.post {
                inflight.remove(key)
                if (bitmap != null && icon.getTag(R.id.browser_thumb_key) == key) applyDrawable(container, bitmap)
            }
        }
    }

    private fun loadBitmap(context: Context, media: MediaWrapper): Bitmap? {
        return try {
            ImageRepository.decodeSampledBitmap(context.applicationContext, media.uri, THUMB_SIZE, THUMB_SIZE)
        } catch (e: SmbImageLoader.SmbAuthRequiredException) {
            // No credentials for this host yet: stay on the plain icon; the
            // viewer will prompt once and the credentials get stored globally.
            null
        }
    }

    private fun applyDrawable(container: BrowserItemBindingContainer, bitmap: Bitmap) {
        container.setCover(BitmapDrawable(container.itemIcon.resources, bitmap))
    }
}
