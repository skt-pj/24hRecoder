package com.sktpj.recorder24h;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sktpj.recorder24h.util.AppLogger;
import com.sktpj.recorder24h.util.DriveLogSync;
import com.sktpj.recorder24h.util.DriveLogTarget;

public final class DriveLogSetupActivity extends Activity {
    private static final int REQUEST_TREE = 7201;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = Math.round(24f * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("診断ログのGoogle Drive保存");
        title.setTextSize(24f);
        root.addView(title, matchWrap());

        TextView message = new TextView(this);
        message.setText(
                "Google Driveの「" + DriveLogTarget.TARGET_FOLDER_NAME + "」フォルダを選択してください。\n\n"
                        + DriveLogTarget.TARGET_FOLDER_URL
                        + "\n\n一度許可すると、以後は録音・文字起こし・AI分析の診断ログを自動更新します。"
        );
        LinearLayout.LayoutParams messageParams = matchWrap();
        messageParams.topMargin = padding;
        root.addView(message, messageParams);

        Button openTarget = new Button(this);
        openTarget.setText("対象フォルダをGoogle Driveで開く");
        openTarget.setOnClickListener(v -> openTargetFolder());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = padding;
        root.addView(openTarget, buttonParams);

        Button select = new Button(this);
        select.setText("このフォルダを保存先として選択");
        select.setOnClickListener(v -> launchTreePicker());
        LinearLayout.LayoutParams selectParams = matchWrap();
        selectParams.topMargin = padding / 2;
        root.addView(select, selectParams);

        statusView = new TextView(this);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = padding;
        root.addView(statusView, statusParams);

        setContentView(root);
        refreshStatus();

        if (savedInstanceState == null && !DriveLogTarget.isConfigured(this)) {
            root.postDelayed(this::launchTreePicker, 350L);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            statusView.setText("保存先はまだ設定されていません。");
            return;
        }

        Uri treeUri = data.getData();
        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((takeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            statusView.setText("書き込み権限を取得できませんでした。もう一度フォルダを選択してください。");
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
            DriveLogTarget.setTreeUri(this, treeUri);
            DriveLogSync.ensureScheduled(this);
            DriveLogSync.syncDirectAsync(this);
            DriveLogSync.enqueueNow(this);
            AppLogger.event(this, "DRIVE_LOG_FOLDER_CONFIGURED");
            refreshStatus();
            Toast.makeText(this, "Google Driveへの診断ログ出力を開始しました", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            DriveLogTarget.clearTreeUri(this);
            statusView.setText("保存先を設定できませんでした: " + safeMessage(error));
            AppLogger.event(this, "DRIVE_LOG_FOLDER_CONFIGURATION_FAILED");
        }
    }

    private void refreshStatus() {
        if (DriveLogTarget.isConfigured(this)) {
            statusView.setText("保存先設定済み。診断ログを自動更新します。");
        } else {
            statusView.setText("未設定です。Google Driveの「log」フォルダを選択してください。");
        }
    }

    private void launchTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_TREE);
        } catch (RuntimeException error) {
            statusView.setText("フォルダ選択画面を開けませんでした: " + safeMessage(error));
        }
    }

    private void openTargetFolder() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DriveLogTarget.TARGET_FOLDER_URL)));
        } catch (RuntimeException error) {
            Toast.makeText(this, "Google Driveフォルダを開けませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
