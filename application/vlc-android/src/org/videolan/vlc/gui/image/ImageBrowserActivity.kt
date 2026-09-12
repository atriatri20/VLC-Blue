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
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.BitmapCache
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ImageBrowserActivityBinding
import org.videolan.vlc.gui.BaseActivity
import org.videolan.vlc.util.Permissions

/**
 * Grid browser for the images stored on the device.
 * Clicking an image opens [ImageViewerActivity] which pages through them vertically.
 */
class ImageBrowserActivity : BaseActivity() {

    private lateinit var binding: ImageBrowserActivityBinding
    private val images = ArrayList<ImageInfo>()
    private lateinit var adapter: ImageGridAdapter
    private var thumbnailSize = 320
    private var loading = false

    override fun getSnackAnchorView(overAudioPlayer: Boolean): View = binding.root
    override val displayTitle = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.image_browser_activity)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.main_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_up)
        title = getString(R.string.image_browser)

        val metrics = resources.displayMetrics
        thumbnailSize = metrics.widthPixels / 3
        adapter = ImageGridAdapter()
        binding.grid.layoutManager = GridLayoutManager(this, 3)
        binding.grid.adapter = adapter

        if (hasImagesPermission()) loadImages() else requestImagesPermission()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) finish()
        return super.onOptionsItemSelected(item)
    }

    private fun hasImagesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || Permissions.hasAllAccess(this)
        } else Permissions.canReadStorage(this, true)
    }

    private fun requestImagesPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        ActivityCompat.requestPermissions(this, arrayOf(permission), Permissions.FINE_STORAGE_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Permissions.FINE_STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadImages()
            else binding.empty.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasImagesPermission() && images.isEmpty() && !loading && binding.empty.visibility != View.VISIBLE) loadImages()
    }

    private fun loadImages() {
        if (loading) return
        loading = true
        binding.empty.visibility = View.GONE
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    ImageRepository.queryImages(applicationContext)
                } catch (ignored: Exception) {
                    emptyList<ImageInfo>()
                }
            }
            images.clear()
            images.addAll(list)
            adapter.notifyDataSetChanged()
            binding.empty.visibility = if (images.isEmpty()) View.VISIBLE else View.GONE
            loading = false
        }
    }

    private fun getThumbnail(info: ImageInfo): Bitmap? {
        val key = "img_thumb_${info.id}_$thumbnailSize"
        BitmapCache.getBitmapFromMemCache(key)?.let { return it }
        val bitmap = ImageRepository.decodeSampledBitmap(applicationContext, info, thumbnailSize, thumbnailSize) ?: return null
        BitmapCache.addBitmapToMemCache(key, bitmap)
        return bitmap
    }

    fun onImageClick(position: Int) {
        startActivity(Intent(this, ImageViewerActivity::class.java).putExtra(ImageViewerActivity.EXTRA_POSITION, position))
    }

    inner class ImageGridAdapter : RecyclerView.Adapter<ImageGridAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image_grid, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = images.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(images[position])
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val image: ImageView = itemView.findViewById(R.id.image_thumbnail)

            fun bind(item: ImageInfo) {
                image.tag = item.id
                image.setImageBitmap(null)
                itemView.setOnClickListener { onImageClick(bindingAdapterPosition) }
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) { getThumbnail(item) }
                    if (image.tag == item.id) image.setImageBitmap(bitmap)
                }
            }
        }
    }
}
