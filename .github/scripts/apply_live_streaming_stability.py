from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing pattern: {label}")
    return text.replace(old, new, 1)

# Version
p = Path('app/build.gradle')
s = p.read_text()
s = replace_once(s, "versionCode 1019\n        versionName '0.7.19'", "versionCode 1020\n        versionName '0.7.20'", 'version')
s = replace_once(s,
    "        // Record history exposes a live view backed by durable rolling full-streaming final utterances.\n",
    "        // Record history exposes a live view backed by durable rolling full-streaming final utterances.\n"
    "        // Live Whisper keeps model weights resident but allocates/frees inference state per utterance to bound memory.\n"
    "        // Streaming ASR logs prior process exit reason and memory/stage breadcrumbs for native crash diagnosis.\n",
    'version comments')
p.write_text(s)

# Native live Whisper: keep model resident without a default state; allocate state per utterance.
p = Path('app/src/main/cpp/live_whisper_jni.cpp')
s = p.read_text()
s = replace_once(s,
    '    g_live_ctx = whisper_init_from_file_with_params(model, params);\n',
    '    // Keep only model weights resident. The default-state initializer keeps large encoder/decoder\n'
    '    // compute buffers alive for the lifetime of the service and overlaps them with DeepFilterNet.\n'
    '    // Full streaming instead allocates a fresh whisper_state only while one utterance is decoded.\n'
    '    g_live_ctx = whisper_init_from_file_with_params_no_state(model, params);\n',
    'no-state model open')

old = '''    const auto started = std::chrono::steady_clock::now();
    const int rc = whisper_full(g_live_ctx, params, samples, static_cast<int>(sample_count));
    const auto finished = std::chrono::steady_clock::now();
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (rc != 0) {
        throw_runtime(env, "whisper_full for live utterance failed");
        return nullptr;
    }

    std::string text;
'''
new = '''    // v1.9.1 supports an explicit state object. Allocate it only after Java-side denoise has
    // completed, run this utterance through that state, then free it before returning. This keeps
    // medium-q5 model weights resident while avoiding a permanently resident inference state.
    whisper_state * state = whisper_init_state(g_live_ctx);
    if (state == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(language, lang);
        throw_runtime(env, "Unable to allocate live Whisper inference state");
        return nullptr;
    }

    const auto started = std::chrono::steady_clock::now();
    const int rc = whisper_full_with_state(
            g_live_ctx, state, params, samples, static_cast<int>(sample_count));
    const auto finished = std::chrono::steady_clock::now();
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (rc != 0) {
        whisper_free_state(state);
        throw_runtime(env, "whisper_full_with_state for live utterance failed");
        return nullptr;
    }

    std::string text;
'''
s = replace_once(s, old, new, 'per-utterance state')
replacements = {
    'whisper_full_n_segments(g_live_ctx)': 'whisper_full_n_segments_from_state(state)',
    'whisper_full_get_segment_text(g_live_ctx, i)': 'whisper_full_get_segment_text_from_state(state, i)',
    'whisper_full_get_segment_t0(g_live_ctx, i)': 'whisper_full_get_segment_t0_from_state(state, i)',
    'whisper_full_get_segment_t1(g_live_ctx, i)': 'whisper_full_get_segment_t1_from_state(state, i)',
    'whisper_full_n_tokens(g_live_ctx, i)': 'whisper_full_n_tokens_from_state(state, i)',
    'whisper_full_get_token_p(g_live_ctx, i, token)': 'whisper_full_get_token_p_from_state(state, i, token)',
    'whisper_full_get_segment_no_speech_prob(g_live_ctx, i)': 'whisper_full_get_segment_no_speech_prob_from_state(state, i)',
}
for old_value, new_value in replacements.items():
    if old_value not in s:
        raise SystemExit(f'missing native accessor: {old_value}')
    s = s.replace(old_value, new_value)
s = replace_once(s,
    '    return env->NewStringUTF(json.str().c_str());\n',
    '    const std::string output = json.str();\n'
    '    whisper_free_state(state);\n'
    '    return env->NewStringUTF(output.c_str());\n',
    'free utterance state')
p.write_text(s)

# Java-side breadcrumbs + process-exit/memory diagnostics.
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/StreamingTranscriptionService.java')
s = p.read_text()
s = replace_once(s,
    'import android.app.Service;\n',
    'import android.app.ActivityManager;\n'
    'import android.app.ApplicationExitInfo;\n'
    'import android.app.Service;\n',
    'activity imports')
s = replace_once(s,
    'import android.os.Bundle;\n',
    'import android.os.Build;\n'
    'import android.os.Bundle;\n'
    'import android.os.Debug;\n',
    'os diagnostics imports')
s = replace_once(s,
    'import java.io.File;\n',
    'import java.io.File;\n'
    'import java.nio.charset.StandardCharsets;\n',
    'charset import')

s = replace_once(s,
    '''        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
        log("FULL_STREAMING_SERVICE_CREATED", null);
''',
    '''        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
        logPreviousStreamingExit();
        setProcessStage("service-created");
        log("FULL_STREAMING_SERVICE_CREATED", memoryDetails("service-created"));
''',
    'service create diagnostics')

s = replace_once(s,
    '''                JSONObject details = accumulator.config.toJson().put("modelLoadMs", loadMs);
                log("FULL_STREAMING_BACKEND_READY", details);
''',
    '''                JSONObject details = accumulator.config.toJson().put("modelLoadMs", loadMs);
                appendMemory(details, "backend-ready");
                setProcessStage("backend-ready:" + accumulator.config.asrBackend);
                log("FULL_STREAMING_BACKEND_READY", details);
''',
    'backend memory diagnostics')

s = replace_once(s,
    '''        if (TranscriptionPipelineSettings.DENOISE_DEEPFILTER.equals(accumulator.config.denoiseBackend)) {
            DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(
                    this, accumulator.segmentId, asrSamples,
                    new int[]{0}, new int[]{durationMs}, front.snrProxyDb);
            asrSamples = denoise.samples;
        }
''',
    '''        if (TranscriptionPipelineSettings.DENOISE_DEEPFILTER.equals(accumulator.config.denoiseBackend)) {
            setProcessStage("denoise-begin:" + durationMs);
            log("FULL_STREAMING_DENOISE_BEGIN", memoryDetails("denoise-begin")
                    .put("durationMs", durationMs)
                    .put("partial", partial));
            DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(
                    this, accumulator.segmentId, asrSamples,
                    new int[]{0}, new int[]{durationMs}, front.snrProxyDb);
            asrSamples = denoise.samples;
            setProcessStage("denoise-end:" + durationMs);
            log("FULL_STREAMING_DENOISE_END", memoryDetails("denoise-end")
                    .put("durationMs", durationMs)
                    .put("partial", partial)
                    .put("denoiseApplied", denoise.applied)
                    .put("denoiseMs", denoise.processingMs));
        }
''',
    'denoise diagnostics')

s = replace_once(s,
    '''        } else {
            LiveWhisperSession.Result whisper = LiveWhisperSession.transcribe(asrSamples);
            text = whisper.text;
            segments = new JSONArray(whisper.segments.toString());
            whisperFullMs = whisper.whisperFullMs;
        }
''',
    '''        } else {
            setProcessStage("whisper-begin:" + accumulator.config.asrBackend + ":" + durationMs);
            log("FULL_STREAMING_WHISPER_NATIVE_BEGIN", memoryDetails("whisper-native-begin")
                    .put("durationMs", durationMs)
                    .put("partial", partial)
                    .put("backend", accumulator.config.asrBackend));
            LiveWhisperSession.Result whisper = LiveWhisperSession.transcribe(asrSamples);
            setProcessStage("whisper-end:" + accumulator.config.asrBackend + ":" + durationMs);
            log("FULL_STREAMING_WHISPER_NATIVE_END", memoryDetails("whisper-native-end")
                    .put("durationMs", durationMs)
                    .put("partial", partial)
                    .put("backend", accumulator.config.asrBackend)
                    .put("whisperFullMs", whisper.whisperFullMs));
            text = whisper.text;
            segments = new JSONArray(whisper.segments.toString());
            whisperFullMs = whisper.whisperFullMs;
        }
''',
    'whisper diagnostics')

anchor = '''    private JSONObject details(Object... values) {
        JSONObject json = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try {
                json.put(String.valueOf(values[i]), values[i + 1]);
            } catch (Exception ignored) {
            }
        }
        return json;
    }
'''
helpers = anchor + '''
    private JSONObject memoryDetails(String stage) {
        JSONObject json = new JSONObject();
        appendMemory(json, stage);
        return json;
    }

    private void appendMemory(JSONObject json, String stage) {
        try {
            Runtime runtime = Runtime.getRuntime();
            json.put("memoryStage", stage);
            json.put("pssKb", Debug.getPss());
            json.put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize());
            json.put("nativeHeapSizeBytes", Debug.getNativeHeapSize());
            json.put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
            json.put("javaHeapTotalBytes", runtime.totalMemory());
            json.put("javaHeapMaxBytes", runtime.maxMemory());
        } catch (Throwable ignored) {
        }
    }

    private void setProcessStage(String stage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return;
            byte[] bytes = stage.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 120) bytes = Arrays.copyOf(bytes, 120);
            manager.setProcessStateSummary(bytes);
        } catch (Throwable ignored) {
        }
    }

    private void logPreviousStreamingExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return;
            String processName = getPackageName() + ":streaming_asr";
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    getPackageName(), 0, 12);
            for (ApplicationExitInfo info : exits) {
                if (!processName.equals(info.getProcessName())) continue;
                byte[] summary = info.getProcessStateSummary();
                String stage = summary == null ? "" : new String(summary, StandardCharsets.UTF_8);
                JSONObject row = new JSONObject()
                        .put("processName", info.getProcessName())
                        .put("reason", info.getReason())
                        .put("reasonName", exitReasonName(info.getReason()))
                        .put("status", info.getStatus())
                        .put("importance", info.getImportance())
                        .put("pssKb", info.getPss())
                        .put("rssKb", info.getRss())
                        .put("timestampMs", info.getTimestamp())
                        .put("description", info.getDescription() == null
                                ? JSONObject.NULL : info.getDescription())
                        .put("processStateSummary", stage)
                        .put("automaticFallback", false);
                log("FULL_STREAMING_PREVIOUS_PROCESS_EXIT", row);
                return;
            }
        } catch (Throwable error) {
            logError("FULL_STREAMING_PREVIOUS_EXIT_READ_FAILED", error, null);
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN_" + reason;
        }
    }
'''
s = replace_once(s, anchor, helpers, 'diagnostic helpers')
p.write_text(s)
