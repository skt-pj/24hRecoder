#include <jni.h>
#include <algorithm>
#include <chrono>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml-backend.h"

namespace {
std::mutex g_live_mutex;
whisper_context * g_live_ctx = nullptr;
bool g_live_gpu = false;

void throw_runtime(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

long long elapsed_ms(const std::chrono::steady_clock::time_point & start,
                     const std::chrono::steady_clock::time_point & end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
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

bool has_gpu_backend() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
    }
    return false;
}

void close_locked() {
    if (g_live_ctx != nullptr) {
        whisper_free(g_live_ctx);
        g_live_ctx = nullptr;
    }
    g_live_gpu = false;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sktpj_recorder24h_transcription_LiveWhisperSession_nativeLiveWhisperOpen(
        JNIEnv * env, jclass, jstring model_path, jboolean use_gpu) {
    if (model_path == nullptr) {
        throw_runtime(env, "Live Whisper model path is null");
        return -1;
    }
    const char * model = env->GetStringUTFChars(model_path, nullptr);
    if (model == nullptr) return -1;
    std::lock_guard<std::mutex> lock(g_live_mutex);
    close_locked();
    if (use_gpu == JNI_TRUE && !has_gpu_backend()) {
        env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "Selected Vulkan GPU backend is unavailable");
        return -1;
    }
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu == JNI_TRUE;
    params.flash_attn = false;
    const auto started = std::chrono::steady_clock::now();
    g_live_ctx = whisper_init_from_file_with_params(model, params);
    const auto finished = std::chrono::steady_clock::now();
    env->ReleaseStringUTFChars(model_path, model);
    if (g_live_ctx == nullptr) {
        throw_runtime(env, "Unable to load live Whisper model");
        return -1;
    }
    g_live_gpu = use_gpu == JNI_TRUE;
    return static_cast<jlong>(elapsed_ms(started, finished));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sktpj_recorder24h_transcription_LiveWhisperSession_nativeLiveWhisperTranscribe(
        JNIEnv * env, jclass, jfloatArray pcm, jstring language, jint threads) {
    if (pcm == nullptr || language == nullptr) {
        throw_runtime(env, "Invalid live Whisper arguments");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(pcm);
    if (sample_count <= 0) {
        return env->NewStringUTF("{\"segments\":[],\"whisperFullMs\":0,\"lastOutputEndMs\":0,\"text\":\"\"}");
    }
    const char * lang = env->GetStringUTFChars(language, nullptr);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (lang == nullptr || samples == nullptr) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
        throw_runtime(env, "Unable to access live Whisper input");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_live_mutex);
    if (g_live_ctx == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(language, lang);
        throw_runtime(env, "Live Whisper context is not open");
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.translate = false;
    params.no_context = true;
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
    params.vad = false;

    const auto started = std::chrono::steady_clock::now();
    const int rc = whisper_full(g_live_ctx, params, samples, static_cast<int>(sample_count));
    const auto finished = std::chrono::steady_clock::now();
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (rc != 0) {
        throw_runtime(env, "whisper_full for live utterance failed");
        return nullptr;
    }

    std::string text;
    std::ostringstream json;
    long long last_end_ms = 0;
    json << "{\"backend\":\"" << (g_live_gpu ? "whisper-vulkan" : "whisper-cpu") << "\",\"segments\":[";
    const int segment_count = whisper_full_n_segments(g_live_ctx);
    for (int i = 0; i < segment_count; ++i) {
        const char * segment_text = whisper_full_get_segment_text(g_live_ctx, i);
        const long long start_ms = std::max(0LL, static_cast<long long>(whisper_full_get_segment_t0(g_live_ctx, i)) * 10LL);
        const long long end_ms = std::max(start_ms, static_cast<long long>(whisper_full_get_segment_t1(g_live_ctx, i)) * 10LL);
        last_end_ms = std::max(last_end_ms, end_ms);
        if (segment_text != nullptr) text += segment_text;
        const int token_count = whisper_full_n_tokens(g_live_ctx, i);
        double token_sum = 0.0;
        double token_min = 1.0;
        for (int token = 0; token < token_count; ++token) {
            const double p = whisper_full_get_token_p(g_live_ctx, i, token);
            token_sum += p;
            token_min = std::min(token_min, p);
        }
        if (token_count == 0) token_min = 0.0;
        if (i > 0) json << ',';
        json << "{\"startMs\":" << start_ms
             << ",\"endMs\":" << end_ms
             << ",\"durationMs\":" << std::max(0LL, end_ms - start_ms)
             << ",\"tokenCount\":" << token_count
             << ",\"avgTokenProbability\":" << (token_count > 0 ? token_sum / token_count : 0.0)
             << ",\"minTokenProbability\":" << token_min
             << ",\"noSpeechProbability\":" << whisper_full_get_segment_no_speech_prob(g_live_ctx, i)
             << ",\"text\":\"" << json_escape(segment_text) << "\"}";
    }
    json << "],\"whisperFullMs\":" << elapsed_ms(started, finished)
         << ",\"lastOutputEndMs\":" << last_end_ms
         << ",\"text\":\"" << json_escape(text.c_str()) << "\"}";
    return env->NewStringUTF(json.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sktpj_recorder24h_transcription_LiveWhisperSession_nativeLiveWhisperClose(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_live_mutex);
    close_locked();
}
