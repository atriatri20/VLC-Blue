/*
 * *************************************************************************
 *  ImageAlbumAdapter.kt
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

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.vlc.R
import org.videolan.vlc.gui.view.FastScroller

/**
 * Album grid for the 相册 tab: the same SMB large cover card design, with the
 * album name and image count below the cover. Submissions go through DiffUtil.
 */
class ImageAlbumAdapter(
        private val lifecycleOwner: LifecycleOwner,
        private val thumbProvider: (ImageInfo) -> Bitmap?,
        private val listener: Listener
) : ListAdapter<ImageAlbum, ImageAlbumAdapter.AlbumViewHolder>(Diff), FastScroller.SeparatedAdapter {

    interface Listener {
        fun onAlbumClick(album: ImageAlbum)
    }

    object Diff : DiffUtil.ItemCallback<ImageAlbum>() {
        override fun areItemsTheSame(oldItem: ImageAlbum, newItem: ImageAlbum) = oldItem.bucketId == newItem.bucketId

        override fun areContentsTheSame(oldItem: ImageAlbum, newItem: ImageAlbum): Boolean {
            return oldItem.name == newItem.name
                    && oldItem.images.size == newItem.images.size
                    && oldItem.cover.id == newItem.cover.id
        }
    }

    fun setAlbums(newAlbums: List<ImageAlbum>) {
        submitList(ArrayList(newAlbums))
    }

    override fun getItemCount() = currentList.size

    override fun hasSections() = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        return AlbumViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_image_album, parent, false))
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.album_cover)
        private val title: TextView = itemView.findViewById(R.id.album_title)
        private val count: TextView = itemView.findViewById(R.id.album_count)

        fun bind(album: ImageAlbum) {
            cover.tag = album.bucketId
            cover.setImageBitmap(null)
            title.text = album.name
            count.text = itemView.context.getString(R.string.image_count_items, album.images.size)
            itemView.setOnClickListener { listener.onAlbumClick(album) }
            lifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { thumbProvider(album.cover) }
                if (cover.tag == album.bucketId) cover.setImageBitmap(bitmap)
            }
        }
    }
}
