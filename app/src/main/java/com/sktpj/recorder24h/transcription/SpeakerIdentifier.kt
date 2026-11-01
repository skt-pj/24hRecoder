package com.sktpj.recorder24h.transcription

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.sktpj.recorder24h.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

object SpeakerIdentifier {
    const val SELF = "SELF"
    const val OTHER = "OTHER"
    const val UNKNOWN = "UNKNOWN"
    const val SELF_THRESHOLD = 0.5
    private const val SAMPLE_RATE = 16_000

    @JvmStatic
    fun annotate(context: Context, audioFile: File, sourceSegments: JSONArray): JSONArray =
        annotate(context, audioFile, sourceSegments, TranscriptionCancellation.snapshot())

    @JvmStatic
    fun annotate(context: Context, audioFile: File, sourceSegments: JSONArray, cancellationToken: Long): JSONArray {
        TranscriptionCancellation.throwIfCancelled(cancellationToken)
        return try {
            val samples = M4aPcmDecoder.decode(audioFile)
            TranscriptionCancellation.throwIfCancelled(cancellationToken)
            annotatePcm(context, samples, sourceSegments, cancellationToken)
        } catch (error: Exception) {
            if (TranscriptionCancellation.isCancellation(error)) throw error
            val segments = JSONArray(sourceSegments.toString())
            markUnknown(segments)
            AppLogger.event(
                context,
                "SPEAKER_IDENTIFICATION_FAILED",
                JSONObject()
                    .put("segmentId", audioFile.name)
                    .put("error", error.javaClass.simpleName)
            )
            segments
        }
    }

    /** Speaker annotation for an already captured live PCM utterance; no second audio decode. */
    @JvmStatic
    fun annotatePcm(context: Context, samples: FloatArray, sourceSegments: JSONArray): JSONArray =
        annotatePcm(context, samples, sourceSegments, TranscriptionCancellation.snapshot())

    @JvmStatic
    fun annotatePcm(context: Context, samples: FloatArray, sourceSegments: JSONArray, cancellationToken: Long): JSONArray {
        TranscriptionCancellation.throwIfCancelled(cancellationToken)
        val segments = JSONArray(sourceSegments.toString())
        val profile = SpeakerProfileStore.load(context)
        if (profile == null) {
            markUnknown(segments)
            return segments
        }
        if (!SpeakerModelManager.isReady(context)) {
            SpeakerModelManager.enqueueDownload(context)
            markUnknown(segments)
            return segments
        }

        var extractor: SpeakerEmbeddingExtractor? = null
        return try {
            extractor = createExtractor(context)
            for (index in 0 until segments.length()) {
                TranscriptionCancellation.throwIfCancelled(cancellationToken)
                val row = segments.optJSONObject(index) ?: continue
                val startMs = row.optLong("startMs", -1L)
                val endMs = row.optLong("endMs", -1L)
                if (startMs < 0L || endMs <= startMs) {
                    row.put("autoSpeaker", UNKNOWN)
                    row.put("autoSpeakerScore", JSONObject.NULL)
                    continue
                }
                val chunk = slice(samples, startMs, endMs)
                val embedding = computeEmbedding(extractor, chunk)
                TranscriptionCancellation.throwIfCancelled(cancellationToken)
                if (embedding == null) {
                    row.put("autoSpeaker", UNKNOWN)
                    row.put("autoSpeakerScore", JSONObject.NULL)
                    continue
                }
                val score = SpeakerProfileStore.similarity(profile.embedding, embedding)
                if (!score.isFinite()) {
                    row.put("autoSpeaker", UNKNOWN)
                    row.put("autoSpeakerScore", JSONObject.NULL)
                } else {
                    row.put("autoSpeaker", if (score >= SELF_THRESHOLD) SELF else OTHER)
                    row.put("autoSpeakerScore", score)
                }
            }
            stampLiveSpeakerLabels(segments)
            segments
        } catch (error: Exception) {
            if (TranscriptionCancellation.isCancellation(error)) throw error
            markUnknown(segments)
            AppLogger.event(
                context,
                "SPEAKER_IDENTIFICATION_FAILED",
                JSONObject()
                    .put("segmentId", "live-pcm")
                    .put("error", error.javaClass.simpleName)
            )
            segments
        } finally {
            extractor?.release()
        }
    }

    @JvmStatic
    fun enroll(
        context: Context,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        enrollmentKey: String
    ): Boolean {
        if (!audioFile.isFile || endMs <= startMs || enrollmentKey.isBlank()) return false
        if (!SpeakerModelManager.isReady(context)) SpeakerModelManager.download(context)
        var extractor: SpeakerEmbeddingExtractor? = null
        return try {
            val samples = M4aPcmDecoder.decode(audioFile)
            val chunk = slice(samples, startMs, endMs)
            extractor = createExtractor(context)
            val embedding = computeEmbedding(extractor, chunk) ?: return false
            SpeakerProfileStore.upsertEnrollment(context, enrollmentKey, embedding)
            true
        } finally {
            extractor?.release()
        }
    }

    private fun createExtractor(context: Context): SpeakerEmbeddingExtractor =
        SpeakerEmbeddingExtractor(
            assetManager = null,
            config = SpeakerEmbeddingExtractorConfig(
                model = SpeakerModelManager.modelFile(context).absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )

    private fun computeEmbedding(
        extractor: SpeakerEmbeddingExtractor,
        samples: FloatArray
    ): FloatArray? {
        if (samples.isEmpty()) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) null else extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    private fun slice(samples: FloatArray, startMs: Long, endMs: Long): FloatArray {
        val start = max(0L, startMs * SAMPLE_RATE / 1000L).coerceAtMost(samples.size.toLong()).toInt()
        val end = min(samples.size.toLong(), endMs * SAMPLE_RATE / 1000L).toInt()
        return if (end > start) samples.copyOfRange(start, end) else FloatArray(0)
    }

    /**
     * Full-streaming recent history historically looked only at speaker/speakerId while automatic
     * speaker identification stores autoSpeaker. Mirror the automatic result into a display label
     * without changing the authoritative autoSpeaker fields used by normal transcript history.
     * If one finalized utterance contains both SELF and OTHER ASR segments, expose that fact instead
     * of incorrectly labeling the whole utterance as whichever segment happened to come first.
     */
    private fun stampLiveSpeakerLabels(segments: JSONArray) {
        var hasSelf = false
        var hasOther = false
        var hasUnknown = false
        var firstRow: JSONObject? = null
        for (index in 0 until segments.length()) {
            val row = segments.optJSONObject(index) ?: continue
            if (firstRow == null) firstRow = row
            val label = when (row.optString("autoSpeaker", UNKNOWN)) {
                SELF -> {
                    hasSelf = true
                    "自分"
                }
                OTHER -> {
                    hasOther = true
                    "他人"
                }
                else -> {
                    hasUnknown = true
                    "判定不能"
                }
            }
            row.put("speaker", label)
        }
        val aggregate = when {
            hasSelf && hasOther -> "複数話者"
            hasSelf && hasUnknown -> "自分 / 判定不能"
            hasOther && hasUnknown -> "他人 / 判定不能"
            hasSelf -> "自分"
            hasOther -> "他人"
            else -> "判定不能"
        }
        firstRow?.put("speaker", aggregate)
    }

    private fun markUnknown(segments: JSONArray) {
        for (index in 0 until segments.length()) {
            val row = segments.optJSONObject(index) ?: continue
            if (!row.has("autoSpeaker")) row.put("autoSpeaker", UNKNOWN)
            if (!row.has("autoSpeakerScore")) row.put("autoSpeakerScore", JSONObject.NULL)
            row.put("speaker", "判定不能")
        }
    }
}
