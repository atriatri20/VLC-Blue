/*
 * *************************************************************************
 *  ImagePage.kt
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
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * One page of the paged reading modes: a zoomable image inside a container the
 * pager transforms as a whole. The container optionally clips to rounded corners
 * and casts a shadow, which is what turns a page into a card.
 */
class ImagePage(context: Context) : FrameLayout(context) {

    val image = ZoomableImageView(context)
    var position = -1

    /** Last blur actually installed, so a drag does not rebuild the effect per frame. */
    private var blurApplied = -1f

    /**
     * 0 keeps the page edge to edge; a positive value makes it a rounded card. Stepped to
     * a few pixels because a new outline rebuilds the clip, which is waste per frame.
     */
    var cardRadius = 0f
        set(value) {
            val step = (value / 6f).toInt() * 6f
            if (step != field) {
                field = step
                invalidateOutline()
            }
        }

    /** 0 keeps the page invisible against the black backdrop; a positive value tints
     * the card face so a card reads as a card even where its photo has no pixels.
     * Stepped, because repainting the background every frame of a drag is waste. */
    var cardFace = 0f
        set(value) {
            val step = (value * 5f).toInt() / 5f
            if (step != field) {
                field = step
                val tone = (22 * step).toInt().coerceIn(0, 255)
                setBackgroundColor(Color.rgb(tone, tone, tone))
            }
        }

    /**
     * Where the page clips. Each page owns its own rect so pages never share one; pagers
     * set it to the photo's own rect so a card holds the shape of the image instead of
     * dragging the black around it along as it moves.
     */
    val clipRect = RectF()
    var clipToPhoto = false
        set(value) {
            if (value != field) {
                field = value
                invalidateOutline()
            }
        }

    /**
     * True while the photo is zoomed past its fit: the card shape is dropped and the
     * image paints natively across the whole page, unclipped; the clip returns when
     * the zoom comes back to fit.
     */
    var clipReleased = false
        private set

    fun setClipReleased(released: Boolean) {
        if (released == clipReleased) return
        clipReleased = released
        invalidateOutline()
    }

    /** The rect to clip to: [photo] grown by [edge], or nothing when there is no photo. */
    fun setClipTo(photo: RectF?, edge: Float) {
        if (photo == null) {
            if (clipToPhoto) {
                clipRect.setEmpty()
                clipToPhoto = false
            }
            return
        }
        clipRect.set(photo)
        clipRect.inset(-edge, -edge)
        if (!clipToPhoto) clipToPhoto = true
        invalidateOutline()
    }

    init {
        setBackgroundColor(Color.BLACK)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val page = view as ImagePage
                val clipped = page.clipToPhoto && !page.clipRect.isEmpty && !page.clipReleased
                val left = if (clipped) floor(page.clipRect.left).toInt() else 0
                val top = if (clipped) floor(page.clipRect.top).toInt() else 0
                val right = if (clipped) ceil(page.clipRect.right).toInt() else view.width
                val bottom = if (clipped) ceil(page.clipRect.bottom).toInt() else view.height
                if (page.cardRadius <= 0f) outline.setRect(left, top, right, bottom)
                else outline.setRoundRect(left, top, right, bottom, page.cardRadius)
            }
        }
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Drops the bitmap and the zoom: the page is going back to the pool. */
    fun detachContent() {
        position = -1
        image.tag = null
        cardFace = 0f
        clipRect.setEmpty()
        clipToPhoto = false
        clipReleased = false
        image.setZoomableBitmap(null)
    }

    /**
     * Depth of field: 0 keeps the page sharp. Quantised to whole pixels because
     * installing a render effect rebuilds the layer, which is far too expensive to
     * do on every frame of a drag. Blur itself needs API 31; on older releases the
     * modes keep their slide/scale/fade and simply never blur.
     */
    fun blurTo(radius: Float) {
        val step = if (radius < 0.8f) 0f else radius.toInt().toFloat()
        if (step == blurApplied) return
        blurApplied = step
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applyBlur(step)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlur(radius: Float) {
        setRenderEffect(if (radius == 0f) null
        else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }
}

/**
 * What the paged reading modes offer the viewer: a sequence of images the reader
 * can be placed in, by hand or programmatically.
 */
interface PagedReader {
    fun setPages(entries: Int, index: Int)
    fun setPosition(index: Int, animated: Boolean)
}

/**
 * Pool of pages shared by a pager. Released pages stay attached and are only made
 * invisible, so acquiring one again costs no measure pass; their bitmap is dropped
 * and lives on in [org.videolan.tools.BitmapCache].
 */
internal class PagePool(
        private val parent: ViewGroup,
        private val onLoad: (ZoomableImageView, Int) -> Unit,
        private val onTap: () -> Unit
) {

    private val free = ArrayList<ImagePage>(MAX_FREE_PAGES)

    /** Returns the page already showing [position], else a pooled or fresh one. */
    fun acquire(position: Int): ImagePage {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ImagePage && child.position == position) {
                child.visibility = View.VISIBLE
                return child
            }
        }
        val page = if (free.isEmpty()) newPage() else free.removeAt(free.size - 1)
        page.position = position
        page.visibility = View.VISIBLE
        onLoad(page.image, position)
        return page
    }

    fun release(page: ImagePage) {
        page.visibility = View.GONE
        page.detachContent()
        page.blurTo(0f)
        if (free.size < MAX_FREE_PAGES) free.add(page)
    }

    private fun newPage(): ImagePage {
        val page = ImagePage(parent.context)
        page.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        page.image.onSingleTap = onTap
        // a zoomed photo paints across the whole page: the card clip steps aside
        // for as long as the image stays past its fit and returns with it
        page.image.onZoomChanged = { zoomed -> page.setClipReleased(zoomed) }
        parent.addView(page)
        return page
    }

    private companion object {
        /**
         * Cap on pooled-but-idle pages. A warm pager holds 3 pages (previous,
         * current, next) and the two pagers peak at 6 live pages; 5 leaves
         * headroom without keeping a page for every flick of the finger.
         */
        const val MAX_FREE_PAGES = 5
    }
}

/**
 * Drag bookkeeping shared by the paged modes: how far the finger went along the
 * axis it picked, and how fast it was moving when it left.
 */
internal class DragTracker(context: Context) {

    private val configuration = ViewConfiguration.get(context)
    private val density = context.resources.displayMetrics.density

    /** Travel after which a touch turns into a page drag. */
    val slop = configuration.scaledPagingTouchSlop.toFloat()

    /** Finger speed that pages even when the drag itself stays short, px/s. */
    val flingVelocity = 620f * density

    var downX = 0f
        private set
    var downY = 0f
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    private var velocity: VelocityTracker? = null

    fun start(e: MotionEvent) {
        downX = e.x
        downY = e.y
        x = e.x
        y = e.y
        velocity?.recycle()
        velocity = VelocityTracker.obtain().apply { addMovement(e) }
    }

    /** False once a second finger joins: the pager hands the gesture to the child. */
    fun move(e: MotionEvent): Boolean {
        if (e.pointerCount > 1) return false
        velocity?.addMovement(e)
        x = e.x
        y = e.y
        return true
    }

    fun dx() = x - downX
    fun dy() = y - downY
    fun distance() = hypot(dx(), dy())

    /** Feeds the finger lift to the tracker so [velocityAlong] sees it. */
    fun addSample(e: MotionEvent) {
        velocity?.addMovement(e)
    }

    fun velocityAlong(axisVertical: Boolean): Float {
        val tracker = velocity ?: return 0f
        tracker.computeCurrentVelocity(1000)
        return if (axisVertical) tracker.yVelocity else tracker.xVelocity
    }

    fun recycle() {
        velocity?.recycle()
        velocity = null
    }
}
