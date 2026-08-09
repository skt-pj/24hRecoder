package com.sktpj.recorder24h.transcription;

import java.util.Arrays;

/**
 * Lightweight, deterministic ASR front-end for long-lived phone recordings.
 *
 * <p>The goal is not to "beautify" audio. It only corrects obviously poor level before VAD/ASR,
 * while avoiding aggressive denoising that can remove speech cues. Processing is performed after
 * M4A decode and before Silero VAD + Whisper.</p>
 */
final class AudioPreprocessor {
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SAMPLES = SAMPLE_RATE / 50; // 20 ms

    // Speech level target chosen as a conservative initial operating point for speech-recognition
    // input. It is intentionally bounded so quiet ambient noise cannot be amplified without limit.
    private static final double TARGET_SPEECH_DBFS = -22.0;
    private static final double MAX_GAIN_DB = 12.0;
    private static final double MIN_GAIN_DB = -6.0;

    // Energy-only speech proxy. Positive gain is suppressed when the estimated speech/noise
    // separation is weak because gain cannot improve SNR and can make VAD hallucinate on noise.
    private static final double MIN_ACTIVE_FRAME_DBFS = -50.0;
    private static final double ACTIVE_OVER_NOISE_DB = 9.0;
    private static final double MIN_SNR_FOR_BOOST_DB = 6.0;
    private static final int MIN_ACTIVE_FRAMES = 25; // 0.5 s total activity

    // Smooth peak limiter. Samples below the knee are untouched; samples above it approach the
    // -1 dBFS ceiling asymptotically instead of hard clipping.
    private static final double LIMITER_KNEE = dbToLinear(-2.5);
    private static final double LIMITER_CEILING = dbToLinear(-1.0);

    private AudioPreprocessor() {
    }

    static Result process(float[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("Audio samples are empty");
        }

        Stats inputStats = measure(samples);

        // Remove tiny constant microphone/codec bias. This is not a high-pass filter and therefore
        // does not reshape the speech spectrum.
        double mean = 0.0;
        for (float sample : samples) {
            mean += sample;
        }
        mean /= samples.length;
        if (Math.abs(mean) > 1.0e-9) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] = clampUnit((float) (samples[i] - mean));
            }
        }

        double[] frameRms = frameRms(samples);
        double noiseRms = percentile(frameRms, 0.20);
        double activityThreshold = Math.max(
                dbToLinear(MIN_ACTIVE_FRAME_DBFS),
                noiseRms * dbToLinear(ACTIVE_OVER_NOISE_DB));

        double[] active = new double[frameRms.length];
        int activeCount = 0;
        for (double rms : frameRms) {
            if (rms >= activityThreshold) {
                active[activeCount++] = rms;
            }
        }

        double speechRms = 0.0;
        double snrProxyDb = 0.0;
        double gainDb = 0.0;
        boolean boostSuppressedForLowSnr = false;

        if (activeCount >= MIN_ACTIVE_FRAMES) {
            active = Arrays.copyOf(active, activeCount);
            speechRms = percentile(active, 0.60);
            if (noiseRms > 1.0e-9 && speechRms > 1.0e-9) {
                snrProxyDb = linearToDb(speechRms / noiseRms);
            } else if (speechRms > 1.0e-9) {
                snrProxyDb = 60.0;
            }

            if (speechRms > 1.0e-9) {
                gainDb = TARGET_SPEECH_DBFS - linearToDb(speechRms);
                gainDb = clamp(gainDb, MIN_GAIN_DB, MAX_GAIN_DB);
                if (gainDb > 0.0 && snrProxyDb < MIN_SNR_FOR_BOOST_DB) {
                    gainDb = 0.0;
                    boostSuppressedForLowSnr = true;
                }
            }
        }

        double gain = dbToLinear(gainDb);
        long limited = 0L;
        for (int i = 0; i < samples.length; i++) {
            double amplified = samples[i] * gain;
            double absolute = Math.abs(amplified);
            if (absolute > LIMITER_KNEE) {
                limited++;
                double headroom = LIMITER_CEILING - LIMITER_KNEE;
                double compressed = LIMITER_KNEE +
                        headroom * (1.0 - Math.exp(-(absolute - LIMITER_KNEE) / headroom));
                amplified = Math.copySign(compressed, amplified);
            }
            samples[i] = clampUnit((float) amplified);
        }

        Stats outputStats = measure(samples);
        double activeFraction = frameRms.length == 0 ? 0.0 : activeCount / (double) frameRms.length;
        double limitedFraction = limited / (double) samples.length;

        return new Result(
                samples,
                inputStats,
                outputStats,
                mean,
                noiseRms,
                speechRms,
                snrProxyDb,
                gainDb,
                activeFraction,
                limitedFraction,
                boostSuppressedForLowSnr);
    }

    private static double[] frameRms(float[] samples) {
        int frameCount = Math.max(1, (samples.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES);
        double[] result = new double[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * FRAME_SAMPLES;
            int end = Math.min(samples.length, start + FRAME_SAMPLES);
            double sumSquares = 0.0;
            for (int i = start; i < end; i++) {
                double sample = samples[i];
                sumSquares += sample * sample;
            }
            int count = Math.max(1, end - start);
            result[frame] = Math.sqrt(sumSquares / count);
        }
        return result;
    }

    private static Stats measure(float[] samples) {
        double sumSquares = 0.0;
        double peak = 0.0;
        long clipped = 0L;
        for (float sample : samples) {
            double absolute = Math.abs(sample);
            sumSquares += (double) sample * sample;
            peak = Math.max(peak, absolute);
            if (absolute >= 0.99) {
                clipped++;
            }
        }
        return new Stats(
                Math.sqrt(sumSquares / samples.length),
                peak,
                clipped / (double) samples.length);
    }

    private static double percentile(double[] values, double q) {
        if (values.length == 0) {
            return 0.0;
        }
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        double position = clamp(q, 0.0, 1.0) * (sorted.length - 1);
        int left = (int) Math.floor(position);
        int right = Math.min(sorted.length - 1, left + 1);
        double fraction = position - left;
        return sorted[left] + (sorted[right] - sorted[left]) * fraction;
    }

    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    private static double linearToDb(double linear) {
        return 20.0 * Math.log10(Math.max(linear, 1.0e-12));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampUnit(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    static final class Result {
        final float[] samples;
        final Stats input;
        final Stats output;
        final double dcOffset;
        final double estimatedNoiseRms;
        final double estimatedSpeechRms;
        final double snrProxyDb;
        final double appliedGainDb;
        final double activeFrameFraction;
        final double limitedSampleFraction;
        final boolean boostSuppressedForLowSnr;

        Result(float[] samples, Stats input, Stats output, double dcOffset,
               double estimatedNoiseRms, double estimatedSpeechRms, double snrProxyDb,
               double appliedGainDb, double activeFrameFraction, double limitedSampleFraction,
               boolean boostSuppressedForLowSnr) {
            this.samples = samples;
            this.input = input;
            this.output = output;
            this.dcOffset = dcOffset;
            this.estimatedNoiseRms = estimatedNoiseRms;
            this.estimatedSpeechRms = estimatedSpeechRms;
            this.snrProxyDb = snrProxyDb;
            this.appliedGainDb = appliedGainDb;
            this.activeFrameFraction = activeFrameFraction;
            this.limitedSampleFraction = limitedSampleFraction;
            this.boostSuppressedForLowSnr = boostSuppressedForLowSnr;
        }
    }

    static final class Stats {
        final double rms;
        final double peak;
        final double clippedFraction;

        Stats(double rms, double peak, double clippedFraction) {
            this.rms = rms;
            this.peak = peak;
            this.clippedFraction = clippedFraction;
        }
    }
}
