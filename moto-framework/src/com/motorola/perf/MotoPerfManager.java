/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.motorola.perf;

import android.content.Context;
import android.os.Bundle;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Device-local compatibility implementation for proprietary Motorola applications.
 *
 * <p>roadstr does not ship Motorola's performance service, so all operations are disabled.
 */
public final class MotoPerfManager {
    public static final String APP_CLASSIFICATION = "app_classification";
    public static final String APP_PREDICTION = "app_prediction";
    public static final String EXT_RAM = "ext_ram";
    public static final String GAME_DOWNSCALE = "game_downscale";
    public static final String GAME_FPS_OBSERVER = "game_fps_observer";
    public static final String GAME_HIGH_PERF_MODE = "game_high_perf_mode";
    public static final String GAME_MODEM_LOW_LATENCY = "game_modem_low_latency";
    public static final String GAME_POWER_SAVE_MODE = "game_power_save_mode";
    public static final String GAME_WIFI_LOW_LATENCY = "game_wifi_low_latency";
    public static final String MAXE_BALANCE_MODE = "maxe_balance_mode";
    public static final String MAXE_TURBO_MODE = "maxe_turbo_mode";
    public static final String MEMORY_BOOSTER = "memory_booster";
    public static final String PERF_MODE_OBSERVER = "perf_mode_observer";
    public static final String QUICK_LAUNCH = "quick_launch";
    public static final String TGPA = "tgpa";

    public static final int HINT_FPS = 0x64;
    public static final int HINT_TOUCH = 0x65;
    public static final int HINT_APPINSTALL = 0x66;
    public static final int HINT_ROTATION_ANIM = 0x67;
    public static final int HINT_ROTATION_LATENCY = 0x68;
    public static final int HINT_FIRST_DRAW = 0x69;
    public static final int HINT_FIRST_LAUNCH = 0x6a;
    public static final int HINT_ANIM_BOOST = 0x6b;
    public static final int HINT_DRAG_BOOST = 0x6c;
    public static final int HINT_SCROLL_BOOST = 0x6d;
    public static final int HINT_KILL_BOOST = 0x6f;
    public static final int HINT_RENDERTHREAD_BOOST = 0x70;
    public static final int HINT_TAP = 0x71;
    public static final int HINT_KEY_BOOST = 0x72;
    public static final int HINT_DRAG_START = 0x73;
    public static final int HINT_DRAG_END = 0x74;
    public static final int HINT_EXTEND_APP_LAUNCH = 0x75;
    public static final int HINT_INTERACTION = 0x76;
    public static final int HINT_HIGH_PERF_REQ = 0x77;
    public static final int VENDOR_HINT_WARM_LAUNCH = 0x10a1;
    public static final int VENDOR_T_API_LEVEL = 0x21;

    public static final boolean IS_PERF_FWK_ENABLED = false;
    public static final boolean IS_SBE_ENABLED = false;
    public static final int board_api_lvl = 0;
    public static final int board_first_api_lvl = 0;

    public MotoPerfManager(Context context, IMotoPerfManagerService service) {
    }

    public static boolean isMtkPerfEnabled() {
        return false;
    }

    public static boolean isMtkSbeEnabled() {
        return false;
    }

    public static boolean isQcomPerfEnabled() {
        return false;
    }

    public List<String> getAppOptAbilities() {
        return Collections.emptyList();
    }

    public Bundle getAppOptState(String ability, String packageName) {
        return new Bundle();
    }

    public Map<String, String> getCheckinData(int type) {
        return Collections.emptyMap();
    }

    public Bundle getGameOptState(String packageName) {
        return new Bundle();
    }

    public int perfHint(int hintId, int duration) {
        return 0;
    }

    public void perfHintEnd(int hintId) {
    }

    public void perfHintStart(int hintId, int duration, Bundle state) {
    }

    public void registerGameOptObserver(String name, int intervalMs, IGameOptObserver observer) {
    }

    public void reportJankStats(int uid, int pid, int tid, int surfaceFlingerTid,
            int frameIntervalNanos, int appFrameCount, int appMissedFrameCount,
            int surfaceFlingerFrameCount, int surfaceFlingerMissedFrameCount) {
    }

    public boolean setAppOptState(String ability, String packageName, Bundle state) {
        return false;
    }

    public boolean setGameOptState(String packageName, Bundle state) {
        return false;
    }

    public void unregisterGameOptObserver(String name, IGameOptObserver observer) {
    }
}
