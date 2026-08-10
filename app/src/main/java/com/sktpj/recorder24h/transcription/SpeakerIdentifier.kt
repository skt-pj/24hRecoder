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
    fun annotate(context: Context, audioFile: File, sourceSegments: JSONArray): JSONArray {
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
            val samples = M4aPcmDecoder.decode(audioFile)
            extractor = createExtractor(context)
            for (index in 0 until segments.length()) {
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
            segments
        } catch (error: Exception) {
            markUnknown(segments)
            AppLogger.event(
                context,
                "SPEAKER_IDENTIFICATION_FAILED",
                JSONObject()
                    .put("segmentId", audioFile.name)
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

    private fun markUnknown(segments: JSONArray) {
        for (index in 0 until segments.length()) {
            val row = segments.optJSONObject(index) ?: continue
            if (!row.has("autoSpeaker")) row.put("autoSpeaker", UNKNOWN)
            if (!row.has("autoSpeakerScore")) row.put("autoSpeakerScore", JSONObject.NULL)
        }
    }
}
