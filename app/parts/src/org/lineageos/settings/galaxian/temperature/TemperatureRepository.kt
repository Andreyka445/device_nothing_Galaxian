/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.temperature

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IThermalService
import android.os.PowerManager
import android.os.ServiceManager
import android.os.SystemClock
import android.os.Temperature
import java.io.File

/** A real measurement. IDs encode its source; missing or invalid readings remain null. */
data class TemperatureReading(
    val id: String,
    val name: String,
    val type: Int,
    val valueC: Float?,
    val severity: Int?,
)

data class TemperatureSnapshot(
    val sensors: List<TemperatureReading> = emptyList(),
    val thermalStatus: Int? = null,
    val sampledAtElapsed: Long = 0,
) {
    val batteryC: Float? get() = maximum(Temperature.TYPE_BATTERY)
    val cpuMaximumC: Float? get() = maximum(Temperature.TYPE_CPU, preferPhysicalZones = true)
    val gpuMaximumC: Float? get() = maximum(Temperature.TYPE_GPU, preferPhysicalZones = true)
    val skinMaximumC: Float? get() = maximum(Temperature.TYPE_SKIN)

    private fun maximum(type: Int, preferPhysicalZones: Boolean = false): Float? {
        val matching = sensors.filter { it.type == type && it.valueC != null }
        // Galaxian's HAL exposes the same soc_max aggregate as CPU/GPU/NPU/TPU/SOC.
        // Prefer explicitly identified hardware zones for CPU/GPU, if readable.
        val physical = if (preferPhysicalZones) matching.filter { it.id.startsWith("sysfs:") } else emptyList()
        return physical.ifEmpty { matching }.mapNotNull { it.valueC }.maxOrNull()
    }
}

/** Read from a worker thread. No receiver, timer, cached temperature, or write is retained here. */
class TemperatureRepository(context: Context) {
    private val context = context.applicationContext

    fun read(): TemperatureSnapshot {
        // Timestamp the start so a slow Binder/sysfs read is not presented as a fresh sample.
        val sampledAtElapsed = SystemClock.elapsedRealtime()
        val service = runCatching {
            IThermalService.Stub.asInterface(ServiceManager.getService(Context.THERMAL_SERVICE))
        }.getOrNull()
        val framework = runCatching { service?.currentTemperatures?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { TemperatureValues.isCelsiusType(it.type) }
            .map { sensor ->
                TemperatureReading(
                    id = "hal:${sensor.type}:${sensor.name}",
                    name = sensor.name,
                    type = sensor.type,
                    valueC = TemperatureValues.celsius(sensor.value),
                    severity = sensor.status.takeIf(Temperature::isValidStatus),
                )
            }.distinctBy { it.id }
        val kernel = readKernelZones()
        val sensors = (framework + kernel).toMutableList()
        if (sensors.none { it.type == Temperature.TYPE_BATTERY && it.valueC != null }) {
            readBatteryTemperature()?.let { value ->
                sensors += TemperatureReading("battery:broadcast", "battery", Temperature.TYPE_BATTERY, value, null)
            }
        }
        val status = runCatching { service?.currentThermalStatus }.getOrNull()
            ?: runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()
        return TemperatureSnapshot(
            sensors = sensors.sortedWith(compareBy<TemperatureReading> { it.type }.thenBy { it.id }),
            thermalStatus = status?.takeIf(Temperature::isValidStatus),
            sampledAtElapsed = sampledAtElapsed,
        )
    }

    private fun readKernelZones(): List<TemperatureReading> {
        val root = File("/sys/class/thermal")
        val zones = runCatching { root.listFiles()?.filter { ZONE_NAME.matches(it.name) }.orEmpty() }
            .getOrDefault(emptyList())
        return zones.sortedBy { it.name }.take(128).mapNotNull { zone ->
            val name = readSmallFile(File(zone, "type"))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TemperatureReading(
                id = "sysfs:${zone.name}",
                name = name,
                type = TemperatureValues.typeForGalaxianZone(name),
                valueC = TemperatureValues.millidegrees(readSmallFile(File(zone, "temp"))),
                severity = null, // Never infer a thermal severity from an arbitrary temperature.
            )
        }
    }

    private fun readBatteryTemperature(): Float? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching null
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ||
            !intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) return@runCatching null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        TemperatureValues.celsius(tenths / 10f)
    }.getOrNull()

    private fun readSmallFile(file: File): String? = runCatching {
        file.bufferedReader().use { it.readLine()?.take(128)?.trim() }
    }.getOrNull()

    private companion object {
        val ZONE_NAME = Regex("thermal_zone[0-9]+")
    }
}

/** Unit conversion and exact device-tree mappings, kept separate for host validation. */
internal object TemperatureValues {
    fun celsius(value: Float): Float? = value.takeIf { it.isFinite() && it in -40f..150f }

    fun millidegrees(raw: String?): Float? = raw?.trim()?.toLongOrNull()
        ?.takeIf { it in -40_000L..150_000L }?.let { celsius(it / 1000f) }

    fun isCelsiusType(type: Int): Boolean = Temperature.isValidType(type) && type !in setOf(
        Temperature.TYPE_BCL_VOLTAGE, Temperature.TYPE_BCL_CURRENT, Temperature.TYPE_BCL_PERCENTAGE,
    )

    fun typeForGalaxianZone(name: String): Int = when {
        name in CPU_ZONES -> Temperature.TYPE_CPU
        name == "gpu" -> Temperature.TYPE_GPU
        name == "battery" -> Temperature.TYPE_BATTERY
        name in setOf("ap_ntc", "shell_max") -> Temperature.TYPE_SKIN
        name in setOf("soc_max", "soc-top1", "soc-top2", "soc-bot1", "soc-bot2") -> Temperature.TYPE_SOC
        name in setOf("md1", "md2", "md3", "md4") -> Temperature.TYPE_MODEM
        name in setOf("nrpa_ntc", "ltepa_ntc") -> Temperature.TYPE_POWER_AMPLIFIER
        name == "mtk-master-charger" -> Temperature.TYPE_USB_PORT
        else -> Temperature.TYPE_UNKNOWN
    }

    // Exact zone names from mt6878.dts. Unrecognized names are not guessed from substrings.
    private val CPU_ZONES = setOf(
        "cpu-big-core0-1", "cpu-big-core0-2", "cpu-big-core1-1", "cpu-big-core1-2",
        "cpu-big-core2-1", "cpu-big-core2-2", "cpu-big-core3-1", "cpu-big-core3-2",
        "cpu-little-core0", "cpu-little-core1", "cpu-little-core2", "cpu-little-core3",
        "cpu-dsu-1", "cpu-dsu-2",
    )
}
