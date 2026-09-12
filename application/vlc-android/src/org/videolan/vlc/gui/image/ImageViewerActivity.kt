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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ImageViewerActivityBinding
import org.videolan.vlc.gui.BaseActivity

/**
 * Full screen image viewer with three reading modes:
 *
 * - vertical pages (swipe up/down to change image, pinch/double tap zoom)
 * - horizontal pages (swipe left/right)
 * - continuous strip (webtoon style: all images of the folder stacked in one
 *   scrollable column, with optional hands-free smooth auto scrolling)
 *
 * The mode is switchable from the toolbar at any time, keeps the current image
 * and is remembered across sessions. Also serves as a VIEW handler for images
 * opened from other apps.
 */
class ImageViewerActivity : BaseActivity() {

    companion object {
        const val EXTRA_POSITION = "extra_position"
        private const val EXTRA_FOLDER_MODE = "extra_folder_mode"
        private const val DECODE_FACTOR = 1.5f
        private const val PREF_VIEWER_MODE = "image_viewer_mode"
        private const val PREF_AUTO_SPEED = "image_auto_scroll_speed"
        const val MODE_PAGE_VERTICAL = 0
        const val MODE_PAGE_HORIZONTAL = 1
        const val MODE_CONTINUOUS = 2

        /**
         * Folder listings can be huge, so they travel through this static slot
         * instead of the intent. Consumed by the next launch of this activity.
         */
        private var folderEntries: List<String>? = null

        /**
         * Viewer over a list of image uris (local paths, file:// or smb:// mrls),
         * typically every image of the browsed folder.
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
    private var mode = MODE_PAGE_VERTICAL
    private var currentIndex = 0
    private var stripPendingPositioning = false
    private var autoScrolling = false
    private var touchPaused = false
    private var autoSpeed = 3
    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun getSnackAnchorView(overAudioPlayer: Boolean): View? = binding.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.image_viewer_activity)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        val metrics = resources.displayMetrics
        decodeWidth = (metrics.widthPixels * DECODE_FACTOR).toInt()
        decodeHeight = (metrics.heightPixels * DECODE_FACTOR).toInt()

        mode = Settings.getInstance(applicationContext).getInt(PREF_VIEWER_MODE, MODE_PAGE_VERTICAL)
                .coerceIn(MODE_PAGE_VERTICAL, MODE_CONTINUOUS)
        autoSpeed = Settings.getInstance(applicationContext).getInt(PREF_AUTO_SPEED, 3).coerceIn(1, 10)

        requestedPosition = intent.getIntExtra(EXTRA_POSITION, -1)
        requestedUri = intent.data

        binding.backButton.setOnClickListener { finish() }
        binding.shareButton.setOnClickListener { shareCurrent() }
        binding.modeButton.setOnClickListener { showModeDialog() }
        binding.autoScrollFab.setOnClickListener { toggleAutoScroll() }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateOverlay()
            }
        })
        binding.strip.layoutManager = LinearLayoutManager(this)
        binding.strip.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (mode != MODE_CONTINUOUS || stripPendingPositioning) return
                val first = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                if (first in images.indices && first != currentIndex) {
                    currentIndex = first
                    updateOverlay()
                }
            }
        })
        binding.strip.addOnItemTouchListener(stripTouchListener)

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
                    currentIndex = if (images.isEmpty()) 0 else requestedPosition.coerceIn(0, images.size - 1)
                    applyMode()
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
                    currentIndex = 0
                    applyMode()
                    return@launch
                }
            }
            images = list
            currentIndex = if (images.isEmpty()) 0 else position.coerceIn(0, images.size - 1)
            applyMode()
        }
    }

    /**
     * Switch between reading modes, keeping the current image in view
     */
    private fun applyMode() {
        stopAutoScrollTick()
        if (mode == MODE_CONTINUOUS) {
            binding.pager.visibility = View.GONE
            binding.strip.visibility = View.VISIBLE
            binding.autoScrollFab.visibility = View.VISIBLE
            // block the scroll listener while the strip settles, otherwise it
            // overwrites currentIndex with 0 and the viewer opens at image 1
            stripPendingPositioning = true
            binding.strip.adapter = StripAdapter(images)
            (binding.strip.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
            // re-assert after the first layout: a pending scroll issued before
            // the RecyclerView has been measured can be lost
            binding.strip.post {
                (binding.strip.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
                stripPendingPositioning = false
            }
            syncFab()
            if (autoScrolling) startAutoScrollTick()
        } else {
            autoScrolling = false
            binding.strip.visibility = View.GONE
            binding.autoScrollFab.visibility = View.GONE
            binding.pager.visibility = View.VISIBLE
            binding.pager.orientation = if (mode == MODE_PAGE_HORIZONTAL) ViewPager2.ORIENTATION_HORIZONTAL
            else ViewPager2.ORIENTATION_VERTICAL
            binding.pager.adapter = ViewerAdapter(images)
            binding.pager.setCurrentItem(currentIndex, false)
        }
        Settings.getInstance(applicationContext).edit().putInt(PREF_VIEWER_MODE, mode).apply()
        updateOverlay()
    }

    private fun updateOverlay() {
        val item = images.getOrNull(currentIndex)
        binding.imageName.text = item?.name ?: ""
        binding.imageCounter.text = if (images.isEmpty()) "" else getString(R.string.image_counter_format, currentIndex + 1, images.size)
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
        val item = images.getOrNull(currentIndex) ?: return
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

    /**
     * Reading mode picker: three modes plus the auto scroll controls
     */
    private fun showModeDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, 0)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.viewer_mode)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        fun modeRow(labelRes: Int, modeValue: Int): View {
            val row = TextView(this)
            row.text = if (mode == modeValue) "●  ${getString(labelRes)}" else "○  ${getString(labelRes)}"
            row.textSize = 16f
            row.setPadding(pad, pad / 2, pad, pad / 2)
            row.setOnClickListener {
                dialog.dismiss()
                if (mode != modeValue) {
                    mode = modeValue
                    applyMode()
                }
            }
            return row
        }
        box.addView(modeRow(R.string.mode_page_vertical, MODE_PAGE_VERTICAL))
        box.addView(modeRow(R.string.mode_page_horizontal, MODE_PAGE_HORIZONTAL))
        box.addView(modeRow(R.string.mode_continuous, MODE_CONTINUOUS))

        val autoRow = Switch(this).apply {
            text = getString(R.string.auto_scroll)
            textSize = 16f
            isChecked = autoScrolling
            setPadding(pad, pad, pad, pad / 2)
            setOnCheckedChangeListener { _, checked ->
                autoScrolling = checked
                if (mode == MODE_CONTINUOUS) {
                    syncFab()
                    if (checked) startAutoScrollTick() else stopAutoScrollTick()
                }
            }
        }
        box.addView(autoRow)

        val speedLabel = TextView(this).apply {
            text = getString(R.string.auto_scroll_speed)
            textSize = 14f
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(speedLabel)
        val seek = SeekBar(this).apply {
            max = 9
            progress = autoSpeed - 1
            setPadding(pad, 0, pad, pad)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    autoSpeed = progress + 1
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    Settings.getInstance(applicationContext).edit().putInt(PREF_AUTO_SPEED, autoSpeed).apply()
                }
            })
        }
        box.addView(seek)
        dialog.show()
    }

    private fun toggleAutoScroll() {
        autoScrolling = !autoScrolling
        syncFab()
        if (autoScrolling) startAutoScrollTick() else stopAutoScrollTick()
    }

    private fun syncFab() {
        binding.autoScrollFab.setImageResource(if (autoScrolling) R.drawable.ic_auto_pause else R.drawable.ic_auto_scroll)
    }

    private fun startAutoScrollTick() {
        binding.strip.removeCallbacks(autoScrollTick)
        binding.strip.postOnAnimation(autoScrollTick)
    }

    private fun stopAutoScrollTick() {
        binding.strip.removeCallbacks(autoScrollTick)
    }

    private val autoScrollTick = object : Runnable {
        override fun run() {
            if (mode != MODE_CONTINUOUS || !autoScrolling) return
            if (!touchPaused) {
                val rv = binding.strip
                val px = (autoSpeed * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                rv.scrollBy(0, px)
                if (!rv.canScrollVertically(1)) {
                    autoScrolling = false
                    syncFab()
                    Toast.makeText(this@ImageViewerActivity, R.string.auto_scroll_end, Toast.LENGTH_SHORT).show()
                    return
                }
            }
            binding.strip.postOnAnimation(this)
        }
    }

    /**
     * Strip gestures: single tap toggles the toolbar, two fingers scale the
     * strip, touching always pauses the auto scroll until release
     */
    private val stripTapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleOverlay()
                return true
            }
        })
    }

    private val stripScaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val strip = binding.strip
                strip.pivotX = detector.focusX
                strip.pivotY = detector.focusY
                val newScale = (strip.scaleX * detector.scaleFactor).coerceIn(1f, 3f)
                strip.scaleX = newScale
                strip.scaleY = newScale
                return true
            }
        })
    }

    private val stripTouchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> touchPaused = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchPaused = false
            }
            stripTapDetector.onTouchEvent(e)
            stripScaleDetector.onTouchEvent(e)
            return false
        }
    }

    /**
     * Pages for the paged modes: a zoomable image per page
     */
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
     * Endless strip for the continuous mode: images stacked without gaps,
     * decoded at screen size
     */
    inner class StripAdapter(private val items: List<ImageInfo>) : RecyclerView.Adapter<StripAdapter.StripHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder {
            val view = AppCompatImageView(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            view.adjustViewBounds = true
            view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            return StripHolder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: StripHolder, position: Int) {
            holder.bind(items[position], position)
        }

        inner class StripHolder(val view: AppCompatImageView) : RecyclerView.ViewHolder(view) {
            fun bind(item: ImageInfo, position: Int) {
                view.tag = item.uri
                view.setImageDrawable(null)
                lifecycleScope.launch {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = withContext(Dispatchers.IO) { getFullBitmap(item) }
                    } catch (e: SmbImageLoader.SmbAuthRequiredException) {
                        withContext(Dispatchers.Main) { promptSmbCredentials(e.host) }
                    } catch (ignored: Exception) {
                    }
                    if (view.tag == item.uri) view.setImageBitmap(bitmap)
                    if (bitmap != null) withContext(Dispatchers.IO) {
                        prefetchStrip(position + 1)
                        prefetchStrip(position + 2)
                    }
                }
            }
        }

        private suspend fun prefetchStrip(position: Int) {
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
                    refreshCurrentImage()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> smbPromptShowing = false }
                .setOnCancelListener { smbPromptShowing = false }
                .show()
    }

    private fun refreshCurrentImage() {
        when {
            mode == MODE_CONTINUOUS && binding.strip.adapter != null -> {
                val adapter = binding.strip.adapter as? StripAdapter ?: return
                adapter.notifyItemRangeChanged(currentIndex, 2)
            }
            binding.pager.adapter != null -> binding.pager.adapter?.notifyDataSetChanged()
        }
    }
}
