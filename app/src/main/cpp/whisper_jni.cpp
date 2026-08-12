#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <sstream>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"
#include "ggml-backend.h"

namespace {
void throw_runtime(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

std::string json_escape(const char * value) {
    std::string out;
    if (value == nullptr) return out;
    for (const unsigned char ch : std::string(value)) {
        switch (ch) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (ch < 0x20) {
                    const char * hex = "0123456789abcdef";
                    out += "\\u00";
                    out += hex[(ch >> 4) & 0x0f];
                    out += hex[ch & 0x0f];
                } else {
                    out += static_cast<char>(ch);
                }
        }
    }
    return out;
}

long long elapsed_ms(const std::chrono::steady_clock::time_point & start,
                     const std::chrono::steady_clock::time_point & end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

bool app_has_gpu_backend() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            return true;
        }
    }
    return false;
}

std::mutex g_stream_vad_mutex;
whisper_vad_context * g_stream_vad_ctx = nullptr;
std::vector<float> g_stream_vad_pending;
constexpr int k_stream_vad_window_samples = 512;

void close_stream_vad_locked() {
    if (g_stream_vad_ctx != nullptr) {
        whisper_vad_free(g_stream_vad_ctx);
        g_stream_vad_ctx = nullptr;
    }
    g_stream_vad_pending.clear();
}

whisper_vad_params app_vad_params() {
    whisper_vad_params params = whisper_vad_default_params();
    params.threshold = 0.5f;
    params.min_speech_duration_ms = 250;
    params.min_silence_duration_ms = 200;
    params.max_speech_duration_s = 30.0f;
    params.speech_pad_ms = 80;
    params.samples_overlap = 0.10f;
    return params;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sktpj_recorder24h_transcription_StreamingVadStore_nativeStreamingVadOpen(
        JNIEnv * env,
        jclass,
        jstring vad_model_path,
        jint threads) {
    if (vad_model_path == nullptr) return JNI_FALSE;
    const char * vad_model = env->GetStringUTFChars(vad_model_path, nullptr);
    if (vad_model == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_stream_vad_mutex);
    close_stream_vad_locked();
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = threads > 0 ? threads : 1;
    params.use_gpu = false;
    g_stream_vad_ctx = whisper_vad_init_from_file_with_params(vad_model, params);
    env->ReleaseStringUTFChars(vad_model_path, vad_model);
    if (g_stream_vad_ctx == nullptr) return JNI_FALSE;
    whisper_vad_reset_state(g_stream_vad_ctx);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sktpj_recorder24h_transcription_StreamingVadStore_nativeStreamingVadProcess(
        JNIEnv * env,
        jclass,
        jfloatArray pcm) {
    if (pcm == nullptr) {
        throw_runtime(env, "Streaming VAD input is null");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_stream_vad_mutex);
    if (g_stream_vad_ctx == nullptr) {
        throw_runtime(env, "Streaming VAD is not open");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(pcm);
    std::vector<float> incoming(static_cast<size_t>(count));
    if (count > 0) env->GetFloatArrayRegion(pcm, 0, count, incoming.data());
    if (env->ExceptionCheck()) return nullptr;
    g_stream_vad_pending.insert(g_stream_vad_pending.end(), incoming.begin(), incoming.end());
    const size_t full_samples = (g_stream_vad_pending.size() / k_stream_vad_window_samples)
            * k_stream_vad_window_samples;
    std::ostringstream json;
    json << "{\"windowSamples\":" << k_stream_vad_window_samples << ",\"probabilities\":[";
    if (full_samples > 0) {
        std::vector<float> ready(g_stream_vad_pending.begin(), g_stream_vad_pending.begin() + full_samples);
        g_stream_vad_pending.erase(g_stream_vad_pending.begin(), g_stream_vad_pending.begin() + full_samples);
        if (!whisper_vad_detect_speech_no_reset(g_stream_vad_ctx, ready.data(), static_cast<int>(ready.size()))) {
            throw_runtime(env, "Streaming Silero VAD pass failed");
            return nullptr;
        }
        const int n = whisper_vad_n_probs(g_stream_vad_ctx);
        float * probs = whisper_vad_probs(g_stream_vad_ctx);
        for (int i = 0; i < n; ++i) {
            if (i > 0) json << ',';
            json << (probs == nullptr ? 0.0f : probs[i]);
        }
    }
    json << "]}";
    const std::string output = json.str();
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sktpj_recorder24h_transcription_StreamingVadStore_nativeStreamingVadClose(
        JNIEnv *,
        jclass) {
    std::lock_guard<std::mutex> lock(g_stream_vad_mutex);
    close_stream_vad_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sktpj_recorder24h_transcription_LocalWhisperEngine_nativeAnalyzeVadDetailed(
        JNIEnv * env,
        jclass,
        jstring vad_model_path,
        jfloatArray pcm,
        jint threads) {
    if (vad_model_path == nullptr || pcm == nullptr) {
        throw_runtime(env, "Invalid VAD diagnostic arguments");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(pcm);
    if (sample_count <= 0) {
        throw_runtime(env, "VAD diagnostic audio is empty");
        return nullptr;
    }

    const char * vad_model = env->GetStringUTFChars(vad_model_path, nullptr);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (vad_model == nullptr || samples == nullptr) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        if (vad_model != nullptr) env->ReleaseStringUTFChars(vad_model_path, vad_model);
        throw_runtime(env, "Unable to access VAD diagnostic input");
        return nullptr;
    }

    const auto init_started = std::chrono::steady_clock::now();
    whisper_vad_context_params context_params = whisper_vad_default_context_params();
    context_params.n_threads = threads > 0 ? threads : 1;
    context_params.use_gpu = false;
    whisper_vad_context * vctx = whisper_vad_init_from_file_with_params(vad_model, context_params);
    const auto init_finished = std::chrono::steady_clock::now();
    if (vctx == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(vad_model_path, vad_model);
        throw_runtime(env, "Unable to load VAD model for diagnostics");
        return nullptr;
    }

    const auto detect_started = std::chrono::steady_clock::now();
    const bool detected = whisper_vad_detect_speech(vctx, samples, static_cast<int>(sample_count));
    const auto detect_finished = std::chrono::steady_clock::now();
    if (!detected) {
        whisper_vad_free(vctx);
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(vad_model_path, vad_model);
        throw_runtime(env, "Silero VAD diagnostic pass failed");
        return nullptr;
    }

    const int probability_count = whisper_vad_n_probs(vctx);
    float * probabilities = whisper_vad_probs(vctx);
    double probability_sum = 0.0;
    double probability_max = 0.0;
    int above_threshold = 0;
    if (probabilities != nullptr) {
        for (int i = 0; i < probability_count; ++i) {
            const double value = probabilities[i];
            probability_sum += value;
            probability_max = std::max(probability_max, value);
            if (value >= 0.5) ++above_threshold;
        }
    }

    whisper_vad_params params = app_vad_params();
    whisper_vad_segments * segments = whisper_vad_segments_from_probs(vctx, params);
    if (segments == nullptr) {
        whisper_vad_free(vctx);
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(vad_model_path, vad_model);
        throw_runtime(env, "Unable to build VAD diagnostic segments");
        return nullptr;
    }

    const int segment_count = whisper_vad_segments_n_segments(segments);
    long long total_speech_ms = 0;
    long long last_end_ms = 0;
    std::ostringstream json;
    json << "{\"vadInitMs\":" << elapsed_ms(init_started, init_finished)
         << ",\"vadDetectMs\":" << elapsed_ms(detect_started, detect_finished)
         << ",\"probabilityCount\":" << probability_count
         << ",\"meanSpeechProbability\":"
         << (probability_count > 0 ? probability_sum / probability_count : 0.0)
         << ",\"maxSpeechProbability\":" << probability_max
         << ",\"aboveThresholdFraction\":"
         << (probability_count > 0 ? above_threshold / static_cast<double>(probability_count) : 0.0)
         << ",\"threshold\":0.5"
         << ",\"timebase\":\"whisper-vad-centiseconds-to-ms\""
         << ",\"segmentCount\":" << segment_count
         << ",\"segments\":[";

    for (int i = 0; i < segment_count; ++i) {
        // whisper.cpp stores/returns VAD segment timestamps in centiseconds.
        // Convert cs -> ms by multiplying by 10 (not 1000).
        const float t0_centiseconds = whisper_vad_segments_get_segment_t0(segments, i);
        const float t1_centiseconds = whisper_vad_segments_get_segment_t1(segments, i);
        const long long start_ms = std::llround(t0_centiseconds * 10.0);
        const long long end_ms = std::llround(t1_centiseconds * 10.0);
        total_speech_ms += std::max(0LL, end_ms - start_ms);
        last_end_ms = std::max(last_end_ms, end_ms);
        if (i > 0) json << ',';
        json << "{\"index\":" << i
             << ",\"startMs\":" << start_ms
             << ",\"endMs\":" << end_ms
             << ",\"durationMs\":" << std::max(0LL, end_ms - start_ms)
             << "}";
    }
    json << "],\"totalSpeechMs\":" << total_speech_ms
         << ",\"lastEndMs\":" << last_end_ms
         << "}";

    whisper_vad_free_segments(segments);
    whisper_vad_free(vctx);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(vad_model_path, vad_model);

    const std::string output = json.str();
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sktpj_recorder24h_transcription_LocalWhisperEngine_nativeTranscribeDetailed(
        JNIEnv * env,
        jclass,
        jstring model_path,
        jfloatArray pcm,
        jintArray chunk_starts_ms,
        jintArray chunk_ends_ms,
        jstring language,
        jint threads,
        jboolean use_gpu) {
    if (model_path == nullptr || pcm == nullptr || chunk_starts_ms == nullptr ||
            chunk_ends_ms == nullptr || language == nullptr) {
        throw_runtime(env, "Invalid local Whisper arguments");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(pcm);
    if (sample_count <= 0) {
        throw_runtime(env, "Decoded audio is empty");
        return nullptr;
    }

    const jsize chunk_count = env->GetArrayLength(chunk_starts_ms);
    if (chunk_count != env->GetArrayLength(chunk_ends_ms)) {
        throw_runtime(env, "Speech chunk arrays have different lengths");
        return nullptr;
    }
    if (chunk_count <= 0) {
        const std::string empty = "{\"modelLoadMs\":0,\"whisperFullMs\":0,\"segments\":[],\"lastOutputEndMs\":0,\"text\":\"\"}";
        return env->NewStringUTF(empty.c_str());
    }

    std::vector<jint> starts(static_cast<size_t>(chunk_count));
    std::vector<jint> ends(static_cast<size_t>(chunk_count));
    env->GetIntArrayRegion(chunk_starts_ms, 0, chunk_count, starts.data());
    env->GetIntArrayRegion(chunk_ends_ms, 0, chunk_count, ends.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    const char * model = env->GetStringUTFChars(model_path, nullptr);
    const char * lang = env->GetStringUTFChars(language, nullptr);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (model == nullptr || lang == nullptr || samples == nullptr) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
        if (model != nullptr) env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "Unable to access local Whisper input");
        return nullptr;
    }

    if (use_gpu == JNI_TRUE && !app_has_gpu_backend()) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(language, lang);
        env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "Selected Vulkan GPU backend is unavailable");
        return nullptr;
    }
    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = use_gpu == JNI_TRUE;
    context_params.flash_attn = false;

    const auto model_load_started = std::chrono::steady_clock::now();
    whisper_context * ctx = whisper_init_from_file_with_params(model, context_params);
    const auto model_load_finished = std::chrono::steady_clock::now();
    if (ctx == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(language, lang);
        env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "Unable to load Whisper model");
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = lang;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.2f;
    params.entropy_thold = 2.4f;
    params.logprob_thold = -1.0f;
    params.no_speech_thold = 0.6f;
    // VAD already ran before the Whisper model was loaded. Only the selected speech chunks
    // reach whisper_full(), so do not run Whisper's internal VAD a second time.
    params.vad = false;

    std::string text;
    std::ostringstream json;
    long long whisper_full_ms = 0;
    long long last_output_end_ms = 0;
    bool first_output_segment = true;
    json << "{\"modelLoadMs\":" << elapsed_ms(model_load_started, model_load_finished)
         << ",\"backend\":\"" << (use_gpu == JNI_TRUE ? "whisper-vulkan" : "whisper-cpu") << "\""
         << ",\"segments\":[";

    for (jsize chunk_index = 0; chunk_index < chunk_count; ++chunk_index) {
        const long long requested_start_ms = std::max(0, starts[static_cast<size_t>(chunk_index)]);
        const long long requested_end_ms = std::max(0, ends[static_cast<size_t>(chunk_index)]);
        long long start_sample = requested_start_ms * 16LL;
        long long end_sample = requested_end_ms * 16LL;
        start_sample = std::max(0LL, std::min(start_sample, static_cast<long long>(sample_count)));
        end_sample = std::max(0LL, std::min(end_sample, static_cast<long long>(sample_count)));
        if (end_sample <= start_sample) continue;

        const long long chunk_start_ms = start_sample / 16LL;
        const long long chunk_end_ms = end_sample / 16LL;
        const int chunk_sample_count = static_cast<int>(end_sample - start_sample);

        const auto whisper_started = std::chrono::steady_clock::now();
        const int result = whisper_full(ctx, params, samples + start_sample, chunk_sample_count);
        const auto whisper_finished = std::chrono::steady_clock::now();
        whisper_full_ms += elapsed_ms(whisper_started, whisper_finished);
        if (result != 0) {
            whisper_free(ctx);
            env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
            env->ReleaseStringUTFChars(language, lang);
            env->ReleaseStringUTFChars(model_path, model);
            throw_runtime(env, "whisper_full for speech chunk failed");
            return nullptr;
        }

        const int segment_count = whisper_full_n_segments(ctx);
        for (int i = 0; i < segment_count; ++i) {
            const char * segment_text = whisper_full_get_segment_text(ctx, i);
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
            const long long relative_start_ms = std::max(0LL, static_cast<long long>(t0) * 10LL);
            const long long relative_end_ms = std::max(0LL, static_cast<long long>(t1) * 10LL);
            const long long start_ms = std::max(chunk_start_ms,
                    std::min(chunk_end_ms, chunk_start_ms + relative_start_ms));
            const long long end_ms = std::max(start_ms,
                    std::min(chunk_end_ms, chunk_start_ms + relative_end_ms));
            last_output_end_ms = std::max(last_output_end_ms, end_ms);
            if (segment_text != nullptr) text += segment_text;

            const int token_count = whisper_full_n_tokens(ctx, i);
            double token_probability_sum = 0.0;
            double min_token_probability = 1.0;
            for (int token_index = 0; token_index < token_count; ++token_index) {
                const double probability = whisper_full_get_token_p(ctx, i, token_index);
                token_probability_sum += probability;
                min_token_probability = std::min(min_token_probability, probability);
            }
            const double average_token_probability = token_count > 0
                    ? token_probability_sum / token_count : 0.0;
            if (token_count == 0) min_token_probability = 0.0;
            const double no_speech_probability = whisper_full_get_segment_no_speech_prob(ctx, i);

            if (!first_output_segment) json << ',';
            first_output_segment = false;
            json << "{\"startMs\":" << start_ms
                 << ",\"endMs\":" << end_ms
                 << ",\"durationMs\":" << std::max(0LL, end_ms - start_ms)
                 << ",\"sourceChunkIndex\":" << chunk_index
                 << ",\"sourceChunkStartMs\":" << chunk_start_ms
                 << ",\"sourceChunkEndMs\":" << chunk_end_ms
                 << ",\"tokenCount\":" << token_count
                 << ",\"avgTokenProbability\":" << average_token_probability
                 << ",\"minTokenProbability\":" << min_token_probability
                 << ",\"noSpeechProbability\":" << no_speech_probability
                 << ",\"text\":\"" << json_escape(segment_text) << "\"}";
        }
    }

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    json << "],\"whisperFullMs\":" << whisper_full_ms
         << ",\"lastOutputEndMs\":" << last_output_end_ms
         << ",\"text\":\"" << json_escape(text.c_str()) << "\"}";

    const std::string output = json.str();
    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(model_path, model);
    return env->NewStringUTF(output.c_str());
}
