package com.getbouncer.scan.ui.util

import android.content.Context
import android.content.res.Resources.NotFoundException
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.getbouncer.scan.framework.time.Duration
import com.getbouncer.scan.ui.R
import kotlin.math.roundToInt

/**
 * Determine if a view is visible.
 */
fun View.isVisible() = this.visibility == View.VISIBLE

/**
 * Set a view's visibility.
 */
fun View.setVisible(visible: Boolean) {
    this.visibility = if (visible) View.VISIBLE else View.GONE
}

/**
 * Make a view visible.
 */
fun View.show() = setVisible(true)

/**
 * Make a view invisible.
 */
fun View.hide() = setVisible(false)

/**
 * Fade in a view.
 */
fun View.fadeIn(duration: Duration? = null) {
    if (isVisible()) return

    val animation = AnimationUtils.loadAnimation(this.context, R.anim.bouncer_fade_in)
    visibility = View.INVISIBLE
    if (duration != null) {
        animation.duration = duration.inMilliseconds.toLong()
    }
    startAnimation(animation)
    show()
}

/**
 * Fade out a view.
 */
fun View.fadeOut(duration: Duration? = null) {
    if (!isVisible()) return

    val animation = AnimationUtils.loadAnimation(this.context, R.anim.bouncer_fade_out)
    if (duration != null) {
        animation.duration = duration.inMilliseconds.toLong()
    }
    startAnimation(animation)
    Handler(Looper.getMainLooper()).postDelayed({ hide() }, duration?.inMilliseconds?.toLong() ?: 400)
}

/**
 * Get a [ColorInt] from a [ColorRes].
 */
@ColorInt
fun Context.getColorByRes(@ColorRes colorRes: Int) = ContextCompat.getColor(this, colorRes)

/**
 * Get a [Drawable] from a [DrawableRes]
 */
fun Context.getDrawableByRes(@DrawableRes drawableRes: Int) = ContextCompat.getDrawable(
    this,
    drawableRes
)

/**
 * Set the image of an [ImageView] using a [DrawableRes].
 */
fun ImageView.setDrawable(@DrawableRes drawableRes: Int) {
    this.setImageDrawable(this.context.getDrawableByRes(drawableRes))
}

/**
 * Set the image of an [ImageView] using a [DrawableRes] and start the animation.
 */
fun ImageView.startAnimation(@DrawableRes drawableRes: Int) {
    val d = this.context.getDrawableByRes(drawableRes)
    setImageDrawable(d)
    if (d is Animatable) {
        d.start()
    }
}

/**
 * Get a rect from a view.
 */
fun View.asRect() = Rect(left, top, right, bottom)

/**
 * Convert an int in DP to pixels.
 */
fun Context.dpToPixels(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

/**
 * This is copied from Resources.java for API 29 so that we can continue to support API 21.
 */
fun Context.getFloatResource(@DimenRes id: Int): Float {
    val value = TypedValue()
    resources.getValue(id, value, true)
    if (value.type == TypedValue.TYPE_FLOAT) {
        return value.float
    }
    throw NotFoundException("Resource ID #0x ${Integer.toHexString(id)} type #0x${Integer.toHexString(value.type)} is not valid")
}

/**
 * Set the size of a text field using a dimension.
 */
fun TextView.setTextSizeByRes(@DimenRes id: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, this.resources.getDimension(id))
}

/**
 * Determine the center point of a view.
 */
fun View.centerPoint() = PointF(left + width / 2F, top + height / 2F)
