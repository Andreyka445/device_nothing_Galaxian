/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.galaxian.cpu

import java.io.File
import java.io.IOException

internal data class CpuPolicyState(
    val id: Int,
    val cpus: List<Int>,
    val frequencies: List<Long>,
    val minimum: Long,
    val maximum: Long,
    val current: Long?,
    val writable: Boolean,
    val lockSupported: Boolean,
    val locked: Boolean,
)

internal data class CpuCoreState(val id: Int, val online: Boolean?, val canToggle: Boolean)

internal data class CpuState(
    val policies: List<CpuPolicyState>,
    val cores: List<CpuCoreState>,
    val unavailablePolicies: List<Int>,
)

internal enum class CpuFailure {
    UNAVAILABLE, INVALID_FREQUENCY, INVALID_RANGE, LAST_CORE, WRITE_FAILED, READBACK_FAILED,
    LOCK_UNSUPPORTED,
}

internal data class CpuResult(
    val failure: CpuFailure? = null,
    val rollbackFailed: Boolean = false,
) {
    val successful: Boolean get() = failure == null
}

/**
 * Controls Galaxian's two CPU policies using the kernel's advertised OPPs.
 * There is no saved profile, boot receiver or periodic writer. A capable kernel owns forced
 * policy bounds atomically; older kernels support temporary ordinary limits only.
 * The baseline lives only for this app process.
 */
internal class CpuControlManager(
    private val cpuRoot: File = File("/sys/devices/system/cpu"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
) {
    private data class Bounds(val minimum: Long, val maximum: Long, val locked: Boolean = false)
    private data class Snapshot(
        val policies: Map<Int, Bounds>,
        val cores: Map<Int, Boolean>,
    )
    private class OperationFailure(val reason: CpuFailure) : IOException()

    private val initialPolicies = mutableMapOf<Int, Bounds>()
    private val initialCores = mutableMapOf<Int, Boolean>()

    @Synchronized
    fun readState(): CpuState {
        val policies = POLICY_IDS.mapNotNull(::readPolicy)
        val online = readOnlineCores()
        val cores = (0..7).map { id ->
            val state = online[id]
            val group = policyCpus(id)
            val lastOnline = state == true && group.count { online[it] == true } <= 1
            CpuCoreState(
                id = id,
                online = state,
                canToggle = id != 0 && state != null && onlineFile(id).canWrite() &&
                    (!lastOnline || !state),
            )
        }
        policies.forEach { initialPolicies.putIfAbsent(it.id, Bounds(it.minimum, it.maximum)) }
        cores.forEach { core -> core.online?.let { initialCores.putIfAbsent(core.id, it) } }
        return CpuState(policies, cores, POLICY_IDS.filter { id -> policies.none { it.id == id } })
    }

    @Synchronized
    fun setFrequency(id: Int, minimum: Boolean, frequency: Long): CpuResult {
        val policy = readPolicy(id) ?: return CpuResult(CpuFailure.UNAVAILABLE)
        return setFrequencyRange(
            id,
            if (minimum) frequency else policy.minimum,
            if (minimum) policy.maximum else frequency,
        )
    }

    /** force=true acquires the kernel lock and applies both bounds in one sysfs transaction. */
    @Synchronized
    fun setFrequencyRange(id: Int, minimum: Long, maximum: Long, force: Boolean = false): CpuResult {
        readState()
        val policy = readPolicy(id) ?: return CpuResult(CpuFailure.UNAVAILABLE)
        if (force && !policy.lockSupported) return CpuResult(CpuFailure.LOCK_UNSUPPORTED)
        if (!policy.writable) return CpuResult(CpuFailure.UNAVAILABLE)
        if (minimum !in policy.frequencies || maximum !in policy.frequencies) {
            return CpuResult(CpuFailure.INVALID_FREQUENCY)
        }
        if (minimum > maximum) return CpuResult(CpuFailure.INVALID_RANGE)
        val bounds = Bounds(minimum, maximum, force || policy.locked)
        return applyTransaction(Snapshot(mapOf(id to bounds), emptyMap()))
    }

    @Synchronized
    fun setFrequencyLock(id: Int, enabled: Boolean): CpuResult {
        readState()
        val policy = readPolicy(id) ?: return CpuResult(CpuFailure.UNAVAILABLE)
        if (!policy.lockSupported) return CpuResult(CpuFailure.LOCK_UNSUPPORTED)
        if (enabled) return setFrequencyRange(id, policy.minimum, policy.maximum, force = true)
        if (!policy.locked) return CpuResult()
        return try {
            writer(lockFile(id), "0\n")
            if (readLock(id)?.locked != false) throw OperationFailure(CpuFailure.READBACK_FAILED)
            // Releasing a lock reapplies the real current QoS aggregate. Do not replace it with
            // the previously forced bounds, and do not demand that the old range stay applied.
            CpuResult()
        } catch (error: Exception) {
            val restored = try {
                writeBounds(id, Bounds(policy.minimum, policy.maximum, locked = true))
                true
            } catch (_: Exception) {
                false
            }
            CpuResult((error as? OperationFailure)?.reason ?: CpuFailure.WRITE_FAILED, !restored)
        }
    }

    @Synchronized
    fun setCoreOnline(id: Int, online: Boolean): CpuResult {
        readState()
        if (id !in 1..7 || !onlineFile(id).canWrite()) return CpuResult(CpuFailure.UNAVAILABLE)
        return applyTransaction(Snapshot(emptyMap(), mapOf(id to online)))
    }

    @Synchronized
    fun restoreInitialValues(): CpuResult {
        readState()
        if (initialPolicies.isEmpty()) return CpuResult(CpuFailure.UNAVAILABLE)
        return applyTransaction(Snapshot(initialPolicies.toMap(), initialCores.toMap()))
    }

    private fun applyTransaction(target: Snapshot): CpuResult {
        val previous = try {
            Snapshot(
                target.policies.mapValues { (id, _) -> readBounds(id) },
                target.cores.mapValues { (id, _) ->
                    readOnlineCores()[id] ?: throw OperationFailure(CpuFailure.UNAVAILABLE)
                },
            )
        } catch (error: OperationFailure) {
            return CpuResult(error.reason)
        }
        return try {
            applySnapshot(target)
            CpuResult()
        } catch (error: Exception) {
            val rolledBack = try {
                applySnapshot(previous)
                true
            } catch (rollbackError: Exception) {
                false
            }
            CpuResult((error as? OperationFailure)?.reason ?: CpuFailure.WRITE_FAILED, !rolledBack)
        }
    }

    private fun applySnapshot(snapshot: Snapshot) {
        // A policy may disappear when its entire cluster is offline. Online required cores first.
        snapshot.cores.filterValues { it }.forEach { (id, _) -> writeCore(id, true) }
        snapshot.policies.forEach { (id, bounds) -> writeBounds(id, bounds) }
        snapshot.cores.filterValues { !it }.keys.sortedDescending().forEach { writeCore(it, false) }
        snapshot.policies.forEach { (id, expected) ->
            if (readBounds(id) != expected) throw OperationFailure(CpuFailure.READBACK_FAILED)
        }
        val online = readOnlineCores()
        if (snapshot.cores.any { (id, expected) -> online[id] != expected }) {
            throw OperationFailure(CpuFailure.READBACK_FAILED)
        }
    }

    private fun writeBounds(id: Int, target: Bounds) {
        if (id !in POLICY_IDS || target.minimum <= 0 || target.minimum > target.maximum) {
            throw OperationFailure(CpuFailure.INVALID_RANGE)
        }
        if (target.locked) {
            if (readLock(id) == null || !lockFile(id).canWrite()) {
                throw OperationFailure(CpuFailure.LOCK_UNSUPPORTED)
            }
            writer(lockFile(id), "1 ${target.minimum} ${target.maximum}\n")
            if (readLock(id) != target || readBounds(id) != target) {
                throw OperationFailure(CpuFailure.READBACK_FAILED)
            }
            return
        }
        if (readLock(id)?.locked == true) {
            writer(lockFile(id), "0\n")
            if (readLock(id)?.locked != false) throw OperationFailure(CpuFailure.READBACK_FAILED)
        }
        val before = readBounds(id)
        // Never transiently put min above max or max below min, including during rollback.
        if (target.maximum < before.minimum) {
            writeFrequency(id, "scaling_min_freq", target.minimum)
            writeFrequency(id, "scaling_max_freq", target.maximum)
        } else {
            writeFrequency(id, "scaling_max_freq", target.maximum)
            writeFrequency(id, "scaling_min_freq", target.minimum)
        }
        if (readBounds(id) != target) throw OperationFailure(CpuFailure.READBACK_FAILED)
    }

    private fun writeFrequency(id: Int, node: String, value: Long) {
        val file = File(policyDirectory(id), node)
        if (readLong(file) == value) return
        writer(file, "$value\n")
        if (readLong(file) != value) throw OperationFailure(CpuFailure.READBACK_FAILED)
    }

    private fun writeCore(id: Int, online: Boolean) {
        val states = readOnlineCores()
        val current = states[id] ?: throw OperationFailure(CpuFailure.UNAVAILABLE)
        if (current == online) return
        if (id !in 1..7) throw OperationFailure(CpuFailure.UNAVAILABLE)
        if (!online && policyCpus(id).count { states[it] == true } <= 1) {
            throw OperationFailure(CpuFailure.LAST_CORE)
        }
        writer(onlineFile(id), if (online) "1\n" else "0\n")
        if (readOnlineCores()[id] != online) throw OperationFailure(CpuFailure.READBACK_FAILED)
    }

    private fun readPolicy(id: Int): CpuPolicyState? {
        if (id !in POLICY_IDS) return null
        val directory = policyDirectory(id)
        val minimum = readLong(File(directory, "scaling_min_freq")) ?: return null
        val maximum = readLong(File(directory, "scaling_max_freq")) ?: return null
        if (minimum <= 0 || minimum > maximum) return null
        // Do not synthesize a frequency table: cpufreq-hw derives it from the device's LUT.
        val frequencies = readText(File(directory, "scaling_available_frequencies"))
            ?.split(Regex("\\s+"))?.mapNotNull(String::toLongOrNull)
            ?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val cpus = parseCpuList(readText(File(directory, "related_cpus")))
            .ifEmpty { policyCpus(id) }
        val lock = readLock(id)
        val canLock = lock != null && lockFile(id).canWrite()
        return CpuPolicyState(
            id, cpus, frequencies, minimum, maximum,
            readLong(File(directory, "scaling_cur_freq"))
                ?: readLong(File(directory, "cpuinfo_cur_freq")),
            frequencies.isNotEmpty() && (if (lock?.locked == true) canLock else
                File(directory, "scaling_min_freq").canWrite() &&
                    File(directory, "scaling_max_freq").canWrite()),
            lockSupported = canLock,
            locked = lock?.locked == true,
        )
    }

    private fun readBounds(id: Int): Bounds {
        val policy = readPolicy(id) ?: throw OperationFailure(CpuFailure.UNAVAILABLE)
        return Bounds(policy.minimum, policy.maximum, policy.locked)
    }

    private fun readLock(id: Int): Bounds? {
        val values = readText(lockFile(id))?.split(Regex("\\s+"))
            ?.map { it.toLongOrNull() ?: return null } ?: return null
        if (values.size != 3 || values[0] !in 0L..1L || values[1] <= 0 || values[1] > values[2]) {
            return null
        }
        return Bounds(values[1], values[2], values[0] == 1L)
    }

    private fun readOnlineCores(): Map<Int, Boolean?> {
        val globalOnline = readText(File(cpuRoot, "online"))?.let(::parseCpuList)
        return (0..7).associateWith { id ->
            when (readLong(onlineFile(id))) {
                0L -> false
                1L -> true
                else -> if (globalOnline != null) id in globalOnline else if (id == 0) true else null
            }
        }
    }

    // The MT6878 topology is fixed: four Cortex-A55 and four Cortex-A78 cores.
    private fun policyCpus(id: Int): List<Int> = if (id < 4) (0..3).toList() else (4..7).toList()
    private fun policyDirectory(id: Int) = File(cpuRoot, "cpufreq/policy$id")
    private fun lockFile(id: Int) = File(policyDirectory(id), "scaling_locked_limits")
    private fun onlineFile(id: Int) = File(cpuRoot, "cpu$id/online")
    private fun readLong(file: File): Long? = readText(file)?.toLongOrNull()
    private fun readText(file: File): String? = try { file.readText().trim() } catch (_: Exception) { null }

    private fun parseCpuList(text: String?): List<Int> = text.orEmpty()
        .split(Regex("[,\\s]+"))
        .flatMap { part ->
            val range = part.split('-')
            val start = range.firstOrNull()?.toIntOrNull()
            val end = range.lastOrNull()?.toIntOrNull()
            if (start == null || end == null || start !in 0..7 || end !in start..7) emptyList()
            else (start..end).toList()
        }.distinct().sorted()

    companion object {
        private val POLICY_IDS = listOf(0, 4)
    }
}
