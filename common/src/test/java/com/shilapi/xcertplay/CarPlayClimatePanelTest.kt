package com.shilapi.xcertplay

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayClimatePanelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val bus = ClimateTestBus()
    private var allowed = true
    private val worker = InlineWorker()
    private val requestedThemes = mutableListOf<Boolean>()
    private val panel = CarPlayClimatePanel(context, { allowed }, { panelStop() }, true, ::requestNight,
        BydClimateBackend(bus) { }, worker)
    private fun requestNight(night: Boolean) { requestedThemes += night; panel.setNightMode(night) }
    private fun panelStop() { panel.stop() }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts() = descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }

    @Test fun unknownValuesAreShownAsUnknownAndDisableWrites() {
        bus.values["AC_TEMP_MAIN"] = 65535
        panel.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(descendants(panel).any {
            it.contentDescription == context.getString(R.string.climate_temperature_value, "—")
        })
        assertTrue(texts().contains("—"))
        val plus = descendants(panel).filterIsInstance<Button>()
            .single { it.contentDescription == context.getString(R.string.climate_temperature_up) }
        assertFalse(plus.isEnabled)
        panel.perform(WheelControlAction.TEMPERATURE_UP)
        assertTrue(bus.writes.isEmpty())
        panel.stop()
    }

    @Test fun unconfirmedWritesKeepAnExplicitErrorAfterTheNextPoll() {
        bus.applyWrites = false
        panel.start()
        shadowOf(Looper.getMainLooper()).idle()
        panel.perform(WheelControlAction.FAN_UP)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(texts().contains(context.getString(R.string.climate_unconfirmed)))
        assertFalse(texts().contains(context.getString(R.string.climate_confirmed)))
        panel.stop()
    }

    @Test fun stoppedPanelCannotSendAnotherCommand() {
        panel.start()
        shadowOf(Looper.getMainLooper()).idle()
        panel.stop()
        panel.perform(WheelControlAction.POWER)
        assertTrue(bus.writes.isEmpty())
        assertTrue(worker.isShutdown)
    }

    @Test fun aQueuedWheelCommandDoesNotWriteAfterThePanelCloses() {
        val queued = QueuedWorker()
        lateinit var otherPanel: CarPlayClimatePanel
        otherPanel = CarPlayClimatePanel(context, { true }, { otherPanel.stop() }, true, {}, BydClimateBackend(bus) { }, queued)
        otherPanel.start()
        shadowOf(Looper.getMainLooper()).idle()
        queued.tasks.removeAt(0).run()
        shadowOf(Looper.getMainLooper()).idle()
        otherPanel.perform(WheelControlAction.FAN_UP)
        val command = queued.tasks.removeAt(0)
        otherPanel.stop()
        command.run()
        assertTrue(bus.writes.isEmpty())
        panel.stop()
    }

    @Test fun changingAppearancePreservesVehicleStateAndRequestsTheHostThemeWithoutCarWrites() {
        panel.start()
        shadowOf(Looper.getMainLooper()).idle()
        val toggle = descendants(panel).filterIsInstance<Button>().single {
            it.contentDescription == context.getString(R.string.climate_switch_light)
        }
        toggle.performClick()
        assertTrue(texts().contains("22°"))
        assertTrue(texts().contains(context.getString(R.string.climate_fan_level, 3)))
        assertTrue(bus.writes.isEmpty())
        assertEquals(listOf(false), requestedThemes)
        assertEquals(context.getString(R.string.climate_switch_dark), toggle.contentDescription)
        panel.stop()

    }

    private class QueuedWorker : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks += command }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also { tasks.clear(); stopped = true }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    private class InlineWorker : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); command.run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
