package com.sktpj.recorder24h.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sktpj.recorder24h.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SpeakerEnrollmentWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val filePath = inputData.getString(EXTRA_FILE_PATH).orEmpty()
        val segmentId = inputData.getString(EXTRA_SEGMENT_ID).orEmpty()
        val startMs = inputData.getLong(EXTRA_START_MS, -1L)
        val endMs = inputData.getLong(EXTRA_END_MS, -1L)
        if (filePath.isBlank() || startMs < 0L || endMs <= startMs) return Result.failure()

        return try {
            val enrolled = SpeakerIdentifier.enroll(applicationContext, File(filePath), startMs, endMs)
            AppLogger.event(
                applicationContext,
                if (enrolled) "SELF_SPEAKER_ENROLLED" else "SELF_SPEAKER_ENROLLMENT_SKIPPED",
                JSONObject()
                    .put("segmentId", segmentId)
                    .put("startMs", startMs)
                    .put("endMs", endMs)
            )
            if (enrolled) Result.success() else Result.failure()
        } catch (error: Exception) {
            AppLogger.event(
                applicationContext,
                "SELF_SPEAKER_ENROLLMENT_FAILED",
                JSONObject()
                    .put("segmentId", segmentId)
                    .put("error", error.javaClass.simpleName)
            )
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val EXTRA_FILE_PATH = "speakerEnrollmentFilePath"
        private const val EXTRA_SEGMENT_ID = "speakerEnrollmentSegmentId"
        private const val EXTRA_START_MS = "speakerEnrollmentStartMs"
        private const val EXTRA_END_MS = "speakerEnrollmentEndMs"

        @JvmStatic
        fun enqueue(
            context: Context,
            segmentId: String,
            filePath: String,
            startMs: Long,
            endMs: Long
        ) {
            val data = Data.Builder()
                .putString(EXTRA_FILE_PATH, filePath)
                .putString(EXTRA_SEGMENT_ID, segmentId)
                .putLong(EXTRA_START_MS, startMs)
                .putLong(EXTRA_END_MS, endMs)
                .build()
            val request = OneTimeWorkRequestBuilder<SpeakerEnrollmentWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("speaker-enrollment")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
