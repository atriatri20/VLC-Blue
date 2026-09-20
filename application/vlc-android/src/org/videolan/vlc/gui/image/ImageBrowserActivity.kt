/*
 * *************************************************************************
 *  ImageBrowserActivity.kt
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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import org.videolan.resources.util.HeadersIndex
import org.videolan.vlc.gui.view.FastScroller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.BROWSER_CARD_WIDTH
import org.videolan.tools.BitmapCache
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ImageBrowserActivityBinding
import org.videolan.vlc.gui.BaseActivity
import org.videolan.vlc.util.Permissions
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Home of the local image browser: a 照片 tab (every device image, grouped in
 * date sections) and a 相册 tab (one entry per MediaStore bucket). Supports
 * sorting, long-press multi selection with share/delete/details, and opens
 * [ImageViewerActivity] on a card tap. Card sizing follows the browser large
 * card width (BROWSER_CARD_WIDTH).
 */
class ImageBrowserActivity : BaseActivity(), ImageGridAdapter.Listener {

    companion object {
        private const val PREF_SORT = "image_sort"
        private const val REQUEST_DELETE = 4001

        /** Set by the album page after deletions so this home reloads on resume */
        @JvmStatic var dataDirty = false
    }

    private lateinit var binding: ImageBrowserActivityBinding
    private lateinit var photoAdapter: ImageGridAdapter
    private lateinit var albumAdapter: ImageAlbumAdapter
    private var allImages = ArrayList<ImageInfo>()
    private var photos = ArrayList<ImageInfo>()
    private var albums = ArrayList<ImageAlbum>()
    private var sort = ImageSort.DATE_DESC
    private var currentTab = 0 // 0 = 照片, 1 = 相册
    private var loading = false
    private var thumbWidth = 320
    private var thumbHeight = 480
    private var cardWidthPx = 0
    private var columns = 0
    private var gridDecoration: ItemDecoration? = null
    private var actionMode: ActionMode? = null
    private var pendingDelete: List<ImageInfo> = emptyList()
    private val headerProvider = ImageHeaderProvider()
    private var query: String? = null

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) onImagesDeleted(pendingDelete)
    }

    override fun getSnackAnchorView(overAudioPlayer: Boolean): View = binding.root
    override val displayTitle = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.image_browser_activity)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.main_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_up)
        supportActionBar?.setTitle(R.string.image_browser)

        sort = ImageSort.fromValue(Settings.getInstance(this).getInt(PREF_SORT, ImageSort.DATE_DESC.value))

        photoAdapter = ImageGridAdapter(this, ::getThumbnail, this)
        albumAdapter = ImageAlbumAdapter(this, ::getThumbnail, object : ImageAlbumAdapter.Listener {
            override fun onAlbumClick(album: ImageAlbum) {
                startActivity(Intent(this@ImageBrowserActivity, ImageAlbumActivity::class.java)
                        .putExtra(ImageAlbumActivity.EXTRA_BUCKET_ID, album.bucketId)
                        .putExtra(ImageAlbumActivity.EXTRA_TITLE, album.name))
            }
        })

        setupTabs()
        applyGridDisplay(resources.displayMetrics)
        showTab(0)
        setupFastScroller()

        if (hasImagesPermission()) loadImages() else requestImagesPermission()
    }

    private fun setupTabs() {
        val tabs = findViewById<TabLayout>(R.id.sliding_tabs) ?: return
        tabs.visibility = View.VISIBLE
        tabs.addTab(tabs.newTab().setText(R.string.photos_tab))
        tabs.addTab(tabs.newTab().setText(R.string.albums_tab))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showTab(position: Int) {
        if (position == currentTab && binding.grid.adapter != null) return
        currentTab = position
        actionMode?.finish()
        applyGridDisplay(resources.displayMetrics)
        when (position) {
            0 -> {
                binding.grid.adapter = photoAdapter
                binding.empty.setText(R.string.image_browser_empty)
            }
            else -> {
                binding.grid.adapter = albumAdapter
                binding.empty.setText(R.string.image_albums_empty)
            }
        }
        binding.empty.visibility = if (currentList().isEmpty() && !loading) View.VISIBLE else View.GONE
        refreshFastScroller()
        binding.grid.scrollToPosition(0)
    }

    private fun currentList(): List<*> = if (currentTab == 0) photos else albums

    private fun setupFastScroller() {
        binding.fastScroller.attachToCoordinator(findViewById<AppBarLayout>(R.id.appbar), binding.coordinator, null)
        refreshFastScroller()
    }

    private fun refreshFastScroller() {
        headerProvider.submit(if (currentTab == 0) ImageSections.headerIndex(photoAdapter.currentList) else HeadersIndex())
        binding.fastScroller.setRecyclerView(binding.grid, headerProvider)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.image_sort_date_desc, R.id.image_sort_date_asc, R.id.image_sort_name_asc, R.id.image_sort_name_desc -> {
                sort = when (item.itemId) {
                    R.id.image_sort_date_asc -> ImageSort.DATE_ASC
                    R.id.image_sort_name_asc -> ImageSort.NAME_ASC
                    R.id.image_sort_name_desc -> ImageSort.NAME_DESC
                    else -> ImageSort.DATE_DESC
                }
                Settings.getInstance(this).edit().putInt(PREF_SORT, sort.value).apply()
                rebuild()
                invalidateOptionsMenu()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.image_browser_menu, menu)
        updateSortMenu(menu)
        setupSearch(menu)
        return true
    }

    private fun setupSearch(menu: Menu) {
        val searchItem = menu.findItem(R.id.image_menu_search) ?: return
        val searchView = searchItem.actionView as? SearchView ?: return
        searchView.queryHint = getString(R.string.searchable_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                val value = newText?.trim()?.takeIf { it.isNotEmpty() }
                if (value != query) {
                    query = value
                    rebuild()
                }
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (query != null) {
                    query = null
                    rebuild()
                }
                return true
            }
        })
    }

    private fun updateSortMenu(menu: Menu? = null) {
        val target = menu ?: currentMenu ?: return
        val checkedId = when (sort) {
            ImageSort.DATE_DESC -> R.id.image_sort_date_desc
            ImageSort.DATE_ASC -> R.id.image_sort_date_asc
            ImageSort.NAME_ASC -> R.id.image_sort_name_asc
            ImageSort.NAME_DESC -> R.id.image_sort_name_desc
        }
        for (id in intArrayOf(R.id.image_sort_date_desc, R.id.image_sort_date_asc, R.id.image_sort_name_asc, R.id.image_sort_name_desc)) {
            target.findItem(id)?.isChecked = id == checkedId
        }
    }

    private var currentMenu: Menu? = null

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        currentMenu = menu
        if (menu != null) updateSortMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onResume() {
        super.onResume()
        applyGridDisplay(resources.displayMetrics)
        if (dataDirty) {
            dataDirty = false
            if (hasImagesPermission()) loadImages()
        } else if (hasImagesPermission() && allImages.isEmpty() && !loading) {
            loadImages()
        }
    }

    private fun hasImagesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || Permissions.hasAllAccess(this)
        } else Permissions.canReadStorage(this, true)
    }

    private fun requestImagesPermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, Permissions.FINE_STORAGE_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.FINE_STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadImages()
            else binding.empty.visibility = View.VISIBLE
        }
    }

    private fun loadImages() {
        if (loading) return
        loading = true
        binding.empty.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    ImageRepository.queryImages(applicationContext)
                } catch (ignored: Exception) {
                    emptyList<ImageInfo>()
                }
            }
            allImages = ArrayList(list)
            rebuild()
            val ids = allImages.mapTo(HashSet()) { it.id }
            lifecycleScope.launch(Dispatchers.IO) { ImageThumbStore.cleanup(applicationContext, ids) }
            binding.progress.visibility = View.GONE
            binding.empty.visibility = if (currentList().isEmpty()) View.VISIBLE else View.GONE
            loading = false
        }
    }

    /** Rebuilds the sorted photo list and the album groups from [allImages] */
    private fun rebuild() {
        val q = query
        val filteredPhotos = if (q == null) allImages else allImages.filter { it.name.contains(q, true) }
        photos = ArrayList(ImageRepository.sortImages(filteredPhotos, sort))
        val albumGroups = ImageRepository.groupAlbums(allImages)
        albums = ArrayList(if (q == null) albumGroups else albumGroups.filter { it.name.contains(q, true) })
        // the sort menu applies to the album list as well (date orders keep newest-first)
        when (sort) {
            ImageSort.NAME_ASC -> albums.sortBy { it.name.lowercase(Locale.ENGLISH) }
            ImageSort.NAME_DESC -> albums.sortByDescending { it.name.lowercase(Locale.ENGLISH) }
            else -> {}
        }
        photoAdapter.setRows(ImageSections.buildRows(photos, sort.isDateBased, this), sort.isDateBased)
        albumAdapter.setAlbums(albums)
        binding.empty.visibility = if (currentList().isEmpty() && !loading) View.VISIBLE else View.GONE
        if (currentTab == 0) refreshFastScroller()
    }

    private fun getThumbnail(info: ImageInfo): Bitmap? {
        return ImageThumbStore.load(this, info, thumbWidth, thumbHeight)
    }

    /**
     * Mirrors BaseBrowserFragment.manageDisplay() large-card math
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            org.videolan.tools.BitmapCache.clear()
        }
    }
: the card is
     * exactly the configured BROWSER_CARD_WIDTH (minus its own 10dp margins,
     * like the browser cards), leftover space becomes even spacing. Date
     * section headers span the full width.
     */
    private fun applyGridDisplay(metrics: DisplayMetrics) {
        val cardWidth = Settings.getInstance(this).getInt(BROWSER_CARD_WIDTH, 100).coerceIn(70, 500)
        val cardPx = (cardWidth * metrics.density).toInt()
        val available = if (binding.grid.width > 0) binding.grid.width else metrics.widthPixels
        val nbColumns = max(1, available / (cardPx + 2 * 10.dp))
        if (nbColumns == columns && cardPx == cardWidthPx) return
        columns = nbColumns
        cardWidthPx = cardPx
        // Decode thumbnails at the exact card size (2:3)
        thumbWidth = cardPx
        thumbHeight = cardPx * 3 / 2
        val layoutManager = GridLayoutManager(this, nbColumns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val adapter = binding.grid.adapter
                    return if (adapter is ImageGridAdapter && adapter.isHeader(position)) nbColumns else 1
                }
            }
        }
        binding.grid.layoutManager = layoutManager
        gridDecoration?.let { binding.grid.removeItemDecoration(it) }
        gridDecoration = object : ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                super.getItemOffsets(outRect, view, parent, state)
                val cols = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                val gap = if (cols > 0) ((parent.width - cols * cardWidthPx) / (cols * 2)).coerceAtLeast(12.dp) else 12.dp
                outRect.left = gap
                outRect.right = gap
                outRect.top = 14.dp
                outRect.bottom = 10.dp
            }
        }.also { binding.grid.addItemDecoration(it) }
        photoAdapter.notifyDataSetChanged()
        albumAdapter.notifyDataSetChanged()
    }

    // region ImageGridAdapter.Listener

    override fun onImageClick(image: ImageInfo) {
        val index = photos.indexOfFirst { it.id == image.id }
        if (index < 0) return
        startActivity(ImageViewerActivity.mediaStoreIntent(this, photos, index))
    }

    override fun onSelectionChanged(count: Int) {
        actionMode?.title = getString(R.string.selection_count, count)
        actionMode?.invalidate()
        when {
            count == 0 -> actionMode?.finish()
            actionMode == null -> startSupportActionMode(selectionCallback)
        }
    }

    // endregion

    // region ActionMode (multi selection)

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            mode.menuInflater.inflate(R.menu.image_selection_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(R.id.image_action_details)?.isVisible = photoAdapter.selectionCount() == 1
            mode.title = getString(R.string.selection_count, photoAdapter.selectionCount())
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val selection = photoAdapter.selectedImages()
            when (item.itemId) {
                R.id.image_select_all -> photoAdapter.selectAll(photos)
                R.id.image_action_share -> if (selection.isNotEmpty()) shareImages(selection)
                R.id.image_action_delete -> if (selection.isNotEmpty()) deleteImages(selection)
                R.id.image_action_details -> selection.firstOrNull()?.let { showDetails(it) }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            photoAdapter.clearSelection()
        }
    }

    private fun shareImages(images: List<ImageInfo>) {
        if (images.size == 1) {
            val send = Intent(Intent.ACTION_SEND)
            send.type = images[0].mimeType
            send.putExtra(Intent.EXTRA_STREAM, images[0].uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.image_share)))
        } else {
            val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            send.type = "image/*"
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images.map { it.uri }))
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.image_share)))
        }
    }

    private fun deleteImages(images: List<ImageInfo>) {
        if (images.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = images
            val request = MediaStore.createDeleteRequest(contentResolver, ArrayList(images.map { it.uri }))
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete)
                    .setMessage(getString(R.string.image_delete_confirm, images.size))
                    .setPositiveButton(R.string.delete) { _, _ -> directDelete(images) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
        }
    }

    /** Fallback for old Android versions without the system delete request */
    private fun directDelete(images: List<ImageInfo>) {
        val deleted = ArrayList<ImageInfo>()
        images.forEach { image ->
            val ok = runCatching { contentResolver.delete(image.uri, null, null) }.getOrDefault(0) > 0
            if (ok) deleted.add(image)
        }
        if (deleted.isEmpty()) {
            Toast.makeText(this, R.string.image_delete_failed, Toast.LENGTH_SHORT).show()
        } else {
            if (deleted.size < images.size) Toast.makeText(this, R.string.image_delete_failed, Toast.LENGTH_SHORT).show()
            onImagesDeleted(deleted)
        }
    }

    private fun onImagesDeleted(deleted: List<ImageInfo>) {
        if (deleted.isEmpty()) return
        val removed = HashSet(deleted.map { it.id })
        allImages.removeAll { it.id in removed }
        rebuild()
        actionMode?.finish()
        dataDirty = true
        Toast.makeText(this, getString(R.string.image_deleted_count, deleted.size), Toast.LENGTH_SHORT).show()
    }

    private fun showDetails(image: ImageInfo) {
        val resolution = if (image.width > 0 && image.height > 0) "${image.width} × ${image.height}" else getString(R.string.image_unknown)
        val size = Formatter.formatShortFileSize(this, image.size)
        val date = DateFormat.getDateTimeInstance().format(Date(image.dateAdded * 1000L))
        MaterialAlertDialogBuilder(this)
                .setTitle(image.name)
                .setMessage(getString(R.string.image_details_format,
                        image.bucket ?: getString(R.string.image_unknown),
                        resolution,
                        size,
                        date,
                        image.path ?: image.uri.toString()))
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    // endregion
}
