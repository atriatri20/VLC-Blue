/*
 * *************************************************************************
 *  ImageRepository.kt
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

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.provider.MediaStore
import kotlinx.parcelize.Parcelize
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * A single image stored in the device MediaStore
 */
@Parcelize
data class ImageInfo(
        val id: Long,
        val uri: Uri,
        val path: String?,
        val name: String,
        val dateAdded: Long,
        val size: Long,
        val mimeType: String,
        val bucket: String?,
        val bucketId: Long = 0L,
        val width: Int = 0,
        val height: Int = 0
) : Parcelable

/**
 * A device album: all the images of one MediaStore bucket. The cover is the
 * most recent image of the bucket.
 */
data class ImageAlbum(
        val bucketId: Long,
        val name: String,
        val cover: ImageInfo,
        val images: List<ImageInfo>
) {
    val dateAdded: Long = cover.dateAdded
}

/** Sort orders for the image grids, persisted as an int */
enum class ImageSort(val value: Int) {
    DATE_DESC(0), DATE_ASC(1), NAME_ASC(2), NAME_DESC(3);

    /** True when this order groups images in date sections (headers in the grid) */
    val isDateBased: Boolean get() = this == DATE_DESC || this == DATE_ASC;

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: DATE_DESC
    }
}

/**
 * Queries the device images through MediaStore and decodes them at a requested size.
 * The medialibrary only indexes audio and video, so images are read directly from MediaStore.
 */
object ImageRepository {

    val IMAGE_EXTENSIONS = arrayOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif", ".avif")

    /** Remote payloads above this size are not downloaded for decoding */
    private const val MAX_BYTES = 20L * 1024L * 1024L

    fun isImageFile(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase(Locale.ENGLISH)
        val dot = lower.lastIndexOf('.')
        if (dot < 0) return false
        return IMAGE_EXTENSIONS.contains(lower.substring(dot))
    }

    fun queryImages(context: Context): List<ImageInfo> {
        val result = ArrayList<ImageInfo>()
        val projection = ArrayList<String>()
        projection.add(MediaStore.Images.Media._ID)
        projection.add(MediaStore.Images.Media.DATA)
        projection.add(MediaStore.Images.Media.DISPLAY_NAME)
        projection.add(MediaStore.Images.Media.DATE_ADDED)
        projection.add(MediaStore.Images.Media.SIZE)
        projection.add(MediaStore.Images.Media.MIME_TYPE)
        projection.add(MediaStore.Images.Media.WIDTH)
        projection.add(MediaStore.Images.Media.HEIGHT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            projection.add(MediaStore.Images.Media.BUCKET_ID)
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection.toTypedArray(),
                    null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) else -1
                val bucketIdCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID) else -1
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result.add(ImageInfo(
                            id = id,
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            path = cursor.getString(dataCol),
                            name = cursor.getString(nameCol) ?: "",
                            dateAdded = cursor.getLong(dateCol),
                            size = cursor.getLong(sizeCol),
                            mimeType = cursor.getString(mimeCol) ?: "image/*",
                            bucket = if (bucketCol != -1) cursor.getString(bucketCol) else null,
                            bucketId = if (bucketIdCol != -1) cursor.getLong(bucketIdCol) else 0L,
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol)
                    ))
                }
            }
        } catch (ignored: SecurityException) {
        } catch (ignored: IllegalArgumentException) {
        }
        return result
    }

    /**
     * Groups the (date-desc sorted) images into albums, one per MediaStore
     * bucket. The result keeps the newest-first order of the input.
     */
    fun groupAlbums(images: List<ImageInfo>): List<ImageAlbum> {
        val buckets = LinkedHashMap<Long, MutableList<ImageInfo>>()
        for (image in images) {
            val key = if (image.bucketId != 0L) image.bucketId else -image.id
            buckets.getOrPut(key) { ArrayList() }.add(image)
        }
        return buckets.map { (id, list) ->
            ImageAlbum(id, list[0].bucket ?: list[0].name, list[0], list)
        }
    }

    /** Sorts the images with the requested order, returning a new list */
    fun sortImages(images: List<ImageInfo>, sort: ImageSort): List<ImageInfo> = when (sort) {
        ImageSort.DATE_DESC -> images.sortedByDescending { it.dateAdded }
        ImageSort.DATE_ASC -> images.sortedBy { it.dateAdded }
        ImageSort.NAME_ASC -> images.sortedWith(compareBy<ImageInfo> { it.name.lowercase(Locale.ENGLISH) }.thenBy { it.id })
        ImageSort.NAME_DESC -> images.sortedWith(compareByDescending<ImageInfo> { it.name.lowercase(Locale.ENGLISH) }.thenByDescending { it.id })
    }

    /**
     * Decodes an image sampled down to at least [reqWidth] x [reqHeight]
     */
    fun decodeSampledBitmap(context: Context, info: ImageInfo, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        decode(context, info, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options()
        options.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight)
        return decode(context, info, options)
    }

    /**
     * Downloads an image into memory (capped). The result can be decoded
     * repeatedly without re-downloading.
     */
    fun loadBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            when (uri.scheme) {
                "smb" -> readAll(SmbImageLoader.openStream(uri))
                "content" -> context.contentResolver.openInputStream(uri)?.use { readAllStream(it) }
                else -> {
                    val path = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
                    readAllStream(java.io.FileInputStream(path))
                }
            }
        } catch (e: SmbImageLoader.SmbAuthRequiredException) {
            throw e
        } catch (ignored: Exception) {
            null
        }
    }

    private fun readAllStream(stream: InputStream): ByteArray? {
        return try {
            stream.use {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (ignored: Exception) {
            null
        }
    }

    /** Image dimensions from already downloaded bytes, without decoding the image */
    fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return Pair(bounds.outWidth, bounds.outHeight)
    }

    /** Decodes a sampled bitmap from already downloaded bytes */
    fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options()
        options.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Decodes any image uri: local paths, content providers and smb:// shares
     * (through the jcifs client). Returns null for unsupported schemes.
     */
    fun decodeSampledBitmap(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            when (uri.scheme) {
                "smb" -> decodeBytes(readAll(SmbImageLoader.openStream(uri)), reqWidth, reqHeight)
                "content" -> {
                    // NB: with inJustDecodeBounds=true, decodeStream returns null
                    // (it only fills the bounds), so the stream-null check must
                    // not be chained onto the decode result with ?:
                    val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
                    val bounds = BitmapFactory.Options()
                    bounds.inJustDecodeBounds = true
                    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    val options = BitmapFactory.Options()
                    options.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight)
                    val decodeIn = context.contentResolver.openInputStream(uri) ?: return null
                    decodeIn.use { BitmapFactory.decodeStream(it, null, options) }
                }
                else -> {
                    val path = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
                    val bounds = BitmapFactory.Options()
                    bounds.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    val options = BitmapFactory.Options()
                    options.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight)
                    BitmapFactory.decodeFile(path, options)
                }
            }
        } catch (e: SmbImageLoader.SmbAuthRequiredException) {
            throw e
        } catch (ignored: Exception) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray?, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (bytes == null) return null
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options()
        options.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun readAll(stream: InputStream?): ByteArray? {
        if (stream == null) return null
        return try {
            stream.use {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun decode(context: Context, info: ImageInfo, options: BitmapFactory.Options): Bitmap? {
        return try {
            val fromPath = info.path?.let { BitmapFactory.decodeFile(it, options) }
            fromPath ?: context.contentResolver.openInputStream(info.uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun computeInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }
}
