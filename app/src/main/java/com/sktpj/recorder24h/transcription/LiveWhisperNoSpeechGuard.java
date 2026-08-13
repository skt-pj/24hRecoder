package com.sktpj.recorder24h.transcription;

import android.content.Context;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative guard against Whisper hallucinations on silent/near-silent live utterances. */
final class LiveWhisperNoSpeechGuard {
    // Match the existing realtime activity gate's minimum activity floor. If both values are below
    // these limits, the captured PCM is not considered meaningful acoustic activity.
    private static final double MIN_ACTIVITY_RMS = 0.0012;
    private static final double MIN_ACTIVITY_PEAK = 0.0045;

    // A second, slightly wider low-signal band is only used together with Whisper's own
    // no-speech probability. This avoids rejecting ordinary quiet speech from energy alone.
    private static final double LOW_SIGNAL_RMS = 0.0025;
    private static final double LOW_SIGNAL_PEAK = 0.0100;
    private static final double NO_SPEECH_THRESHOLD = 0.60;
    private static final double STRONG_NO_SPEECH_THRESHOLD = 0.85;

    private LiveWhisperNoSpeechGuard() {
    }

    static InputStats measure(float[] samples) {
        if (samples == null || samples.length == 0) return new InputStats(0.0, 0.0);
        double sumSquares = 0.0;
        double peak = 0.0;
        for (float sample : samples) {
            double value = sample;
            sumSquares += value * value;
            peak = Math.max(peak, Math.abs(value));
        }
        return new InputStats(Math.sqrt(sumSquares / samples.length), peak);
    }

    static boolean shouldSkipBeforeAsr(Context context, String route, InputStats input) {
        if (input.rms <= MIN_ACTIVITY_RMS && input.peak <= MIN_ACTIVITY_PEAK) {
            logSuppressed(context, route, "pre-asr-low-signal", input,
                    0, -1.0, -1.0, 0);
            return true;
        }
        return false;
    }

    static Output filterAfterAsr(Context context, String route, InputStats input,
                                 String text, JSONArray segments) {
        String safeText = text == null ? "" : text.trim();
        JSONArray safeSegments = segments == null ? new JSONArray() : segments;
        if (safeText.isEmpty() || safeSegments.length() == 0) {
            return new Output(safeText, safeSegments);
        }

        int textSegmentCount = 0;
        int probabilityCount = 0;
        double minNoSpeech = 1.0;
        double maxNoSpeech = 0.0;
        boolean allNoSpeech = true;
        boolean allStrongNoSpeech = true;

        for (int i = 0; i < safeSegments.length(); i++) {
            JSONObject row = safeSegments.optJSONObject(i);
            if (row == null || row.optString("text", "").trim().isEmpty()) continue;
            textSegmentCount++;
            if (!row.has("noSpeechProbability")) {
                allNoSpeech = false;
                allStrongNoSpeech = false;
                continue;
            }
            double noSpeech = row.optDouble("noSpeechProbability", -1.0);
            if (noSpeech < 0.0) {
                allNoSpeech = false;
                allStrongNoSpeech = false;
                continue;
            }
            probabilityCount++;
            minNoSpeech = Math.min(minNoSpeech, noSpeech);
            maxNoSpeech = Math.max(maxNoSpeech, noSpeech);
            if (noSpeech < NO_SPEECH_THRESHOLD) allNoSpeech = false;
            if (noSpeech < STRONG_NO_SPEECH_THRESHOLD) allStrongNoSpeech = false;
        }

        if (textSegmentCount == 0 || probabilityCount != textSegmentCount) {
            return new Output(safeText, safeSegments);
        }

        boolean lowSignal = input.rms <= LOW_SIGNAL_RMS && input.peak <= LOW_SIGNAL_PEAK;
        String reason = null;
        if (allStrongNoSpeech) {
            reason = "post-asr-strong-no-speech";
        } else if (lowSignal && allNoSpeech) {
            reason = "post-asr-low-signal-no-speech";
        }

        if (reason == null) return new Output(safeText, safeSegments);

        logSuppressed(context, route, reason, input,
                textSegmentCount, minNoSpeech, maxNoSpeech, safeText.length());
        return new Output("", new JSONArray());
    }

    private static void logSuppressed(Context context, String route, String reason,
                                      InputStats input, int segmentCount,
                                      double minNoSpeech, double maxNoSpeech, int originalTextChars) {
        if (context == null) return;
        try {
            JSONObject details = new JSONObject()
                    .put("route", route)
                    .put("reason", reason)
                    .put("inputRms", input.rms)
                    .put("inputPeak", input.peak)
                    .put("segmentCount", segmentCount)
                    .put("originalTextChars", originalTextChars)
                    .put("automaticFallback", false);
            if (minNoSpeech >= 0.0) details.put("minNoSpeechProbability", minNoSpeech);
            if (maxNoSpeech >= 0.0) details.put("maxNoSpeechProbability", maxNoSpeech);
            AppLogger.event(context.getApplicationContext(),
                    "FULL_STREAMING_NO_SPEECH_SUPPRESSED", details);
        } catch (Exception ignored) {
        }
    }

    static final class InputStats {
        final double rms;
        final double peak;

        InputStats(double rms, double peak) {
            this.rms = rms;
            this.peak = peak;
        }
    }

    static final class Output {
        final String text;
        final JSONArray segments;

        Output(String text, JSONArray segments) {
            this.text = text;
            this.segments = segments;
        }
    }
}
