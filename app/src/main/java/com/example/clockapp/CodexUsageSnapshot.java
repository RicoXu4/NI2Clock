package com.example.clockapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CodexUsageSnapshot {
    final long observedAt;
    final boolean refreshFailed;
    final List<Window> windows;

    private CodexUsageSnapshot(long observedAt, boolean refreshFailed, List<Window> windows) {
        this.observedAt = observedAt;
        this.refreshFailed = refreshFailed;
        this.windows = Collections.unmodifiableList(windows);
    }

    static CodexUsageSnapshot parse(JSONObject json) throws JSONException {
        if (json.optInt("schemaVersion") != 1 || json.optLong("observedAt") <= 0) {
            throw new JSONException("Invalid usage snapshot");
        }
        List<Window> windows = new ArrayList<>();
        JSONArray buckets = json.getJSONArray("buckets");
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject bucket = buckets.getJSONObject(i);
            String name = bucket.optString("name", "Codex");
            if ("codex".equals(name)) name = "Codex";
            JSONArray entries = bucket.getJSONArray("windows");
            for (int j = 0; j < entries.length(); j++) {
                JSONObject window = entries.getJSONObject(j);
                Object raw = window.opt("usedPercent");
                Double used = raw instanceof Number ? ((Number) raw).doubleValue() : null;
                if (used != null) {
                    used = Double.isNaN(used) || Double.isInfinite(used) ? null : Math.max(0, Math.min(100, used));
                }
                int duration = window.optInt("durationMinutes", 0);
                long resetsAt = window.optLong("resetsAt", 0);
                windows.add(new Window(name, used, Math.max(0, duration), Math.max(0, resetsAt)));
            }
        }
        return new CodexUsageSnapshot(json.getLong("observedAt"), json.optBoolean("refreshFailed"), windows);
    }

    static final class Window {
        final String name;
        final Double usedPercent;
        final int durationMinutes;
        final long resetsAt;

        private Window(String name, Double usedPercent, int durationMinutes, long resetsAt) {
            this.name = name;
            this.usedPercent = usedPercent;
            this.durationMinutes = durationMinutes;
            this.resetsAt = resetsAt;
        }
    }
}
