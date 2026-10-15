/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.gpu

import kotlin.math.abs

internal data class GpuSessionSnapshot(
    val control: GpuControlSnapshot,
    val manualRevision: Long,
    val controlRevision: Long,
    val adaptiveOwned: Boolean,
) {
    val state: GpuState get() = control.state
    val controls: GpuRawControls? get() = control.controls
    val operatingPoints: Map<Int, Long> get() = control.operatingPoints
    val utilization: GpuUtilization? get() = control.utilization
    // Requested ceiling, not the independently enforced thermal/PowerHAL ceiling.
    val maximumKHz: Long? get() = controls?.let {
        operatingPoints[it.ceilingIndex.takeIf { index -> index >= 0 } ?: 0]
    }
    val guardKey: String get() = buildString {
        append(manualRevision).append(':').append(controlRevision).append(':')
        append(controls?.ceilingIndex).append(',').append(controls?.floorIndex)
            .append(',').append(controls?.fixedIndex)
        for ((index, frequency) in operatingPoints.toSortedMap()) append('|').append(index).append('=').append(frequency)
    }
}

/** Ephemeral single-trial handle. It grants no ownership after a process restart. */
internal class GpuRestoreToken internal constructor(
    val id: Long, val manualRevision: Long, val controlRevision: Long,
)
internal data class AdaptiveGpuWrite(
    val successful: Boolean,
    val reason: String,
    val snapshot: GpuSessionSnapshot,
    val restoreToken: GpuRestoreToken? = null,
    val result: GpuResult? = null,
) {
    val maximumKHz: Long? get() = snapshot.maximumKHz
}

/**
 * One process-wide monitor serializes manual and adaptive operations. Ownership is
 * acquired only from raw automatic controls; an existing/manual range is never adopted.
 * The proc driver has no atomic CAS against other processes. Raw controls and the
 * complete OPP table are checked immediately before each write and on readback.
 */
internal class GpuControlCoordinator(
    private val manager: GpuControlManager = GpuControlManager(),
    private val compareAndSet: (GpuControlSnapshot, GpuRawControls) -> GpuResult = manager::compareAndSetAdaptive,
) {
    private data class Ownership(val baseline: GpuControlSnapshot, val current: GpuRawControls)
    private data class Trial(
        val token: GpuRestoreToken, val before: GpuControlSnapshot,
        val after: GpuRawControls, val previousOwner: Ownership?,
    )
    private var manualRevision = 0L
    private var controlRevision = 0L
    private var tokenSequence = 0L
    private var owner: Ownership? = null
    private var trial: Trial? = null

    @Synchronized fun snapshot(): GpuSessionSnapshot = snapshot(manager.readControlSnapshot())

    @Synchronized fun <T> manual(block: (GpuControlManager) -> T): T {
        // Invalidate ownership for every manual attempt, including no-ops, failures
        // and thrown exceptions. Do not write anything beyond the requested operation.
        manualRevision++
        controlRevision++
        owner = null
        trial = null
        return block(manager)
    }

    @Synchronized fun adaptiveMaximum(expected: GpuSessionSnapshot, targetMaximumKHz: Long): AdaptiveGpuWrite {
        val current = snapshot()
        rejectStale(expected, current)?.let { return failed(it, current) }
        val raw = current.controls ?: return failed("missing_telemetry", current)
        if (raw.fixedIndex != -1 || (!raw.automatic && !current.adaptiveOwned))
            return failed("manual_lock", current)
        if (!current.state.writable || current.state.controlMode !in listOf(GpuMode.AUTOMATIC, GpuMode.RANGE))
            return failed("missing_telemetry", current)
        val previousIndex = raw.ceilingIndex.takeIf { it >= 0 } ?: 0
        val targetIndex = current.operatingPoints.entries.singleOrNull { it.value == targetMaximumKHz }?.key
            ?: return failed("write_failed", current)
        if (abs(targetIndex - previousIndex) != 1 ||
            targetIndex > (raw.floorIndex.takeIf { it >= 0 } ?: current.operatingPoints.size - 1))
            return failed("write_failed", current)
        val target = raw.copy(ceilingIndex = targetIndex)
        val previousOwner = owner
        val result = compareAndSet(current.control, target)
        controlRevision++
        val observed = manager.readControlSnapshot()
        trial = null
        if ((result.successful || result.controlWriteAttempted) &&
            sameControls(observed, target, current.operatingPoints)) {
            owner = Ownership(previousOwner?.baseline ?: current.control, target)
            val token = GpuRestoreToken(++tokenSequence, manualRevision, controlRevision)
            trial = Trial(token, current.control, target, previousOwner)
        } else if (sameControls(observed, raw, current.operatingPoints)) {
            owner = previousOwner
        } else {
            owner = null
        }
        val after = snapshot(observed)
        val success = result.successful && sameControls(observed, target, current.operatingPoints) &&
            observed.state.controlMode in listOf(GpuMode.AUTOMATIC, GpuMode.RANGE) && observed.state.writable
        return AdaptiveGpuWrite(success, reason(result, success, "one_step_trial"), after, trial?.token, result)
    }

    /** Undo only the most recent trial, restoring raw values (including an absent floor). */
    @Synchronized fun adaptiveRestore(token: GpuRestoreToken): AdaptiveGpuWrite {
        val current = snapshot()
        if (token.manualRevision != manualRevision) return failed("manual_override", current)
        if (current.controls == null || current.operatingPoints.isEmpty())
            return failed("missing_telemetry", current)
        val pending = trial
        if (pending == null || pending.token != token || token.controlRevision != controlRevision ||
            !current.adaptiveOwned || current.controls != pending.after)
            return failed("external_override", current)
        val target = pending.before.controls ?: return failed("missing_telemetry", current)
        return restore(current, target, pending.previousOwner)
    }

    /** Release all accepted adaptive trials back to the original raw automatic state. */
    @Synchronized fun releaseAdaptive(expected: GpuSessionSnapshot): AdaptiveGpuWrite {
        val current = snapshot()
        rejectStale(expected, current)?.let { return failed(it, current) }
        val owned = owner
        if (owned == null) {
            return if (current.controls?.automatic == true)
                AdaptiveGpuWrite(true, "restored", current)
            else failed("manual_lock", current)
        }
        val baseline = owned.baseline.controls ?: return failed("missing_telemetry", current)
        return restore(current, baseline, null)
    }

    private fun restore(current: GpuSessionSnapshot, target: GpuRawControls, restoredOwner: Ownership?): AdaptiveGpuWrite {
        val previousOwner = owner
        val result = compareAndSet(current.control, target)
        controlRevision++
        val observed = manager.readControlSnapshot()
        trial = null
        owner = when {
            (result.successful || result.controlWriteAttempted) &&
                sameControls(observed, target, current.operatingPoints) -> restoredOwner
            sameControls(observed, current.controls, current.operatingPoints) -> previousOwner
            else -> null
        }
        val after = snapshot(observed)
        val success = result.successful && sameControls(observed, target, current.operatingPoints) &&
            observed.state.controlMode in listOf(GpuMode.AUTOMATIC, GpuMode.RANGE) && observed.state.writable
        return AdaptiveGpuWrite(success, reason(result, success, "restored"), after, result = result)
    }

    private fun snapshot(control: GpuControlSnapshot): GpuSessionSnapshot {
        owner?.let { owned ->
            val changedControls = control.controls?.let { it != owned.current } == true
            val changedTable = control.operatingPoints.isNotEmpty() &&
                control.operatingPoints != owned.baseline.operatingPoints
            // A failed proc read is not a confirmed external write. Suspend writes while
            // either component is unavailable, and retain the handle for an exact retry.
            if (changedControls || changedTable) {
                // Once an external write is observed, never reacquire its range later,
                // even if the external controller subsequently returns to the same values.
                owner = null
                trial = null
                controlRevision++
            }
        }
        return GpuSessionSnapshot(control, manualRevision, controlRevision, owner != null)
    }

    private fun sameControls(control: GpuControlSnapshot, raw: GpuRawControls?, table: Map<Int, Long>) =
        raw != null && control.controls == raw && control.operatingPoints == table

    private fun rejectStale(expected: GpuSessionSnapshot, current: GpuSessionSnapshot): String? = when {
        expected.manualRevision != current.manualRevision -> "manual_override"
        current.controls == null || current.operatingPoints.isEmpty() -> "missing_telemetry"
        expected.guardKey != current.guardKey -> "external_override"
        else -> null
    }

    private fun failed(reason: String, current: GpuSessionSnapshot) = AdaptiveGpuWrite(false, reason, current)

    private fun reason(result: GpuResult, success: Boolean, successfulReason: String): String = when {
        success -> successfulReason
        result.failure == GpuFailure.CONFLICT || result.successful -> "external_override"
        result.failure == GpuFailure.READBACK_FAILED -> "readback_failed"
        result.failure == GpuFailure.UNAVAILABLE || result.failure == GpuFailure.UNSUPPORTED -> "missing_telemetry"
        else -> "write_failed"
    }
}

internal object GpuControlSession {
    private val coordinator = GpuControlCoordinator()
    fun snapshot(): GpuSessionSnapshot = coordinator.snapshot()
    fun <T> manual(block: (GpuControlManager) -> T): T = coordinator.manual(block)
    fun adaptiveMaximum(expected: GpuSessionSnapshot, targetMaximumKHz: Long): AdaptiveGpuWrite =
        coordinator.adaptiveMaximum(expected, targetMaximumKHz)
    fun adaptiveRestore(token: GpuRestoreToken): AdaptiveGpuWrite = coordinator.adaptiveRestore(token)
    fun releaseAdaptive(expected: GpuSessionSnapshot): AdaptiveGpuWrite = coordinator.releaseAdaptive(expected)
}
