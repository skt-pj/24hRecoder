package com.sktpj.recorder24h.transcription

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Locale

data class TranscriptEdit(
    val text: String?,
    val speaker: String?
)

object TranscriptEditRepository {
    private val lock = Any()

    @JvmStatic
    fun chunkKey(startMs: Long, endMs: Long, sourceText: String): String =
        "chunk:${startMs}:${endMs}:${sha256(sourceText)}"

    @JvmStatic
    fun wholeKey(sourceText: String): String = "whole:${sha256(sourceText)}"

    @JvmStatic
    fun load(context: Context, segmentId: String): Map<String, TranscriptEdit> {
        val file = fileFor(context, segmentId)
        if (!file.isFile) return emptyMap()
        return try {
            val root = JSONObject(readUtf8(file))
            val edits = root.optJSONObject("edits") ?: return emptyMap()
            buildMap {
                val keys = edits.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val row = edits.optJSONObject(key) ?: continue
                    put(
                        key,
                        TranscriptEdit(
                            text = if (row.isNull("text")) null else row.optString("text", null),
                            speaker = if (row.isNull("speaker")) null else row.optString("speaker", null)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @JvmStatic
    fun save(
        context: Context,
        segmentId: String,
        editKey: String,
        text: String,
        speaker: String
    ) {
        synchronized(lock) {
            val target = fileFor(context, segmentId)
            val root = if (target.isFile) {
                try { JSONObject(readUtf8(target)) } catch (_: Exception) { JSONObject() }
            } else {
                JSONObject()
            }
            root.put("schemaVersion", 1)
            root.put("segmentId", segmentId)
            root.put("updatedAtMs", System.currentTimeMillis())
            val edits = root.optJSONObject("edits") ?: JSONObject().also { root.put("edits", it) }
            edits.put(
                editKey,
                JSONObject()
                    .put("text", text)
                    .put("speaker", speaker)
                    .put("updatedAtMs", System.currentTimeMillis())
            )
            writeAtomic(target, root.toString())
        }
    }

    @JvmStatic
    fun delete(context: Context, segmentId: String, editKey: String) {
        synchronized(lock) {
            val target = fileFor(context, segmentId)
            if (!target.isFile) return
            val root = try { JSONObject(readUtf8(target)) } catch (_: Exception) { return }
            val edits = root.optJSONObject("edits") ?: return
            edits.remove(editKey)
            root.put("updatedAtMs", System.currentTimeMillis())
            if (edits.length() == 0) {
                target.delete()
            } else {
                writeAtomic(target, root.toString())
            }
        }
    }

    private fun fileFor(context: Context, segmentId: String): File {
        val dir = File(context.filesDir, "transcript-edits")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${safeSegmentId(segmentId)}.json")
    }

    private fun writeAtomic(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Unable to replace transcript edit file")
        }
        if (!temp.renameTo(target)) {
            throw IllegalStateException("Unable to finalize transcript edit file")
        }
    }

    private fun readUtf8(file: File): String =
        FileInputStream(file).use { input -> InputStreamReader(input, Charsets.UTF_8).readText() }

    private fun safeSegmentId(segmentId: String): String =
        segmentId.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }
}
