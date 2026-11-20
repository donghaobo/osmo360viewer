package com.osmo.viewer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 稳像的可调参数。
 *
 * 真机手感只能现场试，所以这些值都不写死在代码里：播放页长按「稳像」打开调参面板，
 * 边播边改、立即生效，退出时记住。
 */
public final class StabSettings {

    private static final String PREF = "stab";
    private static final String KEY_DELAY = "delayMs";
    private static final String KEY_GAIN = "gainPct";
    private static final String KEY_ALPHA = "alphaPct";
    private static final String KEY_SOURCE = "useDisplayPts";

    /** 姿态相对画面的滞后（毫秒）。正值表示姿态比画面慢，查表时把时间往回拨。 */
    public volatile int delayMs = 0;
    /** 补偿强度（%）：100 = 完全抵消拍摄转动，小于 100 就是「只稳一部分」。 */
    public volatile int gainPct = 100;
    /** 低通系数（%）：100 = 平滑，越小越平滑、但滞后越大。 */
    public volatile int alphaPct = 100;
    /** 时刻源：true = 屏幕上那一帧的 PTS；false = 播放器时钟（墙钟插值）。 */
    public volatile boolean useDisplayPts = true;

    public static StabSettings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        StabSettings s = new StabSettings();
        s.delayMs = p.getInt(KEY_DELAY, 0);
        s.gainPct = p.getInt(KEY_GAIN, 100);
        s.alphaPct = p.getInt(KEY_ALPHA, 100);
        s.useDisplayPts = p.getBoolean(KEY_SOURCE, true);
        return s;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DELAY, delayMs)
                .putInt(KEY_GAIN, gainPct)
                .putInt(KEY_ALPHA, alphaPct)
                .putBoolean(KEY_SOURCE, useDisplayPts)
                .apply();
    }

    public void reset() {
        delayMs = 0;
        gainPct = 100;
        alphaPct = 100;
        useDisplayPts = true;
    }

    public float gain() {
        return gainPct / 100f;
    }

    public float alpha() {
        return alphaPct / 100f;
    }

    /** 一行摘要，写进日志，方便对照真机反馈。 */
    public String summary() {
        return "延时=" + delayMs + "ms 强度=" + gainPct + "% 平滑=" + alphaPct + "%"
                + " 时刻=" + (useDisplayPts ? "显示帧" : "播放器时钟");
    }
}
