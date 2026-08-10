package com.sktpj.recorder24h.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;

import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AacSegmentRecorder {
    public interface Listener {
        void onSegmentChanged(String segmentId, File file);
        void onFatalError(String message, Throwable error);
    }

    public static final int SAMPLE_RATE_HZ = 16_000;
    public static final int CHANNEL_COUNT = 1;
    public static final int BIT_RATE_BPS = 32_000;
    public static final long SEGMENT_DURATION_US = 5L * 60L * 1_000_000L;

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private AudioRecord audioRecord;
    private MediaCodec encoder;
    private MediaFormat encoderOutputFormat;
    private MediaMuxer muxer;
    private int muxerTrackIndex = -1;
    private boolean muxerStarted;
    private boolean wroteSamples;
    private File currentPartFile;
    private String currentSegmentId;
    private long currentSegmentStartedAtMs;
    private long currentSegmentBasePtsUs;
    private long totalPcmFrames;
    private long lastHeartbeatMs;
    private volatile long lastAudioReadMs;
    private AudioManager.AudioRecordingCallback recordingCallback;

    public AacSegmentRecorder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void requestStop() {
        stopRequested.set(true);
        AudioRecord record = audioRecord;
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void run() {
        try {
            StoragePolicy.enforce(context);
            audioRecord = createAudioRecord();
            encoder = createEncoder();
            registerRecordingCallback(audioRecord);

            encoder.start();
            audioRecord.startRecording();
            AppLogger.event(context, "AUDIO_RECORD_STARTED", audioConfigJson(audioRecord));

            RecorderStateStore.write(context, "RECORDING", null, null);
            encodeLoop();
            AppLogger.event(context, "AUDIO_RECORD_STOPPED");
        } catch (Throwable t) {
            AppLogger.event(context, "AUDIO_RECORD_FATAL", errorJson(t));
            if (listener != null) {
                listener.onFatalError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
            }
        } finally {
            cleanup();
        }
    }

    private AudioRecord createAudioRecord() throws IOException {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = SAMPLE_RATE_HZ * 2;
        }
        int bufferSize = Math.max(minBuffer * 4, SAMPLE_RATE_HZ * 2);

        AudioRecord record = buildAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize);
        if (record.getState() == AudioRecord.STATE_INITIALIZED) {
            return record;
        }
        record.release();

        AppLogger.event(context, "VOICE_RECOGNITION_SOURCE_UNAVAILABLE_FALLBACK_MIC");
        record = buildAudioRecord(MediaRecorder.AudioSource.MIC, bufferSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IOException("AudioRecord initialization failed for VOICE_RECOGNITION and MIC");
        }
        return record;
    }

    private AudioRecord buildAudioRecord(int source, int bufferSize) {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        return new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build();
    }

    private MediaCodec createEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE_HZ, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_BPS);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE_HZ * 2);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return codec;
    }

    private void encodeLoop() throws IOException {
        final int pcmChunkBytes = 3_200;
        byte[] pcm = new byte[pcmChunkBytes];
        MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
        boolean eosQueued = false;
        boolean eosReceived = false;

        while (!eosReceived) {
            if (!stopRequested.get() && !eosQueued) {
                feedPcm(pcm);
            } else if (!eosQueued) {
                eosQueued = queueEndOfStream();
            }

            boolean drainedAny = false;
            while (true) {
                int outIndex = encoder.dequeueOutputBuffer(outputInfo, 0);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encoderOutputFormat = encoder.getOutputFormat();
                    AppLogger.event(context, "AAC_ENCODER_FORMAT_READY", mediaFormatJson(encoderOutputFormat));
                    continue;
                }
                if (outIndex < 0) {
                    continue;
                }

                drainedAny = true;
                ByteBuffer outBuffer = encoder.getOutputBuffer(outIndex);
                if (outBuffer != null && outputInfo.size > 0
                        && (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    writeEncodedSample(outBuffer, outputInfo);
                }
                eosReceived = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(outIndex, false);
                if (eosReceived) {
                    break;
                }
            }

            heartbeatIfNeeded();
            if (eosQueued && !drainedAny && !eosReceived) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        closeCurrentSegment("READY");
    }

    private void feedPcm(byte[] pcm) throws IOException {
        int inputIndex = encoder.dequeueInputBuffer(20_000);
        if (inputIndex < 0) {
            return;
        }
        ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            encoder.queueInputBuffer(inputIndex, 0, 0, pcmPresentationTimeUs(), 0);
            return;
        }

        int maxRead = Math.min(pcm.length, inputBuffer.capacity());
        int read;
        try {
            read = audioRecord.read(pcm, 0, maxRead, AudioRecord.READ_BLOCKING);
        } catch (IllegalStateException stoppedWhileReading) {
            if (stopRequested.get()) {
                encoder.queueInputBuffer(inputIndex, 0, 0, pcmPresentationTimeUs(), 0);
                return;
            }
            throw stoppedWhileReading;
        }

        if (read == AudioRecord.ERROR_DEAD_OBJECT) {
            throw new IOException("AudioRecord dead object");
        }
        if (read < 0) {
            throw new IOException("AudioRecord read failed: " + read);
        }
        if (read == 0) {
            encoder.queueInputBuffer(inputIndex, 0, 0, pcmPresentationTimeUs(), 0);
            return;
        }

        lastAudioReadMs = System.currentTimeMillis();
        inputBuffer.clear();
        inputBuffer.put(pcm, 0, read);
        long ptsUs = pcmPresentationTimeUs();
        encoder.queueInputBuffer(inputIndex, 0, read, ptsUs, 0);
        totalPcmFrames += read / (2L * CHANNEL_COUNT);
    }

    private boolean queueEndOfStream() {
        int index = encoder.dequeueInputBuffer(20_000);
        if (index < 0) {
            return false;
        }
        encoder.queueInputBuffer(index, 0, 0, pcmPresentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        return true;
    }

    private long pcmPresentationTimeUs() {
        return totalPcmFrames * 1_000_000L / SAMPLE_RATE_HZ;
    }

    private void writeEncodedSample(ByteBuffer outBuffer, MediaCodec.BufferInfo info) throws IOException {
        if (encoderOutputFormat == null) {
            throw new IOException("Encoder output format not available before sample");
        }

        if (muxer == null) {
            openSegment(info.presentationTimeUs);
        } else if ((info.presentationTimeUs - currentSegmentBasePtsUs) >= SEGMENT_DURATION_US) {
            RecorderStateStore.write(context, "ROTATING", currentSegmentId, null);
            closeCurrentSegment("READY");
            StoragePolicy.enforce(context);
            openSegment(info.presentationTimeUs);
        }

        outBuffer.position(info.offset);
        outBuffer.limit(info.offset + info.size);

        MediaCodec.BufferInfo adjusted = new MediaCodec.BufferInfo();
        long adjustedPts = Math.max(0L, info.presentationTimeUs - currentSegmentBasePtsUs);
        adjusted.set(info.offset, info.size, adjustedPts, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(muxerTrackIndex, outBuffer, adjusted);
        wroteSamples = true;
    }

    private void openSegment(long basePtsUs) throws IOException {
        StoragePolicy.enforce(context);
        currentSegmentId = UUID.randomUUID().toString();
        currentSegmentStartedAtMs = System.currentTimeMillis();
        currentSegmentBasePtsUs = basePtsUs;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(currentSegmentStartedAtMs));
        currentPartFile = new File(StoragePolicy.getAudioDir(context), timestamp + "_" + currentSegmentId + ".m4a.part");

        muxer = new MediaMuxer(currentPartFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerTrackIndex = muxer.addTrack(encoderOutputFormat);
        muxer.start();
        muxerStarted = true;
        wroteSamples = false;

        RecorderStateStore.segmentStarted(
                context, currentSegmentId, currentSegmentStartedAtMs, lastAudioReadMs);
        if (listener != null) {
            listener.onSegmentChanged(currentSegmentId, currentPartFile);
        }
        try {
            JSONObject d = new JSONObject();
            d.put("segmentId", currentSegmentId);
            d.put("file", currentPartFile.getName());
            AppLogger.event(context, "SEGMENT_STARTED", d);
        } catch (Exception ignored) {
        }
    }

    private void closeCurrentSegment(String status) {
        if (muxer == null) {
            return;
        }

        File part = currentPartFile;
        String segmentId = currentSegmentId;
        long startedAt = currentSegmentStartedAtMs;
        long endedAt = System.currentTimeMillis();
        try {
            if (muxerStarted && wroteSamples) {
                muxer.stop();
            }
        } catch (Exception stopError) {
            status = "CORRUPT";
            AppLogger.event(context, "SEGMENT_MUXER_STOP_FAILED", errorJson(stopError));
        } finally {
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
            muxerStarted = false;
            wroteSamples = false;
            muxerTrackIndex = -1;
        }

        if (part == null) {
            return;
        }

        File finalFile;
        if ("READY".equals(status)) {
            finalFile = new File(part.getParentFile(), part.getName().replace(".m4a.part", ".m4a"));
        } else {
            finalFile = new File(part.getParentFile(), part.getName().replace(".m4a.part", ".m4a.corrupt"));
        }

        boolean renamed = part.renameTo(finalFile);
        if (!renamed) {
            finalFile = part;
            status = "CORRUPT";
        }

        SegmentRepository.append(context, segmentId, finalFile, startedAt, endedAt, status,
                "READY".equals(status) ? null : "MUXER_OR_RENAME_FAILURE");
        try {
            JSONObject d = new JSONObject();
            d.put("segmentId", segmentId);
            d.put("file", finalFile.getName());
            d.put("status", status);
            d.put("sizeBytes", finalFile.length());
            d.put("durationMs", Math.max(0L, endedAt - startedAt));
            AppLogger.event(context, "SEGMENT_FINALIZED", d);
        } catch (Exception ignored) {
        }
        RecorderStateStore.segmentFinalized(
                context, segmentId, endedAt, Math.max(0L, endedAt - startedAt));

        currentPartFile = null;
        currentSegmentId = null;
        currentSegmentStartedAtMs = 0L;
        currentSegmentBasePtsUs = 0L;
    }

    private void registerRecordingCallback(AudioRecord record) {
        Executor executor = context.getMainExecutor();
        recordingCallback = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                int sessionId = record.getAudioSessionId();
                for (AudioRecordingConfiguration config : configs) {
                    if (config.getClientAudioSessionId() == sessionId) {
                        try {
                            JSONObject d = new JSONObject();
                            d.put("sessionId", sessionId);
                            d.put("clientAudioSource", config.getClientAudioSource());
                            d.put("audioSource", config.getAudioSource());
                            d.put("silenced", config.isClientSilenced());
                            d.put("deviceId", config.getAudioDevice() == null ? JSONObject.NULL : config.getAudioDevice().getId());
                            RecorderStateStore.setCaptureSilenced(context, config.isClientSilenced());
                            AppLogger.event(context,
                                    config.isClientSilenced() ? "CAPTURE_SILENCED" : "CAPTURE_CONFIGURATION",
                                    d);
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                }
            }
        };
        record.registerAudioRecordingCallback(executor, recordingCallback);
    }

    private void heartbeatIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMs < 5_000L) {
            return;
        }
        lastHeartbeatMs = now;
        RecorderStateStore.heartbeat(
                context, "RECORDING", currentSegmentId, currentSegmentStartedAtMs, lastAudioReadMs);
        try {
            JSONObject d = new JSONObject();
            d.put("segmentId", currentSegmentId == null ? JSONObject.NULL : currentSegmentId);
            d.put("audioBytes", StoragePolicy.audioBytes(context));
            d.put("appBytes", StoragePolicy.appDataBytes(context));
            d.put("usableBytes", context.getFilesDir().getUsableSpace());
            AppLogger.event(context, "RECORDER_HEARTBEAT", d);
        } catch (Exception ignored) {
        }
    }

    private void cleanup() {
        try {
            if (recordingCallback != null && audioRecord != null) {
                audioRecord.unregisterAudioRecordingCallback(recordingCallback);
            }
        } catch (Exception ignored) {
        }
        try {
            closeCurrentSegment(stopRequested.get() ? "READY" : "CORRUPT");
        } catch (Exception ignored) {
        }
        try {
            if (audioRecord != null) {
                audioRecord.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }
        audioRecord = null;
        encoder = null;
    }

    private JSONObject audioConfigJson(AudioRecord record) {
        JSONObject d = new JSONObject();
        try {
            d.put("sampleRateHz", SAMPLE_RATE_HZ);
            d.put("channelCount", CHANNEL_COUNT);
            d.put("bitRateBps", BIT_RATE_BPS);
            d.put("audioSource", record.getAudioSource());
            d.put("sessionId", record.getAudioSessionId());
        } catch (Exception ignored) {
        }
        return d;
    }

    private JSONObject mediaFormatJson(MediaFormat format) {
        JSONObject d = new JSONObject();
        try {
            d.put("format", format.toString());
        } catch (Exception ignored) {
        }
        return d;
    }

    private JSONObject errorJson(Throwable t) {
        JSONObject d = new JSONObject();
        try {
            d.put("type", t.getClass().getName());
            d.put("message", t.getMessage() == null ? JSONObject.NULL : t.getMessage());
        } catch (Exception ignored) {
        }
        return d;
    }
}
