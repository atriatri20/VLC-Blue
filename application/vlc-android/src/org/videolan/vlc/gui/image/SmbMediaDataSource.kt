/*
 * *************************************************************************
 *  SmbMediaDataSource.kt
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

import android.media.MediaDataSource
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import jcifs.smb.SmbRandomAccessFile

/**
 * Bridges a random-access jcifs SMB handle into the android media framework,
 * so MediaMetadataRetriever can extract preview frames from videos stored on
 * network shares without downloading them.
 */
@RequiresApi(Build.VERSION_CODES.M)
class SmbMediaDataSource(uri: Uri) : MediaDataSource() {

    private val file: SmbRandomAccessFile? = SmbImageLoader.openRandomAccess(uri)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val handle = file ?: return -1
        return try {
            handle.seek(position)
            var total = 0
            while (total < size) {
                val read = handle.read(buffer, offset + total, size - total)
                if (read <= 0) break
                total += read
            }
            if (total == 0) -1 else total
        } catch (ignored: Exception) {
            -1
        }
    }

    override fun getSize(): Long = try {
        file?.length() ?: -1L
    } catch (ignored: Exception) {
        -1L
    }

    override fun close() {
        try {
            file?.close()
        } catch (ignored: Exception) {
        }
    }
}
