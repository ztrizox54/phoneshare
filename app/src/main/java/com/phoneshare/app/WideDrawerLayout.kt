package com.phoneshare.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

/**
 * DrawerLayout that opens the start drawer on a horizontal swipe starting
 * anywhere within ~105dp of the left edge -- much wider than the stock ~20dp
 * grab strip. Reflection on mLeftDragger.mEdgeSize is unreliable across AndroidX
 * versions and OEM builds, so we just do the math ourselves in onInterceptTouchEvent.
 */
class WideDrawerLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : DrawerLayout(context, attrs) {

    private val edgePx = (context.resources.displayMetrics.density * 105f).toInt()
    private val touchSlop = (context.resources.displayMetrics.density * 8f).toInt()

    private var startX = 0f
    private var startY = 0f
    private var tracking = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val handled = try { super.onInterceptTouchEvent(ev) } catch (_: Throwable) { false }
        if (handled) return true
        if (isDrawerOpen(GravityCompat.START)) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ev.x <= edgePx) {
                    startX = ev.x; startY = ev.y; tracking = true
                } else tracking = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (dx > touchSlop && abs(dx) > abs(dy)) {
                        openDrawer(GravityCompat.START)
                        tracking = false
                        return true
                    }
                    if (abs(dy) > abs(dx) + touchSlop) tracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }
}
