package com.james.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Green animated sound bars, like the mockups.
 * [active] = bigger motion (listening); [animating] = false draws still, low bars (music paused).
 */
class WaveformView(ctx: Context, private val bars: Int = 31) : android.view.View(ctx) {

    var active = false

    var animating = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2BD47D.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = if (animating) SystemClock.uptimeMillis() / 1000f else 0f
        val gap = w / bars
        paint.strokeWidth = gap * 0.45f
        val half = bars / 2f
        for (i in 0 until bars) {
            val x = gap * (i + 0.5f)
            val centre = 1f - abs(i - half) / half            // taller in the middle
            val wave = if (animating) sin(t * 3f + i * 0.55f) * 0.5f + 0.5f else 0.15f  // 0..1
            val amp = (if (active) 0.35f + 0.65f * wave else 0.15f + 0.3f * wave) * (0.3f + 0.7f * centre)
            val bh = max(paint.strokeWidth, h * amp)
            canvas.drawLine(x, h / 2 - bh / 2, x, h / 2 + bh / 2, paint)
        }
        if (animating && isShown) postInvalidateOnAnimation()   // animates only while visible
    }
}

/** A ScrollView that never grows taller than [maxHeightPx]. */
class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

/**
 * The bottom sheet's root. Handles:
 *  - a tap anywhere outside the sheet (ACTION_OUTSIDE) -> [onOutside]
 *  - a downward swipe -> [onDismiss]
 *  - any touch inside -> [onTouched] (resets the auto-close timer)
 */
class SheetLayout(
    ctx: Context,
    private val onDismiss: () -> Unit,
    private val onOutside: () -> Unit,
    private val onTouched: () -> Unit
) : FrameLayout(ctx) {

    /** A scrolling list inside the sheet; swipe-down scrolls it first if it isn't at the top. */
    var scrollChild: android.view.View? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouched()
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                val listCanScrollUp = scrollChild?.canScrollVertically(-1) == true
                if (dy > slop && dy > abs(dx) && !listCanScrollUp) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                translationY = max(0f, ev.rawY - downY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = ev.rawY - downY
                val threshold = 110 * resources.displayMetrics.density
                if (ev.actionMasked == MotionEvent.ACTION_UP && dy > threshold) {
                    onDismiss()
                } else {
                    animate().translationY(0f).setDuration(150).start()
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}

/** Thin rounded progress bar: grey track, green fill. [fraction] is 0..1. */
class ProgressLine(ctx: Context) : android.view.View(ctx) {

    var fraction = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2BD47D.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        if (width <= 0 || h <= 0f) return
        track.strokeWidth = h
        fill.strokeWidth = h
        val y = h / 2
        val start = h / 2
        val end = width - h / 2
        canvas.drawLine(start, y, end, y, track)
        if (fraction > 0f) canvas.drawLine(start, y, start + (end - start) * fraction, y, fill)
    }
}
