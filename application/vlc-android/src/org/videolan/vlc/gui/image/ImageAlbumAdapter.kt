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
 * Long press enters a multi selection, used to hide albums.
 */
class ImageAlbumAdapter(
        private val lifecycleOwner: LifecycleOwner,
        private val thumbProvider: (ImageInfo) -> Bitmap?,
        private val listener: Listener
) : ListAdapter<ImageAlbum, ImageAlbumAdapter.AlbumViewHolder>(Diff), FastScroller.SeparatedAdapter {

    interface Listener {
        /** The album card was tapped while not in selection mode */
        fun onAlbumClick(album: ImageAlbum)

        /** The selection count changed (start/stop the action mode) */
        fun onSelectionChanged(count: Int)
    }

    private val selected = LinkedHashMap<Long, ImageAlbum>()
    var selectionMode = false
        private set
    private var hiddenIds: Set<Long> = emptySet()

    object Diff : DiffUtil.ItemCallback<ImageAlbum>() {
        override fun areItemsTheSame(oldItem: ImageAlbum, newItem: ImageAlbum) = oldItem.bucketId == newItem.bucketId

        override fun areContentsTheSame(oldItem: ImageAlbum, newItem: ImageAlbum): Boolean {
            return oldItem.name == newItem.name
                    && oldItem.images.size == newItem.images.size
                    && oldItem.cover.id == newItem.cover.id
        }
    }

    fun setAlbums(newAlbums: List<ImageAlbum>) {
        val valid = newAlbums.mapTo(HashSet()) { it.bucketId }
        selected.keys.retainAll(valid)
        submitList(ArrayList(newAlbums))
    }

    /** Albums currently flagged hidden; they get a marker in their count line */
    fun setHiddenIds(ids: Set<Long>) {
        if (hiddenIds == ids) return
        hiddenIds = ids
        notifyDataSetChanged()
    }

    fun selectedAlbums(): List<ImageAlbum> = ArrayList(selected.values)

    fun selectionCount() = selected.size

    /** True when every selected album is already hidden, so the action turns into 取消隐藏 */
    fun selectionAllHidden() = selected.isNotEmpty() && selected.keys.all { it in hiddenIds }

    fun clearSelection() {
        selected.clear()
        if (selectionMode) setSelectionMode(false) else notifyDataSetChanged()
    }

    fun setSelectionMode(active: Boolean) {
        if (selectionMode == active) return
        selectionMode = active
        if (!active) selected.clear()
        notifyDataSetChanged()
        listener.onSelectionChanged(selected.size)
    }

    fun toggle(album: ImageAlbum) {
        if (selected.remove(album.bucketId) == null) selected[album.bucketId] = album
        val position = currentList.indexOfFirst { it.bucketId == album.bucketId }
        if (position >= 0) notifyItemChanged(position)
        listener.onSelectionChanged(selected.size)
    }

    fun selectAll(albums: List<ImageAlbum>) {
        selected.clear()
        albums.forEach { selected[it.bucketId] = it }
        notifyDataSetChanged()
        listener.onSelectionChanged(selected.size)
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
        private val scrim: View = itemView.findViewById(R.id.selection_scrim)
        private val check: ImageView = itemView.findViewById(R.id.selection_check)

        fun bind(album: ImageAlbum) {
            val context = itemView.context
            val items = context.getString(R.string.image_count_items, album.images.size)
            cover.tag = album.bucketId
            cover.setImageBitmap(null)
            title.text = album.name
            count.text = if (album.bucketId in hiddenIds) context.getString(R.string.image_album_hidden_tag, items) else items
            val isSelected = selectionMode && selected.containsKey(album.bucketId)
            scrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.setOnClickListener {
                if (selectionMode) toggle(album) else listener.onAlbumClick(album)
            }
            itemView.setOnLongClickListener {
                if (!selectionMode) setSelectionMode(true)
                toggle(album)
                true
            }
            lifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { thumbProvider(album.cover) }
                if (cover.tag == album.bucketId) cover.setImageBitmap(bitmap)
            }
        }
    }
}
