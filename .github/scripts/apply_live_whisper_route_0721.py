from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise RuntimeError(f"missing replacement target: {label}")
    return text.replace(old, new, 1)

root = Path('.')

# Version
path = root / 'app/build.gradle'
text = path.read_text()
text = replace_once(text, "versionCode 1020\n        versionName '0.7.20'", "versionCode 1021\n        versionName '0.7.21'", 'version')
text = replace_once(
    text,
    '        // Live Whisper keeps model weights resident but allocates/frees inference state per utterance to bound memory.\n        // Streaming ASR logs prior process exit reason and memory/stage breadcrumbs for native crash diagnosis.\n',
    '        // Live Whisper keeps model weights resident but allocates/frees inference state per utterance to bound memory.\n        // Streaming ASR logs prior process exit reason and memory/stage breadcrumbs for native crash diagnosis.\n        // Full streaming can explicitly choose persistent live Whisper or the normal postprocess JNI per utterance.\n        // The standard live route uses nativeTranscribeDetailed without automatic backend fallback.\n',
    'build comments')
path.write_text(text)

# Pipeline settings
path = root / 'app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionPipelineSettings.java'
text = path.read_text()
text = replace_once(
    text,
    '    public static final String ASR_ANDROID_ON_DEVICE = "android-on-device";\n\n    public static final String VAD_CANDIDATE_SILERO',
    '    public static final String ASR_ANDROID_ON_DEVICE = "android-on-device";\n\n    public static final String LIVE_WHISPER_PERSISTENT = "persistent-live";\n    public static final String LIVE_WHISPER_POSTPROCESS_NATIVE = "postprocess-native-per-utterance";\n\n    public static final String VAD_CANDIDATE_SILERO',
    'live route constants')
text = replace_once(
    text,
    '    private static final String KEY_ASR = "asr_backend";\n    private static final String KEY_VAD = "vad_backend";',
    '    private static final String KEY_ASR = "asr_backend";\n    private static final String KEY_LIVE_WHISPER_ROUTE = "live_whisper_route";\n    private static final String KEY_VAD = "vad_backend";',
    'live route key')
text = replace_once(
    text,
    '''    public static void setExecutionMode(Context context, String value) {\n        update(context, normalizeMode(value), null, null, null, null);\n    }\n\n    public static void setAsr(Context context, String value) {\n        update(context, null, normalizeAsr(value), null, null, null);\n    }\n\n    public static void setVad(Context context, String value) {\n        update(context, null, null, normalizeVad(value), null, null);\n    }\n\n    public static void setDenoise(Context context, String value) {\n        update(context, null, null, null, normalizeDenoise(value), null);\n    }\n\n    public static void setSpeaker(Context context, String value) {\n        update(context, null, null, null, null, normalizeSpeaker(value));\n    }\n\n    private static void update(Context context, String mode, String asr, String vad,\n                               String denoise, String speaker) {''',
    '''    public static void setExecutionMode(Context context, String value) {\n        update(context, normalizeMode(value), null, null, null, null, null);\n    }\n\n    public static void setAsr(Context context, String value) {\n        update(context, null, normalizeAsr(value), null, null, null, null);\n    }\n\n    public static void setLiveWhisperRoute(Context context, String value) {\n        update(context, null, null, normalizeLiveWhisperRoute(value), null, null, null);\n    }\n\n    public static void setVad(Context context, String value) {\n        update(context, null, null, null, normalizeVad(value), null, null);\n    }\n\n    public static void setDenoise(Context context, String value) {\n        update(context, null, null, null, null, normalizeDenoise(value), null);\n    }\n\n    public static void setSpeaker(Context context, String value) {\n        update(context, null, null, null, null, null, normalizeSpeaker(value));\n    }\n\n    private static void update(Context context, String mode, String asr, String liveWhisperRoute, String vad,\n                               String denoise, String speaker) {''',
    'setters/update')
text = replace_once(
    text,
    '''            Snapshot next = new Snapshot(\n                    mode == null ? current.executionMode : mode,\n                    asr == null ? current.asrBackend : asr,\n                    vad == null ? current.vadBackend : vad,\n                    denoise == null ? current.denoiseBackend : denoise,\n                    speaker == null ? current.speakerBackend : speaker);''',
    '''            Snapshot next = new Snapshot(\n                    mode == null ? current.executionMode : mode,\n                    asr == null ? current.asrBackend : asr,\n                    liveWhisperRoute == null ? current.liveWhisperRoute : liveWhisperRoute,\n                    vad == null ? current.vadBackend : vad,\n                    denoise == null ? current.denoiseBackend : denoise,\n                    speaker == null ? current.speakerBackend : speaker);''',
    'snapshot update')
text = replace_once(
    text,
    '                    .putString(KEY_ASR, next.asrBackend)\n                    .putString(KEY_VAD, next.vadBackend)',
    '                    .putString(KEY_ASR, next.asrBackend)\n                    .putString(KEY_LIVE_WHISPER_ROUTE, next.liveWhisperRoute)\n                    .putString(KEY_VAD, next.vadBackend)',
    'prefs write')
text = replace_once(
    text,
    '''        return new Snapshot(\n                normalizeMode(prefs.getString(KEY_MODE, MODE_SEGMENT_POSTPROCESS)),\n                normalizeAsr(prefs.getString(KEY_ASR, ASR_WHISPER_CPU)),\n                normalizeVad(prefs.getString(KEY_VAD, VAD_CANDIDATE_SILERO)),\n                normalizeDenoise(prefs.getString(KEY_DENOISE, DENOISE_DEEPFILTER)),\n                normalizeSpeaker(prefs.getString(KEY_SPEAKER, SPEAKER_SHERPA_CPU)));''',
    '''        return new Snapshot(\n                normalizeMode(prefs.getString(KEY_MODE, MODE_SEGMENT_POSTPROCESS)),\n                normalizeAsr(prefs.getString(KEY_ASR, ASR_WHISPER_CPU)),\n                normalizeLiveWhisperRoute(prefs.getString(KEY_LIVE_WHISPER_ROUTE, LIVE_WHISPER_PERSISTENT)),\n                normalizeVad(prefs.getString(KEY_VAD, VAD_CANDIDATE_SILERO)),\n                normalizeDenoise(prefs.getString(KEY_DENOISE, DENOISE_DEEPFILTER)),\n                normalizeSpeaker(prefs.getString(KEY_SPEAKER, SPEAKER_SHERPA_CPU)));''',
    'legacy migration')
text = replace_once(
    text,
    '''        return new Snapshot(\n                normalizeMode(row.optString("executionMode", MODE_SEGMENT_POSTPROCESS)),\n                normalizeAsr(row.optString("asrBackend", ASR_WHISPER_CPU)),\n                normalizeVad(row.optString("vadBackend", VAD_CANDIDATE_SILERO)),\n                normalizeDenoise(row.optString("denoiseBackend", DENOISE_DEEPFILTER)),\n                normalizeSpeaker(row.optString("speakerBackend", SPEAKER_SHERPA_CPU)));''',
    '''        return new Snapshot(\n                normalizeMode(row.optString("executionMode", MODE_SEGMENT_POSTPROCESS)),\n                normalizeAsr(row.optString("asrBackend", ASR_WHISPER_CPU)),\n                normalizeLiveWhisperRoute(row.optString("liveWhisperRoute", LIVE_WHISPER_PERSISTENT)),\n                normalizeVad(row.optString("vadBackend", VAD_CANDIDATE_SILERO)),\n                normalizeDenoise(row.optString("denoiseBackend", DENOISE_DEEPFILTER)),\n                normalizeSpeaker(row.optString("speakerBackend", SPEAKER_SHERPA_CPU)));''',
    'json read')
text = replace_once(text, '            row.put("schemaVersion", 1);', '            row.put("schemaVersion", 2);', 'schema version')
text = replace_once(
    text,
    '            json.put("fullStreaming", true);\n            json.put("settingsTransport", "atomic-json-cross-process");',
    '            json.put("fullStreaming", true);\n            json.put("liveWhisperPersistent", true);\n            json.put("liveWhisperPostprocessNativePerUtterance", true);\n            json.put("settingsTransport", "atomic-json-cross-process");',
    'capabilities')
text = replace_once(
    text,
    '''    public static String asrLabel(String value) {\n        switch (normalizeAsr(value)) {\n            case ASR_WHISPER_VULKAN: return "Whisper Vulkan GPU";\n            case ASR_ANDROID_ON_DEVICE: return "Android 端末内ASR";\n            default: return "Whisper CPU";\n        }\n    }\n\n    public static String vadLabel''',
    '''    public static String asrLabel(String value) {\n        switch (normalizeAsr(value)) {\n            case ASR_WHISPER_VULKAN: return "Whisper Vulkan GPU";\n            case ASR_ANDROID_ON_DEVICE: return "Android 端末内ASR";\n            default: return "Whisper CPU";\n        }\n    }\n\n    public static String liveWhisperRouteLabel(String value) {\n        return LIVE_WHISPER_POSTPROCESS_NATIVE.equals(normalizeLiveWhisperRoute(value))\n                ? "通常JNI（発話ごとロード）" : "ライブ専用常駐";\n    }\n\n    public static String vadLabel''',
    'route label')
text = replace_once(
    text,
    '''    private static String normalizeAsr(String value) {\n        if (ASR_WHISPER_VULKAN.equals(value) || ASR_ANDROID_ON_DEVICE.equals(value)) return value;\n        return ASR_WHISPER_CPU;\n    }\n\n    private static String normalizeVad''',
    '''    private static String normalizeAsr(String value) {\n        if (ASR_WHISPER_VULKAN.equals(value) || ASR_ANDROID_ON_DEVICE.equals(value)) return value;\n        return ASR_WHISPER_CPU;\n    }\n\n    private static String normalizeLiveWhisperRoute(String value) {\n        return LIVE_WHISPER_POSTPROCESS_NATIVE.equals(value)\n                ? LIVE_WHISPER_POSTPROCESS_NATIVE : LIVE_WHISPER_PERSISTENT;\n    }\n\n    private static String normalizeVad''',
    'route normalization')
text = replace_once(
    text,
    '''        public final String executionMode;\n        public final String asrBackend;\n        public final String vadBackend;\n        public final String denoiseBackend;\n        public final String speakerBackend;\n\n        Snapshot(String asrBackend, String vadBackend, String denoiseBackend, String speakerBackend) {\n            this(MODE_SEGMENT_POSTPROCESS, asrBackend, vadBackend, denoiseBackend, speakerBackend);\n        }\n\n        Snapshot(String executionMode, String asrBackend, String vadBackend,\n                 String denoiseBackend, String speakerBackend) {\n            this.executionMode = normalizeMode(executionMode);\n            this.asrBackend = normalizeAsr(asrBackend);\n            this.vadBackend = normalizeVad(vadBackend);\n            this.denoiseBackend = normalizeDenoise(denoiseBackend);\n            this.speakerBackend = normalizeSpeaker(speakerBackend);\n        }''',
    '''        public final String executionMode;\n        public final String asrBackend;\n        public final String liveWhisperRoute;\n        public final String vadBackend;\n        public final String denoiseBackend;\n        public final String speakerBackend;\n\n        Snapshot(String asrBackend, String vadBackend, String denoiseBackend, String speakerBackend) {\n            this(MODE_SEGMENT_POSTPROCESS, asrBackend, LIVE_WHISPER_PERSISTENT,\n                    vadBackend, denoiseBackend, speakerBackend);\n        }\n\n        Snapshot(String executionMode, String asrBackend, String vadBackend,\n                 String denoiseBackend, String speakerBackend) {\n            this(executionMode, asrBackend, LIVE_WHISPER_PERSISTENT,\n                    vadBackend, denoiseBackend, speakerBackend);\n        }\n\n        Snapshot(String executionMode, String asrBackend, String liveWhisperRoute, String vadBackend,\n                 String denoiseBackend, String speakerBackend) {\n            this.executionMode = normalizeMode(executionMode);\n            this.asrBackend = normalizeAsr(asrBackend);\n            this.liveWhisperRoute = normalizeLiveWhisperRoute(liveWhisperRoute);\n            this.vadBackend = normalizeVad(vadBackend);\n            this.denoiseBackend = normalizeDenoise(denoiseBackend);\n            this.speakerBackend = normalizeSpeaker(speakerBackend);\n        }''',
    'snapshot fields/constructors')
text = replace_once(
    text,
    '''            if (MODE_SEGMENT_POSTPROCESS.equals(executionMode)) return legacy;\n            return "mode=" + executionMode + "+" + legacy;''',
    '''            if (MODE_SEGMENT_POSTPROCESS.equals(executionMode)) return legacy;\n            // Preserve the exact 0.7.16-0.7.20 live signature for the existing persistent route.\n            if (LIVE_WHISPER_PERSISTENT.equals(liveWhisperRoute)) {\n                return "mode=" + executionMode + "+" + legacy;\n            }\n            return "mode=" + executionMode + "+liveWhisperRoute=" + liveWhisperRoute + "+" + legacy;''',
    'signature compatibility')
text = replace_once(
    text,
    '                json.put("asrBackend", asrBackend);\n                json.put("vadBackend", vadBackend);',
    '                json.put("asrBackend", asrBackend);\n                json.put("liveWhisperRoute", liveWhisperRoute);\n                json.put("vadBackend", vadBackend);',
    'json write route')
path.write_text(text)

# Settings UI
path = root / 'app/src/main/java/com/sktpj/recorder24h/TranscriptionBackendSettingsCard.kt'
text = path.read_text()
anchor = '''            PipelineSection("VAD") {\n'''
insert = '''            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING &&\n                pipeline.asrBackend != TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE\n            ) {\n                PipelineSection("ライブWhisper経路") {\n                    BackendChip(\n                        selected = pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT,\n                        enabled = true,\n                        text = "ライブ専用常駐"\n                    ) {\n                        val before = pipeline.liveWhisperRoute\n                        TranscriptionPipelineSettings.setLiveWhisperRoute(\n                            context, TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT\n                        )\n                        refresh(); logChange("liveWhisperRoute", before, pipeline.liveWhisperRoute)\n                    }\n                    BackendChip(\n                        selected = pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE,\n                        enabled = true,\n                        text = "通常JNI（発話ごとロード）"\n                    ) {\n                        val before = pipeline.liveWhisperRoute\n                        TranscriptionPipelineSettings.setLiveWhisperRoute(\n                            context, TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE\n                        )\n                        refresh(); logChange("liveWhisperRoute", before, pipeline.liveWhisperRoute)\n                    }\n                }\n                Text(\n                    if (pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE)\n                        "通常JNIは5分処理と同じnativeTranscribeDetailedを発話ごとに使い、毎回モデルをロード・解放します。"\n                    else\n                        "ライブ専用常駐はモデルを保持する既存経路です。どちらも選択backendのままで、自動フォールバックしません。",\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n            }\n\n'''
text = replace_once(text, anchor, insert + anchor, 'settings route UI')
text = replace_once(
    text,
    '                "完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡し、発話中は暫定認識、発話終了時に確定認識します。5分音声保存は従来どおり継続します。録音中の設定変更は次の5分セグメント境界から反映されます。",',
    '                "完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡し、発話中は暫定認識、発話終了時に確定認識します。Whisperはライブ専用常駐または通常JNI（発話ごとロード）を明示選択できます。5分音声保存は従来どおり継続し、録音中の設定変更は次の5分セグメント境界から反映されます。",',
    'settings footer')
path.write_text(text)

# Coordinator: carry route across process boundary
path = root / 'app/src/main/java/com/sktpj/recorder24h/transcription/FullStreamingTranscriptionCoordinator.java'
text = path.read_text()
text = replace_once(
    text,
    '        data.putString(prefix + "Asr", pipeline.asrBackend);\n        data.putString(prefix + "Vad", pipeline.vadBackend);',
    '        data.putString(prefix + "Asr", pipeline.asrBackend);\n        data.putString(prefix + "LiveWhisperRoute", pipeline.liveWhisperRoute);\n        data.putString(prefix + "Vad", pipeline.vadBackend);',
    'bundle route')
path.write_text(text)

# Expose the already-used normal JNI entrypoint package-locally for the live standard runner.
path = root / 'app/src/main/java/com/sktpj/recorder24h/transcription/LocalWhisperEngine.java'
text = path.read_text()
text = replace_once(
    text,
    '    private static native String nativeTranscribeDetailed(String modelPath, float[] pcm,',
    '    static native String nativeTranscribeDetailed(String modelPath, float[] pcm,',
    'native visibility')
path.write_text(text)

# Standard live route wrapper using the exact normal/postprocess JNI call.
path = root / 'app/src/main/java/com/sktpj/recorder24h/transcription/PostprocessWhisperLiveRunner.java'
path.write_text(r'''package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Full-streaming Whisper route that deliberately reuses the normal five-minute JNI entrypoint.
 * It does not keep a live Whisper context. Each utterance loads, runs and frees the model through
 * nativeTranscribeDetailed(), exactly like the proven postprocess path, while still receiving
 * already-captured live PCM. No backend fallback is performed here.
 */
final class PostprocessWhisperLiveRunner {
    private static final String LANGUAGE = "ja";

    private PostprocessWhisperLiveRunner() {
    }

    static Result transcribe(Context context, float[] samples, String modelId,
                             TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {
        if (pipeline == null || TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(pipeline.asrBackend)) {
            throw new IllegalArgumentException("POSTPROCESS_LIVE_WHISPER_REQUIRES_WHISPER_BACKEND");
        }
        TranscriptionPipelineSettings.requireRunnable(context, pipeline, modelId);
        if (samples == null || samples.length == 0) {
            return new Result("", new JSONArray(), 0L, 0L, 0L);
        }
        File model = WhisperModelManager.modelFile(context, modelId);
        if (!model.isFile()) throw new IllegalStateException("LOCAL_WHISPER_MODEL_MISSING");

        int durationMs = Math.max(1, (int) (samples.length * 1000L / 16_000L));
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        boolean useGpu = TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(pipeline.asrBackend);
        long cancellationToken = TranscriptionCancellation.snapshot();
        LocalWhisperEngine.setNativePostprocessCancellationGeneration(cancellationToken);

        String raw = LocalWhisperEngine.nativeTranscribeDetailed(
                model.getAbsolutePath(), samples,
                new int[]{0}, new int[]{durationMs},
                LANGUAGE, threads, useGpu, cancellationToken);
        if (raw == null) throw new IllegalStateException("POSTPROCESS_LIVE_WHISPER_RETURNED_NULL");
        JSONObject json = new JSONObject(raw);
        JSONArray segments = json.optJSONArray("segments");
        return new Result(
                json.optString("text", "").trim(),
                segments == null ? new JSONArray() : segments,
                json.optLong("modelLoadMs", -1L),
                json.optLong("whisperFullMs", -1L),
                json.optLong("lastOutputEndMs", 0L));
    }

    static final class Result {
        final String text;
        final JSONArray segments;
        final long modelLoadMs;
        final long whisperFullMs;
        final long lastOutputEndMs;

        Result(String text, JSONArray segments, long modelLoadMs,
               long whisperFullMs, long lastOutputEndMs) {
            this.text = text;
            this.segments = segments;
            this.modelLoadMs = modelLoadMs;
            this.whisperFullMs = whisperFullMs;
            this.lastOutputEndMs = lastOutputEndMs;
        }
    }
}
''')

# Streaming service route selection.
path = root / 'app/src/main/java/com/sktpj/recorder24h/transcription/StreamingTranscriptionService.java'
text = path.read_text()
text = replace_once(
    text,
    '''                long loadMs = 0L;\n                if (TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(accumulator.config.asrBackend)) {\n                    LiveWhisperSession.close();\n                } else {\n                    loadMs = LiveWhisperSession.open(this, accumulator.config.modelId, accumulator.config.snapshot);\n                }\n                JSONObject details = accumulator.config.toJson().put("modelLoadMs", loadMs);''',
    '''                long loadMs = 0L;\n                boolean persistentWhisper = !TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(accumulator.config.asrBackend)\n                        && TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT.equals(accumulator.config.liveWhisperRoute);\n                if (persistentWhisper) {\n                    loadMs = LiveWhisperSession.open(this, accumulator.config.modelId, accumulator.config.snapshot);\n                } else {\n                    // Standard-per-utterance deliberately has no persistent live context.\n                    LiveWhisperSession.close();\n                }\n                JSONObject details = accumulator.config.toJson()\n                        .put("modelLoadMs", loadMs)\n                        .put("persistentLiveContext", persistentWhisper);''',
    'configure route')
text = replace_once(
    text,
    '''            setProcessStage("whisper-begin:" + accumulator.config.asrBackend + ":" + durationMs);\n            log("FULL_STREAMING_WHISPER_NATIVE_BEGIN", memoryDetails("whisper-native-begin")\n                    .put("durationMs", durationMs)\n                    .put("partial", partial)\n                    .put("backend", accumulator.config.asrBackend));\n            LiveWhisperSession.Result whisper = LiveWhisperSession.transcribe(asrSamples);\n            setProcessStage("whisper-end:" + accumulator.config.asrBackend + ":" + durationMs);\n            log("FULL_STREAMING_WHISPER_NATIVE_END", memoryDetails("whisper-native-end")\n                    .put("durationMs", durationMs)\n                    .put("partial", partial)\n                    .put("backend", accumulator.config.asrBackend)\n                    .put("whisperFullMs", whisper.whisperFullMs));\n            text = whisper.text;\n            segments = new JSONArray(whisper.segments.toString());\n            whisperFullMs = whisper.whisperFullMs;''',
    '''            String liveRoute = accumulator.config.liveWhisperRoute;\n            setProcessStage("whisper-begin:" + accumulator.config.asrBackend + ":" + liveRoute + ":" + durationMs);\n            log("FULL_STREAMING_WHISPER_NATIVE_BEGIN", memoryDetails("whisper-native-begin")\n                    .put("durationMs", durationMs)\n                    .put("partial", partial)\n                    .put("backend", accumulator.config.asrBackend)\n                    .put("liveWhisperRoute", liveRoute));\n            long modelLoadMs = -1L;\n            if (TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE.equals(liveRoute)) {\n                PostprocessWhisperLiveRunner.Result whisper = PostprocessWhisperLiveRunner.transcribe(\n                        this, asrSamples, accumulator.config.modelId, accumulator.config.snapshot);\n                text = whisper.text;\n                segments = new JSONArray(whisper.segments.toString());\n                whisperFullMs = whisper.whisperFullMs;\n                modelLoadMs = whisper.modelLoadMs;\n            } else {\n                LiveWhisperSession.Result whisper = LiveWhisperSession.transcribe(asrSamples);\n                text = whisper.text;\n                segments = new JSONArray(whisper.segments.toString());\n                whisperFullMs = whisper.whisperFullMs;\n            }\n            setProcessStage("whisper-end:" + accumulator.config.asrBackend + ":" + liveRoute + ":" + durationMs);\n            log("FULL_STREAMING_WHISPER_NATIVE_END", memoryDetails("whisper-native-end")\n                    .put("durationMs", durationMs)\n                    .put("partial", partial)\n                    .put("backend", accumulator.config.asrBackend)\n                    .put("liveWhisperRoute", liveRoute)\n                    .put("modelLoadMs", modelLoadMs)\n                    .put("whisperFullMs", whisperFullMs));''',
    'recognize route')
text = replace_once(
    text,
    '''        final String asrBackend;\n        final String vadBackend;\n        final String denoiseBackend;''',
    '''        final String asrBackend;\n        final String liveWhisperRoute;\n        final String vadBackend;\n        final String denoiseBackend;''',
    'pipeline field')
text = replace_once(
    text,
    '''        PipelineConfig(String mode, String asrBackend, String vadBackend,\n                       String denoiseBackend, String speakerBackend, String modelId) {\n            this.mode = mode;\n            this.asrBackend = asrBackend;\n            this.vadBackend = vadBackend;\n            this.denoiseBackend = denoiseBackend;\n            this.speakerBackend = speakerBackend;\n            this.modelId = modelId;\n            this.snapshot = new TranscriptionPipelineSettings.Snapshot(\n                    mode, asrBackend, vadBackend, denoiseBackend, speakerBackend);\n        }''',
    '''        PipelineConfig(String mode, String asrBackend, String liveWhisperRoute, String vadBackend,\n                       String denoiseBackend, String speakerBackend, String modelId) {\n            this.mode = mode;\n            this.asrBackend = asrBackend;\n            this.liveWhisperRoute = liveWhisperRoute;\n            this.vadBackend = vadBackend;\n            this.denoiseBackend = denoiseBackend;\n            this.speakerBackend = speakerBackend;\n            this.modelId = modelId;\n            this.snapshot = new TranscriptionPipelineSettings.Snapshot(\n                    mode, asrBackend, liveWhisperRoute, vadBackend, denoiseBackend, speakerBackend);\n        }''',
    'pipeline constructor')
text = replace_once(
    text,
    '''                    data.getString(prefix + "Mode", TranscriptionPipelineSettings.MODE_SEGMENT_POSTPROCESS),\n                    data.getString(prefix + "Asr", TranscriptionPipelineSettings.ASR_WHISPER_CPU),\n                    data.getString(prefix + "Vad", TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO),''',
    '''                    data.getString(prefix + "Mode", TranscriptionPipelineSettings.MODE_SEGMENT_POSTPROCESS),\n                    data.getString(prefix + "Asr", TranscriptionPipelineSettings.ASR_WHISPER_CPU),\n                    data.getString(prefix + "LiveWhisperRoute", TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT),\n                    data.getString(prefix + "Vad", TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO),''',
    'bundle parse route')
text = text.replace('.put("asrBackend", config.asrBackend)\n                        .put("streamingFinal", true)', '.put("asrBackend", config.asrBackend)\n                        .put("liveWhisperRoute", config.liveWhisperRoute)\n                        .put("streamingFinal", true)')
text = text.replace('.put("asrBackend", config.asrBackend)\n                        .put("streamingFinal", true));', '.put("asrBackend", config.asrBackend)\n                        .put("liveWhisperRoute", config.liveWhisperRoute)\n                        .put("streamingFinal", true));')
text = text.replace('.put("inferenceMs", result.inferenceMs)\n                        .put("queueDepth", pendingInference.get()));', '.put("inferenceMs", result.inferenceMs)\n                        .put("liveWhisperRoute", accumulator.config.liveWhisperRoute)\n                        .put("queueDepth", pendingInference.get()));')
path.write_text(text)

print('0.7.21 live Whisper route patch applied')
