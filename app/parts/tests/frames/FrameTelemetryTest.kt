/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import java.io.File
import java.util.Locale
import kotlin.math.abs

private var checks = 0
private fun expect(value: Boolean, message: String) { checks++; check(value) { message } }
private fun near(actual: Double, expected: Double, tolerance: Double = .02) = abs(actual - expected) <= tolerance

private data class Buffer(
    val token: Long, val layer: Int = 52, val pid: Int = 4242,
    val surface: Boolean = true, val prediction: String = "Valid",
    val state: String = "Presented", val jank: String = "None",
    val fps: Int = 30, val refresh: Double = 8.333333,
    val expected: Double = token * (1000.0 / 30), val actual: Double? = expected,
    val drop: Double? = null, val buffer: Boolean = true,
    val displayComplete: Boolean = true,
)
private fun number(value: Double?) = value?.let { String.format(Locale.ROOT, "%10.2f", it) } ?: "N/A"
private fun table(indent: String, prediction: String, expected: Double, actual: Double?) = buildString {
    append("${indent}\t\t    Start time\t\t|    End time\t\t|    Present time\n")
    if (prediction == "Valid") append("${indent}Expected\t|\t${number(expected - 10)}\t|\t${number(expected - 2)}\t|\t${number(expected)}\n")
    append("${indent}Actual  \t|\t${number(actual?.minus(10))}\t|\t${number(actual?.minus(2))}\t|\t${number(actual)}\n")
    append(indent + "-".repeat(88) + "\n")
}
/** Same sections, spacing, units, optional predictions and drop semantics as the native dumper. */
private fun dump(frames: List<Buffer>, origin: Double = 0.0) = buildString {
    append("Number of display frames : ${frames.size}\n")
    frames.forEachIndexed { index, f ->
        append("Display Frame $index${if (f.jank == "None") "" else " [*] "}\n")
        append("Prediction State : Valid\nJank Type : None\nPresent Metadata : OnTime\nFinish Metadata: OnTime\nStart Metadata: OnTime\n")
        append("Vsync Period: ${f.refresh}\nPresent delta: 0.000000\nPresent delta % refreshrate: 0.000000\nJank Debug Metadata: 0.000000\n")
        append(table("", "Valid", f.expected - origin, if (f.displayComplete) f.expected - origin + 1 else null)); append('\n')
        append("    Layer - ${if (f.surface) "SurfaceView[example.game/Main]" else "example.game/Main"}#${f.layer}${if (f.jank == "None") "" else " [*] "}\n")
        append("    Token: ${f.token}\n    Is Buffer?: ${if (f.buffer) 1 else 0}\n    Owner Pid : ${f.pid}\n")
        append("    Scheduled rendering rate: ${f.fps} fps\n    Layer ID : ${f.layer}\n    Present State : ${f.state}\n")
        if (f.state == "Dropped") append("    Drop time : ${f.drop?.minus(origin) ?: "N/A"}\n")
        append("    Prediction State : ${f.prediction}\n    Jank Type : ${f.jank}\n    Present Metadata : OnTime\n    Finish Metadata: OnTime\n")
        append("    Last latch time: 0.000000\n    Last expected present time: 0.000000\n")
        if (f.prediction == "Valid") append("    Present delta: 0.000000\n")
        append(table("    ", f.prediction, f.expected - origin, f.actual?.minus(origin))); append('\n')
    }
}
private fun frames(first: Int, count: Int = 16) = (first until first + count).map { Buffer(it.toLong()) }
private fun parse(frames: List<Buffer>, origin: Double = 0.0) = FrameTelemetryParser.parse(dump(frames, origin), setOf(4242))
private fun seeded() = FrameTelemetryAnalyzer().also { it.accept(parse(frames(100)), "10042:4242", 1_000) }
private fun fresh(frames: List<Buffer> = frames(200), origin: Double = 0.0) = seeded().accept(parse(frames, origin), "10042:4242", 6_000)

fun main(args: Array<String>) {
    val fixture = File(args.single(), "frametimeline-all.txt").readText()
    val parsed = FrameTelemetryParser.parse(fixture, setOf(4242))
    expect(parsed.supported && parsed.records.size == 3, "Native format fixture must include presented, wrapper and dropped buffers")
    val dropped = parsed.records.single { it.token == 402L }
    expect(dropped.actualPresentMs == null && dropped.dropTimeMs == 39.125, "Native dropped buffer has N/A actual-present")
    expect(parsed.records.first().jank.size == 2, "Child jank union is separate from display jank")
    expect(!FrameTelemetryParser.parse(fixture.dropLast(100), setOf(4242)).supported, "Truncated dump rejected")
    expect(!FrameTelemetryParser.parse(fixture.replace("Number of display frames : 2", "Number of display frames : 3"), setOf(4242)).supported, "Declared count checked")
    expect(!FrameTelemetryParser.parse("x".repeat(FrameTelemetryParser.MAX_BYTES + 1), setOf(4242)).supported, "Parser byte budget")
    expect(!FrameTelemetryParser.parse("Permission Denial: can't dump SurfaceFlinger", setOf(4242)).supported, "Permission denial is unavailable")
    expect(FrameTelemetryParser.parse(fixture, setOf(99)).records.isEmpty(), "Owner PID filtering")
    expect(parse(listOf(Buffer(1, buffer = false))).records.isEmpty(), "Non-buffer container ignored")
    expect(parse(listOf(Buffer(1, displayComplete = false))).records.isEmpty(), "Pending display ignored")
    expect(parse(listOf(Buffer(1, actual = 0.0))).records.isEmpty(), "Invalid/clamped present fence ignored")

    val a = FrameTelemetryAnalyzer()
    expect(a.accept(parse(frames(100)), "10042:4242", 1_000).reason == "collecting_frames", "First ring only warms up")
    val valid = a.accept(parse(frames(200), origin = 6_000.0), "10042:4242", 6_000).window!!
    expect(valid.frameCount == 16 && valid.jankyFrames == 0 && valid.firstFrameToken == 200L && valid.lastFrameToken == 215L, "Fresh classified frame window")
    expect(valid.framePeriodNs == 33_333_333L && valid.refreshPeriodNs == 8_333_333L, "30 fps app differs from 120 Hz display")
    expect(valid.observedDurationNs == 500_000_000L && near(valid.frameTimeP95Ms!!, 33.34), "Observed duration and p95 are real present intervals")
    expect(!valid.contextKey.contains("example") && valid.sampledElapsedMs == 6_000L, "Opaque scope and explicit sample clock")
    expect(a.accept(parse(frames(200), origin = 5_900.0), "10042:4242", 11_000).window == null, "Changing dump origin cannot duplicate tokens")
    val overlap = a.accept(parse(frames(210, 16)), "10042:4242", 16_000).window!!
    expect(overlap.frameCount == 10 && overlap.firstFrameToken == 216L, "Overlapping ring only contributes unseen frames")
    expect(a.accept(parse(frames(300)), "10042:4242", 16_000).window == null, "Non-increasing collection clock rejected")

    val mixed = frames(200).toMutableList()
    mixed[0] = mixed[0].copy(jank = "App Deadline Missed")
    mixed[1] = mixed[1].copy(jank = "SurfaceFlinger deadline missed (while in GPU comp), App Deadline Missed")
    mixed[2] = mixed[2].copy(state = "Dropped", actual = null, drop = mixed[2].expected + 2, jank = "Dropped Frame")
    mixed[3] = mixed[3].copy(jank = "Buffer Stuffing")
    val jank = fresh(mixed).window!!
    expect(jank.frameCount == 16 && jank.jankyFrames == 4 && jank.appJankyFrames == 2 && jank.compositorJankyFrames == 1 && jank.droppedFrames == 1,
        "Count explicit jank/drop union once; buffer stuffing is general jank without CPU/GPU attribution")
    val allDropped = fresh(frames(200).map { it.copy(state = "Dropped", actual = null, drop = it.expected + 2, jank = "Dropped Frame") }).window!!
    expect(allDropped.droppedFrames == 16 && allDropped.jankyFrames == 16 && allDropped.frameTimeP95Ms == null, "All-drop window retains evidence without fake present intervals")
    for ((prediction, jankLabel) in listOf("None" to "None", "Expired" to "App Deadline Missed", "Valid" to "Prediction Error", "Valid" to "Unknown jank", "Valid" to "Non Animating", "Valid" to "ModeChange in progress")) {
        val bad = frames(200).toMutableList().also { it[0] = it[0].copy(prediction = prediction, jank = jankLabel) }
        val result = fresh(bad)
        expect(result.window == null && result.reason == "frames_unavailable" && result.unknownFrames == 1, "Unclassified $prediction/$jankLabel cannot become zero jank")
    }
    expect(fresh(frames(200).map { it.copy(token = -1) }).window == null, "Missing token is unknown")
    expect(fresh(frames(200, 9)).reason == "insufficient_frames", "Small sample gate")
    expect(fresh(frames(200) + frames(200).map { it.copy(layer = 53) }).reason == "frames_changed", "Multiple SurfaceViews are ambiguous")
    val wrapper = fresh(frames(200) + frames(200).map { it.copy(layer = 51, surface = false) }).window!!
    expect(wrapper.frameCount == 16, "Do not double count SurfaceView and its HWUI wrapper")
    expect(fresh(frames(200).map { it.copy(layer = 53) }).reason == "collecting_frames", "Layer replacement must warm up")
    expect(fresh(frames(200).mapIndexed { i, f -> f.copy(refresh = if (i % 2 == 0) 8.333333 else 16.666667) }).reason == "frames_changed", "Mixed refresh periods cannot compare")
    expect(fresh(frames(200).mapIndexed { i, f -> f.copy(fps = if (i % 2 == 0) 30 else 60) }).reason == "frames_changed", "Mixed app schedules cannot compare")
    expect(fresh(frames(200).map { it.copy(fps = 0) }).window?.framePeriodNs in 33_320_000L..33_340_000L, "Stable expected cadence supplies fallback target")
    expect(fresh(frames(200).mapIndexed { i, f -> f.copy(fps = 0, expected = 7000.0 + i * i * 2.0) }).window == null, "Unstable expected cadence cannot become a target")
    val changed = a.accept(parse(frames(300).map { it.copy(fps = 60, refresh = 16.666667) }), "10042:4242", 21_000).window!!
    expect(changed.contextKey != overlap.contextKey && changed.framePeriodNs == 16_666_667L, "Refresh/target change creates a new comparison epoch")
    a.reset()
    expect(a.accept(parse(frames(400)), "10042:4242", 26_000).reason == "collecting_frames", "Reset discards old coverage")
    val review = seeded()
    val beforeReview = review.accept(parse(frames(200)), "10042:4242", 6_000).window!!
    review.pauseForReview(); review.pauseForReview()
    val resumed = review.accept(parse(frames(300)), "10042:4242", 16_000)
    expect(resumed.window == null && resumed.reason == "resuming_frames", "First returning ring is consumed without claiming result coverage")
    val afterReview = review.accept(parse(frames(310, 16)), "10042:4242", 21_000).window!!
    expect(afterReview.contextKey == beforeReview.contextKey && afterReview.firstFrameToken == 316L,
        "Review preserves identity/epoch and excludes every token from the returning ring")
    expect(review.accept(parse(frames(310, 16)), "10042:4242", 26_000).reason == "insufficient_frames",
        "Review grace is not repeated for stale rings")
    for ((label, changedFrames) in listOf(
        "layer" to frames(300).map { it.copy(layer = 99) },
        "refresh" to frames(300).map { it.copy(refresh = 16.666667) },
        "schedule" to frames(300).map { it.copy(fps = 60) },
        "ambiguous" to (frames(300) + frames(300).map { it.copy(layer = 99) }),
    )) {
        val changing = seeded()
        changing.accept(parse(frames(200)), "10042:4242", 6_000)
        changing.pauseForReview()
        expect(changing.accept(parse(changedFrames), "10042:4242", 16_000).reason == "frames_changed",
            "Review cannot hide a changed $label")
    }
    val changedPid = seeded()
    changedPid.accept(parse(frames(200)), "10042:4242", 6_000); changedPid.pauseForReview()
    expect(changedPid.accept(parse(frames(300)), "10042:4243", 16_000).reason == "frames_changed",
        "Review cannot hide process replacement")
    val noNewFrames = seeded()
    noNewFrames.accept(parse(frames(200)), "10042:4242", 6_000); noNewFrames.pauseForReview()
    expect(noNewFrames.accept(parse(frames(200)), "10042:4242", 16_000).reason == "insufficient_frames",
        "A wholly stale ring cannot establish a fresh return to the original workload")
    val noBaseline = FrameTelemetryAnalyzer()
    noBaseline.pauseForReview()
    expect(noBaseline.accept(parse(frames(300)), "10042:4242", 16_000).reason == "collecting_frames",
        "Review without a previous workload cannot manufacture a resumable baseline")
    println("Frame telemetry: $checks checks PASS")
}
