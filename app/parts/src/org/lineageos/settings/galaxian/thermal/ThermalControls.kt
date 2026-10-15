/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.galaxian.thermal

import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.galaxian.R
import org.lineageos.settings.galaxian.ui.PartsInfoCard
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsSwitchPreference
import org.lineageos.settings.galaxian.ui.rememberPartsResumed

@Composable
internal fun ThermalControls() {
    val context = LocalContext.current
    val resumed = rememberPartsResumed()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ThermalController.read()) }
    var busy by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var externalWaiting by remember { mutableStateOf(false) }
    var changingSince by remember { mutableStateOf(0L) }

    LaunchedEffect(resumed) {
        if (!resumed) {
            requestJob?.cancel()
            warning = false
            return@LaunchedEffect
        }
        while (true) {
            state = ThermalController.read()
            val now = SystemClock.elapsedRealtime()
            if (state.mode == "changing") {
                if (changingSince == 0L) changingSince = now
                externalWaiting = now - changingSince < 45_000
            } else {
                changingSince = 0L
                externalWaiting = false
            }
            delay(750)
        }
    }

    fun request(disabled: Boolean) {
        if (busy || !resumed) return
        busy = true
        message = null
        requestJob = scope.launch {
            try {
                val previousResult = ThermalController.read().resultId
                try {
                    withContext(Dispatchers.IO) { ThermalController.request(disabled) }
                } catch (_: RuntimeException) {
                    message = context.getString(R.string.thermal_request_failed)
                    return@launch
                }
                val deadline = SystemClock.elapsedRealtime() + 45_000
                var completed = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    state = ThermalController.read()
                    if (state.resultId != previousResult && state.mode != "changing") {
                        completed = true
                        if (!state.confirmed || state.disabled != disabled) {
                            message = context.getString(R.string.thermal_request_failed)
                        }
                        break
                    }
                    delay(200)
                }
                if (!completed) message = context.getString(R.string.thermal_no_ack)
            } finally {
                busy = false
            }
        }
    }

    val applying = busy || externalWaiting
    PartsPreferenceGroup(title = stringResource(R.string.thermal_section)) {
        if (state.confirmed) {
            PartsSwitchPreference(
                title = stringResource(R.string.thermal_title),
                summary = when {
                    applying -> stringResource(R.string.thermal_changing)
                    state.disabled -> stringResource(R.string.thermal_disabled_summary)
                    else -> stringResource(R.string.thermal_enabled_summary)
                },
                icon = Icons.Filled.DeviceThermostat,
                checked = state.disabled,
                enabled = resumed && !applying,
                onCheckedChange = { disabled ->
                    if (disabled) warning = true else request(false)
                },
            )
        } else {
            // Unknown is not equivalent to "throttling active". Keep both retry and
            // restore reachable when a previous request has failed or timed out.
            PartsPreference(
                title = stringResource(R.string.thermal_title),
                summary = stringResource(if (applying) R.string.thermal_changing else R.string.thermal_unknown_retry),
                icon = Icons.Filled.DeviceThermostat,
                enabled = resumed && !applying,
                onClick = { warning = true },
            )
        }
        if (state.disabled || !state.confirmed || state.error != "none") {
            PartsPreference(
                title = stringResource(R.string.thermal_restore),
                summary = stringResource(R.string.thermal_restore_summary),
                icon = Icons.Filled.RestartAlt,
                enabled = resumed && !applying,
                onClick = { request(false) },
            )
        }
        if (state.profile.isNotEmpty()) {
            PartsPreference(
                title = stringResource(R.string.thermal_active_profile),
                summary = state.profile,
            )
        }
    }
    if (message != null || (state.error != "none" && state.error.isNotEmpty())) {
        val reason = if (state.error.isEmpty() || state.error == "none") null
            else thermalErrorMessage(state)
        PartsInfoCard(
            title = stringResource(R.string.thermal_last_result),
            body = listOfNotNull(message, reason).joinToString("\n"),
            icon = Icons.Filled.Warning,
        )
    }
    if (warning) {
        AlertDialog(
            onDismissRequest = { warning = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.thermal_title)) },
            text = { Text(stringResource(R.string.thermal_confirmation)) },
            confirmButton = {
                TextButton(onClick = { warning = false; request(true) }) {
                    Text(stringResource(R.string.thermal_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { warning = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun thermalErrorMessage(state: ThermalState): String {
    val resource = when (state.error) {
        "current_denied", "profile_denied", "socket_denied" -> R.string.thermal_problem_permission
        "current_busy" -> R.string.thermal_problem_busy
        "current_missing", "socket_missing", "daemon_stopped" -> R.string.thermal_problem_service
        "current_invalid" -> R.string.thermal_problem_status
        "profile_missing", "profile_unsafe" -> R.string.thermal_problem_profile
        "snapshot_failed" -> R.string.thermal_problem_backup
        "socket_connect_failed", "socket_send_failed", "socket_timeout" -> R.string.thermal_problem_socket
        "apply_unconfirmed" -> R.string.thermal_problem_apply
        "restore_missing", "restore_failed" -> R.string.thermal_problem_restore
        else -> R.string.thermal_request_failed
    }
    val detail = if (state.errno == 0) state.error else "${state.error} (${state.errno})"
    return stringResource(resource) + "\n" + stringResource(R.string.thermal_diagnostic_code, detail)
}
