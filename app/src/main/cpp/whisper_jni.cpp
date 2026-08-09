#include <jni.h>
#include <string>

#include "whisper.h"

namespace {
void throw_runtime(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sktpj_recorder24h_transcription_LocalWhisperEngine_nativeTranscribe(
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

    // Conservative decoding guardrails for long-lived ambient recording. The important change is
    // VAD: only speech detected by Silero is passed to Whisper, instead of forcing Whisper to
    // invent text for long stretches of silence and household noise.
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
    const int segment_count = whisper_full_n_segments(ctx);
    for (int i = 0; i < segment_count; ++i) {
        const char * segment_text = whisper_full_get_segment_text(ctx, i);
        if (segment_text != nullptr) {
            text += segment_text;
        }
    }

    whisper_free(ctx);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(vad_model_path, vad_model);
    env->ReleaseStringUTFChars(model_path, model);
    return env->NewStringUTF(text.c_str());
}
