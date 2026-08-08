package com.sktpj.recorder24h.transcription;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

final class M4aPcmDecoder {
    static final int WHISPER_SAMPLE_RATE = 16_000;

    private M4aPcmDecoder() {
    }

    static float[] decode(File source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = findAudioTrack(extractor);
            if (track < 0) {
                throw new IOException("No audio track in " + source.getName());
            }
            extractor.selectTrack(track);
            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("Audio MIME type missing");
            }

            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            FloatCollector collector = new FloatCollector(Math.max(WHISPER_SAMPLE_RATE * 30, 65_536));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000L);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) {
                            throw new IOException("Decoder input buffer unavailable");
                        }
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10_000L);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0) {
                        ByteBuffer data = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        appendPcm(collector, data.slice().order(ByteOrder.LITTLE_ENDIAN),
                                channelCount, pcmEncoding);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }

            float[] mono = collector.toArray();
            if (sampleRate == WHISPER_SAMPLE_RATE) {
                return mono;
            }
            return resampleLinear(mono, sampleRate, WHISPER_SAMPLE_RATE);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static void appendPcm(FloatCollector collector, ByteBuffer data,
                                  int channelCount, int pcmEncoding) throws IOException {
        int channels = Math.max(1, channelCount);
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatBuffer floats = data.asFloatBuffer();
            int frames = floats.remaining() / channels;
            for (int frame = 0; frame < frames; frame++) {
                float sum = 0f;
                for (int channel = 0; channel < channels; channel++) {
                    sum += floats.get();
                }
                collector.add(sum / channels);
            }
            return;
        }
        if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
            throw new IOException("Unsupported decoded PCM encoding: " + pcmEncoding);
        }
        ShortBuffer shorts = data.asShortBuffer();
        int frames = shorts.remaining() / channels;
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += shorts.get();
            }
            collector.add((sum / (float) channels) / 32768.0f);
        }
    }

    private static float[] resampleLinear(float[] input, int sourceRate, int targetRate) {
        if (input.length == 0 || sourceRate <= 0 || sourceRate == targetRate) {
            return input;
        }
        int outputLength = Math.max(1,
                (int) Math.round(input.length * (targetRate / (double) sourceRate)));
        float[] output = new float[outputLength];
        double scale = sourceRate / (double) targetRate;
        for (int i = 0; i < outputLength; i++) {
            double position = i * scale;
            int left = Math.min(input.length - 1, (int) position);
            int right = Math.min(input.length - 1, left + 1);
            float fraction = (float) (position - left);
            output[i] = input[left] + (input[right] - input[left]) * fraction;
        }
        return output;
    }

    private static final class FloatCollector {
        private float[] data;
        private int size;

        FloatCollector(int initialCapacity) {
            data = new float[initialCapacity];
        }

        void add(float value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
