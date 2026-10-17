package com.sktpj.recorder24h.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class DriveLogTarget {
    public static final String TARGET_FOLDER_ID = "16y8Nu_rGIBwhP_MMFF8rWwz9BLOKblRP";
    public static final String TARGET_FOLDER_URL =
            "https://drive.google.com/drive/folders/" + TARGET_FOLDER_ID;
    public static final String TARGET_FOLDER_NAME = "log";

    private static final String PREFS = "drive_log_export";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LAST_PROMPT_AT_MS = "last_prompt_at_ms";
    private static final String KEY_LAST_SUCCESS_AT_MS = "last_success_at_ms";
    private static final String KEY_LAST_ERROR = "last_error";

    private DriveLogTarget() {
    }

    public static Uri getTreeUri(Context context) {
        String value = prefs(context).getString(KEY_TREE_URI, null);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean isConfigured(Context context) {
        return getTreeUri(context) != null;
    }

    public static void setTreeUri(Context context, Uri uri) {
        prefs(context).edit()
                .putString(KEY_TREE_URI, uri == null ? null : uri.toString())
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public static void clearTreeUri(Context context) {
        prefs(context).edit().remove(KEY_TREE_URI).apply();
    }

    public static long getLastPromptAtMs(Context context) {
        return prefs(context).getLong(KEY_LAST_PROMPT_AT_MS, 0L);
    }

    public static void markPrompted(Context context, long nowMs) {
        prefs(context).edit().putLong(KEY_LAST_PROMPT_AT_MS, nowMs).apply();
    }

    public static long getLastSuccessAtMs(Context context) {
        return prefs(context).getLong(KEY_LAST_SUCCESS_AT_MS, 0L);
    }

    public static String getLastError(Context context) {
        return prefs(context).getString(KEY_LAST_ERROR, null);
    }

    public static void recordSuccess(Context context, long nowMs) {
        prefs(context).edit()
                .putLong(KEY_LAST_SUCCESS_AT_MS, nowMs)
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public static void recordError(Context context, String error) {
        prefs(context).edit()
                .putString(KEY_LAST_ERROR, error == null ? "unknown" : error)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
