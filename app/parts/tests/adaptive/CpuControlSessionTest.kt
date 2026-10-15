/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.cpu

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun sessionFixture(): File {
    val root = Files.createTempDirectory("galaxian-coordinator-").toFile()
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
        File(policy, "related_cpus").writeText(if (id == 0) "0-3" else "4-7")
    }
    return root
}

private fun sessionNode(root: File, name: String, id: Int = 0): File =
    File(root, "cpufreq/policy$id/$name")

private fun CountDownLatch.awaitSession(label: String) {
    check(await(5, TimeUnit.SECONDS)) { "Timed out waiting for $label" }
}

/** Wait for monitor contention itself, not a guessed delay in which another thread might run. */
private fun awaitSessionBlocked(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (thread.state != Thread.State.BLOCKED) {
        check(thread.isAlive) { "Adaptive call returned before the manual transaction released its monitor" }
        check(System.nanoTime() < deadline) { "Adaptive thread did not contend for the coordinator monitor" }
        Thread.yield()
    }
}

fun runCpuSessionTests(): Int {
    var tests = 0
    fun run(name: String, body: (File) -> Unit) {
        val root = sessionFixture()
        try {
            body(root)
            println("PASS coordinator: $name")
            tests++
        } finally {
            check(root.deleteRecursively()) { "Cannot remove coordinator fixture" }
        }
    }

    run("manual intent invalidates ownership for no-op, failure and exception") { root ->
        var writes = 0
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { _, _ -> writes++ }
        check(coordinator.snapshot().manualRevision == 0L)
        coordinator.manual { Unit }
        check(coordinator.snapshot().manualRevision == 1L)
        val failed = coordinator.manual { CpuResult(CpuFailure.WRITE_FAILED) }
        check(!failed.successful && coordinator.snapshot().manualRevision == 2L)
        val marker = IllegalStateException("manual operation rejected")
        check(runCatching { coordinator.manual<Unit> { throw marker } }.exceptionOrNull() === marker)
        check(coordinator.snapshot().manualRevision == 3L)
        val stale = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(!stale.successful && stale.reason == "manual_override")
        check(writes == 0)
    }

    run("changed minimum rejects stale adaptive compare-and-set") { root ->
        var writes = 0
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { _, _ -> writes++ }
        sessionNode(root, "scaling_min_freq").writeText("800000")
        val result = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(!result.successful && result.reason == "external_override")
        check(writes == 0 && sessionNode(root, "scaling_min_freq").readText() == "800000")
    }

    run("changed maximum rejects stale adaptive compare-and-set") { root ->
        var writes = 0
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { _, _ -> writes++ }
        sessionNode(root, "scaling_max_freq").writeText("1200000")
        val result = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(!result.successful && result.reason == "external_override" && result.maximum == 1200000L)
        check(writes == 0 && sessionNode(root, "scaling_max_freq").readText() == "1200000")
    }

    run("manual kernel lock prevents all adaptive writes") { root ->
        var writes = 0
        sessionNode(root, "scaling_locked_limits").writeText("1 400000 2000000")
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { _, _ -> writes++ }
        val result = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(!result.successful && result.reason == "manual_lock")
        check(writes == 0 && sessionNode(root, "scaling_locked_limits").readText() == "1 400000 2000000")
    }

    run("valid proposal writes only the maximum once and verifies it") { root ->
        val before = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readText() }
        val writes = mutableListOf<Pair<Int, Long>>()
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { id, value ->
            writes += id to value
            sessionNode(root, "scaling_max_freq", id).writeText(value.toString())
        }
        val result = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(result.successful && result.maximum == 1600000L)
        check(writes == listOf(0 to 1600000L))
        for ((path, contents) in before) {
            if (path != "cpufreq/policy0/scaling_max_freq") check(File(root, path).readText() == contents)
        }
        check(coordinator.snapshot().manualRevision == 0L)
    }

    run("readback mismatch neither retries nor rolls back another value") { root ->
        var writes = 0
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { id, _ ->
            writes++
            // Emulate a competing power controller replacing the requested maximum.
            sessionNode(root, "scaling_max_freq", id).writeText("1200000")
        }
        val result = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(!result.successful && result.reason == "readback_failed" && result.maximum == 1200000L)
        check(writes == 1 && sessionNode(root, "scaling_max_freq").readText() == "1200000")
    }

    run("write exception reports failure without a second write") { root ->
        var writes = 0
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { _, _ ->
            writes++
            throw IOException("simulated sysfs denial")
        }
        val result = coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0)
        check(!result.successful && result.reason == "write_failed")
        check(writes == 1 && sessionNode(root, "scaling_max_freq").readText() == "2000000")
    }

    run("manual transaction serializes adaptive access and wins stale ownership") { root ->
        val manualEntered = CountDownLatch(1)
        val releaseManual = CountDownLatch(1)
        val adaptiveAttempting = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val adaptiveResult = AtomicReference<AdaptiveCpuWrite?>()
        var adaptiveWrites = 0
        val coordinator = CpuControlCoordinator(CpuControlManager(root)) { id, value ->
            adaptiveWrites++
            sessionNode(root, "scaling_max_freq", id).writeText(value.toString())
        }
        val manualThread = Thread {
            try {
                coordinator.manual { manager ->
                    manualEntered.countDown()
                    releaseManual.awaitSession("release of manual transaction")
                    check(manager.setFrequency(0, false, 1200000).successful)
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { isDaemon = true }
        val adaptiveThread = Thread {
            try {
                adaptiveAttempting.countDown()
                adaptiveResult.set(coordinator.adaptiveMaximum(0, 400000, 2000000, 1600000, 0))
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { isDaemon = true }
        try {
            manualThread.start()
            manualEntered.awaitSession("manual monitor acquisition")
            adaptiveThread.start()
            adaptiveAttempting.awaitSession("adaptive attempt")
            awaitSessionBlocked(adaptiveThread)
            check(adaptiveResult.get() == null)
        } finally {
            releaseManual.countDown()
            manualThread.join(5_000)
            adaptiveThread.join(5_000)
        }
        check(!manualThread.isAlive && !adaptiveThread.isAlive) { "Coordinator threads did not finish" }
        failure.get()?.let { throw it }
        val result = checkNotNull(adaptiveResult.get())
        check(!result.successful && result.reason == "manual_override")
        check(adaptiveWrites == 0 && coordinator.snapshot().manualRevision == 1L)
        check(sessionNode(root, "scaling_max_freq").readText().trim() == "1200000")
    }
    return tests
}
