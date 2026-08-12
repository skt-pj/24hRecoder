from pathlib import Path


def one(s, old, new, label):
    if old not in s:
        raise SystemExit('missing ' + label)
    return s.replace(old, new, 1)

# LocalWhisperEngine: candidate VAD observes same token before/after every native pass.
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/LocalWhisperEngine.java')
s = p.read_text()
s = one(s,
'''            vad = gate.available && !gate.ranges.isEmpty()\n                    ? analyzeVad(context, prepared, gate)\n                    : analyzeVad(context, prepared);\n''',
'''            vad = gate.available && !gate.ranges.isEmpty()\n                    ? analyzeVad(context, prepared, gate, cancellationToken)\n                    : analyzeVad(context, prepared, cancellationToken);\n''', 'engine vad calls')
s = one(s,
'''    static VadDiagnostics analyzeVad(Context context, PreparedAudio prepared) throws Exception {\n        File vadModel = WhisperModelManager.vadModelFile(context);\n''',
'''    static VadDiagnostics analyzeVad(Context context, PreparedAudio prepared) throws Exception {\n        return analyzeVad(context, prepared, TranscriptionCancellation.snapshot());\n    }\n\n    static VadDiagnostics analyzeVad(Context context, PreparedAudio prepared, long cancellationToken) throws Exception {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        File vadModel = WhisperModelManager.vadModelFile(context);\n''', 'full vad overload')
s = one(s,
'''        String raw = nativeAnalyzeVadDetailed(vadModel.getAbsolutePath(), prepared.frontEnd.samples, threads);\n        if (raw == null) {\n''',
'''        String raw = nativeAnalyzeVadDetailed(vadModel.getAbsolutePath(), prepared.frontEnd.samples, threads);\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        if (raw == null) {\n''', 'full vad postcheck')
s = one(s,
'''    private static VadDiagnostics analyzeVad(Context context,\n                                             PreparedAudio prepared,\n                                             RealtimeSpeechGateStore.Snapshot gate) throws Exception {\n        File vadModel = WhisperModelManager.vadModelFile(context);\n''',
'''    private static VadDiagnostics analyzeVad(Context context,\n                                             PreparedAudio prepared,\n                                             RealtimeSpeechGateStore.Snapshot gate,\n                                             long cancellationToken) throws Exception {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken);\n        File vadModel = WhisperModelManager.vadModelFile(context);\n''', 'candidate vad token')
s = one(s,
'''        for (RealtimeSpeechGateStore.Range candidate : gate.ranges) {\n            long startMs = Math.max(0L, Math.min(audioDurationMs, candidate.startMs));\n''',
'''        for (RealtimeSpeechGateStore.Range candidate : gate.ranges) {\n            TranscriptionCancellation.throwIfCancelled(cancellationToken);\n            long startMs = Math.max(0L, Math.min(audioDurationMs, candidate.startMs));\n''', 'candidate loop check')
s = one(s,
'''            String raw = nativeAnalyzeVadDetailed(vadModel.getAbsolutePath(), slice, threads);\n            if (raw == null) {\n''',
'''            String raw = nativeAnalyzeVadDetailed(vadModel.getAbsolutePath(), slice, threads);\n            TranscriptionCancellation.throwIfCancelled(cancellationToken);\n            if (raw == null) {\n''', 'candidate native postcheck')
p.write_text(s)

# SpeakerIdentifier: cancellation between decode/segments/embedding operations.
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/SpeakerIdentifier.kt')
s = p.read_text()
s = one(s,
'''    @JvmStatic\n    fun annotate(context: Context, audioFile: File, sourceSegments: JSONArray): JSONArray {\n        return try {\n            annotatePcm(context, M4aPcmDecoder.decode(audioFile), sourceSegments)\n''',
'''    @JvmStatic\n    fun annotate(context: Context, audioFile: File, sourceSegments: JSONArray): JSONArray =\n        annotate(context, audioFile, sourceSegments, TranscriptionCancellation.snapshot())\n\n    @JvmStatic\n    fun annotate(context: Context, audioFile: File, sourceSegments: JSONArray, cancellationToken: Long): JSONArray {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n        return try {\n            val samples = M4aPcmDecoder.decode(audioFile)\n            TranscriptionCancellation.throwIfCancelled(cancellationToken)\n            annotatePcm(context, samples, sourceSegments, cancellationToken)\n''', 'speaker annotate overload')
s = one(s,
'''        } catch (error: Exception) {\n            val segments = JSONArray(sourceSegments.toString())\n''',
'''        } catch (error: Exception) {\n            if (TranscriptionCancellation.isCancellation(error)) throw error\n            val segments = JSONArray(sourceSegments.toString())\n''', 'speaker annotate cancel rethrow')
s = one(s,
'''    @JvmStatic\n    fun annotatePcm(context: Context, samples: FloatArray, sourceSegments: JSONArray): JSONArray {\n        val segments = JSONArray(sourceSegments.toString())\n''',
'''    @JvmStatic\n    fun annotatePcm(context: Context, samples: FloatArray, sourceSegments: JSONArray): JSONArray =\n        annotatePcm(context, samples, sourceSegments, TranscriptionCancellation.snapshot())\n\n    @JvmStatic\n    fun annotatePcm(context: Context, samples: FloatArray, sourceSegments: JSONArray, cancellationToken: Long): JSONArray {\n        TranscriptionCancellation.throwIfCancelled(cancellationToken)\n        val segments = JSONArray(sourceSegments.toString())\n''', 'speaker pcm overload')
s = one(s,
'''            for (index in 0 until segments.length()) {\n                val row = segments.optJSONObject(index) ?: continue\n''',
'''            for (index in 0 until segments.length()) {\n                TranscriptionCancellation.throwIfCancelled(cancellationToken)\n                val row = segments.optJSONObject(index) ?: continue\n''', 'speaker loop check')
s = one(s,
'''                val embedding = computeEmbedding(extractor, chunk)\n                if (embedding == null) {\n''',
'''                val embedding = computeEmbedding(extractor, chunk)\n                TranscriptionCancellation.throwIfCancelled(cancellationToken)\n                if (embedding == null) {\n''', 'speaker embedding check')
# second catch belongs annotatePcm
needle = '''        } catch (error: Exception) {\n            markUnknown(segments)\n            AppLogger.event(\n                context,\n                "SPEAKER_IDENTIFICATION_FAILED",\n                JSONObject()\n                    .put("segmentId", "live-pcm")\n'''
rep = '''        } catch (error: Exception) {\n            if (TranscriptionCancellation.isCancellation(error)) throw error\n            markUnknown(segments)\n            AppLogger.event(\n                context,\n                "SPEAKER_IDENTIFICATION_FAILED",\n                JSONObject()\n                    .put("segmentId", "live-pcm")\n'''
s = one(s, needle, rep, 'speaker pcm cancel rethrow')
p.write_text(s)

# Pass the same item token into speaker annotation in both queue runners.
for path in [
    'app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionWorker.java',
    'app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionQueueService.java',
]:
    p = Path(path)
    s = p.read_text()
    s = one(s,
        'SpeakerIdentifier.annotate(context, audioFile, response.segments)',
        'SpeakerIdentifier.annotate(context, audioFile, response.segments, cancellationToken)',
        path + ' speaker token')
    p.write_text(s)

print('pause completion applied')
