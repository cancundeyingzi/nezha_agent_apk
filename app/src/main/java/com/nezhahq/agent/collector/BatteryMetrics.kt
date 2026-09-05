package com.nezhahq.agent.collector

import kotlin.math.abs

/** Battery-side readings only; never combine a charger, fuel gauge and cell as separate packs. */
internal data class BatteryMetrics(
    val levelPercent: Double?,
    val temperatureCelsius: Double?,
    val powerWatts: Double?,
    val charging: Boolean?,
    val estimated: Boolean
)

internal object BatteryMetricsParser {
    fun parse(
        level: Int,
        scale: Int,
        temperatureTenths: Int?,
        voltageMillivolts: Int,
        currentMicroamps: Int?,
        charging: Boolean?,
        batteryUevent: String?
    ): BatteryMetrics {
        val fields = batteryUevent.orEmpty().lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1).trim()
        }.toMap()
        val isBattery = fields["POWER_SUPPLY_TYPE"] == "Battery" &&
            fields["POWER_SUPPLY_PRESENT"] != "0"
        fun number(key: String): Double? = if (isBattery) {
            fields["POWER_SUPPLY_$key"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
        } else null

        val percent = if (scale > 0 && level in 0..scale) level * 100.0 / scale
            else number("CAPACITY")?.takeIf { it in 0.0..100.0 }
        val temperature = temperatureTenths?.toDouble()?.div(10.0)
            ?.takeIf { it in -50.0..100.0 }
            ?: number("TEMP")?.div(10.0)?.takeIf { it in -50.0..100.0 }

        // Only the logical battery supply is read. bms/main/slave often alias this same battery.
        // POWER_NOW is already a power value: even on dual-cell phones it must never be doubled.
        val directPower = number("POWER_NOW")?.div(1_000_000.0)
            ?.let(::abs)?.takeIf { it <= 500.0 }
        val nodeVoltage = number("VOLTAGE_NOW")?.div(1_000_000.0)
        val nodeCurrent = number("CURRENT_NOW")?.div(1_000_000.0)
        val nodePower = calculatePower(nodeVoltage, nodeCurrent)
        val apiPower = calculatePower(
            voltageMillivolts / 1000.0,
            currentMicroamps?.takeUnless { it == Int.MIN_VALUE }?.div(1_000_000.0)
        )
        val nodeCharging = if (isBattery) when (fields["POWER_SUPPLY_STATUS"]) {
            "Charging" -> true
            "Discharging" -> false
            else -> null
        } else null
        return BatteryMetrics(
            percent, temperature, directPower ?: nodePower ?: apiPower,
            if (directPower != null || nodePower != null) nodeCharging ?: charging else charging,
            estimated = directPower == null
        )
    }

    private fun calculatePower(volts: Double?, amps: Double?): Double? {
        if (volts == null || amps == null || !volts.isFinite() || !amps.isFinite()) return null
        // Accept both single-cell and series-pack voltage, without guessing a cell-count factor.
        if (volts !in 2.0..20.0 || abs(amps) > 50.0) return null
        return abs(volts * amps).takeIf { it <= 500.0 }
    }
}
