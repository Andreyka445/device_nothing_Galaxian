/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.memory

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
import org.lineageos.settings.galaxian.ui.PartsPreference
import org.lineageos.settings.galaxian.ui.PartsPreferenceGroup
import org.lineageos.settings.galaxian.ui.PartsScaffold

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { MemoryRepository(context.applicationContext) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var snapshot by remember { mutableStateOf<MemorySnapshot?>(null) }
    var confirmation by remember { mutableStateOf<List<BackgroundApp>?>(null) }
    var pending by remember { mutableStateOf<List<BackgroundApp>?>(null) }
    var working by remember { mutableStateOf(false) }
    var actionResult by remember { mutableStateOf<String?>(null) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) confirmation = null
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository, resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
            delay(3_000)
        }
    }
    LaunchedEffect(pending, resumed) {
        val selected = pending ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        working = true
        actionResult = context.getString(R.string.memory_result_waiting)
        try {
            val requests = withContext(Dispatchers.IO) {
                val operation = currentCoroutineContext()
                repository.requestStops(selected) { operation.isActive }
            }
            delay(1_000)
            val remaining = withContext(Dispatchers.IO) { repository.stillObserved(requests.requested) }
            actionResult = if (remaining == null) {
                context.getString(R.string.memory_result_unverified, requests.requested.size,
                    requests.skipped, requests.failed)
            } else {
                context.getString(R.string.memory_result_summary, requests.requested.size,
                    requests.requested.size - remaining, remaining, requests.skipped, requests.failed)
            }
            snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
        } catch (cancelled: CancellationException) {
            actionResult = context.getString(R.string.memory_result_interrupted)
            throw cancelled
        } finally {
            working = false
            pending = null
        }
    }

    val unavailable = stringResource(R.string.memory_unavailable)
    fun bytes(value: Long?) = value?.let { Formatter.formatFileSize(context, it) } ?: unavailable
    val apps = snapshot?.apps.orEmpty()
    val canStop = resumed && snapshot?.canStopApps == true && !working && pending == null
    val swapTotal = snapshot?.swapTotalBytes
    val swapFree = snapshot?.swapFreeBytes
    val swapUsed = if (swapTotal != null && swapFree != null && swapFree in 0..swapTotal) {
        swapTotal - swapFree
    } else null

    PartsScaffold(title = stringResource(R.string.memory_title), onBack = onBack) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item("memory_hero") { MemoryHero(snapshot) }
            item("ram_metrics") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PartsMetricCard(stringResource(R.string.memory_total), bytes(snapshot?.totalBytes),
                        Icons.Filled.Memory, Modifier.weight(1f))
                    PartsMetricCard(stringResource(R.string.memory_available), bytes(snapshot?.availableBytes),
                        Icons.Filled.Speed, Modifier.weight(1f))
                }
            }
            item("swap_section") { PartsCategoryTitle(stringResource(R.string.memory_swap_section)) }
            item("swap_metrics") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PartsMetricCard(stringResource(R.string.memory_swap_used),
                        if (swapTotal == 0L) stringResource(R.string.memory_disabled) else bytes(swapUsed),
                        Icons.Filled.SwapVert, Modifier.weight(1f))
                    PartsMetricCard(stringResource(R.string.memory_swap_total), bytes(swapTotal),
                        Icons.Filled.SdStorage, Modifier.weight(1f))
                }
            }
            item("zram") {
                PartsPreferenceGroup {
                    PartsPreference(
                        title = stringResource(R.string.memory_zram),
                        summary = when (snapshot?.zramCapacityBytes) {
                            null -> unavailable
                            0L -> stringResource(R.string.memory_disabled)
                            else -> stringResource(R.string.memory_zram_summary,
                                bytes(snapshot?.zramCapacityBytes), bytes(snapshot?.zramDataBytes),
                                bytes(snapshot?.zramMemoryBytes))
                        },
                        icon = Icons.Filled.Memory,
                    )
                }
            }
            item("apps") {
                PartsPreferenceGroup(title = stringResource(R.string.memory_background_apps)) {
                    PartsPreference(
                        title = stringResource(R.string.memory_clean),
                        summary = stringResource(R.string.memory_clean_summary),
                        icon = Icons.Filled.CleaningServices,
                        onClick = { confirmation = apps.toList() },
                        enabled = canStop && apps.isNotEmpty(),
                    )
                    if (snapshot?.apps == null || snapshot?.canStopApps != true || apps.isEmpty()) {
                        PartsPreference(
                            title = stringResource(if (snapshot?.apps == null || snapshot?.canStopApps != true) {
                                R.string.memory_apps_unavailable
                            } else R.string.memory_no_apps),
                            icon = Icons.Filled.Apps,
                        )
                    }
                    apps.forEach { app ->
                        PartsPreference(
                            title = app.label,
                            summary = app.packageName,
                            icon = Icons.Filled.Apps,
                            iconBitmap = appIcon(app.packageName, resumed),
                            enabled = canStop,
                            trailing = {
                                IconButton(onClick = { confirmation = listOf(app) }, enabled = canStop) {
                                    Icon(Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.memory_stop_app, app.label))
                                }
                            },
                        )
                    }
                }
            }
            actionResult?.let { message ->
                item("action_result") {
                    PartsInfoCard(title = stringResource(R.string.memory_result), body = message, accent = true)
                }
            }
            item("memory_about") {
                PartsInfoCard(title = stringResource(R.string.memory_about),
                    body = stringResource(R.string.memory_about_summary))
            }
        }
    }
    confirmation?.let { selected ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            icon = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
            title = { Text(stringResource(R.string.memory_confirm_title)) },
            text = { Text(if (selected.size == 1) {
                stringResource(R.string.memory_confirm_one, selected.single().label)
            } else stringResource(R.string.memory_confirm_many, selected.size)) },
            confirmButton = {
                TextButton(onClick = { pending = selected; confirmation = null }, enabled = canStop) {
                    Text(stringResource(R.string.memory_stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun MemoryHero(snapshot: MemorySnapshot?) {
    val context = LocalContext.current
    val total = snapshot?.totalBytes
    val available = snapshot?.availableBytes
    val used = if (total != null && available != null) (total - available).coerceAtLeast(0) else null
    val fraction = if (total != null && total > 0 && used != null) used.toFloat() / total else null
    val progress by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = Motion.defaultEffectsSpec(),
        label = "memoryUsage",
    )
    val color = when {
        fraction == null -> MaterialTheme.colorScheme.onSurfaceVariant
        fraction > .9f -> MaterialTheme.colorScheme.error
        fraction > .7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.memory_used), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = used?.let { Formatter.formatFileSize(context, it) }
                    ?: stringResource(R.string.memory_unavailable),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (total != null && available != null) {
                    stringResource(R.string.memory_hero_summary, Formatter.formatFileSize(context, total),
                        Formatter.formatFileSize(context, available))
                } else stringResource(R.string.memory_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun appIcon(packageName: String, resumed: Boolean): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, resumed) {
        if (resumed && icon == null) {
            icon = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96).asImageBitmap() }.getOrNull()
            }
        }
    }
    return icon
}
