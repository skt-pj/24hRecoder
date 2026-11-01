package com.sktpj.recorder24h.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AiRollupRepository {
    private static final Object LOCK = new Object();

    private AiRollupRepository() {
    }

    public static RollupSource buildSource(Context context, String sourceKind,
                                           long periodStartMs, long periodEndMs) {
        List<SourceDocument> documents =
                loadDocuments(context, sourceKind, periodStartMs, periodEndMs);
        return new RollupSource(
                periodStartMs,
                periodEndMs,
                documents,
                hash(documents));
    }

    public static RollupSource buildYearSource(Context context,
                                               long periodStartMs, long periodEndMs) {
        ZoneId zone = ZoneId.systemDefault();
        List<SourceDocument> monthly =
                loadDocuments(context, AiAnalysisScheduler.KIND_MONTHLY, periodStartMs, periodEndMs);
        Set<YearMonth> coveredMonths = new LinkedHashSet<>();
        for (SourceDocument document : monthly) {
            coveredMonths.add(YearMonth.from(
                    Instant.ofEpochMilli(document.periodStartMs).atZone(zone)));
        }

        List<SourceDocument> combined = new ArrayList<>(monthly);
        List<SourceDocument> daily =
                loadDocuments(context, AiAnalysisScheduler.KIND_DAILY, periodStartMs, periodEndMs);
        for (SourceDocument document : daily) {
            YearMonth month = YearMonth.from(
                    Instant.ofEpochMilli(document.periodStartMs).atZone(zone));
            if (!coveredMonths.contains(month)) {
                combined.add(document);
            }
        }
        combined.sort(Comparator
                .comparingLong((SourceDocument document) -> document.periodStartMs)
                .thenComparing(document -> document.kind));
        return new RollupSource(
                periodStartMs,
                periodEndMs,
                combined,
                hash(combined));
    }

    public static File fileFor(Context context, String kind, long periodStartMs) {
        File dir = new File(AiAnalysisRepository.getAnalysisDir(context), kind);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        ZonedDateTime time = Instant.ofEpochMilli(periodStartMs).atZone(ZoneId.systemDefault());
        String key;
        if (AiAnalysisScheduler.KIND_WEEKLY.equals(kind)) {
            int weekYear = time.get(IsoFields.WEEK_BASED_YEAR);
            int week = time.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            key = String.format(Locale.ROOT, "%04d-W%02d", weekYear, week);
        } else if (AiAnalysisScheduler.KIND_MONTHLY.equals(kind)) {
            key = String.format(Locale.ROOT, "%04d-%02d", time.getYear(), time.getMonthValue());
        } else if (AiAnalysisScheduler.KIND_YEARLY.equals(kind)) {
            key = String.format(Locale.ROOT, "%04d", time.getYear());
        } else {
            throw new IllegalArgumentException("Unsupported rollup kind: " + kind);
        }
        return new File(dir, key + ".json");
    }

    public static boolean isCurrent(File file, String sourceHash) {
        return isCurrent(file, sourceHash, OpenAiLunaClient.MODEL);
    }

    public static boolean isCurrent(File file, String sourceHash, String modelId) {
        if (file == null || !file.isFile() || sourceHash == null || sourceHash.isEmpty()
                || modelId == null || modelId.isEmpty()) {
            return false;
        }
        try {
            JSONObject row = new JSONObject(readUtf8(file));
            return sourceHash.equals(row.optString("sourceHash", ""))
                    && modelId.equals(row.optString("model", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void save(File target, String kind, RollupSource source,
                            OpenAiLunaClient.Response response) throws Exception {
        save(target, kind, source, response, OpenAiLunaClient.MODEL);
    }

    public static void save(File target, String kind, RollupSource source,
                            OpenAiLunaClient.Response response, String modelId) throws Exception {
        synchronized (LOCK) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Unable to create rollup directory");
            }

            JSONArray sourceKinds = new JSONArray();
            LinkedHashSet<String> kinds = new LinkedHashSet<>();
            JSONArray sourcePeriods = new JSONArray();
            for (SourceDocument document : source.documents) {
                kinds.add(document.kind);
                sourcePeriods.put(new JSONObject()
                        .put("kind", document.kind)
                        .put("periodStartMs", document.periodStartMs)
                        .put("periodEndMs", document.periodEndMs));
            }
            for (String sourceKind : kinds) {
                sourceKinds.put(sourceKind);
            }

            JSONObject row = new JSONObject();
            row.put("schemaVersion", 1);
            row.put("kind", kind);
            row.put("model", modelId);
            row.put("periodStartMs", source.periodStartMs);
            row.put("periodEndMs", source.periodEndMs);
            row.put("generatedAtMs", System.currentTimeMillis());
            row.put("sourceHash", source.sourceHash);
            row.put("sourceKinds", sourceKinds);
            row.put("sourceAnalysisCount", source.documents.size());
            row.put("sourcePeriods", sourcePeriods);
            row.put("responseId", response.responseId == null ? JSONObject.NULL : response.responseId);
            row.put("usage", response.usage == null
                    ? JSONObject.NULL : new JSONObject(response.usage.toString()));
            row.put("analysis", new JSONObject(response.analysis.toString()));

            File temp = new File(parent, target.getName() + ".tmp");
            byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace rollup file");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("Unable to finalize rollup file");
            }
        }
    }

    public static String promptSource(RollupSource source) {
        StringBuilder text = new StringBuilder();
        for (SourceDocument document : source.documents) {
            text.append('[')
                    .append(document.kind)
                    .append(' ')
                    .append(document.periodStartMs)
                    .append(" - ")
                    .append(document.periodEndMs)
                    .append("]\n")
                    .append(document.analysis.toString())
                    .append("\n\n");
        }
        return text.toString();
    }

    private static List<SourceDocument> loadDocuments(Context context, String kind,
                                                      long periodStartMs, long periodEndMs) {
        File root = new File(AiAnalysisRepository.getAnalysisDir(context), kind);
        List<SourceDocument> documents = new ArrayList<>();
        if (!root.exists()) {
            return documents;
        }
        File[] files = root.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return documents;
        }

        for (File file : files) {
            try {
                JSONObject wrapper = new JSONObject(readUtf8(file));
                if (!kind.equals(wrapper.optString("kind", ""))) {
                    continue;
                }
                long start = wrapper.optLong("periodStartMs", 0L);
                long end = wrapper.optLong("periodEndMs", 0L);
                if (start < periodStartMs || start >= periodEndMs || end <= start) {
                    continue;
                }
                JSONObject analysis = wrapper.optJSONObject("analysis");
                if (analysis == null) {
                    continue;
                }
                documents.add(new SourceDocument(
                        kind,
                        start,
                        end,
                        new JSONObject(analysis.toString())));
            } catch (Exception ignored) {
            }
        }
        documents.sort(Comparator
                .comparingLong((SourceDocument document) -> document.periodStartMs)
                .thenComparing(document -> document.kind));
        return documents;
    }

    private static String hash(List<SourceDocument> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SourceDocument document : documents) {
                digest.update(document.kind.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(document.periodStartMs).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(document.periodEndMs).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(document.analysis.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash rollup source", error);
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder((int) Math.min(file.length(), 256 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) {
                    text.append(buffer, 0, read);
                }
            }
            return text.toString();
        }
    }

    public static final class RollupSource {
        public final long periodStartMs;
        public final long periodEndMs;
        public final List<SourceDocument> documents;
        public final String sourceHash;

        RollupSource(long periodStartMs, long periodEndMs,
                     List<SourceDocument> documents, String sourceHash) {
            this.periodStartMs = periodStartMs;
            this.periodEndMs = periodEndMs;
            this.documents = documents;
            this.sourceHash = sourceHash;
        }

        public boolean isEmpty() {
            return documents.isEmpty();
        }
    }

    public static final class SourceDocument {
        public final String kind;
        public final long periodStartMs;
        public final long periodEndMs;
        public final JSONObject analysis;

        SourceDocument(String kind, long periodStartMs, long periodEndMs, JSONObject analysis) {
            this.kind = kind;
            this.periodStartMs = periodStartMs;
            this.periodEndMs = periodEndMs;
            this.analysis = analysis;
        }
    }
}
