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

import com.sktpj.recorder24h.service.RecorderService;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.storage.StoragePolicy;
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
        if (startAfterPermission && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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

        TextView title = text("24hRecoder", 28, true);
        root.addView(title, matchWrap());

        TextView subtitle = text("Pixel 10a / Android 16 初期録音APK", 15, false);
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
        stopParams.setMargins(0, dp(8), 0, 0);
        root.addView(stopButton, stopParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("アプリ設定を開く");
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        LinearLayout.LayoutParams settingsParams = matchWrap();
        settingsParams.setMargins(0, dp(20), 0, 0);
        root.addView(settingsButton, settingsParams);

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
                "音声 %.1f MB / 600 MB   アプリデータ %.1f MB / 1 GB",
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
