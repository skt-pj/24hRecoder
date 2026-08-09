package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;

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

        int nextGeneration = prefs.getInt(KEY_GENERATION, 0) + 1;
        if (!prefs.edit().putInt(KEY_GENERATION, nextGeneration).commit()) {
            return false;
        }

        WorkManager workManager = WorkManager.getInstance(app);
        workManager.cancelAllWorkByTag("transcription");
        workManager.cancelAllWorkByTag("model-comparison");

        int transcriptFiles = deleteContents(new File(app.getFilesDir(), "transcripts"));
        int comparisonFiles = deleteContents(new File(app.getFilesDir(), "model_comparisons"));

        int audioCount = 0;
        File[] audioFiles = StoragePolicy.getAudioDir(app)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (audioFiles != null) {
            long resetAt = System.currentTimeMillis();
            for (File audioFile : audioFiles) {
                String segmentId = TranscriptionScheduler.extractSegmentId(audioFile.getName());
                if (segmentId == null) {
                    continue;
                }
                SegmentRepository.appendWithoutNotify(app, segmentId, audioFile, 0L, resetAt,
                        "READY", null);
                audioCount++;
            }
        }

        prefs.edit().putInt(KEY_RESET_VERSION, V049_RESET_VERSION).commit();

        try {
            JSONObject details = new JSONObject();
            details.put("generation", nextGeneration);
            details.put("deletedTranscriptFiles", transcriptFiles);
            details.put("deletedComparisonFiles", comparisonFiles);
            details.put("retainedAudioFiles", audioCount);
            details.put("audioDeleted", false);
            AppLogger.event(app, "V049_TRANSCRIPTION_STATE_RESET", details);
        } catch (Exception ignored) {
        }
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
                file.delete();
            } else if (file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }
}
