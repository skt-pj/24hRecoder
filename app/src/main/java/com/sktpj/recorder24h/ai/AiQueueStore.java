package com.sktpj.recorder24h.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AiQueueStore {
    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_WAITING_DATA = "WAITING_DATA";
    public static final String STATE_RETRY_WAIT = "RETRY_WAIT";
    public static final String STATE_FAILED = "FAILED";

    public static final String REQUEST_SCHEDULED = "scheduled";
    public static final String REQUEST_MANUAL = "manual";

    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "queue.json";

    private AiQueueStore() {
    }

    public static void upsert(
            Context context,
            String id,
            String kind,
            long periodStartMs,
            long periodEndMs,
            String requestType,
            String state,
            int attempt,
            String message) {
        synchronized (LOCK) {
            List<Entry> entries = loadLocked(context);
            long now = System.currentTimeMillis();
            Entry existing = null;
            for (Entry entry : entries) {
                if (entry.id.equals(id)) {
                    existing = entry;
                    break;
                }
            }
            if (existing == null) {
                entries.add(new Entry(
                        id,
                        kind,
                        periodStartMs,
                        periodEndMs,
                        requestType,
                        state,
                        now,
                        now,
                        attempt,
                        message));
            } else {
                existing.kind = kind;
                existing.periodStartMs = periodStartMs;
                existing.periodEndMs = periodEndMs;
                existing.requestType = requestType;
                existing.state = state;
                existing.updatedAtMs = now;
                existing.attempt = attempt;
                existing.message = message == null ? "" : message;
            }
            writeLocked(context, entries);
        }
    }

    public static void remove(Context context, String id) {
        synchronized (LOCK) {
            List<Entry> entries = loadLocked(context);
            boolean changed = entries.removeIf(entry -> entry.id.equals(id));
            if (changed) {
                writeLocked(context, entries);
            }
        }
    }

    public static List<Entry> load(Context context) {
        synchronized (LOCK) {
            List<Entry> entries = loadLocked(context);
            entries.sort(Comparator
                    .comparingInt((Entry entry) -> stateOrder(entry.state))
                    .thenComparingLong(entry -> entry.periodStartMs)
                    .thenComparing(entry -> entry.id));
            return entries;
        }
    }

    private static List<Entry> loadLocked(Context context) {
        List<Entry> result = new ArrayList<>();
        File file = queueFile(context);
        if (!file.isFile()) {
            return result;
        }
        try {
            JSONObject root = new JSONObject(readUtf8(file));
            JSONArray rows = root.optJSONArray("items");
            if (rows == null) {
                return result;
            }
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                String id = row.optString("id", "").trim();
                String kind = row.optString("kind", "").trim();
                if (id.isEmpty() || kind.isEmpty()) continue;
                result.add(new Entry(
                        id,
                        kind,
                        row.optLong("periodStartMs", 0L),
                        row.optLong("periodEndMs", 0L),
                        row.optString("requestType", REQUEST_SCHEDULED),
                        row.optString("state", STATE_QUEUED),
                        row.optLong("createdAtMs", 0L),
                        row.optLong("updatedAtMs", 0L),
                        row.optInt("attempt", 0),
                        row.optString("message", "")));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static void writeLocked(Context context, List<Entry> entries) {
        try {
            File target = queueFile(context);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            JSONArray rows = new JSONArray();
            for (Entry entry : entries) {
                JSONObject row = new JSONObject();
                row.put("id", entry.id);
                row.put("kind", entry.kind);
                row.put("periodStartMs", entry.periodStartMs);
                row.put("periodEndMs", entry.periodEndMs);
                row.put("requestType", entry.requestType);
                row.put("state", entry.state);
                row.put("createdAtMs", entry.createdAtMs);
                row.put("updatedAtMs", entry.updatedAtMs);
                row.put("attempt", entry.attempt);
                row.put("message", entry.message);
                rows.put(row);
            }
            JSONObject root = new JSONObject();
            root.put("schemaVersion", 1);
            root.put("items", rows);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            File temp = new File(parent, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        } catch (Exception ignored) {
        }
    }

    private static File queueFile(Context context) {
        return new File(AiAnalysisRepository.getAnalysisDir(context), FILE_NAME);
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder((int) Math.min(file.length(), 128 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }

    private static int stateOrder(String state) {
        if (STATE_RUNNING.equals(state)) return 0;
        if (STATE_WAITING_DATA.equals(state)) return 1;
        if (STATE_RETRY_WAIT.equals(state)) return 2;
        if (STATE_QUEUED.equals(state)) return 3;
        if (STATE_FAILED.equals(state)) return 4;
        return 5;
    }

    public static final class Entry {
        public final String id;
        public String kind;
        public long periodStartMs;
        public long periodEndMs;
        public String requestType;
        public String state;
        public final long createdAtMs;
        public long updatedAtMs;
        public int attempt;
        public String message;

        Entry(
                String id,
                String kind,
                long periodStartMs,
                long periodEndMs,
                String requestType,
                String state,
                long createdAtMs,
                long updatedAtMs,
                int attempt,
                String message) {
            this.id = id;
            this.kind = kind;
            this.periodStartMs = periodStartMs;
            this.periodEndMs = periodEndMs;
            this.requestType = requestType;
            this.state = state;
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
            this.attempt = attempt;
            this.message = message == null ? "" : message;
        }
    }
}
