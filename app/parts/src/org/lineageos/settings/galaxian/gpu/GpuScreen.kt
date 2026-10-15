/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.gpu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.galaxian.R
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsMetricCard
import org.lineageos.settings.galaxian.ui.PartsPageGutter
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold
import org.lineageos.settings.galaxian.ui.rememberPartsResumed

@Composable
fun GpuScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resumed = rememberPartsResumed()
    var state by remember { mutableStateOf<GpuState?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<GpuResult?>(null) }
    var editing by remember { mutableStateOf<GpuState?>(null) }
    var restoreDialog by remember { mutableStateOf(false) }

    LaunchedEffect(resumed) {
        if (!resumed) {
            editing = null
            restoreDialog = false
            return@LaunchedEffect
        }
        while (isActive) {
            if (!busy) {
                try {
                    state = withContext(Dispatchers.IO) { GpuControlSession.snapshot().state }
                    readFailed = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not keep displaying a previous sample as a current GPU reading.
                    state = null
                    readFailed = true
                }
            }
            delay(1_000)
        }
    }

    fun runOperation(operation: () -> GpuResult) {
        if (!resumed || busy) return
        busy = true
        result = null
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { operation() }
                state = withContext(Dispatchers.IO) { GpuControlSession.snapshot().state }
                readFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = GpuResult(GpuFailure.UNAVAILABLE)
                state = null
                readFailed = true
            } finally {
                busy = false
            }
        }
    }

    val current = state
    val unavailable = stringResource(if (current == null && !readFailed) {
        R.string.gpu_loading
    } else R.string.gpu_unavailable)
    PartsScaffold(title = stringResource(R.string.gpu_settings_title), onBack = onBack) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 6.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item("readings") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = PartsPageGutter, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PartsMetricCard(
                        label = stringResource(R.string.gpu_current_frequency),
                        value = when {
                            current?.active == false -> stringResource(R.string.gpu_idle)
                            current?.currentKHz != null -> frequencyText(current.currentKHz)
                            else -> unavailable
                        },
                        icon = Icons.Filled.Speed,
                        modifier = Modifier.weight(1f),
                    )
                    PartsMetricCard(
                        label = stringResource(R.string.gpu_control_state),
                        value = when (current?.controlMode) {
                            GpuMode.FIXED -> current.fixedKHz?.let { frequencyText(it) } ?: unavailable
                            GpuMode.RANGE -> stringResource(R.string.gpu_mode_range)
                            GpuMode.AUTOMATIC -> stringResource(R.string.gpu_mode_automatic)
                            GpuMode.UNKNOWN -> stringResource(R.string.gpu_mode_unknown)
                            null -> unavailable
                        },
                        icon = Icons.Filled.Tune,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item("controls") {
                PartsPreferenceGroup {
                    PartsPreference(
                        title = stringResource(R.string.gpu_control_state),
                        summary = when (current?.controlMode) {
                            GpuMode.AUTOMATIC -> stringResource(R.string.gpu_mode_automatic)
                            GpuMode.FIXED -> stringResource(R.string.gpu_mode_fixed)
                            GpuMode.RANGE -> stringResource(R.string.gpu_mode_range)
                            GpuMode.UNKNOWN -> stringResource(R.string.gpu_mode_unknown)
                            null -> unavailable
                        },
                        icon = Icons.Filled.DeveloperBoard,
                    )
                    PartsPreference(
                        title = stringResource(R.string.gpu_requested_range),
                        summary = when (current?.controlMode) {
                            GpuMode.AUTOMATIC -> stringResource(R.string.gpu_mode_automatic)
                            GpuMode.FIXED -> stringResource(R.string.gpu_mode_fixed)
                            GpuMode.RANGE -> rangeText(current.requestedMinimumKHz, current.requestedMaximumKHz)
                            else -> unavailable
                        },
                    )
                    PartsPreference(
                        title = stringResource(R.string.gpu_effective_range),
                        summary = rangeText(current?.effectiveMinimumKHz, current?.effectiveMaximumKHz),
                    )
                    PartsPreference(
                        title = stringResource(if (current?.writable == true) {
                            R.string.gpu_choose_range
                        } else R.string.gpu_supported_frequencies),
                        summary = current?.frequenciesKHz?.takeIf { it.isNotEmpty() }?.let {
                            stringResource(R.string.gpu_frequency_count, it.size)
                        } ?: unavailable,
                        icon = Icons.Filled.Tune,
                        enabled = resumed && !busy && current?.frequenciesKHz?.isNotEmpty() == true,
                        onClick = { editing = current },
                    )
                    PartsPreference(
                        title = stringResource(R.string.gpu_restore_automatic),
                        summary = stringResource(R.string.gpu_restore_summary),
                        icon = Icons.Filled.RestartAlt,
                        // Recovery stays available independently of the OPP table and fixed mode.
                        enabled = resumed && !busy && current?.canRestore == true,
                        onClick = { restoreDialog = true },
                    )
                }
            }
            if (readFailed || current?.writable == false || current?.readable == false) {
                item("unavailable") {
                    PartsInfoCard(
                        body = stringResource(if (readFailed) R.string.gpu_error_unavailable else {
                            current?.unavailableReason?.let(::failureLabel)
                                ?: R.string.gpu_control_unavailable
                        }),
                        icon = Icons.Filled.Warning,
                    )
                }
            }
            if (current?.controlMode == GpuMode.FIXED) {
                item("legacy_fixed") {
                    PartsInfoCard(body = stringResource(R.string.gpu_legacy_fixed_summary),
                        icon = Icons.Filled.Warning)
                }
            }
            item("effective_explanation") {
                PartsInfoCard(body = stringResource(R.string.gpu_effective_summary))
            }
            item("session") {
                PartsInfoCard(
                    title = stringResource(R.string.gpu_session_title),
                    body = stringResource(R.string.gpu_session_summary),
                    icon = Icons.Filled.DeveloperBoard,
                )
            }
            if (busy || result != null) {
                item("result") {
                    val operationResult = result
                    val message = if (busy) stringResource(R.string.gpu_applying) else {
                        val base = stringResource(operationResult?.failure?.let(::failureLabel)
                            ?: R.string.gpu_verified)
                        if (operationResult?.rollbackFailed == true) {
                            stringResource(R.string.gpu_error_rollback, base)
                        } else base
                    }
                    PartsInfoCard(title = stringResource(R.string.gpu_operation_title), body = message)
                }
            }
        }
    }

    editing?.let { gpu ->
        GpuRangeDialog(
            gpu = gpu,
            onDismiss = { editing = null },
            onApply = { minimum, maximum ->
                editing = null
                runOperation { GpuControlSession.manual { it.setFrequencyRange(minimum, maximum) } }
            },
        )
    }
    if (restoreDialog) {
        AlertDialog(
            onDismissRequest = { restoreDialog = false },
            title = { Text(stringResource(R.string.gpu_restore_automatic)) },
            text = { Text(stringResource(R.string.gpu_restore_summary)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreDialog = false
                    runOperation { GpuControlSession.manual { it.restoreAutomatic() } }
                }) { Text(stringResource(R.string.gpu_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GpuRangeDialog(gpu: GpuState, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    val frequencies = remember(gpu.frequenciesKHz) { gpu.frequenciesKHz.distinct().sorted() }
    if (frequencies.isEmpty()) return
    fun index(value: Long) = frequencies.indices.minByOrNull { abs(frequencies[it] - value) } ?: 0
    val initialMin = gpu.requestedMinimumKHz ?: gpu.fixedKHz ?: frequencies.first()
    val initialMax = gpu.requestedMaximumKHz ?: gpu.fixedKHz ?: frequencies.last()
    var range by remember(frequencies, initialMin, initialMax) {
        mutableStateOf(index(initialMin).toFloat()..index(initialMax).toFloat())
    }
    val lower = range.start.roundToInt().coerceIn(frequencies.indices)
    val upper = range.endInclusive.roundToInt().coerceIn(frequencies.indices)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (gpu.writable) R.string.gpu_choose_range
                else R.string.gpu_supported_frequencies))
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                if (gpu.writable) {
                    item("range") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.gpu_minimum_frequency),
                                style = MaterialTheme.typography.labelLarge)
                            Text(frequencyText(frequencies[lower]), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.gpu_maximum_frequency),
                                style = MaterialTheme.typography.labelLarge)
                            Text(frequencyText(frequencies[upper]), style = MaterialTheme.typography.titleMedium)
                            if (frequencies.size > 1) {
                                RangeSlider(
                                    value = range,
                                    onValueChange = { range = it },
                                    valueRange = 0f..frequencies.lastIndex.toFloat(),
                                    steps = (frequencies.size - 2).coerceAtLeast(0),
                                )
                            }
                        }
                    }
                }
                item("explanation") {
                    Text(stringResource(if (gpu.writable) R.string.gpu_range_summary
                        else R.string.gpu_control_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
                if (!gpu.writable) {
                    items(frequencies, key = { it }) { frequency ->
                        PartsPreference(title = frequencyText(frequency))
                    }
                }
            }
        },
        confirmButton = {
            if (gpu.writable) {
                Button(onClick = { onApply(frequencies[lower], frequencies[upper]) }) {
                    Text(stringResource(R.string.gpu_apply_range))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = {
            if (gpu.writable) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun rangeText(minimum: Long?, maximum: Long?): String = if (minimum != null && maximum != null) {
    stringResource(R.string.gpu_range_value, frequencyText(minimum), frequencyText(maximum))
} else stringResource(R.string.gpu_unavailable)

private fun failureLabel(failure: GpuFailure): Int = when (failure) {
    GpuFailure.UNAVAILABLE -> R.string.gpu_error_unavailable
    GpuFailure.UNSUPPORTED -> R.string.gpu_error_unsupported
    GpuFailure.INVALID_FREQUENCY -> R.string.gpu_error_frequency
    GpuFailure.INVALID_RANGE -> R.string.gpu_error_range
    GpuFailure.CONFLICT -> R.string.gpu_error_conflict
    GpuFailure.WRITE_FAILED -> R.string.gpu_error_write
    GpuFailure.READBACK_FAILED -> R.string.gpu_error_readback
}

@Composable
private fun frequencyText(khz: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }
    }
    return stringResource(R.string.gpu_frequency_mhz, format.format(khz / 1_000.0))
}
