/*
 * *************************************************************************
 *  NetworkThumbStore.kt
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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk cache for browser thumbnails of network (smb://) content: row image
 * thumbnails, video preview frames and folder covers. The cache key is the
 * full memory key (uri + size + kind), hashed to a file name under the app
 * cache dir. Must be called from background threads (the browser executors).
 */
object NetworkThumbStore {

    private const val FOLDER = "net_thumbs"
    private const val MAX_BYTES = 128L * 1024L * 1024L
    private val writeLock = Any()
    private var putCount = 0

    /**
     * Reads the cached bitmap for [key], dropping unreadable files. Age is not
     * judged here: an expired thumbnail is still better than no thumbnail, so
     * the caller keeps showing it and refreshes it in the background.
     */
    fun get(context: Context, key: String): Bitmap? {
        val file = diskFile(context, key)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath) ?: run {
            file.delete()
            null
        }
    }

    /** Last write time of the entry for [key], 0 when absent */
    fun lastModified(context: Context, key: String): Long {
        val file = diskFile(context, key)
        return if (file.isFile) file.lastModified() else 0L
    }

    /** Persists [bitmap] for [key], replacing the previous one atomically */
    fun put(context: Context, key: String, bitmap: Bitmap) {
        synchronized(writeLock) {
            val file = diskFile(context, key)
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                file.parentFile?.mkdirs()
                FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }.onSuccess {
                // rename replaces the old file atomically; if it fails we simply
                // keep serving the previous thumbnail
                if (!tmp.renameTo(file)) tmp.delete()
            }.onFailure { tmp.delete() }
            if (++putCount % 64 == 0) trim(context)
        }
    }

    /** Deletes the oldest files while the folder exceeds the size cap */
    private fun trim(context: Context) {
        val folder = File(context.applicationContext.cacheDir, FOLDER)
        val files = folder.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun diskFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.applicationContext.cacheDir, FOLDER), "$name.jpg")
    }
}
