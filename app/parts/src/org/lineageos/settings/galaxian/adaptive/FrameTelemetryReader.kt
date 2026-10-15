/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.ByteArrayOutputStream

/** Direct, bounded Binder dump of the existing compositor history. No subprocess, reset,
 * tracing session or worker threads. Call only from the adaptive service's serial IO worker.
 */
internal class FrameTelemetryReader(context: Context) {
    private val app = context.applicationContext
    private val activity = app.getSystemService(ActivityManager::class.java)
    private val analyzer = FrameTelemetryAnalyzer()
    private var closed = false
    private var workload: String? = null

    @Synchronized fun reset() {
        closed = false
        workload = null
        analyzer.reset()
    }

    @Synchronized fun close() {
        closed = true
        workload = null
        analyzer.reset()
    }

    @Suppress("DEPRECATION")
    @Synchronized fun read(workloadPackage: String?, sampledElapsedMs: Long): FrameTelemetryResult {
        fun unavailable(reason: String) = FrameTelemetryResult(null, reason)
        if (closed || workloadPackage.isNullOrBlank()) {
            analyzer.reset(); workload = null
            return unavailable("frames_unavailable")
        }
        if (workloadPackage == app.packageName) {
            // The notification review is not a new measured workload. Preserve its identity,
            // then consume the first returning ring without attributing old frames to the trial.
            analyzer.pauseForReview()
            return unavailable("frames_unavailable")
        }
        return try {
            if (workload != workloadPackage) {
                analyzer.reset(); workload = workloadPackage
            }
            if (foreground() != workloadPackage) {
                analyzer.reset()
                return unavailable("frames_changed")
            }
            val uid = app.packageManager.getApplicationInfo(workloadPackage, 0).uid
            val pids = processIds(workloadPackage, uid)
            if (pids.isEmpty()) return unavailable("frames_unavailable")
            val dump = readDump() ?: run {
                analyzer.reset()
                return unavailable("frames_unavailable")
            }
            if (foreground() != workloadPackage || processIds(workloadPackage, uid) != pids) {
                analyzer.reset()
                return unavailable("frames_changed")
            }
            analyzer.accept(FrameTelemetryParser.parse(dump, pids),
                "$uid:${pids.sorted().joinToString(":")}", sampledElapsedMs)
        } catch (_: Exception) {
            // No app names, layer names or dump contents enter logs/history.
            analyzer.reset()
            unavailable("frames_unavailable")
        }
    }

    @Suppress("DEPRECATION")
    private fun foreground(): String? = activity.getRunningTasks(1).firstOrNull()?.topActivity?.packageName

    private fun processIds(packageName: String, uid: Int): Set<Int> =
        activity.runningAppProcesses.orEmpty().filter {
            it.uid == uid && it.pid > 0 && it.pkgList?.contains(packageName) == true
        }.map { it.pid }.toSet()

    private fun readDump(): String? {
        val binder = ServiceManager.checkService("SurfaceFlinger") ?: return null
        val deadline = SystemClock.elapsedRealtime() + READ_BUDGET_MS
        val pair = ParcelFileDescriptor.createSocketPair()
        val input = pair[0]
        val output = pair[1]
        try {
            binder.dumpAsync(output.fileDescriptor, arrayOf("--frametimeline", "-all"))
            // Only the remote duplicate remains, so its closure produces EOF.
            output.close()
            ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) return null
                    Os.setsockoptTimeval(input.fileDescriptor, OsConstants.SOL_SOCKET,
                        OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(remaining))
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (bytes.size() + count > FrameTelemetryParser.MAX_BYTES) return null
                    bytes.write(buffer, 0, count)
                }
                if (SystemClock.elapsedRealtime() > deadline) return null
                return bytes.toString(Charsets.UTF_8.name())
            }
        } finally {
            runCatching { output.close() }
            runCatching { input.close() }
        }
    }

    companion object {
        private const val READ_BUDGET_MS = 1_000L
    }
}
