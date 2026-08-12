package com.sktpj.recorder24h.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Persists only intermediate structured evidence for one Gemma hourly source hash.
 * Transcript text is never copied into this checkpoint. A changed transcript produces a new
 * sourceHash, so stale evidence can never be resumed for different source data.
 */
object GemmaHourlyCheckpointStore {
    private const val SCHEMA_VERSION = 1
    private const val DIR_NAME = "gemma-hourly-progress"
    private const val MAX_AGE_MS = 3L * 24L * 60L * 60L * 1000L
    private val lock = Any()

    @JvmStatic
    fun load(
        context: Context,
        sourceHash: String,
        chunkCount: Int
    ): MutableMap<Int, JSONObject> = synchronized(lock) {
        prune(context)
        val file = checkpointFile(context, sourceHash)
        if (!file.isFile) return@synchronized mutableMapOf()
        try {
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                root.optString("sourceHash", "") != sourceHash ||
                root.optInt("chunkCount", -1) != chunkCount
            ) {
                file.delete()
                return@synchronized mutableMapOf()
            }
            val chunks = root.optJSONObject("chunks") ?: JSONObject()
            val result = mutableMapOf<Int, JSONObject>()
            val keys = chunks.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val index = key.toIntOrNull() ?: continue
                if (index !in 0 until chunkCount) continue
                val value = chunks.optJSONObject(key) ?: continue
                result[index] = JSONObject(value.toString())
            }
            result
        } catch (_: Exception) {
            file.delete()
            mutableMapOf()
        }
    }

    @JvmStatic
    fun saveChunk(
        context: Context,
        sourceHash: String,
        chunkCount: Int,
        chunkIndex: Int,
        evidence: JSONObject
    ) = synchronized(lock) {
        if (chunkIndex !in 0 until chunkCount) return@synchronized
        val file = checkpointFile(context, sourceHash)
        val root = try {
            if (file.isFile) JSONObject(file.readText(StandardCharsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
            root.optString("sourceHash", "") != sourceHash ||
            root.optInt("chunkCount", -1) != chunkCount
        ) {
            while (root.keys().hasNext()) {
                val key = root.keys().next()
                root.remove(key)
            }
            root.put("schemaVersion", SCHEMA_VERSION)
            root.put("sourceHash", sourceHash)
            root.put("chunkCount", chunkCount)
            root.put("createdAtMs", System.currentTimeMillis())
            root.put("chunks", JSONObject())
        }
        root.put("updatedAtMs", System.currentTimeMillis())
        val chunks = root.optJSONObject("chunks") ?: JSONObject().also { root.put("chunks", it) }
        chunks.put(chunkIndex.toString(), JSONObject(evidence.toString()))
        writeAtomic(file, root.toString())
    }

    @JvmStatic
    fun clear(context: Context, sourceHash: String) = synchronized(lock) {
        checkpointFile(context, sourceHash).delete()
    }

    private fun checkpointFile(context: Context, sourceHash: String): File {
        val safeHash = sourceHash.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        val dir = File(AiAnalysisRepository.getAnalysisDir(context), DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${safeHash.ifEmpty { "unknown" }}.json")
    }

    private fun prune(context: Context) {
        val dir = File(AiAnalysisRepository.getAnalysisDir(context), DIR_NAME)
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val temp = File(parent, target.name + ".tmp")
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(temp, false).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Unable to replace Gemma hourly checkpoint")
        }
        if (!temp.renameTo(target)) {
            throw IllegalStateException("Unable to finalize Gemma hourly checkpoint")
        }
    }
}
