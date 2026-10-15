/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.roundToInt

internal data class BatterySnapshot(
    val level: Int? = null,
    val temperatureTenthsC: Int? = null,
    val voltageMv: Int? = null,
    val currentUa: Int? = null,
    val chargeUah: Int? = null,
    val status: Int? = null,
    val health: Int? = null,
)

internal fun readBatterySnapshot(context: Context): BatterySnapshot {
    // A null receiver reads the sticky system broadcast without registering a callback.
    val intent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    if (intent?.hasExtra(BatteryManager.EXTRA_PRESENT) == true &&
        !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return BatterySnapshot()
    val manager = context.getSystemService(BatteryManager::class.java)
    fun property(id: Int): Int? = runCatching { manager?.getIntProperty(id) }.getOrNull()
        ?.takeUnless { it == Int.MIN_VALUE }
    fun extra(name: String): Int? = intent?.takeIf { it.hasExtra(name) }
        ?.getIntExtra(name, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }
    val broadcastLevel = extra(BatteryManager.EXTRA_LEVEL)
    val scale = extra(BatteryManager.EXTRA_SCALE)
    val percent = property(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        ?: if (broadcastLevel != null && scale != null && scale > 0 && broadcastLevel in 0..scale) {
            (broadcastLevel * 100.0 / scale).roundToInt()
        } else null
    return BatterySnapshot(
        level = percent,
        temperatureTenthsC = extra(BatteryManager.EXTRA_TEMPERATURE),
        voltageMv = extra(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 },
        // Zero current and charge are valid readings. Unsupported properties use MIN_VALUE.
        currentUa = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
        chargeUah = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it >= 0 },
        status = extra(BatteryManager.EXTRA_STATUS),
        health = extra(BatteryManager.EXTRA_HEALTH),
    )
}
