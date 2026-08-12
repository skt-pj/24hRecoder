from pathlib import Path
import re


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing target: {label}")
    return text.replace(old, new, 1)

# version
p = Path('app/build.gradle')
s = p.read_text()
s = replace_once(s, "        versionCode 1017\n        versionName '0.7.17'\n", "        versionCode 1018\n        versionName '0.7.18'\n", 'version')
s = s.replace(
    '        // The persisted transcription backlog has an explicit user-controlled pause/resume state.\n',
    '        // The persisted transcription backlog has explicit pause/resume; pause aborts current postprocess inference.\n',
)
p.write_text(s)

# scheduler: pause now cancels the active normal transcription
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionScheduler.java')
s = p.read_text()
s = replace_once(
    s,
    '''        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n                .edit()\n                .putBoolean(KEY_QUEUE_PAUSED, paused)\n                .commit();\n        try {\n''',
    '''        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n                .edit()\n                .putBoolean(KEY_QUEUE_PAUSED, paused)\n                .commit();\n        long cancellationGeneration = paused ? TranscriptionCancellation.cancelCurrent() : -1L;\n        try {\n''',
    'scheduler cancel call',
)
s = s.replace('            details.put("runningItemAllowedToFinish", true);\n',
              '            details.put("runningItemCancelledOnPause", paused);\n            details.put("cancellationGeneration", cancellationGeneration);\n')
p.write_text(s)

# UI text
p = Path('app/src/main/java/com/sktpj/recorder24h/UnifiedQueueScreen.kt')
s = p.read_text()
s = s.replace(
    '"待機中の項目は保持します。実行中の1件がある場合は完了後に停止します。"',
    '"待機中の項目は保持します。実行中の通常文字起こしも中断して待機へ戻します。"',
)
p.write_text(s)

# native Whisper cancellation using whisper.cpp abort_callback
p = Path('app/src/main/cpp/whisper_jni.cpp')
s = p.read_text()
s = replace_once(s, '#include <algorithm>\n', '#include <algorithm>\n#include <atomic>\n', 'atomic include')
s = replace_once(
    s,
    'std::mutex g_stream_vad_mutex;\n',
    '''std::atomic<long long> g_postprocess_cancel_generation{0};\n\nstruct PostprocessAbortContext {\n    long long expected_generation;\n};\n\nbool postprocess_abort_callback(void * user_data) {\n    auto * state = static_cast<PostprocessAbortContext *>(user_data);\n    return state != nullptr\n            && g_postprocess_cancel_generation.load(std::memory_order_relaxed) != state->expected_generation;\n}\n\nbool postprocess_cancelled(long long expected_generation) {\n    return g_postprocess_cancel_generation.load(std::memory_order_relaxed) != expected_generation;\n}\n\nstd::mutex g_stream_vad_mutex;\n''',
    'native cancel globals',
)
marker = 'extern "C" JNIEXPORT jstring JNICALL\nJava_com_sktpj_recorder24h_transcription_LocalWhisperEngine_nativeTranscribeDetailed('
s = replace_once(
    s,
    marker,
    '''extern "C" JNIEXPORT void JNICALL\nJava_com_sktpj_recorder24h_transcription_LocalWhisperEngine_nativeSetPostprocessCancellationGeneration(\n        JNIEnv *, jclass, jlong generation) {\n    g_postprocess_cancel_generation.store(static_cast<long long>(generation), std::memory_order_relaxed);\n}\n\n''' + marker,
    'native cancel setter',
)
s = replace_once(
    s,
    '        jstring language,\n        jint threads,\n        jboolean use_gpu) {\n',
    '        jstring language,\n        jint threads,\n        jboolean use_gpu,\n        jlong cancellation_generation) {\n',
    'native transcribe signature',
)
s = replace_once(
    s,
    '''    if (model_path == nullptr || pcm == nullptr || chunk_starts_ms == nullptr ||\n            chunk_ends_ms == nullptr || language == nullptr) {\n''',
    '''    const long long expected_generation = static_cast<long long>(cancellation_generation);\n    if (postprocess_cancelled(expected_generation)) {\n        throw_runtime(env, "POSTPROCESS_TRANSCRIPTION_CANCELLED");\n        return nullptr;\n    }\n    if (model_path == nullptr || pcm == nullptr || chunk_starts_ms == nullptr ||\n            chunk_ends_ms == nullptr || language == nullptr) {\n''',
    'native early cancel',
)
s = replace_once(
    s,
    '''    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);\n''',
    '''    if (postprocess_cancelled(expected_generation)) {\n        whisper_free(ctx);\n        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);\n        env->ReleaseStringUTFChars(language, lang);\n        env->ReleaseStringUTFChars(model_path, model);\n        throw_runtime(env, "POSTPROCESS_TRANSCRIPTION_CANCELLED");\n        return nullptr;\n    }\n\n    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);\n''',
    'native post-load cancel',
)
s = replace_once(
    s,
    '''    params.vad = false;\n\n    std::string text;\n''',
    '''    params.vad = false;\n    PostprocessAbortContext abort_context{expected_generation};\n    params.abort_callback = postprocess_abort_callback;\n    params.abort_callback_user_data = &abort_context;\n\n    std::string text;\n''',
    'native abort callback params',
)
s = replace_once(
    s,
    '''    for (jsize chunk_index = 0; chunk_index < chunk_count; ++chunk_index) {\n        const long long requested_start_ms''',
    '''    for (jsize chunk_index = 0; chunk_index < chunk_count; ++chunk_index) {\n        if (postprocess_cancelled(expected_generation)) {\n            whisper_free(ctx);\n            env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);\n            env->ReleaseStringUTFChars(language, lang);\n            env->ReleaseStringUTFChars(model_path, model);\n            throw_runtime(env, "POSTPROCESS_TRANSCRIPTION_CANCELLED");\n            return nullptr;\n        }\n        const long long requested_start_ms''',
    'native chunk precheck',
)
s = replace_once(
    s,
    '''        if (result != 0) {\n            whisper_free(ctx);\n            env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);\n            env->ReleaseStringUTFChars(language, lang);\n            env->ReleaseStringUTFChars(model_path, model);\n            throw_runtime(env, "whisper_full for speech chunk failed");\n            return nullptr;\n        }\n''',
    '''        if (result != 0) {\n            const bool cancelled = postprocess_cancelled(expected_generation);\n            whisper_free(ctx);\n            env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);\n            env->ReleaseStringUTFChars(language, lang);\n            env->ReleaseStringUTFChars(model_path, model);\n            throw_runtime(env, cancelled\n                    ? "POSTPROCESS_TRANSCRIPTION_CANCELLED"\n                    : "whisper_full for speech chunk failed");\n            return nullptr;\n        }\n''',
    'native result cancellation',
)
p.write_text(s)

# LocalWhisperEngine passes one cancellation token through the whole postprocess item
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/LocalWhisperEngine.java')
s = p.read_text()
old = '''    public static synchronized Response transcribe(Context context, File audioFile,\n                                                   String modelId,\n                                                   TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {\n        TranscriptionPipelineSettings.requireRunnable(context, pipeline, modelId);\n'''
new = '''    public static synchronized Response transcribe(Context context, File audioFile,\n                                                   String modelId,\n                                                   TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {\n        return transcribe(context, audioFile, modelId, pipeline, TranscriptionCancellation.snapshot());\n    }\n\n    static Response transcribe(Context context, File audioFile, String modelId,\n                               TranscriptionPipelineSettings.Snapshot pipeline,\n                               long cancellationToken) throws Exception {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        TranscriptionPipelineSettings.requireRunnable(context, pipeline, modelId);\n'''
s = replace_once(s, old, new, 'engine token overload')
s = replace_once(s, '        PreparedAudio prepared = prepareAudio(audioFile);\n',
                 '        PreparedAudio prepared = prepareAudio(audioFile);\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n', 'after decode')
s = replace_once(s,
    '                streaming = StreamingVadStore.analyzeOffline(context, prepared.frontEnd.samples);\n',
    '                streaming = StreamingVadStore.analyzeOffline(context, prepared.frontEnd.samples, cancellationToken);\n',
    'streaming vad token')
s = replace_once(s,
    '        return transcribePrepared(context, prepared, modelId, vad, gate, false, segmentId, pipeline);\n',
    '        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        return transcribePrepared(context, prepared, modelId, vad, gate, false, segmentId, pipeline, cancellationToken);\n',
    'engine prepared call')
s = s.replace(
    '                RealtimeSpeechGateStore.Snapshot.missing(), false, null, comparison);',
    '                RealtimeSpeechGateStore.Snapshot.missing(), false, null, comparison, TranscriptionCancellation.snapshot());',
)
s = replace_once(s,
    '''                                               boolean skippedByActivityGate,\n                                               String segmentId,\n                                               TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {\n''',
    '''                                               boolean skippedByActivityGate,\n                                               String segmentId,\n                                               TranscriptionPipelineSettings.Snapshot pipeline,\n                                               long cancellationToken) throws Exception {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n''',
    'private prepared token')
s = replace_once(s,
    '''            DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(\n                    context,\n                    segmentId,\n                    prepared.frontEnd.samples,\n                    chunks.startsMs,\n                    chunks.endsMs,\n                    prepared.frontEnd.snrProxyDb);\n            asrSamples = denoise.samples;\n''',
    '''            DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(\n                    context,\n                    segmentId,\n                    prepared.frontEnd.samples,\n                    chunks.startsMs,\n                    chunks.endsMs,\n                    prepared.frontEnd.snrProxyDb,\n                    cancellationToken);\n            asrSamples = denoise.samples;\n            TranscriptionCancellation.throwIfCancelled(cancellationToken);\n''',
    'denoise token')
s = replace_once(s,
    '            AndroidOnDeviceAsr.Result androidResult = AndroidOnDeviceAsr.transcribe(\n                    context, asrSamples, chunks.startsMs, chunks.endsMs);\n',
    '            AndroidOnDeviceAsr.Result androidResult = AndroidOnDeviceAsr.transcribe(\n                    context, asrSamples, chunks.startsMs, chunks.endsMs, cancellationToken);\n',
    'android token')
s = replace_once(s,
    '''            String raw = nativeTranscribeDetailed(model.getAbsolutePath(), asrSamples,\n                    chunks.startsMs, chunks.endsMs, LANGUAGE, threads, useGpu);\n''',
    '''            String raw = nativeTranscribeDetailed(model.getAbsolutePath(), asrSamples,\n                    chunks.startsMs, chunks.endsMs, LANGUAGE, threads, useGpu, cancellationToken);\n''',
    'native token call')
s = replace_once(s,
    '        long inferenceMs = System.currentTimeMillis() - inferenceStarted;\n',
    '        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        long inferenceMs = System.currentTimeMillis() - inferenceStarted;\n',
    'post asr check')
s = replace_once(s,
    '''    private static native String nativeTranscribeDetailed(String modelPath, float[] pcm,\n                                                           int[] chunkStartsMs, int[] chunkEndsMs,\n                                                           String language, int threads, boolean useGpu);\n''',
    '''    static void setNativePostprocessCancellationGeneration(long generation) {\n        nativeSetPostprocessCancellationGeneration(generation);\n    }\n\n    private static native void nativeSetPostprocessCancellationGeneration(long generation);\n\n    private static native String nativeTranscribeDetailed(String modelPath, float[] pcm,\n                                                           int[] chunkStartsMs, int[] chunkEndsMs,\n                                                           String language, int threads, boolean useGpu,\n                                                           long cancellationGeneration);\n''',
    'native declarations')
p.write_text(s)

# Streaming VAD offline replay checks cancellation every 100 ms of audio
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/StreamingVadStore.java')
s = p.read_text()
s = replace_once(s,
    '''    public static Snapshot analyzeOffline(Context context, float[] samples) throws Exception {\n        if (samples == null || samples.length == 0) {\n''',
    '''    public static Snapshot analyzeOffline(Context context, float[] samples) throws Exception {\n        return analyzeOffline(context, samples, TranscriptionCancellation.snapshot());\n    }\n\n    static Snapshot analyzeOffline(Context context, float[] samples, long cancellationToken) throws Exception {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        if (samples == null || samples.length == 0) {\n''',
    'stream vad overload')
s = replace_once(s,
    '''                while (offset < samples.length) {\n                    int count = Math.min(1600, samples.length - offset);\n''',
    '''                while (offset < samples.length) {\n                    TranscriptionCancellation.throwIfCancelled(cancellationToken);\n                    int count = Math.min(1600, samples.length - offset);\n''',
    'stream vad loop check')
s = replace_once(s,
    '''                long durationUs = samples.length * 1_000_000L / SAMPLE_RATE;\n''',
    '''                TranscriptionCancellation.throwIfCancelled(cancellationToken);\n                long durationUs = samples.length * 1_000_000L / SAMPLE_RATE;\n''',
    'stream vad final check')
p.write_text(s)

# DeepFilterNet cancellation between load waits, chunks and native frames
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/DeepFilterNetSpeechDenoiser.kt')
s = p.read_text()
s = replace_once(s,
    '''    @JvmStatic\n    fun denoise(\n        context: Context,\n        segmentId: String?,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        inputSnrProxyDb: Double,\n    ): Result {\n        val startedAt = System.currentTimeMillis()\n''',
    '''    @JvmStatic\n    fun denoise(\n        context: Context,\n        segmentId: String?,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        inputSnrProxyDb: Double,\n    ): Result = denoise(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb, TranscriptionCancellation.snapshot())\n\n    @JvmStatic\n    fun denoise(\n        context: Context,\n        segmentId: String?,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        inputSnrProxyDb: Double,\n        cancellationToken: Long,\n    ): Result {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n        val startedAt = System.currentTimeMillis()\n''',
    'denoise overload')
s = replace_once(s,
    '''            if (!loadLatch.await(MODEL_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {\n                loadTimedOut.set(true)\n                instance.release()\n                return fallback(\n''',
    '''            val loadDeadline = System.currentTimeMillis() + MODEL_LOAD_TIMEOUT_MS\n            while (!loadLatch.await(100L, TimeUnit.MILLISECONDS)) {\n                TranscriptionCancellation.throwIfCancelled(cancellationToken)\n                if (System.currentTimeMillis() >= loadDeadline) {\n                    loadTimedOut.set(true)\n                    instance.release()\n                    return fallback(\n''',
    'denoise load poll start')
s = replace_once(s,
    '''                    "model-load-timeout", null,\n                )\n            }\n\n            val frameBytes''',
    '''                        "model-load-timeout", null,\n                    )\n                }\n            }\n            TranscriptionCancellation.throwIfCancelled(cancellationToken)\n\n            val frameBytes''',
    'denoise load poll close')
s = replace_once(s,
    '''            for (index in startsMs.indices) {\n                if (index >= endsMs.size) break\n''',
    '''            for (index in startsMs.indices) {\n                TranscriptionCancellation.throwIfCancelled(cancellationToken)\n                if (index >= endsMs.size) break\n''',
    'denoise chunk check')
s = s.replace('                    flushState(instance, frameBytes)\n', '                    flushState(instance, frameBytes, cancellationToken)\n')
s = s.replace('                val processed = processRegion(instance, frameBytes, region)\n', '                val processed = processRegion(instance, frameBytes, region, cancellationToken)\n')
s = replace_once(s,
    '''        } catch (error: Throwable) {\n            fallback(\n''',
    '''        } catch (error: Throwable) {\n            if (TranscriptionCancellation.isCancellation(error)) throw error\n            fallback(\n''',
    'denoise rethrow cancel')
s = replace_once(s,
    '''    @JvmStatic\n    fun denoiseSelected(\n        context: Context,\n        segmentId: String?,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        inputSnrProxyDb: Double,\n    ): Result {\n        val result = denoise(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb)\n''',
    '''    @JvmStatic\n    fun denoiseSelected(\n        context: Context,\n        segmentId: String?,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        inputSnrProxyDb: Double,\n    ): Result = denoiseSelected(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb, TranscriptionCancellation.snapshot())\n\n    @JvmStatic\n    fun denoiseSelected(\n        context: Context,\n        segmentId: String?,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        inputSnrProxyDb: Double,\n        cancellationToken: Long,\n    ): Result {\n        val result = denoise(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb, cancellationToken)\n''',
    'denoise selected overload')
s = replace_once(s,
    '''    private fun processRegion(\n        deepFilterNet: NativeDeepFilterNet,\n        frameBytes: Int,\n        source16k: FloatArray,\n    ): FloatArray? {\n''',
    '''    private fun processRegion(\n        deepFilterNet: NativeDeepFilterNet,\n        frameBytes: Int,\n        source16k: FloatArray,\n        cancellationToken: Long,\n    ): FloatArray? {\n''',
    'process region token')
s = replace_once(s,
    '''        while (offset < upsampled.size) {\n            frame.clear()\n''',
    '''        while (offset < upsampled.size) {\n            TranscriptionCancellation.throwIfCancelled(cancellationToken)\n            frame.clear()\n''',
    'process frame check')
s = replace_once(s,
    '''    private fun flushState(deepFilterNet: NativeDeepFilterNet, frameBytes: Int) {\n''',
    '''    private fun flushState(deepFilterNet: NativeDeepFilterNet, frameBytes: Int, cancellationToken: Long) {\n''',
    'flush token')
s = replace_once(s,
    '''        repeat(frames) {\n            zero.clear()\n''',
    '''        repeat(frames) {\n            TranscriptionCancellation.throwIfCancelled(cancellationToken)\n            zero.clear()\n''',
    'flush check')
p.write_text(s)

# Android on-device ASR cooperative cancel
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/AndroidOnDeviceAsr.kt')
s = p.read_text()
s = replace_once(s,
    '''    private const val MAX_RESULT_TIMEOUT_SECONDS = 180L\n\n    class Result(\n''',
    '''    private const val MAX_RESULT_TIMEOUT_SECONDS = 180L\n    private val activeRecognizer = AtomicReference<SpeechRecognizer?>()\n    private val activeCompletion = AtomicReference<CountDownLatch?>()\n\n    @JvmStatic\n    fun cancelActivePostprocessRecognition() {\n        activeCompletion.get()?.countDown()\n        Handler(Looper.getMainLooper()).post {\n            try { activeRecognizer.get()?.cancel() } catch (_: Throwable) { }\n        }\n    }\n\n    class Result(\n''',
    'android active globals')
s = replace_once(s,
    '''    @JvmStatic\n    fun transcribe(\n        context: Context,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n    ): Result {\n''',
    '''    @JvmStatic\n    fun transcribe(\n        context: Context,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n    ): Result = transcribe(context, source, startsMs, endsMs, TranscriptionCancellation.snapshot())\n\n    @JvmStatic\n    fun transcribe(\n        context: Context,\n        source: FloatArray,\n        startsMs: IntArray,\n        endsMs: IntArray,\n        cancellationToken: Long,\n    ): Result {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n''',
    'android token overload')
s = replace_once(s,
    '        for (index in startsMs.indices) {\n',
    '        for (index in startsMs.indices) {\n            TranscriptionCancellation.throwIfCancelled(cancellationToken)\n',
    'android chunk check')
s = s.replace('            val recognized = recognizeChunk(context.applicationContext, chunk)\n',
              '            val recognized = recognizeChunk(context.applicationContext, chunk, cancellationToken)\n')
s = replace_once(s,
    '    private fun recognizeChunk(context: Context, samples: FloatArray): ChunkResult {\n',
    '    private fun recognizeChunk(context: Context, samples: FloatArray, cancellationToken: Long): ChunkResult {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n',
    'recognize token')
s = replace_once(s,
    '''                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)\n                recognizerRef.set(recognizer)\n''',
    '''                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)\n                recognizerRef.set(recognizer)\n                activeRecognizer.set(recognizer)\n                activeCompletion.set(completed)\n''',
    'android active refs')
s = replace_once(s,
    '''                while (offset < samples.size) {\n                    val sampleCount = min(buffer.size / 2, samples.size - offset)\n''',
    '''                while (offset < samples.size) {\n                    TranscriptionCancellation.throwIfCancelled(cancellationToken)\n                    val sampleCount = min(buffer.size / 2, samples.size - offset)\n''',
    'android write check')
s = replace_once(s,
    '''        if (!completed.await(timeoutSeconds, TimeUnit.SECONDS)) {\n''',
    '''        var completedNormally = false\n        val resultDeadline = System.currentTimeMillis() + timeoutSeconds * 1000L\n        while (!completed.await(100L, TimeUnit.MILLISECONDS)) {\n            TranscriptionCancellation.throwIfCancelled(cancellationToken)\n            if (System.currentTimeMillis() >= resultDeadline) break\n        }\n        completedNormally = completed.count == 0L\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n        if (!completedNormally) {\n''',
    'android result poll')
s = replace_once(s,
    '''        closeQuietly(readFd)\n        handler.post { destroyQuietly(recognizerRef.getAndSet(null)) }\n\n        val errorCode''',
    '''        closeQuietly(readFd)\n        activeCompletion.compareAndSet(completed, null)\n        val finishedRecognizer = recognizerRef.getAndSet(null)\n        activeRecognizer.compareAndSet(finishedRecognizer, null)\n        handler.post { destroyQuietly(finishedRecognizer) }\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n\n        val errorCode''',
    'android cleanup refs')
p.write_text(s)

# Worker: token starts before TRANSCRIBING; cancellation requeues without retry
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionWorker.java')
s = p.read_text()
s = replace_once(s,
    '''                    long startedAt = System.currentTimeMillis();\n                    long queueWaitMs''',
    '''                    long cancellationToken = TranscriptionCancellation.snapshot();\n                    if (TranscriptionScheduler.isQueuePaused(context)) {\n                        throw new IllegalStateException(TranscriptionCancellation.CANCELLED);\n                    }\n                    long startedAt = System.currentTimeMillis();\n                    long queueWaitMs''',
    'worker token')
s = replace_once(s,
    '''                    LocalWhisperEngine.Response response =\n                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId, pipeline);\n''',
    '''                    LocalWhisperEngine.Response response =\n                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId, pipeline, cancellationToken);\n                    TranscriptionCancellation.throwIfCancelled(cancellationToken);\n''',
    'worker engine token')
s = replace_once(s,
    '''                                ? SpeakerIdentifier.annotate(context, audioFile, response.segments)\n                                : new JSONArray(response.segments.toString());\n\n                    synchronized (TranscriptionResetManager.class) {\n''',
    '''                                ? SpeakerIdentifier.annotate(context, audioFile, response.segments)\n                                : new JSONArray(response.segments.toString());\n                    TranscriptionCancellation.throwIfCancelled(cancellationToken);\n\n                    synchronized (TranscriptionResetManager.class) {\n''',
    'worker presave check')
s = replace_once(s,
    '''            } catch (Exception error) {\n                boolean retry = attempt < MAX_ATTEMPTS;\n''',
    '''            } catch (Exception error) {\n                if (TranscriptionCancellation.isCancellation(error)) {\n                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,\n                            audioFile.lastModified(), System.currentTimeMillis(), "QUEUED",\n                            "USER_PAUSED_RUNNING_TRANSCRIPTION");\n                    log(context, "TRANSCRIPTION_RUNNING_ITEM_CANCELLED_BY_USER", segmentId, audioFile,\n                            TranscriptionCancellation.CANCELLED, forceRetranscribe, attempt, null);\n                    return;\n                }\n                boolean retry = attempt < MAX_ATTEMPTS;\n''',
    'worker cancel catch')
p.write_text(s)

# Direct queue service: same immediate cancellation semantics
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionQueueService.java')
s = p.read_text()
s = replace_once(s,
    '''                    long startedAt = System.currentTimeMillis();\n                    SegmentRepository.appendWithoutNotify''',
    '''                    long cancellationToken = TranscriptionCancellation.snapshot();\n                    if (TranscriptionScheduler.isQueuePaused(context)) {\n                        throw new IllegalStateException(TranscriptionCancellation.CANCELLED);\n                    }\n                    long startedAt = System.currentTimeMillis();\n                    SegmentRepository.appendWithoutNotify''',
    'service token')
s = replace_once(s,
    '''                    LocalWhisperEngine.Response response =\n                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId, pipeline);\n''',
    '''                    LocalWhisperEngine.Response response =\n                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId, pipeline, cancellationToken);\n                    TranscriptionCancellation.throwIfCancelled(cancellationToken);\n''',
    'service engine token')
s = replace_once(s,
    '''                                ? SpeakerIdentifier.annotate(context, audioFile, response.segments)\n                                : new org.json.JSONArray(response.segments.toString());\n                    if (!TranscriptionResetManager.isCurrentGeneration''',
    '''                                ? SpeakerIdentifier.annotate(context, audioFile, response.segments)\n                                : new org.json.JSONArray(response.segments.toString());\n                    TranscriptionCancellation.throwIfCancelled(cancellationToken);\n                    if (!TranscriptionResetManager.isCurrentGeneration''',
    'service presave check')
s = replace_once(s,
    '''            } catch (Exception error) {\n                boolean retry = attempt < MAX_ATTEMPTS;\n''',
    '''            } catch (Exception error) {\n                if (TranscriptionCancellation.isCancellation(error)) {\n                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,\n                            audioFile.lastModified(), System.currentTimeMillis(), "QUEUED",\n                            "USER_PAUSED_RUNNING_TRANSCRIPTION");\n                    log(context, "TRANSCRIPTION_DIRECT_RUNNING_ITEM_CANCELLED_BY_USER", segmentId, audioFile,\n                            TranscriptionCancellation.CANCELLED, forceRetranscribe, attempt, null,\n                            selectedModelId, selectedEngineId);\n                    promote("文字起こしキュー: 一時停止中");\n                    return;\n                }\n                boolean retry = attempt < MAX_ATTEMPTS;\n''',
    'service cancel catch')
p.write_text(s)

# diagnostics version of behavior is visible via existing queuePaused plus cancellation events
print('running-pause cancellation patch applied')
