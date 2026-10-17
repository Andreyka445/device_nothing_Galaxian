/*
 * Copyright (C) 2022-2024 Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.aospa.glyph.tiles;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import co.aospa.glyph.settings.SettingsActivity;

public class TileActivity extends Activity {

    private static final boolean DEBUG = true;
    private static final String TAG = "TileActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (DEBUG) Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        ComponentName sourceClass = getIntent().getParcelableExtra(Intent.EXTRA_COMPONENT_NAME);
        if (DEBUG) Log.d(TAG, "sourceClass: " + sourceClass.getClassName());
        if (sourceClass.getClassName().equals("co.aospa.glyph.tiles.GlyphTileService")
            || sourceClass.getClassName().equals("co.aospa.glyph.tiles.TorchTileService")) {
            openActivitySafely(new Intent(this, SettingsActivity.class));
        } else {
            finish();
        }
    }

    private void openActivitySafely(Intent dest) {
        if (DEBUG) Log.d(TAG, "openActivitySafely");
        try {
            dest.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
            finish();
            startActivity(dest);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No activity found for " + dest);
            finish();
        }
    }
}
