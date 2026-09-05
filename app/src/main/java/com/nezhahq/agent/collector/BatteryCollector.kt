package com.nezhahq.agent.collector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import proto.Nezha.State_SensorTemperature

internal class BatteryCollector(private val context: Context) {
    private val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    fun collect(batteryUevent: String?): List<State_SensorTemperature> {
        val intent = runCatching { context.registerReceiver(null, filter) }.getOrNull()
        if (intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false) return emptyList()
        fun extra(key: String): Int? = intent?.takeIf { it.hasExtra(key) }?.getIntExtra(key, 0)
        val status = extra(BatteryManager.EXTRA_STATUS)
        val metrics = BatteryMetricsParser.parse(
            level = extra(BatteryManager.EXTRA_LEVEL) ?: -1,
            scale = extra(BatteryManager.EXTRA_SCALE) ?: 0,
            temperatureTenths = extra(BatteryManager.EXTRA_TEMPERATURE),
            voltageMillivolts = extra(BatteryManager.EXTRA_VOLTAGE) ?: 0,
            currentMicroamps = runCatching {
                manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            }.getOrNull(),
            charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING -> false
                else -> null
            },
            batteryUevent = batteryUevent
        )
        return buildList {
            fun addMetric(name: String, value: Double?) {
                if (value != null) add(State_SensorTemperature.newBuilder()
                    .setName(name).setTemperature(value).build())
            }
            addMetric("Battery", metrics.temperatureCelsius)
            addMetric("电池电量 (%)", metrics.levelPercent)
            val direction = when (metrics.charging) {
                true -> "充电"
                false -> "放电"
                null -> "净"
            }
            val estimate = if (metrics.estimated) "·估算" else ""
            addMetric("电池${direction}功率$estimate (W)", metrics.powerWatts)
        }
    }
}
