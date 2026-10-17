/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
 *               2020-2024 Paranoid Android
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

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.PrimarySwitchPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.Arrays;

import co.aospa.glyph.R;
import co.aospa.glyph.utils.Constants;
import co.aospa.glyph.manager.AnimationManager;
import co.aospa.glyph.manager.SettingsManager;
import co.aospa.glyph.utils.ServiceUtils;

public class SettingsFragment extends SettingsBasePreferenceFragment implements OnPreferenceChangeListener {

    private SwitchPreferenceCompat mFlipPreference;
    private PrimarySwitchPreference mNotifsPreference;
    private PrimarySwitchPreference mCallPreference;
    private SwitchPreferenceCompat mChargingLevelPreference;
    private SwitchPreferenceCompat mChargingPowersharePreference;
    private SwitchPreferenceCompat mVolumeLevelPreference;
    private SwitchPreferenceCompat mMusicVisualizerPreference;

    private ContentResolver mContentResolver;
    private SettingObserver mSettingObserver;

    private Handler mHandler = new Handler();
    private Runnable mBrightnessPreviewOff;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.glyph_settings);

        mContentResolver = getActivity().getContentResolver();
        mSettingObserver = new SettingObserver();
        mSettingObserver.register(mContentResolver);

        boolean glyphEnabled = SettingsManager.isGlyphEnabled();

        MainSwitchPreference switchBar = findPreference(Constants.GLYPH_ENABLE);
        switchBar.setOnPreferenceChangeListener(this);
        switchBar.setChecked(glyphEnabled);

        mFlipPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_FLIP_ENABLE);
        mFlipPreference.setOnPreferenceChangeListener(this);

        SeekBarPreference brightnessPreference = findPreference(Constants.GLYPH_BRIGHTNESS);
        brightnessPreference.setMin(1);
        brightnessPreference.setMax(Constants.getBrightnessLevels().length);
        brightnessPreference.setValue(SettingsManager.getGlyphBrightnessSetting());
        brightnessPreference.setUpdatesContinuously(true);
        brightnessPreference.setOnPreferenceChangeListener(this);

        mNotifsPreference = (PrimarySwitchPreference) findPreference(Constants.GLYPH_NOTIFS_ENABLE);
        mNotifsPreference.setChecked(SettingsManager.isGlyphNotifsEnabled());
        mNotifsPreference.setOnPreferenceChangeListener(this);

        mCallPreference = (PrimarySwitchPreference) findPreference(Constants.GLYPH_CALL_ENABLE);
        mCallPreference.setChecked(SettingsManager.isGlyphCallEnabled());
        mCallPreference.setOnPreferenceChangeListener(this);

        mChargingLevelPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_CHARGING_LEVEL_ENABLE);
        mChargingLevelPreference.setOnPreferenceChangeListener(this);

        mChargingPowersharePreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_CHARGING_POWERSHARE_ENABLE);
        mChargingPowersharePreference.setOnPreferenceChangeListener(this);
        if (!Constants.isPowershareSupported()) {
            mChargingPowersharePreference.setVisible(false);
        }

        mVolumeLevelPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_VOLUME_LEVEL_ENABLE);
        mVolumeLevelPreference.setOnPreferenceChangeListener(this);

        mMusicVisualizerPreference = (SwitchPreferenceCompat) findPreference(Constants.GLYPH_MUSIC_VISUALIZER_ENABLE);
        mMusicVisualizerPreference.setOnPreferenceChangeListener(this);
        if (mMusicVisualizerPreference.isChecked()) {
            mFlipPreference.setEnabled(false);
            //mBrightnessPreference.setEnabled(false);
            mNotifsPreference.setEnabled(false);
            mNotifsPreference.setSwitchEnabled(false);
            mCallPreference.setEnabled(false);
            mCallPreference.setSwitchEnabled(false);
            mChargingLevelPreference.setEnabled(false);
            mVolumeLevelPreference.setEnabled(false);
            mChargingPowersharePreference.setEnabled(false);
        }

        mHandler.post(() -> ServiceUtils.checkGlyphService());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String preferenceKey = preference.getKey();

        if (preferenceKey.equals(Constants.GLYPH_ENABLE)) {
            boolean isChecked = (Boolean) newValue;
            SettingsManager.enableGlyph(isChecked);

            mFlipPreference.setEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mNotifsPreference.setEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mNotifsPreference.setSwitchEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mCallPreference.setEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mCallPreference.setSwitchEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mChargingLevelPreference.setEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mChargingPowersharePreference.setEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
            mVolumeLevelPreference.setEnabled(isChecked && !mMusicVisualizerPreference.isChecked());
        }

        if (preferenceKey.equals(Constants.GLYPH_BRIGHTNESS)) {
            int brightness = Constants.getBrightnessLevels()[(Integer) newValue - 1];
            Constants.setBrightness(brightness);
            int patternLen = Constants.getSupportedAnimationPatternLengths()[0];
            int[] preview = new int[patternLen];
            Arrays.fill(preview, Constants.getMaxBrightness());
            new Thread(() -> AnimationManager.updateLedFrame(preview)).start();
            if (mBrightnessPreviewOff != null) mHandler.removeCallbacks(mBrightnessPreviewOff);
            mBrightnessPreviewOff = () -> new Thread(() -> AnimationManager.updateLedFrame(new int[patternLen])).start();
            mHandler.postDelayed(mBrightnessPreviewOff, 500);
        }

        if (preferenceKey.equals(Constants.GLYPH_CALL_ENABLE)) {
            SettingsManager.setGlyphCallEnabled(!mCallPreference.isChecked());
        }

        if (preferenceKey.equals(Constants.GLYPH_NOTIFS_ENABLE)) {
            SettingsManager.setGlyphNotifsEnabled(!mNotifsPreference.isChecked());
        }

        if (preferenceKey.equals(Constants.GLYPH_MUSIC_VISUALIZER_ENABLE)) {
            boolean isChecked = mMusicVisualizerPreference.isChecked();
            mFlipPreference.setEnabled(isChecked);
            //mBrightnessPreference.setEnabled(isChecked);
            mNotifsPreference.setEnabled(isChecked);
            mNotifsPreference.setSwitchEnabled(isChecked);
            mCallPreference.setEnabled(isChecked);
            mCallPreference.setSwitchEnabled(isChecked);
            mChargingLevelPreference.setEnabled(isChecked);
            mVolumeLevelPreference.setEnabled(isChecked);
            mChargingPowersharePreference.setEnabled(isChecked);
        }

        mHandler.post(() -> ServiceUtils.checkGlyphService());

        return true;
    }

    @Override
    public void onDestroyView() {
        if (mBrightnessPreviewOff != null) {
            mHandler.removeCallbacks(mBrightnessPreviewOff);
            int patternLen = Constants.getSupportedAnimationPatternLengths()[0];
            new Thread(() -> AnimationManager.updateLedFrame(new int[patternLen])).start();
            mBrightnessPreviewOff = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        mSettingObserver.unregister(mContentResolver);
        super.onDestroy();
    }

    private class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(new Handler());
        }

        public void register(ContentResolver cr) {
            cr.registerContentObserver(Settings.Secure.getUriFor(
                Constants.GLYPH_CALL_ENABLE), false, this);
            cr.registerContentObserver(Settings.Secure.getUriFor(
                Constants.GLYPH_NOTIFS_ENABLE), false, this);
        }

        public void unregister(ContentResolver cr) {
            cr.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.Secure.getUriFor(Constants.GLYPH_CALL_ENABLE))) {
                mCallPreference.setChecked(SettingsManager.isGlyphCallEnabled());
            }
            if (uri.equals(Settings.Secure.getUriFor(Constants.GLYPH_NOTIFS_ENABLE))) {
                mNotifsPreference.setChecked(SettingsManager.isGlyphNotifsEnabled());
            }
        }
    }
}
