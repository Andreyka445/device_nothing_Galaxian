/*
 * Copyright (C) 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.onlineconfig;

import android.content.Context;

import org.json.JSONArray;

// Stub of the stock Nothing online-config grabber (stock boot jar).
// NTCamera reaches it via reflection (sdk ConfigGrabber) only for
// remote-config values; an empty array means stock defaults, which is safe.
public class ConfigGrabber {
    public ConfigGrabber(Context context, String tag) {
    }

    public JSONArray grabConfig() {
        return new JSONArray();
    }
}
