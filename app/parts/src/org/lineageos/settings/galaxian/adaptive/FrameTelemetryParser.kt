/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

internal data class FrameTimelineRecord(
    val token: Long, val pid: Int, val layerId: Int, val surfaceView: Boolean,
    val presentState: String, val predictionState: String, val jank: Set<String>,
    val scheduledFps: Int, val refreshPeriodNs: Long,
    val expectedPresentMs: Double?, val actualPresentMs: Double?, val dropTimeMs: Double?,
)
internal data class FrameTimelineSnapshot(val records: List<FrameTimelineRecord>, val supported: Boolean)

/** Parser for this tree's FrameTimeline::dumpAll<std::milli>, not dumpsys latency.
 * Timestamps are relative to a different base in each dump. Never compare them across dumps.
 */
internal object FrameTelemetryParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val display = Regex("^Display Frame [0-9]+(?: \\[\\*\\] )?$")
    private val header = Regex("^Number of display frames : ([0-9]+)$")
    private val field = Regex("^([^:]+):\\s*(.*)$")
    private fun number(value: String?) = value?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun parse(text: String, ownerPids: Set<Int>): FrameTimelineSnapshot {
        if (text.length > MAX_BYTES || ownerPids.isEmpty()) return FrameTimelineSnapshot(emptyList(), false)
        val records = mutableListOf<FrameTimelineRecord>()
        var declared = -1
        var displays = 0
        var refreshNs = 0L
        var displayComplete = false
        var values: MutableMap<String, String>? = null
        var surfaceView = false
        var expected: Double? = null
        var actual: Double? = null
        var actualSeen = false
        var broken = false
        fun finish() {
            val v = values ?: return
            values = null
            if (v["Is Buffer?"] != "1" || v["Owner Pid"]?.toIntOrNull() !in ownerPids) return
            val token = v["Token"]?.toLongOrNull()
            val pid = v["Owner Pid"]?.toIntOrNull()
            val layer = v["Layer ID"]?.toIntOrNull()
            val fps = v["Scheduled rendering rate"]?.removeSuffix(" fps")?.toIntOrNull()
            val state = v["Present State"]
            val prediction = v["Prediction State"]
            val jank = v["Jank Type"]
            if (token == null || pid == null || layer == null || fps == null || state == null ||
                prediction == null || jank == null || !actualSeen || refreshNs <= 0 ||
                state !in setOf("Presented", "Dropped", "Unknown") ||
                prediction !in setOf("Valid", "Expired", "None") ||
                (prediction == "Valid" && expected == null)) {
                broken = true
                return
            }
            // In-flight and invalid-fence display frames are not complete measurements.
            val drop = number(v["Drop time"])
            if (!displayComplete || state == "Unknown") return
            // Native classification deliberately clears actual-present for a dropped buffer.
            // Its explicit drop timestamp and completed parent display establish completion.
            if (state == "Presented" && (actual == null || actual!! <= 0)) return
            if (state == "Dropped" && (drop == null || drop <= 0)) return
            records += FrameTimelineRecord(token, pid, layer, surfaceView, state, prediction,
                jank.split(", ").toSet(), fps, refreshNs, expected, actual, drop)
        }
        for (line in text.lineSequence()) {
            header.matchEntire(line)?.let {
                if (declared != -1) broken = true
                declared = it.groupValues[1].toIntOrNull() ?: -1
            }
            if (display.matches(line)) {
                finish(); displays++; refreshNs = 0; displayComplete = false
                continue
            }
            if (line.startsWith("    Layer - ")) {
                finish(); values = mutableMapOf(); expected = null; actual = null; actualSeen = false
                // This is a selection hint only; multiple SurfaceViews remain ambiguous.
                surfaceView = line.removePrefix("    Layer - ").contains("SurfaceView")
                continue
            }
            val trimmed = line.trim()
            if (!line.startsWith("    ")) {
                if (trimmed.startsWith("Vsync Period:")) {
                    val ms = number(trimmed.substringAfter(':').trim())
                    refreshNs = ms?.takeIf { it in 2.0..250.0 }?.times(1_000_000)?.roundToLong() ?: 0
                }
                if (trimmed.startsWith("Actual") && '|' in trimmed) {
                    displayComplete = number(trimmed.substringAfterLast('|').trim())?.let { it > 0 } == true
                }
                continue
            }
            if (values == null) continue
            if (trimmed.startsWith("Expected") && '|' in trimmed) {
                expected = number(trimmed.substringAfterLast('|').trim())
            } else if (trimmed.startsWith("Actual") && '|' in trimmed) {
                actualSeen = true
                actual = number(trimmed.substringAfterLast('|').trim())
            } else {
                field.matchEntire(trimmed)?.let { values?.put(it.groupValues[1].trim(), it.groupValues[2]) }
            }
        }
        finish()
        // A truncated dump or unsupported version must not silently become a clean window.
        return FrameTimelineSnapshot(records, !broken && declared in 0..4096 && displays == declared &&
            (declared == 0 || text.trimEnd().endsWith("----")))
    }
}

/** Keeps only tokens from one identifiable rendering layer; all output is sampled coverage. */
internal class FrameTelemetryAnalyzer(private val minimumFrames: Int = 10) {
    private var epoch = 0L
    private var context: String? = null
    private var lastToken = -1L
    private val seen = linkedSetOf<Long>()
    private var lastSampled = -1L
    private var period = 0L
    private var refresh = 0L
    private var reviewPaused = false

    fun reset() {
        epoch++; context = null; lastToken = -1; seen.clear(); lastSampled = -1; period = 0; refresh = 0
        reviewPaused = false
    }

    /** Pause only for our own review UI. Other app/layer changes must still reset the epoch. */
    fun pauseForReview() {
        if (context != null) reviewPaused = true
    }

    fun accept(snapshot: FrameTimelineSnapshot, scope: String, now: Long): FrameTelemetryResult {
        fun unavailable(reason: String, count: Int = 0, unknown: Int = 0) =
            FrameTelemetryResult(null, reason, count, count - unknown, unknown)
        if (!snapshot.supported) { reset(); return unavailable("frames_unavailable") }
        if (lastSampled >= 0 && now <= lastSampled) return unavailable("frames_unavailable")
        lastSampled = now
        val groups = snapshot.records.groupBy { it.pid to it.layerId }
        if (groups.isEmpty()) return unavailable("insufficient_frames")
        val surfaceViews = groups.filterValues { it.any { frame -> frame.surfaceView } }
        val candidates = if (surfaceViews.isNotEmpty()) surfaceViews else groups
        // No geometry is exported: do not guess among multiple rendering surfaces/windows.
        if (candidates.size != 1) { reset(); return unavailable("frames_changed") }
        val (identity, raw) = candidates.entries.single()
        val records = raw.distinctBy { it.token to it.actualPresentMs }
        val key = "$scope:${identity.first}:${identity.second}"
        val knownLabels = setOf("None", "App Deadline Missed", "App Resynced Jitter",
            "Display HAL", "SurfaceFlinger deadline missed (while in HWC)",
            "SurfaceFlinger deadline missed (while in GPU comp)", "SurfaceFlinger Scheduling",
            "Buffer Stuffing", "SurfaceFlinger Stuffing", "Dropped Frame")
        val invalid = records.count { it.token < 0 || it.predictionState != "Valid" ||
            it.jank.any { label -> label !in knownLabels } }
        if (invalid != 0) {
            reset()
            return unavailable("frames_unavailable", records.size, invalid)
        }
        val unique = records.distinctBy { it.token }.sortedBy { it.token }
        if (unique.size != records.size) { reset(); return unavailable("frames_unavailable") }
        if (context != key) {
            val returningFromReview = reviewPaused
            reset(); context = key; lastSampled = now
            seen += unique.map { it.token }; lastToken = unique.maxOf { it.token }
            return unavailable(if (returningFromReview) "frames_changed" else "collecting_frames", unique.size)
        }
        val fresh = unique.filter { it.token !in seen }
        if (fresh.isEmpty()) return unavailable("insufficient_frames")
        val previousToken = lastToken
        seen += fresh.map { it.token }
        while (seen.size > 4096) seen.remove(seen.first())
        lastToken = maxOf(lastToken, fresh.maxOf { it.token })
        if (fresh.first().token <= previousToken) {
            // Late/out-of-order completions must not bias a supposedly disjoint comparison.
            epoch++
            return unavailable("frames_changed", fresh.size)
        }
        if (fresh.size < minimumFrames) return unavailable("insufficient_frames", fresh.size)
        val refreshes = fresh.map { it.refreshPeriodNs }
        val newRefresh = refreshes.sorted()[refreshes.size / 2]
        if (refreshes.any { relativeDifference(it, newRefresh) > .03 }) {
            epoch++; return unavailable("frames_changed", fresh.size)
        }
        val scheduled = fresh.map { it.scheduledFps }.distinct()
        if (scheduled.size != 1 || scheduled.single() !in 0..240) {
            epoch++; return unavailable("frames_changed", fresh.size)
        }
        val expectedDeltas = fresh.mapNotNull { it.expectedPresentMs }.sorted().zipWithNext { a, b -> b - a }
            .filter { it > 0 }
        val newPeriod = if (scheduled.single() > 0) {
            (1_000_000_000.0 / scheduled.single()).roundToLong()
        } else {
            // Expected present cadence, not display refresh or measured FPS. Require stability.
            if (expectedDeltas.size < minimumFrames - 1) return unavailable("insufficient_frames", fresh.size)
            val median = expectedDeltas.sorted()[expectedDeltas.size / 2]
            if (median !in 4.0..250.0 || expectedDeltas.count { abs(it - median) <= median * .08 } <
                ceil(expectedDeltas.size * .8).toInt()) return unavailable("frames_changed", fresh.size)
            (median * 1_000_000).roundToLong()
        }
        val cadenceChanged = period > 0 && (relativeDifference(period, newPeriod) > .03 ||
            relativeDifference(refresh, newRefresh) > .03)
        if (cadenceChanged) epoch++
        if (reviewPaused) {
            reviewPaused = false
            // Tokens are already consumed above. Neither pre-approval frames nor frames
            // produced while switching apps may enter the post-change comparison.
            // Grant the one-dump warm-up only with the same verified layer and cadence.
            return unavailable(if (period == 0L || cadenceChanged) "frames_changed" else "resuming_frames", fresh.size)
        }
        // Avoid context churn from the dump's 0.01 ms rounding.
        if (period == 0L || relativeDifference(period, newPeriod) > .03) period = newPeriod
        if (refresh == 0L || relativeDifference(refresh, newRefresh) > .03) refresh = newRefresh
        val presented = fresh.filter { it.presentState == "Presented" }
            .mapNotNull { it.actualPresentMs }.distinct().sorted()
        val deltas = presented.zipWithNext { a, b -> b - a }.filter { it > 0 && it <= 5_000 }
        val completions = fresh.mapNotNull { if (it.presentState == "Dropped") it.dropTimeMs else it.actualPresentMs }.sorted()
        val duration = if (completions.size >= 2) ((completions.last() - completions.first()) * 1_000_000).roundToLong() else 0
        if (duration <= 0) return unavailable("insufficient_frames", fresh.size)
        fun appJank(f: FrameTimelineRecord) = f.jank.any { it == "App Deadline Missed" || it == "App Resynced Jitter" }
        fun compositorJank(f: FrameTimelineRecord) = f.jank.any { it == "Display HAL" || it.startsWith("SurfaceFlinger") }
        fun dropped(f: FrameTimelineRecord) = f.presentState == "Dropped" || "Dropped Frame" in f.jank
        val sorted = deltas.sorted()
        val p95 = if (sorted.size >= 10) sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)] else null
        return FrameTelemetryResult(AdaptiveFrameWindow("$key:$epoch", fresh.size,
            fresh.count { appJank(it) || compositorJank(it) || dropped(it) || "Buffer Stuffing" in it.jank }, fresh.count(::dropped),
            fresh.count(::appJank), fresh.count(::compositorJank), period, refresh,
            fresh.first().token, fresh.last().token, now, duration, p95), "collecting_frames",
            fresh.size, fresh.size, 0)
    }

    private fun relativeDifference(a: Long, b: Long) = abs(a - b).toDouble() / maxOf(1L, b)
}
