/*
 * *************************************************************************
 *  CardStackPager.kt
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
 * Card stack reading: the folder as a small deck you flip sideways.
 *
 * At rest there is no stack at all, only the current image edge to edge. Grab it and the
 * deck appears: the card in hand just slides aside, full size and sharp, and what it
 * uncovers is the next image already waiting underneath - centred, at 78%, transparent
 * and softly out of focus - which fades up to full opacity and grows into place as the
 * top card moves off it. Both directions are that one gesture, and nothing has to travel
 * in from off screen, so a landscape photo on a tablet never has to cross the whole
 * display.
 *
 * Cards are clipped to the rect their photo paints, never to the screen, so a card never
 * carries the black around a portrait image with it.
 *
 * Paging is horizontal; a zoomed card keeps its own panning and a second finger is always
 * the card's pinch.
 */
class CardStackPager @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
        ViewGroup(context, attrs), PagedReader {

    companion object {
        /** Scale of the card waiting under the one in hand; it fades in from nothing. */
        private const val SIDE_SCALE = 0.78f
        /** Fraction of a screen width a slow drag has to cover to turn the deck. */
        private const val COMMIT_PROGRESS = 0.34f
        /** A drag with nowhere to go only stretches this much of itself. */
        private const val RESIST = 0.3f
        /** Radius of a card, and the sliver of card face left around its photo. */
        private const val CARD_RADIUS_DP = 20f
        private const val CARD_EDGE_DP = 3f
    }

    var onImageLoad: ((ZoomableImageView, Int) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onPositionChanged: ((Int) -> Unit)? = null

    var position = 0
        private set

    private val density = resources.displayMetrics.density
    private val cardRadiusPx = CARD_RADIUS_DP * density
    private val cardEdge = CARD_EDGE_DP * density
    private val pool = PagePool(this,
            onLoad = { image, position -> onImageLoad?.invoke(image, position) },
            onTap = { onSingleTap?.invoke() })
    private val drag = DragTracker(context)

    private var count = 0
    private var animator: ValueAnimator? = null
    private var center: ImagePage? = null
    private var incoming: ImagePage? = null
    private var forward = true
    private var offset = 0f
    private var progress = 0f
    private var dragging = false
    private var multiPointer = false

    /** Starts over on [entries] images showing [index]. */
    override fun setPages(entries: Int, index: Int) {
        count = entries.coerceAtLeast(0)
        rebuild(if (count == 0) 0 else index.coerceIn(0, count - 1))
    }

    /** Jumps to [index]; the animated form slides the deck the way a finger would. */
    override fun setPosition(index: Int, animated: Boolean) {
        if (count == 0 || index !in 0 until count || index == position) return
        if (!animated) {
            rebuild(index)
            onPositionChanged?.invoke(position)
            return
        }
        finishAnimator()
        forward = index > position
        if (!beginTurn()) return
        animateTo(if (forward) -width.toFloat() else width.toFloat(), index)
    }

    private fun rebuild(index: Int) {
        finishAnimator()
        dragging = false
        for (i in 0 until childCount) (getChildAt(i) as? ImagePage)?.let { pool.release(it) }
        position = index
        center = null
        incoming = null
        if (count > 0) center = pool.acquire(index)
        warm()
        applyTurn(0f)
    }

    /**
     * Keeps the image either side decoded and waiting under the top card, so a turn has
     * something to uncover even over a slow share. They stay invisible: the deck only
     * exists while a turn is running.
     */
    private fun warm() {
        val wanted = setOf(position - 1, position + 1)
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? ImagePage ?: continue
            if (page.position >= 0 && page !== center && page.position !in wanted) pool.release(page)
        }
        for (index in wanted) if (index in 0 until count) pool.acquire(index)
    }

    /**
     * Takes the card a turn needs out of the deck. Returns false when the sequence has
     * nothing on that side, which still lets the deck stretch.
     */
    private fun beginTurn(): Boolean {
        val target = if (forward) position + 1 else position - 1
        incoming = if (target in 0 until count) pool.acquire(target) else null
        progress = 0f
        offset = 0f
        stackDeck()
        return incoming != null
    }

    /** The top card of the deck is the one in hand, so it uncovers the rest as it moves. */
    private fun stackDeck() {
        incoming?.let { bringChildToFront(it) }
        center?.let { bringChildToFront(it) }
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
        applyTurn(offset)
    }

    // region deck transform

    /**
     * Lays the deck out for a slide of [offset]. The card in hand only slides aside, at
     * full size and sharp; what it uncovers is the card already waiting underneath,
     * centred, smaller, dimmer and soft, which grows into place as the top card moves off
     * it. Nothing has to travel in from off screen, so a landscape photo on a tablet does
     * not have to cross the whole display to be read.
     */
    private fun applyTurn(offset: Float) {
        this.offset = offset
        val width = width.toFloat()
        if (width <= 0f) return
        progress = (abs(offset) / width).coerceIn(0f, 1f)
        val turning = dragging || animator != null
        val shown = if (turning) VISIBLE else INVISIBLE
        val grown = SIDE_SCALE + (1f - SIDE_SCALE) * progress
        // no depth-of-field blur here on purpose: a blurred layer that is also scaled and
        // faded every frame leaves a smear on some GPUs, and the reveal reads as depth
        // already from the scale and the fade
        center?.placeCard(offset, 1f, 1f, 0f, VISIBLE)
        incoming?.placeCard(0f, grown, progress, 1f - progress, shown)
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? ImagePage ?: continue
            if (page === center || page === incoming || page.position < 0) continue
            page.placeCard(0f, SIDE_SCALE, 0f, 1f, INVISIBLE)
        }
    }

    /**
     * Places one card for a slide of [along] across the width. [round] is how far it has
     * become a card: its corner radius, the sliver of face around the photo it shows, and
     * the clip to the photo's own rect.
     */
    private fun ImagePage.placeCard(along: Float, scale: Float, alpha: Float, round: Float,
            visibility: Int) {
        if (this.visibility != visibility) this.visibility = visibility
        translationX = along
        translationY = 0f
        rotation = 0f
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
        cardRadius = cardRadiusPx * round
        cardFace = round
        // clipped to the photo itself even when square, so a card sliding aside is the
        // photo moving and not a full screen black panel wiping the one underneath
        setClipTo(image.photoRect(), cardEdge * round)
        blurTo(0f)
    }

    // endregion

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
                // a second finger means pinch, which belongs to the card
                multiPointer = true
                if (dragging) animateTo(0f, null)
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiPointer || !drag.move(event)) return false
                if (dragging) {
                    applyTurn(clamped(drag.dx()))
                    return true
                }
                if (abs(drag.dx()) < drag.slop || abs(drag.dy()) > abs(drag.dx()) || centerZoomed()) return false
                forward = drag.dx() < 0f
                beginTurn()
                dragging = true
                applyTurn(clamped(drag.dx()))
                return true
            }

            MotionEvent.ACTION_UP -> if (!dragging) drag.recycle()

            MotionEvent.ACTION_CANCEL -> if (dragging) animateTo(0f, null)
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (multiPointer || !drag.move(event)) {
                multiPointer = true
                animateTo(0f, null)
            } else applyTurn(clamped(drag.dx()))

            MotionEvent.ACTION_UP -> {
                drag.addSample(event)
                val velocity = drag.velocityAlong(false)
                dragging = false
                drag.recycle()
                val width = width.toFloat()
                val flung = abs(velocity) > drag.flingVelocity && (velocity < 0f) == (offset < 0f)
                if (width <= 0f) return true
                if (flung || abs(offset) / width > COMMIT_PROGRESS) commit() else animateTo(0f, null)
            }

            MotionEvent.ACTION_CANCEL -> animateTo(0f, null)
        }
        return true
    }

    private fun clamped(dx: Float): Float {
        val width = width.toFloat()
        if (incoming == null) return (dx * RESIST).coerceIn(-width * RESIST, width * RESIST)
        return dx.coerceIn(-width, width)
    }

    private fun centerZoomed() = center?.image?.isZoomed() == true

    private fun commit() {
        val target = if (forward) position + 1 else position - 1
        if (target !in 0 until count) {
            animateTo(0f, null)
            return
        }
        animateTo(if (forward) -width.toFloat() else width.toFloat(), target)
    }

    /**
     * Drives [offset] to [target]. A [turn] means the incoming card lands in the centre;
     * null springs the deck back to a single full screen image.
     */
    private fun animateTo(target: Float, turn: Int?) {
        finishAnimator()
        dragging = false
        val from = offset
        val distance = abs(target - from) / width.toFloat().coerceAtLeast(1f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (220L + 180L * distance).toLong()
            interpolator = if (turn == null) PathInterpolator(0.3f, 0f, 0.3f, 1f) else PathInterpolator(0.2f, 0f, 0.1f, 1f)
            addUpdateListener {
                applyTurn(from + (target - from) * it.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (turn == null) {
                        applyTurn(0f)
                    } else {
                        position = turn
                        center = incoming
                        incoming = null
                        warm()
                        applyTurn(0f)
                        onPositionChanged?.invoke(position)
                    }
                }
            })
            start()
        }
    }

    private fun finishAnimator() {
        val running = animator ?: return
        animator = null
        running.end()
    }

    // endregion
}
