#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <sstream>
#include <string>

#include "whisper.h"

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
        jstring vad_model_path,
        jfloatArray pcm,
        jstring language,
        jint threads) {
    if (model_path == nullptr || vad_model_path == nullptr || pcm == nullptr || language == nullptr) {
        throw_runtime(env, "Invalid local Whisper arguments");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(pcm);
    if (sample_count <= 0) {
        throw_runtime(env, "Decoded audio is empty");
        return nullptr;
    }

    const char * model = env->GetStringUTFChars(model_path, nullptr);
    const char * vad_model = env->GetStringUTFChars(vad_model_path, nullptr);
    const char * lang = env->GetStringUTFChars(language, nullptr);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (model == nullptr || vad_model == nullptr || lang == nullptr || samples == nullptr) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
        if (vad_model != nullptr) env->ReleaseStringUTFChars(vad_model_path, vad_model);
        if (model != nullptr) env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "Unable to access local Whisper input");
        return nullptr;
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;

    const auto model_load_started = std::chrono::steady_clock::now();
    whisper_context * ctx = whisper_init_from_file_with_params(model, context_params);
    const auto model_load_finished = std::chrono::steady_clock::now();
    if (ctx == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(language, lang);
        env->ReleaseStringUTFChars(vad_model_path, vad_model);
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

    params.vad = true;
    params.vad_model_path = vad_model;
    params.vad_params = app_vad_params();

    const auto whisper_started = std::chrono::steady_clock::now();
    const int result = whisper_full(ctx, params, samples, static_cast<int>(sample_count));
    const auto whisper_finished = std::chrono::steady_clock::now();
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (result != 0) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(language, lang);
        env->ReleaseStringUTFChars(vad_model_path, vad_model);
        env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "whisper_full with VAD failed");
        return nullptr;
    }

    std::string text;
    std::ostringstream json;
    const int segment_count = whisper_full_n_segments(ctx);
    long long last_output_end_ms = 0;
    json << "{\"modelLoadMs\":" << elapsed_ms(model_load_started, model_load_finished)
         << ",\"whisperFullMs\":" << elapsed_ms(whisper_started, whisper_finished)
         << ",\"segments\":[";
    for (int i = 0; i < segment_count; ++i) {
        const char * segment_text = whisper_full_get_segment_text(ctx, i);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        const long long start_ms = t0 * 10;
        const long long end_ms = t1 * 10;
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

        if (i > 0) json << ',';
        json << "{\"startMs\":" << start_ms
             << ",\"endMs\":" << end_ms
             << ",\"durationMs\":" << std::max(0LL, end_ms - start_ms)
             << ",\"tokenCount\":" << token_count
             << ",\"avgTokenProbability\":" << average_token_probability
             << ",\"minTokenProbability\":" << min_token_probability
             << ",\"noSpeechProbability\":" << no_speech_probability
             << ",\"text\":\"" << json_escape(segment_text) << "\"}";
    }
    json << "],\"lastOutputEndMs\":" << last_output_end_ms
         << ",\"text\":\"" << json_escape(text.c_str()) << "\"}";

    const std::string output = json.str();
    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(vad_model_path, vad_model);
    env->ReleaseStringUTFChars(model_path, model);
    return env->NewStringUTF(output.c_str());
}
