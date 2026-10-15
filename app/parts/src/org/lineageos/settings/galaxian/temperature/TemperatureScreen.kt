/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.temperature

import android.os.Temperature
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.lineageos.settings.galaxian.R
import org.lineageos.settings.galaxian.ui.PartsCategoryTitle
import org.lineageos.settings.galaxian.ui.PartsGroupShape
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsMetricCard
import org.lineageos.settings.galaxian.ui.PartsPageGutter
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold

private data class HistorySample(val time: Long, val cpu: Float?, val battery: Float?)

@Composable
fun TemperatureScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { TemperatureRepository(context) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var snapshot by remember { mutableStateOf(TemperatureSnapshot()) }
    var history by remember { mutableStateOf(emptyList<HistorySample>()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository, resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            val reading = withContext(Dispatchers.IO) { repository.read() }
            snapshot = reading
            history = (history + HistorySample(reading.sampledAtElapsed, reading.cpuMaximumC, reading.batteryC))
                .filter { it.time >= reading.sampledAtElapsed - 120_000 }
                .takeLast(60)
            delay(2_000)
        }
    }

    PartsScaffold(title = stringResource(R.string.temperature_title), onBack = onBack) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item("status") {
                PartsInfoCard(
                    title = stringResource(R.string.temperature_system_status),
                    body = stringResource(severityLabel(snapshot.thermalStatus)),
                    icon = Icons.Filled.DeviceThermostat,
                    accent = snapshot.thermalStatus?.let { it >= Temperature.THROTTLING_SEVERE } == true,
                )
            }
            item("live") { PartsCategoryTitle(stringResource(R.string.temperature_live)) }
            item("cpu_gpu") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PartsMetricCard(stringResource(R.string.temperature_cpu_max), temperatureText(snapshot.cpuMaximumC),
                        Icons.Filled.Memory, Modifier.weight(1f))
                    PartsMetricCard(stringResource(R.string.temperature_gpu_max), temperatureText(snapshot.gpuMaximumC),
                        Icons.Filled.Memory, Modifier.weight(1f))
                }
            }
            item("battery_skin") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PartsMetricCard(stringResource(R.string.temperature_battery), temperatureText(snapshot.batteryC),
                        Icons.Filled.BatteryFull, Modifier.weight(1f))
                    PartsMetricCard(stringResource(R.string.temperature_skin_max), temperatureText(snapshot.skinMaximumC),
                        Icons.Filled.Sensors, Modifier.weight(1f))
                }
            }
            item("history") { TemperatureHistory(history) }
            if (snapshot.sensors.isEmpty()) {
                item("empty") {
                    PartsInfoCard(body = stringResource(if (snapshot.sampledAtElapsed == 0L)
                        R.string.temperature_loading else R.string.temperature_no_sensors))
                }
            }
            val framework = snapshot.sensors.filter { it.id.startsWith("hal:") }
            val physical = snapshot.sensors.filter { it.id.startsWith("sysfs:") }
            val batteryFallback = snapshot.sensors.filter { it.id.startsWith("battery:") }
            if (framework.isNotEmpty()) {
                item("framework") { SensorGroup(stringResource(R.string.temperature_framework), framework) }
            }
            if (physical.isNotEmpty()) {
                item("hardware") { SensorGroup(stringResource(R.string.temperature_hardware), physical) }
            }
            if (batteryFallback.isNotEmpty()) {
                item("battery_fallback") { SensorGroup(stringResource(R.string.temperature_broadcast), batteryFallback) }
            }
            item("explanation") {
                PartsInfoCard(
                    title = stringResource(R.string.temperature_sources_title),
                    body = stringResource(R.string.temperature_sources_summary),
                )
            }
        }
    }
}

@Composable
private fun SensorGroup(title: String, sensors: List<TemperatureReading>) {
    PartsPreferenceGroup(title = title) {
        sensors.forEach { sensor ->
            val type = stringResource(typeLabel(sensor.type))
            val summary = sensor.severity?.let {
                stringResource(R.string.temperature_sensor_summary, type, stringResource(severityLabel(it)))
            } ?: type
            PartsPreference(
                title = if (sensor.id == "battery:broadcast") {
                    stringResource(R.string.temperature_battery)
                } else sensor.name,
                summary = summary,
                icon = Icons.Filled.DeviceThermostat,
                trailing = {
                    Text(temperatureText(sensor.valueC), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
private fun TemperatureHistory(history: List<HistorySample>) {
    val cpuColor = MaterialTheme.colorScheme.primary
    val batteryColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val values = history.flatMap { listOfNotNull(it.cpu, it.battery) }
    val observedMin = values.minOrNull()
    val observedMax = values.maxOrNull()
    val description = if (observedMin != null && observedMax != null)
        stringResource(R.string.temperature_history_range, observedMin, observedMax) else
        stringResource(R.string.temperature_history_waiting)
    PartsCategoryTitle(stringResource(R.string.temperature_history_title))
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.temperature_cpu_max), color = cpuColor,
                    style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.temperature_battery), color = batteryColor,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (history.size >= 2 && values.isNotEmpty()) {
                val low = (observedMin ?: 0f) - 2f
                val high = (observedMax ?: 0f) + 2f
                val start = history.first().time
                val span = (history.last().time - start).coerceAtLeast(1L)
                Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }) {
                    for (fraction in listOf(0f, 0.5f, 1f)) {
                        drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                    }
                    fun plot(color: Color, read: (HistorySample) -> Float?) {
                        val path = Path()
                        var connected = false
                        var previousTime: Long? = null
                        history.forEach { sample ->
                            val value = read(sample)
                            if (value == null) {
                                connected = false
                            } else {
                                val x = size.width * ((sample.time - start).toFloat() / span)
                                val y = size.height * (1 - (value - low) / (high - low))
                                // Do not bridge a background pause or missing measurements.
                                if (connected && previousTime != null && sample.time - previousTime!! <= 5_000)
                                    path.lineTo(x, y) else path.moveTo(x, y)
                                connected = true
                                drawCircle(color, radius = 2.dp.toPx(), center = Offset(x, y))
                            }
                            previousTime = sample.time
                        }
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                    }
                    plot(cpuColor) { it.cpu }
                    plot(batteryColor) { it.battery }
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.temperature_history_summary), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun temperatureText(value: Float?): String = value?.let {
    stringResource(R.string.temperature_celsius, it)
} ?: stringResource(R.string.temperature_unavailable)

private fun severityLabel(severity: Int?): Int = when (severity) {
    Temperature.THROTTLING_NONE -> R.string.temperature_severity_none
    Temperature.THROTTLING_LIGHT -> R.string.temperature_severity_light
    Temperature.THROTTLING_MODERATE -> R.string.temperature_severity_moderate
    Temperature.THROTTLING_SEVERE -> R.string.temperature_severity_severe
    Temperature.THROTTLING_CRITICAL -> R.string.temperature_severity_critical
    Temperature.THROTTLING_EMERGENCY -> R.string.temperature_severity_emergency
    Temperature.THROTTLING_SHUTDOWN -> R.string.temperature_severity_shutdown
    else -> R.string.temperature_unavailable
}

private fun typeLabel(type: Int): Int = when (type) {
    Temperature.TYPE_CPU -> R.string.temperature_type_cpu
    Temperature.TYPE_GPU -> R.string.temperature_type_gpu
    Temperature.TYPE_BATTERY -> R.string.temperature_battery
    Temperature.TYPE_SKIN -> R.string.temperature_type_skin
    Temperature.TYPE_USB_PORT -> R.string.temperature_type_usb
    Temperature.TYPE_POWER_AMPLIFIER -> R.string.temperature_type_radio
    Temperature.TYPE_NPU -> R.string.temperature_type_npu
    Temperature.TYPE_TPU -> R.string.temperature_type_tpu
    Temperature.TYPE_DISPLAY -> R.string.temperature_type_display
    Temperature.TYPE_MODEM -> R.string.temperature_type_modem
    Temperature.TYPE_SOC -> R.string.temperature_type_soc
    Temperature.TYPE_WIFI -> R.string.temperature_type_wifi
    Temperature.TYPE_CAMERA -> R.string.temperature_type_camera
    Temperature.TYPE_FLASHLIGHT -> R.string.temperature_type_flash
    Temperature.TYPE_SPEAKER -> R.string.temperature_type_speaker
    Temperature.TYPE_AMBIENT -> R.string.temperature_type_ambient
    Temperature.TYPE_POGO -> R.string.temperature_type_pogo
    else -> R.string.temperature_type_unknown
}
