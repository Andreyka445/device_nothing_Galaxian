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

import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import co.aospa.glyph.R;
import co.aospa.glyph.utils.Constants;
import co.aospa.glyph.manager.SettingsManager;
import co.aospa.glyph.preference.GlyphAnimationPreference;
import co.aospa.glyph.utils.ResourceUtils;
import co.aospa.glyph.utils.ServiceUtils;

public class CallSettingsFragment extends SettingsBasePreferenceFragment implements OnPreferenceChangeListener {

    private GlyphAnimationPreference mGlyphAnimationPreference;

    private Handler mHandler = new Handler();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.glyph_call_settings);

        getActivity().setTitle(R.string.glyph_settings_call_toggle_title);

        MainSwitchPreference switchBar = findPreference(Constants.GLYPH_CALL_SUB_ENABLE);
        switchBar.setOnPreferenceChangeListener(this);
        switchBar.setChecked(SettingsManager.isGlyphCallEnabled());

        ListPreference listPreference = findPreference(Constants.GLYPH_CALL_SUB_ANIMATIONS);
        listPreference.setOnPreferenceChangeListener(this);
        listPreference.setEntries(ResourceUtils.getCallAnimations());
        listPreference.setEntryValues(ResourceUtils.getCallAnimations());
        if (!ArrayUtils.contains(ResourceUtils.getCallAnimations(), listPreference.getValue())) {
            listPreference.setValue(ResourceUtils.getString("glyph_settings_call_animations_default"));
        }

        mGlyphAnimationPreference = (GlyphAnimationPreference) findPreference(Constants.GLYPH_CALL_SUB_PREVIEW);
    }

    @Override
    public void onViewCreated (View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mGlyphAnimationPreference.updateAnimation(SettingsManager.isGlyphCallEnabled(),
                SettingsManager.getGlyphCallAnimation());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String preferenceKey = preference.getKey();

        if (preferenceKey.equals(Constants.GLYPH_CALL_SUB_ENABLE)) {
            boolean isChecked = (Boolean) newValue;
            SettingsManager.setGlyphCallEnabled(isChecked);
            ServiceUtils.checkGlyphService();
            mGlyphAnimationPreference.updateAnimation(isChecked,
                    SettingsManager.getGlyphCallAnimation());
        }

        if (preferenceKey.equals(Constants.GLYPH_CALL_SUB_ANIMATIONS)) {
            mGlyphAnimationPreference.updateAnimation(SettingsManager.isGlyphCallEnabled(),
                newValue.toString());
        }

        //mHandler.post(() -> ServiceUtils.checkGlyphService());

        return true;
    }

}
