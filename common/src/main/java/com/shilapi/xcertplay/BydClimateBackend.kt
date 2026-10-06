package com.shilapi.xcertplay

import android.content.Context
import java.lang.reflect.InvocationTargetException

internal enum class ClimateField(val symbol: String, val constants: List<String> = emptyList()) {
    TEMPERATURE("AC_TEMP_MAIN"),
    FAN("AC_WIND_LEVEL"),
    POWER("AC_POWER_STATE", listOf("AC_POWER_OFF", "AC_POWER_ON")),
    COMPRESSOR("AC_COMPRESSOR_MODE", listOf("AC_COMPRESSOR_OFF", "AC_COMPRESSOR_ON")),
    CYCLE("AC_CYCLE_MODE", listOf("AC_CYCLEMODE_OUTLOOP", "AC_CYCLEMODE_INLOOP")),
    AUTO("AC_CTRL_MODE", listOf("AC_CTRLMODE_MANUAL", "AC_CTRLMODE_AUTO")),
}

internal enum class ClimateProblem { INTERFACE, PERMISSION, UNSUPPORTED, VALUE, UNIT, WRITE, UNCONFIRMED, CANCELLED, LIMIT }
internal class ClimateException(val problem: ClimateProblem, detail: String, cause: Throwable? = null) :
    Exception(detail, cause)

internal data class ClimateReading(
    val value: Int? = null,
    val choices: List<Int> = emptyList(),
    val writable: Boolean = false,
    val problem: ClimateProblem? = null,
    val detail: String? = null,
) {
    val enabled: Boolean get() = writable && value != null && problem == null
    val on: Boolean? get() = if (choices.size == 2 && value != null) value == choices[1] else null
}

internal data class ClimateSnapshot(val fields: Map<ClimateField, ClimateReading>) {
    operator fun get(field: ClimateField) = fields.getValue(field)
}

/** Single vehicle data source. IDs and state/range constants come from this firmware, never another car. */
internal interface ClimateBus {
    fun read(symbol: String): Int
    fun write(symbol: String, value: Int): Int
    fun hasWrite(symbol: String): Boolean
    fun constant(name: String): Int
    fun fanLevels(): List<Int>
}

internal class ReflectiveClimateBus(context: Context) : ClimateBus {
    private val context = context.applicationContext
    private val managerType by lazy { type("android.hardware.bydauto.BYDAutoDeviceManager") }
    private val manager by lazy {
        checked { managerType.getMethod("getInstance", Context::class.java).invoke(null, context) }
            ?: throw ClimateException(ClimateProblem.INTERFACE, "BYDAutoDeviceManager.getInstance returned null")
    }
    private val ids by lazy { type("android.hardware.bydauto.BYDAutoFeatureIds\$Ac") }
    private val ac by lazy { type("android.hardware.bydauto.ac.BYDAutoAcDevice") }
    private val intType = Int::class.javaPrimitiveType!!

    private fun type(name: String): Class<*> = checked { Class.forName(name) }
    private fun fid(symbol: String): Int = checked { ids.getField(symbol).getInt(null) }
    override fun constant(name: String): Int = checked { ac.getField(name).getInt(null) }
    override fun fanLevels(): List<Int> = checked {
        ac.fields.filter { Regex("AC_WINDLEVEL_[0-9]+").matches(it.name) }
            .map { it.getInt(null) }.filter { it >= 0 && it != 65535 }.distinct().sorted()
    }.also { if (it.isEmpty()) throw ClimateException(ClimateProblem.UNSUPPORTED, "No fan level constants") }

    override fun hasWrite(symbol: String): Boolean = checked { ids.fields.any { it.name == symbol } }
    override fun read(symbol: String): Int = checked {
        val id = fid(symbol)
        managerType.getMethod("getInt", intType, intType).invoke(manager, 0, id) as Int
    }
    override fun write(symbol: String, value: Int): Int = checked {
        val id = fid(symbol)
        managerType.getMethod("setInt", intType, intType, intType).invoke(manager, 0, id, value) as Int
    }

    private fun <T> checked(block: () -> T): T = try {
        block()
    } catch (error: ClimateException) {
        throw error
    } catch (error: Exception) {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val problem = when (cause) {
            is SecurityException -> ClimateProblem.PERMISSION
            is ClassNotFoundException -> ClimateProblem.INTERFACE
            is NoSuchFieldException, is NoSuchMethodException -> ClimateProblem.UNSUPPORTED
            else -> ClimateProblem.INTERFACE
        }
        throw ClimateException(problem, cause.toString(), cause)
    }
}

internal class BydClimateBackend(
    private val bus: ClimateBus,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun read(): ClimateSnapshot = ClimateSnapshot(ClimateField.entries.associateWith(::reading))

    private fun reading(field: ClimateField): ClimateReading = try {
        val choices = when (field) {
            ClimateField.TEMPERATURE -> {
                val unit = bus.read("AC_TEMPERATURE_UNIT")
                if (unit != bus.constant("AC_TEMPERATURE_UNIT_OC")) {
                    throw ClimateException(ClimateProblem.UNIT, "Temperature unit is not Celsius: $unit")
                }
                val min = bus.constant("AC_TEMP_IN_CELSIUS_MIN")
                val max = bus.constant("AC_TEMP_IN_CELSIUS_MAX")
                if (min < 0 || max < min || max - min > 100) {
                    throw ClimateException(ClimateProblem.VALUE, "Invalid temperature range: $min..$max")
                }
                (min..max).toList()
            }
            ClimateField.FAN -> bus.fanLevels()
            else -> field.constants.map(bus::constant).also {
                if (it.distinct().size != 2) throw ClimateException(ClimateProblem.VALUE, "Ambiguous ${field.symbol} states")
            }
        }
        val value = bus.read(field.symbol)
        if (value !in choices) throw ClimateException(ClimateProblem.VALUE, "${field.symbol} returned $value")
        val writable = bus.hasWrite("${field.symbol}_SET")
        ClimateReading(value, choices, writable,
            problem = if (writable) null else ClimateProblem.UNSUPPORTED,
            detail = if (writable) null else "Missing ${field.symbol}_SET")
    } catch (error: ClimateException) {
        ClimateReading(problem = error.problem, detail = error.message)
    }

    /** Read before every relative operation; a zero return only accepts the command, readback confirms it. */
    fun execute(action: WheelControlAction, allowed: () -> Boolean): ClimateSnapshot {
        checkAllowed(allowed)
        val field = when (action) {
            WheelControlAction.TEMPERATURE_UP, WheelControlAction.TEMPERATURE_DOWN -> ClimateField.TEMPERATURE
            WheelControlAction.FAN_UP, WheelControlAction.FAN_DOWN -> ClimateField.FAN
            WheelControlAction.POWER -> ClimateField.POWER
            WheelControlAction.COMPRESSOR -> ClimateField.COMPRESSOR
            WheelControlAction.CYCLE -> ClimateField.CYCLE
            WheelControlAction.AUTO -> ClimateField.AUTO
            else -> throw IllegalArgumentException("Not a climate command: $action")
        }
        val current = reading(field)
        if (!current.enabled) throw ClimateException(current.problem ?: ClimateProblem.UNSUPPORTED,
            current.detail ?: "${field.symbol} unavailable")
        val index = current.choices.indexOf(current.value)
        val delta = when (action) {
            WheelControlAction.TEMPERATURE_UP, WheelControlAction.FAN_UP -> 1
            WheelControlAction.TEMPERATURE_DOWN, WheelControlAction.FAN_DOWN -> -1
            else -> if (index == 0) 1 else -1
        }
        val target = current.choices[(index + delta).coerceIn(current.choices.indices)]
        if (target == current.value) throw ClimateException(ClimateProblem.LIMIT, "${field.symbol} is already at its limit")
        checkAllowed(allowed)
        val result = bus.write("${field.symbol}_SET", target)
        if (result != 0) throw ClimateException(ClimateProblem.WRITE, "${field.symbol}_SET returned $result")
        repeat(15) {
            checkAllowed(allowed)
            val confirmed = reading(field)
            if (confirmed.problem != null) throw ClimateException(confirmed.problem, confirmed.detail ?: field.symbol)
            if (confirmed.value == target) return read()
            pause(100)
        }
        throw ClimateException(ClimateProblem.UNCONFIRMED, "${field.symbol}: requested $target, not confirmed")
    }

    private fun checkAllowed(allowed: () -> Boolean) {
        if (!allowed() || Thread.currentThread().isInterrupted) {
            throw ClimateException(ClimateProblem.CANCELLED, "Climate panel/session no longer active")
        }
    }
}
