/*
 * *************************************************************************
 *  ImageViewerActivity.kt
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

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.BitmapCache
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ImageViewerActivityBinding
import org.videolan.vlc.gui.BaseActivity

/**
 * Full screen image viewer. Images are paged vertically: swipe up/down to move
 * to the next or previous image. Pinch zoom, double tap zoom and panning are supported
 * on the current page. Also serves as a VIEW handler for image/* content.
 */
class ImageViewerActivity : BaseActivity() {

    companion object {
        const val EXTRA_POSITION = "extra_position"
        private const val DECODE_FACTOR = 1.5f
    }

    private lateinit var binding: ImageViewerActivityBinding
    private var images: List<ImageInfo> = emptyList()
    private var requestedPosition = -1
    private var requestedUri: Uri? = null
    private var overlayVisible = true
    private var decodeWidth = 1080
    private var decodeHeight = 1920
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun getSnackAnchorView(overAudioPlayer: Boolean): View? = binding.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.image_viewer_activity)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        val metrics = resources.displayMetrics
        decodeWidth = (metrics.widthPixels * DECODE_FACTOR).toInt()
        decodeHeight = (metrics.heightPixels * DECODE_FACTOR).toInt()

        requestedPosition = intent.getIntExtra(EXTRA_POSITION, -1)
        requestedUri = intent.data

        binding.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.backButton.setOnClickListener { finish() }
        binding.shareButton.setOnClickListener { shareCurrent() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    ImageRepository.queryImages(applicationContext)
                } catch (ignored: Exception) {
                    emptyList<ImageInfo>()
                }
            }
            var position = requestedPosition
            val uri = requestedUri
            if (uri != null) {
                val index = list.indexOfFirst { it.uri == uri }
                if (index >= 0) position = index
                else if (position < 0) {
                    // The image is not part of MediaStore (e.g. opened from another provider)
                    images = listOf(ImageInfo(-1L, uri, uri.path, uri.lastPathSegment ?: "image", 0L, 0L, "image/*", null))
                    setupPager(0)
                    return@launch
                }
            }
            images = list
            if (images.isEmpty()) {
                setupPager(-1)
                return@launch
            }
            setupPager(position.coerceIn(0, images.size - 1))
        }
    }

    private fun setupPager(startPosition: Int) {
        binding.pager.adapter = ViewerAdapter(images)
        if (startPosition in images.indices) binding.pager.setCurrentItem(startPosition, false)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateOverlay()
            }
        })
        updateOverlay()
    }

    private fun updateOverlay() {
        val position = binding.pager.currentItem
        val item = images.getOrNull(position)
        binding.imageName.text = item?.name ?: ""
        binding.imageCounter.text = if (images.isEmpty()) "" else getString(R.string.image_counter_format, position + 1, images.size)
    }

    fun toggleOverlay() {
        overlayVisible = !overlayVisible
        binding.overlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
        if (overlayVisible) insetsController.show(WindowInsetsCompat.Type.systemBars()) else hideSystemBars()
    }

    private fun hideSystemBars() {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun shareCurrent() {
        val item = images.getOrNull(binding.pager.currentItem) ?: return
        val send = Intent(Intent.ACTION_SEND)
        send.type = item.mimeType
        send.putExtra(Intent.EXTRA_STREAM, item.uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.image_share)))
    }

    private fun getFullBitmap(info: ImageInfo): Bitmap? {
        val key = "img_full_${info.id}_${decodeWidth}x$decodeHeight"
        BitmapCache.getBitmapFromMemCache(key)?.let { return it }
        val bitmap = ImageRepository.decodeSampledBitmap(applicationContext, info, decodeWidth, decodeHeight) ?: return null
        BitmapCache.addBitmapToMemCache(key, bitmap)
        return bitmap
    }

    inner class ViewerAdapter(private val items: List<ImageInfo>) : RecyclerView.Adapter<ViewerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = ZoomableImageView(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            view.onSingleTap = { toggleOverlay() }
            return ViewHolder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(val view: ZoomableImageView) : RecyclerView.ViewHolder(view) {
            fun bind(item: ImageInfo) {
                view.setZoomableBitmap(null)
                view.tag = item.id
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) { getFullBitmap(item) }
                    if (view.tag == item.id) view.setZoomableBitmap(bitmap)
                }
            }
        }
    }
}
