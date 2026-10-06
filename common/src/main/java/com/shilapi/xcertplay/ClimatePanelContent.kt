package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Static glass styling for Android 9/10. No video capture, blur, or frame-by-frame effect. */
internal class ClimatePanelContent(
    context: Context,
    initialNight: Boolean,
    requestNight: (Boolean) -> Unit,
    private val command: (WheelControlAction) -> Unit,
    close: () -> Unit,
    refresh: () -> Unit,
) : LinearLayout(context) {
    private var light = !initialNight
    private var palette = palette(light)
    private val secondaryLabels = mutableListOf<TextView>()
    private val controls = linkedMapOf<WheelControlAction, Button>()
    private val temperature = label("", 64).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
    }
    private val fan = label("", 14, secondary = true)
    private val fanLevels = ClimateFanLevels(context)
    val status = label(context.getString(R.string.climate_reading), 12, secondary = true).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val title = label(context.getString(R.string.climate_panel_title), 18)
    private val handle = View(context)
    private val theme = button("") {
        requestNight(light)
    }.apply {
        textSize = 13f
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val refreshButton = iconButton(R.drawable.ic_climate_refresh, R.string.climate_refresh, refresh)
    private val closeButton = iconButton(R.drawable.ic_climate_close, R.string.climate_close, close)
    private val main = row()
    private val temperatureBlock = block().apply {
        addView(controlLabel(R.string.climate_temperature_label, label("°C", 14, secondary = true)))
        addView(row().apply {
            addView(actionButton(WheelControlAction.TEMPERATURE_DOWN), LayoutParams(dp(48), dp(48)))
            addView(temperature, LayoutParams(0, -1, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(actionButton(WheelControlAction.TEMPERATURE_UP), LayoutParams(dp(48), dp(48)))
        }, LayoutParams(-1, dp(92)))
    }
    private val fanBlock = block().apply {
        addView(controlLabel(R.string.climate_fan_label, fan))
        addView(row().apply {
            addView(actionButton(WheelControlAction.FAN_DOWN), LayoutParams(dp(48), dp(48)))
            addView(fanLevels, LayoutParams(0, dp(28), 1f).apply { setMargins(dp(12), 0, dp(12), 0) })
            addView(actionButton(WheelControlAction.FAN_UP), LayoutParams(dp(48), dp(48)))
        }, LayoutParams(-1, dp(92)))
    }
    private val switches = GridLayout(context)
    private val toggleActions = listOf(WheelControlAction.POWER, WheelControlAction.COMPRESSOR,
        WheelControlAction.CYCLE, WheelControlAction.AUTO)
    private var narrow: Boolean? = null

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(10), dp(24), dp(14))
        isClickable = true
        clipToOutline = true
        elevation = dp(10).toFloat()
        addView(handle, LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })
        addView(row().apply {
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_climate_wind, 0, 0, 0)
            title.compoundDrawablePadding = dp(10)
            addView(title, LayoutParams(0, dp(44), 1f))
            addView(theme, LayoutParams(dp(88), dp(44)))
            addView(refreshButton, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            addView(closeButton, LayoutParams(dp(44), dp(44)))
        }, LayoutParams(-1, dp(44)))
        main.addView(temperatureBlock)
        main.addView(fanBlock)
        toggleActions.forEach { action ->
            switches.addView(actionButton(action).apply {
                textSize = 15f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                compoundDrawablePadding = dp(8)
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon(action), 0, 0, 0)
            })
        }
        val body = block(padded = false).apply {
            addView(main, LayoutParams(-1, -2).apply { topMargin = dp(20) })
            addView(switches, LayoutParams(-1, -2).apply { topMargin = dp(20) })
            addView(row().apply {
                addView(label(context.getString(R.string.climate_controls_active), 12, secondary = true).apply {
                    contentDescription = context.getString(R.string.climate_panel_hint)
                }, LayoutParams(0, -2, 1f))
                addView(status, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            }, LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        addView(ScrollView(context).apply {
            isFillViewport = false
            addView(body)
        }, LayoutParams(-1, -2))
        configureLayout(false)
        applyTheme()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        configureLayout(MeasureSpec.getSize(widthMeasureSpec) < dp(560))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun configureLayout(compactWidth: Boolean) {
        if (narrow == compactWidth) return
        narrow = compactWidth
        main.orientation = if (compactWidth) VERTICAL else HORIZONTAL
        temperatureBlock.layoutParams = if (compactWidth) LayoutParams(-1, -2)
            else LayoutParams(0, -2, 1f)
        fanBlock.layoutParams = (if (compactWidth) LayoutParams(-1, -2)
            else LayoutParams(0, -2, 1f)).apply {
            if (compactWidth) topMargin = dp(12) else marginStart = dp(24)
        }
        val columns = if (compactWidth) 2 else 4
        if (columns > switches.columnCount) switches.columnCount = columns
        toggleActions.forEachIndexed { index, action ->
            controls.getValue(action).layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f)
            ).apply {
                width = 0
                height = dp(68)
                if (index % columns != 0) marginStart = dp(8)
                if (index >= columns) topMargin = dp(8)
            }
        }
        switches.columnCount = columns
    }

    fun render(snapshot: ClimateSnapshot?, enabled: Boolean) {
        val temp = snapshot?.get(ClimateField.TEMPERATURE)?.value
        val value = temp?.let { "$it°" } ?: "—"
        if (temperature.text.toString() != value) {
            temperature.text = SpannableString(value).apply {
                if (temp != null) {
                    setSpan(RelativeSizeSpan(0.56f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(SuperscriptSpan(), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            temperature.contentDescription = context.getString(R.string.climate_temperature_value, temp?.toString() ?: "—")
        }
        val wind = snapshot?.get(ClimateField.FAN)
        val fanText = wind?.value?.let { context.getString(R.string.climate_fan_level, it) } ?: "—"
        if (fan.text.toString() != fanText) fan.text = fanText
        fanLevels.update(wind, palette.control, palette.text)
        controls.forEach { (action, button) ->
            val state = field(action)?.let { snapshot?.get(it) }
            button.isEnabled = enabled && state?.enabled == true
            button.alpha = if (button.isEnabled) 1f else 0.4f
            if (action in toggleActions) {
                val selected = state?.on == true
                if (button.isSelected != selected) {
                    button.isSelected = selected
                    styleButton(action, button)
                }
                val stateText = when {
                    state?.value == null -> context.getString(R.string.climate_unknown)
                    action == WheelControlAction.CYCLE -> context.getString(
                        if (selected) R.string.climate_cycle_inside else R.string.climate_cycle_outside)
                    else -> context.getString(if (selected) R.string.climate_on else R.string.climate_off)
                }
                val heading = context.getString(if (action == WheelControlAction.POWER) R.string.climate_panel_title else action.label)
                val text = "$heading\n$stateText"
                if (button.text.toString() != text) button.text = SpannableString(text).apply {
                    setSpan(RelativeSizeSpan(0.8f), heading.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                button.contentDescription = "${context.getString(action.label)}, $stateText"
            }
        }
    }

    /** The host's effective night mode is the only appearance source, including ambient changes. */
    fun setNightMode(night: Boolean) {
        if (light == !night) return
        light = !night
        applyTheme()
    }

    private fun applyTheme() {
        palette = palette(light)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.surface).apply {
            cornerRadius = dp(32).toFloat()
            setStroke(dp(1), palette.rim)
        }
        handle.background = rounded(palette.handle, 4)
        temperatureBlock.background = rounded(palette.block, 24)
        fanBlock.background = rounded(palette.block, 24)
        temperature.setTextColor(palette.text)
        title.setTextColor(palette.text)
        title.compoundDrawableTintList = ColorStateList.valueOf(palette.secondary)
        secondaryLabels.forEach { it.setTextColor(palette.secondary) }
        theme.text = context.getString(if (light) R.string.climate_theme_dark else R.string.climate_theme_light)
        theme.contentDescription = context.getString(if (light) R.string.climate_switch_dark else R.string.climate_switch_light)
        theme.setCompoundDrawablesRelativeWithIntrinsicBounds(if (light) R.drawable.ic_climate_moon else R.drawable.ic_climate_sun, 0, 0, 0)
        theme.compoundDrawablePadding = dp(6)
        theme.compoundDrawableTintList = ColorStateList.valueOf(palette.text)
        theme.setTextColor(palette.text)
        theme.background = ripple(palette.control, 24)
        listOf(refreshButton, closeButton).forEach {
            it.compoundDrawableTintList = ColorStateList.valueOf(palette.secondary)
            it.background = ripple(Color.TRANSPARENT, 24)
        }
        controls.forEach(::styleButton)
        fanLevels.colors(palette.control, palette.text)
    }

    private fun styleButton(action: WheelControlAction, button: Button) {
        val selected = button.isSelected
        button.setTextColor(if (selected) Color.rgb(32, 32, 36) else palette.text)
        button.compoundDrawableTintList = ColorStateList.valueOf(if (selected) Color.rgb(10, 132, 255) else palette.secondary)
        button.background = ripple(if (selected) palette.selected else palette.control,
            if (action in toggleActions) 24 else 48)
    }

    private fun actionButton(action: WheelControlAction) = button(when (action) {
        WheelControlAction.TEMPERATURE_UP, WheelControlAction.FAN_UP -> "+"
        WheelControlAction.TEMPERATURE_DOWN, WheelControlAction.FAN_DOWN -> "−"
        else -> ""
    }) { command(action) }.apply {
        textSize = 23f
        contentDescription = context.getString(action.label)
        controls[action] = this
    }

    private fun iconButton(icon: Int, description: Int, click: () -> Unit) = button("", click).apply {
        contentDescription = context.getString(description)
        // A centered compound drawable retains Button keyboard/accessibility behavior.
        setPadding(dp(13), 0, dp(13), 0)
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    private fun button(title: String, click: () -> Unit) = Button(context).apply {
        text = title
        isAllCaps = false
        minimumWidth = 0
        minimumHeight = 0
        minWidth = 0
        minHeight = 0
        backgroundTintList = null
        setPadding(0, 0, 0, 0)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun controlLabel(title: Int, value: TextView) = row().apply {
        addView(label(context.getString(title), 14, secondary = true), LayoutParams(0, dp(24), 1f))
        addView(value)
    }
    private fun block(padded: Boolean = true) = LinearLayout(context).apply {
        orientation = VERTICAL
        if (padded) setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun label(title: String, size: Int, secondary: Boolean = false) = TextView(context).apply {
        text = title
        textSize = size.toFloat()
        gravity = Gravity.CENTER_VERTICAL
        if (secondary) secondaryLabels += this
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun ripple(color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(if (light) 0x200a84ff else 0x400a84ff), rounded(color, radius), rounded(Color.WHITE, radius))
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun icon(action: WheelControlAction) = when (action) {
        WheelControlAction.POWER -> R.drawable.ic_climate_power
        WheelControlAction.COMPRESSOR -> R.drawable.ic_climate_snow
        WheelControlAction.CYCLE -> R.drawable.ic_climate_cycle
        WheelControlAction.AUTO -> R.drawable.ic_climate_auto
        else -> error("No toggle icon for $action")
    }
    private fun field(action: WheelControlAction) = when (action) {
        WheelControlAction.TEMPERATURE_UP, WheelControlAction.TEMPERATURE_DOWN -> ClimateField.TEMPERATURE
        WheelControlAction.FAN_UP, WheelControlAction.FAN_DOWN -> ClimateField.FAN
        WheelControlAction.POWER -> ClimateField.POWER
        WheelControlAction.COMPRESSOR -> ClimateField.COMPRESSOR
        WheelControlAction.CYCLE -> ClimateField.CYCLE
        WheelControlAction.AUTO -> ClimateField.AUTO
        else -> null
    }

    private data class Palette(val surface: IntArray, val text: Int, val secondary: Int, val block: Int,
        val control: Int, val selected: Int, val rim: Int, val handle: Int)
    private fun palette(light: Boolean) = if (light) Palette(
        intArrayOf(0xfff7f3f7.toInt(), 0xffeaf0f8.toInt()), 0xff161618.toInt(), 0xff575761.toInt(),
        0xffe4e6ed.toInt(), 0xffd8dbe4.toInt(), Color.WHITE, 0xb3ffffff.toInt(), 0xffa3a3ad.toInt()
    ) else Palette(
        intArrayOf(0xff343139.toInt(), 0xff292f38.toInt()), 0xfff5f5f7.toInt(), 0xffb8b8c2.toInt(),
        0xff3a3a42.toInt(), 0xff494950.toInt(), 0xffeeeef2.toInt(), 0x38ffffff, 0xff777780.toInt()
    )
}

/** The firmware's actual ordered choices determine the segments; zero is the empty/off level. */
internal class ClimateFanLevels(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var choices: List<Int> = emptyList()
    private var value: Int? = null
    private var track = Color.TRANSPARENT
    private var fill = Color.TRANSPARENT

    fun update(reading: ClimateReading?, trackColor: Int, fillColor: Int) {
        if (choices != reading?.choices.orEmpty() || value != reading?.value || contentDescription == null) {
            choices = reading?.choices.orEmpty()
            value = reading?.value
            contentDescription = context.getString(R.string.climate_fan_value, value?.toString() ?: "—")
            invalidate()
        }
        colors(trackColor, fillColor)
    }

    fun colors(trackColor: Int, fillColor: Int) {
        if (track != trackColor || fill != fillColor) {
            track = trackColor
            fill = fillColor
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = track
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        val levels = choices.size - if (choices.firstOrNull() == 0) 1 else 0
        val current = value ?: return
        if (levels <= 0 || current == 0) return
        val active = choices.indexOf(current) + if (choices.firstOrNull() == 0) 0 else 1
        val inset = 12 * density
        val gap = minOf(4 * density, (width - 2 * inset) / levels * 0.25f)
        val segment = (width - 2 * inset - (levels - 1) * gap) / levels
        if (segment <= 0) return
        paint.color = fill
        val top = (height - 12 * density) / 2
        for (index in 0 until active) {
            val left = inset + index * (segment + gap)
            canvas.drawRoundRect(left, top, left + segment, top + 12 * density, 3 * density, 3 * density, paint)
        }
    }
}
