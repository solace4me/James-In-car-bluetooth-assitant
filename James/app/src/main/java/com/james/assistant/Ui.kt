package com.james.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** James's look in one place: colours, type sizes, and small view builders (8 dp spacing grid). */
object Ui {
    const val BG = 0xFF0E0E10.toInt()
    const val SURFACE = 0xFF1B1B1F.toInt()
    const val SURFACE_HI = 0xFF26262B.toInt()
    const val HAIRLINE = 0x1AFFFFFF
    const val GREEN = 0xFF2BD47D.toInt()
    const val GREEN_DIM = 0x262BD47D
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_2 = 0xB3FFFFFF.toInt()   // secondary
    const val TEXT_3 = 0x80FFFFFF.toInt()   // labels
    const val GREY_DOT = 0xFF6E6E73.toInt()

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, sp: Float, color: Int = TEXT, bold: Boolean = false, oneLine: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = if (bold) BOLD else MEDIUM
            includeFontPadding = false
            if (oneLine) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    /** Uppercase section label, e.g. "SETUP". */
    fun label(ctx: Context, s: String) = text(ctx, s.uppercase(), 13f, TEXT_3, bold = true).apply {
        letterSpacing = 0.12f
    }

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun oval(ctx: Context, color: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    /** Soft top-to-bottom gradient card with a hairline edge: gives depth without looking busy. */
    fun card(ctx: Context, radiusDp: Int = 24, top: Int = SURFACE_HI, bottom: Int = SURFACE) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(dp(ctx, 1), HAIRLINE)
        }

    fun pressable(d: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null)

    fun icon(ctx: Context, res: Int, tint: Int = TEXT) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** Round icon button, e.g. play / next / close. */
    fun circleButton(ctx: Context, res: Int, sizeDp: Int, iconDp: Int, fill: Int, tint: Int, desc: String, onClick: () -> Unit) =
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = pressable(oval(ctx, fill))
            isClickable = true
            isFocusable = true
            contentDescription = desc
            setOnClickListener { onClick() }
            addView(icon(ctx, res, tint), LinearLayout.LayoutParams(dp(ctx, iconDp), dp(ctx, iconDp)))
            minimumWidth = dp(ctx, sizeDp)
            minimumHeight = dp(ctx, sizeDp)
        }

    fun roundCorners(view: View, radiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun column(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun space(ctx: Context, wDp: Int, hDp: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, wDp), dp(ctx, hDp))
    }
}
