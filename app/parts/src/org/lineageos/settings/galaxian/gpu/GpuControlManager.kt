/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.gpu

import java.io.File
import java.io.IOException

internal enum class GpuMode { AUTOMATIC, RANGE, FIXED, UNKNOWN }
internal enum class GpuFailure {
    UNAVAILABLE, UNSUPPORTED, INVALID_FREQUENCY, INVALID_RANGE, CONFLICT,
    WRITE_FAILED, READBACK_FAILED,
}
internal data class GpuResult(
    val failure: GpuFailure? = null,
    val rollbackFailed: Boolean = false,
    val controlWriteAttempted: Boolean = false,
) {
    val successful: Boolean get() = failure == null
}
internal data class GpuState(
    val currentKHz: Long?,
    val frequenciesKHz: List<Long>,
    val fixedKHz: Long?,
    val requestedMinimumKHz: Long?,
    val requestedMaximumKHz: Long?,
    val effectiveMinimumKHz: Long?,
    val effectiveMaximumKHz: Long?,
    val controlMode: GpuMode,
    val readable: Boolean,
    val writable: Boolean,
    val unavailableReason: GpuFailure?,
    val active: Boolean?,
    val canRestore: Boolean,
)

/** Raw proc values are retained: -1 is an absent constraint, not the lowest/highest OPP. */
internal data class GpuRawControls(val ceilingIndex: Int, val floorIndex: Int, val fixedIndex: Int) {
    val automatic: Boolean get() = ceilingIndex == -1 && floorIndex == -1 && fixedIndex == -1
}
internal data class GpuUtilization(val loadingPercent: Int, val blockedPercent: Int, val idlePercent: Int)
internal data class GpuControlSnapshot(
    val state: GpuState,
    val controls: GpuRawControls?,
    val operatingPoints: Map<Int, Long>,
    val utilization: GpuUtilization?,
)

/**
 * Single-domain MediaTek GPUFREQ v2. Only the DEBUG limiter (1) is written, with OPP
 * indexes from the device's working table. A range remains a dynamic PPM constraint:
 * it does not use fixed-OPP mode, voltages, enable switches, or repeated forcing.
 */
internal class GpuControlManager(
    private val root: File = File("/proc/gpufreqv2"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val utilizationFile: File = File("/sys/kernel/ged/hal/gpu_utilization"),
) {
    private data class Status(
        val currentKHz: Long?, val active: Boolean, val singleDomain: Boolean,
        val dvfs: Int, val count: Int, val currentIndex: Int,
        val ceiling: Int, val floor: Int,
    )
    private data class Limits(val ceiling: Int, val floor: Int) {
        val automatic: Boolean get() = ceiling == -1 && floor == -1
    }
    private data class Limiter(
        val id: Int, val name: String, val priority: Int,
        val limits: Limits, val ceilingEnabled: Boolean, val floorEnabled: Boolean,
    )
    private data class LimitTable(val debug: Limits, val rows: List<Limiter>) {
        // Match gpuppm.c::__gpuppm_sort_limit. Compare against the independently reported
        // effective PPM bounds, so an updated DEBUG cache alone is not a successful readback.
        fun effective(count: Int): Limits? {
            if (count <= 0 || rows.any { row ->
                listOf(row.limits.ceiling, row.limits.floor).any { it !in -1 until count }
            }) return null
            val segment = rows.singleOrNull { it.id == 0 && it.name == "SEGMENT" } ?: return null
            if (segment.limits.ceiling !in 0 until count || segment.limits.floor !in 0 until count)
                return null
            val ceilings = rows.filter { it.id != 0 && it.ceilingEnabled && it.limits.ceiling > 0 }
            val floors = rows.filter { it.id != 0 && it.floorEnabled && it.limits.floor in 0 until count - 1 }
            var ceiling = ceilings.maxOfOrNull { it.limits.ceiling } ?: segment.limits.ceiling
            var floor = floors.minOfOrNull { it.limits.floor } ?: segment.limits.floor
            val ceilingPriority = ceilings.maxOfOrNull { it.priority } ?: segment.priority
            val floorPriority = floors.maxOfOrNull { it.priority } ?: segment.priority
            if (ceiling > floor) {
                if (floorPriority > ceilingPriority) ceiling = floor else floor = ceiling
            }
            return Limits(ceiling, floor)
        }
    }
    private data class Settings(val limits: Limits, val fixed: Int)
    private data class DriverState(
        val state: GpuState, val table: Map<Int, Long>, val settings: Settings?,
        val status: Status?, val supported: Boolean, val expectedEffective: Limits?,
    )
    private class ReadbackFailure : IOException()
    private class ConflictFailure : IOException()
    private val fixedControl get() = File(root, "fix_target_opp_index")
    private val rangeControl get() = File(root, "limit_table")

    @Synchronized fun readState(): GpuState = readDriver().state

    @Synchronized fun readControlSnapshot(): GpuControlSnapshot = readDriver().let { driver ->
        GpuControlSnapshot(driver.state, driver.settings?.let {
            GpuRawControls(it.limits.ceiling, it.limits.floor, it.fixed)
        }, driver.table.toMap(), if (driver.state.active == true) readUtilization() else null)
    }

    /** One guarded DEBUG ceiling write. Only the coordinator decides who owns this range. */
    @Synchronized fun compareAndSetAdaptive(
        expected: GpuControlSnapshot, target: GpuRawControls,
    ): GpuResult {
        val before = readDriver()
        val controls = before.settings?.let { GpuRawControls(it.limits.ceiling, it.limits.floor, it.fixed) }
        if (controls != expected.controls || before.table != expected.operatingPoints)
            return GpuResult(GpuFailure.CONFLICT)
        if (controls == null || !before.state.writable ||
            before.state.controlMode !in listOf(GpuMode.AUTOMATIC, GpuMode.RANGE))
            return GpuResult(before.state.unavailableReason ?: GpuFailure.UNAVAILABLE)
        // Adaptive work never fixes an OPP, changes the minimum, or normalizes -1 to an endpoint.
        if (target.fixedIndex != -1 || controls.fixedIndex != -1 || target.floorIndex != controls.floorIndex)
            return GpuResult(GpuFailure.INVALID_RANGE)
        if (target.ceilingIndex != -1 && target.ceilingIndex !in before.table)
            return GpuResult(GpuFailure.INVALID_FREQUENCY)
        if (target.ceilingIndex.coerceAtLeast(0) >
            (target.floorIndex.takeIf { it >= 0 } ?: before.table.size - 1))
            return GpuResult(GpuFailure.INVALID_RANGE)
        return apply(Settings(Limits(target.ceilingIndex, target.floorIndex), -1), before,
            expected.operatingPoints)
    }

    private fun readUtilization(): GpuUtilization? = try {
        // ged_hal.c prints loading, blocked, idle as unsigned percentages. It is
        // a driver observation without a timestamp/window, not an FPS or jank estimate.
        val text = utilizationFile.readText().trim()
        val values = text.split(Regex("\\s+"))
        if (values.size != 3 || values.any { !it.matches(Regex("[0-9]+")) }) null
        else values.map { it.toIntOrNull() }.let { percentages ->
            if (percentages.any { it == null || it !in 0..100 }) null
            else GpuUtilization(percentages[0]!!, percentages[1]!!, percentages[2]!!)
        }
    } catch (_: IOException) { null } catch (_: SecurityException) { null }

    @Synchronized fun setFrequencyRange(minimumKHz: Long, maximumKHz: Long): GpuResult {
        if (minimumKHz > maximumKHz) return GpuResult(GpuFailure.INVALID_RANGE)
        val before = readDriver()
        if (!before.state.writable) {
            return GpuResult(before.state.unavailableReason ?: GpuFailure.UNAVAILABLE)
        }
        val floor = before.table.entries.singleOrNull { it.value == minimumKHz }?.key
            ?: return GpuResult(GpuFailure.INVALID_FREQUENCY)
        val ceiling = before.table.entries.singleOrNull { it.value == maximumKHz }?.key
            ?: return GpuResult(GpuFailure.INVALID_FREQUENCY)
        return apply(Settings(Limits(ceiling, floor), -1), before)
    }

    @Synchronized fun restoreAutomatic(): GpuResult {
        val before = readDriver()
        if (!before.state.canRestore) {
            return GpuResult(before.state.unavailableReason ?: GpuFailure.UNAVAILABLE)
        }
        return apply(Settings(Limits(-1, -1), -1), before)
    }

    private fun apply(target: Settings, before: DriverState, guardedTable: Map<Int, Long>? = null): GpuResult {
        val original = before.settings ?: return GpuResult(GpuFailure.UNAVAILABLE)
        val owned = mutableSetOf(original)
        var expected = original
        var controlWriteAttempted = false
        return try {
            if (expected.limits != target.limits) {
                requireUnchanged(expected, guardedTable)
                val next = expected.copy(limits = target.limits)
                owned += next
                controlWriteAttempted = true
                writeLimits(next.limits)
                if (!awaitSettings(next, guardedTable)) throw ReadbackFailure()
                expected = next
            }
            if (expected.fixed != target.fixed) {
                requireUnchanged(expected, guardedTable)
                val next = expected.copy(fixed = target.fixed)
                owned += next
                controlWriteAttempted = true
                writer(fixedControl, "${next.fixed}\n")
                if (!awaitSettings(next, guardedTable)) throw ReadbackFailure()
                expected = next
            }
            if (!awaitApplied(target, guardedTable)) throw ReadbackFailure()
            GpuResult(controlWriteAttempted = controlWriteAttempted)
        } catch (error: Exception) {
            val observed = readDriver()
            // The proc API has no compare-and-swap. Recheck both manual controls immediately
            // before every write; never restore a different controller's observed setting.
            val canRollback = observed.supported && observed.settings in owned && observed.state.canRestore &&
                (guardedTable == null || observed.table == guardedTable)
            val restored = canRollback && rollback(original, observed, before.table, guardedTable)
            GpuResult(when (error) {
                is ConflictFailure -> GpuFailure.CONFLICT
                is ReadbackFailure -> GpuFailure.READBACK_FAILED
                else -> GpuFailure.WRITE_FAILED
            }, rollbackFailed = !restored, controlWriteAttempted = controlWriteAttempted)
        }
    }

    private fun rollback(original: Settings, observed: DriverState, originalTable: Map<Int, Long>,
        guardedTable: Map<Int, Long>? = null): Boolean {
        var expected = observed.settings ?: return false
        if (expected == original) return settingsMatch(observed, original)
        return try {
            if (expected.fixed != original.fixed) {
                if (original.fixed >= 0 && (observed.table[original.fixed] == null ||
                    observed.table[original.fixed] != originalTable[original.fixed])) return false
                requireUnchanged(expected, guardedTable)
                writer(fixedControl, "${original.fixed}\n")
                expected = expected.copy(fixed = original.fixed)
                if (!awaitSettings(expected, guardedTable)) return false
            }
            if (expected.limits != original.limits) {
                for (index in listOf(original.limits.ceiling, original.limits.floor)) {
                    if (index >= 0 && (observed.table[index] == null ||
                        observed.table[index] != originalTable[index])) return false
                }
                requireUnchanged(expected, guardedTable)
                writeLimits(original.limits)
                expected = expected.copy(limits = original.limits)
                if (!awaitSettings(expected, guardedTable)) return false
            }
            settingsMatch(readDriver(), original, guardedTable)
        } catch (_: Exception) { false }
    }

    private fun writeLimits(limits: Limits) {
        writer(rangeControl, "set 1 ${limits.ceiling} ${limits.floor}\n")
    }

    private fun requireUnchanged(expected: Settings, guardedTable: Map<Int, Long>? = null) {
        val current = readDriver()
        if (!current.state.canRestore || !settingsMatch(current, expected, guardedTable)) throw ConflictFailure()
    }

    private fun settingsMatch(driver: DriverState, expected: Settings, guardedTable: Map<Int, Long>? = null): Boolean {
        if (!driver.supported || driver.settings != expected ||
            (guardedTable != null && driver.table != guardedTable)) return false
        val fixedBit = driver.status?.dvfs?.and(FIXED_BIT) != 0
        return fixedBit == (expected.fixed >= 0)
    }

    private fun awaitSettings(expected: Settings, guardedTable: Map<Int, Long>? = null): Boolean =
        await { settingsMatch(it, expected, guardedTable) }

    private fun awaitApplied(expected: Settings, guardedTable: Map<Int, Long>? = null): Boolean = await { driver ->
        if (!settingsMatch(driver, expected, guardedTable)) return@await false
        // Clearing our controls does not require an OPP table or claim that other caps vanish.
        if (expected.limits.automatic && expected.fixed == -1) return@await true
        val state = driver.state
        val status = driver.status ?: return@await false
        if (state.controlMode != GpuMode.RANGE || state.effectiveMinimumKHz == null ||
            state.effectiveMaximumKHz == null ||
            driver.expectedEffective != Limits(status.ceiling, status.floor)) return@await false
        // Effective limits can be tighter than requested, or changed by higher-priority caps.
        state.active == false || (status.currentIndex in status.ceiling..status.floor &&
            driver.table[status.currentIndex] == state.currentKHz)
    }

    private fun await(matches: (DriverState) -> Boolean): Boolean {
        repeat(5) { attempt ->
            if (attempt > 0) pause(40)
            if (matches(readDriver())) return true
        }
        return false
    }

    private fun readDriver(): DriverState {
        val firstFixed = read("fix_target_opp_index")?.let(::parseFixedIndex)
        val firstLimits = read("limit_table")?.let(::parseLimits)
        val status = read("gpufreq_status")?.let(::parseStatus)
        val table = read("gpu_working_opp_table")?.let(::parseTable).orEmpty()
        val lastLimits = read("limit_table")?.let(::parseLimits)
        val lastFixed = read("fix_target_opp_index")?.let(::parseFixedIndex)
        val settings = if (firstFixed != null && firstFixed == lastFixed && firstLimits != null &&
            firstLimits.debug == lastLimits?.debug) Settings(firstLimits.debug, firstFixed) else null
        val supported = status?.singleDomain == true && (status.dvfs and UNSUPPORTED_BITS) == 0
        val tableValid = status != null && table.isNotEmpty() && table.size == status.count
        val limitsValid = settings != null && listOf(settings.limits.ceiling, settings.limits.floor)
            .all { it == -1 || it in table } &&
            (settings.limits.ceiling.takeIf { it >= 0 } ?: 0) <=
            (settings.limits.floor.takeIf { it >= 0 } ?: (table.size - 1))
        val effectiveValid = tableValid && status.ceiling in table &&
            status.floor in table && status.ceiling <= status.floor
        val fixedBit = status != null && (status.dvfs and FIXED_BIT) != 0
        val mode = when {
            !supported || settings == null -> GpuMode.UNKNOWN
            settings.fixed >= 0 && fixedBit && tableValid && settings.fixed in table -> GpuMode.FIXED
            settings.fixed != -1 || fixedBit -> GpuMode.UNKNOWN
            settings.limits.automatic -> GpuMode.AUTOMATIC
            tableValid && limitsValid -> GpuMode.RANGE
            else -> GpuMode.UNKNOWN
        }
        val writable = supported && tableValid && limitsValid && effectiveValid &&
            mode != GpuMode.UNKNOWN && rangeControl.canWrite() && fixedControl.canWrite()
        val canRestore = supported && settings != null && rangeControl.canWrite() && fixedControl.canWrite()
        val reason = when {
            status != null && !supported -> GpuFailure.UNSUPPORTED
            !writable -> GpuFailure.UNAVAILABLE
            else -> null
        }
        val requested = settings?.limits?.takeUnless { it.automatic }?.takeIf { tableValid && limitsValid }
        val showEffective = effectiveValid && (mode == GpuMode.AUTOMATIC || mode == GpuMode.RANGE)
        return DriverState(GpuState(
            currentKHz = status?.currentKHz,
            frequenciesKHz = if (tableValid) table.values.sorted() else emptyList(),
            fixedKHz = settings?.fixed?.takeIf { it >= 0 }?.let(table::get),
            requestedMinimumKHz = requested?.let { table[it.floor.takeIf { n -> n >= 0 } ?: (table.size - 1)] },
            requestedMaximumKHz = requested?.let { table[it.ceiling.takeIf { n -> n >= 0 } ?: 0] },
            effectiveMinimumKHz = if (showEffective) table[status!!.floor] else null,
            effectiveMaximumKHz = if (showEffective) table[status!!.ceiling] else null,
            controlMode = mode, readable = status != null, writable = writable,
            unavailableReason = reason, active = status?.active, canRestore = canRestore,
        ), table, settings, status, supported,
            if (firstLimits == lastLimits && tableValid) lastLimits?.effective(table.size) else null)
    }

    private fun read(name: String): String? = try {
        File(root, name).readText().trim()
    } catch (_: IOException) { null } catch (_: SecurityException) { null }

    private fun parseFixedIndex(text: String): Int? {
        if (text == "[GPUFREQ-DEBUG] fix GPU OPP index is disabled") return -1
        return FIXED.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..99 }
    }

    private fun parseLimits(text: String): LimitTable? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty() || !lines.first().contains("[c_enable]") || !lines.first().contains("[f_enable]")) return null
        val rows = lines.drop(1).map { line ->
            val match = LIMIT.matchEntire(line.trim()) ?: return null
            val id = match.groupValues[1].toIntOrNull() ?: return null
            val priority = match.groupValues[3].toIntOrNull() ?: return null
            val ceiling = match.groupValues[4].toIntOrNull()?.takeIf { it in -1..99 } ?: return null
            val floor = match.groupValues[5].toIntOrNull()?.takeIf { it in -1..99 } ?: return null
            Limiter(id, match.groupValues[2], priority, Limits(ceiling, floor),
                match.groupValues[6] == "1", match.groupValues[7] == "1")
        }
        if (rows.map { it.id }.distinct().size != rows.size) return null
        val debug = rows.singleOrNull { it.id == 1 } ?: return null
        if (debug.name != "DEBUG" || debug.priority != 8 || !debug.ceilingEnabled || !debug.floorEnabled) return null
        return LimitTable(debug.limits, rows)
    }

    private fun parseTable(text: String): Map<Int, Long>? {
        val values = linkedMapOf<Int, Long>()
        for (line in text.lines().filter { it.isNotBlank() }) {
            val match = OPP.matchEntire(line.trim()) ?: return null
            val index = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..99 } ?: return null
            val frequency = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            if (index in values || frequency in values.values) return null
            values[index] = frequency
        }
        if (values.isEmpty() || values.keys.toList() != (0 until values.size).toList()) return null
        if (values.values.zipWithNext().any { (high, low) -> high <= low }) return null
        return values
    }

    private fun parseStatus(text: String): Status? {
        val currentMatch = CURRENT.find(text) ?: return null
        val index = currentMatch.groupValues[1].toIntOrNull() ?: return null
        val current = currentMatch.groupValues[2].toLongOrNull() ?: return null
        val count = COUNT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..100 } ?: return null
        val powerCount = POWER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val dvfs = DVFS.find(text)?.groupValues?.get(1)?.toIntOrNull(16) ?: return null
        val dual = DUAL.find(text)?.groupValues?.get(1) ?: return null
        val ceiling = CEILING.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val floor = FLOOR.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val active = powerCount > 0 && (dvfs and IDLE_BITS) == 0
        return Status(current.takeIf { active && it > 0 }, active, dual == "False", dvfs, count, index, ceiling, floor)
    }

    companion object {
        private const val FIXED_BIT = 1 shl 2
        private const val IDLE_BITS = (1 shl 1) or (1 shl 5) or (1 shl 8)
        private const val UNSUPPORTED_BITS = (FIXED_BIT or IDLE_BITS).inv()
        private val FIXED = Regex("\\[GPUFREQ-DEBUG] fix GPU OPP index: (\\d+)")
        private val LIMIT = Regex("(\\d+)\\s+(\\S+)\\s+(\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([01])\\s+([01])")
        private val OPP = Regex("\\[(\\d+)]\\s+freq:\\s+(\\d+),\\s+volt:\\s+\\d+,\\s+vsram:\\s+\\d+(?:,.*)?")
        private val CURRENT = Regex("(?m)^\\[GPU\\s+OPP]\\s+Index:\\s+(-?\\d+),\\s+Freq:\\s+(\\d+),")
        private val COUNT = Regex("(?m)^\\[GPU\\s+Segment].*WorkingOPPNum:\\s+(\\d+),")
        private val POWER = Regex("(?m)^\\[Power State]\\s+PowerCount:\\s+(\\d+),\\s+Active:\\s+(\\d+),")
        private val DVFS = Regex("(?m)^\\[Power State].*DVFSState:\\s+0x([0-9a-fA-F]+)")
        private val DUAL = Regex("(?m)^\\[MFGSYS Config]\\s+DualBuck:\\s+(True|False),")
        private val CEILING = Regex("(?m)^\\[PPM Ceiling]\\s+LimitIndex:\\s+(-?\\d+),")
        private val FLOOR = Regex("(?m)^\\[PPM Floor]\\s+LimitIndex:\\s+(-?\\d+),")
    }
}
