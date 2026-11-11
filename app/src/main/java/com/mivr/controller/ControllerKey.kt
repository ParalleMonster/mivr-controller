package com.mivr.controller

import android.content.Context
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import kotlin.math.abs

internal class ControllerKey(
    context: Context,
    private val mask: Int,
    private val input: () -> ControllerInput?,
) : Button(context) {
    private val touchpad = mask == ControllerReports.TOUCHPAD_CLICK
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pressedAt = 0L
    private val hold = Runnable { press() }
    private val release = Runnable {
        pressedAt = 0L
        input()?.let {
            it.setButton(mask, false)
            if (touchpad) it.setButton(ControllerReports.TOUCHING, false)
        }
    }

    init {
        isSoundEffectsEnabled = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (touchpad && !insidePad(event.x, event.y)) return false
                reset()
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                moved = false
                isPressed = true
                parent.requestDisallowInterceptTouchEvent(true)
                touchFeedback()
                if (touchpad) {
                    updateTouch(downX, downY)
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                } else {
                    press()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchpad) {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        if (abs(x - downX) > slop || abs(y - downY) > slop) {
                            moved = true
                            removeCallbacks(hold)
                        }
                        updateTouch(x, y)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) finishTouch()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    override fun performClick(): Boolean {
        reset()
        if (touchpad) input()?.setTouch(0.5f, 0.5f)
        touchFeedback()
        press()
        releaseAfterMinimum()
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }

    fun reset() {
        removeCallbacks(hold)
        removeCallbacks(release)
        pointerId = -1
        isPressed = false
        release.run()
    }

    private fun finishTouch() {
        removeCallbacks(hold)
        pointerId = -1
        isPressed = false
        if (touchpad && moved && pressedAt == 0L) {
            release.run()
            return
        }
        if (pressedAt == 0L) press()
        releaseAfterMinimum()
        super.performClick()
    }

    private fun updateTouch(x: Float, y: Float) {
        input()?.setTouch(x / width, y / height)
    }

    private fun insidePad(x: Float, y: Float): Boolean {
        val dx = x / width - 0.5f
        val dy = y / height - 0.5f
        return dx * dx + dy * dy <= 0.25f
    }

    private fun touchFeedback() {
        performHapticFeedback(if (touchpad) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun press() {
        pressedAt = SystemClock.uptimeMillis()
        input()?.setButton(mask, true)
    }

    private fun releaseAfterMinimum() {
        // Keep quick taps visible to at least one Bluetooth report.
        val remaining = 60L - (SystemClock.uptimeMillis() - pressedAt)
        if (remaining > 0) postDelayed(release, remaining) else release.run()
    }
}
