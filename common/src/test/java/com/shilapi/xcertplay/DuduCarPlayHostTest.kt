package com.shilapi.xcertplay

import android.os.Handler
import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class DuduCarPlayHostTest {
    private val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    private val phone = mock(CarPlayController::class.java)
    private val sink = mock(AndroidMediaSink::class.java)
    private val display = CarPlaySessionDisplay(1920, 1080, 0, true, true, 1920, 1080)

    @Before fun setup() {
        AirPlayPersistence.saveDuduIntegration(activity, true)
        set("sessionDisplay", display)
        set("controller", phone)
    }
    @After fun cleanup() {
        (get("carPlayOverlay") as? CarPlayOverlayHost)?.hide()
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        activity.stopBackgroundThemeMonitoring()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDuduIntegration(activity, false)
    }

    @Test fun floatingAndFullScreenSizesNeverRenegotiateTheConnectedCanvas() {
        val schedule = activity.javaClass.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        for ((w, h) in listOf(800 to 600, 1200 to 500, 1920 to 1080)) {
            schedule.invoke(activity, w, h)
            assertNull(get("pendingDisplaySize"))
            assertSame(display, get("sessionDisplay"))
        }
        verifyNoInteractions(phone)
        val layout = activity.javaClass.getDeclaredMethod("contentRect", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 800, 600) as CarPlayVideoLayout
        assertEquals(800f, layout.width)
        assertEquals(450f, layout.height)
        assertEquals(75f, layout.top)
    }

    @Test fun letterboxGesturesAreIgnoredAndContentTouchesUseTheNegotiatedCanvas() {
        ShadowSettings.setCanDrawOverlays(true)
        val home = FrameLayout(activity)
        val content = FrameLayout(activity).also { home.addView(it) }
        val overlay = CarPlayOverlayHost(activity, content, home, {}, { error(it) })
        set("carPlayOverlay", overlay)
        overlay.show(Rect(0, 0, 800, 600))
        val received = mutableListOf<List<AirPlayContact>>()
        doAnswer { received += it.getArgument<List<AirPlayContact>>(0); true }.`when`(phone).sendTouch(anyList())
        val view = View(activity).apply { layout(0, 0, 800, 600) }
        val touch = activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
            .apply { isAccessible = true }
        fun event(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            touch.invoke(activity, view, event)
            event.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 400f, 10f)
        event(MotionEvent.ACTION_MOVE, 400f, 300f)
        event(MotionEvent.ACTION_UP, 400f, 300f)
        assertTrue(received.isEmpty())
        event(MotionEvent.ACTION_DOWN, 400f, 300f)
        assertEquals(listOf(AirPlayContact(0, .5, .5, true)), received.single())
    }

    @Test fun backgroundThemeMonitoringBelongsToTheConnectionAndStopsWhenTheOwnerChanges() {
        CarPlayBackgroundSession.store(phone, sink, 1920, 1080, activity, display) {}
        activity.javaClass.getDeclaredMethod("updateThemeMonitoring").apply { isAccessible = true }.invoke(activity)
        val handler = get("mainHandler") as Handler
        val poll = get("pollConfiguration") as Runnable
        assertTrue(handler.hasCallbacks(poll))
        CarPlayBackgroundSession.store(phone, sink, 1920, 1080, Any(), display) {}
        assertFalse(handler.hasCallbacks(poll))
    }

    @Test fun onlyBackgroundCarPlayWithALiveStreamAndALauncherRequestMayFloat() {
        ShadowSettings.setCanDrawOverlays(true)
        val home = FrameLayout(activity)
        val content = FrameLayout(activity).also { home.addView(it) }
        val overlay = CarPlayOverlayHost(activity, content, home, {}, { error(it) })
        set("carPlayOverlay", overlay)
        @Suppress("UNCHECKED_CAST")
        val streams = get("activeScreenStreamTypes") as MutableSet<Int>
        streams.add(110)
        val request = Intent(DuduLauncher.SHOW).putExtra("x", 10).putExtra("y", 10)
            .putExtra("w", 210).putExtra("h", 210)
        DuduLauncher.receive(activity, request)
        assertEquals(Rect(10, 10, 210, 210), DuduLauncher.requestedBounds)
        val update = activity.javaClass.getDeclaredMethod("updateDuduOverlay").apply { isAccessible = true }
        update.invoke(activity)
        assertFalse("Opening/recreating the full-screen Activity must not briefly float", overlay.shown)
        set("allowDuduOverlay", true)
        update.invoke(activity)
        assertTrue(overlay.shown)
        set("menuOpen", true)
        update.invoke(activity)
        assertFalse("DiPlay settings stay in the full-screen container", overlay.shown)
        set("menuOpen", false)
        update.invoke(activity)
        assertTrue(overlay.shown)
        streams.clear()
        update.invoke(activity)
        assertFalse("A disconnected stream must not leave a stale picture", overlay.shown)
        streams.add(110)
        update.invoke(activity)
        assertTrue(overlay.shown)
        DuduLauncher.receive(activity, Intent(DuduLauncher.CLOSE))
        update.invoke(activity)
        assertFalse(overlay.shown)
        assertSame(home, content.parent)
    }

    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) { activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
}
