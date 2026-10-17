/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.glyph.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import co.aospa.glyph.manager.AnimationManager
import co.aospa.glyph.manager.SettingsManager
import co.aospa.glyph.manager.StatusManager
import co.aospa.glyph.tiles.TorchTileService
import co.aospa.glyph.utils.FileUtils
import co.aospa.glyph.utils.ResourceUtils
import com.nothing.thirdparty.IGlyphService

class ThirdPartyService : Service() {

    private val binder =
        object : IGlyphService.Stub() {
            override fun setFrameColors(iArray: IntArray?) {
                Log.d("ThirdPartyService", "received data: ${iArray.contentToString()}")
                AnimationManager.updateLedFrame(iArray)
            }

            override fun setGlyphTorch(active: Boolean) {
                Log.d("ThirdPartyService", "setGlyphTorch: $active")
                val brightness = SettingsManager.getGlyphBrightness()
                StatusManager.setAllLedsActive(active)
                FileUtils.writeAllLed(if (active) brightness else 0)
                if (StatusManager.isEssentialLedActive() && !active) {
                    FileUtils.writeSingleLed(
                        ResourceUtils.getInteger("glyph_settings_notifs_essential_led"),
                        (brightness / 100 * 7).toFloat(),
                    )
                }
                sendBroadcast(
                    Intent(TorchTileService.ACTION_TORCH_STATE_CHANGED).setPackage(packageName)
                )
            }

            override fun openSession() {
                Log.d("IGlyphServiceImpl", "openSession")
            }

            override fun closeSession() {
                Log.d("IGlyphServiceImpl", "closeSession")
            }

            override fun register(str: String) = true

            override fun registerSDK(str1: String, str2: String) = true
        }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
