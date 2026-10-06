package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelCustomControlTest {
    private lateinit var service: WheelKeyService
    private var session: Any? = "phone-one"
    private val actions = mutableListOf<WheelControlAction>()
    private val host = object : WheelClimateHost.Host {
        override val available = true
        override var panelVisible = false
        override fun togglePanel() { panelVisible = !panelVisible }
        override fun perform(action: WheelControlAction) { actions += action }
        override fun closePanel() { panelVisible = false }
    }

    @Before fun setUp() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        for (name in listOf("diplay_wheel_controls", "diplay_wheel_map_zoom")) {
            service.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        service.session = { session }
        service.mapRoute = { null }
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        service.customAction = { action ->
            if (action == WheelControlAction.PANEL || action.mode == WheelControlMode.CLIMATE) WheelClimateHost.dispatch(action)
            else { actions += action; true }
        }
        WheelClimateHost.attach(host)
        WheelControlSettings.setEnabled(service, true)
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    @After fun tearDown() {
        service.onDestroy()
        WheelClimateHost.detach(host)
        service.getSharedPreferences("diplay_wheel_controls", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun bind(action: WheelControlAction, code: Int) =
        WheelControlSettings.assign(service, action, WheelKey(code, 0, "?"))
    private fun key(code: Int, down: Boolean, repeat: Int = 0): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }.invoke(service,
            KeyEvent(0, 0, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, 0, -1, 0)) as Boolean
    private fun press(code: Int): Pair<Boolean, Boolean> = key(code, true) to key(code, false)

    @Test fun theSameRollerChangesVolumeNormallyAndTemperatureOnlyInsideThePanel() {
        bind(WheelControlAction.PANEL, KeyEvent.KEYCODE_F1)
        bind(WheelControlAction.VOLUME_UP, KeyEvent.KEYCODE_F2)
        bind(WheelControlAction.TEMPERATURE_UP, KeyEvent.KEYCODE_F2)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F2))
        assertEquals(listOf(WheelControlAction.VOLUME_UP), actions)
        press(KeyEvent.KEYCODE_F1)
        assertTrue(host.panelVisible)
        press(KeyEvent.KEYCODE_F2)
        assertEquals(WheelControlAction.TEMPERATURE_UP, actions.last())
        press(KeyEvent.KEYCODE_F1)
        assertFalse(host.panelVisible)
        press(KeyEvent.KEYCODE_F2)
        assertEquals(WheelControlAction.VOLUME_UP, actions.last())
    }

    @Test fun disconnectedCarPlayDoesNotCaptureTheCarsKeys() {
        bind(WheelControlAction.PLAY_PAUSE, KeyEvent.KEYCODE_MUTE)
        session = null
        assertEquals(false to false, press(KeyEvent.KEYCODE_MUTE))
        assertTrue(actions.isEmpty())
    }

    @Test fun muteCanBeLearnedAsCarPlayPlayPauseWithoutTriggeringTwice() {
        bind(WheelControlAction.PLAY_PAUSE, KeyEvent.KEYCODE_MUTE)
        assertTrue(key(KeyEvent.KEYCODE_MUTE, true))
        assertTrue(key(KeyEvent.KEYCODE_MUTE, true, 1))
        assertTrue(key(KeyEvent.KEYCODE_MUTE, false))
        assertEquals(listOf(WheelControlAction.PLAY_PAUSE), actions)
    }

    @Test fun aHeldKeyKeepsItsReleaseAfterDisablingOrDisconnecting() {
        bind(WheelControlAction.VOLUME_UP, KeyEvent.KEYCODE_F2)
        assertTrue(key(KeyEvent.KEYCODE_F2, true))
        WheelControlSettings.setEnabled(service, false)
        session = null
        assertTrue(key(KeyEvent.KEYCODE_F2, true, 1))
        assertTrue(key(KeyEvent.KEYCODE_F2, false))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F2))
        assertEquals(1, actions.size)
    }

    @Test fun aCallClosesThePanelAndPassesNewVolumePresses() {
        bind(WheelControlAction.PANEL, KeyEvent.KEYCODE_F1)
        bind(WheelControlAction.TEMPERATURE_UP, KeyEvent.KEYCODE_F2)
        press(KeyEvent.KEYCODE_F1)
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertEquals(false to false, press(KeyEvent.KEYCODE_F2))
        assertFalse(host.panelVisible)
        assertTrue(actions.isEmpty())
    }

    @Test fun bindingConflictsAreReturnedAndThePanelKeyIsReservedAcrossModes() {
        bind(WheelControlAction.TEMPERATURE_UP, KeyEvent.KEYCODE_F2)
        assertEquals(listOf(WheelControlAction.TEMPERATURE_UP), bind(WheelControlAction.FAN_UP, KeyEvent.KEYCODE_F2))
        assertNull(WheelControlSettings.key(service, WheelControlAction.TEMPERATURE_UP))
        assertEquals(listOf(WheelControlAction.FAN_UP), bind(WheelControlAction.PANEL, KeyEvent.KEYCODE_F2))
        assertNull(WheelControlSettings.key(service, WheelControlAction.FAN_UP))
    }

    @Test fun learningIsAvailableBeforeConnectingButDoesNotExecuteTheLearnedAction() {
        session = null
        var learned: WheelKey? = null
        assertTrue(WheelKeyService.learnCustom(WheelControlAction.POWER) { key, _ -> learned = key })
        assertEquals(true to true, press(KeyEvent.KEYCODE_F3))
        assertEquals(KeyEvent.KEYCODE_F3, learned?.code)
        assertTrue(actions.isEmpty())
        assertEquals(false to false, press(KeyEvent.KEYCODE_F3))
    }

    @Test fun unknownKeysPassInBothModes() {
        assertEquals(false to false, press(KeyEvent.KEYCODE_F12))
        host.panelVisible = true
        assertEquals(false to false, press(KeyEvent.KEYCODE_F12))
    }
}
