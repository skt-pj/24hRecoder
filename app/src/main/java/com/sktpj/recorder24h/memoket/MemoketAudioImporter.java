package com.sktpj.recorder24h.memoket;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.AudioFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class MemoketAudioImporter {
    private MemoketAudioImporter() {}

    public static long convert(File opus, File target) throws Exception {
        File pcm = new File(target.getParentFile(), target.getName() + ".pcm.part");
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try {
            int[] format = decodeOpus(opus, pcm);
            long durationMs = encodeAac(pcm, part, format[0], format[1]);
            if (durationMs <= 0 || part.length() <= 0) throw new IOException("Gem audio contained no samples");
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return durationMs;
        } finally {
            pcm.delete();
            part.delete();
        }
    }

    private static int[] decodeOpus(File opus, File pcm) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        int sampleRate = 0;
        int channelCount = 0;
        boolean inputDone = false, outputDone = false;
        long deadline = System.currentTimeMillis() + 180_000L;
        try (FileOutputStream out = new FileOutputStream(pcm)) {
            extractor.setDataSource(opus.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = candidate;
                    break;
                }
            }
            if (format == null) throw new IOException("Gem audio track missing");
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw new IOException("Gem decode timeout");
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = decoder.getOutputFormat();
                    sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channelCount = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && actual.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                        throw new IOException("Unsupported Gem PCM format");
                    if (sampleRate <= 0 || channelCount < 1 || channelCount > 2)
                        throw new IOException("Unsupported Gem audio channels");
                } else if (index >= 0) {
                    if (info.size > 0) {
                        ByteBuffer data = decoder.getOutputBuffer(index);
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size];
                        data.get(bytes);
                        out.write(bytes);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(index, false);
                }
            }
            out.flush();
            out.getFD().sync();
            if (sampleRate <= 0 || channelCount <= 0 || pcm.length() <= 0)
                throw new IOException("Gem decoder output empty");
            return new int[]{sampleRate, channelCount};
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            extractor.release();
        }
    }

    private static long encodeAac(File pcm, File output, int sampleRate, int channels) throws Exception {
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer muxer = null;
        boolean inputDone = false, outputDone = false, started = false;
        int track = -1;
        long fedBytes = 0;
        long deadline = System.currentTimeMillis() + 180_000L;
        final int bytesPerFrame = 2 * channels;
        try (FileInputStream input = new FileInputStream(pcm)) {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32_000 * channels);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[16_384];
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw new IOException("Gem AAC encode timeout");
                if (!inputDone) {
                    int index = encoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(index);
                        buffer.clear();
                        int length = Math.min(buffer.remaining(), chunk.length);
                        length -= length % bytesPerFrame;
                        int read = input.read(chunk, 0, length);
                        long pts = fedBytes / bytesPerFrame * 1_000_000L / sampleRate;
                        if (read < 0) {
                            encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buffer.put(chunk, 0, read);
                            encoder.queueInputBuffer(index, 0, read, pts, 0);
                            fedBytes += read;
                        }
                    }
                }
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    started = true;
                } else if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!started) throw new IOException("Gem AAC output not configured");
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(track, data, info);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false);
                }
            }
            if (!started || fedBytes <= 0) throw new IOException("Gem AAC output empty");
            muxer.stop();
            started = false;
            return fedBytes / bytesPerFrame * 1_000L / sampleRate;
        } finally {
            try { encoder.stop(); } catch (Exception ignored) {}
            encoder.release();
            if (muxer != null) {
                if (started) try { muxer.stop(); } catch (Exception ignored) {}
                muxer.release();
            }
        }
    }
}
