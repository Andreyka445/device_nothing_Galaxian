// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.galaxian.battery
import org.lineageos.settings.galaxian.ScreenProbe
fun BatteryScreen(onBack: () -> Unit) { ScreenProbe.showPage("battery", onBack) }
