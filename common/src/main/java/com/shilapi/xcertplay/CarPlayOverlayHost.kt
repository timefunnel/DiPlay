package com.shilapi.xcertplay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/** Moves the original video AND touch views between the Activity and one non-focusable window. */
internal class CarPlayOverlayHost(
    private val context: Context,
    private val content: FrameLayout,
    private val home: FrameLayout,
    private val beforeMove: () -> Unit,
    private val failed: (String) -> Unit,
) {
    // Application context avoids inheriting the Activity's window token for TYPE_APPLICATION_OVERLAY.
    private val manager = context.applicationContext.getSystemService(WindowManager::class.java)
    private var window: FrameLayout? = null
    private var added = false
    val shown: Boolean get() = window != null

    fun show(bounds: Rect) {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            failed("overlay_permission")
            return
        }
        @Suppress("DEPRECATION")
        val windowType = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(), windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
            title = "DiPlay CarPlay"
        }
        try {
            val existing = window
            if (existing != null) {
                val previous = existing.layoutParams as WindowManager.LayoutParams
                if (previous.x == params.x && previous.y == params.y &&
                    previous.width == params.width && previous.height == params.height) return
                beforeMove()
                manager.updateViewLayout(existing, params)
                return
            }
            beforeMove()
            val overlay = FrameLayout(context.applicationContext)
            (content.parent as? ViewGroup)?.removeView(content)
            overlay.addView(content, FrameLayout.LayoutParams(-1, -1))
            // Keep ownership even if addView throws, so the failure path can restore the views.
            window = overlay
            manager.addView(overlay, params)
            added = true
        } catch (error: RuntimeException) {
            // Permission revocation, display removal and bad window tokens must be visible failures.
            Log.e("DiPlay-Dudu", "Could not show/update CarPlay overlay", error)
            hide()
            failed(error.javaClass.simpleName)
        }
    }

    fun hide() {
        val overlay = window ?: return
        beforeMove()
        window = null
        if (added) manager.removeViewImmediate(overlay)
        added = false
        overlay.removeView(content)
        home.addView(content, 0, FrameLayout.LayoutParams(-1, -1))
    }
}
