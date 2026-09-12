/*
 * *************************************************************************
 *  ZoomableImageView.kt
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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * ImageView supporting pinch zoom, double tap zoom and panning while zoomed.
 *
 * The displayed matrix is B·C(s)·T where B fits the bitmap inside the view,
 * C(s) scales around the view center and T is a panning translation.
 *
 * It cooperates with a vertical ViewPager2 parent: while the image sits at scale 1.0,
 * vertical drags are left to the parent so the pager can switch images. Once the image
 * is zoomed in, the view claims the touch stream and the pager stops intercepting.
 */
class ZoomableImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : AppCompatImageView(context, attrs) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }

    var onSingleTap: (() -> Unit)? = null

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val tempRect = RectF()

    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var scale = MIN_SCALE
    private var offsetX = 0f
    private var offsetY = 0f
    private var animator: ValueAnimator? = null

    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        scaleType = ScaleType.MATRIX
    }

    /**
     * Display a bitmap, fitting it inside the view at scale 1.0
     */
    fun setZoomableBitmap(bitmap: Bitmap?) {
        cancelAnimation()
        super.setImageBitmap(bitmap)
        scale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            bitmapWidth = bitmap.width
            bitmapHeight = bitmap.height
        } else {
            bitmapWidth = 0
            bitmapHeight = 0
        }
        computeBaseMatrix()
        applyMatrix()
    }

    fun resetZoom() {
        cancelAnimation()
        scale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBaseMatrix()
        applyMatrix()
    }

    private fun computeBaseMatrix() {
        if (width == 0 || height == 0 || bitmapWidth == 0) return
        baseMatrix.setTranslate(0f, 0f)
        val fit = min(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
        baseMatrix.postScale(fit, fit)
        baseMatrix.postTranslate((width - bitmapWidth * fit) / 2f, (height - bitmapHeight * fit) / 2f)
    }

    private fun currentDrawMatrix(): Matrix {
        drawMatrix.set(baseMatrix)
        drawMatrix.postScale(scale, scale, width / 2f, height / 2f)
        drawMatrix.postTranslate(offsetX, offsetY)
        return drawMatrix
    }

    private fun applyMatrix() {
        setImageMatrix(currentDrawMatrix())
        invalidate()
    }

    private fun isZoomed() = scale > MIN_SCALE + 0.01f

    /**
     * Keep the displayed image within the view bounds while zoomed
     */
    private fun clampOffsets() {
        if (bitmapWidth == 0 || width == 0 || height == 0) return
        val scaled = Matrix()
        scaled.set(baseMatrix)
        scaled.postScale(scale, scale, width / 2f, height / 2f)
        tempRect.set(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        scaled.mapRect(tempRect)
        offsetX = if (tempRect.width() <= width) (width - tempRect.width()) / 2f - tempRect.left
        else offsetX.coerceIn(width - tempRect.right, -tempRect.left)
        offsetY = if (tempRect.height() <= height) (height - tempRect.height()) / 2f - tempRect.top
        else offsetY.coerceIn(height - tempRect.bottom, -tempRect.top)
    }

    /**
     * Zoom to [targetScale] keeping the image point under ([focusX], [focusY]) stationary
     */
    private fun applyZoom(targetScale: Float, focusX: Float, focusY: Float) {
        if (bitmapWidth == 0 || width == 0 || height == 0) return
        val drawInverse = inverted(currentDrawMatrix()) ?: return
        val bitmapPoint = floatArrayOf(focusX, focusY)
        drawInverse.mapPoints(bitmapPoint)
        scale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val centerInverse = Matrix()
        centerInverse.postScale(1f / scale, 1f / scale, width / 2f, height / 2f)
        val baseInverse = inverted(baseMatrix) ?: return
        centerInverse.preConcat(baseInverse)
        val focusPoint = floatArrayOf(focusX, focusY)
        centerInverse.mapPoints(focusPoint)
        offsetX = focusPoint[0] - bitmapPoint[0]
        offsetY = focusPoint[1] - bitmapPoint[1]
        clampOffsets()
        applyMatrix()
    }

    private fun inverted(matrix: Matrix): Matrix? {
        val result = Matrix()
        return if (matrix.invert(result)) result else null
    }

    private fun zoomTo(targetScale: Float, focusX: Float, focusY: Float, animate: Boolean) {
        if (!animate) {
            applyZoom(targetScale, focusX, focusY)
            return
        }
        cancelAnimation()
        val startScale = scale
        val startOffsetX = offsetX
        val startOffsetY = offsetY
        applyZoom(targetScale, focusX, focusY)
        val endScale = scale
        val endOffsetX = offsetX
        val endOffsetY = offsetY
        if (startScale == endScale && startOffsetX == endOffsetX && startOffsetY == endOffsetY) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                scale = startScale + (endScale - startScale) * fraction
                offsetX = startOffsetX + (endOffsetX - startOffsetX) * fraction
                offsetY = startOffsetY + (endOffsetY - startOffsetY) * fraction
                applyMatrix()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (!isZoomed()) parent?.requestDisallowInterceptTouchEvent(false)
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (bitmapWidth == 0) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            cancelAnimation()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (bitmapWidth == 0) return false
            applyZoom(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (bitmapWidth == 0) return true
            parent?.requestDisallowInterceptTouchEvent(true)
            if (isZoomed()) zoomTo(MIN_SCALE, width / 2f, height / 2f, true)
            else zoomTo(DOUBLE_TAP_SCALE, e.x, e.y, true)
            return true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmapWidth == 0) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                cancelAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && isZoomed()) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    clampOffsets()
                    applyMatrix()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isZoomed()) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
