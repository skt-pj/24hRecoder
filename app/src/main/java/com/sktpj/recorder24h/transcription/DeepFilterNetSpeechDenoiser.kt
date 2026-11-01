package com.sktpj.recorder24h.transcription

import android.content.Context
import com.rikorose.deepfilternet.NativeDeepFilterNet
import com.sktpj.recorder24h.util.AppLogger
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Applies AndroidDeepFilterNet only to speech regions that Silero already accepted.
 *
 * The input and output arrays are always 16 kHz and always retain the original recording length.
 * Speech ranges are temporarily resampled to the 48 kHz PCM16 format required by DeepFilterNet,
 * filtered, resampled back to 16 kHz, delay-aligned, and copied back into the same source indices.
 * Silence is never compacted, so transcript timestamps remain on the original recording timeline.
 */
object DeepFilterNetSpeechDenoiser {
    const val LIBRARY_VERSION = "0.0.8"
    private const val SOURCE_SAMPLE_RATE = 16_000
    private const val FILTER_SAMPLE_RATE = 48_000
    private const val ATTENUATION_LIMIT_DB = 30f
    private const val APPLY_BELOW_SNR_DB = 15.0
    private const val CONTEXT_MS = 350
    private const val MAX_DELAY_MS = 120
    private const val MODEL_LOAD_TIMEOUT_MS = 20_000L
    private const val STATE_FLUSH_MS = 300

    // 15-tap Hamming-windowed low-pass, cutoff ~= 7.2 kHz at 48 kHz, before 3:1 decimation.
    private val DECIMATION_TAPS = doubleArrayOf(
        0.0011182951686352573,
        -0.003894764791719015,
        -0.016034917455185427,
        -0.020363771182153732,
        0.020951807051299644,
        0.12449781344246415,
        0.2445068318461513,
        0.29843741184101563,
        0.2445068318461513,
        0.12449781344246415,
        0.020951807051299648,
        -0.020363771182153757,
        -0.016034917455185434,
        -0.0038947647917190117,
        0.0011182951686352573,
    )

    class Result(
        @JvmField val samples: FloatArray,
        @JvmField val applied: Boolean,
        @JvmField val processingMs: Long,
        @JvmField val filteredInputMs: Long,
        @JvmField val chunkCount: Int,
        @JvmField val estimatedDelayMs: Double,
        @JvmField val snrBeforeDb: Double,
        @JvmField val snrAfterDb: Double,
        @JvmField val reason: String,
    )

    @JvmStatic
    fun denoise(
        context: Context,
        segmentId: String?,
        source: FloatArray,
        startsMs: IntArray,
        endsMs: IntArray,
        inputSnrProxyDb: Double,
    ): Result = denoise(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb, TranscriptionCancellation.snapshot())

    @JvmStatic
    fun denoise(
        context: Context,
        segmentId: String?,
        source: FloatArray,
        startsMs: IntArray,
        endsMs: IntArray,
        inputSnrProxyDb: Double,
        cancellationToken: Long,
    ): Result {
        TranscriptionCancellation.throwIfCancelled(cancellationToken)
        val startedAt = System.currentTimeMillis()
        val speechMs = startsMs.indices.sumOf { index ->
            max(0, endsMs.getOrElse(index) { 0 } - startsMs[index]).toLong()
        }

        if (source.isEmpty() || startsMs.isEmpty() || endsMs.isEmpty() || speechMs <= 0L) {
            return skipped(context, segmentId, source, startedAt, inputSnrProxyDb, "no-speech-chunks")
        }
        if (inputSnrProxyDb.isFinite() && inputSnrProxyDb >= APPLY_BELOW_SNR_DB) {
            return skipped(context, segmentId, source, startedAt, inputSnrProxyDb, "snr-clean-enough")
        }

        var deepFilterNet: NativeDeepFilterNet? = null
        return try {
            val loadLatch = CountDownLatch(1)
            val loadTimedOut = AtomicBoolean(false)
            val instance = NativeDeepFilterNet(
                context = context.applicationContext,
                attenuationLimit = ATTENUATION_LIMIT_DB,
            )
            deepFilterNet = instance
            instance.onModelLoaded { loaded ->
                if (loadTimedOut.get()) {
                    loaded.release()
                } else {
                    loadLatch.countDown()
                }
            }
            val loadDeadline = System.currentTimeMillis() + MODEL_LOAD_TIMEOUT_MS
            while (!loadLatch.await(100L, TimeUnit.MILLISECONDS)) {
                TranscriptionCancellation.throwIfCancelled(cancellationToken)
                if (System.currentTimeMillis() >= loadDeadline) {
                    loadTimedOut.set(true)
                    instance.release()
                    return fallback(
                    context, segmentId, source, startedAt, speechMs, inputSnrProxyDb,
                        "model-load-timeout", null,
                    )
                }
            }
            TranscriptionCancellation.throwIfCancelled(cancellationToken)

            val frameBytes = instance.frameLength.toInt()
            if (frameBytes <= 0 || frameBytes % 2 != 0) {
                return fallback(
                    context, segmentId, source, startedAt, speechMs, inputSnrProxyDb,
                    "invalid-frame-length:$frameBytes", null,
                )
            }
            instance.setAttenuationLimit(ATTENUATION_LIMIT_DB)

            val output = source.copyOf()
            val beforeSnr = estimateSelectedSnrDb(source, startsMs, endsMs)
            var delayWeightedSamples = 0.0
            var delayWeight = 0L
            var filteredMs = 0L
            var appliedChunks = 0

            for (index in startsMs.indices) {
                TranscriptionCancellation.throwIfCancelled(cancellationToken)
                if (index >= endsMs.size) break
                val coreStartMs = max(0, startsMs[index])
                val coreEndMs = max(coreStartMs, endsMs[index])
                if (coreEndMs <= coreStartMs) continue

                val sourceDurationMs = source.size * 1000L / SOURCE_SAMPLE_RATE
                val expandedStartMs = max(0L, coreStartMs.toLong() - CONTEXT_MS).toInt()
                val expandedEndMs = min(sourceDurationMs, coreEndMs.toLong() + CONTEXT_MS).toInt()
                val expandedStartSample = msToSample(expandedStartMs, source.size)
                val expandedEndSample = msToSample(expandedEndMs, source.size)
                val coreStartSample = msToSample(coreStartMs, source.size)
                val coreEndSample = msToSample(coreEndMs, source.size)
                if (expandedEndSample <= expandedStartSample || coreEndSample <= coreStartSample) continue

                if (appliedChunks > 0) {
                    flushState(instance, frameBytes, cancellationToken)
                }

                val region = source.copyOfRange(expandedStartSample, expandedEndSample)
                val processed = processRegion(instance, frameBytes, region, cancellationToken)
                    ?: return fallback(
                        context, segmentId, source, startedAt, speechMs, inputSnrProxyDb,
                        "native-frame-processing-failed", null,
                    )
                val delaySamples = estimateDelaySamples(region, processed, MAX_DELAY_MS)
                val localCoreStart = coreStartSample - expandedStartSample
                val localCoreEnd = coreEndSample - expandedStartSample

                for (local in localCoreStart until localCoreEnd) {
                    val delayedIndex = local + delaySamples
                    val destination = expandedStartSample + local
                    if (destination !in output.indices) continue
                    output[destination] = if (delayedIndex in processed.indices) {
                        processed[delayedIndex]
                    } else {
                        source[destination]
                    }
                }

                val coreSamples = (coreEndSample - coreStartSample).toLong()
                delayWeightedSamples += delaySamples.toDouble() * coreSamples
                delayWeight += coreSamples
                filteredMs += (coreEndMs - coreStartMs).toLong()
                appliedChunks++
            }

            if (appliedChunks == 0) {
                return skipped(context, segmentId, source, startedAt, inputSnrProxyDb, "no-valid-speech-ranges")
            }

            val afterSnr = estimateSelectedSnrDb(output, startsMs, endsMs)
            val avgDelaySamples = if (delayWeight > 0L) delayWeightedSamples / delayWeight else 0.0
            val delayMs = avgDelaySamples * 1000.0 / SOURCE_SAMPLE_RATE
            val result = Result(
                samples = output,
                applied = true,
                processingMs = max(0L, System.currentTimeMillis() - startedAt),
                filteredInputMs = filteredMs,
                chunkCount = appliedChunks,
                estimatedDelayMs = delayMs,
                snrBeforeDb = beforeSnr,
                snrAfterDb = afterSnr,
                reason = "applied",
            )
            log(context, segmentId, "DEEPFILTERNET_DENOISE_APPLIED", result, inputSnrProxyDb, null)
            result
        } catch (error: Throwable) {
            if (TranscriptionCancellation.isCancellation(error)) throw error
            fallback(
                context, segmentId, source, startedAt, speechMs, inputSnrProxyDb,
                "exception", error,
            )
        } finally {
            try {
                deepFilterNet?.release()
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun denoiseSelected(
        context: Context,
        segmentId: String?,
        source: FloatArray,
        startsMs: IntArray,
        endsMs: IntArray,
        inputSnrProxyDb: Double,
    ): Result = denoiseSelected(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb, TranscriptionCancellation.snapshot())

    @JvmStatic
    fun denoiseSelected(
        context: Context,
        segmentId: String?,
        source: FloatArray,
        startsMs: IntArray,
        endsMs: IntArray,
        inputSnrProxyDb: Double,
        cancellationToken: Long,
    ): Result {
        val result = denoise(context, segmentId, source, startsMs, endsMs, inputSnrProxyDb, cancellationToken)
        val intentionalSkip = result.reason == "snr-clean-enough" ||
            result.reason == "no-speech-chunks" ||
            result.reason == "no-valid-speech-ranges"
        if (!result.applied && !intentionalSkip) {
            try {
                AppLogger.event(
                    context,
                    "DEEPFILTERNET_SELECTED_FAILED_NO_FALLBACK",
                    JSONObject()
                        .put("segmentId", segmentId ?: JSONObject.NULL)
                        .put("reason", result.reason)
                        .put("automaticFallback", false)
                )
            } catch (_: Throwable) {
            }
            throw IllegalStateException("DEEPFILTERNET_SELECTED_FAILED:${result.reason}")
        }
        return result
    }

    private fun processRegion(
        deepFilterNet: NativeDeepFilterNet,
        frameBytes: Int,
        source16k: FloatArray,
        cancellationToken: Long,
    ): FloatArray? {
        val upsampled = upsample16To48Pcm16(source16k)
        val frameSamples = frameBytes / 2
        if (frameSamples <= 0) return null
        val processed48 = ShortArray(upsampled.size)
        val frame = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset < upsampled.size) {
            TranscriptionCancellation.throwIfCancelled(cancellationToken)
            frame.clear()
            val valid = min(frameSamples, upsampled.size - offset)
            for (i in 0 until frameSamples) {
                frame.putShort(if (i < valid) upsampled[offset + i] else 0)
            }
            frame.flip()
            val snr = deepFilterNet.processFrame(frame)
            if (!snr.isFinite() && snr != -1f) return null
            frame.rewind()
            for (i in 0 until valid) {
                processed48[offset + i] = frame.short
            }
            offset += valid
        }
        return downsample48To16(processed48, source16k.size)
    }

    private fun flushState(deepFilterNet: NativeDeepFilterNet, frameBytes: Int, cancellationToken: Long) {
        val frameSamples = frameBytes / 2
        if (frameSamples <= 0) return
        val frames = max(1, ceil(STATE_FLUSH_MS * FILTER_SAMPLE_RATE / 1000.0 / frameSamples).toInt())
        val zero = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            TranscriptionCancellation.throwIfCancelled(cancellationToken)
            zero.clear()
            repeat(frameSamples) { zero.putShort(0) }
            zero.flip()
            deepFilterNet.processFrame(zero)
        }
    }

    private fun upsample16To48Pcm16(source: FloatArray): ShortArray {
        val result = ShortArray(source.size * 3)
        for (i in source.indices) {
            val a = source[i].coerceIn(-1f, 1f)
            val b = source[min(source.lastIndex, i + 1)].coerceIn(-1f, 1f)
            val base = i * 3
            result[base] = floatToPcm16(a)
            result[base + 1] = floatToPcm16(a + (b - a) / 3f)
            result[base + 2] = floatToPcm16(a + (b - a) * 2f / 3f)
        }
        return result
    }

    private fun downsample48To16(source: ShortArray, outputSamples: Int): FloatArray {
        val result = FloatArray(outputSamples)
        val half = DECIMATION_TAPS.size / 2
        for (i in 0 until outputSamples) {
            val center = min(source.lastIndex, i * 3)
            var sum = 0.0
            for (tap in DECIMATION_TAPS.indices) {
                val sourceIndex = (center + tap - half).coerceIn(0, source.lastIndex)
                sum += DECIMATION_TAPS[tap] * (source[sourceIndex] / 32768.0)
            }
            result[i] = sum.toFloat().coerceIn(-1f, 1f)
        }
        return result
    }

    /**
     * DeepFilterNet has STFT/model lookahead delay. Estimate it from 1 ms energy envelopes and
     * compensate in samples before filtered audio is copied back to the original timeline.
     */
    private fun estimateDelaySamples(original: FloatArray, filtered: FloatArray, maxDelayMs: Int): Int {
        if (original.size < SOURCE_SAMPLE_RATE / 5 || filtered.size != original.size) return 0
        val envelopeHop = SOURCE_SAMPLE_RATE / 1000 // 1 ms
        val input = envelope(original, envelopeHop)
        val output = envelope(filtered, envelopeHop)
        if (input.size < 100 || output.size != input.size) return 0

        val maxLag = min(maxDelayMs, input.size / 4)
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 0..maxLag) {
            val count = input.size - lag
            if (count < 50) break
            var meanIn = 0.0
            var meanOut = 0.0
            for (i in 0 until count) {
                meanIn += input[i]
                meanOut += output[i + lag]
            }
            meanIn /= count
            meanOut /= count
            var numerator = 0.0
            var inPower = 0.0
            var outPower = 0.0
            for (i in 0 until count) {
                val x = input[i] - meanIn
                val y = output[i + lag] - meanOut
                numerator += x * y
                inPower += x * x
                outPower += y * y
            }
            val denominator = sqrt(max(1e-18, inPower * outPower))
            val score = numerator / denominator
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (!bestScore.isFinite() || bestScore < 0.10) return 0
        return bestLag * envelopeHop
    }

    private fun envelope(samples: FloatArray, hop: Int): DoubleArray {
        val count = (samples.size + hop - 1) / hop
        val result = DoubleArray(count)
        for (frame in 0 until count) {
            val start = frame * hop
            val end = min(samples.size, start + hop)
            var sum = 0.0
            for (i in start until end) sum += abs(samples[i].toDouble())
            result[frame] = if (end > start) sum / (end - start) else 0.0
        }
        return result
    }

    private fun estimateSelectedSnrDb(samples: FloatArray, startsMs: IntArray, endsMs: IntArray): Double {
        val frameSamples = SOURCE_SAMPLE_RATE / 50 // 20 ms
        val rmsFrames = ArrayList<Double>()
        for (index in startsMs.indices) {
            if (index >= endsMs.size) break
            val start = msToSample(max(0, startsMs[index]), samples.size)
            val end = msToSample(max(startsMs[index], endsMs[index]), samples.size)
            var cursor = start
            while (cursor < end) {
                val frameEnd = min(end, cursor + frameSamples)
                var power = 0.0
                for (i in cursor until frameEnd) {
                    val value = samples[i].toDouble()
                    power += value * value
                }
                if (frameEnd > cursor) rmsFrames += sqrt(power / (frameEnd - cursor))
                cursor = frameEnd
            }
        }
        if (rmsFrames.size < 4) return 0.0
        rmsFrames.sort()
        val noise = percentile(rmsFrames, 0.20)
        val speech = percentile(rmsFrames, 0.70)
        if (speech <= 1e-9) return 0.0
        if (noise <= 1e-9) return 60.0
        return (20.0 * log10(speech / noise)).coerceIn(-20.0, 60.0)
    }

    private fun percentile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val position = q.coerceIn(0.0, 1.0) * (values.size - 1)
        val left = position.toInt()
        val right = min(values.lastIndex, left + 1)
        val fraction = position - left
        return values[left] + (values[right] - values[left]) * fraction
    }

    private fun msToSample(ms: Int, sampleCount: Int): Int {
        return (ms.toLong() * SOURCE_SAMPLE_RATE / 1000L).coerceIn(0L, sampleCount.toLong()).toInt()
    }

    private fun floatToPcm16(value: Float): Short {
        val scaled = (value.coerceIn(-1f, 1f) * 32767f).toInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun skipped(
        context: Context,
        segmentId: String?,
        source: FloatArray,
        startedAt: Long,
        inputSnrProxyDb: Double,
        reason: String,
    ): Result {
        val result = Result(
            samples = source,
            applied = false,
            processingMs = max(0L, System.currentTimeMillis() - startedAt),
            filteredInputMs = 0L,
            chunkCount = 0,
            estimatedDelayMs = 0.0,
            snrBeforeDb = inputSnrProxyDb,
            snrAfterDb = inputSnrProxyDb,
            reason = reason,
        )
        log(context, segmentId, "DEEPFILTERNET_DENOISE_SKIPPED", result, inputSnrProxyDb, null)
        return result
    }

    private fun fallback(
        context: Context,
        segmentId: String?,
        source: FloatArray,
        startedAt: Long,
        speechMs: Long,
        inputSnrProxyDb: Double,
        reason: String,
        error: Throwable?,
    ): Result {
        val result = Result(
            samples = source,
            applied = false,
            processingMs = max(0L, System.currentTimeMillis() - startedAt),
            filteredInputMs = speechMs,
            chunkCount = 0,
            estimatedDelayMs = 0.0,
            snrBeforeDb = inputSnrProxyDb,
            snrAfterDb = inputSnrProxyDb,
            reason = reason,
        )
        log(context, segmentId, "DEEPFILTERNET_DENOISE_FALLBACK_RAW", result, inputSnrProxyDb, error)
        return result
    }

    private fun log(
        context: Context,
        segmentId: String?,
        event: String,
        result: Result,
        inputSnrProxyDb: Double,
        error: Throwable?,
    ) {
        try {
            val details = JSONObject()
                .put("segmentId", segmentId ?: JSONObject.NULL)
                .put("library", "AndroidDeepFilterNet")
                .put("libraryVersion", LIBRARY_VERSION)
                .put("audioRequirement", "48kHz-mono-pcm16")
                .put("inputTimeline", "original-16k-segment")
                .put("outputTimeline", "original-16k-segment")
                .put("originalTimelinePreserved", true)
                .put("attenuationLimitDb", ATTENUATION_LIMIT_DB.toDouble())
                .put("applyBelowSnrDb", APPLY_BELOW_SNR_DB)
                .put("inputSnrProxyDb", inputSnrProxyDb)
                .put("denoiseApplied", result.applied)
                .put("denoiseMs", result.processingMs)
                .put("denoiseInputMs", result.filteredInputMs)
                .put("denoiseChunkCount", result.chunkCount)
                .put("estimatedDelayMs", result.estimatedDelayMs)
                .put("snrBeforeDb", result.snrBeforeDb)
                .put("snrAfterDb", result.snrAfterDb)
                .put("reason", result.reason)
            if (error != null) {
                details.put("error", error.javaClass.simpleName)
                details.put("message", error.message ?: JSONObject.NULL)
            }
            AppLogger.event(context, event, details)
        } catch (_: Throwable) {
        }
    }
}
