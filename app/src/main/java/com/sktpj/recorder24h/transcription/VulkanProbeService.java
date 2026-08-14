package com.sktpj.recorder24h.transcription;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs one ASR comparison profile only inside the dedicated :vulkan_probe process. */
public final class VulkanProbeService extends Service {
    public static final String ACTION_RUN = "com.sktpj.recorder24h.action.RUN_VULKAN_PROBE";
    public static final String EXTRA_PROFILE = "profile";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) return START_NOT_STICKY;
        String profile = intent.getStringExtra(EXTRA_PROFILE);
        if (profile != null) executor.execute(() -> runProbe(profile));
        return START_NOT_STICKY;
    }

    private void runProbe(String profile) {
        String modelId = WhisperModelManager.selectedModelId(this);
        File model = WhisperModelManager.modelFile(this, modelId);
        SegmentRecord source = SegmentHistoryRepository.load(this).stream()
                .filter(record -> record.getAudioAvailable() && record.getAudioPath() != null)
                .max(Comparator.comparingLong(SegmentRecord::getSortTimeMs))
                .orElse(null);
        File audio = source == null ? null : new File(source.getAudioPath());
        VulkanProbeStore.begin(this, profile, modelId, audio == null ? null : audio.getName());
        try {
            AppLogger.event(this, "VULKAN_PROBE_STARTED", new JSONObject()
                    .put("profile", profile)
                    .put("modelId", modelId)
                    .put("audioFile", audio == null ? JSONObject.NULL : audio.getName()));
            if (!model.isFile()) throw new IllegalStateException("WHISPER_MODEL_MISSING");
            if (audio == null || !audio.isFile()) throw new IllegalStateException("RETAINED_AUDIO_MISSING");

            boolean useGpu = !VulkanProbeStore.PROFILE_CPU.equals(profile);
            VulkanProbeStore.phase(this, "PREPARE_AUDIO");
            PostprocessAsrDiagnostics.mark(this, "PROBE_PREPARE_AUDIO_BEGIN", null,
                    useGpu ? "whisper-vulkan" : "whisper-cpu", modelId,
                    new JSONObject().put("profile", profile).put("audioFile", audio.getName()));
            LocalWhisperEngine.PreparedAudio prepared = LocalWhisperEngine.prepareAudio(audio);
            PostprocessAsrDiagnostics.mark(this, "PROBE_PREPARE_AUDIO_END", null,
                    useGpu ? "whisper-vulkan" : "whisper-cpu", modelId,
                    new JSONObject()
                            .put("profile", profile)
                            .put("sampleCount", prepared.frontEnd.samples.length)
                            .put("durationMs", prepared.durationMs()));

            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            String breadcrumb = PostprocessAsrDiagnostics.nativeBreadcrumbPath(this, "vulkan_probe");
            long[] durations = new long[] {0L, 2_000L, 10_000L, 30_000L};
            for (long durationMs : durations) {
                String phase = durationMs == 0L ? "MODEL_LOAD_ONLY" : "INFERENCE_" + durationMs + "MS";
                VulkanProbeStore.phase(this, phase);
                PostprocessAsrDiagnostics.mark(this, "PROBE_" + phase + "_BEGIN", null,
                        useGpu ? "whisper-vulkan" : "whisper-cpu", modelId,
                        new JSONObject().put("profile", profile).put("durationMs", durationMs));
                String raw = LocalWhisperEngine.nativeProbeWhisper(model.getAbsolutePath(),
                        prepared.frontEnd.samples, "ja", threads, useGpu, durationMs, profile, breadcrumb);
                JSONObject result = new JSONObject(raw == null ? "{}" : raw);
                VulkanProbeStore.addResult(this, phase, result);
                PostprocessAsrDiagnostics.mark(this, "PROBE_" + phase + "_END", null,
                        useGpu ? "whisper-vulkan" : "whisper-cpu", modelId, result);
            }
            VulkanProbeStore.complete(this);
            AppLogger.event(this, "VULKAN_PROBE_COMPLETED", new JSONObject().put("profile", profile));
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
            VulkanProbeStore.fail(this, message);
            try {
                AppLogger.event(this, "VULKAN_PROBE_FAILED", new JSONObject()
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
