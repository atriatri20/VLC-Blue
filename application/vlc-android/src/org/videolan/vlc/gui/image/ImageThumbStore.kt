/*
 * *************************************************************************
 *  ImageThumbStore.kt
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
package org.videolan.vlc.gui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.videolan.tools.BitmapCache
import java.io.File
import java.io.FileOutputStream

/**
 * Two-level thumbnail cache for the device image grids: memory first, then a
 * disk folder under the app cache dir, then a MediaStore decode (persisted to
 * disk for the next sessions). Must be called from a background thread.
 */
object ImageThumbStore {

    private const val FOLDER = "image_thumbs"

    /** Decodes (or loads) the thumbnail of [info] at the requested 2:3 card size */
    fun load(context: Context, info: ImageInfo, width: Int, height: Int): Bitmap? {
        val appContext = context.applicationContext
        val key = "img_thumb_${info.id}_${width}x$height"
        BitmapCache.getBitmapFromMemCache(key)?.let { return it }

        val file = diskFile(appContext, info.id, width, height)
        if (file.isFile) {
            val cached = BitmapFactory.decodeFile(file.absolutePath)
            if (cached != null) {
                BitmapCache.addBitmapToMemCache(key, cached)
                return cached
            }
            file.delete()
        }

        val bitmap = ImageRepository.decodeSampledBitmap(appContext, info, width, height) ?: return null
        BitmapCache.addBitmapToMemCache(key, bitmap)
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return bitmap
    }

    /**
     * Removes cached thumbnails that no longer match any MediaStore id (deleted
     * images, obsolete card sizes). Cheap enough to run after every reload.
     */
    fun cleanup(context: Context, validIds: Collection<Long>) {
        val folder = File(context.applicationContext.cacheDir, FOLDER)
        val files = folder.listFiles() ?: return
        for (file in files) {
            val id = file.nameWithoutExtension.substringBefore('_').toLongOrNull()
            if (id == null || id !in validIds) file.delete()
        }
    }

    private fun diskFile(context: Context, id: Long, width: Int, height: Int): File {
        val folder = File(context.cacheDir, FOLDER)
        return File(folder, "${id}_${width}x$height.jpg")
    }
}
