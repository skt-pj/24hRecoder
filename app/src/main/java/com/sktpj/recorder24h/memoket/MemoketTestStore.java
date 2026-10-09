package com.sktpj.recorder24h.memoket;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MemoketTestStore {
    private static final String PREFS = "memoket_test_history";
    private static final String KEY_HISTORY = "history";

    private MemoketTestStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized JSONArray history(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY_HISTORY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    public static synchronized void save(Context context, JSONObject result) {
        if (result == null) return;
        JSONArray old = history(context);
        JSONArray next = new JSONArray();
        next.put(new JSONObject(result.toString()));
        for (int i = 0; i < old.length() && i < 99; i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null) next.put(row);
        }
        prefs(context).edit().putString(KEY_HISTORY, next.toString()).apply();
    }

    public static synchronized void updateVibration(Context context, String id, int vibration) {
        JSONArray rows = history(context);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) {
                try { row.put("vibration", vibration); } catch (Exception ignored) { }
                break;
            }
        }
        prefs(context).edit().putString(KEY_HISTORY, rows.toString()).apply();
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().remove(KEY_HISTORY).apply();
    }
}
