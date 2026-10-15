/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.battery

import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BatteryUnknown
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.lineageos.settings.galaxian.R
import org.lineageos.settings.galaxian.ui.Motion
import org.lineageos.settings.galaxian.ui.PartsCategoryTitle
import org.lineageos.settings.galaxian.ui.PartsGroupShape
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsMetricCard
import org.lineageos.settings.galaxian.ui.PartsPageGutter
import org.lineageos.settings.galaxian.ui.PartsScaffold

@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var snapshot by remember { mutableStateOf(BatterySnapshot()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(context, resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            snapshot = withContext(Dispatchers.IO) { readBatterySnapshot(context) }
            delay(2_000)
        }
    }

    val unavailable = stringResource(R.string.battery_unavailable)
    val temperature = snapshot.temperatureTenthsC?.let {
        stringResource(R.string.battery_celsius, it / 10.0)
    } ?: unavailable
    val voltage = snapshot.voltageMv?.let {
        stringResource(R.string.battery_volts, it / 1000.0)
    } ?: unavailable
    val current = snapshot.currentUa?.let {
        stringResource(R.string.battery_milliamps, it / 1000.0)
    } ?: unavailable
    val charge = snapshot.chargeUah?.let {
        stringResource(R.string.battery_milliamp_hours, it / 1000.0)
    } ?: unavailable
    val status = stringResource(statusLabel(snapshot.status))
    val health = stringResource(healthLabel(snapshot.health))
    val healthKnown = snapshot.health in setOf(
        BatteryManager.BATTERY_HEALTH_GOOD, BatteryManager.BATTERY_HEALTH_OVERHEAT,
        BatteryManager.BATTERY_HEALTH_DEAD, BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE,
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE, BatteryManager.BATTERY_HEALTH_COLD,
    )
    val healthGood = snapshot.health == BatteryManager.BATTERY_HEALTH_GOOD

    PartsScaffold(title = stringResource(R.string.battery_title), onBack = onBack) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item("battery_hero") {
                BatteryHero(snapshot.level, status, temperature,
                    snapshot.status == BatteryManager.BATTERY_STATUS_CHARGING)
            }
            item("live_section") { PartsCategoryTitle(stringResource(R.string.battery_live_section)) }
            item("temperature_voltage") {
                MetricRow(
                    Metric(stringResource(R.string.battery_temperature), temperature,
                        Icons.Filled.DeviceThermostat, temperatureTint(snapshot.temperatureTenthsC)),
                    Metric(stringResource(R.string.battery_voltage), voltage, Icons.Filled.ElectricBolt),
                )
            }
            item("current_charge") {
                MetricRow(
                    Metric(stringResource(R.string.battery_current), current, Icons.Filled.Bolt),
                    Metric(stringResource(R.string.battery_charge), charge, Icons.Filled.Memory),
                )
            }
            item("battery_condition") { PartsCategoryTitle(stringResource(R.string.battery_condition_section)) }
            item("status_health") {
                MetricRow(
                    Metric(stringResource(R.string.battery_status), status,
                        if (snapshot.status == BatteryManager.BATTERY_STATUS_CHARGING) {
                            Icons.Filled.BatteryChargingFull
                        } else Icons.Filled.BatteryFull),
                    Metric(stringResource(R.string.battery_health), health, when {
                        healthGood -> Icons.Filled.Favorite
                        healthKnown -> Icons.Filled.Dangerous
                        else -> Icons.AutoMirrored.Filled.BatteryUnknown
                    }, when {
                        healthGood -> MaterialTheme.colorScheme.primary
                        healthKnown -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }),
                )
            }
            item("battery_about") {
                PartsInfoCard(title = stringResource(R.string.battery_about),
                    body = stringResource(R.string.battery_about_summary))
            }
        }
    }
}

private data class Metric(val label: String, val value: String, val icon: ImageVector, val tint: Color? = null)

@Composable
private fun MetricRow(first: Metric, second: Metric) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PartsMetricCard(first.label, first.value, first.icon, Modifier.weight(1f),
            first.tint ?: MaterialTheme.colorScheme.primary)
        PartsMetricCard(second.label, second.value, second.icon, Modifier.weight(1f),
            second.tint ?: MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BatteryHero(level: Int?, status: String, temperature: String, charging: Boolean) {
    val progress by animateFloatAsState(
        targetValue = (level ?: 0) / 100f,
        animationSpec = Motion.defaultEffectsSpec(),
        label = "batteryLevel",
    )
    val color = when {
        level == null -> MaterialTheme.colorScheme.onSurfaceVariant
        level <= 20 -> MaterialTheme.colorScheme.error
        level <= 40 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (level == null) {
                    // No determinate progress semantics for an unknown battery level.
                    Surface(
                        modifier = Modifier.size(112.dp),
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = BorderStroke(12.dp, MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {}
                } else {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(112.dp),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeWidth = 12.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
                AnimatedContent(
                    targetState = if (level == null) 0 else if (charging) 1 else 2,
                    transitionSpec = {
                        fadeIn(Motion.defaultEffectsSpec()) togetherWith fadeOut(Motion.defaultEffectsSpec())
                    },
                    label = "batteryIcon",
                ) { state ->
                    Icon(
                        imageVector = when (state) {
                            0 -> Icons.AutoMirrored.Filled.BatteryUnknown
                            1 -> Icons.Filled.BatteryChargingFull
                            else -> Icons.Filled.BatteryFull
                        },
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = color,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = level?.let { stringResource(R.string.battery_percent, it) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (level == null) stringResource(R.string.battery_unavailable) else status,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
                Text(temperature, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun temperatureTint(tenthsC: Int?): Color = when {
    tenthsC == null -> MaterialTheme.colorScheme.onSurfaceVariant
    tenthsC < 300 -> MaterialTheme.colorScheme.primary
    tenthsC < 450 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun statusLabel(status: Int?): Int = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> R.string.battery_charging
    BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.battery_discharging
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.battery_not_charging
    BatteryManager.BATTERY_STATUS_FULL -> R.string.battery_full
    else -> R.string.battery_unavailable
}

private fun healthLabel(health: Int?): Int = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_good
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_overheat
    BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over_voltage
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.battery_health_failure
    BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold
    else -> R.string.battery_unavailable
}
