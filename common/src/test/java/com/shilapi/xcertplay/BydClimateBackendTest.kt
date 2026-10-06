package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class BydClimateBackendTest {
    private val bus = ClimateTestBus()
    private val backend = BydClimateBackend(bus) { }

    private fun failure(expected: ClimateProblem, block: () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (error: ClimateException) { assertEquals(expected, error.problem) }
    }

    @Test fun relativeTemperatureReadsTheCarAgainInsteadOfUsingThePanelSnapshot() {
        assertEquals(22, backend.read()[ClimateField.TEMPERATURE].value)
        bus.values["AC_TEMP_MAIN"] = 26
        val actual = backend.execute(WheelControlAction.TEMPERATURE_UP) { true }
        assertEquals(listOf("AC_TEMP_MAIN_SET" to 27), bus.writes)
        assertEquals(27, actual[ClimateField.TEMPERATURE].value)
    }

    @Test fun compressorDoesNotSwitchTheWholeClimateSystem() {
        backend.execute(WheelControlAction.COMPRESSOR) { true }
        assertEquals(listOf("AC_COMPRESSOR_MODE_SET" to 1), bus.writes)
        assertEquals(1, bus.values["AC_POWER_STATE"])
    }

    @Test fun cyclesUseFirmwareConstantsEvenWhenZeroMeansOn() {
        bus.constants["AC_CYCLEMODE_INLOOP"] = 0
        bus.constants["AC_CYCLEMODE_OUTLOOP"] = 1
        bus.values["AC_CYCLE_MODE"] = 1
        val actual = backend.execute(WheelControlAction.CYCLE) { true }
        assertEquals(0, bus.writes.single().second)
        assertEquals(true, actual[ClimateField.CYCLE].on)
    }

    @Test fun autoIsNotMistakenForManualWhenItsCodeIsZero() {
        val actual = backend.execute(WheelControlAction.AUTO) { true }
        assertEquals("AC_CTRL_MODE_SET" to 0, bus.writes.single())
        assertEquals(true, actual[ClimateField.AUTO].on)
    }

    @Test fun acceptedButUnchangedCommandFailsWithoutRetryingTheWrite() {
        bus.applyWrites = false
        failure(ClimateProblem.UNCONFIRMED) { backend.execute(WheelControlAction.FAN_UP) { true } }
        assertEquals(1, bus.writes.size)
        assertEquals(3, bus.values["AC_WIND_LEVEL"])
    }

    @Test fun rejectedCommandReportsFailure() {
        bus.result = -5
        failure(ClimateProblem.WRITE) { backend.execute(WheelControlAction.POWER) { true } }
        assertEquals(1, bus.values["AC_POWER_STATE"])
    }

    @Test fun missingCompressorSetterNeverFallsBackToClimatePower() {
        bus.missingWrites += "AC_COMPRESSOR_MODE_SET"
        failure(ClimateProblem.UNSUPPORTED) { backend.execute(WheelControlAction.COMPRESSOR) { true } }
        assertTrue(bus.writes.isEmpty())
        assertTrue(backend.read()[ClimateField.POWER].enabled)
    }

    @Test fun unknownStateDoesNotBecomeAUsableDefault() {
        bus.values["AC_TEMP_MAIN"] = 65535
        val actual = backend.read()
        assertNull(actual[ClimateField.TEMPERATURE].value)
        assertFalse(actual[ClimateField.TEMPERATURE].enabled)
        assertTrue(actual[ClimateField.FAN].enabled)
        failure(ClimateProblem.VALUE) { backend.execute(WheelControlAction.TEMPERATURE_DOWN) { true } }
        assertTrue(bus.writes.isEmpty())
    }

    @Test fun otherUnitsAreExplicitlyUnsupported() {
        bus.values["AC_TEMPERATURE_UNIT"] = 2
        failure(ClimateProblem.UNIT) { backend.execute(WheelControlAction.TEMPERATURE_UP) { true } }
        assertTrue(bus.writes.isEmpty())
    }

    @Test fun leavingThePanelDuringFreshReadPreventsTheWrite() {
        var active = true
        bus.onRead = { if (it == "AC_TEMP_MAIN") active = false }
        failure(ClimateProblem.CANCELLED) { backend.execute(WheelControlAction.TEMPERATURE_UP) { active } }
        assertTrue(bus.writes.isEmpty())
    }

    @Test fun disconnectDuringConfirmationIsCancelledRatherThanReportedSuccessful() {
        var active = true
        bus.onWrite = { active = false }
        failure(ClimateProblem.CANCELLED) { backend.execute(WheelControlAction.FAN_UP) { active } }
        assertEquals(1, bus.writes.size)
    }

    @Test fun limitsAreReportedWithoutWritingAnotherValue() {
        bus.values["AC_TEMP_MAIN"] = 33
        failure(ClimateProblem.LIMIT) { backend.execute(WheelControlAction.TEMPERATURE_UP) { true } }
        assertTrue(bus.writes.isEmpty())
    }
}

internal class ClimateTestBus : ClimateBus {
    val constants = mutableMapOf(
        "AC_POWER_OFF" to 0, "AC_POWER_ON" to 1,
        "AC_COMPRESSOR_OFF" to 0, "AC_COMPRESSOR_ON" to 1,
        "AC_CYCLEMODE_OUTLOOP" to 0, "AC_CYCLEMODE_INLOOP" to 1,
        "AC_CTRLMODE_MANUAL" to 1, "AC_CTRLMODE_AUTO" to 0,
        "AC_TEMPERATURE_UNIT_OC" to 1, "AC_TEMP_IN_CELSIUS_MIN" to 17, "AC_TEMP_IN_CELSIUS_MAX" to 33,
    )
    val values = mutableMapOf("AC_TEMP_MAIN" to 22, "AC_WIND_LEVEL" to 3, "AC_POWER_STATE" to 1,
        "AC_COMPRESSOR_MODE" to 0, "AC_CYCLE_MODE" to 0, "AC_CTRL_MODE" to 1, "AC_TEMPERATURE_UNIT" to 1)
    val writes = mutableListOf<Pair<String, Int>>()
    val missingWrites = mutableSetOf<String>()
    var applyWrites = true
    var result = 0
    var onRead: (String) -> Unit = {}
    var onWrite: () -> Unit = {}
    override fun read(symbol: String): Int { onRead(symbol); return values.getValue(symbol) }
    override fun write(symbol: String, value: Int): Int {
        writes += symbol to value
        if (applyWrites && result == 0) values[symbol.removeSuffix("_SET")] = value
        onWrite()
        return result
    }
    override fun hasWrite(symbol: String) = symbol !in missingWrites
    override fun constant(name: String) = constants.getValue(name)
    override fun fanLevels() = (0..7).toList()
}
