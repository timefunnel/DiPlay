package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
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
import org.robolectric.Shadows.shadowOf
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
        val screen = shadowOf(activity.windowManager.defaultDisplay)
        screen.setRealWidth(1920)
        screen.setRealHeight(1080)
        DuduLauncher.clearRequest()
        DuduLauncher.settingsVisible = false
        AirPlayPersistence.saveDuduIntegration(activity, true)
        set("sessionDisplay", display)
        set("controller", phone)
    }
    @After fun cleanup() {
        DuduLauncher.clearRequest()
        (get("carPlayOverlay") as? CarPlayOverlayHost)?.hide()
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        activity.stopBackgroundThemeMonitoring()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDuduIntegration(activity, false)
    }

    @Test fun overlayMeasurementsNeverReplaceTheFullWindowCanvasIncludingDuringTeardown() {
        val schedule = activity.javaClass.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        for ((w, h) in listOf(800 to 600, 1200 to 500, 1920 to 1080)) {
            schedule.invoke(activity, w, h)
            assertNull(get("pendingDisplaySize"))
            assertSame(display, get("sessionDisplay"))
        }
        verifyNoInteractions(phone)
        setSize(1920, 1080)
        set("sessionDisplay", null)
        set("handshakeResetInProgress", true)
        schedule.invoke(activity, 800, 600)
        assertNull(get("pendingDisplaySize"))
        set("sessionDisplay", display)
        val layout = activity.javaClass.getDeclaredMethod("contentRect", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 800, 600) as CarPlayVideoLayout
        assertEquals(800f, layout.width)
        assertEquals(450f, layout.height)
        assertEquals(75f, layout.top)
    }

    @Test fun matchingCardFillsItsWindowMapsTouchesAndReturnsToFullScreenWithoutReconnect() {
        val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, null,
            duduWindow = 1328f / 1920 to 1012f / 1080)!!
        set("sessionDisplay", display.copy(viewAreas = areas))
        `when`(phone.showViewArea(anyInt())).thenReturn(true)
        val overlay = prepareOverlay()
        request(1328, 1012)
        update()
        assertTrue(overlay.shown)
        assertEquals(CarPlayViewAreas.Kind.DUDU_CARD, areas.kindOf(areas.current))
        assertNull(get("pendingDuduSize"))
        val layout = activity.javaClass.getDeclaredMethod("contentRect", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1328, 1012) as CarPlayVideoLayout
        val card = areas.areas[areas.current]
        assertEquals(0f, layout.left)
        assertEquals(0f, layout.top)
        assertEquals(1328f, layout.width * card.width / 1920, .01f)
        assertEquals(1012f, layout.height * card.height / 1080, .01f)
        val received = mutableListOf<List<AirPlayContact>>()
        doAnswer { received += it.getArgument<List<AirPlayContact>>(0); true }.`when`(phone).sendTouch(anyList())
        val view = View(activity).apply { layout(0, 0, 1328, 1012) }
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 664f, 506f, 0)
        activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
            .apply { isAccessible = true }.invoke(activity, view, event)
        event.recycle()
        assertEquals(listOf(AirPlayContact(0, card.width / 3840.0, card.height / 2160.0, true)), received.single())
        DuduLauncher.receive(activity, Intent(DuduLauncher.CLOSE))
        update()
        assertFalse(overlay.shown)
        assertEquals(CarPlayViewAreas.Kind.FULL_SCREEN, areas.kindOf(areas.current))
        verify(phone, times(2)).showViewArea(anyInt())
        verify(phone, never()).close()
    }

    @Test fun nextHandshakeDeclaresTheRememberedCardAfterTheLauncherClosesIt() {
        request(1328, 1012)
        DuduLauncher.receive(activity, Intent(DuduLauncher.CLOSE))
        set("airPlayIdentity", AirPlayIdentity.generate())
        set("hevcEnabled", false)
        val sizeType = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        for (width in listOf(1920, 960)) {
            val size = sizeType.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(width, 990)
            val config = activity.javaClass.getDeclaredMethod("createAirPlayConfig", sizeType)
                .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
            val areas = get("pendingViewAreas") as CarPlayViewAreas
            assertEquals(areas.areas, config.main.viewAreas)
            assertEquals(CarPlayViewAreas.Kind.FULL_SCREEN, areas.kindOf(areas.current))
            assertNotNull(areas.duduCard(1328, 1012, false))
        }
    }

    @Test fun newShapeReconnectsOnceAfterSettlingWhileRepeatedRequestsDoNotPostponeIt() {
        val teardown = mock(ExecutorService::class.java)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        set("teardownExecutor", teardown)
        setSize(1920, 1080)
        CarPlayBackgroundSession.store(phone, sink, 1920, 1080, activity, display) {}
        prepareOverlay()
        request(200, 200)
        update()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        request(240, 200)
        update()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        request(240, 200, 5)
        update()
        verify(teardown, never()).execute(any(Runnable::class.java))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(201))
        verify(teardown, times(1)).execute(any(Runnable::class.java))
        assertTrue(get("handshakeResetInProgress") as Boolean)
        assertNull(get("controller"))
        assertNull(get("pendingDisplaySize"))
    }

    @Test fun closingCardCancelsPendingAutomaticReconnect() {
        prepareOverlay()
        request(200, 200)
        update()
        assertNotNull(get("pendingDuduSize"))
        DuduLauncher.receive(activity, Intent(DuduLauncher.CLOSE))
        update()
        assertNull(get("pendingDuduSize"))
        assertFalse((get("mainHandler") as Handler).hasCallbacks(get("adaptDuduSize") as Runnable))
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
    private fun setSize(width: Int, height: Int) {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        set("activeDisplaySize", type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height))
    }
    private fun prepareOverlay(): CarPlayOverlayHost {
        ShadowSettings.setCanDrawOverlays(true)
        val home = FrameLayout(activity)
        val content = FrameLayout(activity).also { home.addView(it) }
        val overlay = CarPlayOverlayHost(activity, content, home, {}, { error(it) })
        set("carPlayOverlay", overlay)
        set("allowDuduOverlay", true)
        @Suppress("UNCHECKED_CAST")
        (get("activeScreenStreamTypes") as MutableSet<Int>).add(110)
        return overlay
    }
    private fun request(width: Int, height: Int, x: Int = 0) {
        DuduLauncher.receive(activity, Intent(DuduLauncher.SHOW).putExtra("x", x).putExtra("y", 0)
            .putExtra("w", x + width).putExtra("h", height))
    }
    private fun update() { activity.javaClass.getDeclaredMethod("updateDuduOverlay").apply { isAccessible = true }.invoke(activity) }
}
