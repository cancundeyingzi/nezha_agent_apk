package com.nezhahq.agent.collector

import org.junit.Assert.*
import org.junit.Test

class BatteryMetricsTest {
    private fun sample(
        voltage: Int = 4000, current: Int? = -1_000_000,
        node: String? = null, level: Int = 50, scale: Int = 100,
        temperature: Int? = 320
    ) = BatteryMetricsParser.parse(level, scale, temperature, voltage, current, false, node)

    @Test fun systemReadingsUseCorrectUnitsAndDirection() {
        val value = sample()
        assertEquals(50.0, value.levelPercent!!, 0.001)
        assertEquals(32.0, value.temperatureCelsius!!, 0.001)
        assertEquals(4.0, value.powerWatts!!, 0.001)
        assertEquals(false, value.charging)
        assertTrue(value.estimated)
        assertEquals(25.0, sample(level = 50, scale = 200).levelPercent!!, 0.001)
    }

    @Test fun seriesPackVoltageIsNotDoubledAgain() {
        assertEquals(8.0, sample(voltage = 8000).powerWatts!!, 0.001)
        assertEquals(4.0, sample(voltage = 4000).powerWatts!!, 0.001)
    }

    @Test fun logicalPackPowerTakesPriorityOverCellLikeVoltage() {
        val value = sample(node = """
            POWER_SUPPLY_TYPE=Battery
            POWER_SUPPLY_POWER_NOW=8000000
            POWER_SUPPLY_VOLTAGE_NOW=4000000
            POWER_SUPPLY_CURRENT_NOW=-1000000
            POWER_SUPPLY_STATUS=Charging
        """.trimIndent())
        assertEquals(8.0, value.powerWatts!!, 0.001)
        assertEquals(true, value.charging)
        assertFalse(value.estimated)
    }

    @Test fun nodeVoltageAndCurrentArePairedWithoutMixingApi() {
        val node = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_VOLTAGE_NOW=8000000\nPOWER_SUPPLY_CURRENT_NOW=-2000000"
        assertEquals(16.0, sample(node = node).powerWatts!!, 0.001)
        assertEquals(4.0, sample(node = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_VOLTAGE_NOW=8000000").powerWatts!!, 0.001)
    }

    @Test fun unsupportedAndMissingMeasurementsStayAbsent() {
        assertNull(sample(current = Int.MIN_VALUE).powerWatts)
        assertNull(sample(current = null).powerWatts)
        assertNull(sample(voltage = 0).powerWatts)
        assertNull(sample(level = -1, scale = 0).levelPercent)
        assertNull(sample(temperature = null).temperatureCelsius)
        assertNull(sample(current = Int.MAX_VALUE).powerWatts)
        assertEquals(0.0, sample(current = 0).powerWatts!!, 0.001)
    }

    @Test fun chargerAndAbsentSuppliesAreNeverCountedAsBatteryPower() {
        for (node in listOf("POWER_SUPPLY_TYPE=USB", "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_PRESENT=0")) {
            assertEquals(4.0, sample(node = "$node\nPOWER_SUPPLY_POWER_NOW=100000000").powerWatts!!, 0.001)
        }
    }

    @Test fun malformedOrNonStandardUnitsFallBackWithoutGuessingMultipliers() {
        for (power in listOf("NaN", "Infinity", "broken", "1000000000")) {
            assertEquals(4.0, sample(node = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_POWER_NOW=$power").powerWatts!!, 0.001)
        }
        assertEquals(4.0, sample(node = "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_VOLTAGE_NOW=4000\nPOWER_SUPPLY_CURRENT_NOW=1000").powerWatts!!, 0.001)
    }
}
