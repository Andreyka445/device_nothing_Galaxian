/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import org.lineageos.settings.galaxian.cpu.runCpuSessionTests

private class FakeCpu : AdaptiveCpuPort {
    var revision = 0L
    var policies = listOf(0, 4).map { AdaptivePolicy(it, 1_000, 3_000, listOf(1_000, 2_000, 3_000), true, false) }
    val attempts = mutableListOf<AdaptiveProposal>()
    val writes = mutableListOf<AdaptiveProposal>()
    var failWrites = false
    var ignoreWrites = false
    var beforeWrite: (() -> Unit)? = null
    override fun snapshot() = AdaptiveCpuSnapshot(policies, revision)
    fun change(id: Int = 4, transform: (AdaptivePolicy) -> AdaptivePolicy) {
        policies = policies.map { if (it.id == id) transform(it) else it }
    }
    fun maximum(id: Int = 4) = policies.first { it.id == id }.maximum
    override fun setMaximum(proposal: AdaptiveProposal): AdaptiveWriteResult {
        attempts += proposal
        beforeWrite?.invoke()
        val policy = policies.first { it.id == proposal.policyId }
        val rejected = when {
            proposal.revision != revision -> "manual_override"
            policy.locked -> "manual_lock"
            proposal.minimum != policy.minimum || proposal.beforeMaximum != policy.maximum -> "external_override"
            failWrites -> "write_failed"
            else -> null
        }
        if (rejected != null) return AdaptiveWriteResult(false, rejected, policy.maximum)
        writes += proposal
        if (ignoreWrites) return AdaptiveWriteResult(false, "readback_failed", policy.maximum)
        change(proposal.policyId) { it.copy(maximum = proposal.targetMaximum) }
        return AdaptiveWriteResult(true, "one_step_trial", proposal.targetMaximum)
    }
}

private fun frames(at: Long, janky: Int = 0, count: Int = 100, context: String = "layer:42:epoch:1") =
    AdaptiveFrameWindow(context, count, janky, 0, janky, 0, 16_666_667, 8_333_333,
        at * 100, at * 100 + count - 1, at, 500_000_000)

private fun sample(at: Long, current: Double = 300.0, load: Double = 20.0, workload: String = "example.app",
    janky: Int = if (load >= 75) 20 else 0) =
    AdaptiveSample(at, 1_000_000 + at, load, 35.0, 55.0, current, 2_000, false, false, true, 0, workload,
        frames = frames(at, janky))

private class Fixture(objective: AdaptiveObjective = AdaptiveObjective.BATTERY, start: Boolean = true,
    reviewWorkloadKey: String? = null) {
    val cpu = FakeCpu()
    val engine = AdaptiveEngine(cpu, windowSize = 3, windowMinimumMs = 10_000, cooldownMs = 10_000, decisionIntervalMs = 0, reviewWorkloadKey = reviewWorkloadKey)
    init { if (start) engine.start(objective, 0, 1_000_000) }
    fun baseline(load: Double = 20.0) { listOf(5_000L, 10_000L, 15_000L).forEach { engine.observe(sample(it, load = load)) } }
    fun approvedBaseline(load: Double = 20.0) {
        baseline(load)
        state().pendingProposal?.let { engine.approveProposal(it.id, sample(16_000, load = load)) }
    }
    fun result(current: Double = 200.0, load: Double = 20.0, workload: String = "example.app") {
        listOf(20_000L, 25_000L, 30_000L).forEach { engine.observe(sample(it, current, load, workload, janky = if (load >= 75) 5 else 0)) }
    }
    fun state(at: Long = 30_000) = engine.snapshot(at)
}

private class FakeGpu : AdaptiveGpuPort {
    private data class Token(val maximum: Long, val revision: Long, val control: Long, val owned: Boolean)
    private data class Restore(val before: Token, val after: Token)
    var maximum = 3_000L
    var revision = 0L
    var controlRevision = 0L
    var owned = false
    var writable = true
    var locked = false
    var unavailable = false
    var failApply = false
    var failRestore = false
    var failReadback = false
    var beforeWrite: (() -> Unit)? = null
    val attempts = mutableListOf<AdaptiveProposal>()
    val writes = mutableListOf<AdaptiveProposal>()
    var restores = 0
    var releases = 0
    private fun token() = Token(maximum, revision, controlRevision, owned)
    override fun snapshot(): AdaptiveGpuSnapshot? = if (unavailable) null else AdaptiveGpuSnapshot(
        AdaptivePolicy(0, 1_000, maximum, listOf(1_000, 2_000, 3_000), writable, locked),
        revision, token().toString(), token(), owned)
    fun manual(value: Long = maximum) { maximum = value; revision++; controlRevision++; owned = false }
    override fun setMaximum(proposal: AdaptiveProposal): AdaptiveWriteResult {
        attempts += proposal
        beforeWrite?.invoke()
        val before = token()
        if (proposal.controlToken != before || locked || !writable || failApply)
            return AdaptiveWriteResult(false, if (proposal.revision != revision) "manual_override" else "write_failed",
                maximum, snapshot())
        maximum = proposal.targetMaximum; owned = true; controlRevision++; writes += proposal
        return AdaptiveWriteResult(!failReadback, if (failReadback) "readback_failed" else "one_step_trial",
            maximum, snapshot(), Restore(before, token()))
    }
    override fun restoreTrial(restoreToken: Any): AdaptiveWriteResult {
        restores++
        val restore = restoreToken as Restore
        if (restore.after != token()) return AdaptiveWriteResult(false, "external_override", maximum, snapshot())
        if (failRestore || unavailable) {
            // A real coordinator advances controlRevision after a failed attempted write,
            // but not when a missing pre-write read prevents touching the driver at all.
            if (failRestore && !unavailable) controlRevision++
            return AdaptiveWriteResult(false, "write_failed", maximum, snapshot())
        }
        maximum = restore.before.maximum; owned = restore.before.owned; controlRevision++
        return AdaptiveWriteResult(true, "restored", maximum, snapshot())
    }
    override fun release(controlToken: Any): AdaptiveWriteResult {
        releases++
        if (controlToken != token() || !owned) return AdaptiveWriteResult(false, "external_override", maximum, snapshot())
        if (failRestore || unavailable) {
            // A real coordinator advances controlRevision after a failed attempted write,
            // but not when a missing pre-write read prevents touching the driver at all.
            if (failRestore && !unavailable) controlRevision++
            return AdaptiveWriteResult(false, "write_failed", maximum, snapshot())
        }
        maximum = 3_000; owned = false; controlRevision++
        return AdaptiveWriteResult(true, "restored", maximum, snapshot())
    }
}

private class BothFixture(objective: AdaptiveObjective = AdaptiveObjective.BATTERY,
    reviewWorkloadKey: String? = null) {
    val cpu = FakeCpu()
    val gpu = FakeGpu()
    val engine = AdaptiveEngine(cpu, windowSize = 3, windowMinimumMs = 10_000, cooldownMs = 10_000,
        decisionIntervalMs = 0, reviewWorkloadKey = reviewWorkloadKey, gpu = gpu)
    init { engine.start(objective, 0, 1_000_000) }
    fun reading(at: Long, cpuLoad: Double = 60.0, gpuLoad: Double = 10.0,
        current: Double = 300.0, janky: Int = if (gpuLoad >= 75 || cpuLoad >= 75) 20 else 0,
        workload: String = "example.app") = sample(at, current, cpuLoad, workload, janky)
            .copy(gpuLoadPercent = gpuLoad, gpuTemperatureC = 55.0)
    fun baseline(cpuLoad: Double = 60.0, gpuLoad: Double = 10.0) {
        listOf(5_000L, 10_000L, 15_000L).forEach { engine.observe(reading(it, cpuLoad, gpuLoad)) }
    }
    fun approve(cpuLoad: Double = 60.0, gpuLoad: Double = 10.0) =
        engine.approveProposal(checkNotNull(state().pendingProposal).id, reading(16_000, cpuLoad, gpuLoad))
    fun result(current: Double = 200.0, cpuLoad: Double = 60.0, gpuLoad: Double = 10.0,
        janky: Int = 0) {
        listOf(20_000L, 25_000L, 30_000L).forEach {
            engine.observe(reading(it, cpuLoad, gpuLoad, current, janky))
        }
    }
    fun state(at: Long = 30_000) = engine.snapshot(at)
}

/** Feed actual analyzer output into the engine so review tests cannot invent stable epochs. */
private fun analyzed(analyzer: FrameTelemetryAnalyzer, sample: AdaptiveSample, first: Long,
    layer: Int = 52, scope: String = "10042:4242", refresh: Long = 8_333_333,
    fps: Int = 60, janky: Boolean = false): AdaptiveSample {
    val records = (first until first + 16).map { token ->
        FrameTimelineRecord(token, 4242, layer, true, "Presented", "Valid",
            if (janky) setOf("App Deadline Missed") else setOf("None"), fps, refresh,
            token * 16.666667, token * 16.666667, null)
    }
    val result = analyzer.accept(FrameTimelineSnapshot(records, true), scope, sample.elapsedMs)
    return sample.copy(frames = result.window, frameTelemetryReason = result.reason)
}

fun main() {
    var tests = 0
    fun test(name: String, body: () -> Unit) {
        body()
        println("PASS $name")
        tests++
    }
    test("adaptive control is off until explicitly started") {
        val f = Fixture(start = false); f.approvedBaseline()
        check(!f.state().enabled && f.cpu.writes.isEmpty())
    }
    test("one OPP on one unlocked policy after a complete baseline") {
        val f = Fixture(); f.engine.observe(sample(5_000)); f.engine.observe(sample(10_000))
        check(f.cpu.writes.isEmpty())
        f.engine.observe(sample(15_000))
        val proposal = checkNotNull(f.state().pendingProposal)
        check(f.cpu.attempts.isEmpty() && f.state().phase == AdaptivePhase.AWAITING_APPROVAL)
        check(f.engine.approveProposal(proposal.id, sample(16_000)).successful)
        check(f.cpu.writes.single().let { it.policyId == 4 && it.beforeMaximum == 3_000L && it.targetMaximum == 2_000L })
        check(f.state().phase == AdaptivePhase.EVALUATING)
    }
    test("sample count alone cannot bypass the minimum measurement duration") {
        val f = Fixture(); listOf(1L, 2L, 3L).forEach { f.engine.observe(sample(it)) }
        check(f.cpu.writes.isEmpty())
    }
    test("manual locks remain observation only") {
        val f = Fixture(); f.cpu.policies = f.cpu.policies.map { it.copy(locked = true) }; f.approvedBaseline()
        check(f.cpu.writes.isEmpty() && f.state().reason == "manual_lock")
    }
    test("read-only policies remain observation only") {
        val f = Fixture(); f.cpu.policies = f.cpu.policies.map { it.copy(writable = false) }; f.approvedBaseline()
        check(f.cpu.writes.isEmpty())
    }
    test("candidate never lowers maximum below the current minimum") {
        val f = Fixture(); f.cpu.policies = f.cpu.policies.map { it.copy(minimum = 3_000) }; f.approvedBaseline()
        check(f.cpu.writes.isEmpty())
    }
    test("battery objective avoids reducing a busy CPU") {
        val f = Fixture(); f.approvedBaseline(load = 80.0); check(f.cpu.writes.isEmpty())
    }
    test("performance objective needs a busy CPU and raises just one OPP") {
        val f = Fixture(AdaptiveObjective.PERFORMANCE)
        f.cpu.change { it.copy(maximum = 2_000) }; f.approvedBaseline(load = 90.0)
        check(f.cpu.writes.single().targetMaximum == 3_000L)
        f.result(current = 320.0, load = 90.0)
        check(f.state().reason == "frame_improved")
        check(f.state().journal.none { it.reason.contains("performance_gain") })
    }
    test("balanced objective leaves middle-range demand alone") {
        val f = Fixture(AdaptiveObjective.BALANCED); f.approvedBaseline(load = 50.0); check(f.cpu.writes.isEmpty())
    }
    test("comparable lower current retains the trial with measured after-minus-before delta") {
        val f = Fixture(); f.approvedBaseline(); f.result()
        val evaluated = f.state().journal.last { it.action == "evaluated" }
        check(evaluated.reason == "current_improved" && evaluated.measuredDeltaMa == -100.0)
        check(evaluated.beforeSample?.dischargeCurrentMa == 300.0 && evaluated.afterSample?.dischargeCurrentMa == 200.0)
        check(f.cpu.maximum() == 2_000L && f.state().trialPolicyId == null)
    }
    test("no measured current improvement rolls back the trial") {
        val f = Fixture(); f.approvedBaseline(); f.result(current = 300.0)
        check(f.cpu.maximum() == 3_000L && f.cpu.writes.size == 2)
        check(f.state().reason == "no_measured_improvement")
    }
    test("a rejected candidate is not retried after the ordinary cooldown") {
        val f = Fixture(); f.cpu.policies = f.cpu.policies.filter { it.id == 4 }
        f.approvedBaseline(); f.result(current = 300.0)
        listOf(40_000L, 45_000L, 50_000L).forEach { f.engine.observe(sample(it)) }
        check(f.cpu.writes.size == 2)
    }
    test("performance trial above the measured budget rolls back") {
        val f = Fixture(AdaptiveObjective.PERFORMANCE); f.cpu.change { it.copy(maximum = 2_000) }
        f.approvedBaseline(load = 90.0); f.result(current = 400.0, load = 90.0)
        check(f.cpu.maximum() == 2_000L && f.state().reason == "budget_regression")
    }
    test("foreground workload changes make comparisons inconclusive") {
        val f = Fixture(); f.approvedBaseline(); f.result(workload = "different.app")
        check(f.cpu.maximum() == 3_000L && f.state().reason == "inconclusive_context")
    }
    test("different CPU demand cannot be attributed to the frequency trial") {
        val f = Fixture(); f.approvedBaseline(); f.result(load = 40.0)
        check(f.cpu.maximum() == 3_000L && f.state().reason == "inconclusive_context")
    }
    test("noisy discharge measurements cannot be accepted as a benefit") {
        val f = Fixture(); f.approvedBaseline()
        listOf(20_000L to 100.0, 25_000L to 500.0, 30_000L to 200.0).forEach { (t, c) -> f.engine.observe(sample(t, c)) }
        check(f.cpu.maximum() == 3_000L && f.state().reason == "inconclusive_noise")
    }
    test("memory context changes invalidate before-after comparison") {
        val f = Fixture(); f.approvedBaseline()
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it, 200.0).copy(availableMemoryMiB = 1_000)) }
        check(f.cpu.maximum() == 3_000L && f.state().reason == "inconclusive_context")
    }
    for ((name, blocked) in listOf<Pair<String, (AdaptiveSample) -> AdaptiveSample>>(
        "missing_telemetry" to { it.copy(cpuTemperatureC = null) },
        "charging" to { it.copy(charging = true, dischargeCurrentMa = null) },
        "thermal_limit" to { it.copy(cpuTemperatureC = 80.0) },
        "memory_pressure" to { it.copy(lowMemory = true) },
    )) test("$name immediately unwinds an active owned trial") {
        val f = Fixture(); f.approvedBaseline()
        f.engine.observe(blocked(sample(20_000)))
        check(f.cpu.maximum() == 3_000L && f.state().trialPolicyId == null && f.state().reason == name)
        f.engine.observe(blocked(sample(25_000)))
        check(f.cpu.writes.size == 2)
    }
    test("severe framework thermal status prevents new trials") {
        val f = Fixture(); listOf(5_000L, 10_000L, 15_000L).forEach { f.engine.observe(sample(it).copy(thermalStatus = 3)) }
        check(f.cpu.writes.isEmpty() && f.state().reason == "thermal_limit")
    }
    test("stale telemetry cancels a trial instead of appearing freshly collected") {
        val f = Fixture(); f.approvedBaseline(); f.engine.observe(sample(20_000), nowElapsedMs = 40_001)
        check(f.cpu.maximum() == 3_000L && f.state().reason == "missing_telemetry")
        check(f.state().latestSample?.elapsedMs == 16_000L)
    }
    test("nonmonotonic telemetry cannot move the accepted watermark backwards") {
        val f = Fixture(); f.approvedBaseline(); f.engine.observe(sample(10_000)); f.engine.observe(sample(12_000))
        check(f.state().latestSample?.elapsedMs == 16_000L)
        check(f.state().reason == "missing_telemetry" && f.cpu.writes.size == 2)
    }
    test("a trial without a complete result window expires and restores") {
        val f = Fixture(); f.approvedBaseline(); f.engine.observe(sample(140_001))
        check(f.cpu.maximum() == 3_000L && f.state(140_001).reason == "inconclusive_samples")
    }
    test("normal stop restores an accepted override to its original maximum") {
        val f = Fixture(); f.approvedBaseline(); f.result(); f.engine.stop(31_000, 1_031_000)
        check(f.cpu.maximum() == 3_000L && !f.state().enabled)
    }
    test("manual intent revokes AI ownership even if it preserves the exact limit") {
        val f = Fixture(); f.approvedBaseline(); f.cpu.revision++
        f.engine.observe(sample(20_000)); f.engine.stop(21_000, 1_021_000)
        check(f.cpu.maximum() == 2_000L && f.cpu.writes.size == 1)
    }
    test("manual intent before Stop is caught by the atomic CPU coordinator contract") {
        val f = Fixture(); f.approvedBaseline(); f.cpu.revision++; f.engine.stop(16_000, 1_016_000)
        check(f.cpu.maximum() == 2_000L && f.cpu.writes.size == 1)
        check(f.state().journal.any { it.action == "rollback" && it.reason == "manual_override" })
    }
    test("externally replaced limit is left untouched and controller yields") {
        val f = Fixture(); f.approvedBaseline(); f.cpu.change { it.copy(maximum = 1_000) }
        f.engine.observe(sample(20_000)); f.engine.stop(21_000, 1_021_000)
        check(f.cpu.maximum() == 1_000L && f.cpu.writes.size == 1)
    }
    test("failed readback gets one write, a rejection and no retry during cooldown") {
        val f = Fixture(); f.cpu.ignoreWrites = true; f.approvedBaseline(); f.engine.observe(sample(20_000))
        check(f.cpu.writes.size == 1 && f.cpu.maximum() == 3_000L)
        check(f.state().journal.any { it.action == "readback" && it.reason == "readback_failed" })
    }
    test("failed safety restore is suspended until an explicit stop retry") {
        val f = Fixture(); f.approvedBaseline(); f.cpu.failWrites = true
        f.engine.observe(sample(20_000).copy(cpuTemperatureC = null))
        val count = f.cpu.attempts.size
        listOf(25_000L, 30_000L, 90_000L).forEach { f.engine.observe(sample(it)) }
        check(f.cpu.attempts.size == count && f.state().reason == "write_failed")
        f.engine.stop(91_000, 1_091_000)
        check(f.state().reason == "write_failed")
        f.cpu.failWrites = false; f.engine.stop(92_000, 1_092_000)
        check(f.cpu.maximum() == 3_000L && f.state().reason == "disabled")
    }
    test("failed trial rollback preserves only a suspended and still-owned restore lease") {
        val f = Fixture(); f.approvedBaseline(); f.cpu.failWrites = true; f.result(current = 300.0)
        val count = f.cpu.attempts.size; f.engine.observe(sample(90_000))
        check(f.cpu.attempts.size == count && f.state().reason == "write_failed")
        f.cpu.failWrites = false; f.engine.stop(91_000, 1_091_000)
        check(f.cpu.maximum() == 3_000L)
    }
    test("switching objective restores existing ownership before observing afresh") {
        val f = Fixture(); f.approvedBaseline(); f.engine.changeObjective(AdaptiveObjective.PERFORMANCE, 16_000, 1_016_000)
        check(f.cpu.maximum() == 3_000L && f.state().objective == AdaptiveObjective.PERFORMANCE)
        check(f.state().enabled && f.state().trialPolicyId == null)
    }
    test("journal remains bounded and does not claim unobserved outcomes") {
        val f = Fixture()
        (1L..200L).forEach { f.engine.observe(sample(it * 60_000).copy(cpuTemperatureC = null)) }
        check(f.state().journal.size == 120 && f.state().journal.none { it.action == "applied" })
    }
    test("a complete baseline proposes but never calls the CPU writer without consent") {
        val f = Fixture(); f.baseline()
        val request = checkNotNull(f.state().pendingProposal)
        check(request.proposal.policyId == 4 && request.reason == "proposal_lower_frequency")
        check(request.baseline.cpuLoadPercent == 20.0 && request.expiresElapsedMs == 135_000L)
        listOf(20_000L, 25_000L, 30_000L, 35_000L).forEach { f.engine.observe(sample(it)) }
        check(f.state().pendingProposal?.id == request.id)
        check(f.cpu.attempts.isEmpty() && f.state().journal.count { it.action == "proposed" } == 1)
    }
    test("default decision cadence waits at least sixty seconds and sufficient observations") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        (1L..11L).forEach { engine.observe(sample(it * 5_000)) }
        check(engine.snapshot(55_000).pendingProposal == null && cpu.attempts.isEmpty())
        engine.observe(sample(60_000))
        check(engine.snapshot(60_000).pendingProposal?.createdElapsedMs == 60_000L)
        check(cpu.attempts.isEmpty())
    }
    test("snapshot polling and five-second samples preserve the scheduled decision deadline") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        for (second in 0L..60L) {
            val now = second * 1_000
            if (second > 0 && second < 60 && second % 5 == 0L) engine.observe(sample(now))
            check(engine.snapshot(now).cooldownRemainingMs == 60_000 - now)
            check(engine.snapshot(now).pendingProposal == null)
        }
        engine.observe(sample(60_000))
        check(engine.snapshot(60_000).pendingProposal?.createdElapsedMs == 60_000L)
        check(engine.snapshot(60_000).cooldownRemainingMs == 0L && cpu.attempts.isEmpty())
    }
    for ((name, blocked) in listOf<Pair<String, (AdaptiveSample) -> AdaptiveSample>>(
        "missing_telemetry" to { it.copy(cpuTemperatureC = null) },
        "charging" to { it.copy(charging = true, dischargeCurrentMa = null) },
        "thermal_limit" to { it.copy(cpuTemperatureC = 80.0) },
        "memory_pressure" to { it.copy(lowMemory = true) },
    )) test("repeated $name samples do not restart the recovery countdown") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        for (tick in 1L..26L) {
            val now = tick * 5_000
            engine.observe(blocked(sample(now)))
            val state = engine.snapshot(now)
            check(state.phase == AdaptivePhase.UNAVAILABLE && state.reason == name)
            check(state.cooldownRemainingMs == (65_000 - now).coerceAtLeast(0))
            check(engine.snapshot(now + 4_999).cooldownRemainingMs ==
                (65_000 - now - 4_999).coerceAtLeast(0))
            check(state.pendingProposal == null && cpu.attempts.isEmpty())
        }
        val journal = engine.snapshot(130_000).journal
        check(journal.all { it.action == "started" || it.action == "sample" })
        val periodic = journal.filter { it.action == "sample" }
        check(periodic.size == 3)
        check(periodic.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs >= 60_000 })
    }
    test("changing the unavailable reason does not start another blocked episode") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        engine.observe(sample(5_000).copy(charging = true))
        engine.observe(sample(10_000).copy(cpuTemperatureC = null))
        engine.observe(sample(15_000).copy(lowMemory = true))
        check(engine.snapshot(15_000).cooldownRemainingMs == 50_000L)
        check(cpu.attempts.isEmpty())
    }
    test("stale repeated samples cannot postpone the clock-based recovery deadline") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        engine.observe(sample(5_000))
        for (now in 20_000L..90_000L step 5_000) {
            engine.observe(sample(5_000), nowElapsedMs = now)
            val state = engine.snapshot(now)
            check(state.reason == "missing_telemetry")
            check(state.cooldownRemainingMs == (80_000 - now).coerceAtLeast(0))
            check(state.pendingProposal == null && cpu.attempts.isEmpty())
        }
    }
    test("fresh delayed readings use the same recovery deadline as the displayed clock") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        engine.observe(sample(5_000).copy(charging = true), nowElapsedMs = 15_000)
        engine.observe(sample(20_000).copy(charging = true), nowElapsedMs = 30_000)
        check(engine.snapshot(74_999).cooldownRemainingMs == 1L)
        engine.observe(sample(65_000), nowElapsedMs = 75_000)
        val state = engine.snapshot(75_000)
        check(state.cooldownRemainingMs == 0L && state.phase == AdaptivePhase.OBSERVING)
        check(state.reason == "collecting_baseline")
        check(state.pendingProposal == null && cpu.attempts.isEmpty())
    }
    test("expired recovery interval still requires a fresh complete safe baseline") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        for (now in 5_000L..75_000L step 5_000) engine.observe(sample(now).copy(charging = true))
        check(engine.snapshot(75_000).cooldownRemainingMs == 0L)
        for (now in 80_000L..100_000L step 5_000) {
            engine.observe(sample(now))
            check(engine.snapshot(now).pendingProposal == null && cpu.attempts.isEmpty())
        }
        engine.observe(sample(105_000))
        check(engine.snapshot(105_000).pendingProposal?.createdElapsedMs == 105_000L)
        check(cpu.attempts.isEmpty())
    }
    test("a distinct telemetry failure after recovery gets its own stable interval") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        engine.observe(sample(5_000).copy(charging = true))
        engine.observe(sample(10_000))
        engine.observe(sample(15_000).copy(cpuTemperatureC = null))
        check(engine.snapshot(15_000).cooldownRemainingMs == 60_000L)
        engine.observe(sample(20_000).copy(cpuTemperatureC = null))
        check(engine.snapshot(20_000).cooldownRemainingMs == 55_000L)
        check(cpu.attempts.isEmpty())
    }
    test("OFF never exposes a countdown after stop restart or objective changes") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        check(engine.snapshot(0).cooldownRemainingMs == 0L)
        engine.start(AdaptiveObjective.BATTERY, 0, 1_000_000)
        engine.observe(sample(5_000).copy(charging = true))
        engine.stop(10_000, 1_010_000)
        check(!engine.snapshot(10_000).enabled && engine.snapshot(10_000).cooldownRemainingMs == 0L)
        engine.changeObjective(AdaptiveObjective.PERFORMANCE, 20_000, 1_020_000)
        check(engine.snapshot(20_000).cooldownRemainingMs == 0L)
        engine.start(AdaptiveObjective.PERFORMANCE, 30_000, 1_030_000)
        check(engine.snapshot(30_000).cooldownRemainingMs == 60_000L)
        check(engine.snapshot(35_000).cooldownRemainingMs == 55_000L)
        engine.stop(40_000, 1_040_000)
        check(engine.snapshot(40_000).cooldownRemainingMs == 0L)
    }
    test("five-second sampling adds at most one periodic journal entry per sixty seconds") {
        val cpu = FakeCpu(); val engine = AdaptiveEngine(cpu)
        engine.start(AdaptiveObjective.BALANCED, 0, 1_000_000)
        (1L..40L).forEach { engine.observe(sample(it * 5_000, load = 50.0)) }
        val journal = engine.snapshot(200_000).journal
        val observations = journal.filter { it.action == "sample" }
        check(observations.size == 4 && journal.size == 5)
        check(observations.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs >= 60_000 })
        check(cpu.attempts.isEmpty())
    }
    test("duplicate start and stop commands do not create repeated journal transitions") {
        val f = Fixture(); f.engine.start(AdaptiveObjective.BATTERY, 1, 1_000_001)
        f.engine.stop(2, 1_000_002); f.engine.stop(3, 1_000_003)
        check(f.state().journal.count { it.action == "started" } == 1)
        check(f.state().journal.count { it.action == "stopped" } == 1)
        check(f.cpu.attempts.isEmpty())
    }
    test("approval is single-use and a forged ID cannot approve the current request") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        check(!f.engine.approveProposal("different-id", sample(16_000)).successful)
        check(f.state().pendingProposal?.id == id && f.cpu.attempts.isEmpty())
        check(f.engine.approveProposal(id, sample(17_000)).successful)
        check(!f.engine.approveProposal(id, sample(18_000)).successful)
        check(f.cpu.attempts.size == 1 && f.state().pendingProposal == null)
    }
    test("a stale reject ID leaves the current request intact") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        check(!f.engine.rejectProposal("old-id", 16_000, 1_016_000).successful)
        check(f.state().pendingProposal?.id == id && f.cpu.attempts.isEmpty())
    }
    test("rejecting a proposal never writes and backs off that candidate for ten minutes") {
        val f = Fixture(); f.cpu.policies = f.cpu.policies.filter { it.id == 4 }; f.baseline()
        val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.rejectProposal(id, 16_000, 1_016_000).successful)
        listOf(30_000L, 35_000L, 40_000L).forEach { f.engine.observe(sample(it)) }
        check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty())
        listOf(620_000L, 625_000L, 630_000L).forEach { f.engine.observe(sample(it)) }
        check(f.state(630_000).pendingProposal?.id?.let { it != id } == true)
        check(f.cpu.attempts.isEmpty())
    }
    test("approval checks expiry even before the next service tick") {
        val f = Fixture(); f.baseline(); val request = checkNotNull(f.state().pendingProposal)
        val result = f.engine.approveProposal(request.id, sample(request.expiresElapsedMs))
        check(!result.successful && result.reason == "proposal_expired")
        check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty())
        check(f.state().journal.count { it.reason == "proposal_expired" } == 1)
    }
    test("ordinary observation expires a pending request without applying it") {
        val f = Fixture(); f.baseline(); val request = checkNotNull(f.state().pendingProposal)
        f.engine.observe(sample(request.expiresElapsedMs))
        check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty())
        check(f.state().reason == "proposal_expired")
    }
    test("stopping or changing objective invalidates pending consent") {
        for (changeObjective in listOf(false, true)) {
            val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
            if (changeObjective) f.engine.changeObjective(AdaptiveObjective.PERFORMANCE, 16_000, 1_016_000)
            else f.engine.stop(16_000, 1_016_000)
            check(!f.engine.approveProposal(id, sample(17_000)).successful)
            check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty())
        }
    }
    test("manual revision is checked before an approval can reach the CPU writer") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        f.cpu.revision++
        check(f.engine.approveProposal(id, sample(16_000)).reason == "manual_override")
        check(f.cpu.attempts.isEmpty() && f.state().pendingProposal == null)
    }
    for ((name, change) in listOf<Pair<String, (AdaptivePolicy) -> AdaptivePolicy>>(
        "manual_lock" to { it.copy(locked = true) },
        "external_override" to { it.copy(maximum = 1_000) },
        "external_override" to { it.copy(minimum = 2_000) },
        "write_failed" to { it.copy(writable = false) },
        "candidate_rejected" to { it.copy(frequencies = listOf(1_000, 3_000)) },
    )) test("approval revalidates CPU state: $name") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        f.cpu.change(transform = change)
        check(f.engine.approveProposal(id, sample(16_000)).reason == name)
        check(f.cpu.attempts.isEmpty() && f.state().pendingProposal == null)
    }
    test("the atomic CPU port still rejects a manual race after approval validation") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        f.cpu.beforeWrite = { f.cpu.revision++ }
        check(f.engine.approveProposal(id, sample(16_000)).reason == "manual_override")
        check(f.cpu.writes.isEmpty() && f.cpu.attempts.size == 1 && f.state().pendingProposal == null)
    }
    for ((name, change) in listOf<Pair<String, (AdaptiveSample) -> AdaptiveSample>>(
        "charging" to { it.copy(charging = true) },
        "thermal_limit" to { it.copy(cpuTemperatureC = 85.0) },
        "missing_telemetry" to { it.copy(thermalStatus = null) },
        "memory_pressure" to { it.copy(lowMemory = true) },
        "inconclusive_context" to { it.copy(workloadKey = "different.app") },
    )) test("approval requires fresh usable matching telemetry: $name") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, change(sample(16_000))).reason == name)
        check(f.cpu.attempts.isEmpty() && f.state().pendingProposal == null)
    }
    test("stale approval telemetry cannot be relabeled as fresh") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, sample(16_000), 26_001).reason == "missing_telemetry")
        check(f.cpu.attempts.isEmpty())
    }
    test("a long telemetry gap requires a new baseline instead of writing on approval") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, sample(40_000)).reason == "inconclusive_samples")
        check(f.cpu.attempts.isEmpty())
    }
    test("an approved trial compares against refreshed measurements, not the notification's old baseline") {
        val f = Fixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it, current = 200.0)) }
        check(f.engine.approveProposal(id, sample(31_000, current = 200.0)).successful)
        val applied = f.state().journal.last { it.action == "applied" }
        check(applied.beforeSample?.dischargeCurrentMa == 200.0)
        listOf(35_000L, 40_000L, 45_000L).forEach { f.engine.observe(sample(it, current = 100.0)) }
        check(f.state().journal.last { it.action == "evaluated" }.measuredDeltaMa == -100.0)
    }
    test("old notification IDs cannot survive session or process restart") {
        val f = Fixture(); f.baseline(); val old = checkNotNull(f.state().pendingProposal).id
        f.engine.stop(16_000, 1_016_000); f.engine.start(AdaptiveObjective.BATTERY, 17_000, 1_017_000)
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it)) }
        val newer = checkNotNull(f.state().pendingProposal).id
        check(newer != old && !f.engine.approveProposal(old, sample(31_000)).successful)
        check(f.state().pendingProposal?.id == newer && f.cpu.attempts.isEmpty())
        val restarted = AdaptiveEngine(f.cpu)
        restarted.seedJournal(f.state().journal)
        check(restarted.snapshot(31_000).pendingProposal == null)
        check(!restarted.approveProposal(newer, sample(32_000)).successful && f.cpu.attempts.isEmpty())
    }
    test("changed CPU demand revalidates the objective even within comparable context limits") {
        val f = Fixture(); f.baseline(load = 48.0)
        val id = checkNotNull(f.state().pendingProposal).id
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it, load = 55.0)) }
        check(f.state().pendingProposal?.id == id)
        check(f.engine.approveProposal(id, sample(31_000, load = 55.0)).reason == "candidate_rejected")
        check(f.cpu.attempts.isEmpty())
    }
    test("notification review preserves the proposal and compares results only after the original workload returns") {
        val reviewApp = "org.lineageos.settings.galaxian"
        val f = Fixture(AdaptiveObjective.PERFORMANCE, reviewWorkloadKey = reviewApp)
        f.cpu.change { it.copy(maximum = 2_000) }; f.baseline(load = 90.0)
        val id = checkNotNull(f.state().pendingProposal).id
        listOf(20_000L, 25_000L, 30_000L).forEach {
            f.engine.observe(sample(it, current = 100.0, load = 5.0, workload = reviewApp))
        }
        check(f.state().pendingProposal?.id == id && f.cpu.attempts.isEmpty())
        check(f.engine.approveProposal(id, sample(31_000, current = 100.0, load = 5.0, workload = reviewApp)).successful)
        check(f.cpu.maximum() == 3_000L && f.state().reason == "waiting_original_workload")
        listOf(35_000L, 40_000L, 45_000L).forEach {
            f.engine.observe(sample(it, current = 100.0, load = 5.0, workload = reviewApp))
        }
        check(f.state().journal.none { it.action == "evaluated" })
        listOf(50_000L, 55_000L, 60_000L).forEach { f.engine.observe(sample(it, current = 320.0, load = 90.0, janky = 5)) }
        val evaluated = f.state().journal.last { it.action == "evaluated" }
        check(evaluated.reason == "frame_improved" && evaluated.measuredDeltaMa == 20.0)
        check(evaluated.beforeSample?.workloadKey == "example.app" && evaluated.afterSample?.workloadKey == "example.app")
    }
    test("reviewing never exempts charging or thermal and memory safety checks") {
        val reviewApp = "org.lineageos.settings.galaxian"
        for ((name, change) in listOf<Pair<String, (AdaptiveSample) -> AdaptiveSample>>(
            "charging" to { it.copy(charging = true) },
            "thermal_limit" to { it.copy(cpuTemperatureC = 85.0) },
            "missing_telemetry" to { it.copy(cpuTemperatureC = null) },
            "memory_pressure" to { it.copy(lowMemory = true) },
        )) {
            val f = Fixture(reviewWorkloadKey = reviewApp); f.baseline()
            val id = checkNotNull(f.state().pendingProposal).id
            val result = f.engine.approveProposal(id, change(sample(16_000, workload = reviewApp)))
            check(!result.successful && result.reason == name && f.cpu.attempts.isEmpty())
        }
    }
    test("reviewing does not exempt manual CPU changes or proposal expiry") {
        val reviewApp = "org.lineageos.settings.galaxian"
        val f = Fixture(reviewWorkloadKey = reviewApp); f.baseline()
        val request = checkNotNull(f.state().pendingProposal)
        f.cpu.revision++
        check(f.engine.approveProposal(request.id, sample(16_000, workload = reviewApp)).reason == "manual_override")
        val expired = Fixture(reviewWorkloadKey = reviewApp); expired.baseline()
        val stale = checkNotNull(expired.state().pendingProposal)
        check(expired.engine.approveProposal(stale.id,
            sample(stale.expiresElapsedMs, workload = reviewApp)).reason == "proposal_expired")
        check(f.cpu.attempts.isEmpty() && expired.cpu.attempts.isEmpty())
    }
    test("another app cannot masquerade as the proposal review workload") {
        val f = Fixture(reviewWorkloadKey = "org.lineageos.settings.galaxian"); f.baseline()
        val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, sample(16_000, workload = "some.other.app")).reason == "inconclusive_context")
        check(f.cpu.attempts.isEmpty())
    }
    test("a trial that stays on the review screen times out and restores without claiming an improvement") {
        val reviewApp = "org.lineageos.settings.galaxian"
        val f = Fixture(reviewWorkloadKey = reviewApp); f.baseline()
        val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, sample(16_000, workload = reviewApp)).successful)
        f.engine.observe(sample(136_001, workload = reviewApp))
        check(f.cpu.maximum() == 3_000L && f.state().trialPolicyId == null)
        check(f.state().reason == "inconclusive_samples")
        check(f.state().journal.none { it.reason == "current_improved" || it.reason == "frame_improved" })
    }
    test("a baseline originally collected in Parts still follows ordinary comparison rules") {
        val reviewApp = "org.lineageos.settings.galaxian"
        val f = Fixture(reviewWorkloadKey = reviewApp)
        listOf(5_000L, 10_000L, 15_000L).forEach { f.engine.observe(sample(it, workload = reviewApp)) }
        val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, sample(16_000, workload = reviewApp)).successful)
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it, 200.0, workload = reviewApp)) }
        check(f.state().reason == "current_improved")
    }
    test("frame telemetry absent leaves both devices observation-only") {
        val f = BothFixture()
        listOf(5_000L, 10_000L, 15_000L).forEach { f.engine.observe(f.reading(it).copy(frames = null)) }
        check(f.state().pendingProposal == null && f.state().reason == "frames_unavailable")
        check(f.cpu.attempts.isEmpty() && f.gpu.attempts.isEmpty())
    }
    test("busy CPU without application jank never proposes an increase") {
        val f = Fixture(AdaptiveObjective.PERFORMANCE); f.cpu.change { it.copy(maximum = 2_000) }
        listOf(5_000L, 10_000L, 15_000L).forEach { f.engine.observe(sample(it, load = 90.0, janky = 0)) }
        check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty())
    }
    test("compositor-only jank is not mistaken for an app CPU bottleneck") {
        val f = Fixture(AdaptiveObjective.PERFORMANCE); f.cpu.change { it.copy(maximum = 2_000) }
        listOf(5_000L, 10_000L, 15_000L).forEach {
            f.engine.observe(sample(it, load = 90.0).copy(frames = frames(it, 20).copy(appJankyFrames = 0, compositorJankyFrames = 20)))
        }
        check(f.state().pendingProposal == null)
    }
    test("low utilization cannot justify a reduction while frames already stutter") {
        val f = Fixture()
        listOf(5_000L, 10_000L, 15_000L).forEach { f.engine.observe(sample(it, janky = 10)) }
        check(f.state().pendingProposal == null)
    }
    test("increased frequency within electrical budget but without frame benefit is reverted") {
        val f = Fixture(AdaptiveObjective.PERFORMANCE); f.cpu.change { it.copy(maximum = 2_000) }
        f.approvedBaseline(load = 90.0)
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it, 320.0, 90.0)) }
        check(f.state().reason == "no_frame_improvement" && f.cpu.maximum() == 2_000L)
    }
    test("current savings do not justify worse frame delivery") {
        val f = Fixture(); f.approvedBaseline()
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(sample(it, 100.0, janky = 10)) }
        check(f.state().reason == "frame_regression" && f.cpu.maximum() == 3_000L)
    }
    test("a newly dropped frame is a regression even if total jank is unchanged") {
        val f = Fixture(); f.approvedBaseline()
        listOf(20_000L, 25_000L, 30_000L).forEach {
            f.engine.observe(sample(it, 100.0).copy(frames = frames(it).copy(droppedFrames = 1)))
        }
        check(f.state().reason == "frame_regression" && f.cpu.maximum() == 3_000L)
    }
    for ((label, change) in listOf<Pair<String, (AdaptiveFrameWindow) -> AdaptiveFrameWindow>>(
        "surface or process epoch" to { it.copy(contextKey = "layer:43:epoch:2") },
        "scheduled target FPS" to { it.copy(framePeriodNs = 33_333_333) },
        "display refresh rate" to { it.copy(refreshPeriodNs = 16_666_667) },
    )) test("changed $label cannot create a false before-after improvement") {
        val f = Fixture(); f.approvedBaseline()
        listOf(20_000L, 25_000L, 30_000L).forEach {
            f.engine.observe(sample(it, 200.0).let { value -> value.copy(frames = change(value.frames!!)) })
        }
        check(f.state().reason == "frames_changed" && f.cpu.maximum() == 3_000L)
    }
    test("thirty-FPS content on a 120-Hz display with no missed deadlines is smooth") {
        val f = Fixture()
        listOf(5_000L, 10_000L, 15_000L).forEach {
            f.engine.observe(sample(it).copy(frames = frames(it, count = 16).copy(framePeriodNs = 33_333_333)))
        }
        check(f.state().pendingProposal != null && f.state().pendingProposal!!.baseline.frames!!.jankyFrames == 0)
    }
    test("tiny, stale and invalid frame observations never authorize writes") {
        for (change in listOf<(AdaptiveFrameWindow) -> AdaptiveFrameWindow>(
            { it.copy(frameCount = 0) }, { it.copy(frameCount = 7) },
            { it.copy(jankyFrames = 101) }, { it.copy(framePeriodNs = 0) },
            { it.copy(sampledElapsedMs = -20_000) }, { it.copy(sampledElapsedMs = 100_000) },
        )) {
            val f = Fixture()
            listOf(5_000L, 10_000L, 15_000L).forEach {
                f.engine.observe(sample(it).let { value -> value.copy(frames = change(value.frames!!)) })
            }
            check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty())
        }
    }
    test("duplicate frame windows cannot supply a measurement baseline") {
        val f = Fixture()
        listOf(5_000L, 10_000L, 15_000L).forEach {
            f.engine.observe(sample(it).copy(frames = frames(it).copy(firstFrameToken = 100, lastFrameToken = 199)))
        }
        check(f.state().pendingProposal == null && f.state().reason == "inconclusive_frames")
    }
    test("overlapping pre-trial frame tokens cannot count as a post-trial result") {
        val f = Fixture(); f.approvedBaseline()
        listOf(20_000L, 25_000L, 30_000L).forEachIndexed { index, at ->
            f.engine.observe(sample(at, 200.0).copy(frames = frames(at).copy(firstFrameToken = 100L + index * 100,
                lastFrameToken = 199L + index * 100)))
        }
        check(f.cpu.maximum() == 3_000L && f.state().reason == "inconclusive_frames")
    }
    test("frame rates aggregate real counts rather than averaging unequal percentages") {
        val f = Fixture(AdaptiveObjective.PERFORMANCE); f.cpu.change { it.copy(maximum = 2_000) }
        listOf(5_000L to (40 to 20), 10_000L to (10 to 0), 15_000L to (10 to 0)).forEach { (at, counts) ->
            f.engine.observe(sample(at, load = 90.0).copy(frames = frames(at, counts.second, counts.first)))
        }
        val id = checkNotNull(f.state().pendingProposal).id
        check(f.engine.approveProposal(id, sample(16_000, load = 90.0).copy(frames = frames(16_000, 5, 10))).successful)
        listOf(20_000L to (40 to 0), 25_000L to (10 to 6), 30_000L to (10 to 6)).forEach { (at, counts) ->
            f.engine.observe(sample(at, 300.0, 90.0).copy(frames = frames(at, counts.second, counts.first)))
        }
        val event = f.state().journal.last { it.action == "evaluated" }
        check(event.reason == "frame_improved")
        check(event.beforeSample!!.frames!!.frameCount == 70 && event.beforeSample.frames!!.jankyFrames == 25)
        check(event.afterSample!!.frames!!.frameCount == 60 && event.afterSample.frames!!.jankyFrames == 12)
        check(event.afterSample.frames!!.frameTimeP95Ms == null)
    }
    test("GPU proposal is explicit, separate and cannot write before approval") {
        val f = BothFixture(); f.baseline()
        val request = checkNotNull(f.state().pendingProposal)
        check(request.proposal.target == AdaptiveTarget.GPU && f.cpu.attempts.isEmpty() && f.gpu.attempts.isEmpty())
        check(f.approve().successful && f.gpu.writes.single().targetMaximum == 2_000L)
        check(f.state().trialTarget == AdaptiveTarget.GPU && f.cpu.attempts.isEmpty())
        check(f.state().journal.filter { it.action in setOf("proposed", "approved", "applied") }.all { it.target == AdaptiveTarget.GPU })
    }
    test("accepted GPU reduction is released to automatic on Stop") {
        val f = BothFixture(); f.baseline(); check(f.approve().successful); f.result()
        check(f.state().reason == "current_improved" && f.gpu.maximum == 2_000L && f.gpu.owned)
        f.engine.stop(31_000, 1_031_000)
        check(f.gpu.maximum == 3_000L && !f.gpu.owned && f.gpu.releases == 1 && f.gpu.restores == 0)
    }
    test("GPU trial with no benefit uses trial rollback and relinquishes original automatic ownership") {
        val f = BothFixture(); f.baseline(); check(f.approve().successful); f.result(current = 300.0)
        check(f.gpu.maximum == 3_000L && !f.gpu.owned && f.gpu.restores == 1 && f.gpu.releases == 0)
    }
    test("busy GPU and observed app jank can propose a GPU boost without a busy CPU") {
        val f = BothFixture(AdaptiveObjective.PERFORMANCE); f.gpu.maximum = 2_000; f.gpu.owned = true
        f.baseline(cpuLoad = 20.0, gpuLoad = 90.0)
        check(f.state().pendingProposal?.proposal?.target == AdaptiveTarget.GPU)
        check(f.approve(cpuLoad = 20.0, gpuLoad = 90.0).successful)
        f.result(current = 310.0, cpuLoad = 20.0, gpuLoad = 90.0, janky = 5)
        check(f.gpu.maximum == 3_000L && f.state().reason == "frame_improved" && f.cpu.attempts.isEmpty())
    }
    test("unknown GPU load or temperature never inherits CPU demand") {
        for (change in listOf<(AdaptiveSample) -> AdaptiveSample>(
            { it.copy(gpuLoadPercent = null) }, { it.copy(gpuTemperatureC = null) },
            { it.copy(gpuLoadPercent = Double.NaN) }, { it.copy(gpuTemperatureC = 85.0) },
        )) {
            val f = BothFixture(); f.cpu.policies = f.cpu.policies.map { it.copy(locked = true) }
            listOf(5_000L, 10_000L, 15_000L).forEach { f.engine.observe(change(f.reading(it, cpuLoad = 20.0))) }
            check(f.state().pendingProposal == null && f.gpu.attempts.isEmpty())
        }
    }
    test("CPU operation remains available without any GPU telemetry or port") {
        val f = Fixture(); f.approvedBaseline(); f.result()
        check(f.state().reason == "current_improved" && f.cpu.writes.size == 1)
    }
    test("both unknown loads cannot produce either CPU or GPU proposals") {
        val f = BothFixture()
        listOf(5_000L, 10_000L, 15_000L).forEach {
            f.engine.observe(f.reading(it).copy(cpuLoadPercent = null, gpuLoadPercent = null))
        }
        check(f.state().pendingProposal == null && f.cpu.attempts.isEmpty() && f.gpu.attempts.isEmpty())
    }
    test("a manual GPU setting is never taken over by adaptation") {
        val f = BothFixture(); f.gpu.locked = true; f.baseline()
        check(f.state().pendingProposal == null && f.gpu.attempts.isEmpty())
    }
    test("CPU manual override during GPU trial restores only the owned GPU trial") {
        val f = BothFixture(); f.baseline(); check(f.approve().successful)
        f.cpu.revision++; f.cpu.change { it.copy(maximum = 1_000) }
        f.engine.observe(f.reading(20_000))
        check(f.gpu.maximum == 3_000L && f.gpu.restores == 1 && !f.gpu.owned)
        check(f.cpu.maximum() == 1_000L && f.cpu.attempts.isEmpty())
    }
    test("GPU manual override during CPU trial restores only the owned CPU trial") {
        val f = BothFixture(); f.baseline(cpuLoad = 10.0, gpuLoad = 60.0)
        check(f.state().pendingProposal?.proposal?.target == AdaptiveTarget.CPU)
        check(f.approve(cpuLoad = 10.0, gpuLoad = 60.0).successful)
        f.gpu.manual(1_000); f.engine.observe(f.reading(20_000, cpuLoad = 10.0, gpuLoad = 60.0))
        check(f.cpu.maximum() == 3_000L && f.gpu.maximum == 1_000L)
        check(f.gpu.restores == 0 && f.gpu.releases == 0)
    }
    test("same-value manual GPU intent revokes consent and ownership") {
        val f = BothFixture(); f.baseline(); val id = checkNotNull(f.state().pendingProposal).id
        f.gpu.manual()
        check(!f.engine.approveProposal(id, f.reading(16_000)).successful && f.gpu.attempts.isEmpty())
        val active = BothFixture(); active.baseline(); active.approve(); active.gpu.manual()
        active.engine.stop(17_000, 1_017_000)
        check(active.gpu.maximum == 2_000L && active.gpu.restores == 0)
    }
    test("an external GPU replacement invalidates a CPU comparison without undoing external state") {
        val f = BothFixture(); f.baseline(cpuLoad = 10.0, gpuLoad = 60.0)
        f.approve(cpuLoad = 10.0, gpuLoad = 60.0)
        f.gpu.maximum = 1_000; f.gpu.controlRevision++
        f.engine.observe(f.reading(20_000, cpuLoad = 10.0, gpuLoad = 60.0))
        check(f.cpu.maximum() == 3_000L && f.gpu.maximum == 1_000L && f.gpu.attempts.isEmpty())
    }
    test("GPU readback failure preserves a suspended owned lease for Stop recovery") {
        val f = BothFixture(); f.baseline(); f.gpu.failReadback = true
        check(!f.approve().successful && f.gpu.maximum == 2_000L && f.gpu.owned)
        f.engine.observe(f.reading(20_000))
        check(f.state().reason == "readback_failed" && f.gpu.attempts.size == 1)
        f.gpu.failReadback = false; f.engine.stop(21_000, 1_021_000)
        check(!f.gpu.owned && f.gpu.maximum == 3_000L && f.gpu.releases == 1)
    }
    test("GPU rollback failure is suspended rather than repeatedly forced") {
        val f = BothFixture(); f.baseline(); f.approve(); f.gpu.failRestore = true; f.result(current = 300.0)
        val count = f.gpu.restores
        f.engine.observe(f.reading(35_000)); f.engine.observe(f.reading(40_000))
        check(f.gpu.restores == count && f.state().reason == "write_failed")
        f.gpu.failRestore = false; f.engine.stop(41_000, 1_041_000)
        check(!f.gpu.owned && f.gpu.maximum == 3_000L)
    }
    test("temporary loss of GPU readback does not forget a recoverable owned lease") {
        val f = BothFixture(); f.baseline(); f.approve(); f.gpu.unavailable = true
        f.engine.observe(f.reading(20_000))
        check(f.gpu.owned && f.gpu.maximum == 2_000L)
        f.gpu.unavailable = false; f.engine.stop(21_000, 1_021_000)
        check(!f.gpu.owned && f.gpu.maximum == 3_000L)
    }
    test("one accepted GPU ceiling followed by another failed trial restores only the preceding ceiling") {
        val f = BothFixture(); f.baseline(); f.approve(); f.result()
        listOf(40_000L, 45_000L, 50_000L).forEach { f.engine.observe(f.reading(it, current = 200.0)) }
        val next = checkNotNull(f.state(50_000).pendingProposal)
        check(next.proposal.beforeMaximum == 2_000L && next.proposal.targetMaximum == 1_000L)
        check(f.engine.approveProposal(next.id, f.reading(51_000, current = 200.0)).successful)
        listOf(55_000L, 60_000L, 65_000L).forEach { f.engine.observe(f.reading(it, current = 200.0)) }
        check(f.gpu.maximum == 2_000L && f.gpu.owned && f.gpu.restores == 1)
        f.engine.stop(66_000, 1_066_000)
        check(f.gpu.maximum == 3_000L && !f.gpu.owned && f.gpu.releases == 1)
    }
    test("GPU consent remains single-use and a stale ID cannot approve a CPU proposal") {
        val f = BothFixture(); f.baseline(); val gpuId = checkNotNull(f.state().pendingProposal).id
        check(f.approve().successful)
        check(!f.engine.approveProposal(gpuId, f.reading(17_000)).successful && f.gpu.writes.size == 1)
        f.engine.stop(18_000, 1_018_000); f.engine.start(AdaptiveObjective.BATTERY, 19_000, 1_019_000)
        listOf(20_000L, 25_000L, 30_000L).forEach { f.engine.observe(f.reading(it, cpuLoad = 10.0, gpuLoad = 60.0)) }
        check(f.state().pendingProposal?.proposal?.target == AdaptiveTarget.CPU)
        check(!f.engine.approveProposal(gpuId, f.reading(31_000, cpuLoad = 10.0, gpuLoad = 60.0)).successful)
        check(f.cpu.attempts.isEmpty())
    }
    test("CPU manual intent racing the GPU write rolls back only the new GPU trial") {
        val f = BothFixture(); f.baseline()
        f.gpu.beforeWrite = { f.cpu.revision++; f.cpu.change { it.copy(maximum = 1_000) } }
        check(f.approve().reason == "manual_override")
        check(f.gpu.maximum == 3_000L && !f.gpu.owned && f.gpu.restores == 1)
        check(f.cpu.maximum() == 1_000L && f.cpu.attempts.isEmpty() && f.state().trialTarget == null)
    }
    test("GPU manual intent racing the CPU write rolls back only the new CPU trial") {
        val f = BothFixture(); f.baseline(cpuLoad = 10.0, gpuLoad = 60.0)
        f.cpu.beforeWrite = { f.gpu.manual(1_000) }
        check(f.approve(cpuLoad = 10.0, gpuLoad = 60.0).reason == "manual_override")
        check(f.cpu.maximum() == 3_000L && f.gpu.maximum == 1_000L)
        check(f.gpu.restores == 0 && f.gpu.releases == 0 && f.state().trialTarget == null)
    }
    test("GPU proposal review ignores Parts frame context but checks fresh GPU safety") {
        val review = "org.lineageos.settings.galaxian"
        val f = BothFixture(reviewWorkloadKey = review); f.baseline()
        val id = checkNotNull(f.state().pendingProposal).id
        f.engine.observe(f.reading(20_000, workload = review).copy(frames = null))
        check(f.engine.approveProposal(id, f.reading(21_000, workload = review).copy(frames = null)).successful)
        f.engine.observe(f.reading(25_000, workload = review).copy(frames = null))
        check(f.state().journal.none { it.action == "evaluated" })
        listOf(30_000L, 35_000L, 40_000L).forEach { f.engine.observe(f.reading(it, current = 200.0)) }
        check(f.state().reason == "current_improved")
        val blocked = BothFixture(reviewWorkloadKey = review); blocked.baseline()
        val blockedId = checkNotNull(blocked.state().pendingProposal).id
        check(blocked.engine.approveProposal(blockedId,
            blocked.reading(16_000, workload = review).copy(gpuTemperatureC = 85.0, frames = null)).reason == "thermal_limit")
        check(blocked.gpu.attempts.isEmpty())
    }
    test("failed GPU Stop retains the new control revision and can retry automatic release") {
        val f = BothFixture(); f.baseline(); f.approve(); f.result()
        val revision = f.gpu.controlRevision
        f.gpu.failRestore = true
        f.engine.stop(35_000, 1_035_000)
        check(f.gpu.maximum == 2_000L && f.gpu.owned && f.gpu.controlRevision == revision + 1)
        check(f.gpu.releases == 1)
        f.gpu.failRestore = false
        f.engine.stop(40_000, 1_040_000)
        check(f.gpu.maximum == 3_000L && !f.gpu.owned && f.gpu.releases == 2)
    }
    test("GPU review tolerates idle load but never absent or unsafe temperature") {
        val review = "org.lineageos.settings.galaxian"
        val f = BothFixture(reviewWorkloadKey = review); f.baseline()
        val id = checkNotNull(f.state().pendingProposal).id
        val idle = f.reading(20_000, workload = review).copy(gpuLoadPercent = null, frames = null)
        f.engine.observe(idle)
        check(f.state().pendingProposal?.id == id && f.gpu.attempts.isEmpty())
        check(f.engine.approveProposal(id, idle.copy(elapsedMs = 21_000)).successful)
        f.engine.observe(idle.copy(elapsedMs = 25_000))
        check(f.gpu.maximum == 2_000L && f.state().reason == "waiting_original_workload")
        f.engine.observe(f.reading(30_000).copy(gpuLoadPercent = null))
        check(f.gpu.maximum == 3_000L && f.state().reason == "gpu_missing_telemetry")
        for (temperature in listOf(null, 85.0)) {
            val blocked = BothFixture(reviewWorkloadKey = review); blocked.baseline()
            val blockedId = checkNotNull(blocked.state().pendingProposal).id
            val result = blocked.engine.approveProposal(blockedId,
                blocked.reading(16_000, workload = review).copy(gpuLoadPercent = null,
                    gpuTemperatureC = temperature, frames = null))
            check(!result.successful && blocked.gpu.attempts.isEmpty())
            check(result.reason == if (temperature == null) "gpu_missing_telemetry" else "thermal_limit")
        }
    }
    test("real analyzer review resume reaches GPU evaluation without reusing pre-approval frames") {
        val review = "org.lineageos.settings.galaxian"
        val f = BothFixture(reviewWorkloadKey = review)
        val analyzer = FrameTelemetryAnalyzer()
        analyzed(analyzer, f.reading(0), 100)
        listOf(5_000L, 10_000L, 15_000L).forEachIndexed { i, t ->
            f.engine.observe(analyzed(analyzer, f.reading(t), 200L + i * 100))
        }
        val request = checkNotNull(f.state().pendingProposal)
        analyzer.pauseForReview()
        f.engine.observe(f.reading(20_000, workload = review).copy(frames = null, gpuLoadPercent = null))
        check(f.engine.approveProposal(request.id,
            f.reading(21_000, workload = review).copy(frames = null, gpuLoadPercent = null)).successful)
        val resume = analyzed(analyzer, f.reading(25_000, current = 100.0), 500)
        check(resume.frameTelemetryReason == "resuming_frames")
        f.engine.observe(resume)
        check(f.state().trialTarget == AdaptiveTarget.GPU && f.gpu.restores == 0)
        listOf(30_000L, 35_000L, 40_000L).forEachIndexed { i, t ->
            f.engine.observe(analyzed(analyzer, f.reading(t, current = 200.0), 600L + i * 100))
        }
        check(f.state().reason == "current_improved" && f.gpu.maximum == 2_000L)
        val evaluation = f.state().journal.last { it.action == "evaluated" }
        check(evaluation.beforeSample?.frames?.contextKey == evaluation.afterSample?.frames?.contextKey)
        check(evaluation.afterSample?.frames?.firstFrameToken == 600L)
        check(evaluation.afterSample?.dischargeCurrentMa == 200.0)
    }
    for (change in listOf("layer", "scope", "refresh", "schedule"))
        test("review resume rejects real $change change before admitting a trial result") {
            val review = "org.lineageos.settings.galaxian"
            val f = Fixture(reviewWorkloadKey = review)
            val analyzer = FrameTelemetryAnalyzer()
            analyzed(analyzer, sample(0), 100)
            listOf(5_000L, 10_000L, 15_000L).forEachIndexed { i, t ->
                f.engine.observe(analyzed(analyzer, sample(t), 200L + i * 100))
            }
            val id = checkNotNull(f.state().pendingProposal).id
            analyzer.pauseForReview()
            check(f.engine.approveProposal(id, sample(20_000, workload = review).copy(frames = null)).successful)
            val returned = analyzed(analyzer, sample(25_000), 500,
                layer = if (change == "layer") 99 else 52,
                scope = if (change == "scope") "10042:4243" else "10042:4242",
                refresh = if (change == "refresh") 16_666_667 else 8_333_333,
                fps = if (change == "schedule") 30 else 60)
            check(returned.frameTelemetryReason == "frames_changed")
            f.engine.observe(returned)
            check(f.cpu.maximum() == 3_000L && f.state().trialPolicyId == null)
            check(f.state().reason == "frames_changed")
        }
    test("resume grace is bounded and only applies to the original workload") {
        val review = "org.lineageos.settings.galaxian"
        val expired = Fixture(reviewWorkloadKey = review); expired.baseline()
        val id = checkNotNull(expired.state().pendingProposal).id
        check(expired.engine.approveProposal(id, sample(20_000, workload = review).copy(frames = null)).successful)
        expired.engine.observe(sample(140_001).copy(frames = null, frameTelemetryReason = "resuming_frames"))
        check(expired.cpu.maximum() == 3_000L && expired.state().reason == "inconclusive_samples")
        val other = Fixture(reviewWorkloadKey = review); other.baseline()
        check(other.engine.approveProposal(checkNotNull(other.state().pendingProposal).id,
            sample(20_000, workload = review).copy(frames = null)).successful)
        other.engine.observe(sample(25_000, workload = "other.app").copy(frames = null,
            frameTelemetryReason = "resuming_frames"))
        check(other.cpu.maximum() == 3_000L && other.state().trialPolicyId == null)
    }
    test("an unapproved proposal survives only the validated resume watermark within its deadline") {
        val review = "org.lineageos.settings.galaxian"
        val f = Fixture(reviewWorkloadKey = review); f.baseline()
        val request = checkNotNull(f.state().pendingProposal)
        f.engine.observe(sample(20_000, workload = review).copy(frames = null))
        f.engine.observe(sample(25_000).copy(frames = null, frameTelemetryReason = "resuming_frames"))
        check(f.state().pendingProposal?.id == request.id && f.cpu.attempts.isEmpty())
        f.engine.observe(sample(request.expiresElapsedMs).copy(frames = null, frameTelemetryReason = "resuming_frames"))
        check(f.state().pendingProposal == null && f.state().reason == "proposal_expired")
    }
    test("battery current sign and unsupported values are never silently inverted") {
        check(dischargeCurrentMa(-125_000, false) == 125.0)
        for (value in listOf(125_000L, 0L, Long.MIN_VALUE, Long.MAX_VALUE, -10_000_001L))
            check(dischargeCurrentMa(value, false) == null)
        check(dischargeCurrentMa(-125_000, true) == null)
    }
    test("CPU load subtracts idle and does not double count guest fields") {
        val reader = CpuLoadReader()
        check(reader.read("cpu 20 0 10 70 0 0 0 0 999 999\n", 5_000) == null)
        check(reader.read("cpu 40 0 20 140 0 0 0 0 1999 1999\n", 10_000) == 30.0)
    }
    test("CPU load resets across sessions and refuses measurements spanning a long gap") {
        val reader = CpuLoadReader()
        reader.read("cpu 20 0 10 70 0 0 0 0", 5_000)
        check(reader.read("cpu 40 0 20 140 0 0 0 0", 50_000) == null)
        reader.reset()
        check(reader.read("cpu 60 0 30 210 0 0 0 0", 55_000) == null)
        check(reader.read("cpu 80 0 40 280 0 0 0 0", 60_000) == 30.0)
    }
    test("CPU counters must advance consistently") {
        val reader = CpuLoadReader(); reader.read("cpu 20 0 10 70 0 0 0 0", 5_000)
        check(reader.read("cpu 1 0 0 1 0 0 0 0", 10_000) == null)
        check(reader.read("cpu malformed", 15_000) == null)
    }
    tests += runCpuSessionTests()
    println("$tests adaptive/controller tests passed")
}
