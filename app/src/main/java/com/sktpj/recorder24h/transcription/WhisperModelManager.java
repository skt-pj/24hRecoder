package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class WhisperModelManager {
    public static final String MODEL_ID = "whisper.cpp/base";
    public static final String MODEL_FILE_NAME = "ggml-base.bin";
    public static final long EXPECTED_BYTES = 147_951_465L;
    public static final String EXPECTED_SHA1 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac";
    private static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";
    private static final String UNIQUE_DOWNLOAD = "download-whisper-base-model";

    private WhisperModelManager() {
    }

    public static File modelFile(Context context) {
        return new File(modelDir(context), MODEL_FILE_NAME);
    }

    public static boolean isReady(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() == EXPECTED_BYTES;
    }

    public static long downloadedBytes(Context context) {
        File finalFile = modelFile(context);
        if (finalFile.isFile()) {
            return finalFile.length();
        }
        File part = partFile(context);
        return part.isFile() ? part.length() : 0L;
    }

    public static void enqueueDownload(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WhisperModelDownloadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("whisper-model-download")
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                UNIQUE_DOWNLOAD, ExistingWorkPolicy.KEEP, request);
    }

    static File download(Context context) throws Exception {
        File target = modelFile(context);
        if (isReady(context)) {
            return target;
        }
        File part = partFile(context);
        if (part.exists() && !part.delete()) {
            throw new IOException("Unable to reset partial Whisper model");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "24hRecoder/0.3");
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("Whisper model HTTP " + code);
        }

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(part, false)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                sha1.update(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            connection.disconnect();
        }

        String actualSha1 = hex(sha1.digest());
        if (part.length() != EXPECTED_BYTES || !EXPECTED_SHA1.equals(actualSha1)) {
            long size = part.length();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Whisper model integrity check failed: bytes=" + size
                    + " sha1=" + actualSha1);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace Whisper model");
        }
        if (!part.renameTo(target)) {
            throw new IOException("Unable to finalize Whisper model");
        }
        return target;
    }

    public static boolean deleteModel(Context context) {
        File target = modelFile(context);
        File part = partFile(context);
        boolean ok = true;
        if (target.exists()) {
            ok = target.delete();
        }
        if (part.exists()) {
            ok = part.delete() && ok;
        }
        return ok;
    }

    private static File modelDir(Context context) {
        File dir = new File(context.getNoBackupFilesDir(), "whisper");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static File partFile(Context context) {
        return new File(modelDir(context), MODEL_FILE_NAME + ".part");
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
