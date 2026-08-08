package com.sktpj.recorder24h.storage;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class RecordingIntentStore {
    private static final String FILE_NAME = "recording_requested.flag";

    private RecordingIntentStore() {
    }

    public static boolean isRequested(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) {
                return false;
            }
            return "1".equals(Files.readString(file.toPath(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        }
    }

    public static void setRequested(Context context, boolean requested) {
        File target = new File(context.getFilesDir(), FILE_NAME);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write((requested ? "1" : "0").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        try {
            Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailed) {
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        }
    }
}
