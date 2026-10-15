/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal enum class AdaptiveTarget { CPU, GPU }
internal enum class AdaptiveObjective { BALANCED, PERFORMANCE, BATTERY }
internal enum class AdaptivePhase { OFF, OBSERVING, AWAITING_APPROVAL, APPLYING, EVALUATING, COOLDOWN, MANUAL_OVERRIDE, UNAVAILABLE }
internal data class AdaptiveSample(
    val elapsedMs: Long,
    val wallTimeMs: Long,
    val cpuLoadPercent: Double?,
    val batteryTemperatureC: Double?,
    val cpuTemperatureC: Double?,
    val dischargeCurrentMa: Double?,
    val availableMemoryMiB: Long?,
    val lowMemory: Boolean,
    val charging: Boolean,
    val interactive: Boolean,
    val thermalStatus: Int?,
    val workloadKey: String? = null,
    val gpuLoadPercent: Double? = null,
    val gpuTemperatureC: Double? = null,
    val frames: AdaptiveFrameWindow? = null,
    val frameTelemetryReason: String? = null,
)
internal data class AdaptiveEvent(
    val timestampMs: Long,
    val action: String,
    val reason: String,
    val policyId: Int? = null,
    val beforeMaximumKHz: Long? = null,
    val afterMaximumKHz: Long? = null,
    val measuredDeltaMa: Double? = null,
    val beforeSample: AdaptiveSample? = null,
    val afterSample: AdaptiveSample? = null,
    val target: AdaptiveTarget = AdaptiveTarget.CPU,
)
internal data class AdaptiveSnapshot(
    val enabled: Boolean,
    val objective: AdaptiveObjective,
    val phase: AdaptivePhase,
    val reason: String,
    val latestSample: AdaptiveSample?,
    val journal: List<AdaptiveEvent>,
    val trialPolicyId: Int?,
    val cooldownRemainingMs: Long,
    val pendingProposal: AdaptivePendingProposal? = null,
    val trialTarget: AdaptiveTarget? = null,
)
internal data class AdaptivePolicy(
    val id: Int, val minimum: Long, val maximum: Long,
    val frequencies: List<Long>, val writable: Boolean, val locked: Boolean,
)
internal data class AdaptiveCpuSnapshot(val policies: List<AdaptivePolicy>, val revision: Long)
internal data class AdaptiveProposal(
    val policyId: Int, val minimum: Long, val beforeMaximum: Long,
    val targetMaximum: Long, val revision: Long,
    val target: AdaptiveTarget = AdaptiveTarget.CPU,
    val controlKey: String? = null,
    val controlToken: Any? = null,
)
/** Ephemeral approval request. Never reconstruct this value from an Intent or saved journal. */
internal data class AdaptivePendingProposal(
    val id: String,
    val proposal: AdaptiveProposal,
    val objective: AdaptiveObjective,
    val reason: String,
    val baseline: AdaptiveSample,
    val createdElapsedMs: Long,
    val expiresElapsedMs: Long,
)
internal data class AdaptiveDecisionResult(val successful: Boolean, val reason: String)
internal data class AdaptiveWriteResult(
    val successful: Boolean, val reason: String, val maximum: Long?,
    val gpuState: AdaptiveGpuSnapshot? = null, val restoreToken: Any? = null,
)
/** Opaque coordinator state is process-local and never serialized into notifications/history. */
internal data class AdaptiveGpuSnapshot(
    val policy: AdaptivePolicy, val revision: Long, val controlKey: String,
    val controlToken: Any, val owned: Boolean,
)
internal interface AdaptiveGpuPort {
    fun snapshot(): AdaptiveGpuSnapshot?
    fun setMaximum(proposal: AdaptiveProposal): AdaptiveWriteResult
    fun restoreTrial(restoreToken: Any): AdaptiveWriteResult
    fun release(controlToken: Any): AdaptiveWriteResult
}
internal interface AdaptiveCpuPort {
    fun snapshot(): AdaptiveCpuSnapshot
    fun setMaximum(proposal: AdaptiveProposal): AdaptiveWriteResult
}

/**
 * A bounded feedback controller, not a trained model. It changes one advertised OPP at a time
 * and requires explicit approval. Frequency, utilization and current alone never demonstrate
 * smoothness: comparable classified frame windows are required before and after every trial.
 */
internal class AdaptiveEngine(
    private val cpu: AdaptiveCpuPort,
    private val windowSize: Int = 6,
    private val windowMinimumMs: Long = 20_000,
    private val cooldownMs: Long = 60_000,
    private val rejectionMs: Long = 10 * 60_000,
    private val decisionIntervalMs: Long = 60_000,
    private val proposalValidityMs: Long = 120_000,
    private val reviewWorkloadKey: String? = null,
    private val gpu: AdaptiveGpuPort? = null,
) {
    private data class Key(val target: AdaptiveTarget, val id: Int)
    private val AdaptiveProposal.key get() = Key(target, policyId)
    private data class Lease(
        val minimum: Long, val originalMaximum: Long, var maximum: Long, val revision: Long,
        var restoreFailure: String? = null, var gpuState: AdaptiveGpuSnapshot? = null,
    )
    private data class Pending(val value: AdaptivePendingProposal, val before: List<AdaptiveSample>)
    private data class Trial(val proposal: AdaptiveProposal, val before: List<AdaptiveSample>,
        val started: Long, val restoreToken: Any? = null)
    private val leases = mutableMapOf<Key, Lease>()
    private val samples = ArrayDeque<AdaptiveSample>()
    private val events = ArrayDeque<AdaptiveEvent>()
    private val rejected = mutableMapOf<Pair<Key, Long>, Long>()
    private var trial: Trial? = null
    private var pending: Pending? = null
    private var nextDecisionAt = 0L
    private var latest: AdaptiveSample? = null
    private var lastAcceptedElapsed: Long? = null
    private var cooldownUntil = 0L
    private var lastJournalSampleAt = Long.MIN_VALUE
    private var manualRevision: Long? = null
    private var gpuManualRevision: Long? = null
    private var observedCpuPolicies: List<AdaptivePolicy>? = null
    private var observedGpuControlKey: String? = null
    private var enabled = false
    private var objective = AdaptiveObjective.BALANCED
    private var phase = AdaptivePhase.OFF
    private var reason = "disabled"

    fun seedJournal(history: List<AdaptiveEvent>) {
        if (events.isEmpty()) history.takeLast(120).forEach(events::addLast)
    }

    fun start(target: AdaptiveObjective, now: Long, wallTime: Long) {
        if (enabled) return
        enabled = true
        objective = target
        val pendingRestore = leases.values.firstNotNullOfOrNull { it.restoreFailure }
        phase = if (pendingRestore == null) AdaptivePhase.OBSERVING else AdaptivePhase.UNAVAILABLE
        reason = pendingRestore ?: "collecting_baseline"
        latest = null
        lastAcceptedElapsed = null
        rememberControls()
        cooldownUntil = now
        nextDecisionAt = now + decisionIntervalMs
        pending = null
        samples.clear()
        emit(AdaptiveEvent(wallTime, "started", reason))
    }

    fun changeObjective(target: AdaptiveObjective, now: Long, wallTime: Long) {
        if (objective == target) return
        val running = enabled
        if (running) stop(now, wallTime, "objective_changed")
        objective = target
        if (running) start(target, now, wallTime)
    }

    fun stop(now: Long, wallTime: Long, why: String = "stopped") {
        if (!enabled && leases.isEmpty()) return
        discardPending(now, wallTime, why)
        leases.keys.toList().forEach { restoreLease(it, wallTime) }
        trial = null
        samples.clear()
        enabled = false
        cooldownUntil = now
        phase = AdaptivePhase.OFF
        reason = leases.values.firstNotNullOfOrNull { it.restoreFailure } ?: "disabled"
        emit(AdaptiveEvent(wallTime, "stopped", why))
    }

    fun snapshot(now: Long) = AdaptiveSnapshot(
        enabled, objective, phase, reason, latest, events.toList(), trial?.proposal?.policyId,
        if (pending != null || !enabled || trial != null) 0 else
            (max(cooldownUntil, nextDecisionAt) - now).coerceAtLeast(0),
        pending?.value, trial?.proposal?.target,
    )

    fun observe(sample: AdaptiveSample, nowElapsedMs: Long = sample.elapsedMs) {
        val previousElapsed = lastAcceptedElapsed
        val fresh = nowElapsedMs >= sample.elapsedMs && nowElapsedMs - sample.elapsedMs <= 10_000 &&
            (previousElapsed == null || sample.elapsedMs > previousElapsed)
        if (fresh) {
            latest = sample
            lastAcceptedElapsed = sample.elapsedMs
        }
        if (!enabled) return
        if (fresh && (lastJournalSampleAt == Long.MIN_VALUE || sample.elapsedMs - lastJournalSampleAt >= 60_000)) {
            emit(AdaptiveEvent(sample.wallTimeMs, "sample", reason, afterSample = sample))
            lastJournalSampleAt = sample.elapsedMs
        }
        val state = cpu.snapshot()
        val gpuState = gpu?.snapshot()
        val cpuManual = manualRevision != state.revision
        val gpuManual = gpuManualRevision != null && gpuState != null && gpuManualRevision != gpuState.revision
        val crossContext = (pending != null || trial != null) &&
            (observedCpuPolicies != state.policies ||
                (gpuState != null && observedGpuControlKey != null && observedGpuControlKey != gpuState.controlKey))
        manualRevision = state.revision
        gpuManualRevision = gpuState?.revision
        observedCpuPolicies = state.policies
        observedGpuControlKey = gpuState?.controlKey
        if (cpuManual || gpuManual) {
            discardPending(nowElapsedMs, sample.wallTimeMs, "manual_override")
            // Manual intent revokes that device's leases, even if the raw values are unchanged.
            // A trial on the OTHER device remains ours and must be unwound, never forgotten.
            leases.keys.filter { (it.target == AdaptiveTarget.CPU && cpuManual) ||
                (it.target == AdaptiveTarget.GPU && gpuManual) }.forEach(leases::remove)
            trial?.let { active ->
                if (active.proposal.key in leases) rollbackTrial(active, sample, "manual_override")
                else trial = null
            }
            samples.clear()
            cooldownUntil = sample.elapsedMs + cooldownMs
            update(AdaptivePhase.MANUAL_OVERRIDE, "manual_override")
            emit(AdaptiveEvent(sample.wallTimeMs, "yield", reason,
                target = if (gpuManual && !cpuManual) AdaptiveTarget.GPU else AdaptiveTarget.CPU))
            return
        }
        val replaced = leases.entries.firstOrNull { (key, lease) -> !owns(key, lease, state, gpuState) }
        if (replaced != null) {
            val locked = if (replaced.key.target == AdaptiveTarget.CPU)
                state.policies.firstOrNull { it.id == replaced.key.id }?.locked == true
                else gpuState?.policy?.locked == true
            val why = if (locked) "manual_lock" else "external_override"
            discardPending(nowElapsedMs, sample.wallTimeMs, why)
            leases.remove(replaced.key)
            trial?.let { active ->
                if (active.proposal.key == replaced.key) trial = null
                else rollbackTrial(active, sample, why)
            }
            samples.clear()
            cooldownUntil = sample.elapsedMs + cooldownMs
            update(AdaptivePhase.COOLDOWN, why)
            emit(AdaptiveEvent(sample.wallTimeMs, "yield", reason,
                policyId = replaced.key.id, target = replaced.key.target))
            return
        }
        if (crossContext) {
            // Includes unowned CPU/GPU settings: they still confound an ongoing comparison.
            val why = pending?.value?.proposal?.let { proposalInvalidReason(it, state, gpuState) }
                ?: "external_override"
            discardPending(nowElapsedMs, sample.wallTimeMs, why)
            trial?.let { rollbackTrial(it, sample, why) }
            samples.clear()
            cooldownUntil = sample.elapsedMs + cooldownMs
            update(AdaptivePhase.COOLDOWN, why)
            return
        }
        // A failed restore suspends optimization. Retain the proven lease for an explicit
        // Stop retry, but never keep writing against a power HAL or a rejected sysfs write.
        val pendingRestore = leases.values.firstNotNullOfOrNull { it.restoreFailure }
        if (pendingRestore != null) {
            discardPending(nowElapsedMs, sample.wallTimeMs, pendingRestore)
            trial = null
            samples.clear()
            update(AdaptivePhase.UNAVAILABLE, pendingRestore)
            return
        }
        val unavailable = if (fresh) sample.blockingReason() else "missing_telemetry"
        if (unavailable != null) {
            discardPending(nowElapsedMs, sample.wallTimeMs, unavailable)
            // Unwind our own trial/accepted override on lost telemetry, charging or heat.
            // This is a compare-and-set restore, never another optimization or repeated writer.
            trial?.let { active ->
                rejected[active.proposal.key to active.proposal.targetMaximum] = sample.elapsedMs + rejectionMs
                emit(event(sample, "evaluated", unavailable, active.proposal))
            }
            trial = null
            leases.keys.toList().forEach { restoreLease(it, sample.wallTimeMs) }
            samples.clear()
            // Start one recovery interval for this unavailable episode. Re-arming it on
            // every five-second sample makes the displayed deadline recede forever.
            // All safety checks above still run on every tick, even after it expires;
            // only a new complete, valid baseline can produce another proposal.
            if (phase != AdaptivePhase.UNAVAILABLE) {
                cooldownUntil = max(cooldownUntil, nowElapsedMs + cooldownMs)
            }
            update(AdaptivePhase.UNAVAILABLE, unavailable)
            return
        }
        val original = trial?.before?.lastOrNull() ?: pending?.value?.baseline
        val reviewing = original != null && isReviewInterruption(sample, original)
        val resuming = original != null && sample.workloadKey == original.workloadKey &&
            sample.frames == null && sample.frameTelemetryReason == "resuming_frames"
        val activeTarget = trial?.proposal?.target ?: pending?.value?.proposal?.target
        if (activeTarget == AdaptiveTarget.GPU) {
            // An idle GPU legitimately has no load sample while Parts displays the review.
            // Continue checking fresh temperature and controls; require load again on return.
            val gpuBlocked = if (gpuState == null) "gpu_missing_telemetry"
                else sample.gpuBlockingReason(requireLoad = !reviewing)
            if (gpuBlocked != null) {
                discardPending(nowElapsedMs, sample.wallTimeMs, gpuBlocked)
                trial?.let { rollbackTrial(it, sample, gpuBlocked) }
                samples.clear()
                update(AdaptivePhase.UNAVAILABLE, gpuBlocked)
                return
            }
        }
        val framesBlocked = if (reviewing || resuming) null else frameReason(sample, nowElapsedMs)
        if (framesBlocked != null) {
            discardPending(nowElapsedMs, sample.wallTimeMs, framesBlocked)
            trial?.let { rollbackTrial(it, sample, framesBlocked) }
            samples.clear()
            update(AdaptivePhase.OBSERVING, framesBlocked)
            return
        }
        pending?.let { request ->
            val invalid = when {
                nowElapsedMs >= request.value.expiresElapsedMs -> "proposal_expired"
                else -> proposalInvalidReason(request.value.proposal, state, gpuState)
                    ?: if (reviewing || resuming) null
                    else comparisonReason(request.before, listOf(sample))
            }
            if (invalid != null) {
                discardPending(nowElapsedMs, sample.wallTimeMs, invalid)
                samples.clear()
                update(AdaptivePhase.COOLDOWN, invalid)
                return
            }
        }
        if (resuming) {
            // The collector verified unchanged identity/cadence and consumed the returning
            // ring. Wait for new tokens, without inserting this gap into either baseline.
            trial?.let { active ->
                samples.clear()
                if (sample.elapsedMs - active.started > 120_000)
                    rollbackTrial(active, sample, "inconclusive_samples")
                else update(AdaptivePhase.EVALUATING, "waiting_original_workload")
            } ?: update(AdaptivePhase.AWAITING_APPROVAL, "awaiting_approval")
            return
        }
        if (nowElapsedMs < cooldownUntil) {
            update(AdaptivePhase.COOLDOWN, "cooldown")
            return
        }
        pending?.let { request ->
            if (isReviewInterruption(sample, request.value.baseline)) {
                // Opening our notification necessarily changes the foreground app and workload.
                // Keep the request, but never replace its app's baseline with review-screen data.
                update(AdaptivePhase.AWAITING_APPROVAL, "awaiting_approval")
                return
            }
        }
        trial?.let { active ->
            if (isReviewInterruption(sample, active.before.last())) {
                samples.clear()
                if (sample.elapsedMs - active.started > 120_000)
                    rollbackTrial(active, sample, "inconclusive_samples")
                else update(AdaptivePhase.EVALUATING, "waiting_original_workload")
                return
            }
        }
        if (samples.lastOrNull()?.let { sample.elapsedMs - it.elapsedMs > 15_000 } == true) samples.clear()
        samples.addLast(sample)
        while (samples.size > windowSize) samples.removeFirst()
        if (pending != null) {
            update(AdaptivePhase.AWAITING_APPROVAL, "awaiting_approval")
            return
        }
        val active = trial
        if (active != null) {
            update(AdaptivePhase.EVALUATING, "collecting_result")
            if (sample.elapsedMs - active.started > 120_000)
                rollbackTrial(active, sample, "inconclusive_samples")
            else if (samples.size >= windowSize && sample.elapsedMs - samples.first().elapsedMs >= windowMinimumMs)
                evaluate(active, samples.toList(), sample)
            return
        }
        if (samples.size < windowSize || sample.elapsedMs - samples.first().elapsedMs < windowMinimumMs) {
            update(AdaptivePhase.OBSERVING, "collecting_baseline")
            return
        }
        if (nowElapsedMs < nextDecisionAt) {
            if (phase != AdaptivePhase.OBSERVING) update(AdaptivePhase.OBSERVING, "collecting_baseline")
            return
        }
        // A five-second telemetry tick is not a new decision or a new journal card.
        nextDecisionAt = nowElapsedMs + decisionIntervalMs
        val baseline = samples.toList()
        val invalidBaseline = if (baseline.sumOf { it.frames?.frameCount ?: 0 } < 30) "insufficient_frames"
            else comparisonReason(baseline, baseline)
        if (invalidBaseline != null) {
            update(AdaptivePhase.OBSERVING, invalidBaseline)
            return
        }
        val mean = average(baseline)
        val candidates = buildList {
            val cpuDirection = directionFor(mean, AdaptiveTarget.CPU)
            if (cpuDirection != 0) state.policies.sortedByDescending { it.id }.forEach { policy ->
                candidate(policy, state.revision, AdaptiveTarget.CPU, cpuDirection, sample.elapsedMs)
                    ?.let(::add)
            }
            if (gpuState != null && mean.gpuBlockingReason() == null) {
                val direction = directionFor(mean, AdaptiveTarget.GPU)
                if (direction != 0) candidate(gpuState.policy, gpuState.revision, AdaptiveTarget.GPU,
                    direction, sample.elapsedMs, gpuState.controlKey, gpuState.controlToken)?.let(::add)
            }
        }
        // Prefer the independently measured busy device for increases, the quieter one for
        // reductions. Unknown GPU utilization is never inferred from CPU utilization.
        val proposal = candidates.sortedByDescending {
            val load = if (it.target == AdaptiveTarget.GPU) mean.gpuLoadPercent!! else mean.cpuLoadPercent!!
            if (it.targetMaximum > it.beforeMaximum) load else 100.0 - load
        }.firstOrNull()
        if (proposal == null) {
            update(AdaptivePhase.OBSERVING, if (state.policies.any { it.locked }) "manual_lock" else "no_candidate")
            return
        }
        val proposalReason = if (proposal.targetMaximum < proposal.beforeMaximum)
            "proposal_lower_frequency" else "proposal_raise_frequency"
        pending = Pending(AdaptivePendingProposal(
            id = UUID.randomUUID().toString(), proposal = proposal, objective = objective,
            reason = proposalReason, baseline = mean, createdElapsedMs = nowElapsedMs,
            expiresElapsedMs = nowElapsedMs + proposalValidityMs,
        ), baseline)
        update(AdaptivePhase.AWAITING_APPROVAL, "awaiting_approval")
        emit(event(sample, "proposed", proposalReason, proposal, before = mean))
    }

    /** The sole entry point for a new optimization write; every request is single-use. */
    fun approveProposal(id: String, sample: AdaptiveSample,
        nowElapsedMs: Long = sample.elapsedMs): AdaptiveDecisionResult {
        val request = pending ?: return AdaptiveDecisionResult(false, "proposal_not_current")
        if (!enabled || request.value.id != id || request.value.objective != objective)
            return AdaptiveDecisionResult(false, "proposal_not_current")
        if (nowElapsedMs >= request.value.expiresElapsedMs) {
            discardPending(nowElapsedMs, sample.wallTimeMs, "proposal_expired")
            samples.clear()
            update(AdaptivePhase.COOLDOWN, "proposal_expired")
            return AdaptiveDecisionResult(false, "proposal_expired")
        }
        // Include the fresh approval reading without dropping the oldest measured point early.
        val reviewing = isReviewInterruption(sample, request.value.baseline)
        val refreshedBaseline = if (reviewing) samples.toList() else
            (samples.toList() + sample).distinctBy { it.elapsedMs }
        observe(sample, nowElapsedMs)
        if (pending?.value?.id != id) return AdaptiveDecisionResult(false, reason)
        val proposal = request.value.proposal
        val invalid = when {
            refreshedBaseline.size < windowSize ||
                refreshedBaseline.last().elapsedMs - refreshedBaseline.first().elapsedMs < windowMinimumMs ||
                refreshedBaseline.zipWithNext().any { (a, b) -> b.elapsedMs - a.elapsedMs !in 1..15_000 } ->
                "inconclusive_samples"
            else -> proposalInvalidReason(proposal, cpu.snapshot(), gpu?.snapshot())
                ?: comparisonReason(request.before, refreshedBaseline)
                ?: if (directionFor(average(refreshedBaseline), proposal.target) !=
                    proposal.targetMaximum.compareTo(proposal.beforeMaximum)) "candidate_rejected" else null
        }
        if (invalid != null) {
            discardPending(nowElapsedMs, sample.wallTimeMs, invalid)
            samples.clear()
            update(AdaptivePhase.COOLDOWN, invalid)
            return AdaptiveDecisionResult(false, invalid)
        }
        pending = null // Consume before calling the atomic CPU coordinator, including failures.
        val mean = average(refreshedBaseline)
        emit(event(sample, "approved", "user_approved", proposal, before = mean))
        update(AdaptivePhase.APPLYING, "one_step_trial")
        val cpuBeforeWrite = cpu.snapshot()
        val gpuBeforeWrite = gpu?.snapshot()
        val result = write(proposal)
        emit(event(sample, "readback", result.reason, proposal))
        if (!result.successful || result.maximum != proposal.targetMaximum) {
            rejected[proposal.key to proposal.targetMaximum] = nowElapsedMs + rejectionMs
            cooldownUntil = nowElapsedMs + cooldownMs
            samples.clear()
            val failure = if (result.successful) "readback_failed" else result.reason
            // A failed GPU readback can still leave a proven owned raw ceiling. Preserve a
            // suspended lease for explicit Stop recovery; never silently abandon that setting.
            result.gpuState?.takeIf { it.owned }?.let { owned ->
                leases[proposal.key] = Lease(proposal.minimum,
                    leases[proposal.key]?.originalMaximum ?: proposal.beforeMaximum,
                    owned.policy.maximum, owned.revision, failure, owned)
            }
            update(AdaptivePhase.COOLDOWN, failure)
            emit(event(sample, "yield", failure, proposal))
            return AdaptiveDecisionResult(false, failure)
        }
        val lease = leases[proposal.key]
        if (lease == null) leases[proposal.key] = Lease(proposal.minimum, proposal.beforeMaximum,
            proposal.targetMaximum, proposal.revision, gpuState = result.gpuState)
        else { lease.maximum = proposal.targetMaximum; lease.gpuState = result.gpuState }
        trial = Trial(proposal, refreshedBaseline, sample.elapsedMs, result.restoreToken)
        // A coordinator serializes its own device, not a simultaneous command to the other
        // device. Detect that race before accepting the trial and unwind only our own write.
        val cpuAfterWrite = cpu.snapshot()
        val gpuAfterWrite = gpu?.snapshot()
        val crossWriteChange = if (proposal.target == AdaptiveTarget.CPU)
            gpuBeforeWrite?.controlKey != gpuAfterWrite?.controlKey
            else cpuBeforeWrite != cpuAfterWrite
        val manualWriteChange = cpuBeforeWrite.revision != cpuAfterWrite.revision ||
            gpuBeforeWrite?.revision != gpuAfterWrite?.revision
        if (!owns(proposal.key, leases.getValue(proposal.key), cpuAfterWrite, gpuAfterWrite)) {
            leases.remove(proposal.key)
            trial = null
            samples.clear()
            cooldownUntil = nowElapsedMs + cooldownMs
            val why = if (manualWriteChange) "manual_override" else "external_override"
            update(AdaptivePhase.COOLDOWN, why)
            emit(event(sample, "yield", why, proposal))
            return AdaptiveDecisionResult(false, why)
        }
        if (crossWriteChange || (proposal.target == AdaptiveTarget.GPU && gpuAfterWrite == null)) {
            val why = if (manualWriteChange) "manual_override" else
                if (gpuAfterWrite == null && proposal.target == AdaptiveTarget.GPU) "gpu_missing_telemetry"
                else "external_override"
            rollbackTrial(trial!!, sample, why)
            return AdaptiveDecisionResult(false, why)
        }
        samples.clear()
        update(AdaptivePhase.EVALUATING,
            if (reviewing) "waiting_original_workload" else "collecting_result")
        emit(event(sample, "applied", "one_step_trial", proposal, before = mean))
        return AdaptiveDecisionResult(true, "user_approved")
    }

    fun rejectProposal(id: String, now: Long, wallTime: Long): AdaptiveDecisionResult {
        val request = pending ?: return AdaptiveDecisionResult(false, "proposal_not_current")
        if (!enabled || request.value.id != id) return AdaptiveDecisionResult(false, "proposal_not_current")
        if (now >= request.value.expiresElapsedMs) {
            discardPending(now, wallTime, "proposal_expired")
            samples.clear()
            update(AdaptivePhase.COOLDOWN, "proposal_expired")
            return AdaptiveDecisionResult(false, "proposal_expired")
        }
        discardPending(now, wallTime, "proposal_rejected", rejectedByUser = true)
        samples.clear()
        update(AdaptivePhase.COOLDOWN, "proposal_rejected")
        return AdaptiveDecisionResult(true, "proposal_rejected")
    }

    private fun isReviewInterruption(sample: AdaptiveSample, baseline: AdaptiveSample): Boolean =
        reviewWorkloadKey != null && sample.workloadKey == reviewWorkloadKey &&
            baseline.workloadKey != reviewWorkloadKey

    private fun directionFor(mean: AdaptiveSample, target: AdaptiveTarget): Int {
        val frames = mean.frames ?: return 0
        val load = (if (target == AdaptiveTarget.GPU) mean.gpuLoadPercent else mean.cpuLoadPercent)
            ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return 0
        val jank = frames.jankyFrames.toDouble() / frames.frameCount
        val appJank = frames.appJankyFrames.toDouble() / frames.frameCount
        val smooth = jank <= .02 && frames.droppedFrames == 0
        // A busy device alone is not evidence of lag. Compositor-only jank is not proof that
        // granting an app more CPU/GPU frequency headroom will help it meet its deadline.
        val lagging = jank >= .05 && appJank >= .03
        return when (objective) {
            AdaptiveObjective.BATTERY -> if (load <= 50 && smooth) -1 else 0
            AdaptiveObjective.PERFORMANCE -> if (load >= 75 && lagging) 1 else 0
            AdaptiveObjective.BALANCED -> when {
                load <= 35 && smooth -> -1
                load >= 85 && lagging -> 1
                else -> 0
            }
        }
    }

    private fun candidate(policy: AdaptivePolicy, revision: Long, target: AdaptiveTarget,
        direction: Int, now: Long, controlKey: String? = null, controlToken: Any? = null): AdaptiveProposal? {
        if (policy.locked || !policy.writable) return null
        val index = policy.frequencies.indexOf(policy.maximum)
        val next = policy.frequencies.getOrNull(index + direction) ?: return null
        if (index < 0 || next < policy.minimum || next == policy.maximum ||
            (rejected[Key(target, policy.id) to next] ?: 0) > now) return null
        return AdaptiveProposal(policy.id, policy.minimum, policy.maximum, next, revision,
            target, controlKey, controlToken)
    }

    private fun proposalInvalidReason(proposal: AdaptiveProposal, state: AdaptiveCpuSnapshot,
        gpuState: AdaptiveGpuSnapshot?): String? {
        val policy = if (proposal.target == AdaptiveTarget.CPU) {
            if (state.revision != proposal.revision) return "manual_override"
            state.policies.firstOrNull { it.id == proposal.policyId } ?: return "missing_telemetry"
        } else {
            if (gpuState == null) return "gpu_missing_telemetry"
            if (gpuState.revision != proposal.revision) return "manual_override"
            if (gpuState.controlKey != proposal.controlKey) return "external_override"
            gpuState.policy
        }
        if (policy.locked) return "manual_lock"
        if (!policy.writable) return "write_failed"
        if (policy.minimum != proposal.minimum || policy.maximum != proposal.beforeMaximum)
            return "external_override"
        val from = policy.frequencies.indexOf(proposal.beforeMaximum)
        val to = policy.frequencies.indexOf(proposal.targetMaximum)
        if (from < 0 || to < 0 || abs(from - to) != 1 || proposal.targetMaximum < policy.minimum)
            return "candidate_rejected"
        return null
    }

    private fun discardPending(now: Long, wallTime: Long, why: String, rejectedByUser: Boolean = false) {
        val request = pending ?: return
        pending = null
        val proposal = request.value.proposal
        rejected[proposal.key to proposal.targetMaximum] = now +
            if (rejectedByUser || why == "proposal_expired") rejectionMs else cooldownMs
        cooldownUntil = max(cooldownUntil, now + cooldownMs)
        nextDecisionAt = max(nextDecisionAt, now + decisionIntervalMs)
        emit(AdaptiveEvent(wallTime, if (rejectedByUser) "rejected" else "proposal_cancelled", why,
            proposal.policyId, proposal.beforeMaximum, proposal.targetMaximum, beforeSample = request.value.baseline,
            target = proposal.target))
    }

    private fun evaluate(active: Trial, after: List<AdaptiveSample>, latest: AdaptiveSample) {
        val beforeMean = average(active.before)
        val afterMean = average(after)
        val comparison = if (after.sumOf { it.frames?.frameCount ?: 0 } < 30) "insufficient_frames"
            else comparisonReason(active.before, after)
            ?: if (after.first().frames!!.firstFrameToken <= active.before.last().frames!!.lastFrameToken)
                "inconclusive_frames" else null
        if (comparison != null) {
            emit(event(latest, "evaluated", comparison, active.proposal, beforeMean, afterMean))
            rollbackTrial(active, latest, comparison)
            return
        }
        val delta = afterMean.dischargeCurrentMa!! - beforeMean.dischargeCurrentMa!!
        val lower = active.proposal.targetMaximum < active.proposal.beforeMaximum
        val beforeFrames = beforeMean.frames!!
        val afterFrames = afterMean.frames!!
        val beforeJank = beforeFrames.jankyFrames.toDouble() / beforeFrames.frameCount
        val afterJank = afterFrames.jankyFrames.toDouble() / afterFrames.frameCount
        val beforeDrops = beforeFrames.droppedFrames.toDouble() / beforeFrames.frameCount
        val afterDrops = afterFrames.droppedFrames.toDouble() / afterFrames.frameCount
        val beforeAppJank = beforeFrames.appJankyFrames.toDouble() / beforeFrames.frameCount
        val afterAppJank = afterFrames.appJankyFrames.toDouble() / afterFrames.frameCount
        val frameRegression = afterJank > beforeJank + .01 || afterDrops > beforeDrops + .005 ||
            afterAppJank > beforeAppJank + .01
        val frameImprovement = beforeJank - afterJank >= max(.02, beforeJank * .20) &&
            beforeAppJank - afterAppJank >= max(.01, beforeAppJank * .20) && afterDrops <= beforeDrops + .005
        val thermalBudget = afterMean.batteryTemperatureC!! <= beforeMean.batteryTemperatureC!! + 1.0 &&
            afterMean.cpuTemperatureC!! <= beforeMean.cpuTemperatureC!! + 5.0 &&
            (active.proposal.target != AdaptiveTarget.GPU ||
                afterMean.gpuTemperatureC!! <= beforeMean.gpuTemperatureC!! + 5.0)
        val currentBudget = if (lower) {
            val threshold = max(if (objective == AdaptiveObjective.BATTERY) 30.0 else 20.0,
                beforeMean.dischargeCurrentMa * if (objective == AdaptiveObjective.BATTERY) .10 else .05)
            delta <= -threshold
        } else delta <= max(30.0, beforeMean.dischargeCurrentMa * .08)
        val outcome = when {
            frameRegression -> "frame_regression"
            !currentBudget || !thermalBudget -> if (lower) "no_measured_improvement" else "budget_regression"
            lower -> "current_improved"
            frameImprovement -> "frame_improved"
            else -> "no_frame_improvement"
        }
        val accepted = outcome == "current_improved" || outcome == "frame_improved"
        emit(event(latest, "evaluated", outcome, active.proposal, beforeMean, afterMean, delta))
        if (!accepted) { rollbackTrial(active, latest, outcome); return }
        trial = null
        samples.clear()
        cooldownUntil = latest.elapsedMs + cooldownMs
        update(AdaptivePhase.COOLDOWN, outcome)
    }

    private fun rollbackTrial(active: Trial, sample: AdaptiveSample, why: String) {
        val p = active.proposal
        rejected[p.key to p.targetMaximum] = sample.elapsedMs + rejectionMs
        val result = if (p.target == AdaptiveTarget.CPU)
            write(p.copy(beforeMaximum = p.targetMaximum, targetMaximum = p.beforeMaximum))
            else active.restoreToken?.let { gpu?.restoreTrial(it) }
                ?: AdaptiveWriteResult(false, "external_override", null)
        rememberControls()
        val restored = result.successful && result.maximum == p.beforeMaximum
        emit(event(sample, "rollback", if (restored) "restored" else result.reason,
            p.copy(beforeMaximum = p.targetMaximum, targetMaximum = p.beforeMaximum)))
        val lease = leases[p.key]
        if (restored) {
            if (p.target == AdaptiveTarget.GPU) {
                if (result.gpuState?.owned != true) leases.remove(p.key)
                else if (lease != null) { lease.maximum = p.beforeMaximum; lease.gpuState = result.gpuState }
            } else if (lease?.originalMaximum == p.beforeMaximum) leases.remove(p.key)
            else lease?.maximum = p.beforeMaximum
        } else if (lease != null) {
            result.gpuState?.takeIf { it.owned }?.let { lease.gpuState = it; lease.maximum = it.policy.maximum }
            if (stillOwned(p.key, lease)) lease.restoreFailure = result.reason
            else leases.remove(p.key)
        }
        trial = null
        samples.clear()
        cooldownUntil = sample.elapsedMs + cooldownMs
        update(AdaptivePhase.COOLDOWN, why)
    }

    private fun restoreLease(key: Key, wallTime: Long) {
        val lease = leases[key] ?: return
        val result = if (key.target == AdaptiveTarget.CPU) {
            write(AdaptiveProposal(key.id, lease.minimum, lease.maximum, lease.originalMaximum, lease.revision))
        } else lease.gpuState?.let { gpu?.release(it.controlToken) }
            ?: AdaptiveWriteResult(false, "external_override", null)
        rememberControls()
        val restored = result.successful && (key.target == AdaptiveTarget.GPU || result.maximum == lease.originalMaximum)
        // Even a failed restore advances the coordinator's control revision. Retain its
        // newest proven ownership/token so a later explicit Stop can retry the exact lease.
        result.gpuState?.takeIf { it.owned }?.let { lease.gpuState = it; lease.maximum = it.policy.maximum }
        if (restored || !stillOwned(key, lease)) leases.remove(key)
        else lease.restoreFailure = result.reason
        emit(AdaptiveEvent(wallTime, "rollback", if (restored) "restored" else result.reason,
            key.id, lease.maximum, lease.originalMaximum, target = key.target))
    }

    private fun write(proposal: AdaptiveProposal): AdaptiveWriteResult {
        val result = if (proposal.target == AdaptiveTarget.CPU) cpu.setMaximum(proposal)
            else gpu?.setMaximum(proposal) ?: AdaptiveWriteResult(false, "gpu_missing_telemetry", null)
        rememberControls()
        // A GPU success must carry exact post-write ownership and a rollback token.
        return if (proposal.target == AdaptiveTarget.GPU && result.successful &&
            (result.gpuState?.owned != true || result.restoreToken == null ||
                result.gpuState.policy.maximum != proposal.targetMaximum))
            result.copy(successful = false, reason = "readback_failed") else result
    }

    private fun rememberControls() {
        val state = cpu.snapshot()
        manualRevision = state.revision
        observedCpuPolicies = state.policies
        val gpuState = gpu?.snapshot()
        gpuManualRevision = gpuState?.revision
        observedGpuControlKey = gpuState?.controlKey
    }

    private fun stillOwned(key: Key, lease: Lease): Boolean = owns(key, lease, cpu.snapshot(), gpu?.snapshot())

    private fun owns(key: Key, lease: Lease, state: AdaptiveCpuSnapshot, gpuState: AdaptiveGpuSnapshot?): Boolean =
        if (key.target == AdaptiveTarget.CPU) state.revision == lease.revision && state.policies.any {
            it.id == key.id && !it.locked && it.minimum == lease.minimum && it.maximum == lease.maximum
        } else if (gpuState == null) lease.gpuState != null // Unknown is not evidence of lost ownership.
        else gpuState.owned && !gpuState.policy.locked && gpuState.revision == lease.revision &&
            gpuState.controlKey == lease.gpuState?.controlKey

    private fun comparisonReason(before: List<AdaptiveSample>, after: List<AdaptiveSample>): String? {
        if (!stableWindow(before) || !stableWindow(after)) return "inconclusive_context"
        if (!stableFrames(before) || !stableFrames(after)) return "inconclusive_frames"
        val a = average(before); val b = average(after)
        val af = a.frames!!; val bf = b.frames!!
        if (af.contextKey != bf.contextKey || af.framePeriodNs != bf.framePeriodNs ||
            af.refreshPeriodNs != bf.refreshPeriodNs) return "frames_changed"
        if (a.workloadKey != b.workloadKey || a.interactive != b.interactive || a.charging != b.charging ||
            !comparableLoad(a.cpuLoadPercent, b.cpuLoadPercent) ||
            !comparableLoad(a.gpuLoadPercent, b.gpuLoadPercent) ||
            abs(a.batteryTemperatureC!! - b.batteryTemperatureC!!) > 2.5 ||
            abs(a.cpuTemperatureC!! - b.cpuTemperatureC!!) > 8 ||
            abs(a.availableMemoryMiB!! - b.availableMemoryMiB!!) > max(128.0, a.availableMemoryMiB * .15))
            return "inconclusive_context"
        if (listOf(before, after).any { window ->
                val currents = window.map { it.dischargeCurrentMa!! }
                val mean = currents.average()
                sqrt(currents.map { (it - mean) * (it - mean) }.average()) > max(60.0, mean * .25)
            }) return "inconclusive_noise"
        return null
    }

    private fun stableWindow(window: List<AdaptiveSample>): Boolean =
        window.all { it.blockingReason() == null } &&
            window.map { it.workloadKey }.distinct().size == 1 &&
            window.map { it.interactive }.distinct().size == 1 &&
            window.map { it.charging }.distinct().size == 1 &&
            stableLoad(window.map { it.cpuLoadPercent }) && stableLoad(window.map { it.gpuLoadPercent })

    private fun AdaptiveSample.blockingReason(): String? {
        if (charging) return "charging"
        if (listOf(batteryTemperatureC, cpuTemperatureC, dischargeCurrentMa).any {
                it == null || !it.isFinite() } || availableMemoryMiB == null || availableMemoryMiB <= 0 ||
            workloadKey == null || thermalStatus == null || thermalStatus !in 0..6 ||
            (cpuLoadPercent != null && (!cpuLoadPercent.isFinite() || cpuLoadPercent !in 0.0..100.0)) ||
            batteryTemperatureC!! !in -20.0..70.0 ||
            cpuTemperatureC!! !in -20.0..140.0 || dischargeCurrentMa!! <= 0 || dischargeCurrentMa > 10_000)
            return "missing_telemetry"
        if (batteryTemperatureC!! >= 43.0 || cpuTemperatureC!! >= 80.0 || thermalStatus!! >= 3)
            return "thermal_limit"
        if (lowMemory) return "memory_pressure"
        return null
    }

    private fun AdaptiveSample.gpuBlockingReason(requireLoad: Boolean = true): String? {
        if ((requireLoad && gpuLoadPercent == null) ||
            (gpuLoadPercent != null && (!gpuLoadPercent.isFinite() || gpuLoadPercent !in 0.0..100.0)) ||
            gpuTemperatureC == null || !gpuTemperatureC.isFinite() || gpuTemperatureC !in -20.0..140.0)
            return "gpu_missing_telemetry"
        if (gpuTemperatureC >= 80.0) return "thermal_limit"
        return null
    }

    private fun frameReason(sample: AdaptiveSample, now: Long = sample.elapsedMs): String? {
        val f = sample.frames ?: return when (sample.frameTelemetryReason) {
            "frames_changed", "insufficient_frames" -> sample.frameTelemetryReason
            else -> "frames_unavailable"
        }
        if (f.contextKey.isBlank() || f.frameCount <= 0 || f.jankyFrames !in 0..f.frameCount ||
            f.droppedFrames !in 0..f.frameCount || f.appJankyFrames !in 0..f.jankyFrames ||
            f.compositorJankyFrames !in 0..f.jankyFrames || f.framePeriodNs <= 0 ||
            f.refreshPeriodNs <= 0 || f.firstFrameToken < 0 || f.lastFrameToken < f.firstFrameToken ||
            f.sampledElapsedMs > now || now - f.sampledElapsedMs > 10_000 || f.observedDurationNs <= 0)
            return "frames_unavailable"
        return if (f.frameCount < 8) "insufficient_frames" else null
    }

    private fun stableFrames(window: List<AdaptiveSample>): Boolean {
        if (window.any { frameReason(it, max(it.elapsedMs, it.frames?.sampledElapsedMs ?: 0)) != null }) return false
        val frames = window.map { it.frames!! }
        return frames.sumOf { it.frameCount.toLong() } >= 8 &&
            frames.map { Triple(it.contextKey, it.framePeriodNs, it.refreshPeriodNs) }.distinct().size == 1 &&
            frames.zipWithNext().all { (a, b) -> b.firstFrameToken > a.lastFrameToken }
    }

    private fun aggregateFrames(window: List<AdaptiveSample>): AdaptiveFrameWindow? {
        if (window.any { it.frames == null }) return null
        val frames = window.map { it.frames!! }
        return frames.last().copy(
            frameCount = frames.sumOf { it.frameCount }, jankyFrames = frames.sumOf { it.jankyFrames },
            droppedFrames = frames.sumOf { it.droppedFrames }, appJankyFrames = frames.sumOf { it.appJankyFrames },
            compositorJankyFrames = frames.sumOf { it.compositorJankyFrames },
            firstFrameToken = frames.first().firstFrameToken,
            observedDurationNs = frames.sumOf { it.observedDurationNs },
            frameTimeP95Ms = null, // Quantiles cannot be averaged; do not invent a pooled p95.
        )
    }

    private fun stableLoad(values: List<Double?>): Boolean = values.all { it == null } ||
        (values.all { it != null && it.isFinite() } && values.filterNotNull().let { it.max() - it.min() <= 20 })

    private fun comparableLoad(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == b else abs(a - b) <= 12

    private fun average(window: List<AdaptiveSample>): AdaptiveSample = window.last().copy(
        cpuLoadPercent = window.mapNotNull { it.cpuLoadPercent }.takeIf { it.size == window.size }?.average(),
        gpuLoadPercent = window.mapNotNull { it.gpuLoadPercent }.takeIf { it.size == window.size }?.average(),
        gpuTemperatureC = window.mapNotNull { it.gpuTemperatureC }.takeIf { it.size == window.size }?.average(),
        frames = aggregateFrames(window),
        batteryTemperatureC = window.mapNotNull { it.batteryTemperatureC }.average(),
        cpuTemperatureC = window.mapNotNull { it.cpuTemperatureC }.average(),
        dischargeCurrentMa = window.mapNotNull { it.dischargeCurrentMa }.average(),
        availableMemoryMiB = window.mapNotNull { it.availableMemoryMiB }.average().toLong(),
    )
    private fun update(value: AdaptivePhase, why: String) { phase = value; reason = why }
    private fun emit(value: AdaptiveEvent) { events.addLast(value); while (events.size > 120) events.removeFirst() }
    private fun event(sample: AdaptiveSample, action: String, why: String, proposal: AdaptiveProposal,
        before: AdaptiveSample? = null, after: AdaptiveSample? = null, delta: Double? = null) =
        AdaptiveEvent(sample.wallTimeMs, action, why, proposal.policyId, proposal.beforeMaximum,
            proposal.targetMaximum, delta, before, after, proposal.target)
}
