package com.sktpj.recorder24h.memoket;

import android.content.Context;
import android.content.SharedPreferences;

public final class MemoketSettings {
    private static final String PREFS = "memoket_sync";

    private MemoketSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String address(Context context) {
        return prefs(context).getString("address", "");
    }

    public static void selectDevice(Context context, String address) {
        if (address == null || !address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            throw new IllegalArgumentException("Invalid Bluetooth address");
        }
        prefs(context).edit().putString("address", address).putString("source", "MEMOKET").apply();
    }

    public static String source(Context context) {
        return prefs(context).getString("source", "LOCAL");
    }

    public static void setSource(Context context, String source) {
        if (!"LOCAL".equals(source) && !"MEMOKET".equals(source)) {
            throw new IllegalArgumentException("Unknown recording source");
        }
        prefs(context).edit().putString("source", source).apply();
    }

    public static boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
    }

    public static String result(Context context) {
        return prefs(context).getString("last_result", "未取得");
    }

    public static long lastAttempt(Context context) {
        return prefs(context).getLong("last_attempt", 0);
    }

    public static void saveResult(Context context, String result) {
        prefs(context).edit()
                .putString("last_result", result)
                .putLong("last_attempt", System.currentTimeMillis())
                .apply();
    }
}
