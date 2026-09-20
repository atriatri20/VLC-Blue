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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
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
import android.view.Gravity
import androidx.appcompat.widget.AppCompatImageView
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ImageViewerActivityBinding
import org.videolan.vlc.gui.BaseActivity

/**
 * Full screen image viewer with three reading modes:
 *
 * - free pages (swipe any way: up/left next, down/right previous)
 * - continuous strip (webtoon style: all images of the folder stacked in one
 *   scrollable column, with optional hands-free smooth auto scrolling)
 * - card stack (the folder as a deck; throw the top card to turn the page)
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
        private const val DEFAULT_RATIO = 1.5f
        private const val PREF_VIEWER_MODE = "image_viewer_mode_v2"
        private const val PREF_AUTO_SPEED = "image_auto_scroll_speed"
        private const val PREF_SLIDESHOW_INTERVAL = "image_slideshow_interval"
        private val SLIDESHOW_INTERVALS = intArrayOf(1, 2, 3, 5, 10)
        const val MODE_PAGE_FREE = 0
        const val MODE_CONTINUOUS = 1
        const val MODE_CARD_STACK = 2

        /**
         * Folder listings can be huge, so they travel through this static slot
         * instead of the intent. Consumed by the next launch of this activity.
         */
        private var folderEntries: List<String>? = null

        /**
         * MediaStore listings (photos tab, album page) also travel through a
         * static slot so the viewer pages through exactly the list the grid
         * is displaying, in its current sort order. Consumed on next launch.
         */
        private var mediaStoreEntries: List<ImageInfo>? = null

        /**
         * Viewer over a list of device images (already sorted by the caller),
         * typically the photos tab or one album.
         */
        fun mediaStoreIntent(context: Context, entries: List<ImageInfo>, startIndex: Int): Intent {
            mediaStoreEntries = entries
            return Intent(context, ImageViewerActivity::class.java)
                    .putExtra(EXTRA_POSITION, startIndex)
        }

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
    private var mode = MODE_PAGE_FREE
    private var currentIndex = 0
    private var stripPendingPositioning = false
    private var scrubberDragging = false
    private var autoScrolling = false
    private var touchPaused = false
    private var autoSpeed = 3
    private var slideshowInterval = 3
    private var slideshowJob: kotlinx.coroutines.Job? = null
    private var stripWidth = 0
    private var stripDecodeWidth = 1080
    private var stripDecodeHeight = 1920
    private var sizePrefetchJob: Job? = null

    // continuous strip: downloaded bytes (shared by prefetch and decode),
    // predicted height/width ratio per position, in-flight downloads
    private val stripIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stripBytes = object : LruCache<Int, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray) = value.size
    }
    private val stripSizes = ConcurrentHashMap<Int, Float>()
    private val stripBytesJobs = ConcurrentHashMap<Int, Deferred<ByteArray?>>()
    private val stripInflight = ConcurrentHashMap.newKeySet<Int>()

    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun getSnackAnchorView(overAudioPlayer: Boolean): View? = binding.root

    /**
     * The viewer drives its own fullscreen flags. BaseActivity's edge-to-edge helper pads
     * android.R.id.content by the status bar inset, which leaks the window background
     * (white under the light app theme) as a bar above the image.
     */
    override var isEdgeToEdge = false
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            org.videolan.tools.BitmapCache.clear()
            stripBytes.evictAll()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // applyTheme() paints the window background with the app theme colour; a black
        // one keeps every pixel the viewer can expose (cutout, overscan, first frame) black
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        binding = DataBindingUtil.setContentView(this, R.layout.image_viewer_activity)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        val metrics = resources.displayMetrics
        decodeWidth = (metrics.widthPixels * DECODE_FACTOR).toInt()
        decodeHeight = (metrics.heightPixels * DECODE_FACTOR).toInt()

        mode = Settings.getInstance(applicationContext).getInt(PREF_VIEWER_MODE, MODE_PAGE_FREE)
                .coerceIn(MODE_PAGE_FREE, MODE_CARD_STACK)
        autoSpeed = Settings.getInstance(applicationContext).getInt(PREF_AUTO_SPEED, 3).coerceIn(1, 10)
        slideshowInterval = Settings.getInstance(applicationContext).getInt(PREF_SLIDESHOW_INTERVAL, 3)

        requestedPosition = intent.getIntExtra(EXTRA_POSITION, -1)
        requestedUri = intent.data

        binding.backButton.setOnClickListener { finish() }
        binding.shareButton.setOnClickListener { shareCurrent() }
        binding.slideshowButton.setOnClickListener { toggleSlideshow() }
        binding.slideshowButton.setOnLongClickListener {
            showSlideshowDialog()
            true
        }
        binding.modeButton.setOnClickListener { showModeDialog() }
        binding.autoScrollFab.setOnClickListener { toggleAutoScroll() }

        binding.freePager.onImageLoad = { image, position -> loadPage(image, position) }
        binding.freePager.onSingleTap = { toggleOverlay() }
        binding.freePager.onPositionChanged = { position -> onPaged(position) }
        binding.cardStack.onImageLoad = { image, position -> loadPage(image, position) }
        binding.cardStack.onSingleTap = { toggleOverlay() }
        binding.cardStack.onPositionChanged = { position -> onPaged(position) }
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

        binding.pageScrubber.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || mode != MODE_CONTINUOUS || images.isEmpty()) return
                currentIndex = progress.coerceIn(0, images.size - 1)
                (binding.strip.layoutManager as? LinearLayoutManager)?.scrollToPosition(currentIndex)
                updateOverlay()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                stripPendingPositioning = true
                scrubberDragging = true
                if (autoScrolling) {
                    autoScrolling = false
                    syncFab()
                    stopAutoScrollTick()
                }
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                scrubberDragging = false
                stripPendingPositioning = false
            }
        })
        binding.strip.addOnItemTouchListener(stripTouchListener)

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val mediaStore = mediaStoreEntries
            mediaStoreEntries = null
            if (mediaStore != null) {
                images = mediaStore
                currentIndex = if (images.isEmpty()) 0 else requestedPosition.coerceIn(0, images.size - 1)
                applyMode()
                return@launch
            }
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
        val metrics = resources.displayMetrics
        when (mode) {
            MODE_CONTINUOUS -> {
                binding.freePager.visibility = View.GONE
                binding.cardStack.visibility = View.GONE
                binding.strip.visibility = View.VISIBLE
                binding.autoScrollFab.visibility = View.VISIBLE
                stripWidth = binding.strip.width.takeIf { it > 0 } ?: metrics.widthPixels
                // the strip has no zoom: screen-size decoding is enough, halves memory
                stripDecodeWidth = metrics.widthPixels
                stripDecodeHeight = metrics.heightPixels
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
                    if (binding.strip.width > 0) stripWidth = binding.strip.width
                }
                syncFab()
                binding.pageScrubber.max = (images.size - 1).coerceAtLeast(0)
                binding.pageScrubber.progress = currentIndex.coerceIn(0, images.size - 1)
                binding.pageScrubber.visibility = if (overlayVisible) View.VISIBLE else View.GONE
                if (autoScrolling) startAutoScrollTick()
                startStripSizePrefetch()
            }

            MODE_CARD_STACK -> {
                autoScrolling = false
                sizePrefetchJob?.cancel()
                binding.strip.visibility = View.GONE
                binding.pageScrubber.visibility = View.GONE
                binding.freePager.visibility = View.GONE
                binding.autoScrollFab.visibility = View.GONE
                binding.cardStack.visibility = View.VISIBLE
                // the deck keeps three cards at once: screen-size decoding keeps that affordable
                decodeWidth = metrics.widthPixels
                decodeHeight = metrics.heightPixels
                binding.cardStack.setPages(images.size, currentIndex)
            }

            else -> {
                autoScrolling = false
                sizePrefetchJob?.cancel()
                binding.strip.visibility = View.GONE
                binding.pageScrubber.visibility = View.GONE
                binding.cardStack.visibility = View.GONE
                binding.autoScrollFab.visibility = View.GONE
                binding.freePager.visibility = View.VISIBLE
                decodeWidth = (metrics.widthPixels * DECODE_FACTOR).toInt()
                decodeHeight = (metrics.heightPixels * DECODE_FACTOR).toInt()
                binding.freePager.setPages(images.size, currentIndex)
            }
        }
        Settings.getInstance(applicationContext).edit().putInt(PREF_VIEWER_MODE, mode).apply()
        updateOverlay()
    }

    private fun updateOverlay() {
        val item = images.getOrNull(currentIndex)
        binding.imageName.text = item?.name ?: ""
        binding.imageCounter.text = if (images.isEmpty()) "" else getString(R.string.image_counter_format, currentIndex + 1, images.size)
        if (mode == MODE_CONTINUOUS && images.isNotEmpty() && !scrubberDragging) {
            binding.pageScrubber.max = (images.size - 1).coerceAtLeast(0)
            binding.pageScrubber.progress = currentIndex.coerceIn(0, images.size - 1)
        }
    }

    fun toggleOverlay() {
        overlayVisible = !overlayVisible
        binding.overlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
        if (mode == MODE_CONTINUOUS) {
            binding.pageScrubber.visibility = if (overlayVisible && images.isNotEmpty()) View.VISIBLE else View.GONE
        }
        if (overlayVisible) insetsController.show(WindowInsetsCompat.Type.systemBars()) else hideSystemBars()
    }

    /**
     * D-pad navigation for TV remotes: up/left goes to the next image, down/right
     * to the previous one, mirroring the free-pager swipe directions. Center and
     * the media keys keep their system meaning.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (images.isEmpty()) return super.onKeyDown(keyCode, event)
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> currentIndex + 1
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> currentIndex - 1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (target < 0 || target >= images.size) return true
        goTo(target)
        return true
    }

    /** Jumps to [position] in the active mode; currentIndex follows via the mode's own callbacks. */
    private fun goTo(position: Int) {
        val clamped = position.coerceIn(0, images.size - 1)
        if (mode == MODE_CONTINUOUS) {
            (binding.strip.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(clamped, 0)
        } else {
            paged().setPosition(clamped, animated = true)
        }
    }

    private fun hideSystemBars() {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** MIUI/HyperOS restores the status bar after a dialog or a notification; re-assert on refocus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !overlayVisible) hideSystemBars()
    }

    /**
     * Slideshow: play/pause entry point. First run opens the interval picker,
     * afterwards the button toggles playback directly; long press reopens the
     * picker. The ticker advances one image per interval in every mode.
     */
    private fun toggleSlideshow() {
        if (slideshowJob != null) {
            stopSlideshow()
            return
        }
        showSlideshowDialog()
    }

    private fun showSlideshowDialog() {
        val labels = SLIDESHOW_INTERVALS.map { getString(R.string.image_slideshow_seconds, it) }.toTypedArray()
        val checked = SLIDESHOW_INTERVALS.indexOf(slideshowInterval).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.image_slideshow_interval)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    slideshowInterval = SLIDESHOW_INTERVALS[which]
                    Settings.getInstance(applicationContext).edit().putInt(PREF_SLIDESHOW_INTERVAL, slideshowInterval).apply()
                    dialog.dismiss()
                    startSlideshow()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun startSlideshow() {
        if (images.isEmpty()) return
        stopSlideshow()
        slideshowJob = lifecycleScope.launch {
            while (true) {
                delay(slideshowInterval * 1000L)
                advanceSlideshow()
            }
        }
        syncSlideshowButton()
    }

    private fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
        syncSlideshowButton()
    }

    private fun advanceSlideshow() {
        if (images.isEmpty()) {
            stopSlideshow()
            return
        }
        val next = currentIndex + 1
        if (next >= images.size) {
            stopSlideshow()
            Toast.makeText(this, R.string.image_slideshow_end, Toast.LENGTH_SHORT).show()
            return
        }
        goTo(next)
    }

    private fun syncSlideshowButton() {
        binding.slideshowButton.setImageResource(
                if (slideshowJob != null) R.drawable.ic_slideshow_pause else R.drawable.ic_slideshow_play)
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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(pad, pad / 3, pad, pad / 3)
                minimumHeight = (48 * resources.displayMetrics.density).toInt()
                val out = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                if (android.os.Build.VERSION.SDK_INT >= 23 && out.resourceId != 0)
                    this.foreground = context.getDrawable(out.resourceId)
            }
            row.addView(MaterialRadioButton(this).apply {
                isChecked = mode == modeValue
                isClickable = false
            })
            row.addView(TextView(this).apply {
                text = getString(labelRes)
                textSize = 16f
                setPadding(pad / 2, 0, 0, 0)
            })
            row.setOnClickListener {
                dialog.dismiss()
                if (mode != modeValue) {
                    mode = modeValue
                    applyMode()
                }
            }
            return row
        }
        box.addView(modeRow(R.string.mode_page_free, MODE_PAGE_FREE))
        box.addView(modeRow(R.string.mode_continuous, MODE_CONTINUOUS))
        box.addView(modeRow(R.string.mode_card_stack, MODE_CARD_STACK))

        val autoRow = SwitchMaterial(this).apply {
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

    /** The pager serving the current paged mode. */
    private fun paged(): PagedReader = if (mode == MODE_CARD_STACK) binding.cardStack else binding.freePager

    /** A paged mode landed on [position]. */
    private fun onPaged(position: Int) {
        currentIndex = position
        updateOverlay()
    }

    /**
     * Fills a page of either paged mode. The tag holds the position the page was
     * asked for, so a page already recycled to another image ignores a load that is
     * still in flight.
     */
    private fun loadPage(view: ZoomableImageView, position: Int) {
        val item = images.getOrNull(position) ?: return
        view.setZoomableBitmap(null)
        view.tag = position
        lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(Dispatchers.IO) { getFullBitmap(item) }
            } catch (e: SmbImageLoader.SmbAuthRequiredException) {
                withContext(Dispatchers.Main) { promptSmbCredentials(e.host) }
            } catch (ignored: Exception) {
            }
            if (view.tag == position) view.setZoomableBitmap(bitmap)
            if (bitmap != null) withContext(Dispatchers.IO) { prefetchPage(position + 2) }
        }
    }

    /**
     * Decodes past the pages the pagers keep warm, so turning pages feels instant,
     * especially over slow network shares.
     */
    private suspend fun prefetchPage(position: Int) {
        val item = images.getOrNull(position) ?: return
        val key = "img_full_${item.uri}_${decodeWidth}x$decodeHeight"
        if (BitmapCache.getBitmapFromMemCache(key) != null) return
        try {
            ImageRepository.decodeSampledBitmap(applicationContext, item.uri, decodeWidth, decodeHeight)?.let {
                BitmapCache.addBitmapToMemCache(key, it)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Endless strip for the continuous mode: images stacked without gaps,
     * decoded at screen size
     */
    inner class StripAdapter(private val items: List<ImageInfo>) : RecyclerView.Adapter<StripAdapter.StripHolder>() {

        fun ratioAt(position: Int): Float = stripSizes[position] ?: DEFAULT_RATIO

        fun applyCellHeight(view: AppCompatImageView, ratio: Float) {
            val params = view.layoutParams as? RecyclerView.LayoutParams ?: return
            val height = (stripWidth * ratio).toInt().coerceAtLeast(1)
            if (params.height != height) {
                params.height = height
                view.layoutParams = params
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder {
            val view = AppCompatImageView(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            view.setBackgroundColor(android.graphics.Color.BLACK)
            return StripHolder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: StripHolder, position: Int) {
            applyCellHeight(holder.view, ratioAt(position))
            holder.bind(items[position], position)
        }

        inner class StripHolder(val view: AppCompatImageView) : RecyclerView.ViewHolder(view) {
            fun bind(item: ImageInfo, position: Int) {
                view.tag = item.uri
                view.setImageDrawable(null)
                lifecycleScope.launch {
                    var bitmap: Bitmap? = null
                    try {
                        val bytes = ensureStripBytes(position).await()
                        bitmap = if (bytes != null) withContext(Dispatchers.IO) { ImageRepository.decodeSampledBitmap(bytes, stripDecodeWidth, stripDecodeHeight) }
                        else withContext(Dispatchers.IO) { ImageRepository.decodeSampledBitmap(applicationContext, item.uri, stripDecodeWidth, stripDecodeHeight) }
                    } catch (e: SmbImageLoader.SmbAuthRequiredException) {
                        withContext(Dispatchers.Main) { promptSmbCredentials(e.host) }
                    } catch (ignored: Exception) {
                    }
                    if (view.tag != item.uri || bitmap == null) return@launch
                    view.setImageBitmap(bitmap)
                    // refine the predicted ratio with the real one; if rows above
                    // the current image changed height, keep the visual anchor stable
                    val actualRatio = bitmap.height.toFloat() / bitmap.width
                    val predicted = stripSizes.put(position, actualRatio) ?: DEFAULT_RATIO
                    withContext(Dispatchers.Main) {
                        if (abs(actualRatio - predicted) > 0.01f && position < currentIndex) {
                            val lm = binding.strip.layoutManager as? LinearLayoutManager
                            val anchor = lm?.findViewByPosition(currentIndex)
                            val oldTop = anchor?.top
                            applyCellHeight(view, actualRatio)
                            binding.strip.adapter?.notifyItemChanged(position)
                            if (oldTop != null) binding.strip.post {
                                val newTop = (binding.strip.layoutManager as? LinearLayoutManager)?.findViewByPosition(currentIndex)?.top
                                if (newTop != null) binding.strip.scrollBy(0, newTop - oldTop)
                            }
                        } else applyCellHeight(view, actualRatio)
                    }
                    withContext(Dispatchers.IO) {
                        prefetchStrip(position + 1)
                        prefetchStrip(position + 2)
                    }
                }
            }
        }

        private suspend fun prefetchStrip(position: Int) {
            val item = items.getOrNull(position) ?: return
            val key = "img_strip_${item.uri}_${stripDecodeWidth}x$stripDecodeHeight"
            if (BitmapCache.getBitmapFromMemCache(key) != null) return
            try {
                val bytes = ensureStripBytes(position).await() ?: return
                ImageRepository.decodeSampledBitmap(bytes, stripDecodeWidth, stripDecodeHeight)?.let {
                    BitmapCache.addBitmapToMemCache(key, it)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /** Bytes of an image, downloaded once and shared by prefetch and decode */
    private fun ensureStripBytes(position: Int): Deferred<ByteArray?> {
        stripBytes.get(position)?.let { cached ->
            return stripIoScope.async { cached }
        }
        return stripBytesJobs.getOrPut(position) {
            stripIoScope.async {
                stripBytes.get(position)?.let { return@async it }
                val uri = images.getOrNull(position)?.uri ?: return@async null
                val loaded = ImageRepository.loadBytes(applicationContext, uri)
                loaded?.let { stripBytes.put(position, it) }
                loaded
            }.apply {
                // drop the finished job: its Deferred would otherwise pin the
                // downloaded bytes in memory for the whole session
                invokeOnCompletion { stripBytesJobs.remove(position) }
            }
        }
    }

    /**
     * Prefetches every image ratio, nearest to the current image first, then
     * refreshes the strip cell heights once all sizes are known.
     */
    private fun startStripSizePrefetch() {
        sizePrefetchJob?.cancel()
        sizePrefetchJob = stripIoScope.launch {
            val count = images.size
            val ordered = ArrayList<Int>(count)
            for (d in 0 until count) {
                if (currentIndex + d < count) ordered.add(currentIndex + d)
                if (d != 0 && currentIndex - d >= 0) ordered.add(currentIndex - d)
            }
            for (position in ordered) {
                if (stripSizes.containsKey(position)) continue
                try {
                    val bytes = ensureStripBytes(position).await() ?: run {
                        stripSizes[position] = DEFAULT_RATIO
                        continue
                    }
                    val size = runCatching { ImageRepository.decodeBounds(bytes) }.getOrNull()
                    stripSizes[position] = if (size != null && size.first > 0) size.second.toFloat() / size.first else DEFAULT_RATIO
                } catch (ignored: Exception) {
                    stripSizes[position] = DEFAULT_RATIO
                }
            }
            withContext(Dispatchers.Main) {
                if (mode == MODE_CONTINUOUS) binding.strip.adapter?.notifyItemRangeChanged(0, count)
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
                    // let the strip retry with the new credentials
                    stripSizes.clear()
                    stripBytes.evictAll()
                    stripBytesJobs.clear()
                    refreshCurrentImage()
                    if (mode == MODE_CONTINUOUS) startStripSizePrefetch()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> smbPromptShowing = false }
                .setOnCancelListener { smbPromptShowing = false }
                .show()
    }

    private fun refreshCurrentImage() {
        if (mode == MODE_CONTINUOUS) {
            val adapter = binding.strip.adapter as? StripAdapter ?: return
            adapter.notifyItemRangeChanged(currentIndex, 2)
        } else {
            // rebuild the deck: its pages ask for their bitmap again
            paged().setPages(images.size, currentIndex)
        }
    }
}
