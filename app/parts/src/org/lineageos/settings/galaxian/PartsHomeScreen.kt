/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold

@Composable
fun PartsHomeScreen(onNavigate: (String) -> Unit) {
    PartsScaffold(title = stringResource(R.string.parts_title)) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 6.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                PartsInfoCard(
                    title = stringResource(R.string.parts_device_name),
                    body = stringResource(R.string.parts_device_chip),
                    icon = Icons.Filled.Smartphone,
                    accent = true,
                )
            }
            item {
                PartsPreferenceGroup(title = stringResource(R.string.parts_performance)) {
                    PartsPreference(
                        title = stringResource(R.string.parts_cpu_title),
                        summary = stringResource(R.string.parts_cpu_summary),
                        icon = Icons.Filled.Speed,
                        onClick = { onNavigate("cpu") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.gpu_settings_title),
                        summary = stringResource(R.string.gpu_home_summary),
                        icon = Icons.Filled.DeveloperBoard,
                        onClick = { onNavigate("gpu") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_adaptive_title),
                        summary = stringResource(R.string.parts_adaptive_summary),
                        icon = Icons.Filled.AutoAwesome,
                        onClick = { onNavigate("adaptive") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_memory_title),
                        summary = stringResource(R.string.parts_memory_summary),
                        icon = Icons.Filled.Memory,
                        onClick = { onNavigate("memory") },
                    )
                }
            }
            item {
                PartsPreferenceGroup(title = stringResource(R.string.parts_device_status)) {
                    PartsPreference(
                        title = stringResource(R.string.parts_temperature_title),
                        summary = stringResource(R.string.parts_temperature_summary),
                        icon = Icons.Filled.DeviceThermostat,
                        onClick = { onNavigate("temperature") },
                    )
                    PartsPreference(
                        title = stringResource(R.string.parts_battery_title),
                        summary = stringResource(R.string.parts_battery_summary),
                        icon = Icons.Filled.BatteryChargingFull,
                        onClick = { onNavigate("battery") },
                    )
                }
            }
        }
    }
}
