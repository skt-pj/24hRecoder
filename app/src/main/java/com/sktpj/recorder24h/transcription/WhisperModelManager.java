package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class WhisperModelManager {
    public static final String MODEL_ID = "whisper.cpp-v1.9.1/base+silero-v6.2.0";
    public static final String MODEL_FILE_NAME = "ggml-base.bin";
    public static final long ASR_EXPECTED_BYTES = 147_951_465L;
    public static final String ASR_EXPECTED_SHA1 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac";

    public static final String VAD_MODEL_ID = "silero-v6.2.0";
    public static final String VAD_MODEL_FILE_NAME = "ggml-silero-v6.2.0.bin";
    public static final long VAD_EXPECTED_BYTES = 885_098L;
    public static final String VAD_EXPECTED_SHA256 =
            "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987";

    // Kept for the existing UI progress calculation. It now represents all local models needed
    // for transcription, not just the ASR model.
    public static final long EXPECTED_BYTES = ASR_EXPECTED_BYTES + VAD_EXPECTED_BYTES;

    private static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";
    private static final String VAD_MODEL_URL =
            "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin";
    private static final String UNIQUE_DOWNLOAD = "download-whisper-local-models";

    private WhisperModelManager() {
    }

    public static File modelFile(Context context) {
        return new File(modelDir(context), MODEL_FILE_NAME);
    }

    public static File vadModelFile(Context context) {
        return new File(modelDir(context), VAD_MODEL_FILE_NAME);
    }

    public static boolean isAsrReady(Context context) {
        File file = modelFile(context);
        return file.isFile() && file.length() == ASR_EXPECTED_BYTES;
    }

    public static boolean isVadReady(Context context) {
        File file = vadModelFile(context);
        return file.isFile() && file.length() == VAD_EXPECTED_BYTES;
    }

    public static boolean isReady(Context context) {
        return isAsrReady(context) && isVadReady(context);
    }

    public static long downloadedBytes(Context context) {
        long bytes = downloadedBytesFor(modelFile(context));
        bytes += downloadedBytesFor(vadModelFile(context));
        return bytes;
    }

    private static long downloadedBytesFor(File finalFile) {
        if (finalFile.isFile()) {
            return finalFile.length();
        }
        File part = new File(finalFile.getParentFile(), finalFile.getName() + ".part");
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
        File asr = modelFile(context);
        if (!isAsrReady(context)) {
            downloadVerified(MODEL_URL, asr, ASR_EXPECTED_BYTES, "SHA-1", ASR_EXPECTED_SHA1);
        }

        File vad = vadModelFile(context);
        if (!isVadReady(context)) {
            downloadVerified(VAD_MODEL_URL, vad, VAD_EXPECTED_BYTES,
                    "SHA-256", VAD_EXPECTED_SHA256);
        }

        if (!isReady(context)) {
            throw new IOException("Local Whisper model set is incomplete");
        }
        return asr;
    }

    private static void downloadVerified(String url, File target, long expectedBytes,
                                         String digestAlgorithm, String expectedDigest) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        if (part.exists() && !part.delete()) {
            throw new IOException("Unable to reset partial model: " + part.getName());
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "24hRecoder/0.4.3");
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("Model HTTP " + code + ": " + target.getName());
        }

        MessageDigest digest = MessageDigest.getInstance(digestAlgorithm);
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(part, false)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            connection.disconnect();
        }

        String actualDigest = hex(digest.digest());
        if (part.length() != expectedBytes || !expectedDigest.equals(actualDigest)) {
            long size = part.length();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Model integrity check failed: file=" + target.getName()
                    + " bytes=" + size + " digest=" + actualDigest);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace model: " + target.getName());
        }
        if (!part.renameTo(target)) {
            throw new IOException("Unable to finalize model: " + target.getName());
        }
    }

    public static boolean verifyVadModel(Context context) {
        File file = vadModelFile(context);
        if (!file.isFile() || file.length() != VAD_EXPECTED_BYTES) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return VAD_EXPECTED_SHA256.equals(hex(digest.digest()));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean deleteModel(Context context) {
        boolean ok = deleteWithPart(modelFile(context));
        ok = deleteWithPart(vadModelFile(context)) && ok;
        return ok;
    }

    private static boolean deleteWithPart(File target) {
        File part = new File(target.getParentFile(), target.getName() + ".part");
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

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
