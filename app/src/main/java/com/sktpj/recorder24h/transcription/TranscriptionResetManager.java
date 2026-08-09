package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class TranscriptionResetManager {
    public static final String EXTRA_GENERATION = "transcriptionGeneration";

    private static final String PREFS = "transcription-reset";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_RESET_VERSION = "resetVersion";
    private static final int V049_RESET_VERSION = 1;

    private TranscriptionResetManager() {
    }

    public static int currentGeneration(Context context) {
        return prefs(context).getInt(KEY_GENERATION, 0);
    }

    public static boolean isCurrentGeneration(Context context, int generation) {
        return currentGeneration(context) == generation;
    }

    public static synchronized boolean applyV049ResetIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        if (prefs.getInt(KEY_RESET_VERSION, 0) >= V049_RESET_VERSION) {
            return false;
        }

        // Advance the generation before touching files. Any 0.4.8-or-earlier Worker still running
        // in native inference becomes stale and is blocked from writing a result back afterwards.
        int nextGeneration = prefs.getInt(KEY_GENERATION, 0) + 1;
        if (!prefs.edit().putInt(KEY_GENERATION, nextGeneration).commit()) {
            return false;
        }

        WorkManager workManager = WorkManager.getInstance(app);
        workManager.cancelAllWorkByTag("transcription");
        workManager.cancelAllWorkByTag("model-comparison");

        Set<String> knownSegmentIds = readKnownSegmentIds(app);
        int transcriptFiles = deleteContents(new File(app.getFilesDir(), "transcripts"));
        int comparisonFiles = deleteContents(new File(app.getFilesDir(), "model_comparisons"));

        Set<String> readyIds = new HashSet<>();
        Set<String> corruptIds = new HashSet<>();
        Set<String> writingIds = new HashSet<>();
        int audioCount = 0;
        int corruptCount = 0;
        long resetAt = System.currentTimeMillis();

        File[] audioFiles = StoragePolicy.getAudioDir(app).listFiles();
        if (audioFiles != null) {
            for (File audioFile : audioFiles) {
                String name = audioFile.getName();
                String segmentId = extractSegmentId(name);
                if (segmentId == null) {
                    continue;
                }
                if (name.endsWith(".m4a")) {
                    readyIds.add(segmentId);
                    SegmentRepository.appendWithoutNotify(app, segmentId, audioFile, 0L, resetAt,
                            "READY", null);
                    audioCount++;
                } else if (name.endsWith(".m4a.corrupt")) {
                    corruptIds.add(segmentId);
                    SegmentRepository.appendWithoutNotify(app, segmentId, audioFile, 0L, resetAt,
                            "CORRUPT", "CORRUPT_AUDIO_FILE");
                    corruptCount++;
                } else if (name.endsWith(".m4a.part")) {
                    // Do not rewrite the state of a segment that is currently being recorded.
                    writingIds.add(segmentId);
                }
            }
        }

        int missingAudioCount = 0;
        for (String segmentId : knownSegmentIds) {
            if (readyIds.contains(segmentId) || corruptIds.contains(segmentId)
                    || writingIds.contains(segmentId)) {
                continue;
            }
            // A transcript may previously have survived after its M4A was evicted. Once all
            // conversion output is intentionally removed, that record must not remain in a stale
            // TRANSCRIBED/TRANSCRIBING state. Mark the audio as already unavailable instead.
            SegmentRepository.appendWithoutNotify(app, segmentId, null, 0L, resetAt,
                    "DELETED", "AUDIO_NOT_RETAINED");
            missingAudioCount++;
        }

        prefs.edit().putInt(KEY_RESET_VERSION, V049_RESET_VERSION).commit();

        try {
            JSONObject details = new JSONObject();
            details.put("generation", nextGeneration);
            details.put("deletedTranscriptFiles", transcriptFiles);
            details.put("deletedComparisonFiles", comparisonFiles);
            details.put("retainedAudioFiles", audioCount);
            details.put("corruptAudioFiles", corruptCount);
            details.put("writingAudioFiles", writingIds.size());
            details.put("missingAudioSegments", missingAudioCount);
            details.put("audioDeletedByReset", false);
            AppLogger.event(app, "V049_TRANSCRIPTION_STATE_RESET", details);
        } catch (Exception ignored) {
        }
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> readKnownSegmentIds(Context context) {
        Set<String> ids = new HashSet<>();
        File journal = new File(context.getFilesDir(), "metadata/segments.jsonl");
        if (!journal.isFile()) {
            return ids;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(journal), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    JSONObject row = new JSONObject(line);
                    String segmentId = row.optString("segmentId", "");
                    if (!segmentId.isEmpty() && !"unknown".equals(segmentId)) {
                        ids.add(segmentId);
                    }
                } catch (Exception ignored) {
                    // The recorder process may be appending the final JSONL line while this runs.
                }
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private static String extractSegmentId(String fileName) {
        int suffix = fileName.indexOf(".m4a");
        if (suffix <= 0) {
            return null;
        }
        int underscore = fileName.lastIndexOf('_', suffix - 1);
        if (underscore < 0 || underscore + 1 >= suffix) {
            return null;
        }
        return fileName.substring(underscore + 1, suffix);
    }

    private static int deleteContents(File dir) {
        if (!dir.isDirectory()) {
            return 0;
        }
        int deleted = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleted += deleteContents(file);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } else if (file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }
}
