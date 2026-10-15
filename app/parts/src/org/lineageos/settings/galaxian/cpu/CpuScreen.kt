/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.cpu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.galaxian.R
import org.lineageos.settings.galaxian.thermal.ThermalControls
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsMetricCard
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold
import org.lineageos.settings.galaxian.ui.PartsSwitchPreference
import org.lineageos.settings.galaxian.ui.rememberPartsResumed
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CpuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resumed = rememberPartsResumed()
    var state by remember { mutableStateOf<CpuState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CpuPolicyState?>(null) }
    var restoreDialog by remember { mutableStateOf(false) }

    LaunchedEffect(resumed) {
        while (resumed) {
            if (!busy) state = withContext(Dispatchers.IO) { CpuControlSession.read { it.readState() } }
            delay(1000)
        }
    }

    fun runOperation(operation: (CpuControlManager) -> CpuResult) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val operationResult = withContext(Dispatchers.IO) { CpuControlSession.manual(operation) }
                state = withContext(Dispatchers.IO) { CpuControlSession.read { it.readState() } }
                val text = when (operationResult.failure) {
                    null -> R.string.cpu_verified
                    CpuFailure.LOCK_UNSUPPORTED -> R.string.cpu_kernel_required
                    CpuFailure.INVALID_FREQUENCY -> R.string.cpu_error_frequency
                    CpuFailure.INVALID_RANGE -> R.string.cpu_error_range
                    CpuFailure.LAST_CORE -> R.string.cpu_error_last_core
                    CpuFailure.WRITE_FAILED -> R.string.cpu_error_write
                    CpuFailure.READBACK_FAILED -> R.string.cpu_error_readback
                    else -> R.string.cpu_error_unavailable
                }
                result = context.getString(text).let {
                    if (operationResult.rollbackFailed) context.getString(R.string.cpu_error_rollback, it)
                    else it
                }
            } finally {
                busy = false
            }
        }
    }

    PartsScaffold(title = stringResource(R.string.cpu_settings_title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 6.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (id in listOf(0, 4)) {
                        val policy = state?.policies?.firstOrNull { it.id == id }
                        PartsMetricCard(
                            label = stringResource(if (id == 0) R.string.cpu_little_short else R.string.cpu_big_short),
                            value = policy?.current?.let { frequencyText(it) }
                                ?: stringResource(R.string.cpu_unavailable),
                            icon = Icons.Filled.Speed,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                PartsInfoCard(
                    title = stringResource(R.string.cpu_lock_info_title),
                    body = stringResource(R.string.cpu_lock_info),
                    icon = Icons.Filled.Lock,
                )
            }
            if (state?.policies?.any { !it.lockSupported } == true) {
                item {
                    PartsInfoCard(
                        title = stringResource(R.string.cpu_kernel_required),
                        body = stringResource(R.string.cpu_kernel_required_summary),
                        icon = Icons.Filled.Warning,
                    )
                }
            }
            for (id in listOf(0, 4)) {
                item(key = "policy_$id") {
                    val policy = state?.policies?.firstOrNull { it.id == id }
                    PartsPreferenceGroup(title = stringResource(
                        if (id == 0) R.string.cpu_efficiency_cluster else R.string.cpu_performance_cluster,
                    )) {
                        if (policy == null) {
                            PartsPreference(
                                title = stringResource(R.string.cpu_current_frequency),
                                summary = stringResource(if (state == null) R.string.cpu_loading else R.string.cpu_policy_unavailable),
                            )
                        } else {
                            PartsPreference(
                                title = stringResource(R.string.cpu_range_title),
                                summary = stringResource(R.string.cpu_range_value,
                                    frequencyText(policy.minimum), frequencyText(policy.maximum)),
                                icon = Icons.Filled.Tune,
                                onClick = { editing = policy },
                                enabled = !busy && policy.writable && policy.frequencies.isNotEmpty(),
                            )
                            PartsSwitchPreference(
                                title = stringResource(R.string.cpu_force_title),
                                summary = stringResource(when {
                                    !policy.lockSupported -> R.string.cpu_kernel_required
                                    policy.locked -> R.string.cpu_force_active
                                    else -> R.string.cpu_force_inactive
                                }),
                                icon = if (policy.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                checked = policy.locked,
                                enabled = !busy && policy.lockSupported,
                                onCheckedChange = { force -> runOperation { it.setFrequencyLock(id, force) } },
                            )
                        }
                    }
                }
            }
            item {
                PartsPreferenceGroup(title = stringResource(R.string.cpu_cores_title)) {
                    state?.cores?.forEach { core ->
                        PartsSwitchPreference(
                            title = stringResource(R.string.cpu_core_title, core.id),
                            summary = stringResource(when {
                                core.online == null -> R.string.cpu_unavailable
                                core.id == 0 -> R.string.cpu_core_required
                                core.online -> R.string.cpu_core_online
                                else -> R.string.cpu_core_offline
                            }),
                            icon = Icons.Filled.Memory,
                            checked = core.online == true,
                            enabled = !busy && core.canToggle,
                            onCheckedChange = { online -> runOperation { it.setCoreOnline(core.id, online) } },
                        )
                    }
                }
            }
            item { ThermalControls() }
            item {
                PartsPreferenceGroup {
                    PartsPreference(
                        title = stringResource(R.string.cpu_restore_title),
                        summary = stringResource(R.string.cpu_restore_summary),
                        icon = Icons.Filled.RestartAlt,
                        enabled = !busy && state?.policies?.isNotEmpty() == true,
                        onClick = { restoreDialog = true },
                    )
                }
            }
            if (busy || result != null) {
                item {
                    PartsInfoCard(
                        title = stringResource(R.string.cpu_operation_title),
                        body = if (busy) stringResource(R.string.cpu_applying) else result.orEmpty(),
                    )
                }
            }
        }
    }

    editing?.let { policy ->
        FrequencyRangeDialog(
            policy = policy,
            onDismiss = { editing = null },
            onApply = { minimum, maximum ->
                editing = null
                runOperation {
                    it.setFrequencyRange(policy.id, minimum, maximum, force = policy.lockSupported)
                }
            },
        )
    }
    if (restoreDialog) {
        AlertDialog(
            onDismissRequest = { restoreDialog = false },
            title = { Text(stringResource(R.string.cpu_restore_title)) },
            text = { Text(stringResource(R.string.cpu_restore_summary)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreDialog = false
                    runOperation { it.restoreInitialValues() }
                }) { Text(stringResource(R.string.cpu_restore_action)) }
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
private fun FrequencyRangeDialog(
    policy: CpuPolicyState,
    onDismiss: () -> Unit,
    onApply: (Long, Long) -> Unit,
) {
    val frequencies = policy.frequencies
    if (frequencies.isEmpty()) return
    fun index(value: Long) = frequencies.indices.minByOrNull { abs(frequencies[it] - value) } ?: 0
    var range by remember(policy.id) {
        mutableStateOf(index(policy.minimum).toFloat()..index(policy.maximum).toFloat())
    }
    val lower = range.start.roundToInt().coerceIn(frequencies.indices)
    val upper = range.endInclusive.roundToInt().coerceIn(frequencies.indices)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (policy.id == 0) R.string.cpu_efficiency_cluster else R.string.cpu_performance_cluster)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.cpu_range_value, frequencyText(frequencies[lower]), frequencyText(frequencies[upper])),
                    style = MaterialTheme.typography.titleMedium)
                if (frequencies.size > 1) {
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        valueRange = 0f..frequencies.lastIndex.toFloat(),
                        steps = (frequencies.size - 2).coerceAtLeast(0),
                    )
                }
                Text(stringResource(if (policy.lockSupported) R.string.cpu_apply_lock_summary else R.string.cpu_apply_temporary_summary),
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = { onApply(frequencies[lower], frequencies[upper]) }) {
                Text(stringResource(if (policy.lockSupported) R.string.cpu_apply_lock else R.string.cpu_apply_temporary))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun frequencyText(khz: Long): String = stringResource(R.string.cpu_frequency_mhz, khz / 1000)
