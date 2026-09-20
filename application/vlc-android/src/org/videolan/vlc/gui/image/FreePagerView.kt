/*
 * *************************************************************************
 *  FreePagerView.kt
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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * Free paging: one image sequence read through both axes. Up or left moves to the
 * next image, down or right back to the previous one, so a swipe never has to
 * commit to a direction first.
 *
 * The axis is picked once, when the drag starts, and the rest of the gesture is
 * locked to it: pulling up moves the pages up only, so a sloppy diagonal drag never
 * drags black background in from the sides. The whole transition is a single signed
 * offset along that axis: the leaving page follows the finger 1:1 while it shrinks a
 * little and fades, the incoming one rides in from the edge that just opened and
 * settles to full size and opacity.
 *
 * Pages zoomed with [ZoomableImageView] keep their own pan gestures: a drag is only
 * claimed when it starts on an unzoomed page, and a second finger hands the gesture
 * back to the page for pinching.
 */
class FreePagerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
        ViewGroup(context, attrs), PagedReader {

    companion object {
        private const val ENTRY_SCALE = 1.02f
        private const val LEAVING_SCALE = 0.93f
        private const val ENTRY_ALPHA = 0.6f
        private const val LEAVING_ALPHA = 0.35f
        /** Fraction of the short edge a slow drag has to cover to turn the page. */
        private const val COMMIT_PROGRESS = 0.3f
        /** A drag towards a missing neighbour only stretches this much of itself. */
        private const val RESIST = 0.28f
    }

    var onImageLoad: ((ZoomableImageView, Int) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onPositionChanged: ((Int) -> Unit)? = null

    var position = 0
        private set

    private val pool = PagePool(this,
            onLoad = { image, position -> onImageLoad?.invoke(image, position) },
            onTap = { onSingleTap?.invoke() })
    private val drag = DragTracker(context)

    private var count = 0
    private var animator: ValueAnimator? = null
    private var current: ImagePage? = null
    private var entering: ImagePage? = null
    private var axisVertical = true
    private var forward = false
    private var main = 0f
    private var dragging = false
    private var multiPointer = false

    /** Starts over on [entries] images showing [index]. */
    override fun setPages(entries: Int, index: Int) {
        count = entries.coerceAtLeast(0)
        rebuild(if (count == 0) 0 else index.coerceIn(0, count - 1))
    }

    /** Jumps to [index]; the animated form runs the same swipe a finger would. */
    override fun setPosition(index: Int, animated: Boolean) {
        if (count == 0 || index !in 0 until count || index == position) return
        if (!animated) {
            rebuild(index)
            onPositionChanged?.invoke(position)
            return
        }
        finishAnimator()
        forward = index > position
        entering = pool.acquire(index)
        val span = axisSpan()
        animateTo(if (forward) -span else span, index)
    }

    private fun rebuild(index: Int) {
        finishAnimator()
        current = null
        entering = null
        dragging = false
        position = index
        for (i in 0 until childCount) (getChildAt(i) as? ImagePage)?.let { pool.release(it) }
        if (count > 0) current = pool.acquire(index)
        warm()
        applyTransition(0f)
    }

    /** Keeps the neighbours of the current page attached and decoded, parked aside. */
    private fun warm() {
        val wanted = setOf(position - 1, position, position + 1)
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? ImagePage ?: continue
            if (page.position >= 0 && page.position !in wanted) pool.release(page)
        }
        if (position > 0) pool.acquire(position - 1)
        if (position < count - 1) pool.acquire(position + 1)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val xSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val ySpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) child.measure(xSpec, ySpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, r - l, b - t)
        applyTransition(main)
    }

    private fun axisSpan() = (if (axisVertical) height else width).toFloat()

    /**
     * Travel that carries a turn through its visual arc. Measured on the short edge
     * so a slow drag on a tall screen still commits at a comfortable distance.
     */
    private fun travel() = minOf(width, height).toFloat().coerceAtLeast(1f)

    private fun currentZoomed() = current?.image?.isZoomed() == true

    /**
     * Places every live page for a transition of [main] along the paging axis.
     * Nothing moves across that axis: the axis is chosen when the drag starts, and
     * the gesture stays on it.
     */
    private fun applyTransition(main: Float) {
        this.main = main
        val span = axisSpan()
        if (span <= 0f) return
        val progress = (abs(main) / travel()).coerceIn(0f, 1f)
        val entry = if (forward) span else -span
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? ImagePage ?: continue
            when {
                page === current -> page.place(main, VISIBLE,
                        1f - (1f - LEAVING_SCALE) * progress,
                        1f - LEAVING_ALPHA * progress)

                page === entering -> page.place(main + entry, VISIBLE,
                        ENTRY_SCALE - (ENTRY_SCALE - 1f) * progress,
                        ENTRY_ALPHA + (1f - ENTRY_ALPHA) * progress)

                page.position >= 0 -> page.place(if (page.position > position) span else -span,
                        INVISIBLE, 1f, 1f)
            }
        }
    }

    private fun ImagePage.place(along: Float, visibility: Int, scale: Float, alpha: Float) {
        if (this.visibility != visibility) this.visibility = visibility
        translationX = if (axisVertical) 0f else along
        translationY = if (axisVertical) along else 0f
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
        cardRadius = 0f
        cardFace = 0f
    }

    // region gestures

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (count < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishAnimator()
                drag.start(event)
                dragging = false
                multiPointer = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger means pinch, which belongs to the page
                multiPointer = true
                if (dragging) bounceBack()
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiPointer || !drag.move(event)) return false
                if (dragging) return true
                if (drag.distance() < drag.slop || currentZoomed()) return false
                beginDrag()
                updateDrag()
                return true
            }

            MotionEvent.ACTION_UP -> if (!dragging) drag.recycle()

            MotionEvent.ACTION_CANCEL -> if (dragging) bounceBack()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once this view intercepted the sequence it must keep consuming it:
        // returning false on UP marks the whole sequence unconsumed and can
        // swallow the child page's click (single-tap overlay toggle).
        if (!dragging) drag.start(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (multiPointer || !drag.move(event)) {
                multiPointer = true
                bounceBack()
            } else updateDrag()

            MotionEvent.ACTION_UP -> {
                drag.addSample(event)
                val velocity = drag.velocityAlong(axisVertical)
                val span = axisSpan()
                val wasDragging = dragging
                dragging = false
                drag.recycle()
                if (!wasDragging) return true
                val flung = abs(velocity) > drag.flingVelocity && (velocity < 0f) == (main < 0f)
                if (span <= 0f) return true
                if (flung || abs(main) > COMMIT_PROGRESS * travel()) commit() else bounceBack()
            }

            MotionEvent.ACTION_CANCEL -> if (dragging) bounceBack()
        }
        return true
    }

    private fun beginDrag() {
        val dx = drag.dx()
        val dy = drag.dy()
        axisVertical = abs(dy) >= abs(dx)
        forward = if (axisVertical) dy < 0f else dx < 0f
        dragging = true
        val target = if (forward) position + 1 else position - 1
        entering = if (target in 0 until count) pool.acquire(target) else null
    }

    private fun updateDrag() {
        val span = axisSpan()
        var along = if (axisVertical) drag.dy() else drag.dx()
        if (entering == null) along *= RESIST else along = along.coerceIn(-span, span)
        applyTransition(along)
    }

    private fun commit() {
        val target = if (forward) position + 1 else position - 1
        if (target !in 0 until count) {
            bounceBack()
            return
        }
        val span = axisSpan()
        animateTo(if (forward) -span else span, target)
    }

    private fun bounceBack() {
        dragging = false
        animateTo(0f, null)
    }

    /**
     * Drives [main] to [target]. A [turn] means the page under the finger lands as
     * the new current one; null means the gesture springs back.
     */
    private fun animateTo(target: Float, turn: Int?) {
        finishAnimator()
        dragging = false
        val from = main
        val fraction = abs(target - from) / axisSpan().coerceAtLeast(1f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (turn == null) (180L + 120L * fraction).toLong() else (170L + 170L * fraction).toLong()
            interpolator = if (turn == null) PathInterpolator(0.3f, 0f, 0.3f, 1f) else PathInterpolator(0.2f, 0f, 0.1f, 1f)
            addUpdateListener {
                val value = it.animatedValue as Float
                applyTransition(from + (target - from) * value)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (turn == null) applyTransition(0f) else landOn(turn)
                }
            })
            start()
        }
    }

    private fun landOn(index: Int) {
        position = index
        current = entering
        entering = null
        warm()
        applyTransition(0f)
        onPositionChanged?.invoke(position)
    }

    private fun finishAnimator() {
        val running = animator ?: return
        animator = null
        running.end()
    }

    // endregion
}
