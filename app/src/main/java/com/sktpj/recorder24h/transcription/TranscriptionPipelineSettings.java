package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Explicit transcription pipeline selection shared by main, :recorder and :streaming_asr.
 *
 * SharedPreferences alone is not used as the live source because Android does not provide a
 * reliable multi-process cache-coherency contract for it. 0.7.15 preferences are migrated once
 * into an atomic JSON file, then every process reads the file at the point it snapshots a segment.
 *
 * There is deliberately no automatic fallback between backends. A selected backend either runs
 * or reports an unavailable/failure state. The legacy post-segment CPU path remains selectable.
 */
public final class TranscriptionPipelineSettings {
    public static final String MODE_SEGMENT_POSTPROCESS = "segment-postprocess";
    public static final String MODE_LIVE_STREAMING = "live-streaming";

    public static final String ASR_WHISPER_CPU = "whisper-cpu";
    public static final String ASR_WHISPER_VULKAN = "whisper-vulkan";
    public static final String ASR_ANDROID_ON_DEVICE = "android-on-device";

    public static final String VAD_CANDIDATE_SILERO = "candidate-silero";
    public static final String VAD_STREAMING_SILERO = "streaming-silero";

    public static final String DENOISE_OFF = "off";
    public static final String DENOISE_DEEPFILTER = "deepfilternet-adaptive";

    public static final String SPEAKER_SHERPA_CPU = "sherpa-cpu";
    public static final String SPEAKER_OFF = "off";

    private static final Object LOCK = new Object();
    private static final String SETTINGS_FILE = "transcription_pipeline_settings.json";
    private static final String PREFS = "transcription_pipeline_settings";
    private static final String KEY_MODE = "execution_mode";
    private static final String KEY_ASR = "asr_backend";
    private static final String KEY_VAD = "vad_backend";
    private static final String KEY_DENOISE = "denoise_backend";
    private static final String KEY_SPEAKER = "speaker_backend";

    private TranscriptionPipelineSettings() {
    }

    public static Snapshot snapshot(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            File file = settingsFile(app);
            if (!file.isFile()) {
                Snapshot migrated = migrateLegacyPreferences(app);
                writeSnapshotLocked(app, migrated);
                return migrated;
            }
            try {
                JSONObject row = new JSONObject(new String(
                        Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                return snapshotFromJson(row);
            } catch (Exception ignored) {
                Snapshot recovered = migrateLegacyPreferences(app);
                writeSnapshotLocked(app, recovered);
                return recovered;
            }
        }
    }

    public static void setExecutionMode(Context context, String value) {
        update(context, normalizeMode(value), null, null, null, null);
    }

    public static void setAsr(Context context, String value) {
        update(context, null, normalizeAsr(value), null, null, null);
    }

    public static void setVad(Context context, String value) {
        update(context, null, null, normalizeVad(value), null, null);
    }

    public static void setDenoise(Context context, String value) {
        update(context, null, null, null, normalizeDenoise(value), null);
    }

    public static void setSpeaker(Context context, String value) {
        update(context, null, null, null, null, normalizeSpeaker(value));
    }

    private static void update(Context context, String mode, String asr, String vad,
                               String denoise, String speaker) {
        Context app = context.getApplicationContext();
        Snapshot next;
        boolean modeChanged;
        synchronized (LOCK) {
            Snapshot current = snapshot(app);
            next = new Snapshot(
                    mode == null ? current.executionMode : mode,
                    asr == null ? current.asrBackend : asr,
                    vad == null ? current.vadBackend : vad,
                    denoise == null ? current.denoiseBackend : denoise,
                    speaker == null ? current.speakerBackend : speaker);
            modeChanged = !current.executionMode.equals(next.executionMode);
            writeSnapshotLocked(app, next);
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_MODE, next.executionMode)
                    .putString(KEY_ASR, next.asrBackend)
                    .putString(KEY_VAD, next.vadBackend)
                    .putString(KEY_DENOISE, next.denoiseBackend)
                    .putString(KEY_SPEAKER, next.speakerBackend)
                    .apply();
        }
        // Do this after releasing LOCK because the scheduler snapshots the same settings file.
        if (modeChanged) {
            TranscriptionScheduler.onExecutionModeChanged(app);
        }
    }

    private static Snapshot migrateLegacyPreferences(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Snapshot(
                normalizeMode(prefs.getString(KEY_MODE, MODE_SEGMENT_POSTPROCESS)),
                normalizeAsr(prefs.getString(KEY_ASR, ASR_WHISPER_CPU)),
                normalizeVad(prefs.getString(KEY_VAD, VAD_CANDIDATE_SILERO)),
                normalizeDenoise(prefs.getString(KEY_DENOISE, DENOISE_DEEPFILTER)),
                normalizeSpeaker(prefs.getString(KEY_SPEAKER, SPEAKER_SHERPA_CPU)));
    }

    private static Snapshot snapshotFromJson(JSONObject row) {
        return new Snapshot(
                normalizeMode(row.optString("executionMode", MODE_SEGMENT_POSTPROCESS)),
                normalizeAsr(row.optString("asrBackend", ASR_WHISPER_CPU)),
                normalizeVad(row.optString("vadBackend", VAD_CANDIDATE_SILERO)),
                normalizeDenoise(row.optString("denoiseBackend", DENOISE_DEEPFILTER)),
                normalizeSpeaker(row.optString("speakerBackend", SPEAKER_SHERPA_CPU)));
    }

    private static void writeSnapshotLocked(Context app, Snapshot snapshot) {
        JSONObject row = snapshot.toJson();
        try {
            row.put("schemaVersion", 1);
            row.put("updatedAtMs", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        File target = settingsFile(app);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName()
                + ".tmp." + Process.myPid() + "." + Thread.currentThread().getId());
        try {
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(row.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            throw new IllegalStateException("TRANSCRIPTION_PIPELINE_SETTINGS_WRITE_FAILED", error);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static File settingsFile(Context context) {
        return new File(context.getFilesDir(), SETTINGS_FILE);
    }

    public static boolean isAsrRuntimeAvailable(Context context, String asr) {
        String normalized = normalizeAsr(asr);
        if (ASR_WHISPER_CPU.equals(normalized)) return true;
        if (ASR_WHISPER_VULKAN.equals(normalized)) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && context.getPackageManager().hasSystemFeature("android.hardware.vulkan.level");
        }
        if (ASR_ANDROID_ON_DEVICE.equals(normalized)) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        }
        return false;
    }

    public static boolean requiresWhisperModel(Snapshot snapshot) {
        return snapshot != null && !ASR_ANDROID_ON_DEVICE.equals(snapshot.asrBackend);
    }

    public static boolean isLiveStreaming(Snapshot snapshot) {
        return snapshot != null && MODE_LIVE_STREAMING.equals(snapshot.executionMode);
    }

    public static boolean isSelectedPipelineReady(Context context, String whisperModelId) {
        Snapshot pipeline = snapshot(context);
        return unavailableReason(context, pipeline, whisperModelId) == null;
    }

    public static String unavailableReason(Context context, Snapshot pipeline, String whisperModelId) {
        if (pipeline == null) return "PIPELINE_SETTINGS_MISSING";
        if (MODE_LIVE_STREAMING.equals(pipeline.executionMode)
                && !VAD_STREAMING_SILERO.equals(pipeline.vadBackend)) {
            return "LIVE_STREAMING_REQUIRES_STREAMING_SILERO";
        }
        if (!isAsrRuntimeAvailable(context, pipeline.asrBackend)) {
            if (ASR_WHISPER_VULKAN.equals(pipeline.asrBackend)) return "VULKAN_BACKEND_UNAVAILABLE";
            if (ASR_ANDROID_ON_DEVICE.equals(pipeline.asrBackend)) return "ANDROID_ON_DEVICE_ASR_UNAVAILABLE";
            return "ASR_BACKEND_UNAVAILABLE";
        }
        if (!WhisperModelManager.isVadReady(context)) return "SILERO_VAD_MODEL_MISSING";
        if (requiresWhisperModel(pipeline)
                && !WhisperModelManager.isModelReady(context, whisperModelId)) {
            return "LOCAL_WHISPER_MODEL_MISSING";
        }
        return null;
    }

    public static void requireRunnable(Context context, Snapshot pipeline, String whisperModelId) {
        String reason = unavailableReason(context, pipeline, whisperModelId);
        if (reason != null) throw new IllegalStateException(reason);
    }

    public static JSONObject capabilities(Context context) {
        JSONObject json = new JSONObject();
        try {
            json.put("manufacturer", Build.MANUFACTURER);
            json.put("model", Build.MODEL);
            json.put("sdkInt", Build.VERSION.SDK_INT);
            json.put("cpuCores", Runtime.getRuntime().availableProcessors());
            json.put("whisperCpu", true);
            json.put("whisperVulkan", isAsrRuntimeAvailable(context, ASR_WHISPER_VULKAN));
            json.put("androidOnDeviceAsr", isAsrRuntimeAvailable(context, ASR_ANDROID_ON_DEVICE));
            json.put("sileroModelReady", WhisperModelManager.isVadReady(context));
            json.put("deepFilterNetPackaged", true);
            json.put("speakerSherpaCpu", true);
            json.put("fullStreaming", true);
            json.put("settingsTransport", "atomic-json-cross-process");
            json.put("automaticFallback", false);
            json.put("postprocessBacklogPaused", isLiveStreaming(snapshot(context)));
            json.put("selected", snapshot(context).toJson());
        } catch (Exception ignored) {
        }
        return json;
    }

    public static String modeLabel(String value) {
        return MODE_LIVE_STREAMING.equals(normalizeMode(value))
                ? "完全ストリーミング" : "5分終了後に処理";
    }

    public static String asrLabel(String value) {
        switch (normalizeAsr(value)) {
            case ASR_WHISPER_VULKAN: return "Whisper Vulkan GPU";
            case ASR_ANDROID_ON_DEVICE: return "Android 端末内ASR";
            default: return "Whisper CPU";
        }
    }

    public static String vadLabel(String value) {
        return VAD_STREAMING_SILERO.equals(normalizeVad(value))
                ? "Silero ストリーミング" : "Silero 候補区間";
    }

    public static String denoiseLabel(String value) {
        return DENOISE_DEEPFILTER.equals(normalizeDenoise(value))
                ? "DeepFilterNet 適応" : "なし";
    }

    public static String speakerLabel(String value) {
        return SPEAKER_OFF.equals(normalizeSpeaker(value))
                ? "話者判定なし" : "sherpa-onnx CPU";
    }

    private static String normalizeMode(String value) {
        return MODE_LIVE_STREAMING.equals(value) ? MODE_LIVE_STREAMING : MODE_SEGMENT_POSTPROCESS;
    }

    private static String normalizeAsr(String value) {
        if (ASR_WHISPER_VULKAN.equals(value) || ASR_ANDROID_ON_DEVICE.equals(value)) return value;
        return ASR_WHISPER_CPU;
    }

    private static String normalizeVad(String value) {
        return VAD_STREAMING_SILERO.equals(value) ? VAD_STREAMING_SILERO : VAD_CANDIDATE_SILERO;
    }

    private static String normalizeDenoise(String value) {
        return DENOISE_OFF.equals(value) ? DENOISE_OFF : DENOISE_DEEPFILTER;
    }

    private static String normalizeSpeaker(String value) {
        return SPEAKER_OFF.equals(value) ? SPEAKER_OFF : SPEAKER_SHERPA_CPU;
    }

    public static final class Snapshot {
        public final String executionMode;
        public final String asrBackend;
        public final String vadBackend;
        public final String denoiseBackend;
        public final String speakerBackend;

        Snapshot(String asrBackend, String vadBackend, String denoiseBackend, String speakerBackend) {
            this(MODE_SEGMENT_POSTPROCESS, asrBackend, vadBackend, denoiseBackend, speakerBackend);
        }

        Snapshot(String executionMode, String asrBackend, String vadBackend,
                 String denoiseBackend, String speakerBackend) {
            this.executionMode = normalizeMode(executionMode);
            this.asrBackend = normalizeAsr(asrBackend);
            this.vadBackend = normalizeVad(vadBackend);
            this.denoiseBackend = normalizeDenoise(denoiseBackend);
            this.speakerBackend = normalizeSpeaker(speakerBackend);
        }

        public String signature() {
            String legacy = "asr=" + asrBackend
                    + "+vad=" + vadBackend
                    + "+denoise=" + denoiseBackend
                    + "+speaker=" + speakerBackend;
            if (MODE_SEGMENT_POSTPROCESS.equals(executionMode)) return legacy;
            return "mode=" + executionMode + "+" + legacy;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("executionMode", executionMode);
                json.put("asrBackend", asrBackend);
                json.put("vadBackend", vadBackend);
                json.put("denoiseBackend", denoiseBackend);
                json.put("speakerBackend", speakerBackend);
                json.put("automaticFallback", false);
            } catch (Exception ignored) {
            }
            return json;
        }
    }
}
