package com.sktpj.recorder24h.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps concrete discussion details from being lost when a model produces an overly compressed
 * one-hour summary. Structured evidence already returned by the model is folded back into the
 * visible summary; no new facts are invented here.
 */
public final class AiHourlyDetailPreserver {
    private AiHourlyDetailPreserver() {
    }

    public static void preserve(OpenAiLunaClient.Response response) {
        if (response == null || response.analysis == null) return;
        JSONObject analysis = response.analysis;
        String summary = analysis.optString("summary", "").trim();
        List<String> details = new ArrayList<>();

        JSONArray keyEvents = analysis.optJSONArray("keyEvents");
        if (keyEvents != null) {
            for (int i = 0; i < keyEvents.length() && details.size() < 4; i++) {
                JSONObject row = keyEvents.optJSONObject(i);
                if (row == null) continue;
                addUnique(details, summary, row.optString("event", ""));
            }
        }

        JSONArray decisions = analysis.optJSONArray("decisions");
        if (decisions != null) {
            for (int i = 0; i < decisions.length() && details.size() < 6; i++) {
                addUnique(details, summary, decisions.optString(i, ""));
            }
        }

        JSONArray todos = analysis.optJSONArray("todos");
        if (todos != null) {
            for (int i = 0; i < todos.length() && details.size() < 8; i++) {
                JSONObject row = todos.optJSONObject(i);
                if (row == null) continue;
                addUnique(details, summary, row.optString("task", ""));
            }
        }

        JSONArray ideas = analysis.optJSONArray("ideas");
        if (ideas != null) {
            for (int i = 0; i < ideas.length() && details.size() < 10; i++) {
                addUnique(details, summary, ideas.optString(i, ""));
            }
        }

        if (details.isEmpty()) return;
        StringBuilder enriched = new StringBuilder(summary);
        if (enriched.length() > 0) enriched.append('\n');
        enriched.append("具体事項: ");
        for (int i = 0; i < details.size(); i++) {
            if (i > 0) enriched.append(" / ");
            enriched.append(details.get(i));
        }
        try {
            analysis.put("summary", enriched.toString());
        } catch (Exception ignored) {
        }
    }

    private static void addUnique(List<String> details, String summary, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return;
        if (summary.contains(value)) return;
        for (String existing : details) {
            if (existing.equals(value)) return;
        }
        details.add(value);
    }
}
