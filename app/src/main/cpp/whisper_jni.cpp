#include <jni.h>
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

    whisper_context * ctx = whisper_init_from_file_with_params(model, context_params);
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
    params.vad_params.threshold = 0.5f;
    params.vad_params.min_speech_duration_ms = 250;
    params.vad_params.min_silence_duration_ms = 200;
    params.vad_params.max_speech_duration_s = 30.0f;
    params.vad_params.speech_pad_ms = 80;
    params.vad_params.samples_overlap = 0.10f;

    const int result = whisper_full(ctx, params, samples, static_cast<int>(sample_count));
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
    json << "{\"segments\":[";
    const int segment_count = whisper_full_n_segments(ctx);
    for (int i = 0; i < segment_count; ++i) {
        const char * segment_text = whisper_full_get_segment_text(ctx, i);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        if (segment_text != nullptr) text += segment_text;
        if (i > 0) json << ',';
        json << "{\"startMs\":" << (t0 * 10)
             << ",\"endMs\":" << (t1 * 10)
             << ",\"text\":\"" << json_escape(segment_text) << "\"}";
    }
    json << "],\"text\":\"" << json_escape(text.c_str()) << "\"}";

    const std::string output = json.str();
    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(vad_model_path, vad_model);
    env->ReleaseStringUTFChars(model_path, model);
    return env->NewStringUTF(output.c_str());
}
