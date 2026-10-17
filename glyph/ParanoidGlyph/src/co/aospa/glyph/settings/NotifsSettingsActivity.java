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

package co.aospa.glyph.settings;

import androidx.fragment.app.Fragment;
import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import co.aospa.glyph.utils.Constants;

public class NotifsSettingsActivity extends CollapsingToolbarBaseActivity {

    private NotifsSettingsFragment mNotifsSettingsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Constants.CONTEXT == null) {
            Constants.CONTEXT = getApplicationContext();
        }

        Fragment fragment = getSupportFragmentManager().findFragmentById(com.android.settingslib.collapsingtoolbar.R.id.content_frame);
        if (fragment == null) {
            mNotifsSettingsFragment = new NotifsSettingsFragment();
            getSupportFragmentManager().beginTransaction()
                .add(com.android.settingslib.collapsingtoolbar.R.id.content_frame, mNotifsSettingsFragment)
                .commit();
        } else {
            mNotifsSettingsFragment = (NotifsSettingsFragment) fragment;
        }
    }
}
