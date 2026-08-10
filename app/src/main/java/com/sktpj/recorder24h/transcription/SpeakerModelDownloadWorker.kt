package com.sktpj.recorder24h.transcription

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sktpj.recorder24h.util.AppLogger
import org.json.JSONObject

class SpeakerModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val file = SpeakerModelManager.download(applicationContext)
            AppLogger.event(
                applicationContext,
                "SPEAKER_MODEL_READY",
                JSONObject().put("bytes", file.length())
            )
            Result.success()
        } catch (error: Exception) {
            AppLogger.event(
                applicationContext,
                "SPEAKER_MODEL_DOWNLOAD_FAILED",
                JSONObject().put("error", error.javaClass.simpleName)
            )
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
