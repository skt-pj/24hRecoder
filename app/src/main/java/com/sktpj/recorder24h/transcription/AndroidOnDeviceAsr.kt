package com.sktpj.recorder24h.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Android's explicit on-device recognition backend using already captured 16 kHz mono PCM.
 * No microphone is opened here and no alternate ASR backend is attempted on failure.
 */
object AndroidOnDeviceAsr {
    private const val SAMPLE_RATE = 16_000
    private const val START_TIMEOUT_SECONDS = 10L
    private const val MIN_RESULT_TIMEOUT_SECONDS = 30L
    private const val MAX_RESULT_TIMEOUT_SECONDS = 180L
    private val activeRecognizer = AtomicReference<SpeechRecognizer?>()
    private val activeCompletion = AtomicReference<CountDownLatch?>()

    @JvmStatic
    fun cancelActivePostprocessRecognition() {
        activeCompletion.get()?.countDown()
        Handler(Looper.getMainLooper()).post {
            try { activeRecognizer.get()?.cancel() } catch (_: Throwable) { }
        }
    }

    class Result(
        @JvmField val text: String,
        @JvmField val segments: JSONArray,
        @JvmField val inferenceMs: Long,
        @JvmField val lastOutputEndMs: Long,
    )

    private data class ChunkResult(
        val text: String,
        val segments: JSONArray,
    )

    @JvmStatic
    fun transcribe(
        context: Context,
        source: FloatArray,
        startsMs: IntArray,
        endsMs: IntArray,
    ): Result = transcribe(context, source, startsMs, endsMs, TranscriptionCancellation.snapshot())

    @JvmStatic
    fun transcribe(
        context: Context,
        source: FloatArray,
        startsMs: IntArray,
        endsMs: IntArray,
        cancellationToken: Long,
    ): Result {
        TranscriptionCancellation.throwIfCancelled(cancellationToken)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw IllegalStateException("ANDROID_ON_DEVICE_ASR_REQUIRES_API_33")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IllegalStateException("ANDROID_ON_DEVICE_ASR_UNAVAILABLE")
        }
        require(startsMs.size == endsMs.size) { "Speech chunk arrays have different lengths" }

        val startedAt = System.currentTimeMillis()
        val output = JSONArray()
        val allText = StringBuilder()
        var lastEnd = 0L
        val sourceDurationMs = source.size * 1000L / SAMPLE_RATE

        for (index in startsMs.indices) {
            TranscriptionCancellation.throwIfCancelled(cancellationToken)
            val chunkStartMs = startsMs[index].toLong().coerceIn(0L, sourceDurationMs)
            val chunkEndMs = endsMs[index].toLong().coerceIn(chunkStartMs, sourceDurationMs)
            if (chunkEndMs <= chunkStartMs) continue
            val startSample = (chunkStartMs * SAMPLE_RATE / 1000L).toInt().coerceIn(0, source.size)
            val endSample = (chunkEndMs * SAMPLE_RATE / 1000L).toInt().coerceIn(startSample, source.size)
            if (endSample <= startSample) continue

            val chunk = source.copyOfRange(startSample, endSample)
            val recognized = recognizeChunk(context.applicationContext, chunk, cancellationToken)
            if (recognized.text.isNotBlank()) {
                if (allText.isNotEmpty()) allText.append(' ')
                allText.append(recognized.text.trim())
            }

            for (segmentIndex in 0 until recognized.segments.length()) {
                val local = recognized.segments.optJSONObject(segmentIndex) ?: continue
                val localStart = local.optLong("startMs", 0L).coerceIn(0L, chunkEndMs - chunkStartMs)
                val localEnd = local.optLong("endMs", chunkEndMs - chunkStartMs)
                    .coerceIn(localStart, chunkEndMs - chunkStartMs)
                val mappedStart = chunkStartMs + localStart
                val mappedEnd = chunkStartMs + localEnd
                lastEnd = max(lastEnd, mappedEnd)
                output.put(
                    JSONObject(local.toString())
                        .put("startMs", mappedStart)
                        .put("endMs", mappedEnd)
                        .put("durationMs", max(0L, mappedEnd - mappedStart))
                        .put("sourceChunkIndex", index)
                        .put("sourceChunkStartMs", chunkStartMs)
                        .put("sourceChunkEndMs", chunkEndMs)
                        .put("asrBackend", TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE)
                )
            }
        }

        return Result(
            text = allText.toString().trim(),
            segments = output,
            inferenceMs = max(0L, System.currentTimeMillis() - startedAt),
            lastOutputEndMs = lastEnd,
        )
    }

    private fun recognizeChunk(context: Context, samples: FloatArray, cancellationToken: Long): ChunkResult {
        TranscriptionCancellation.throwIfCancelled(cancellationToken)
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val recognizerRef = AtomicReference<SpeechRecognizer?>()
        val startError = AtomicReference<Throwable?>()
        val recognitionError = AtomicInteger(0)
        val resultBundles = mutableListOf<Bundle>()
        val resultLock = Any()
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            try {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizerRef.set(recognizer)
                activeRecognizer.set(recognizer)
                activeCompletion.set(completed)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        recognitionError.set(error)
                        completed.countDown()
                    }

                    override fun onResults(results: Bundle) {
                        synchronized(resultLock) {
                            if (resultBundles.isEmpty()) resultBundles += Bundle(results)
                        }
                        completed.countDown()
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        synchronized(resultLock) {
                            resultBundles += Bundle(segmentResults)
                        }
                    }

                    override fun onEndOfSegmentedSession() {
                        completed.countDown()
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    intent.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
                    intent.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true)
                }
                recognizer.startListening(intent)
                started.countDown()
            } catch (error: Throwable) {
                startError.set(error)
                started.countDown()
                completed.countDown()
            }
        }

        if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            closeQuietly(writeFd)
            closeQuietly(readFd)
            handler.post { destroyQuietly(recognizerRef.get()) }
            throw IllegalStateException("ANDROID_ON_DEVICE_ASR_START_TIMEOUT")
        }
        startError.get()?.let { error ->
            closeQuietly(writeFd)
            closeQuietly(readFd)
            handler.post { destroyQuietly(recognizerRef.get()) }
            throw IllegalStateException("ANDROID_ON_DEVICE_ASR_START_FAILED", error)
        }

        try {
            FileOutputStream(writeFd.fileDescriptor).use { out ->
                val buffer = ByteArray(8192)
                var offset = 0
                while (offset < samples.size) {
                    TranscriptionCancellation.throwIfCancelled(cancellationToken)
                    val sampleCount = min(buffer.size / 2, samples.size - offset)
                    var byteIndex = 0
                    for (i in 0 until sampleCount) {
                        val pcm = floatToPcm16(samples[offset + i]).toInt()
                        buffer[byteIndex++] = (pcm and 0xff).toByte()
                        buffer[byteIndex++] = ((pcm ushr 8) and 0xff).toByte()
                    }
                    out.write(buffer, 0, byteIndex)
                    offset += sampleCount
                }
                out.flush()
            }
        } finally {
            closeQuietly(writeFd)
        }

        val audioSeconds = max(1L, samples.size.toLong() / SAMPLE_RATE)
        val timeoutSeconds = (audioSeconds * 3L + 20L)
            .coerceIn(MIN_RESULT_TIMEOUT_SECONDS, MAX_RESULT_TIMEOUT_SECONDS)
        var completedNormally = false
        val resultDeadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (!completed.await(100L, TimeUnit.MILLISECONDS)) {
            TranscriptionCancellation.throwIfCancelled(cancellationToken)
            if (System.currentTimeMillis() >= resultDeadline) break
        }
        completedNormally = completed.count == 0L
        TranscriptionCancellation.throwIfCancelled(cancellationToken)
        if (!completedNormally) {
            closeQuietly(readFd)
            handler.post {
                try { recognizerRef.get()?.cancel() } catch (_: Throwable) { }
                destroyQuietly(recognizerRef.get())
            }
            throw IllegalStateException("ANDROID_ON_DEVICE_ASR_RESULT_TIMEOUT")
        }

        closeQuietly(readFd)
        activeCompletion.compareAndSet(completed, null)
        val finishedRecognizer = recognizerRef.getAndSet(null)
        activeRecognizer.compareAndSet(finishedRecognizer, null)
        handler.post { destroyQuietly(finishedRecognizer) }
        TranscriptionCancellation.throwIfCancelled(cancellationToken)

        val errorCode = recognitionError.get()
        if (errorCode != 0 &&
            errorCode != SpeechRecognizer.ERROR_NO_MATCH &&
            errorCode != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) {
            throw IllegalStateException("ANDROID_ON_DEVICE_ASR_ERROR_$errorCode")
        }

        val bundles = synchronized(resultLock) { resultBundles.toList() }
        if (bundles.isEmpty()) return ChunkResult("", JSONArray())

        val segments = JSONArray()
        val text = StringBuilder()
        val chunkDurationMs = samples.size * 1000L / SAMPLE_RATE
        for (bundle in bundles) {
            val alternatives = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = alternatives?.firstOrNull()?.trim().orEmpty()
            if (best.isBlank()) continue
            if (text.isNotEmpty()) text.append(' ')
            text.append(best)

            var startMs = 0L
            var endMs = chunkDurationMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                val parts = bundle.getParcelableArrayList<RecognitionPart>(SpeechRecognizer.RECOGNITION_PARTS)
                if (!parts.isNullOrEmpty()) {
                    startMs = parts.first().timestampMillis.coerceIn(0L, chunkDurationMs)
                    endMs = (parts.last().timestampMillis + 500L).coerceIn(startMs, chunkDurationMs)
                }
            }
            val confidence = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                ?.firstOrNull()?.toDouble() ?: -1.0
            segments.put(
                JSONObject()
                    .put("startMs", startMs)
                    .put("endMs", endMs)
                    .put("durationMs", max(0L, endMs - startMs))
                    .put("text", best)
                    .put("confidence", confidence)
            )
        }
        return ChunkResult(text.toString().trim(), segments)
    }

    private fun floatToPcm16(value: Float): Short {
        val scaled = (value.coerceIn(-1f, 1f) * 32767f).toInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun closeQuietly(fd: ParcelFileDescriptor?) {
        try { fd?.close() } catch (_: Throwable) { }
    }

    private fun destroyQuietly(recognizer: SpeechRecognizer?) {
        try { recognizer?.destroy() } catch (_: Throwable) { }
    }
}
