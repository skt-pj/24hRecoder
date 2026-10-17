package com.sktpj.recorder24h.ai;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class Gemma4ModelManager {
    public static final String MODEL_ID = "gemma-4-e2b-it-litertlm";
    public static final String MODEL_LABEL = "Gemma 4 E2B";
    public static final String MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm";
    public static final long EXPECTED_BYTES = 2_588_147_712L;
    public static final String EXPECTED_SHA256 =
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c";
    public static final String MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/" +
                    MODEL_FILE_NAME + "?download=true";

    public static final String DOWNLOAD_WORK_NAME = "gemma4-e2b-model-download";
    public static final String PROGRESS_DOWNLOADED_BYTES = "downloadedBytes";
    public static final String PROGRESS_EXPECTED_BYTES = "expectedBytes";
    public static final String PROGRESS_PHASE = "phase";
    public static final String PROGRESS_MESSAGE = "message";
    public static final String OUTPUT_ERROR = "error";
    public static final String PHASE_STARTING = "starting";
    public static final String PHASE_DOWNLOADING = "downloading";
    public static final String PHASE_VERIFYING = "verifying";
    public static final String PHASE_RETRYING = "retrying";

    private static final String VERIFIED_FILE_NAME = MODEL_FILE_NAME + ".verified";

    private Gemma4ModelManager() {
    }

    public static File modelFile(Context context) {
        return new File(modelDir(context), MODEL_FILE_NAME);
    }

    static File partialFile(Context context) {
        return new File(modelDir(context), MODEL_FILE_NAME + ".part");
    }

    private static File verifiedFile(Context context) {
        return new File(modelDir(context), VERIFIED_FILE_NAME);
    }

    public static File cacheDir(Context context) {
        File dir = new File(context.getCacheDir(), "gemma4_litertlm");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static boolean isReady(Context context) {
        File model = modelFile(context);
        File verified = verifiedFile(context);
        if (!model.isFile() || model.length() != EXPECTED_BYTES || !verified.isFile()) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(verified)) {
            byte[] bytes = new byte[(int) Math.min(128L, verified.length())];
            int read = input.read(bytes);
            if (read <= 0) {
                return false;
            }
            return EXPECTED_SHA256.equals(
                    new String(bytes, 0, read, StandardCharsets.UTF_8).trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static long downloadedBytes(Context context) {
        File model = modelFile(context);
        if (model.isFile()) {
            return model.length();
        }
        File partial = partialFile(context);
        return partial.isFile() ? partial.length() : 0L;
    }

    public static void enqueueDownload(Context context) {
        Context app = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(Gemma4ModelDownloadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("gemma4-model")
                .build();
        WorkManager.getInstance(app).enqueueUniqueWork(
                DOWNLOAD_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request);
    }

    public static void cancelDownload(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(DOWNLOAD_WORK_NAME);
    }

    public static boolean deleteModel(Context context) {
        Context app = context.getApplicationContext();
        cancelDownload(app);
        boolean ok = true;
        File[] files = new File[]{modelFile(app), partialFile(app), verifiedFile(app)};
        for (File file : files) {
            if (file.exists() && !file.delete()) {
                ok = false;
            }
        }
        deleteRecursively(cacheDir(app));
        return ok;
    }

    static void markVerified(Context context) throws Exception {
        File target = verifiedFile(context);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(EXPECTED_SHA256.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    static boolean verifySha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return EXPECTED_SHA256.equals(hex.toString());
    }

    private static File modelDir(Context context) {
        File base = context.getExternalFilesDir("ai_models");
        if (base == null) {
            base = new File(context.getNoBackupFilesDir(), "ai_models");
        }
        if (!base.exists()) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
        }
        return base;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
