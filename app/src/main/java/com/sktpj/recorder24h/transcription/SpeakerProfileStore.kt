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
    fun enrollmentKey(segmentId: String, editKey: String): String = "$segmentId|$editKey"

    @JvmStatic
    fun load(context: Context): Profile? {
        val file = profileFile(context)
        if (!file.isFile) return null
        return try {
            val root = JSONObject(readUtf8(file))
            val enrollments = root.optJSONObject("enrollments")
            if (enrollments != null && enrollments.length() > 0) {
                profileFromEnrollments(enrollments)
            } else {
                profileFromLegacy(root)
            }
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun hasProfile(context: Context): Boolean = load(context) != null

    @JvmStatic
    fun upsertEnrollment(context: Context, enrollmentKey: String, newEmbedding: FloatArray) {
        require(enrollmentKey.isNotBlank()) { "Enrollment key is empty" }
        require(newEmbedding.isNotEmpty()) { "Speaker embedding is empty" }
        synchronized(lock) {
            val target = profileFile(context)
            val root = if (target.isFile) {
                try { JSONObject(readUtf8(target)) } catch (_: Exception) { JSONObject() }
            } else {
                JSONObject()
            }
            val enrollments = root.optJSONObject("enrollments") ?: JSONObject().also { created ->
                val legacy = root.optJSONArray("embedding")
                if (legacy != null && legacy.length() > 0) {
                    created.put(
                        "legacy",
                        JSONObject()
                            .put("embedding", JSONArray(legacy.toString()))
                            .put("updatedAtMs", root.optLong("updatedAtMs", 0L))
                    )
                }
                root.put("enrollments", created)
                root.remove("embedding")
                root.remove("enrollmentCount")
            }

            val normalized = newEmbedding.copyOf()
            normalize(normalized)
            enrollments.put(
                enrollmentKey,
                JSONObject()
                    .put("embedding", JSONArray().also { array ->
                        normalized.forEach { value -> array.put(value.toDouble()) }
                    })
                    .put("updatedAtMs", System.currentTimeMillis())
            )
            root.put("schemaVersion", 2)
            root.put("updatedAtMs", System.currentTimeMillis())
            writeAtomic(target, root.toString())
        }
    }

    @JvmStatic
    fun removeEnrollment(context: Context, enrollmentKey: String) {
        if (enrollmentKey.isBlank()) return
        synchronized(lock) {
            val target = profileFile(context)
            if (!target.isFile) return
            val root = try { JSONObject(readUtf8(target)) } catch (_: Exception) { return }
            val enrollments = root.optJSONObject("enrollments") ?: return
            enrollments.remove(enrollmentKey)
            if (enrollments.length() == 0) {
                target.delete()
                return
            }
            root.put("updatedAtMs", System.currentTimeMillis())
            writeAtomic(target, root.toString())
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

    private fun profileFromEnrollments(enrollments: JSONObject): Profile? {
        val vectors = mutableListOf<FloatArray>()
        val keys = enrollments.keys()
        var dimension = -1
        while (keys.hasNext()) {
            val key = keys.next()
            val values = enrollments.optJSONObject(key)?.optJSONArray("embedding") ?: continue
            if (values.length() == 0) continue
            if (dimension < 0) dimension = values.length()
            if (values.length() != dimension) continue
            val vector = FloatArray(values.length()) { index -> values.optDouble(index, 0.0).toFloat() }
            normalize(vector)
            vectors += vector
        }
        if (vectors.isEmpty() || dimension <= 0) return null
        val centroid = FloatArray(dimension)
        for (vector in vectors) {
            for (index in centroid.indices) centroid[index] += vector[index]
        }
        for (index in centroid.indices) centroid[index] /= vectors.size.toFloat()
        normalize(centroid)
        return Profile(centroid, vectors.size)
    }

    private fun profileFromLegacy(root: JSONObject): Profile? {
        val values = root.optJSONArray("embedding") ?: return null
        if (values.length() == 0) return null
        val embedding = FloatArray(values.length()) { index -> values.optDouble(index, 0.0).toFloat() }
        normalize(embedding)
        return Profile(embedding, root.optInt("enrollmentCount", 1).coerceAtLeast(1))
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
