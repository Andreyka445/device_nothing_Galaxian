/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.galaxian.temperature

import android.os.Temperature

fun main() {
    check(TemperatureValues.millidegrees("42500") == 42.5f)
    check(TemperatureValues.millidegrees("0") == 0f)
    check(TemperatureValues.millidegrees("-12500") == -12.5f)
    for (invalid in listOf(null, "", "NaN", "Infinity", "2147483647", "-127000", "45000.5")) {
        check(TemperatureValues.millidegrees(invalid) == null)
    }
    println("PASS milli-degree conversion and invalid kernel sentinels")
    check(TemperatureValues.celsius(Float.NaN) == null)
    check(TemperatureValues.celsius(Float.POSITIVE_INFINITY) == null)
    check(TemperatureValues.celsius(-273.15f) == null)
    check(TemperatureValues.celsius(0f) == 0f)
    println("PASS invalid HAL values rejected without treating zero as missing")
    check(!TemperatureValues.isCelsiusType(Temperature.TYPE_BCL_VOLTAGE))
    check(!TemperatureValues.isCelsiusType(Temperature.TYPE_BCL_CURRENT))
    check(!TemperatureValues.isCelsiusType(Temperature.TYPE_BCL_PERCENTAGE))
    check(TemperatureValues.isCelsiusType(Temperature.TYPE_CPU))
    println("PASS voltage/current/percentage never represented as Celsius")
    check(TemperatureValues.typeForGalaxianZone("cpu-big-core3-2") == Temperature.TYPE_CPU)
    check(TemperatureValues.typeForGalaxianZone("cpu-little-core2") == Temperature.TYPE_CPU)
    check(TemperatureValues.typeForGalaxianZone("cpu-dsu-1") == Temperature.TYPE_CPU)
    check(TemperatureValues.typeForGalaxianZone("gpu") == Temperature.TYPE_GPU)
    check(TemperatureValues.typeForGalaxianZone("soc_max") == Temperature.TYPE_SOC)
    check(TemperatureValues.typeForGalaxianZone("cpu_future_guess") == Temperature.TYPE_UNKNOWN)
    println("PASS exact device mapping distinguishes physical CPU, aggregate SoC and unknown zones")
    val empty = TemperatureSnapshot()
    check(empty.cpuMaximumC == null && empty.batteryC == null)
    val readings = listOf(
        TemperatureReading("hal:cpu", "CPU", Temperature.TYPE_CPU, 85f, 3),
        TemperatureReading("sysfs:zone1", "cpu-big-core0-1", Temperature.TYPE_CPU, 55f, null),
        TemperatureReading("sysfs:zone2", "cpu-little-core0", Temperature.TYPE_CPU, 60f, null),
        TemperatureReading("hal:gpu", "GPU", Temperature.TYPE_GPU, 85f, 3),
        TemperatureReading("hal:battery", "battery", Temperature.TYPE_BATTERY, 37f, null),
        TemperatureReading("sysfs:zone3", "battery", Temperature.TYPE_BATTERY, 37.5f, null),
    )
    val snapshot = TemperatureSnapshot(readings, 3, 123L)
    check(snapshot.cpuMaximumC == 60f)
    check(snapshot.gpuMaximumC == 85f)
    check(snapshot.batteryC == 37.5f)
    println("PASS CPU uses identified physical maximum, with HAL fallback and conservative battery maximum")
    val missingPhysical = snapshot.copy(sensors = readings.map {
        if (it.id.startsWith("sysfs:") && it.type == Temperature.TYPE_CPU) it.copy(valueC = null) else it
    })
    check(missingPhysical.cpuMaximumC == 85f)
    println("PASS unreadable CPU zones fall back to actual HAL measurement")
    println("6 temperature checks passed")
}
