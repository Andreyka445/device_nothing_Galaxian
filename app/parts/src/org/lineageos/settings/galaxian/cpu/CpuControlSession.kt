/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.cpu

import java.io.File

internal data class CpuSessionSnapshot(val state: CpuState, val manualRevision: Long)
internal data class AdaptiveCpuWrite(val successful: Boolean, val reason: String, val maximum: Long?)

/** One process-wide monitor serializes manual and adaptive writes. Manual attempts take priority. */
internal class CpuControlCoordinator(
    private val manager: CpuControlManager = CpuControlManager(),
    private val maximumWriter: (Int, Long) -> Unit = { id, value ->
        File("/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq").writeText("$value\n")
    },
) {
    private var revision = 0L

    @Synchronized fun <T> read(block: (CpuControlManager) -> T): T = block(manager)
    @Synchronized fun snapshot() = CpuSessionSnapshot(manager.readState(), revision)

    @Synchronized fun <T> manual(block: (CpuControlManager) -> T): T {
        // Invalidate AI ownership even if a requested manual operation fails or keeps the same value.
        revision++
        return block(manager)
    }

    @Synchronized fun adaptiveMaximum(
        policyId: Int, expectedMinimum: Long, expectedMaximum: Long,
        targetMaximum: Long, expectedRevision: Long,
    ): AdaptiveCpuWrite {
        if (expectedRevision != revision) return AdaptiveCpuWrite(false, "manual_override", null)
        val policy = manager.readState().policies.firstOrNull { it.id == policyId }
            ?: return AdaptiveCpuWrite(false, "missing_telemetry", null)
        if (policy.locked) return AdaptiveCpuWrite(false, "manual_lock", policy.maximum)
        if (policy.minimum != expectedMinimum || policy.maximum != expectedMaximum)
            return AdaptiveCpuWrite(false, "external_override", policy.maximum)
        if (!policy.writable || targetMaximum !in policy.frequencies || targetMaximum < policy.minimum)
            return AdaptiveCpuWrite(false, "write_failed", policy.maximum)
        try {
            // Exactly one maximum write: no min/core/lock change, no retry or automatic rollback
            // that could overwrite a concurrently replacing power-HAL value.
            maximumWriter(policyId, targetMaximum)
        } catch (_: Exception) {
            return AdaptiveCpuWrite(false, "write_failed", null)
        }
        val actual = manager.readState().policies.firstOrNull { it.id == policyId }
        val matches = actual?.locked == false && actual.minimum == expectedMinimum &&
            actual.maximum == targetMaximum
        return AdaptiveCpuWrite(matches, if (matches) "one_step_trial" else "readback_failed", actual?.maximum)
    }
}

internal object CpuControlSession {
    private val coordinator = CpuControlCoordinator()
    fun <T> read(block: (CpuControlManager) -> T): T = coordinator.read(block)
    fun <T> manual(block: (CpuControlManager) -> T): T = coordinator.manual(block)
    fun snapshot(): CpuSessionSnapshot = coordinator.snapshot()
    fun adaptiveMaximum(id: Int, minimum: Long, maximum: Long, target: Long, revision: Long) =
        coordinator.adaptiveMaximum(id, minimum, maximum, target, revision)
}
