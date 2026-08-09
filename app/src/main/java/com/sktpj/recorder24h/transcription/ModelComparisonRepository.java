package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ModelComparisonRepository {
    private static final Object LOCK = new Object();
    private static final int MAX_LOG_LINES = 160;
    private static final int MAX_REPORT_CHARS = 180_000;

    private ModelComparisonRepository() {
    }

    public static File fileFor(Context context, String segmentId) {
        File dir = new File(context.getFilesDir(), "model_comparisons");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, safeSegmentId(segmentId) + ".json");
    }

    public static void save(Context context, String segmentId, JSONObject value) throws Exception {
        synchronized (LOCK) {
            File target = fileFor(context, segmentId);
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace comparison result");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("Unable to finalize comparison result");
            }
        }
    }

    public static JSONObject read(Context context, String segmentId) {
        File file = fileFor(context, segmentId);
        if (!file.isFile()) {
            return null;
        }
        try {
            return new JSONObject(readFile(file));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String readText(Context context, String segmentId) {
        JSONObject row = read(context, segmentId);
        return row == null ? null : row.toString();
    }

    public static boolean delete(Context context, String segmentId) {
        File file = fileFor(context, segmentId);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        boolean ok = !file.exists() || file.delete();
        if (temp.exists()) {
            ok = temp.delete() && ok;
        }
        return ok;
    }

    public static String buildCopyReport(Context context, String segmentId) {
        StringBuilder out = new StringBuilder();
        out.append("24hRecoder model comparison diagnostics\n");
        out.append("segmentId: ").append(segmentId).append("\n\n");
        JSONObject comparison = read(context, segmentId);
        if (comparison != null) {
            out.append("=== comparison ===\n");
            out.append(comparison.toString(2)).append("\n\n");
        } else {
            out.append("=== comparison ===\n(no comparison result)\n\n");
        }
        out.append("=== related logs ===\n");
        List<String> matching = relatedLogLines(context, segmentId);
        if (matching.isEmpty()) {
            out.append("(no matching log lines)\n");
        } else {
            for (String line : matching) {
                if (out.length() + line.length() + 1 > MAX_REPORT_CHARS) {
                    out.append("... report truncated ...\n");
                    break;
                }
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static List<String> relatedLogLines(Context context, String segmentId) {
        List<String> all = new ArrayList<>();
        File dir = new File(context.getFilesDir(), "logs");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jsonl") || name.endsWith(".jsonl.1"));
        if (files == null) {
            return all;
        }
        for (File file : files) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(segmentId)) {
                        all.add(line);
                        if (all.size() > MAX_LOG_LINES * 2) {
                            all = new ArrayList<>(all.subList(all.size() - MAX_LOG_LINES, all.size()));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (all.size() > MAX_LOG_LINES) {
            return new ArrayList<>(all.subList(all.size() - MAX_LOG_LINES, all.size()));
        }
        return all;
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder((int) Math.min(file.length(), 256 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }

    private static String safeSegmentId(String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return "unknown";
        return segmentId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
