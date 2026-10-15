/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.cpu

import java.io.File
import java.nio.file.Files

private fun fixture(): File {
    val root = Files.createTempDirectory("galaxian-cpu").toFile()
    File(root, "online").writeText("0-7")
    for (id in 0..7) {
        File(root, "cpu$id").mkdirs()
        File(root, "cpu$id/online").writeText("1")
    }
    for (id in listOf(0, 4)) {
        val policy = File(root, "cpufreq/policy$id").also { it.mkdirs() }
        File(policy, "scaling_available_frequencies").writeText("400000 800000 1200000 1600000 2000000")
        File(policy, "scaling_min_freq").writeText("400000")
        File(policy, "scaling_max_freq").writeText("2000000")
        File(policy, "scaling_cur_freq").writeText("1200000")
        File(policy, "related_cpus").writeText(if (id == 0) "0-3" else "4 5 6 7")
    }
    return root
}

private fun frequency(root: File, id: Int, node: String): Long =
    File(root, "cpufreq/policy$id/scaling_${node}_freq").readText().trim().toLong()

fun main() {
    var tests = 0
    fun run(name: String, body: (File) -> Unit) {
        val root = fixture()
        try { body(root); println("PASS $name"); tests++ } finally { root.deleteRecursively() }
    }
    run("discover two Galaxian policies and real OPP table") { root ->
        val state = CpuControlManager(root).readState()
        check(state.policies.map { it.id } == listOf(0, 4))
        check(state.policies.first().cpus == listOf(0, 1, 2, 3))
        check(state.policies.last().frequencies.size == 5)
        check(!state.cores[0].canToggle)
    }
    run("unsupported frequency never written") { root ->
        var writes = 0
        val manager = CpuControlManager(root) { _, _ -> writes++ }
        check(manager.setFrequency(0, true, 1234567).failure == CpuFailure.INVALID_FREQUENCY)
        check(writes == 0)
    }
    run("inverted bounds never written") { root ->
        val manager = CpuControlManager(root)
        check(manager.setFrequency(0, true, 1200000).successful)
        check(manager.setFrequency(0, false, 800000).failure == CpuFailure.INVALID_RANGE)
        check(frequency(root, 0, "max") == 2000000L)
    }
    run("low range restored with max written before raised min") { root ->
        File(root, "cpufreq/policy0/scaling_min_freq").writeText("1200000")
        File(root, "cpufreq/policy0/scaling_max_freq").writeText("1600000")
        val writes = mutableListOf<String>()
        val manager = CpuControlManager(root) { file, value ->
            val id = file.parentFile.name.removePrefix("policy").toInt()
            if (file.name == "scaling_min_freq") check(value.trim().toLong() <= frequency(root, id, "max"))
            if (file.name == "scaling_max_freq") check(value.trim().toLong() >= frequency(root, id, "min"))
            writes += file.name
            file.writeText(value)
        }
        manager.readState()
        check(manager.setFrequency(0, true, 400000).successful)
        check(manager.setFrequency(0, false, 800000).successful)
        writes.clear()
        check(manager.restoreInitialValues().successful)
        check(writes == listOf("scaling_max_freq", "scaling_min_freq"))
    }
    run("high range restored with min written before lowered max") { root ->
        File(root, "cpufreq/policy0/scaling_max_freq").writeText("800000")
        val writes = mutableListOf<String>()
        val manager = CpuControlManager(root) { file, value -> writes += file.name; file.writeText(value) }
        manager.readState()
        check(manager.setFrequency(0, false, 2000000).successful)
        check(manager.setFrequency(0, true, 1600000).successful)
        writes.clear()
        check(manager.restoreInitialValues().successful)
        check(writes == listOf("scaling_min_freq", "scaling_max_freq"))
    }
    run("partial restore failure rolls back already changed policy") { root ->
        var fail = false
        val manager = CpuControlManager(root) { file, value ->
            if (fail && file.parentFile.name == "policy4") {
                fail = false
                throw java.io.IOException("simulated rejected write")
            }
            file.writeText(value)
        }
        manager.readState()
        check(manager.setFrequency(0, false, 1200000).successful)
        check(manager.setFrequency(4, false, 1200000).successful)
        fail = true
        val result = manager.restoreInitialValues()
        check(result.failure == CpuFailure.WRITE_FAILED && !result.rollbackFailed)
        check(frequency(root, 0, "max") == 1200000L)
        check(frequency(root, 4, "max") == 1200000L)
    }
    run("ignored writes fail readback instead of reporting success") { root ->
        val manager = CpuControlManager(root) { _, _ -> }
        val result = manager.setFrequency(0, false, 1200000)
        check(result.failure == CpuFailure.READBACK_FAILED && !result.rollbackFailed)
    }
    run("rollback failure explicitly reported") { root ->
        val manager = CpuControlManager(root) { file, value ->
            if (value.trim() == "2000000") throw java.io.IOException("rollback denied")
            file.writeText("1600000")
        }
        val result = manager.setFrequency(0, false, 1200000)
        check(result.failure == CpuFailure.READBACK_FAILED && result.rollbackFailed)
    }
    run("cannot offline final core in A78 policy") { root ->
        val manager = CpuControlManager(root)
        for (id in 5..7) check(manager.setCoreOnline(id, false).successful)
        check(manager.setCoreOnline(4, false).failure == CpuFailure.LAST_CORE)
        check(File(root, "cpu4/online").readText().trim() == "1")
        check(!manager.readState().cores[4].canToggle)
    }
    run("cpu0 cannot be disabled") { root ->
        check(CpuControlManager(root).setCoreOnline(0, false).failure == CpuFailure.UNAVAILABLE)
    }
    run("missing OPP table leaves policy read only") { root ->
        File(root, "cpufreq/policy0/scaling_available_frequencies").delete()
        val state = CpuControlManager(root).readState()
        check(state.policies.first().frequencies.isEmpty())
        check(!state.policies.first().writable)
    }
    run("restoring baseline re-enables changed cores") { root ->
        val manager = CpuControlManager(root)
        manager.readState()
        check(manager.setCoreOnline(7, false).successful)
        check(manager.restoreInitialValues().successful)
        check(manager.readState().cores[7].online == true)
    }
    run("old kernel cannot report forced success") { root ->
        var writes = 0
        val manager = CpuControlManager(root) { _, _ -> writes++ }
        check(!manager.readState().policies.first().lockSupported)
        check(manager.setFrequencyRange(0, 800000, 1600000, force = true).failure == CpuFailure.LOCK_UNSUPPORTED)
        check(writes == 0)
    }
    run("force range is one atomic kernel write") { root ->
        val lock = File(root, "cpufreq/policy0/scaling_locked_limits")
        lock.writeText("0 400000 2000000")
        val writes = mutableListOf<String>()
        val manager = CpuControlManager(root) { file, value ->
            writes += file.name
            check(file == lock)
            file.writeText(value)
            File(file.parentFile, "scaling_min_freq").writeText("800000")
            File(file.parentFile, "scaling_max_freq").writeText("1600000")
        }
        check(manager.readState().policies.first().lockSupported)
        check(manager.setFrequencyRange(0, 800000, 1600000, force = true).successful)
        check(writes == listOf("scaling_locked_limits"))
        check(manager.readState().policies.first().locked)
    }
    run("unlock accepts current Android aggregate instead of forcing old values again") { root ->
        val lock = File(root, "cpufreq/policy0/scaling_locked_limits")
        lock.writeText("1 800000 1600000")
        File(lock.parentFile, "scaling_min_freq").writeText("800000")
        File(lock.parentFile, "scaling_max_freq").writeText("1600000")
        val writes = mutableListOf<String>()
        val manager = CpuControlManager(root) { file, value ->
            writes += file.name
            check(file == lock && value.trim() == "0")
            file.writeText("0 400000 1200000")
            File(file.parentFile, "scaling_min_freq").writeText("400000")
            File(file.parentFile, "scaling_max_freq").writeText("1200000")
        }
        check(manager.setFrequencyLock(0, false).successful)
        check(writes == listOf("scaling_locked_limits"))
        check(!manager.readState().policies.first().locked)
        check(frequency(root, 0, "max") == 1200000L)
    }
    run("restore releases preexisting kernel lock") { root ->
        val lock = File(root, "cpufreq/policy0/scaling_locked_limits")
        lock.writeText("1 400000 2000000")
        val manager = CpuControlManager(root) { file, value ->
            if (file == lock && value.trim() == "0") file.writeText("0 400000 2000000")
            else file.writeText(value)
        }
        manager.readState()
        check(manager.restoreInitialValues().successful)
        check(!manager.readState().policies.first().locked)
    }
    run("failed forced update restores preceding forced range") { root ->
        val lock = File(root, "cpufreq/policy0/scaling_locked_limits")
        lock.writeText("1 400000 2000000")
        var first = true
        val manager = CpuControlManager(root) { file, value ->
            check(file == lock)
            if (first) { first = false; throw java.io.IOException("lock rejected") }
            file.writeText(value)
        }
        val result = manager.setFrequencyRange(0, 800000, 1600000, force = true)
        check(result.failure == CpuFailure.WRITE_FAILED && !result.rollbackFailed)
        check(manager.readState().policies.first().locked)
        check(lock.readText().trim() == "1 400000 2000000")
    }
    println("$tests CPU checks passed")
}
