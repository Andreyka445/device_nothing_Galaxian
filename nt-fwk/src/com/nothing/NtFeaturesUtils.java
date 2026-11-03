/*
 * Copyright (C) 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing;

import android.os.Build;
import android.os.SystemProperties;

import java.math.BigInteger;
import java.util.BitSet;

public class NtFeaturesUtils {

  // Full NTF_* table from stock nt-framework.jar (com/nothing/NtFeatures).
  // Stock has no NTF_GALAXIAN; Galaxian reuses the Galaga family bit.
  public static final int NTF_ADJUSTABLE_KEYGUARD_CLOCK = 145;
  public static final int NTF_ADVANCED_THERMAL_MITIGATION = 21;
  public static final int NTF_ALL_DAY_AOD = 95;
  public static final int NTF_AOD_WAKE_UP_ALPHA_ANIMATION = 111;
  public static final int NTF_APP_LOCKER = 19;
  public static final int NTF_APP_PROX_SCREEN_OFF_WAKE_LOCK_DEBOUNCE = 78;
  public static final int NTF_ARCSOFT_FACE_RECOGNITION = 14;
  public static final int NTF_ASTEROIDS = 80;
  public static final int NTF_ASTEROIDS_PLUS = 86;
  public static final int NTF_AUDIO_STEPLESS_VOLUME = 148;
  public static final int NTF_AUDIO_SUPER_VOLUME = 107;
  public static final int NTF_BACKGROUND_RES_LIMIT = 115;
  public static final int NTF_BACK_TAP = 49;
  public static final int NTF_BATTERY_CHARGE_CONFIG = 12;
  public static final int NTF_BATTERY_HEALTH = 28;
  public static final int NTF_BATTERY_HEALTH_2 = 68;
  public static final int NTF_BATTERY_INFORMATION = 84;
  public static final int NTF_BATTERY_SAVER_MODE = 47;
  public static final int NTF_BATTERY_WIDGET_SUPPORT = 58;
  public static final int NTF_BETA_DIALOG = 51;
  public static final int NTF_BLOCK_BENCHMARK = 27;
  public static final int NTF_BRIGHTNESS_LEVELCUST = 2;
  public static final int NTF_BT_GPT = 76;
  public static final int NTF_CAMERA_BLUETOOTHSCO_RECORD = 120;
  public static final int NTF_CHARGING_ASSISTANT = 81;
  public static final int NTF_CIRCLE_TO_SEARCH = 98;
  public static final int NTF_COMMUNITY_KEYGUARD_CLOCK = 146;
  public static final int NTF_CONN_LOCATION_TRACKER = 5;
  public static final int NTF_CONN_NFC_LIGHTS = 8;
  public static final int NTF_CONN_NFC_MUTUAL_WLC = 7;
  public static final int NTF_CONN_NFC_TRACKER = 6;
  public static final int NTF_COVER_AOD_CLOCK = 94;
  public static final int NTF_CO_CA = 82;
  public static final int NTF_DEEP_EFFECT_WALLPAPER = 151;
  public static final int NTF_DEFAULT_HIDE_VOLTE_ICON = 149;
  public static final int NTF_DISPLAY_IDLE_FPS = 52;
  public static final int NTF_DISPLAY_LTPO = 43;
  public static final int NTF_DISPLAY_SDR_HDR_COMPOSITION = 54;
  public static final int NTF_DISPLAY_UH_REFRESH = 136;
  public static final int NTF_DISPLAY_VRR = 13;
  public static final int NTF_DOUBLE_TAP_POWER = 42;
  public static final int NTF_DRAGONITE = 110;
  public static final int NTF_DUAL_APPS = 15;
  public static final int NTF_DUAL_LIGHT_SENSOR = 24;
  public static final int NTF_DYNAMIC_CLOCKFACE_TRANSITION = 152;
  public static final int NTF_DYNAMIC_FONT = 85;
  public static final int NTF_DYNAMIC_THERMAL_CONFIG = 11;
  public static final int NTF_EEA = 87;
  public static final int NTF_EK = 100;
  public static final int NTF_ENHANCED_AUDIO_FOR_AUDIOEFFECT = 90;
  public static final int NTF_ESSENTIAL_NOTIFICATION = 20;
  public static final int NTF_ESSENTIAL_VOICE = 153;
  public static final int NTF_EXTEND_BRIGHTNESS = 50;
  public static final int NTF_FLIP_TO_RECORD = 125;
  public static final int NTF_FORCE_FULLSCREEN = 36;
  public static final int NTF_FP_ARCH = 75;
  public static final int NTF_FRAME_INSERT = 140;
  public static final int NTF_GALAGA = 93;
  public static final int NTF_GAME_COLOR_PLUS = 31;
  public static final int NTF_GAME_MODE_TOUCH_SAMPLE_RATE_ENHANCE = 18;
  public static final int NTF_GAME_PUBG_THREAD_OPTIMIZATION = 126;
  public static final int NTF_GAMING_MODE = 4;
  public static final int NTF_GE = 65;
  public static final int NTF_GEB = 134;
  public static final int NTF_GEL = 129;
  public static final int NTF_GENERAL_AOD = 73;
  public static final int NTF_GE_GEN_RING_NOTI = 137;
  public static final int NTF_GLYPH_DEBUG = 45;
  public static final int NTF_GLYPH_PROGRESS = 37;
  public static final int NTF_GLYPH_TIMER = 39;
  public static final int NTF_GMS_ADAPTIVE_BRIGHTNESS = 72;
  public static final int NTF_HDR_PEAK_BRIGHTNESS = 30;
  public static final int NTF_HIDE_NAV_BAR = 70;
  public static final int NTF_IGNORE_NDDS_PAGING = 113;
  public static final int NTF_IND = 88;
  public static final int NTF_JPN = 89;
  public static final int NTF_KEYGUARD_MAGAZINE_ADS = 130;
  public static final int NTF_KEYGUARD_MAGAZINE_SUPPORT_FROM_PDT = 141;
  public static final int NTF_KEYGUARD_SPACE_AGE_CLOCKFACE_SUPPORT_TRANSITION = 154;
  public static final int NTF_LAUNCH_ANIMATION_IMPROVE_FEATURE = 142;
  public static final int NTF_LINEAR_VIBRATOR = 139;
  public static final int NTF_LOCKSCREEN_WIDGET = 34;
  public static final int NTF_LOW_LINK_LATENCY = 112;
  public static final int NTF_LP_BRIGHTNESS_STRATEGY = 158;
  public static final int NTF_ML = 105;
  public static final int NTF_MONITOR_CHARGE_SERVICE = 33;
  public static final int NTF_MTK = 60;
  public static final int NTF_MULTIPLE_USER_LOCKSCREEN_WIDGET = 35;
  public static final int NTF_NAVBAR_SWITCH = 0;
  public static final int NTF_NETWORK_FAST_DATA_RECOVERY = 83;
  public static final int NTF_NETWORK_LIMIT = 17;
  public static final int NTF_NETWORK_LIMIT_APP_NETWORK = 122;
  public static final int NTF_NETWORK_MT_CALL_DATA_KEEP = 53;
  public static final int NTF_NETWORK_SOFTAP_MANAGER = 108;
  public static final int NTF_NETWORK_SOFTAP_MANAGER_NOT_MAINLINE = 118;
  public static final int NTF_NETWORK_SOFTAP_MANAGER_V2 = 121;
  public static final int NTF_NETWORK_SOFTAP_SPEED = 103;
  public static final int NTF_NETWORK_WIFI_AP_TEMPERATURE = 29;
  public static final int NTF_NETWORK_WIFI_BREATH = 69;
  public static final int NTF_NETWORK_WIFI_DBAM = 119;
  public static final int NTF_NETWORK_WIFI_FDD = 56;
  public static final int NTF_NETWORK_WIFI_LOW_LATENCY = 123;
  public static final int NTF_NETWORK_WIFI_SAR_LEGACY = 66;
  public static final int NTF_NO_FINGERPRINT_IMPROVE = 77;
  public static final int NTF_OS_CMF = 147;
  public static final int NTF_OTA_BUTTON = 38;
  public static final int NTF_PACMAN = 63;
  public static final int NTF_PALM_TOUCH_SLEEP = 128;
  public static final int NTF_PERF_TRACE = 46;
  public static final int NTF_PJ_COB = 143;
  public static final int NTF_PJ_COP = 144;
  public static final int NTF_PJ_DOO = 156;
  public static final int NTF_PJ_FRB = 131;
  public static final int NTF_PJ_FRP = 132;
  public static final int NTF_PJ_GX = 124;
  public static final int NTF_PJ_ME = 109;
  public static final int NTF_PJ_QUA = 157;
  public static final int NTF_PMP = 71;
  public static final int NTF_PONG = 62;
  public static final int NTF_POP_UP_VIEW = 10;
  public static final int NTF_POWER_MONITOR = 155;
  public static final int NTF_POWER_OFF_VERIFY = 106;
  public static final int NTF_PRIVACY_ICON_CAMERA_BOKEH = 25;
  public static final int NTF_PRIVACY_ICON_MICPHONE = 67;
  public static final int NTF_PRIVATE_SPACE_ENTRY_FEATURE = 116;
  public static final int NTF_PROX_SCREEN_OFF_WAKE_LOCK_NOTIFICATION = 99;
  public static final int NTF_QCOM = 59;
  public static final int NTF_RISCV_VOICE_MODEL = 44;
  public static final int NTF_SCHEDULE_AOD = 96;
  public static final int NTF_SCREENRECORDER_LOWER_FPS = 40;
  public static final int NTF_SCREENSHOT_SOUND = 1;
  public static final int NTF_SCREEN_ON_OFF_ANIMATION = 26;
  public static final int NTF_SCREEN_RECORDER_BY_720P = 127;
  public static final int NTF_SENSOR_BACK_LIGHT_EXTRA_SOURCE = 32;
  public static final int NTF_SHOW_DUAL_SA_SWITCH = 16;
  public static final int NTF_SLEEP_TIGHT = 9;
  public static final int NTF_SLIDE_CURVE = 133;
  public static final int NTF_SMART_CELL_WIFI_DATA_SWITCH = 117;
  public static final int NTF_SMOOTH_ANIM_LAUNCHER_FEATURE = 57;
  public static final int NTF_SMOOTH_INTERRUPT_LAUNCHER_FEATURE = 114;
  public static final int NTF_SPACEWAR = 61;
  public static final int NTF_STATUSBAR_NETWORK_SPEED = 23;
  public static final int NTF_SUBSYSTEM_SLEEP_STATS = 97;
  public static final int NTF_SUPPORT_BACKGROUND_BLUR_LITE = 150;
  public static final int NTF_SUPPORT_ESIM_PHYSICAL_SIM_SWITCH = 92;
  public static final int NTF_SUPPORT_LOCAL_HBM = 91;
  public static final int NTF_SUPPORT_OLD_QUICKLOOK = 102;
  public static final int NTF_SYSTEM_POWER_TRACKER = 3;
  public static final int NTF_TAP_AOD = 74;
  public static final int NTF_TETRIS = 64;
  public static final int NTF_THREE_FINGER_SCREENSHOT = 48;
  public static final int NTF_TORCH_ADJUST = 138;
  public static final int NTF_TUR = 101;
  public static final int NTF_UD_ALS = 104;
  public static final int NTF_VIBRATE_DURING_CALL = 79;
  public static final int NTF_VIBRATOR_THREE_INTENSITY = 55;
  public static final int NTF_WATERMARK = 41;
  public static final int NTF_WB_VOICE_MODEL = 22;
  public static final int NTF_WIFI_RECOVERY = 135;

    private static final BitSet sFeatures;

    static {
        final String fullProp = SystemProperties.get("ro.vendor.nothing.feature.base", "0");
        final String productDiffProp = SystemProperties.get("ro.vendor.nothing.feature.diff.product." + Build.PRODUCT, "0");
        final String deviceDiffProp = SystemProperties.get("ro.vendor.nothing.feature.diff.device." + Build.DEVICE, "0");

        int bitsetSize = maxLength(replace(fullProp),replace(productDiffProp),replace(deviceDiffProp)) * 4;

        sFeatures = new BitSet(bitsetSize);

        base(new BigInteger(replace(fullProp), 16));
        change(new BigInteger(replace(productDiffProp), 16));
        change(new BigInteger(replace(deviceDiffProp), 16));
    }

    public static boolean isSupport(int... features) {
        for (int feature : features) {
            if (feature < 0 || feature >= sFeatures.length()) {
                return false;
            }
            if (!sFeatures.get(feature)) {
                return false;
            }
        }
        return true;
    }

    private static void base(BigInteger bi) {
        int index = 0;
        while (!bi.equals(BigInteger.ZERO)) {
            if (bi.testBit(0)) {
                sFeatures.set(index);
            }
            index++;
            bi = bi.shiftRight(1);
        }
    }

    private static void change(BigInteger bi) {
        int index = 0;
        while (!bi.equals(BigInteger.ZERO)) {
            if (bi.testBit(0)) {
                sFeatures.flip(index);
            }
            index++;
            bi = bi.shiftRight(1);
        }
    }

    private static String replace(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("0x", "").replace("L", "");
    }

    private static int maxLength(String... strs) {
        int max = 0;
        for (String s : strs) {
            if (s.length() > max) {
                max = s.length();
            }
        }
        return max;
    }
}
