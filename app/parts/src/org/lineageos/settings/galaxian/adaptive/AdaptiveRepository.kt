/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.settings.galaxian.cpu.CpuControlSession
import org.lineageos.settings.galaxian.gpu.AdaptiveGpuWrite
import org.lineageos.settings.galaxian.gpu.GpuControlSession
import org.lineageos.settings.galaxian.gpu.GpuMode
import org.lineageos.settings.galaxian.gpu.GpuRestoreToken
import org.lineageos.settings.galaxian.gpu.GpuSessionSnapshot
import org.lineageos.settings.galaxian.temperature.TemperatureRepository

internal class AdaptiveRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("adaptive_journal", Context.MODE_PRIVATE)
    private val activity = app.getSystemService(ActivityManager::class.java)
    private val battery = app.getSystemService(BatteryManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val temperatures = TemperatureRepository(app)
    private val cpuLoad = CpuLoadReader()
    private val frameReader = FrameTelemetryReader(app)
    private val port = object : AdaptiveCpuPort {
        override fun snapshot(): AdaptiveCpuSnapshot {
            val state = CpuControlSession.snapshot()
            return AdaptiveCpuSnapshot(state.state.policies.map {
                AdaptivePolicy(it.id, it.minimum, it.maximum, it.frequencies, it.writable, it.locked)
            }, state.manualRevision)
        }
        override fun setMaximum(proposal: AdaptiveProposal): AdaptiveWriteResult {
            val result = CpuControlSession.adaptiveMaximum(proposal.policyId, proposal.minimum,
                proposal.beforeMaximum, proposal.targetMaximum, proposal.revision)
            return AdaptiveWriteResult(result.successful, result.reason, result.maximum)
        }
    }
    private val gpuPort = object : AdaptiveGpuPort {
        override fun snapshot(): AdaptiveGpuSnapshot? = GpuControlSession.snapshot().toAdaptive()

        override fun setMaximum(proposal: AdaptiveProposal): AdaptiveWriteResult {
            val expected = proposal.controlToken as? GpuSessionSnapshot
                ?: return AdaptiveWriteResult(false, "proposal_not_current", null)
            if (proposal.target != AdaptiveTarget.GPU || expected.guardKey != proposal.controlKey)
                return AdaptiveWriteResult(false, "proposal_not_current", null)
            return GpuControlSession.adaptiveMaximum(expected, proposal.targetMaximum).toAdaptive()
        }

        override fun restoreTrial(restoreToken: Any): AdaptiveWriteResult {
            val token = restoreToken as? GpuRestoreToken
                ?: return AdaptiveWriteResult(false, "restore_skipped", null)
            return GpuControlSession.adaptiveRestore(token).toAdaptive()
        }

        override fun release(controlToken: Any): AdaptiveWriteResult {
            val expected = controlToken as? GpuSessionSnapshot
                ?: return AdaptiveWriteResult(false, "restore_skipped", null)
            return GpuControlSession.releaseAdaptive(expected).toAdaptive()
        }
    }
    private fun GpuSessionSnapshot.toAdaptive(): AdaptiveGpuSnapshot? {
        val frequencies = operatingPoints.values.distinct().sorted()
        val maximum = maximumKHz ?: return null
        val raw = controls ?: return null
        val minimum = operatingPoints[raw.floorIndex.takeIf { it >= 0 } ?: (operatingPoints.size - 1)]
            ?: return null
        if (frequencies.isEmpty()) return null
        val manuallyControlled = state.controlMode != GpuMode.AUTOMATIC && !adaptiveOwned
        return AdaptiveGpuSnapshot(
            policy = AdaptivePolicy(0, minimum, maximum, frequencies, state.writable, manuallyControlled),
            revision = manualRevision, controlKey = guardKey, controlToken = this, owned = adaptiveOwned,
        )
    }
    private fun AdaptiveGpuWrite.toAdaptive() = AdaptiveWriteResult(
        successful, reason, maximumKHz, gpuState = snapshot.toAdaptive(), restoreToken = restoreToken,
    )
    private val engine = AdaptiveEngine(port, reviewWorkloadKey = app.packageName, gpu = gpuPort)
    private var savedJournal = ""
    private var sessionOwner: Any? = null

    init {
        val target = runCatching { AdaptiveObjective.valueOf(preferences.getString("objective", "BALANCED")!!) }
            .getOrDefault(AdaptiveObjective.BALANCED)
        engine.changeObjective(target, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        engine.seedJournal(readHistory())
        // Enabled state is deliberately never persisted; no boot receiver or auto-resume.
    }

    @Synchronized fun snapshot(): AdaptiveSnapshot = engine.snapshot(SystemClock.elapsedRealtime())

    fun setEnabled(enabled: Boolean) {
        val intent = Intent(app, AdaptiveService::class.java).setAction(
            if (enabled) AdaptiveService.ACTION_START else AdaptiveService.ACTION_STOP)
        if (enabled) ContextCompat.startForegroundService(app, intent) else app.startService(intent)
    }

    fun setObjective(objective: AdaptiveObjective) {
        app.startService(Intent(app, AdaptiveService::class.java)
            .setAction(AdaptiveService.ACTION_OBJECTIVE).putExtra(AdaptiveService.EXTRA_OBJECTIVE, objective.name))
    }

    fun approveProposal(id: String) {
        app.startService(Intent(app, AdaptiveService::class.java)
            .setAction(AdaptiveService.ACTION_APPROVE).putExtra(AdaptiveService.EXTRA_PROPOSAL_ID, id))
    }

    fun rejectProposal(id: String) {
        app.startService(Intent(app, AdaptiveService::class.java)
            .setAction(AdaptiveService.ACTION_REJECT).putExtra(AdaptiveService.EXTRA_PROPOSAL_ID, id))
    }

    internal fun approveProposal(owner: Any, id: String): AdaptiveDecisionResult {
        synchronized(this) {
            if (sessionOwner !== owner || !engine.snapshot(SystemClock.elapsedRealtime()).enabled ||
                engine.snapshot(SystemClock.elapsedRealtime()).pendingProposal?.id != id)
                return AdaptiveDecisionResult(false, "proposal_not_current")
        }
        val sample = collectSample()
        return synchronized(this) {
            if (sessionOwner !== owner) return@synchronized AdaptiveDecisionResult(false, "proposal_not_current")
            engine.approveProposal(id, sample, SystemClock.elapsedRealtime()).also { saveHistory() }
        }
    }

    @Synchronized internal fun rejectProposal(owner: Any, id: String): AdaptiveDecisionResult {
        if (sessionOwner !== owner) return AdaptiveDecisionResult(false, "proposal_not_current")
        return engine.rejectProposal(id, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            .also { saveHistory() }
    }

    @Synchronized internal fun changeObjective(objective: AdaptiveObjective) {
        engine.changeObjective(objective, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        preferences.edit().putString("objective", objective.name).apply()
        saveHistory()
    }

    @Synchronized internal fun startSession(owner: Any) {
        if (sessionOwner != null && sessionOwner !== owner)
            engine.stop(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (sessionOwner !== owner || !engine.snapshot(SystemClock.elapsedRealtime()).enabled) {
            cpuLoad.reset()
            frameReader.reset()
        }
        sessionOwner = owner
        engine.start(engine.snapshot(SystemClock.elapsedRealtime()).objective,
            SystemClock.elapsedRealtime(), System.currentTimeMillis())
        saveHistory()
    }

    @Synchronized internal fun stopSession(owner: Any, explicit: Boolean = false) {
        if (sessionOwner !== owner && (!explicit || sessionOwner != null)) return
        engine.stop(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        sessionOwner = null
        frameReader.close()
        saveHistory()
    }

    @Synchronized internal fun isRunning(owner: Any): Boolean =
        sessionOwner === owner && engine.snapshot(SystemClock.elapsedRealtime()).enabled

    internal fun tick(owner: Any) {
        if (!isRunning(owner)) return
        val sample = collectSample()
        synchronized(this) {
            if (sessionOwner !== owner) return
            engine.observe(sample, SystemClock.elapsedRealtime())
            saveHistory()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectSample(): AdaptiveSample {
        val started = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val load = runCatching { cpuLoad.read(File("/proc/stat").readText(), started) }.getOrNull()
        val temperature = runCatching { temperatures.read() }.getOrNull()
        val memory = ActivityManager.MemoryInfo()
        val hasMemory = runCatching { activity.getMemoryInfo(memory); true }.getOrDefault(false)
        val status = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Being plugged in invalidates current as a comparable discharge measure, including full charge.
        val charging = status == null || status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0
        val microamps = runCatching { battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
            .getOrDefault(Long.MIN_VALUE)
        val current = dischargeCurrentMa(microamps, charging)
        val foreground = runCatching { activity.getRunningTasks(1).firstOrNull()?.topActivity?.packageName }.getOrNull()
        val gpuSnapshot = runCatching { GpuControlSession.snapshot() }.getOrNull()
        val frameResult = frameReader.read(foreground, started)
        val currentForeground = runCatching {
            activity.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        }.getOrNull()
        val finished = SystemClock.elapsedRealtime()
        val fresh = finished - started <= 10_000
        val freshTemperature = temperature?.takeIf { finished - it.sampledAtElapsed in 0..10_000 }
        return AdaptiveSample(started, wall,
            cpuLoadPercent = load.takeIf { fresh },
            batteryTemperatureC = freshTemperature?.batteryC?.toDouble(),
            cpuTemperatureC = freshTemperature?.cpuMaximumC?.toDouble(),
            dischargeCurrentMa = current.takeIf { fresh },
            availableMemoryMiB = if (hasMemory && fresh) memory.availMem / (1024 * 1024) else null,
            lowMemory = hasMemory && memory.lowMemory,
            charging = charging,
            interactive = power.isInteractive,
            thermalStatus = freshTemperature?.thermalStatus,
            workloadKey = foreground.takeIf { it == currentForeground },
            gpuLoadPercent = gpuSnapshot?.utilization?.loadingPercent?.toDouble().takeIf { fresh },
            gpuTemperatureC = freshTemperature?.gpuMaximumC?.toDouble(),
            frames = frameResult.window.takeIf { fresh && foreground == currentForeground },
            frameTelemetryReason = frameResult.reason.takeIf { fresh && foreground == currentForeground },
        )
    }

    private fun saveHistory() {
        val journal = engine.snapshot(SystemClock.elapsedRealtime()).journal
        val json = JSONArray().apply { journal.forEach { put(it.toJson()) } }.toString()
        if (json != savedJournal) {
            savedJournal = json
            preferences.edit().putString("journal", json).apply()
        }
    }

    private fun readHistory(): List<AdaptiveEvent> = runCatching {
        val array = JSONArray(preferences.getString("journal", "[]"))
        (maxOf(0, array.length() - 120) until array.length()).map { index ->
            val value = array.getJSONObject(index)
            AdaptiveEvent(value.getLong("time"), value.getString("action"), value.getString("reason"),
                value.nullableLong("policy")?.toInt(), value.nullableLong("beforeMax"), value.nullableLong("afterMax"),
                value.nullableDouble("delta"), value.optJSONObject("before")?.toSample(), value.optJSONObject("after")?.toSample(),
                target = runCatching { AdaptiveTarget.valueOf(value.optString("target", "CPU")) }
                    .getOrDefault(AdaptiveTarget.CPU))
        }
    }.getOrDefault(emptyList())

    private fun AdaptiveEvent.toJson() = JSONObject().apply {
        put("time", timestampMs); put("action", action); put("reason", reason); put("target", target.name)
        put("policy", policyId); put("beforeMax", beforeMaximumKHz); put("afterMax", afterMaximumKHz)
        put("delta", measuredDeltaMa); beforeSample?.let { put("before", it.toJson()) }
        afterSample?.let { put("after", it.toJson()) }
    }
    private fun AdaptiveSample.toJson() = JSONObject().apply {
        put("elapsed", elapsedMs); put("wall", wallTimeMs); put("load", cpuLoadPercent)
        put("battery", batteryTemperatureC); put("cpu", cpuTemperatureC); put("current", dischargeCurrentMa)
        put("memory", availableMemoryMiB); put("pressure", lowMemory); put("charging", charging)
        put("interactive", interactive); put("thermal", thermalStatus)
        put("gpuLoad", gpuLoadPercent); put("gpuTemperature", gpuTemperatureC)
        frames?.let { put("frames", it.toHistoryJson()) }
        // Foreground package identity is used in-memory for comparability, never stored in the journal.
    }
    private fun JSONObject.toSample() = AdaptiveSample(optLong("elapsed"), optLong("wall"), nullableDouble("load"),
        nullableDouble("battery"), nullableDouble("cpu"), nullableDouble("current"), nullableLong("memory"),
        optBoolean("pressure"), optBoolean("charging"), optBoolean("interactive"), nullableLong("thermal")?.toInt(),
        gpuLoadPercent = nullableDouble("gpuLoad"), gpuTemperatureC = nullableDouble("gpuTemperature"),
        frames = optJSONObject("frames")?.toFrameHistory())

    // Retain numerical evidence only. PID, layer, package and proposal/control tokens are never
    // persisted; restored journal frames cannot be fed back into a live controller baseline.
    private fun AdaptiveFrameWindow.toHistoryJson() = JSONObject().apply {
        put("count", frameCount); put("jank", jankyFrames); put("dropped", droppedFrames)
        put("appJank", appJankyFrames); put("compositorJank", compositorJankyFrames)
        put("period", framePeriodNs); put("refresh", refreshPeriodNs)
        put("observed", observedDurationNs); put("p95", frameTimeP95Ms)
    }
    private fun JSONObject.toFrameHistory(): AdaptiveFrameWindow? = runCatching {
        val count = getInt("count")
        val jank = getInt("jank")
        val dropped = getInt("dropped")
        val appJank = getInt("appJank")
        val compositor = getInt("compositorJank")
        val period = getLong("period")
        val refresh = getLong("refresh")
        if (count <= 0 || listOf(jank, dropped, appJank, compositor).any { it !in 0..count } ||
            period <= 0 || refresh <= 0) return@runCatching null
        AdaptiveFrameWindow("history", count, jank, dropped, appJank, compositor,
            period, refresh, -1, -1, 0, optLong("observed"), nullableDouble("p95"))
    }.getOrNull()
    private fun JSONObject.nullableLong(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
    private fun JSONObject.nullableDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { it.isFinite() } else null

    companion object {
        @Volatile private var instance: AdaptiveRepository? = null
        fun get(context: Context): AdaptiveRepository = instance ?: synchronized(this) {
            instance ?: AdaptiveRepository(context).also { instance = it }
        }
    }
}
