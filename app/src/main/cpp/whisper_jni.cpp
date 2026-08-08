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
        jfloatArray pcm,
        jstring language,
        jint threads) {
    if (model_path == nullptr || pcm == nullptr || language == nullptr) {
        throw_runtime(env, "Invalid local Whisper arguments");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(pcm);
    if (sample_count <= 0) {
        throw_runtime(env, "Decoded audio is empty");
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

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;

    whisper_context * ctx = whisper_init_from_file_with_params(model, context_params);
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

    const int result = whisper_full(ctx, params, samples, static_cast<int>(sample_count));
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (result != 0) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(language, lang);
        env->ReleaseStringUTFChars(model_path, model);
        throw_runtime(env, "whisper_full failed");
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
    env->ReleaseStringUTFChars(model_path, model);
    return env->NewStringUTF(text.c_str());
}
