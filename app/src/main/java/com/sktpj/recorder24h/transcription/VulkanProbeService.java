package com.sktpj.recorder24h.transcription;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs one CPU/Vulkan benchmark profile inside the dedicated :vulkan_probe process. */
public final class VulkanProbeService extends Service {
    public static final String ACTION_RUN = "com.sktpj.recorder24h.action.RUN_VULKAN_PROBE";
    public static final String EXTRA_PROFILE = "profile";
    public static final String EXTRA_MODEL_ID = "modelId";
    public static final String EXTRA_AUDIO_PATH = "audioPath";
    public static final String EXTRA_REQUEST_ID = "requestId";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) return START_NOT_STICKY;
        String profile = intent.getStringExtra(EXTRA_PROFILE);
        String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
        String audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH);
        String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        if (profile != null) executor.execute(() -> runProbe(profile, modelId, audioPath, requestId));
        return START_NOT_STICKY;
    }

    private void runProbe(String profile, String modelId, String audioPath, String requestId) {
        File audio = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);
        File model = modelId == null || modelId.isEmpty() ? null : WhisperModelManager.modelFile(this, modelId);
        VulkanProbeStore.begin(this, requestId, profile, modelId, audio == null ? null : audio.getName());
        try {
            AppLogger.event(this, "CPU_VULKAN_BENCHMARK_PROBE_STARTED", new JSONObject()
                    .put("profile", profile)
                    .put("requestId", requestId == null ? JSONObject.NULL : requestId)
                    .put("modelId", modelId == null ? JSONObject.NULL : modelId)
                    .put("audioFile", audio == null ? JSONObject.NULL : audio.getName())
                    .put("fixedInput", true));
            if (modelId == null || modelId.isEmpty()) throw new IllegalStateException("WHISPER_MODEL_ID_MISSING");
            if (model == null || !model.isFile()) throw new IllegalStateException("WHISPER_MODEL_MISSING");
            if (audio == null || !audio.isFile()) throw new IllegalStateException("RETAINED_AUDIO_MISSING");

            boolean useGpu = !VulkanProbeStore.PROFILE_CPU.equals(profile);
            VulkanProbeStore.phase(this, "PREPARE_AUDIO");
            PostprocessAsrDiagnostics.mark(this, "PROBE_PREPARE_AUDIO_BEGIN", null,
                    useGpu ? "whisper-vulkan" : "whisper-cpu", modelId,
                    new JSONObject().put("profile", profile).put("audioFile", audio.getName())
                            .put("fixedInput", true));
            LocalWhisperEngine.PreparedAudio prepared = LocalWhisperEngine.prepareAudio(audio);
            PostprocessAsrDiagnostics.mark(this, "PROBE_PREPARE_AUDIO_END", null,
                    useGpu ? "whisper-vulkan" : "whisper-cpu", modelId,
                    new JSONObject()
                            .put("profile", profile)
                            .put("sampleCount", prepared.frontEnd.samples.length)
                            .put("durationMs", prepared.durationMs())
                            .put("fixedInput", true));

            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            String breadcrumb = PostprocessAsrDiagnostics.nativeBreadcrumbPath(this, "vulkan_probe");
            long[] durations = new long[] {0L, 2_000L, 10_000L};
            for (long durationMs : durations) {
                String phase = durationMs == 0L ? "MODEL_LOAD_ONLY" : "INFERENCE_" + durationMs + "MS";
                VulkanProbeStore.phase(this, phase);
                PostprocessAsrDiagnostics.mark(this, "PROBE_" + phase + "_BEGIN", null,
                        useGpu ? "whisper-vulkan" : "whisper-cpu", modelId,
                        new JSONObject().put("profile", profile).put("durationMs", durationMs)
                                .put("fixedInput", true));
                String raw = LocalWhisperEngine.nativeProbeWhisper(model.getAbsolutePath(),
                        prepared.frontEnd.samples, "ja", threads, useGpu, durationMs, profile, breadcrumb);
                JSONObject result = new JSONObject(raw == null ? "{}" : raw);
                result.put("audioFile", audio.getName());
                result.put("modelId", modelId);
                result.put("fixedInput", true);
                VulkanProbeStore.addResult(this, phase, result);
                PostprocessAsrDiagnostics.mark(this, "PROBE_" + phase + "_END", null,
                        useGpu ? "whisper-vulkan" : "whisper-cpu", modelId, result);
            }
            VulkanProbeStore.complete(this);
            AppLogger.event(this, "CPU_VULKAN_BENCHMARK_PROBE_COMPLETED",
                    new JSONObject().put("profile", profile)
                            .put("requestId", requestId == null ? JSONObject.NULL : requestId)
                            .put("modelId", modelId)
                            .put("audioFile", audio.getName()));
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
            VulkanProbeStore.fail(this, message);
            try {
                AppLogger.event(this, "CPU_VULKAN_BENCHMARK_PROBE_FAILED", new JSONObject()
                        .put("profile", profile)
                        .put("phase", VulkanProbeStore.read(this).optString("phase", "-"))
                        .put("error", message));
            } catch (Exception ignored) {}
        } finally {
            stopSelf();
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> Process.killProcess(Process.myPid()), 500L);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
