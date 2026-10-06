package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

enum class WheelControlMode { NORMAL, CLIMATE }

/** One learned key per action. Firmware-specific long-press codes can be learned separately. */
enum class WheelControlAction(val mode: WheelControlMode, val label: Int) {
    PANEL(WheelControlMode.NORMAL, R.string.climate_panel_toggle),
    PLAY_PAUSE(WheelControlMode.NORMAL, R.string.wheel_custom_play_pause),
    NEXT(WheelControlMode.NORMAL, R.string.wheel_custom_next),
    PREVIOUS(WheelControlMode.NORMAL, R.string.wheel_custom_previous),
    VOLUME_UP(WheelControlMode.NORMAL, R.string.wheel_custom_volume_up),
    VOLUME_DOWN(WheelControlMode.NORMAL, R.string.wheel_custom_volume_down),
    SIRI(WheelControlMode.NORMAL, R.string.wheel_custom_siri),
    TEMPERATURE_UP(WheelControlMode.CLIMATE, R.string.climate_temperature_up),
    TEMPERATURE_DOWN(WheelControlMode.CLIMATE, R.string.climate_temperature_down),
    FAN_UP(WheelControlMode.CLIMATE, R.string.climate_fan_up),
    FAN_DOWN(WheelControlMode.CLIMATE, R.string.climate_fan_down),
    POWER(WheelControlMode.CLIMATE, R.string.climate_power),
    COMPRESSOR(WheelControlMode.CLIMATE, R.string.climate_compressor),
    CYCLE(WheelControlMode.CLIMATE, R.string.climate_cycle),
    AUTO(WheelControlMode.CLIMATE, R.string.climate_auto),
    CLOSE(WheelControlMode.CLIMATE, R.string.climate_close),
}

object WheelControlSettings {
    private const val PREFS = "diplay_wheel_controls"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun key(context: Context, action: WheelControlAction): WheelKey? =
        WheelKey.decode(prefs(context).getString(action.name, null))

    /** Reassignment replaces conflicts in the same mode; the panel toggle is reserved in both modes. */
    fun assign(context: Context, action: WheelControlAction, key: WheelKey): List<WheelControlAction> {
        val removed = WheelControlAction.entries.filter {
            it != action && this.key(context, it) == key &&
                (it.mode == action.mode || it == WheelControlAction.PANEL || action == WheelControlAction.PANEL)
        }
        val edit = prefs(context).edit().putString(action.name, key.encode())
        removed.forEach { edit.remove(it.name) }
        edit.apply()
        WheelKeyService.settingsChanged()
        return removed
    }

    fun clear(context: Context, action: WheelControlAction) {
        prefs(context).edit().remove(action.name).apply()
        WheelKeyService.settingsChanged()
    }

    fun action(context: Context, key: WheelKey, panelVisible: Boolean): WheelControlAction? {
        if (this.key(context, WheelControlAction.PANEL) == key) return WheelControlAction.PANEL
        val mode = if (panelVisible) WheelControlMode.CLIMATE else WheelControlMode.NORMAL
        return WheelControlAction.entries.firstOrNull { it.mode == mode && this.key(context, it) == key }
    }
}

/** The foreground CarPlay activity owns this registration and removes it when paused. */
internal object WheelClimateHost {
    interface Host {
        val available: Boolean
        val panelVisible: Boolean
        fun togglePanel()
        fun perform(action: WheelControlAction)
        fun closePanel()
    }

    private var host: Host? = null
    fun attach(host: Host) { this.host = host }
    fun detach(host: Host) { if (this.host === host) this.host = null }
    val panelVisible: Boolean get() = host?.let { it.available && it.panelVisible } == true
    fun close() { host?.closePanel() }
    fun dispatch(action: WheelControlAction): Boolean {
        val current = host?.takeIf { it.available } ?: return false
        if (action == WheelControlAction.PANEL) current.togglePanel()
        else if (current.panelVisible) current.perform(action)
        else return false
        return true
    }
}
