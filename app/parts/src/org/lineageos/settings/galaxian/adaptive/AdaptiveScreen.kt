/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.lineageos.settings.galaxian.ui.PartsSwitchPreference
import org.lineageos.settings.galaxian.ui.rememberPartsResumed

@Composable
fun AdaptiveScreen(
    onBack: () -> Unit,
    proposalToReview: String? = null,
    onReviewRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        AdaptiveRepository.get(context.applicationContext)
    }
    val resumed = rememberPartsResumed()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<AdaptiveSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var choosingObjective by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var reviewingId by rememberSaveable { mutableStateOf<String?>(null) }
    var decisionId by remember { mutableStateOf<String?>(null) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(proposalToReview) {
        proposalToReview?.let {
            reviewingId = it
            selectedTab = 0
            onReviewRequestConsumed()
        }
    }
    // Keep both scroll positions even when their tab is not composed or after recreation.
    val controlsState = rememberLazyListState()
    val historyState = rememberLazyListState()

    LaunchedEffect(repository, resumed) {
        if (!resumed) {
            choosingObjective = false
            return@LaunchedEffect
        }
        while (isActive) {
            snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
            notificationsEnabled = withContext(Dispatchers.IO) {
                AdaptiveService.proposalNotificationsEnabled(context)
            }
            delay(1_000)
        }
    }

    fun update(operation: () -> Unit) {
        if (!resumed || busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                decisionId = null
                failed = true
                snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
            } finally {
                busy = false
            }
        }
    }

    val current = snapshot
    LaunchedEffect(current?.pendingProposal?.id, decisionId) {
        val submitted = decisionId
        if (submitted != null && current != null && current.pendingProposal?.id != submitted) {
            if (reviewingId == submitted) reviewingId = null
            decisionId = null
        }
    }
    LaunchedEffect(current?.enabled) {
        if (current?.enabled == false) AdaptiveService.clearProposalNotification(context)
    }
    fun decide(id: String, approve: Boolean) {
        if (!resumed || busy || decisionId != null) return
        decisionId = id
        update {
            if (approve) repository.approveProposal(id) else repository.rejectProposal(id)
        }
    }
    val history = remember(current?.journal) {
        val occurrences = mutableMapOf<AdaptiveEvent, Int>()
        // The repository already bounds its journal to 120 events. Show all of them here.
        current?.journal.orEmpty().sortedByDescending { it.timestampMs }.map { event ->
            val occurrence = occurrences.getOrDefault(event, 0)
            occurrences[event] = occurrence + 1
            // Equal events can share a timestamp; distinguish them without position-based keys.
            "${event}:$occurrence" to event
        }
    }
    PartsScaffold(title = stringResource(R.string.adaptive_title), onBack = onBack) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.adaptive_tab_controls)) },
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.adaptive_tab_history)) },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                )
            }
            if (selectedTab == 0) {
                LazyColumn(
                    state = controlsState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                ) {
                    item("introduction") {
                        PartsInfoCard(
                            title = stringResource(R.string.adaptive_intro_title),
                            body = stringResource(R.string.adaptive_review_intro),
                            icon = Icons.Filled.Insights,
                            accent = true,
                        )
                    }
                    item("controls") {
                        PartsPreferenceGroup {
                            PartsSwitchPreference(
                                title = stringResource(R.string.adaptive_enabled_title),
                                summary = stringResource(when {
                                    busy -> R.string.adaptive_working
                                    current == null -> R.string.adaptive_loading
                                    current.enabled -> R.string.adaptive_enabled_summary
                                    else -> R.string.adaptive_review_disabled
                                }),
                                checked = current?.enabled == true,
                                enabled = resumed && current != null && !busy,
                                icon = Icons.Filled.Insights,
                                onCheckedChange = { enabled -> update { repository.setEnabled(enabled) } },
                            )
                            PartsPreference(
                                title = stringResource(R.string.adaptive_goal_title),
                                summary = current?.objective?.let { stringResource(objectiveLabel(it)) }
                                    ?: stringResource(R.string.adaptive_unavailable),
                                icon = Icons.Filled.Tune,
                                enabled = resumed && current != null && !busy,
                                onClick = { choosingObjective = true },
                            )
                        }
                    }
                    current?.pendingProposal?.let { pending ->
                        item("pending_proposal") {
                            PartsPreferenceGroup {
                                PartsPreference(
                                    title = stringResource(R.string.adaptive_review_title),
                                    summary = stringResource(R.string.adaptive_decision_limits,
                                        targetName(pending.proposal.target, pending.proposal.policyId),
                                        frequencyText(pending.proposal.beforeMaximum),
                                        frequencyText(pending.proposal.targetMaximum)),
                                    icon = Icons.Filled.Insights,
                                    enabled = resumed && !busy && decisionId == null,
                                    onClick = { reviewingId = pending.id },
                                )
                            }
                        }
                    }
                    if (!notificationsEnabled) {
                        item("notifications_disabled") {
                            PartsPreferenceGroup {
                                PartsPreference(
                                    title = stringResource(R.string.adaptive_review_notification_settings),
                                    summary = stringResource(R.string.adaptive_review_notifications_disabled),
                                    icon = Icons.Filled.Warning,
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                        }.onFailure { failed = true }
                                    },
                                )
                            }
                        }
                    }
                    if (current?.phase == AdaptivePhase.OFF && current.reason != "disabled") {
                        item("retry_restore") {
                            PartsPreferenceGroup {
                                PartsPreference(
                                    title = stringResource(R.string.adaptive_retry_restore_title),
                                    summary = stringResource(R.string.adaptive_retry_restore_summary),
                                    icon = Icons.Filled.RestartAlt,
                                    enabled = resumed && !busy,
                                    onClick = { update { repository.setEnabled(false) } },
                                )
                            }
                        }
                    }
                    if (failed) {
                        item("action_error") {
                            PartsInfoCard(body = stringResource(R.string.adaptive_action_failed),
                                icon = Icons.Filled.Warning)
                        }
                    }
                    item("status") { AdaptiveStatus(current) }
                    item("readings_title") {
                        PartsCategoryTitle(stringResource(if (current?.enabled == true) {
                            R.string.adaptive_measurements_title
                        } else R.string.adaptive_last_measurements_title))
                    }
                    item("readings") { AdaptiveReadings(current?.latestSample) }
                    item("boundaries") {
                        PartsInfoCard(
                            title = stringResource(R.string.adaptive_boundaries_title),
                            body = stringResource(R.string.adaptive_boundaries_summary) + "\n\n" +
                                stringResource(R.string.adaptive_gpu_boundaries),
                            icon = Icons.Filled.Lock,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = historyState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                ) {
                    item("history_title") {
                        PartsCategoryTitle(stringResource(R.string.adaptive_history_title))
                    }
                    item("cadence") {
                        PartsInfoCard(body = stringResource(R.string.adaptive_review_cadence),
                            icon = Icons.Filled.Schedule)
                    }
                    if (history.isEmpty()) {
                        item("empty_history") {
                            PartsInfoCard(body = stringResource(if (current?.enabled == true) {
                                R.string.adaptive_history_waiting
                            } else R.string.adaptive_history_empty), icon = Icons.Filled.History)
                        }
                    } else {
                        items(history, key = { it.first }) { (_, event) -> AdaptiveEventCard(event) }
                        item("measurement_note") {
                            PartsInfoCard(body = stringResource(R.string.adaptive_measurement_note))
                        }
                    }
                }
            }
        }
    }
    if (reviewingId != null && current != null) {
        // Never substitute a newer proposal for the ID the user opened.
        val pending = current.pendingProposal?.takeIf {
            it.id == reviewingId && it.expiresElapsedMs > SystemClock.elapsedRealtime()
        }
        AlertDialog(
            onDismissRequest = { reviewingId = null },
            title = { Text(stringResource(R.string.adaptive_review_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (pending == null) {
                        Text(stringResource(R.string.adaptive_review_unavailable))
                    } else {
                        Text(stringResource(R.string.adaptive_decision_limits,
                            targetName(pending.proposal.target, pending.proposal.policyId),
                            frequencyText(pending.proposal.beforeMaximum),
                            frequencyText(pending.proposal.targetMaximum)),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(objectiveLabel(pending.objective)))
                        Text(stringResource(reasonLabel(pending.reason)))
                        Text(stringResource(R.string.adaptive_review_explanation))
                        AdaptiveReadings(pending.baseline)
                        if (busy || decisionId != null) Text(stringResource(R.string.adaptive_working))
                        if (failed) Text(stringResource(R.string.adaptive_action_failed))
                    }
                }
            },
            confirmButton = {
                if (pending == null) {
                    TextButton(onClick = { reviewingId = null }) { Text(stringResource(android.R.string.ok)) }
                } else {
                    TextButton(enabled = resumed && !busy && decisionId == null,
                        onClick = { decide(pending.id, approve = true) }) {
                        Text(stringResource(R.string.adaptive_review_apply))
                    }
                }
            },
            dismissButton = {
                if (pending != null) {
                    TextButton(enabled = resumed && !busy && decisionId == null,
                        onClick = { decide(pending.id, approve = false) }) {
                        Text(stringResource(R.string.adaptive_review_reject))
                    }
                }
            },
        )
    }
    if (choosingObjective && current != null) {
        AlertDialog(
            onDismissRequest = { choosingObjective = false },
            title = { Text(stringResource(R.string.adaptive_goal_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdaptiveObjective.entries.forEach { objective ->
                        PartsPreference(
                            title = stringResource(objectiveLabel(objective)),
                            summary = stringResource(objectiveDescription(objective)),
                            selected = current.objective == objective,
                            enabled = resumed && !busy,
                            onClick = {
                                choosingObjective = false
                                update { repository.setObjective(objective) }
                            },
                            trailing = {
                                RadioButton(selected = current.objective == objective, onClick = null)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingObjective = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AdaptiveStatus(snapshot: AdaptiveSnapshot?) {
    val icon = when (snapshot?.phase) {
        AdaptivePhase.AWAITING_APPROVAL -> Icons.Filled.Insights
        AdaptivePhase.OBSERVING -> Icons.Filled.Visibility
        AdaptivePhase.APPLYING -> Icons.Filled.Tune
        AdaptivePhase.EVALUATING -> Icons.Filled.Insights
        AdaptivePhase.COOLDOWN -> Icons.Filled.Schedule
        AdaptivePhase.MANUAL_OVERRIDE -> Icons.Filled.Lock
        AdaptivePhase.UNAVAILABLE -> Icons.Filled.Warning
        else -> Icons.Filled.Insights
    }
    val details = buildList {
        if (snapshot != null) {
            add(stringResource(reasonLabel(snapshot.reason)))
            snapshot.trialPolicyId?.let { add(stringResource(R.string.adaptive_trial_policy, targetName(snapshot.trialTarget ?: AdaptiveTarget.CPU, it))) }
            if (snapshot.enabled && snapshot.cooldownRemainingMs > 0) {
                add(stringResource(R.string.adaptive_cooldown_remaining,
                    (snapshot.cooldownRemainingMs + 999) / 1_000))
            }
        } else add(stringResource(R.string.adaptive_loading))
    }
    PartsInfoCard(
        title = stringResource(when (snapshot?.phase) {
            AdaptivePhase.AWAITING_APPROVAL -> R.string.adaptive_status_approval
            AdaptivePhase.OFF -> R.string.adaptive_status_off
            AdaptivePhase.OBSERVING -> R.string.adaptive_status_observing
            AdaptivePhase.APPLYING -> R.string.adaptive_status_applying
            AdaptivePhase.EVALUATING -> R.string.adaptive_status_evaluating
            AdaptivePhase.COOLDOWN -> R.string.adaptive_status_cooldown
            AdaptivePhase.MANUAL_OVERRIDE -> R.string.adaptive_status_manual
            AdaptivePhase.UNAVAILABLE -> R.string.adaptive_status_unavailable
            null -> R.string.adaptive_status_title
        }),
        body = details.joinToString("\n"),
        icon = icon,
    )
}

@Composable
private fun AdaptiveReadings(sample: AdaptiveSample?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PartsMetricCard(stringResource(R.string.adaptive_cpu_load), loadText(sample?.cpuLoadPercent),
                Icons.Filled.Speed, Modifier.weight(1f))
            PartsMetricCard(stringResource(R.string.adaptive_cpu_temperature), temperatureText(sample?.cpuTemperatureC),
                Icons.Filled.DeviceThermostat, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PartsMetricCard(stringResource(R.string.adaptive_gpu_load), loadText(sample?.gpuLoadPercent),
                Icons.Filled.Speed, Modifier.weight(1f))
            PartsMetricCard(stringResource(R.string.adaptive_gpu_temperature), temperatureText(sample?.gpuTemperatureC),
                Icons.Filled.DeviceThermostat, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PartsMetricCard(stringResource(R.string.adaptive_discharge_current), currentText(sample?.dischargeCurrentMa),
                Icons.Filled.Bolt, Modifier.weight(1f))
            PartsMetricCard(stringResource(R.string.adaptive_battery_temperature), temperatureText(sample?.batteryTemperatureC),
                Icons.Filled.BatterySaver, Modifier.weight(1f))
        }
        AdaptiveFrameReadings(sample?.frames)
        if (sample != null) {
            Text(stringResource(R.string.adaptive_read_at, eventTime(sample.wallTimeMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun AdaptiveFrameReadings(frames: AdaptiveFrameWindow?) {
    Text(stringResource(R.string.adaptive_frames_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    if (frames != null && frames.frameCount > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PartsMetricCard(stringResource(R.string.adaptive_frames_jank), frameJankText(frames),
                Icons.Filled.Speed, Modifier.weight(1f))
            PartsMetricCard(stringResource(R.string.adaptive_frames_dropped), frames.droppedFrames.toString(),
                Icons.Filled.Insights, Modifier.weight(1f))
        }
        Text(stringResource(R.string.adaptive_frames_count, frames.frameCount),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        if (frames.framePeriodNs > 0 && frames.refreshPeriodNs > 0) {
            Text(stringResource(R.string.adaptive_frames_timing,
                1_000_000_000.0 / frames.framePeriodNs, 1_000_000_000.0 / frames.refreshPeriodNs),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }
        frames.frameTimeP95Ms?.takeIf { it.isFinite() && it >= 0 }?.let { value ->
            PartsMetricCard(stringResource(R.string.adaptive_frames_p95),
                stringResource(R.string.adaptive_milliseconds, value), Icons.Filled.Schedule,
                Modifier.fillMaxWidth())
        }
    } else {
        Text(stringResource(R.string.adaptive_frames_waiting),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
    }
    Text(stringResource(R.string.adaptive_frames_partial), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
private fun frameJankText(frames: AdaptiveFrameWindow?): String = loadText(
    frames?.takeIf { it.frameCount > 0 }?.let { it.jankyFrames * 100.0 / it.frameCount })

@Composable
private fun AdaptiveEventCard(event: AdaptiveEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(actionLabel(event.action)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Icon(if (event.action == "evaluated") Icons.Filled.Insights else Icons.Filled.History,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(eventTime(event.timestampMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val policy = event.policyId
            val before = event.beforeMaximumKHz
            val after = event.afterMaximumKHz
            if (policy != null && before != null && after != null) {
                Text(stringResource(R.string.adaptive_decision_limits, targetName(event.target, policy),
                    frequencyText(before), frequencyText(after)),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(reasonLabel(event.reason)), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.beforeSample != null && event.afterSample != null) {
                Text(stringResource(R.string.adaptive_before_after),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                MeasurementComparison(stringResource(R.string.adaptive_cpu_load),
                    loadText(event.beforeSample.cpuLoadPercent), loadText(event.afterSample.cpuLoadPercent))
                MeasurementComparison(stringResource(R.string.adaptive_gpu_load),
                    loadText(event.beforeSample.gpuLoadPercent), loadText(event.afterSample.gpuLoadPercent))
                MeasurementComparison(stringResource(R.string.adaptive_frames_jank),
                    frameJankText(event.beforeSample.frames), frameJankText(event.afterSample.frames))
                MeasurementComparison(stringResource(R.string.adaptive_discharge_current),
                    currentText(event.beforeSample.dischargeCurrentMa), currentText(event.afterSample.dischargeCurrentMa))
                MeasurementComparison(stringResource(R.string.adaptive_cpu_temperature),
                    temperatureText(event.beforeSample.cpuTemperatureC), temperatureText(event.afterSample.cpuTemperatureC))
                MeasurementComparison(stringResource(R.string.adaptive_gpu_temperature),
                    temperatureText(event.beforeSample.gpuTemperatureC), temperatureText(event.afterSample.gpuTemperatureC))
                MeasurementComparison(stringResource(R.string.adaptive_battery_temperature),
                    temperatureText(event.beforeSample.batteryTemperatureC), temperatureText(event.afterSample.batteryTemperatureC))
            }
            event.measuredDeltaMa?.takeIf { it.isFinite() }?.let { delta ->
                Text(stringResource(R.string.adaptive_current_delta, delta),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MeasurementComparison(label: String, before: String, after: String) {
    Text(stringResource(R.string.adaptive_measurement_change, label, before, after),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun eventTime(timestamp: Long): String = DateUtils.formatDateTime(LocalContext.current,
    timestamp, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
private fun loadText(value: Double?): String = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
    ?.let { stringResource(R.string.adaptive_percent, it) } ?: stringResource(R.string.adaptive_unavailable)

@Composable
private fun temperatureText(value: Double?): String = value?.takeIf { it.isFinite() }
    ?.let { stringResource(R.string.adaptive_celsius, it) } ?: stringResource(R.string.adaptive_unavailable)

@Composable
private fun currentText(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }
    ?.let { stringResource(R.string.adaptive_milliamps, it) } ?: stringResource(R.string.adaptive_unavailable)

@Composable
private fun frequencyText(value: Long): String = stringResource(R.string.adaptive_frequency, value / 1_000)

@Composable
private fun targetName(target: AdaptiveTarget, id: Int): String =
    if (target == AdaptiveTarget.GPU) stringResource(R.string.temperature_type_gpu) else clusterName(id)

@Composable
private fun clusterName(id: Int): String = when (id) {
    0 -> stringResource(R.string.adaptive_cluster_efficiency)
    4 -> stringResource(R.string.adaptive_cluster_performance)
    else -> stringResource(R.string.adaptive_cluster_number, id)
}

private fun objectiveLabel(objective: AdaptiveObjective): Int = when (objective) {
    AdaptiveObjective.BALANCED -> R.string.adaptive_goal_balance
    AdaptiveObjective.PERFORMANCE -> R.string.adaptive_goal_performance
    AdaptiveObjective.BATTERY -> R.string.adaptive_goal_battery
}

private fun objectiveDescription(objective: AdaptiveObjective): Int = when (objective) {
    AdaptiveObjective.BALANCED -> R.string.adaptive_goal_balance_summary
    AdaptiveObjective.PERFORMANCE -> R.string.adaptive_goal_performance_summary
    AdaptiveObjective.BATTERY -> R.string.adaptive_goal_battery_summary
}

private fun actionLabel(action: String): Int = when (action) {
    "approved" -> R.string.adaptive_event_approved
    "rejected" -> R.string.adaptive_event_rejected
    "proposal_cancelled" -> R.string.adaptive_event_expired
    "sample" -> R.string.adaptive_event_sample
    "proposed" -> R.string.adaptive_event_proposed
    "applied" -> R.string.adaptive_event_applied
    "readback" -> R.string.adaptive_event_readback
    "evaluated" -> R.string.adaptive_event_evaluated
    "rollback" -> R.string.adaptive_event_rollback
    "yield" -> R.string.adaptive_event_yield
    "started" -> R.string.adaptive_event_started
    "stopped" -> R.string.adaptive_event_stopped
    else -> R.string.adaptive_event_unknown
}

private fun reasonLabel(reason: String): Int = when (reason) {
    "frames_unavailable", "insufficient_frames", "inconclusive_frames", "collecting_frames" -> R.string.adaptive_frames_waiting
    "frames_changed" -> R.string.adaptive_frames_changed
    "frame_improved" -> R.string.adaptive_frames_improved
    "frame_regression", "no_frame_improvement" -> R.string.adaptive_frames_regressed
    "gpu_missing_telemetry" -> R.string.adaptive_gpu_missing
    "waiting_original_workload" -> R.string.adaptive_reason_waiting_workload
    "awaiting_approval" -> R.string.adaptive_reason_pending_approval
    "proposal_lower_frequency" -> R.string.adaptive_frames_lower
    "proposal_raise_frequency" -> R.string.adaptive_frames_raise
    "user_approved" -> R.string.adaptive_event_approved
    "proposal_rejected" -> R.string.adaptive_reason_user_rejected
    "proposal_expired" -> R.string.adaptive_reason_proposal_expired
    "proposal_not_current" -> R.string.adaptive_reason_proposal_stale
    "disabled" -> R.string.adaptive_reason_disabled
    "collecting_baseline" -> R.string.adaptive_reason_collecting_baseline
    "collecting_result" -> R.string.adaptive_reason_collecting_result
    "missing_telemetry" -> R.string.adaptive_reason_missing_telemetry
    "charging" -> R.string.adaptive_reason_charging
    "thermal_limit" -> R.string.adaptive_reason_thermal_limit
    "memory_pressure" -> R.string.adaptive_reason_memory_pressure
    "no_candidate" -> R.string.adaptive_reason_no_candidate
    "manual_override" -> R.string.adaptive_reason_manual_override
    "external_override" -> R.string.adaptive_reason_external_override
    "manual_lock" -> R.string.adaptive_reason_manual_lock
    "write_failed" -> R.string.adaptive_reason_write_failed
    "readback_failed" -> R.string.adaptive_reason_readback_failed
    "one_step_trial" -> R.string.adaptive_trial_generic
    "inconclusive_context" -> R.string.adaptive_reason_inconclusive_context
    "inconclusive_noise" -> R.string.adaptive_reason_inconclusive_noise
    "inconclusive_samples" -> R.string.adaptive_reason_inconclusive_samples
    "current_improved" -> R.string.adaptive_reason_current_improved
    "within_budget" -> R.string.adaptive_reason_within_budget
    "no_measured_improvement" -> R.string.adaptive_reason_no_measured_improvement
    "budget_regression" -> R.string.adaptive_reason_budget_regression
    "cooldown" -> R.string.adaptive_reason_cooldown
    "stopped" -> R.string.adaptive_reason_stopped
    "restore_skipped" -> R.string.adaptive_reason_restore_skipped
    "restored" -> R.string.adaptive_reason_restored
    "objective_changed" -> R.string.adaptive_reason_objective_changed
    "candidate_rejected" -> R.string.adaptive_reason_candidate_rejected
    else -> R.string.adaptive_reason_unknown
}
