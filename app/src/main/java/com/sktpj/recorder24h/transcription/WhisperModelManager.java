package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
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
    public static final String MODEL_BASE = "base";
    public static final String MODEL_SMALL = "small";
    public static final String MODEL_MEDIUM_Q5 = "medium-q5";
    public static final String MODEL_KOTOBA_V2_Q5 = "kotoba-v2-q5";
    public static final String MODEL_LARGE_V3_Q5 = "large-v3-q5";
    public static final String MODEL_DEFAULT = MODEL_LARGE_V3_Q5;

    // Canonical automatic transcription now prioritizes recognition quality and uses
    // multilingual Whisper large-v3 Q5. Comparison remains separate and never overwrites the
    // canonical transcript unless the normal/manual transcription path is invoked.
    public static final String MODEL_ID = "whisper.cpp-v1.9.1/large-v3-q5_0+silero-v6.2.0";
    public static final String MODEL_FILE_NAME = "ggml-large-v3-q5_0.bin";
    public static final long ASR_EXPECTED_BYTES = 1_081_140_203L;
    public static final String ASR_EXPECTED_SHA256 =
            "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1";

    public static final String VAD_MODEL_ID = "silero-v6.2.0";
    public static final String VAD_MODEL_FILE_NAME = "ggml-silero-v6.2.0.bin";
    public static final long VAD_EXPECTED_BYTES = 885_098L;
    public static final String VAD_EXPECTED_SHA256 =
            "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987";

    public static final long EXPECTED_BYTES = ASR_EXPECTED_BYTES + VAD_EXPECTED_BYTES;

    public static final String EXTRA_MODEL_ID = "comparisonModelId";

    private static final String VAD_MODEL_URL =
            "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin";
    private static final String UNIQUE_DOWNLOAD = "download-whisper-local-models";

    private static final ModelSpec[] COMPARISON_MODELS = new ModelSpec[] {
            new ModelSpec(
                    MODEL_BASE,
                    "Whisper base",
                    "多言語・標準",
                    "ggml-base.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
                    147_951_465L,
                    "SHA-256",
                    ASR_EXPECTED_SHA256,
                    false),
            new ModelSpec(
                    MODEL_SMALL,
                    "Whisper small",
                    "多言語・baseより上位",
                    "ggml-small.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
                    487_601_967L,
                    "SHA-256",
                    "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
                    false),
            new ModelSpec(
                    MODEL_MEDIUM_Q5,
                    "Whisper medium Q5",
                    "多言語・medium量子化・精度優先候補",
                    "ggml-medium-q5_0.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
                    539_212_467L,
                    "SHA-256",
                    "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
                    false),
            new ModelSpec(
                    MODEL_KOTOBA_V2_Q5,
                    "Kotoba-Whisper v2.0 Q5",
                    "日本語特化・量子化・長音声デバッグ対象",
                    "ggml-kotoba-whisper-v2.0-q5_0.bin",
                    "https://huggingface.co/kotoba-tech/kotoba-whisper-v2.0-ggml/resolve/main/ggml-kotoba-whisper-v2.0-q5_0.bin",
                    537_819_875L,
                    "SHA-256",
                    "4a3b92192b5d3578ff854a5876213e2e27af0c2d357492c2d14271e82c303658",
                    true),
            new ModelSpec(
                    MODEL_LARGE_V3_Q5,
                    "Whisper large-v3 Q5",
                    "多言語・large-v3量子化・最高精度候補",
                    "ggml-large-v3-q5_0.bin",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-q5_0.bin",
                    1_081_140_203L,
                    "SHA-256",
                    "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1",
                    false)
    };

    private WhisperModelManager() {
    }

    public static ModelSpec[] comparisonModels() {
        return COMPARISON_MODELS.clone();
    }

    public static ModelSpec modelSpec(String modelId) {
        if (modelId == null) {
            return null;
        }
        for (ModelSpec spec : COMPARISON_MODELS) {
            if (spec.id.equals(modelId)) {
                return spec;
            }
        }
        return null;
    }

    public static File modelFile(Context context) {
        return modelFile(context, MODEL_DEFAULT);
    }

    public static File modelFile(Context context, String modelId) {
        ModelSpec spec = modelSpec(modelId);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown Whisper model: " + modelId);
        }
        return new File(modelDir(context), spec.fileName);
    }

    public static File vadModelFile(Context context) {
        return new File(modelDir(context), VAD_MODEL_FILE_NAME);
    }

    public static boolean isAsrReady(Context context) {
        return isModelReady(context, MODEL_DEFAULT);
    }

    public static boolean isModelReady(Context context, String modelId) {
        ModelSpec spec = modelSpec(modelId);
        if (spec == null) {
            return false;
        }
        File file = modelFile(context, modelId);
        return file.isFile() && file.length() == spec.expectedBytes;
    }

    public static boolean isVadReady(Context context) {
        File file = vadModelFile(context);
        return file.isFile() && file.length() == VAD_EXPECTED_BYTES;
    }

    public static boolean isReady(Context context) {
        return isAsrReady(context) && isVadReady(context);
    }

    public static boolean isComparisonReady(Context context, String modelId) {
        return isModelReady(context, modelId) && isVadReady(context);
    }

    public static long downloadedBytes(Context context) {
        long bytes = downloadedBytesFor(modelFile(context));
        bytes += downloadedBytesFor(vadModelFile(context));
        return bytes;
    }

    public static long downloadedBytesForModel(Context context, String modelId) {
        ModelSpec spec = modelSpec(modelId);
        if (spec == null) {
            return 0L;
        }
        return downloadedBytesFor(modelFile(context, modelId));
    }

    private static long downloadedBytesFor(File finalFile) {
        if (finalFile.isFile()) {
            return finalFile.length();
        }
        File part = new File(finalFile.getParentFile(), finalFile.getName() + ".part");
        return part.isFile() ? part.length() : 0L;
    }

    public static void enqueueDownload(Context context) {
        enqueueModelDownload(context, MODEL_DEFAULT);
    }

    public static void enqueueModelDownload(Context context, String modelId) {
        ModelSpec spec = modelSpec(modelId);
        if (spec == null) {
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data data = new Data.Builder().putString(EXTRA_MODEL_ID, modelId).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WhisperModelDownloadWorker.class)
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("whisper-model-download")
                .addTag("whisper-model-download:" + modelId)
                .build();
        String unique = MODEL_DEFAULT.equals(modelId) ? UNIQUE_DOWNLOAD : "download-whisper-model:" + modelId;
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                unique, ExistingWorkPolicy.KEEP, request);
    }

    static File download(Context context) throws Exception {
        File asr = downloadModel(context, MODEL_DEFAULT);
        if (!isReady(context)) {
            throw new IOException("Local Whisper model set is incomplete");
        }
        return asr;
    }

    static File downloadModel(Context context, String modelId) throws Exception {
        ModelSpec spec = modelSpec(modelId);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown Whisper model: " + modelId);
        }

        File asr = modelFile(context, modelId);
        if (!isModelReady(context, modelId)) {
            downloadVerified(spec.url, asr, spec.expectedBytes, spec.digestAlgorithm, spec.expectedDigest);
        }

        File vad = vadModelFile(context);
        if (!isVadReady(context)) {
            downloadVerified(VAD_MODEL_URL, vad, VAD_EXPECTED_BYTES,
                    "SHA-256", VAD_EXPECTED_SHA256);
        }

        if (!isComparisonReady(context, modelId)) {
            throw new IOException("Local Whisper model set is incomplete: " + modelId);
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
        connection.setRequestProperty("User-Agent", "24hRecoder/0.4.8");
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
        return verifyDigest(file, "SHA-256", VAD_EXPECTED_SHA256);
    }

    public static boolean verifyModel(Context context, String modelId) {
        ModelSpec spec = modelSpec(modelId);
        if (spec == null) {
            return false;
        }
        File file = modelFile(context, modelId);
        return file.isFile() && file.length() == spec.expectedBytes
                && verifyDigest(file, spec.digestAlgorithm, spec.expectedDigest);
    }

    private static boolean verifyDigest(File file, String algorithm, String expected) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return expected.equals(hex(digest.digest()));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean deleteModel(Context context) {
        boolean ok = deleteWithPart(modelFile(context, MODEL_BASE));
        ok = deleteWithPart(modelFile(context, MODEL_SMALL)) && ok;
        ok = deleteWithPart(modelFile(context, MODEL_MEDIUM_Q5)) && ok;
        ok = deleteWithPart(modelFile(context, MODEL_KOTOBA_V2_Q5)) && ok;
        ok = deleteWithPart(modelFile(context, MODEL_LARGE_V3_Q5)) && ok;
        ok = deleteWithPart(vadModelFile(context)) && ok;
        return ok;
    }

    public static boolean deleteComparisonModel(Context context, String modelId) {
        if (MODEL_BASE.equals(modelId)) {
            return false;
        }
        ModelSpec spec = modelSpec(modelId);
        return spec != null && deleteWithPart(modelFile(context, modelId));
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

    public static final class ModelSpec {
        public final String id;
        public final String label;
        public final String description;
        public final String fileName;
        public final String url;
        public final long expectedBytes;
        public final String digestAlgorithm;
        public final String expectedDigest;
        public final boolean japaneseOptimized;

        ModelSpec(String id, String label, String description, String fileName, String url,
                  long expectedBytes, String digestAlgorithm, String expectedDigest,
                  boolean japaneseOptimized) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.fileName = fileName;
            this.url = url;
            this.expectedBytes = expectedBytes;
            this.digestAlgorithm = digestAlgorithm;
            this.expectedDigest = expectedDigest;
            this.japaneseOptimized = japaneseOptimized;
        }
    }
}
