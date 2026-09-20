/*
 * *************************************************************************
 *  ImageGridAdapter.kt
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
import org.videolan.resources.util.HeaderProvider
import org.videolan.resources.util.HeadersIndex
import androidx.collection.SparseArrayCompat
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One row of the image grid: either a date section header or an image card */
class ImageRow(val header: String? = null, val image: ImageInfo? = null)

object ImageSections {

    /**
     * Builds the grid rows: when [dateSections] the date-sorted images are
     * grouped under 今天/昨天/"yyyy年M月" headers, otherwise a flat grid.
     */
    fun buildRows(images: List<ImageInfo>, dateSections: Boolean, context: Context): List<ImageRow> {
        val rows = ArrayList<ImageRow>(images.size + 8)
        if (!dateSections) {
            images.forEach { rows.add(ImageRow(image = it)) }
            return rows
        }
        val today = dayStart(0)
        val yesterday = dayStart(1)
        val monthPattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yyyyMMMM")
        val monthFormat = SimpleDateFormat(monthPattern, Locale.getDefault())
        var header: String? = null
        images.forEach { image ->
            val calendar = Calendar.getInstance().apply { timeInMillis = image.dateAdded * 1000L }
            val label = when {
                calendar.timeInMillis >= today -> context.getString(R.string.image_today)
                calendar.timeInMillis >= yesterday -> context.getString(R.string.image_yesterday)
                else -> monthFormat.format(calendar.time)
            }
            if (label != header) {
                header = label
                rows.add(ImageRow(header = label))
            }
            rows.add(ImageRow(image = image))
        }
        return rows
    }

    /** FastScroller index: position of every header row → its label */
    fun headerIndex(rows: List<ImageRow>): HeadersIndex {
        val index = HeadersIndex()
        rows.forEachIndexed { position, row -> row.header?.let { index.put(position, it) } }
        return index
    }

    private fun dayStart(daysAgo: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return calendar.timeInMillis
    }
}

/** Feeds the fast scroll bubble with the current date sections */
class ImageHeaderProvider : HeaderProvider() {
    fun submit(index: HeadersIndex) {
        (liveHeaders as? MutableLiveData<HeadersIndex>)?.value = index
    }
}

/**
 * Card grid for the device images: SMB large cover cards with the file name
 * below, optional date section headers (fast-scroll bubble), and a long-press
 * multi selection. Submitting a new list goes through DiffUtil so visible
 * cards are not rebound unless they really changed.
 */
class ImageGridAdapter(
        private val lifecycleOwner: LifecycleOwner,
        private val thumbProvider: (ImageInfo) -> Bitmap?,
        private val listener: Listener
) : ListAdapter<ImageRow, RecyclerView.ViewHolder>(Diff), FastScroller.SeparatedAdapter {

    interface Listener {
        /** The image card was tapped while not in selection mode */
        fun onImageClick(image: ImageInfo)

        /** The selection count changed (start/stop the action mode) */
        fun onSelectionChanged(count: Int)
    }

    private val rows get() = currentList
    private val selected = LinkedHashMap<Long, ImageInfo>()
    var selectionMode = false
        private set
    var dateSections = false
        private set

    object Diff : DiffUtil.ItemCallback<ImageRow>() {
        override fun areItemsTheSame(oldItem: ImageRow, newItem: ImageRow): Boolean {
            return if (oldItem.header != null && newItem.header != null) oldItem.header == newItem.header
            else oldItem.image != null && newItem.image != null && oldItem.image.id == newItem.image.id
        }

        override fun areContentsTheSame(oldItem: ImageRow, newItem: ImageRow): Boolean {
            return if (oldItem.header != null && newItem.header != null) oldItem.header == newItem.header
            else oldItem.image?.id == newItem.image?.id && oldItem.image?.name == newItem.image?.name
        }
    }

    fun setRows(newRows: List<ImageRow>, sections: Boolean) {
        dateSections = sections
        val valid = newRows.mapNotNullTo(HashSet()) { it.image?.id }
        selected.keys.retainAll(valid)
        submitList(ArrayList(newRows))
    }

    fun selectedImages(): List<ImageInfo> = ArrayList(selected.values)
    fun selectionCount() = selected.size

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

    fun toggle(image: ImageInfo) {
        if (selected.remove(image.id) == null) selected[image.id] = image
        val position = rows.indexOfFirst { it.image?.id == image.id }
        if (position >= 0) notifyItemChanged(position)
        listener.onSelectionChanged(selected.size)
    }

    fun selectAll(images: List<ImageInfo>) {
        selected.clear()
        images.forEach { selected[it.id] = it }
        notifyDataSetChanged()
        listener.onSelectionChanged(selected.size)
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position].header != null) TYPE_HEADER else TYPE_CARD

    /** True for date section headers (they span the whole grid width) */
    fun isHeader(position: Int) = rows.getOrNull(position)?.header != null

    override fun hasSections() = dateSections

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_image_section_header, parent, false))
        } else {
            CardViewHolder(inflater.inflate(R.layout.item_image_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeaderViewHolder -> holder.title.text = row.header
            is CardViewHolder -> row.image?.let { holder.bind(it) }
        }
    }

    private inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.section_header)
    }

    private inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.image_thumbnail)
        private val title: TextView = itemView.findViewById(R.id.image_title)
        private val scrim: View = itemView.findViewById(R.id.selection_scrim)
        private val check: ImageView = itemView.findViewById(R.id.selection_check)

        fun bind(item: ImageInfo) {
            image.tag = item.id
            image.setImageBitmap(null)
            title.text = item.name
            val isSelected = selectionMode && selected.containsKey(item.id)
            scrim.visibility = if (isSelected) View.VISIBLE else View.GONE
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.setOnClickListener {
                if (selectionMode) toggle(item) else listener.onImageClick(item)
            }
            itemView.setOnLongClickListener {
                if (!selectionMode) setSelectionMode(true)
                toggle(item)
                true
            }
            lifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { thumbProvider(item) }
                if (image.tag == item.id) image.setImageBitmap(bitmap)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
    }
}
