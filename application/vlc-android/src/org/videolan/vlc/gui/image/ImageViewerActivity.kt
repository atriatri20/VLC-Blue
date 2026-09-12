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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
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
 * on the current page. Also serves as a VIEW handler for image files opened from other apps.
 */
class ImageViewerActivity : BaseActivity() {

    companion object {
        const val EXTRA_POSITION = "extra_position"
        private const val EXTRA_FOLDER_MODE = "extra_folder_mode"
        private const val DECODE_FACTOR = 1.5f

        /**
         * Folder listings can be huge, so they travel through this static slot
         * instead of the intent. Consumed by the next launch of this activity.
         */
        private var folderEntries: List<String>? = null

        /**
         * Viewer over a list of image uris (local paths, file:// or smb:// mrls),
         * typically every image of the browsed folder: pages scroll vertically
         * and continuously through the whole list.
         */
        fun folderIntent(context: Context, entries: List<String>, startIndex: Int): Intent {
            folderEntries = entries
            return Intent(context, ImageViewerActivity::class.java)
                    .putExtra(EXTRA_POSITION, startIndex)
                    .putExtra(EXTRA_FOLDER_MODE, true)
        }
    }

    private lateinit var binding: ImageViewerActivityBinding
    private var images: List<ImageInfo> = emptyList()
    private var requestedPosition = -1
    private var requestedUri: Uri? = null
    private var overlayVisible = true
    private var decodeWidth = 1080
    private var decodeHeight = 1920
    private var smbPromptShowing = false
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
            if (intent.getBooleanExtra(EXTRA_FOLDER_MODE, false)) {
                val entries = folderEntries
                folderEntries = null
                if (entries != null) {
                    images = entries.map { entry ->
                        val uri = Uri.parse(entry)
                        ImageInfo(-1L, uri,
                                if (uri.scheme == null || uri.scheme == "file") uri.path else null,
                                uri.lastPathSegment ?: entry,
                                0L, 0L, "image/*", null)
                    }
                    if (images.isNotEmpty()) setupPager(requestedPosition.coerceIn(0, images.size - 1))
                    else setupPager(-1)
                    return@launch
                }
            }
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
        val key = "img_full_${info.uri}_${decodeWidth}x$decodeHeight"
        BitmapCache.getBitmapFromMemCache(key)?.let { return it }
        val bitmap = ImageRepository.decodeSampledBitmap(applicationContext, info.uri, decodeWidth, decodeHeight) ?: return null
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
            holder.bind(items[position], position)
        }

        inner class ViewHolder(val view: ZoomableImageView) : RecyclerView.ViewHolder(view) {
            fun bind(item: ImageInfo, position: Int) {
                view.setZoomableBitmap(null)
                view.tag = item.uri
                lifecycleScope.launch {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = withContext(Dispatchers.IO) { getFullBitmap(item) }
                    } catch (e: SmbImageLoader.SmbAuthRequiredException) {
                        withContext(Dispatchers.Main) { promptSmbCredentials(e.host) }
                    } catch (ignored: Exception) {
                    }
                    if (view.tag == item.uri) view.setZoomableBitmap(bitmap)
                    if (bitmap != null) withContext(Dispatchers.IO) { prefetch(position + 1) }
                }
            }
        }

        /**
         * Decode the next page ahead of time so vertical swiping feels instant,
         * especially over slow network shares.
         */
        private suspend fun prefetch(position: Int) {
            val item = items.getOrNull(position) ?: return
            val key = "img_full_${item.uri}_${decodeWidth}x$decodeHeight"
            if (BitmapCache.getBitmapFromMemCache(key) != null) return
            try {
                ImageRepository.decodeSampledBitmap(applicationContext, item.uri, decodeWidth, decodeHeight)?.let {
                    BitmapCache.addBitmapToMemCache(key, it)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * First contact with a password protected share: ask once, store the
     * credentials globally (thumbnails included) and reload the current page.
     */
    private fun promptSmbCredentials(host: String) {
        if (smbPromptShowing) return
        smbPromptShowing = true
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val userEdit = EditText(this).apply { hint = getString(R.string.smb_username); setSingleLine() }
        val passEdit = EditText(this).apply {
            hint = getString(R.string.smb_password)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(userEdit)
        layout.addView(passEdit)
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.smb_login_title, host))
                .setView(layout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    smbPromptShowing = false
                    SmbImageLoader.storeCredential(host, userEdit.text.toString(), passEdit.text.toString())
                    binding.pager.adapter?.notifyDataSetChanged()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> smbPromptShowing = false }
                .setOnCancelListener { smbPromptShowing = false }
                .show()
    }
}
