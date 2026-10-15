/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.memory

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import java.io.File

internal data class BackgroundApp(val packageName: String, val uid: Int, val label: String)

internal data class MemorySnapshot(
    val totalBytes: Long?,
    val availableBytes: Long?,
    val swapTotalBytes: Long?,
    val swapFreeBytes: Long?,
    val zramCapacityBytes: Long?,
    val zramDataBytes: Long?,
    val zramMemoryBytes: Long?,
    // null means enumeration is unavailable, not that no background apps are running.
    val apps: List<BackgroundApp>?,
    val canStopApps: Boolean,
)

internal data class StopRequests(
    val requested: List<BackgroundApp>,
    val skipped: Int,
    val failed: Int,
)

/** Read-only memory sampling; only explicit user actions request package-level termination. */
internal class MemoryRepository(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(ActivityManager::class.java)
    private val packages = this.context.packageManager

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canEnumerate() = granted("android.permission.REAL_GET_TASKS") &&
        granted("android.permission.QUERY_ALL_PACKAGES")

    private fun canStop() = canEnumerate() &&
        granted("android.permission.KILL_BACKGROUND_PROCESSES") &&
        granted("android.permission.KILL_ALL_BACKGROUND_PROCESSES")

    private fun processes(): List<ActivityManager.RunningAppProcessInfo>? {
        if (!canEnumerate()) return null
        return runCatching { manager?.runningAppProcesses }.getOrNull()
    }

    fun snapshot(): MemorySnapshot {
        val memory = runCatching {
            if (manager == null) null else ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }.getOrNull()
        val meminfo = readText("/proc/meminfo")?.lineSequence()?.mapNotNull { line ->
            val value = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            value?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1024 }
                ?.let { line.substringBefore(':') to it * 1024 }
        }?.toMap().orEmpty()
        val zramStats = readText("/sys/block/zram0/mm_stat")?.trim()
            ?.split(Regex("\\s+"))?.map { it.toLongOrNull()?.takeIf { value -> value >= 0 } }
        val total = memory?.totalMem?.takeIf { it > 0 }
        return MemorySnapshot(
            totalBytes = total,
            availableBytes = memory?.availMem?.takeIf { it >= 0 && total != null && it <= total },
            swapTotalBytes = meminfo["SwapTotal"],
            swapFreeBytes = meminfo["SwapFree"],
            zramCapacityBytes = readText("/sys/block/zram0/disksize")?.trim()?.toLongOrNull()
                ?.takeIf { it >= 0 },
            zramDataBytes = zramStats?.getOrNull(0),
            zramMemoryBytes = zramStats?.getOrNull(2),
            apps = processes()?.let(::eligibleApps),
            canStopApps = canStop(),
        )
    }

    private fun eligibleApps(
        running: List<ActivityManager.RunningAppProcessInfo>,
    ): List<BackgroundApp> = running.groupBy { it.uid }.mapNotNull { (uid, processes) ->
        if (!Process.isApplicationUid(uid) || uid == Process.myUid() ||
            UserHandle.getUserHandleForUid(uid) != Process.myUserHandle()) return@mapNotNull null
        // Cached apps only: keep all visible, perceptible, service and persistent work alive.
        if (processes.any {
                it.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED ||
                    it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
            }) return@mapNotNull null
        val uidPackages = runCatching { packages.getPackagesForUid(uid) }.getOrNull()
            ?: return@mapNotNull null
        // Never affect another package through a shared UID or a shared process.
        if (uidPackages.size != 1) return@mapNotNull null
        val name = uidPackages.single()
        if (name == context.packageName || processes.any {
                it.pkgList?.toSet() != setOf(name)
            }) return@mapNotNull null
        val app = runCatching { packages.getApplicationInfo(name, 0) }.getOrNull()
            ?: return@mapNotNull null
        val protectedFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or
            ApplicationInfo.FLAG_PERSISTENT
        if (app.uid != uid || app.flags and protectedFlags != 0) return@mapNotNull null
        val label = runCatching { app.loadLabel(packages).toString() }.getOrDefault(name)
        BackgroundApp(name, uid, label)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    fun requestStops(
        selected: List<BackgroundApp>,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): StopRequests {
        val requested = mutableListOf<BackgroundApp>()
        var skipped = 0
        var failed = 0
        for (app in selected.distinctBy { it.packageName }) {
            if (!shouldContinue()) break
            if (!canStop() || manager == null) {
                failed++
                continue
            }
            // Recheck each app immediately before acting; the displayed list may be stale.
            val current = processes()
            if (current == null) {
                failed++
                continue
            }
            if (eligibleApps(current).none { it.packageName == app.packageName && it.uid == app.uid }) {
                skipped++
                continue
            }
            if (!shouldContinue()) break
            try {
                manager.killBackgroundProcesses(app.packageName)
                // The API returns void: this is a request, not proof that an app stopped.
                requested += app
            } catch (_: RuntimeException) {
                failed++
            }
        }
        return StopRequests(requested, skipped, failed)
    }

    /** Observe packages rather than assuming a successful binder call stopped them. */
    fun stillObserved(requested: List<BackgroundApp>): Int? {
        val running = processes() ?: return null
        return requested.count { app ->
            running.any { it.uid == app.uid && (it.pkgList == null || app.packageName in it.pkgList) }
        }
    }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
