/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.gpu

import java.io.File
import java.io.IOException
import java.nio.file.Files

private class Device {
    val root = Files.createTempDirectory("galaxian-gpu-range-test").toFile()
    val writes = mutableListOf<Pair<String, String>>()
    var fixed = -1
    var current = 2
    var flags = 0
    var powered = true
    var activeCount = 1
    var dual = false
    var ceiling = -1
    var floor = -1
    var enabled = true
    var thermalCeiling = -1
    var effectiveCeiling = 0
    var effectiveFloor = 4
    var rejectRange = false
    var rejectFixed = false
    var ignoreRange = false
    var ignoreFixed = false
    var ignoreHardware = false
    var ignorePpm = false
    var afterWrite: ((String) -> Unit)? = null
    val freqs = listOf(1047000L, 900000L, 700000L, 500000L, 390000L)
    init { table(); publish() }
    fun table() {
        File(root, "gpu_working_opp_table").writeText(freqs.mapIndexed { i, f ->
            "[%02d] freq: %7d, volt: 80000, vsram: 80000".format(i, f)
        }.joinToString("\n"))
    }
    fun publish() {
        // MediaTek uses descending OPP indexes; DEBUG priority is 8 and thermal priority 6.
        if (!ignorePpm) {
            effectiveCeiling = maxOf(ceiling.coerceAtLeast(0), thermalCeiling.coerceAtLeast(0))
            effectiveFloor = if (floor < 0) freqs.lastIndex else floor
            val ceilingPriority = when {
                ceiling > 0 && enabled -> 8
                thermalCeiling > 0 -> 6
                else -> 9 // SEGMENT fallback
            }
            val floorPriority = if (floor in 0 until freqs.lastIndex) 8 else 9
            if (effectiveCeiling > effectiveFloor) {
                if (floorPriority > ceilingPriority) effectiveCeiling = effectiveFloor
                else effectiveFloor = effectiveCeiling
            }
        }
        if (!ignoreHardware) current = if (fixed >= 0) fixed else current.coerceIn(effectiveCeiling, effectiveFloor)
        File(root, "fix_target_opp_index").writeText(if (fixed == -1)
            "[GPUFREQ-DEBUG] fix GPU OPP index is disabled\n"
            else "[GPUFREQ-DEBUG] fix GPU OPP index: $fixed\n")
        File(root, "limit_table").writeText("""
            [id] [name] [priority] [ceiling] [floor] [c_enable] [f_enable]
            0 SEGMENT 9 0 4 1 1
            1 DEBUG 8 $ceiling $floor ${if (enabled) 1 else 0} 1
            4 THERMAL_AP 6 $thermalCeiling -1 1 1
            11 APIBOOST 3 -1 -1 1 1
            12 FPSGO 2 -1 -1 1 1
        """.trimIndent())
        File(root, "gpufreq_status").writeText("""
            [GPUFREQ-DEBUG] Current Status of GPUFREQ
            [GPU   OPP]      Index: $current, Freq: ${freqs[current]}, Volt: 80000, Vsram: 80000
            [GPU   Segment]  SegmentID: 0, WorkingOPPNum: 5, SignedOPPNum: 5
            [PPM Ceiling]    LimitIndex: $effectiveCeiling, Limiter: 1, Priority: 8
            [PPM Floor]      LimitIndex: $effectiveFloor, Limiter: 1, Priority: 8
            [Power State]    PowerCount: ${if (powered) 1 else 0}, Active: $activeCount, CG: 1, MTCMOS: 1, BUCK: 1
            [Power State]    Timestamp: 1, DVFSState: 0x${flags.toString(16).padStart(4, '0')}
            [MFGSYS Config]  DualBuck: ${if (dual) "True" else "False"}, GPUEBSupport: On, StressTest: Off
        """.trimIndent())
    }
    fun write(file: File, value: String) {
        writes += file.name to value
        when (file.name) {
            "limit_table" -> {
                val args = value.trim().split(" ")
                check(args.size == 4 && args[0] == "set" && args[1] == "1")
                val c = args[2].toInt(); val f = args[3].toInt()
                check(c in -1..4 && f in -1..4)
                if (rejectRange) throw IOException("range write denied")
                if (!ignoreRange) { ceiling = c; floor = f }
            }
            "fix_target_opp_index" -> {
                val index = value.trim().toInt(); check(index in -1..4)
                if (rejectFixed) throw IOException("fixed write denied")
                if (!ignoreFixed) { fixed = index; flags = if (index < 0) flags and 4.inv() else flags or 4 }
            }
            else -> error("unexpected GPU control write: ${file.name}")
        }
        afterWrite?.invoke(file.name)
        publish()
    }
    fun manager() = GpuControlManager(root, ::write, {})
}

fun main() {
    var count = 0
    fun run(name: String, body: (Device) -> Unit) {
        val d = Device()
        try { body(d); println("PASS $name"); count++ } finally { d.root.deleteRecursively() }
    }
    run("discover current, available and effective frequencies from driver") { d ->
        val s = d.manager().readState()
        check(s.currentKHz == 700000L && s.frequenciesKHz == d.freqs.reversed())
        check(s.controlMode == GpuMode.AUTOMATIC && s.writable)
        check(s.requestedMinimumKHz == null && s.effectiveMinimumKHz == 390000L && s.effectiveMaximumKHz == 1047000L)
    }
    run("inverted and unsupported ranges are rejected before writing") { d ->
        val m = d.manager()
        check(m.setFrequencyRange(900000, 500000).failure == GpuFailure.INVALID_RANGE)
        check(m.setFrequencyRange(400000, 900000).failure == GpuFailure.INVALID_FREQUENCY)
        check(d.writes.isEmpty())
    }
    run("range writes only DEBUG with inverted index order, not fixed mode") { d ->
        val m = d.manager(); check(m.setFrequencyRange(500000, 900000).successful)
        check(d.writes == listOf("limit_table" to "set 1 1 3\n"))
        check(d.fixed == -1 && d.flags and 4 == 0)
        val s = m.readState()
        check(s.controlMode == GpuMode.RANGE && s.requestedMinimumKHz == 500000L && s.requestedMaximumKHz == 900000L)
        check(s.effectiveMinimumKHz == 500000L && s.effectiveMaximumKHz == 900000L)
    }
    run("DVFS can move within a range without app rewrites") { d ->
        val m = d.manager(); check(m.setFrequencyRange(500000, 900000).successful)
        d.current = 1; d.publish(); check(m.readState().currentKHz == 900000L)
        d.current = 3; d.publish(); check(m.readState().currentKHz == 500000L)
        check(d.writes.size == 1)
    }
    run("equal endpoints use a PPM range, never fixed OPP override") { d ->
        check(d.manager().setFrequencyRange(700000, 700000).successful)
        check(d.writes == listOf("limit_table" to "set 1 2 2\n") && d.fixed == -1)
    }
    run("other controllers can narrow effective limits without falsifying requested range") { d ->
        d.thermalCeiling = 2; d.publish(); val m = d.manager()
        check(m.setFrequencyRange(390000, 1047000).successful)
        val s = m.readState(); check(s.requestedMaximumKHz == 1047000L && s.effectiveMaximumKHz == 700000L)
        check(d.thermalCeiling == 2 && d.writes.all { it.second.startsWith("set 1 ") })
    }
    run("DEBUG floor priority is represented by actual effective readback") { d ->
        d.thermalCeiling = 2; d.publish(); val m = d.manager()
        check(m.setFrequencyRange(900000, 1047000).successful)
        check(m.readState().effectiveMinimumKHz == 900000L && m.readState().effectiveMaximumKHz == 900000L)
        check(d.thermalCeiling == 2)
    }
    run("equal combined priorities prefer ceiling when DEBUG maximum is restricted") { d ->
        d.thermalCeiling = 3; d.publish()
        val r = d.manager().setFrequencyRange(700000, 900000)
        check(r.successful)
        val state = d.manager().readState()
        check(state.requestedMinimumKHz == 700000L && state.requestedMaximumKHz == 900000L)
        check(state.effectiveMinimumKHz == 500000L && state.effectiveMaximumKHz == 500000L)
    }
    run("updated DEBUG cache with stale effective PPM bounds is not success") { d ->
        d.ignorePpm = true
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && !r.rollbackFailed)
        check(d.ceiling == -1 && d.floor == -1)
    }
    run("automatic removes DEBUG limits but preserves thermal cap") { d ->
        d.ceiling = 1; d.floor = 3; d.thermalCeiling = 2; d.publish()
        val m = d.manager(); check(m.restoreAutomatic().successful)
        val s = m.readState(); check(s.controlMode == GpuMode.AUTOMATIC && s.effectiveMaximumKHz == 700000L)
        check(d.writes == listOf("limit_table" to "set 1 -1 -1\n") && d.thermalCeiling == 2)
    }
    run("legacy fixed mode migrates to dynamic scaling only after range confirmation") { d ->
        d.fixed = 0; d.flags = 4; d.publish(); val m = d.manager()
        check(m.readState().controlMode == GpuMode.FIXED)
        check(m.readState().effectiveMaximumKHz == null)
        check(m.setFrequencyRange(500000, 900000).successful)
        check(d.writes == listOf("limit_table" to "set 1 1 3\n", "fix_target_opp_index" to "-1\n"))
        check(m.readState().controlMode == GpuMode.RANGE && d.fixed == -1)
    }
    run("idle GPU retains requested and effective range without a live clock") { d ->
        d.powered = false; d.flags = 2; d.publish(); val m = d.manager()
        check(m.setFrequencyRange(500000, 900000).successful)
        check(m.readState().currentKHz == null && m.readState().effectiveMinimumKHz == 500000L)
    }
    run("zero active count is not idle when active-sleep control is disabled") { d ->
        d.activeCount = 0; d.publish(); check(d.manager().readState().currentKHz == 700000L)
    }
    run("sleep and pre-sleep suppress stale frequency") { d ->
        for (flag in listOf(32, 256)) { d.flags = flag; d.publish(); check(d.manager().readState().currentKHz == null) }
    }
    run("ignored range write fails without inventing applied limits") { d ->
        d.ignoreRange = true; val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && !r.rollbackFailed)
        check(d.ceiling == -1 && d.floor == -1 && d.writes.size == 1)
    }
    run("failed range write leaves original settings intact") { d ->
        d.rejectRange = true; val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.WRITE_FAILED && !r.rollbackFailed && d.writes.size == 1)
    }
    run("wrong active clock fails and restores previous range") { d ->
        d.current = 0; d.ignoreHardware = true; d.publish()
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && !r.rollbackFailed)
        check(d.writes == listOf("limit_table" to "set 1 1 3\n", "limit_table" to "set 1 -1 -1\n"))
    }
    run("failed fixed release rolls back range without changing legacy fixed setting") { d ->
        d.fixed = 0; d.flags = 4; d.rejectFixed = true; d.publish()
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.WRITE_FAILED && !r.rollbackFailed)
        check(d.fixed == 0 && d.ceiling == -1 && d.floor == -1)
    }
    run("unchanged proc cache with inconsistent firmware flag is not called restored") { d ->
        d.ignoreRange = true; d.afterWrite = { d.flags = 4 }
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && r.rollbackFailed && d.writes.size == 1)
    }
    run("third-party DEBUG setting is not overwritten during rollback") { d ->
        d.afterWrite = { if (it == "limit_table") { d.ceiling = 2; d.floor = 4 } }
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && r.rollbackFailed)
        check(d.ceiling == 2 && d.floor == 4 && d.writes.size == 1)
    }
    run("third-party fixed override is not released or overwritten during rollback") { d ->
        d.afterWrite = { d.fixed = 4; d.flags = 4 }
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && r.rollbackFailed)
        check(d.fixed == 4 && d.writes.size == 1)
    }
    run("a voltage override appearing mid-operation prevents further writes") { d ->
        d.afterWrite = { d.flags = 8 }
        val r = d.manager().setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && r.rollbackFailed && d.writes.size == 1)
    }
    run("restore remains available when the OPP table disappears") { d ->
        d.ceiling = 1; d.floor = 3; d.publish(); File(d.root, "gpu_working_opp_table").delete()
        val m = d.manager(); check(!m.readState().writable && m.readState().canRestore)
        check(m.restoreAutomatic().successful && d.ceiling == -1 && d.floor == -1)
    }
    run("disabled DEBUG limiter is not silently enabled") { d ->
        d.enabled = false; d.publish(); val m = d.manager()
        check(!m.readState().writable && !m.restoreAutomatic().successful && d.writes.isEmpty())
    }
    run("unsupported modes and dual-domain hardware cannot be changed") { d ->
        for (flag in listOf(1, 8, 16, 64, 128, 512)) {
            d.flags = flag; d.publish(); val m = d.manager()
            check(m.readState().controlMode == GpuMode.UNKNOWN && !m.restoreAutomatic().successful)
        }
        d.flags = 0; d.dual = true; d.publish()
        check(d.manager().setFrequencyRange(500000, 900000).failure == GpuFailure.UNSUPPORTED && d.writes.isEmpty())
    }
    run("malformed working table blocks range writes") { d ->
        for (table in listOf("[00] freq: 900000, volt: 1, vsram: 1\n[02] freq: 700000, volt: 1, vsram: 1",
            "[00] freq: 900000, volt: 1, vsram: 1\n[01] freq: 900000, volt: 1, vsram: 1", "not a table")) {
            File(d.root, "gpu_working_opp_table").writeText(table)
            check(!d.manager().setFrequencyRange(500000, 900000).successful)
        }
        check(d.writes.isEmpty())
    }
    run("only DEBUG row with expected ID and priority is trusted") { d ->
        for (replacement in listOf("1 OTHER 8", "1 DEBUG 6", "2 DEBUG 8")) {
            d.publish(); val p = File(d.root, "limit_table"); p.writeText(p.readText().replace("1 DEBUG 8", replacement))
            check(!d.manager().setFrequencyRange(500000, 900000).successful)
        }
        check(d.writes.isEmpty())
    }
    run("malformed effective range does not claim successful application") { d ->
        d.afterWrite = null
        val m = GpuControlManager(d.root, { file, value ->
            d.write(file, value)
            if (value.contains("set 1 1 3")) {
                val p = File(d.root, "gpufreq_status")
                p.writeText(p.readText().replace("[PPM Ceiling]    LimitIndex: 1", "[PPM Ceiling]    LimitIndex: 4"))
            }
        }, {})
        val r = m.setFrequencyRange(500000, 900000)
        check(r.failure == GpuFailure.READBACK_FAILED && !r.rollbackFailed)
    }
    run("unchanged range avoids redundant writes") { d ->
        d.ceiling = 1; d.floor = 3; d.publish()
        check(d.manager().setFrequencyRange(500000, 900000).successful && d.writes.isEmpty())
    }
    run("adaptive changes one maximum OPP and restores exact automatic raw controls") { d ->
        val c = GpuControlCoordinator(d.manager()); val start = c.snapshot()
        check(start.controls == GpuRawControls(-1, -1, -1) && !start.adaptiveOwned)
        val applied = c.adaptiveMaximum(start, 900000)
        check(applied.successful && applied.snapshot.adaptiveOwned && applied.maximumKHz == 900000L)
        check(d.writes == listOf("limit_table" to "set 1 1 -1\n") && d.floor == -1 && d.fixed == -1)
        val restored = c.adaptiveRestore(applied.restoreToken!!)
        check(restored.successful && !restored.snapshot.adaptiveOwned)
        check(restored.snapshot.controls == GpuRawControls(-1, -1, -1))
        check(d.writes.last() == "limit_table" to "set 1 -1 -1\n")
    }
    run("latest trial rollback differs from releasing the whole accepted adaptive session") { d ->
        val c = GpuControlCoordinator(d.manager())
        val first = c.adaptiveMaximum(c.snapshot(), 900000); check(first.successful)
        val second = c.adaptiveMaximum(first.snapshot, 700000); check(second.successful)
        check(!c.adaptiveRestore(first.restoreToken!!).successful)
        val back = c.adaptiveRestore(second.restoreToken!!)
        check(back.successful && back.snapshot.adaptiveOwned && back.maximumKHz == 900000L && d.floor == -1)
        val stopped = c.releaseAdaptive(back.snapshot)
        check(stopped.successful && stopped.snapshot.controls == GpuRawControls(-1, -1, -1))
        check(!stopped.snapshot.adaptiveOwned && d.fixed == -1)
    }
    run("returning to highest OPP retains lease until explicitly releasing raw baseline") { d ->
        val c = GpuControlCoordinator(d.manager())
        val lower = c.adaptiveMaximum(c.snapshot(), 900000)
        val upper = c.adaptiveMaximum(lower.snapshot, 1047000)
        check(upper.successful && upper.snapshot.adaptiveOwned && d.ceiling == 0 && d.floor == -1)
        check(c.releaseAdaptive(upper.snapshot).successful && d.ceiling == -1 && d.floor == -1)
    }
    run("adaptive never adopts full partial or default-looking manual ranges") { d ->
        for ((ceiling, floor) in listOf(1 to 3, -1 to 3, 1 to -1, 0 to 4)) {
            d.ceiling = ceiling; d.floor = floor; d.publish()
            val c = GpuControlCoordinator(d.manager()); val before = c.snapshot()
            check(!c.adaptiveMaximum(before, 900000).successful)
            check(!c.releaseAdaptive(before).successful)
            check(d.ceiling == ceiling && d.floor == floor && d.writes.isEmpty())
        }
    }
    run("initial fixed mode is never released by adaptive control") { d ->
        d.fixed = 1; d.flags = 4; d.publish()
        val c = GpuControlCoordinator(d.manager()); val before = c.snapshot()
        check(c.adaptiveMaximum(before, 700000).reason == "manual_lock")
        check(!c.releaseAdaptive(before).successful && d.writes.isEmpty() && d.fixed == 1)
    }
    run("failed manual attempt revokes ownership without additional restore writes") { d ->
        val c = GpuControlCoordinator(d.manager()); val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        val writes = d.writes.size
        check(!c.manual { it.setFrequencyRange(900000, 500000) }.successful)
        check(d.writes.size == writes && d.ceiling == 1 && d.floor == -1)
        val current = c.snapshot()
        check(!current.adaptiveOwned && current.manualRevision == applied.snapshot.manualRevision + 1)
        check(c.adaptiveRestore(applied.restoreToken!!).reason == "manual_override")
        check(!c.adaptiveMaximum(current, 700000).successful && d.writes.size == writes)
    }
    run("manual no-op and thrown exception both invalidate pending GPU proposals") { d ->
        val c = GpuControlCoordinator(d.manager()); val before = c.snapshot()
        check(c.manual { it.restoreAutomatic() }.successful && d.writes.isEmpty())
        check(c.adaptiveMaximum(before, 900000).reason == "manual_override")
        val next = c.snapshot()
        try { c.manual<Unit> { throw IOException("manual error") } } catch (_: IOException) { }
        check(c.adaptiveMaximum(next, 900000).reason == "manual_override" && d.writes.isEmpty())
    }
    run("manual range wins over adaptive trial and stale rollback cannot overwrite it") { d ->
        val c = GpuControlCoordinator(d.manager()); val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        check(c.manual { it.setFrequencyRange(500000, 700000) }.successful)
        val countBefore = d.writes.size
        check(!c.adaptiveRestore(applied.restoreToken!!).successful)
        check(!c.releaseAdaptive(applied.snapshot).successful)
        check(d.ceiling == 2 && d.floor == 3 && d.writes.size == countBefore)
    }
    run("restarting coordinator never adopts a previously adaptive raw range") { d ->
        val first = GpuControlCoordinator(d.manager()); check(first.adaptiveMaximum(first.snapshot(), 900000).successful)
        val replacement = GpuControlCoordinator(d.manager()); val snapshot = replacement.snapshot()
        check(!snapshot.adaptiveOwned && !replacement.adaptiveMaximum(snapshot, 700000).successful)
        check(d.writes.size == 1 && d.ceiling == 1)
    }
    run("stale snapshots and nonadjacent maximum requests cause no GPU writes") { d ->
        val c = GpuControlCoordinator(d.manager()); val before = c.snapshot()
        for (target in listOf(700000L, 1047000L, 899999L)) check(!c.adaptiveMaximum(before, target).successful)
        check(d.writes.isEmpty())
        check(c.adaptiveMaximum(before, 900000).successful)
        check(c.adaptiveMaximum(before, 900000).reason == "external_override" && d.writes.size == 1)
    }
    run("clock and thermal changes do not alter control fingerprint or get overwritten") { d ->
        val c = GpuControlCoordinator(d.manager()); val before = c.snapshot()
        d.current = 3; d.thermalCeiling = 2; d.publish()
        check(c.snapshot().guardKey == before.guardKey)
        val applied = c.adaptiveMaximum(before, 900000)
        check(applied.successful && applied.snapshot.state.effectiveMaximumKHz == 700000L)
        check(d.thermalCeiling == 2 && d.writes == listOf("limit_table" to "set 1 1 -1\n"))
        check(c.releaseAdaptive(applied.snapshot).successful && d.thermalCeiling == 2)
    }
    run("external DEBUG replacement permanently cancels ownership even after an ABA return") { d ->
        val c = GpuControlCoordinator(d.manager()); val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        d.ceiling = 2; d.floor = 3; d.publish(); check(!c.snapshot().adaptiveOwned)
        d.ceiling = 1; d.floor = -1; d.publish()
        check(!c.snapshot().adaptiveOwned && !c.adaptiveRestore(applied.restoreToken!!).successful)
        check(!c.adaptiveMaximum(c.snapshot(), 700000).successful && d.writes.size == 1)
    }
    run("external fixed mode blocks adaptive release without changing its controls") { d ->
        val c = GpuControlCoordinator(d.manager()); val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        d.fixed = 0; d.flags = 4; d.publish()
        check(!c.adaptiveRestore(applied.restoreToken!!).successful)
        check(!c.releaseAdaptive(applied.snapshot).successful && d.fixed == 0 && d.writes.size == 1)
    }
    run("OPP table remapping invalidates proposal before writing") { d ->
        val c = GpuControlCoordinator(d.manager()); val before = c.snapshot()
        val table = File(d.root, "gpu_working_opp_table")
        table.writeText(table.readText().replace("1047000", "1050000"))
        check(c.adaptiveMaximum(before, 900000).reason == "external_override" && d.writes.isEmpty())
    }
    run("OPP remapping during write blocks rollback and relinquishes adaptive ownership") { d ->
        val c = GpuControlCoordinator(d.manager())
        d.afterWrite = {
            val table = File(d.root, "gpu_working_opp_table")
            table.writeText(table.readText().replace("1047000", "1050000"))
        }
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        check(!applied.successful && applied.result?.rollbackFailed == true && !applied.snapshot.adaptiveOwned)
        check(applied.restoreToken == null && d.writes.size == 1)
    }
    run("readback failure keeps recoverable ownership when our raw override remains") { d ->
        val c = GpuControlCoordinator(d.manager()); d.afterWrite = { d.flags = 8 }
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        check(!applied.successful && applied.result?.rollbackFailed == true)
        check(applied.snapshot.adaptiveOwned && applied.restoreToken != null)
        val countBefore = d.writes.size
        check(!c.adaptiveMaximum(applied.snapshot, 700000).successful && d.writes.size == countBefore)
        d.afterWrite = null; d.flags = 0; d.publish()
        check(c.releaseAdaptive(c.snapshot()).successful && d.ceiling == -1 && d.floor == -1)
    }
    run("failed adaptive write with successful rollback never invents a lease") { d ->
        val c = GpuControlCoordinator(d.manager()); d.ignorePpm = true
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        check(!applied.successful && !applied.snapshot.adaptiveOwned && applied.restoreToken == null)
        check(applied.snapshot.controls == GpuRawControls(-1, -1, -1))
    }
    run("driver CAS retains a partial raw floor without endpoint normalization") { d ->
        d.floor = 3; d.publish(); val m = d.manager(); val before = m.readControlSnapshot()
        check(m.compareAndSetAdaptive(before, GpuRawControls(1, 3, -1)).successful)
        check(d.writes.single() == "limit_table" to "set 1 1 3\n")
        check(m.compareAndSetAdaptive(m.readControlSnapshot(), GpuRawControls(-1, 3, -1)).successful)
        check(d.ceiling == -1 && d.floor == 3)
    }
    run("driver CAS cannot change fixed mode minimum or write with stale controls") { d ->
        val m = d.manager(); val before = m.readControlSnapshot()
        check(!m.compareAndSetAdaptive(before, GpuRawControls(1, 4, -1)).successful)
        check(!m.compareAndSetAdaptive(before, GpuRawControls(1, -1, 0)).successful)
        d.ceiling = 2; d.publish()
        check(m.compareAndSetAdaptive(before, GpuRawControls(1, -1, -1)).failure == GpuFailure.CONFLICT)
        check(d.writes.isEmpty())
    }
    run("utilization is a strict optional driver percentage observation") { d ->
        val utilization = File(d.root, "gpu_utilization")
        val m = GpuControlManager(d.root, d::write, {}, utilization)
        check(m.readControlSnapshot().utilization == null)
        for ((text, expected) in listOf("73 4 27" to GpuUtilization(73, 4, 27), "0 0 100\n" to GpuUtilization(0, 0, 100))) {
            utilization.writeText(text); check(m.readControlSnapshot().utilization == expected)
        }
        for (text in listOf("101 0 0", "-1 0 100", "73 0", "73 0 27 extra", "73% 0 27", "999999999999999 0 0")) {
            utilization.writeText(text); check(m.readControlSnapshot().utilization == null)
        }
        utilization.writeText("73 4 27"); d.powered = false; d.flags = 2; d.publish()
        check(m.readControlSnapshot().utilization == null)
    }
    run("load-only refresh leaves fingerprint stable") { d ->
        val file = File(d.root, "gpu_utilization"); file.writeText("20 0 80")
        val c = GpuControlCoordinator(GpuControlManager(d.root, d::write, {}, file))
        val before = c.snapshot(); file.writeText("90 0 10")
        val after = c.snapshot()
        check(before.guardKey == after.guardKey && before.utilization?.loadingPercent == 20 && after.utilization?.loadingPercent == 90)
    }
    run("manual and adaptive writes share one monitor and manual result remains last") { d ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val manualStarted = java.util.concurrent.CountDownLatch(1)
        val manualDone = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val m = GpuControlManager(d.root, { file, value ->
            if (value == "set 1 1 -1\n") { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            d.write(file, value)
        }, {})
        val c = GpuControlCoordinator(m); val start = c.snapshot()
        val adaptive = Thread { try { check(c.adaptiveMaximum(start, 900000).successful) } catch (error: Throwable) { errors += error } }
        adaptive.start(); check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val manual = Thread {
            manualStarted.countDown()
            try { check(c.manual { it.setFrequencyRange(500000, 700000) }.successful) }
            catch (error: Throwable) { errors += error }
            finally { manualDone.countDown() }
        }
        manual.start(); check(manualStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        check(!manualDone.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown(); adaptive.join(5000); manual.join(5000)
        check(!adaptive.isAlive && !manual.isAlive && errors.isEmpty())
        check(d.ceiling == 2 && d.floor == 3 && !c.snapshot().adaptiveOwned && c.snapshot().manualRevision == 1L)
    }
    run("CAS precondition failure never adopts an external writer matching our intended target") { d ->
        val m = d.manager()
        val c = GpuControlCoordinator(m) { expected, target ->
            d.ceiling = target.ceilingIndex; d.floor = target.floorIndex; d.publish()
            m.compareAndSetAdaptive(expected, target)
        }
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        check(!applied.successful && applied.result?.failure == GpuFailure.CONFLICT)
        check(applied.result?.controlWriteAttempted == false && !applied.snapshot.adaptiveOwned)
        check(applied.restoreToken == null && d.writes.isEmpty() && d.ceiling == 1)
        check(!c.releaseAdaptive(c.snapshot()).successful && d.writes.isEmpty())
    }
    run("failed restore CAS never adopts externally selected previous adaptive range") { d ->
        val m = d.manager(); var interfere = false
        val c = GpuControlCoordinator(m) { expected, target ->
            if (interfere) { d.ceiling = target.ceilingIndex; d.floor = target.floorIndex; d.publish() }
            m.compareAndSetAdaptive(expected, target)
        }
        val first = c.adaptiveMaximum(c.snapshot(), 900000)
        val second = c.adaptiveMaximum(first.snapshot, 700000)
        interfere = true
        val restored = c.adaptiveRestore(second.restoreToken!!)
        check(!restored.successful && !restored.snapshot.adaptiveOwned)
        check(restored.result?.controlWriteAttempted == false && d.writes.size == 2 && d.ceiling == 1)
    }
    run("unavailable proc controls suspend ownership and exact rollback can retry") { d ->
        val c = GpuControlCoordinator(d.manager())
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        val limitFile = File(d.root, "limit_table"); val contents = limitFile.readText()
        limitFile.delete()
        val missing = c.snapshot()
        check(missing.controls == null && missing.adaptiveOwned)
        val writes = d.writes.size
        check(c.adaptiveRestore(applied.restoreToken!!).reason == "missing_telemetry")
        check(c.releaseAdaptive(applied.snapshot).reason == "missing_telemetry")
        check(d.writes.size == writes)
        limitFile.writeText(contents)
        check(c.snapshot().guardKey == applied.snapshot.guardKey)
        check(c.adaptiveRestore(applied.restoreToken).successful)
        check(d.ceiling == -1 && d.floor == -1 && !c.snapshot().adaptiveOwned)
    }
    run("unavailable OPP table suspends release without forgetting its automatic baseline") { d ->
        val c = GpuControlCoordinator(d.manager())
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        File(d.root, "gpu_working_opp_table").delete()
        val missing = c.snapshot()
        check(missing.operatingPoints.isEmpty() && missing.adaptiveOwned)
        check(c.releaseAdaptive(applied.snapshot).reason == "missing_telemetry")
        check(d.writes.size == 1)
        d.table()
        check(c.snapshot().guardKey == applied.snapshot.guardKey)
        check(c.releaseAdaptive(applied.snapshot).successful && d.ceiling == -1 && d.floor == -1)
    }
    run("external controls discovered after missing reads still revoke ownership") { d ->
        val c = GpuControlCoordinator(d.manager())
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        File(d.root, "limit_table").delete(); check(c.snapshot().adaptiveOwned)
        d.ceiling = 2; d.floor = 3; d.publish()
        val external = c.snapshot(); check(!external.adaptiveOwned)
        val writes = d.writes.size
        check(!c.releaseAdaptive(applied.snapshot).successful && d.writes.size == writes)
        check(d.ceiling == 2 && d.floor == 3)
    }
    run("manual intent during unavailable reads permanently revokes the old trial") { d ->
        val c = GpuControlCoordinator(d.manager())
        val applied = c.adaptiveMaximum(c.snapshot(), 900000)
        File(d.root, "limit_table").delete(); check(c.snapshot().adaptiveOwned)
        c.manual { }
        d.publish()
        check(!c.snapshot().adaptiveOwned)
        check(c.adaptiveRestore(applied.restoreToken!!).reason == "manual_override")
        check(d.writes.size == 1 && d.ceiling == 1)
    }
    println("$count GPU range/session checks passed")
}
