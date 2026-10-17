/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.thirdparty

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

class IGlyphServiceImpl(private val context: Context) : IGlyphService.Stub() {
    private var glyphService: IGlyphService? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                glyphService = IGlyphService.Stub.asInterface(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                glyphService = null
            }
        }

    init {
        bindglyphService()
    }

    private fun bindglyphService() {
        if (context != null) {
            val intent =
                Intent("com.nothing.thirdparty.IGlyphService").apply {
                    component =
                        ComponentName("co.aospa.glyph", "co.aospa.glyph.services.ThirdPartyService")
                }
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } else {
            Log.e("IGlyphServiceImpl", "Context is null, cannot bind service")
        }
    }

    override fun setFrameColors(iArray: IntArray?) {
        Log.i("IGlyphServiceImpl", "updateLedFrame - ${iArray.contentToString()}")
        if (iArray != null) {
            glyphService?.setFrameColors(iArray)
        }
    }

    override fun setGlyphTorch(active: Boolean) {
        Log.i("IGlyphServiceImpl", "setGlyphTorch: $active")
        glyphService?.setGlyphTorch(active)
    }

    override fun openSession() {
        Log.i("IGlyphServiceImpl", "openSession")
        glyphService?.setFrameColors(intArrayOf(0, 0, 0, 0, 0))
    }

    override fun closeSession() {
        Log.i("IGlyphServiceImpl", "closeSession")
        glyphService?.setFrameColors(intArrayOf(0, 0, 0, 0, 0))
    }

    override fun register(str: String) = true

    override fun registerSDK(str1: String, str2: String) = true
}
