package com.sktpj.recorder24h.storage;

import android.content.Context;

import com.sktpj.recorder24h.transcription.TranscriptionRepository;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StoragePolicy {
    public static final long AUDIO_LIMIT_BYTES = 600L * 1024L * 1024L;
    public static final long LOGICAL_APP_LIMIT_BYTES = 1024L * 1024L * 1024L;
    public static final long EMERGENCY_RESERVE_BYTES = 150L * 1024L * 1024L;
    private static final long AUDIO_CLEANUP_TARGET_BYTES = 570L * 1024L * 1024L;
    private static final long DEVICE_FREE_TARGET_BYTES = 250L * 1024L * 1024L;

    private StoragePolicy() {
    }

    public static File getAudioDir(Context context) {
        File dir = new File(context.getFilesDir(), "audio");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static long audioBytes(Context context) {
        return finalizedAudioFiles(context).stream().mapToLong(File::length).sum();
    }

    public static long appDataBytes(Context context) {
        return directoryBytes(context.getFilesDir());
    }

    public static void enforce(Context context) {
        List<File> files = finalizedAudioFiles(context);
        long audioBytes = files.stream().mapToLong(File::length).sum();
        long appBytes = appDataBytes(context);
        long usable = context.getFilesDir().getUsableSpace();

        boolean cleanupNeeded = audioBytes > AUDIO_LIMIT_BYTES
                || appBytes > (LOGICAL_APP_LIMIT_BYTES - EMERGENCY_RESERVE_BYTES)
                || usable < EMERGENCY_RESERVE_BYTES;

        if (!cleanupNeeded) {
            return;
        }

        // Preserve recent recordings for playback. Under pressure, delete the oldest audio
        // that already has a durable transcript before considering untranscribed audio.
        files.sort(Comparator
                .comparingInt((File file) -> isTranscribed(context, file) ? 0 : 1)
                .thenComparingLong(File::lastModified));

        for (File file : files) {
            if (audioBytes <= AUDIO_CLEANUP_TARGET_BYTES
                    && appBytes <= (LOGICAL_APP_LIMIT_BYTES - EMERGENCY_RESERVE_BYTES)
                    && usable >= DEVICE_FREE_TARGET_BYTES) {
                break;
            }

            String segmentId = extractSegmentId(file.getName());
            boolean transcribed = TranscriptionRepository.exists(context, segmentId);
            long size = file.length();
            long modified = file.lastModified();
            if (file.delete()) {
                audioBytes -= size;
                appBytes -= size;
                usable = context.getFilesDir().getUsableSpace();
                if (transcribed) {
                    SegmentRepository.append(context, segmentId, file, modified,
                            System.currentTimeMillis(), "DELETED",
                            "STORAGE_PRESSURE_TRANSCRIBED_RETENTION");
                    logTranscribedEviction(context, file, segmentId, size,
                            audioBytes, appBytes, usable);
                } else {
                    logDataLoss(context, file, segmentId, size, audioBytes, appBytes, usable);
                }
            }
        }
    }

    public static void recoverOrphanParts(Context context) {
        File dir = getAudioDir(context);
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".m4a.part"));
        if (parts == null) {
            return;
        }
        for (File part : parts) {
            String originalName = part.getName();
            String segmentId = extractSegmentId(originalName);
            File corrupt = new File(dir, originalName.replace(".m4a.part", ".m4a.corrupt"));
            boolean renamed = part.renameTo(corrupt);
            File recorded = renamed ? corrupt : part;
            SegmentRepository.append(context, segmentId, recorded, recorded.lastModified(),
                    System.currentTimeMillis(), "CORRUPT", "PROCESS_TERMINATED_DURING_WRITE");
            try {
                JSONObject d = new JSONObject();
                d.put("file", recorded.getName());
                d.put("segmentId", segmentId);
                AppLogger.event(context, "ORPHAN_SEGMENT_RECOVERED", d);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isTranscribed(Context context, File file) {
        return TranscriptionRepository.exists(context, extractSegmentId(file.getName()));
    }

    private static List<File> finalizedAudioFiles(Context context) {
        File[] files = getAudioDir(context).listFiles((d, name) -> name.endsWith(".m4a"));
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                result.add(file);
            }
        }
        return result;
    }

    private static long directoryBytes(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directoryBytes(child);
            }
        }
        return total;
    }

    private static String extractSegmentId(String fileName) {
        int lastUnderscore = fileName.lastIndexOf('_');
        int suffix = fileName.indexOf(".m4a", Math.max(0, lastUnderscore));
        if (lastUnderscore >= 0 && suffix > lastUnderscore) {
            return fileName.substring(lastUnderscore + 1, suffix);
        }
        return "unknown";
    }

    private static void logTranscribedEviction(Context context, File file, String segmentId,
                                               long size, long audioBytes, long appBytes,
                                               long usable) {
        try {
            JSONObject d = new JSONObject();
            d.put("file", file.getName());
            d.put("segmentId", segmentId);
            d.put("deletedBytes", size);
            d.put("audioBytesAfter", audioBytes);
            d.put("appBytesAfter", appBytes);
            d.put("usableBytesAfter", usable);
            d.put("reason", "STORAGE_PRESSURE_TRANSCRIBED_RETENTION");
            AppLogger.event(context, "TRANSCRIBED_AUDIO_EVICTED", d);
        } catch (Exception ignored) {
        }
    }

    private static void logDataLoss(Context context, File file, String segmentId, long size,
                                    long audioBytes, long appBytes, long usable) {
        try {
            JSONObject d = new JSONObject();
            d.put("file", file.getName());
            d.put("segmentId", segmentId);
            d.put("deletedBytes", size);
            d.put("audioBytesAfter", audioBytes);
            d.put("appBytesAfter", appBytes);
            d.put("usableBytesAfter", usable);
            d.put("reason", "STORAGE_PRESSURE_ROLLING_DELETE");
            AppLogger.event(context, "DATA_LOSS_AUDIO_DELETED", d);
        } catch (Exception ignored) {
        }
    }
}
