/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.adaptive

/** BatteryManager CURRENT_NOW is negative while discharging, positive while charging. */
internal fun dischargeCurrentMa(microamps: Long, charging: Boolean): Double? =
    if (!charging && microamps in -10_000_000L..-1L) -microamps.toDouble() / 1000.0 else null

/** Guest time is included in user/nice already. Long gaps never become a fresh load reading. */
internal class CpuLoadReader {
    private data class Reading(val total: Long, val idle: Long, val elapsedMs: Long)
    private var previous: Reading? = null

    @Synchronized fun reset() { previous = null }

    @Synchronized fun read(text: String, elapsedMs: Long): Double? {
        val fields = text.lineSequence().firstOrNull { it.startsWith("cpu ") }
            ?.trim()?.split(Regex("\\s+"))?.drop(1)?.take(8)?.map { it.toLongOrNull() ?: return null }
            ?: return null
        if (fields.size < 8 || fields.any { it < 0 }) return null
        val total = fields.sum()
        val idle = fields[3] + fields[4]
        val last = previous
        previous = Reading(total, idle, elapsedMs)
        if (last == null || elapsedMs - last.elapsedMs !in 1..15_000) return null
        val elapsed = total - last.total
        val idleDelta = idle - last.idle
        if (elapsed <= 0 || idleDelta < 0 || idleDelta > elapsed) return null
        return 100.0 * (elapsed - idleDelta) / elapsed
    }
}
