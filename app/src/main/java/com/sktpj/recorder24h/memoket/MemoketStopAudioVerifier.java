package com.sktpj.recorder24h.memoket;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.CRC32;

/** Read-only verification of user-selected recording; never connects to Gem or sends ACK. */
public final class MemoketStopAudioVerifier {
    private MemoketStopAudioVerifier() {}

    public static JSONObject verify(Context context, Uri uri, JSONObject trial) throws Exception {
        if (trial == null || !trial.optString("caseId").startsWith("STOP_")) {
            throw new IllegalArgumentException("停止テストの結果を選択してください");
        }
        String name = "";
        long size = -1;
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameColumn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !c.isNull(nameColumn)) name = c.getString(nameColumn);
                if (sizeColumn >= 0 && !c.isNull(sizeColumn)) size = c.getLong(sizeColumn);
            }
        }
        if (name.isEmpty()) name = uri.getLastPathSegment() == null ? "unknown" : uri.getLastPathSegment();
        long duration = -1;
        if (name.toLowerCase(Locale.ROOT).endsWith(".opus") ||
                name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IOException("ファイルを開けません");
                duration = oggDurationMs(stream);
            } catch (IOException ignored) {
                // Some user exports are not Ogg containers: try Android MediaMetadataRetriever.
            }
        }
        if (duration <= 0) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(context, uri);
                String value = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (value != null) duration = Long.parseLong(value);
            } finally {
                mmr.release();
            }
        }
        if (duration <= 0) throw new IOException("音声の再生時間を特定できません");
        CRC32 crc32 = new CRC32();
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("ファイルを読み取れません");
            byte[] buffer = new byte[16384];
            int n;
            while ((n = stream.read(buffer)) != -1) crc32.update(buffer, 0, n);
        }
        long startAt = trial.optLong("startCommandAtMs");
        long candidateAt = trial.optLong("candidateAtMs");
        long endAt = trial.optLong("observationEndedAtMs");
        if (startAt <= 0 || candidateAt <= startAt || endAt <= candidateAt)
            throw new IllegalArgumentException("開始・候補実行・観測時刻が不足しています");
        long untilCandidate = candidateAt - startAt;
        long untilEnd = endAt - startAt;

        boolean matches = false;
        long fileStartedAt = -1;
        if (name.length() >= 15 && name.substring(0, 15).matches("[0-9]{8}_[0-9]{6}")) {
            SimpleDateFormat date = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            date.setLenient(false);
            Date parsed = date.parse(name.substring(0, 15));
            if (parsed != null) {
                fileStartedAt = parsed.getTime();
                matches = Math.abs(fileStartedAt - startAt) <= 120_000;
            }
        }
        String verdict;
        if (!matches) {
            verdict = "録音時刻がテストと照合できません。別ファイルの可能性があるため停止判定は保留";
        } else if (duration >= untilCandidate + 4_000) {
            verdict = "録音継続と整合（候補時刻を4秒以上超える音声あり）";
        } else if (Math.abs(duration - untilCandidate) <= 2_000 && duration < untilEnd - 3_000) {
            verdict = "候補時点の停止と整合。ただし再録音や別ファイルの有無も確認が必要";
        } else {
            verdict = "録音長からは停止を判定できません";
        }
        return new JSONObject()
                .put("fileName", name).put("fileBytes", size)
                .put("durationMs", duration)
                .put("localFileCrc32", String.format(Locale.ROOT, "%08x", crc32.getValue()))
                .put("localCrc32IsDeviceCrc32", false)
                .put("candidateElapsedMs", untilCandidate)
                .put("observedElapsedMs", untilEnd)
                .put("fileStartedAtMs", fileStartedAt)
                .put("fileMatchesTrial", matches)
                .put("inference", verdict)
                .put("verifiedAtMs", System.currentTimeMillis());
    }

    private static long oggDurationMs(InputStream stream) throws IOException {
        long lastGranule = -1;
        int preSkip = 0;
        byte[] header = new byte[27];
        int pages = 0;
        while (true) {
            int n = readFull(stream, header, 27);
            if (n == 0) break;
            if (n != 27 || header[0] != 'O' || header[1] != 'g' ||
                    header[2] != 'g' || header[3] != 'S') {
                throw new IOException("OggS形式ではありません");
            }
            int segments = header[26] & 0xff;
            byte[] lacing = new byte[segments];
            if (readFull(stream, lacing, segments) != segments)
                throw new IOException("Oggセグメントヘッダが不完全です");
            int bodyBytes = 0;
            for (byte b : lacing) bodyBytes += b & 0xff;
            if (pages == 0) {
                byte[] first = new byte[Math.min(32, bodyBytes)];
                if (readFull(stream, first, first.length) != first.length)
                    throw new IOException("Oggデータが不完全です");
                if (first.length >= 12 && new String(first, 0, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead")) {
                    preSkip = (first[10] & 0xff) | ((first[11] & 0xff) << 8);
                }
                skipFull(stream, bodyBytes - first.length);
            } else {
                skipFull(stream, bodyBytes);
            }
            long granule = 0;
            for (int i = 7; i >= 0; i--) granule = (granule << 8) | (header[6 + i] & 0xffL);
            if (granule >= 0) lastGranule = granule;
            pages++;
        }
        if (pages == 0 || lastGranule < preSkip)
            throw new IOException("Ogg Opusの終端時刻が見つかりません");
        return (lastGranule - preSkip) / 48L;
    }

    private static int readFull(InputStream in, byte[] out, int count) throws IOException {
        int offset = 0;
        while (offset < count) {
            int n = in.read(out, offset, count - offset);
            if (n == -1) break;
            if (n == 0) continue;
            offset += n;
        }
        return offset;
    }

    private static void skipFull(InputStream in, int count) throws IOException {
        byte[] buffer = new byte[4096];
        while (count > 0) {
            int n = in.read(buffer, 0, Math.min(count, buffer.length));
            if (n <= 0) throw new IOException("Oggページ本体が不完全です");
            count -= n;
        }
    }
}
