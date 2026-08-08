package com.sktpj.recorder24h;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sktpj.recorder24h.service.RecorderService;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.transcription.LocalWhisperEngine;
import com.sktpj.recorder24h.transcription.TranscriptionRepository;
import com.sktpj.recorder24h.transcription.TranscriptionScheduler;
import com.sktpj.recorder24h.transcription.WhisperModelManager;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;
    private TextView storageText;
    private TextView heartbeatText;
    private TextView detailText;
    private TextView transcriptionText;
    private Button startButton;
    private Button stopButton;
    private boolean startAfterPermission;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        AppLogger.event(this, "MAIN_ACTIVITY_CREATED");
        if (WhisperModelManager.isReady(this)) {
            TranscriptionScheduler.enqueueExisting(this);
        }
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (startAfterPermission
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = false;
            startRecording();
        } else {
            startAfterPermission = false;
            refreshStatus();
        }
    }

    private View buildContent() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        root.addView(text("24hRecoder", 28, true), matchWrap());

        TextView subtitle = text("Pixel 10a / Android 16  0.3.0-debug", 15, false);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.setMargins(0, dp(8), 0, dp(24));
        root.addView(subtitle, subtitleParams);

        statusText = text("状態: -", 22, true);
        root.addView(statusText, matchWrap());

        heartbeatText = text("heartbeat: -", 14, false);
        LinearLayout.LayoutParams smallMargin = matchWrap();
        smallMargin.setMargins(0, dp(8), 0, 0);
        root.addView(heartbeatText, smallMargin);

        storageText = text("保存容量: -", 14, false);
        root.addView(storageText, smallMargin);

        detailText = text("", 13, false);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.setMargins(0, dp(12), 0, dp(24));
        root.addView(detailText, detailParams);

        startButton = new Button(this);
        startButton.setText("録音開始");
        startButton.setOnClickListener(v -> requestStart());
        root.addView(startButton, matchWrap());

        stopButton = new Button(this);
        stopButton.setText("録音停止");
        stopButton.setOnClickListener(v -> stopRecording());
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.setMargins(0, dp(8), 0, dp(20));
        root.addView(stopButton, stopParams);

        root.addView(text("ローカル文字起こし", 20, true), matchWrap());

        transcriptionText = text("文字起こし: -", 14, false);
        LinearLayout.LayoutParams transcriptionParams = matchWrap();
        transcriptionParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(transcriptionText, transcriptionParams);

        Button downloadModelButton = new Button(this);
        downloadModelButton.setText("Whisper baseモデルをダウンロード");
        downloadModelButton.setOnClickListener(v -> {
            WhisperModelManager.enqueueDownload(this);
            AppLogger.event(this, "UI_WHISPER_MODEL_DOWNLOAD_REQUESTED");
            Toast.makeText(this, "モデルのダウンロードをキューへ登録しました", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        root.addView(downloadModelButton, matchWrap());

        Button retryTranscriptionButton = new Button(this);
        retryTranscriptionButton.setText("未処理音声をローカル文字起こしへ登録");
        retryTranscriptionButton.setOnClickListener(v -> {
            if (!WhisperModelManager.isReady(this)) {
                Toast.makeText(this, "先にWhisperモデルをダウンロードしてください", Toast.LENGTH_SHORT).show();
                return;
            }
            int count = TranscriptionScheduler.enqueueExisting(this);
            Toast.makeText(this, count + "件をキューへ確認しました", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        LinearLayout.LayoutParams retryParams = matchWrap();
        retryParams.setMargins(0, dp(8), 0, 0);
        root.addView(retryTranscriptionButton, retryParams);

        Button deleteModelButton = new Button(this);
        deleteModelButton.setText("Whisperモデルを削除");
        deleteModelButton.setOnClickListener(v -> {
            boolean deleted = WhisperModelManager.deleteModel(this);
            AppLogger.event(this, deleted ? "UI_WHISPER_MODEL_DELETED" : "UI_WHISPER_MODEL_DELETE_FAILED");
            Toast.makeText(this, deleted ? "モデルを削除しました" : "モデルを削除できませんでした",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        root.addView(deleteModelButton, matchWrap());

        TextView privacyNotice = text(
                "文字起こしは端末内のwhisper.cppで実行し、録音音声を外部APIへ送信しません。初回のみWhisper baseモデルをインターネットから取得します。モデル本体は1GBの作業データ上限には含めません。文字起こし結果を永続保存できた音声だけを削除します。",
                13,
                false);
        LinearLayout.LayoutParams privacyParams = matchWrap();
        privacyParams.setMargins(0, dp(12), 0, dp(20));
        root.addView(privacyNotice, privacyParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("アプリ設定を開く");
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        root.addView(settingsButton, matchWrap());

        TextView notice = text(
                "端末再起動後はAndroidの制約により自動録音を開始しません。再起動後はこの画面から録音を再開してください。",
                13,
                false);
        LinearLayout.LayoutParams noticeParams = matchWrap();
        noticeParams.setMargins(0, dp(24), 0, 0);
        root.addView(notice, noticeParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void requestStart() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            startAfterPermission = true;
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        RecordingIntentStore.setRequested(this, true);
        RecorderStateStore.write(this, "STARTING", null, null);
        Intent intent = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
        try {
            startForegroundService(intent);
            AppLogger.event(this, "UI_START_RECORDING");
        } catch (RuntimeException e) {
            RecordingIntentStore.setRequested(this, false);
            RecorderStateStore.write(this, "ERROR", null, e.getMessage());
            AppLogger.event(this, "UI_START_RECORDING_FAILED");
        }
        refreshStatus();
    }

    private void stopRecording() {
        RecordingIntentStore.setRequested(this, false);
        Intent intent = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
        try {
            startService(intent);
            AppLogger.event(this, "UI_STOP_RECORDING");
        } catch (RuntimeException e) {
            RecorderStateStore.write(this, "ERROR", null, e.getMessage());
        }
        refreshStatus();
    }

    private void refreshStatus() {
        JSONObject state = RecorderStateStore.read(this);
        String status = state.optString("state", "STOPPED");
        long heartbeatMs = state.optLong("heartbeatMs", 0L);
        String segment = state.optString("segmentId", "");
        String error = state.optString("error", "");
        boolean requested = RecordingIntentStore.isRequested(this);

        statusText.setText("状態: " + status);
        heartbeatText.setText("heartbeat: " + formatTime(heartbeatMs));
        storageText.setText(String.format(Locale.JAPAN,
                "音声 %.1f MB / 600 MB   作業データ %.1f MB / 1 GB",
                mb(StoragePolicy.audioBytes(this)),
                mb(StoragePolicy.appDataBytes(this))));

        StringBuilder detail = new StringBuilder();
        detail.append("録音継続要求: ").append(requested ? "ON" : "OFF");
        if (!segment.isEmpty() && !"null".equals(segment)) {
            detail.append("\nsegment: ").append(segment);
        }
        if (!error.isEmpty() && !"null".equals(error)) {
            detail.append("\nerror: ").append(error);
        }
        detailText.setText(detail.toString());

        boolean modelReady = WhisperModelManager.isReady(this);
        long modelBytes = WhisperModelManager.downloadedBytes(this);
        transcriptionText.setText(String.format(Locale.JAPAN,
                "方式: %s\nモデル: base / %s\nモデル状態: %s (%.1f MB)\n未処理音声: %d件\n文字起こし保存済み: %d件",
                LocalWhisperEngine.ENGINE_ID,
                "1GB作業データ上限の対象外",
                modelReady ? "準備済み" : "未準備",
                mb(modelBytes),
                TranscriptionScheduler.pendingAudioCount(this),
                TranscriptionRepository.count(this)));

        boolean active = "STARTING".equals(status) || "RECORDING".equals(status) || "STOPPING".equals(status);
        startButton.setEnabled(!active);
        stopButton.setEnabled(active || requested);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(0xFF202124);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static double mb(long bytes) {
        return bytes / 1024.0 / 1024.0;
    }

    private static String formatTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.JAPAN)
                .format(new Date(millis));
    }
}
