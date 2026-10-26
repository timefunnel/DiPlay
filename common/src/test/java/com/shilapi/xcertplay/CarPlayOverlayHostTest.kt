package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayOverlayHostTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()
    private val home = FrameLayout(activity.get())
    private val content = FrameLayout(activity.get())
    private val video = View(activity.get())
    private val gesture = View(activity.get())
    private val failures = mutableListOf<String>()
    private var touches = 0
    private var moves = 0
    private val host = CarPlayOverlayHost(activity.get(), content, home, { moves++ }, failures::add)

    @Before fun setup() {
        ShadowSettings.setCanDrawOverlays(true)
        content.addView(video)
        content.addView(gesture)
        gesture.setOnTouchListener { _, _ -> touches++; true }
        home.addView(content)
        activity.get().setContentView(home)
    }

    @After fun cleanup() { host.hide(); activity.pause().stop().destroy() }

    @Test fun theOriginalVideoAndInteractiveLayerMoveTogetherAndReturnWithoutDuplication() {
        host.show(Rect(100, 50, 900, 500))
        val overlay = content.parent as FrameLayout
        assertTrue(host.shown)
        assertEquals(0, home.childCount)
        assertSame(video, content.getChildAt(0))
        assertSame(gesture, content.getChildAt(1))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        gesture.dispatchTouchEvent(event)
        event.recycle()
        assertEquals(1, touches)
        val params = overlay.layoutParams as WindowManager.LayoutParams
        assertEquals(800, params.width)
        assertEquals(450, params.height)
        assertEquals(100, params.x)
        assertEquals(50, params.y)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        host.show(Rect(200, 100, 800, 450))
        assertSame(overlay, content.parent)
        assertEquals(600, (overlay.layoutParams as WindowManager.LayoutParams).width)
        assertEquals(2, moves)
        host.hide()
        host.hide()
        assertSame(home, content.parent)
        assertEquals(1, home.childCount)
        assertEquals(3, moves)
        assertTrue(failures.isEmpty())
    }

    @Test fun missingPermissionReportsFailureAndKeepsTheOriginalViews() {
        ShadowSettings.setCanDrawOverlays(false)
        host.show(Rect(100, 50, 900, 500))
        assertFalse(host.shown)
        assertSame(home, content.parent)
        assertEquals(listOf("overlay_permission"), failures)
    }

    @Test @Config(sdk = [25])
    fun androidSevenUsesItsSupportedOverlayWindowType() {
        host.show(Rect(100, 50, 900, 500))
        val params = (content.parent as FrameLayout).layoutParams as WindowManager.LayoutParams
        @Suppress("DEPRECATION")
        assertEquals(WindowManager.LayoutParams.TYPE_PHONE, params.type)
        assertTrue(host.shown)
        assertTrue(failures.isEmpty())
    }
}
