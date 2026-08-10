package com.sktpj.recorder24h.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object SpeakerModelManager {
    const val MODEL_FILE_NAME = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    const val EXPECTED_BYTES = 39_593_761L
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    private const val UNIQUE_DOWNLOAD = "download-speaker-identification-model"

    @JvmStatic
    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILE_NAME)

    @JvmStatic
    fun isReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() == EXPECTED_BYTES
    }

    @JvmStatic
    fun enqueueDownload(context: Context) {
        if (isReady(context)) return
        val request = OneTimeWorkRequest.Builder(SpeakerModelDownloadWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("speaker-model-download")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_DOWNLOAD,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    internal fun download(context: Context): File {
        val target = modelFile(context)
        if (isReady(context)) return target
        val part = File(target.parentFile, target.name + ".part")
        if (part.exists() && !part.delete()) {
            throw IOException("Unable to reset partial speaker model")
        }

        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "24hRecoder/0.5.1")
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Speaker model HTTP $code")
            connection.inputStream.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (part.length() != EXPECTED_BYTES) {
            val actual = part.length()
            part.delete()
            throw IOException("Speaker model size mismatch: $actual")
        }
        if (target.exists() && !target.delete()) throw IOException("Unable to replace speaker model")
        if (!part.renameTo(target)) throw IOException("Unable to finalize speaker model")
        return target
    }

    private fun modelDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, "speaker")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
