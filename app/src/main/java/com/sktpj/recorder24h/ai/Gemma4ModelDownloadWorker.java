package com.sktpj.recorder24h.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.R;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class Gemma4ModelDownloadWorker extends Worker {
    private static final String CHANNEL_ID = "gemma4_model_download";
    private static final int NOTIFICATION_ID = 4204;
    private static final long MIN_FREE_BUFFER_BYTES = 256L * 1024L * 1024L;

    public Gemma4ModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (Gemma4ModelManager.isReady(context)) {
            activateLocalAnalysisIfSelected(context);
            return Result.success();
        }

        try {
            setForegroundAsync(foregroundInfo(context, Gemma4ModelManager.downloadedBytes(context))).get();
            File partial = Gemma4ModelManager.partialFile(context);
            File model = Gemma4ModelManager.modelFile(context);
            File parent = partial.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                throw new IllegalStateException("Gemma 4 model directory could not be created");
            }

            long offset = partial.isFile() ? partial.length() : 0L;
            if (offset > Gemma4ModelManager.EXPECTED_BYTES) {
                if (!partial.delete()) {
                    throw new IllegalStateException("Invalid partial Gemma 4 model could not be removed");
                }
                offset = 0L;
            }
            long remaining = Gemma4ModelManager.EXPECTED_BYTES - offset;
            if (parent.getUsableSpace() < remaining + MIN_FREE_BUFFER_BYTES) {
                throw new IllegalStateException("Gemma 4 model requires more free storage");
            }

            HttpURLConnection connection = (HttpURLConnection) new URL(Gemma4ModelManager.MODEL_URL).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0L) {
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
            }

            int status = connection.getResponseCode();
            boolean append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL;
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                throw new IllegalStateException("Gemma 4 download HTTP " + status);
            }
            if (!append) {
                offset = 0L;
            }

            long written = offset;
            long lastProgressAt = 0L;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 256 * 1024);
                 FileOutputStream output = new FileOutputStream(partial, append)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    if (isStopped()) {
                        return Result.failure();
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressAt >= 1_000L) {
                        lastProgressAt = now;
                        setProgressAsync(new Data.Builder()
                                .putLong("downloadedBytes", written)
                                .putLong("expectedBytes", Gemma4ModelManager.EXPECTED_BYTES)
                                .build());
                        setForegroundAsync(foregroundInfo(context, written));
                    }
                }
                output.flush();
                output.getFD().sync();
            } finally {
                connection.disconnect();
            }

            if (partial.length() != Gemma4ModelManager.EXPECTED_BYTES) {
                throw new IllegalStateException(
                        "Gemma 4 model size mismatch: " + partial.length());
            }
            if (!Gemma4ModelManager.verifySha256(partial)) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
                throw new IllegalStateException("Gemma 4 model SHA-256 mismatch");
            }
            if (model.exists() && !model.delete()) {
                throw new IllegalStateException("Existing Gemma 4 model could not be replaced");
            }
            if (!partial.renameTo(model)) {
                throw new IllegalStateException("Gemma 4 model could not be finalized");
            }
            Gemma4ModelManager.markVerified(context);

            JSONObject details = new JSONObject();
            details.put("model", Gemma4ModelManager.MODEL_ID);
            details.put("bytes", model.length());
            AppLogger.event(context, "GEMMA4_MODEL_READY", details);
            activateLocalAnalysisIfSelected(context);
            return Result.success();
        } catch (Exception error) {
            logFailure(context, error);
            return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        }
    }

    private static void activateLocalAnalysisIfSelected(Context context) {
        if (AiProviderStore.isLocalGemma(context) && Gemma4ModelManager.isReady(context)) {
            AiAnalysisScheduler.ensureScheduled(context);
            AiAnalysisScheduler.enqueueNow(context);
        }
    }

    private static ForegroundInfo foregroundInfo(Context context, long downloadedBytes) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    "Gemma 4 モデル取得",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int percent = (int) Math.min(100L,
                downloadedBytes * 100L / Math.max(1L, Gemma4ModelManager.EXPECTED_BYTES));
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Gemma 4 モデルを取得中")
                .setContentText(percent + "%")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, downloadedBytes <= 0L)
                .build();
        return new ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private static void logFailure(Context context, Exception error) {
        try {
            JSONObject details = new JSONObject();
            details.put("model", Gemma4ModelManager.MODEL_ID);
            details.put("downloadedBytes", Gemma4ModelManager.downloadedBytes(context));
            details.put("error", error.getClass().getSimpleName());
            details.put("message", error.getMessage() == null ? JSONObject.NULL : error.getMessage());
            AppLogger.event(context, "GEMMA4_MODEL_DOWNLOAD_FAILED", details);
        } catch (Exception ignored) {
        }
    }
}
