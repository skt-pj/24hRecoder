package com.sktpj.recorder24h.transcription

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import kotlin.math.sqrt

object SpeakerProfileStore {
    private val lock = Any()

    data class Profile(
        val embedding: FloatArray,
        val enrollmentCount: Int
    )

    @JvmStatic
    fun load(context: Context): Profile? {
        val file = profileFile(context)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(readUtf8(file))
            val values = root.optJSONArray("embedding") ?: return null
            if (values.length() == 0) return null
            val embedding = FloatArray(values.length()) { index -> values.optDouble(index, 0.0).toFloat() }
            normalize(embedding)
            Profile(embedding, root.optInt("enrollmentCount", 1).coerceAtLeast(1))
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun hasProfile(context: Context): Boolean = load(context) != null

    @JvmStatic
    fun addEnrollment(context: Context, newEmbedding: FloatArray) {
        require(newEmbedding.isNotEmpty()) { "Speaker embedding is empty" }
        synchronized(lock) {
            val normalizedNew = newEmbedding.copyOf()
            normalize(normalizedNew)
            val existing = load(context)
            val merged: FloatArray
            val count: Int
            if (existing == null || existing.embedding.size != normalizedNew.size) {
                merged = normalizedNew
                count = 1
            } else {
                count = existing.enrollmentCount + 1
                merged = FloatArray(normalizedNew.size) { index ->
                    ((existing.embedding[index] * existing.enrollmentCount) + normalizedNew[index]) / count
                }
                normalize(merged)
            }

            val root = JSONObject()
                .put("schemaVersion", 1)
                .put("updatedAtMs", System.currentTimeMillis())
                .put("enrollmentCount", count)
                .put("embedding", JSONArray().also { array -> merged.forEach { array.put(it.toDouble()) } })
            writeAtomic(profileFile(context), root.toString())
        }
    }

    @JvmStatic
    fun similarity(profile: FloatArray, candidate: FloatArray): Double {
        if (profile.isEmpty() || profile.size != candidate.size) return Double.NaN
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        for (index in profile.indices) {
            val a = profile[index].toDouble()
            val b = candidate[index].toDouble()
            dot += a * b
            left += a * a
            right += b * b
        }
        if (left <= 0.0 || right <= 0.0) return Double.NaN
        return dot / (sqrt(left) * sqrt(right))
    }

    private fun normalize(values: FloatArray) {
        var sum = 0.0
        for (value in values) sum += value.toDouble() * value.toDouble()
        val norm = sqrt(sum)
        if (norm <= 0.0) return
        for (index in values.indices) values[index] = (values[index] / norm).toFloat()
    }

    private fun profileFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, "speaker")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "self-profile.json")
    }

    private fun writeAtomic(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Unable to replace speaker profile")
        }
        if (!temp.renameTo(target)) {
            throw IllegalStateException("Unable to finalize speaker profile")
        }
    }

    private fun readUtf8(file: File): String =
        FileInputStream(file).use { input -> InputStreamReader(input, Charsets.UTF_8).readText() }
}
