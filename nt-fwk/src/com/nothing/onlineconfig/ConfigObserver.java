/*
 * Copyright (C) 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.onlineconfig;

import android.content.Context;
import android.os.Handler;

import org.json.JSONArray;

// Stub of the stock Nothing online-config observer (stock boot jar).
// NTCamera reaches it via reflection (sdk ConfigObserver proxy) only for
// remote-config updates, so no-ops are safe.
public class ConfigObserver {
    public ConfigObserver(Context context, Handler handler, ConfigUpdater updater, String tag) {
    }

    public void register() {
    }

    public void unregister() {
    }

    public void onChange() {
    }

    public interface ConfigUpdater {
        void updateConfig(JSONArray config);
    }
}
