// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Decodes compressed recordings (.ogg Opus/Vorbis, .mp3) using Android's built-in
 * MediaExtractor + MediaCodec for full-track waveform preview generation.
 */
public final class CompressedAudioDecoder {

    private CompressedAudioDecoder() {}

    public static boolean isCompressedName(String fileName) {
        if (fileName == null) return false;
        String low = fileName.toLowerCase(Locale.US);
        return low.endsWith(".ogg") || low.endsWith(".mp3") || low.endsWith(".flac");
    }

    public static WaveformAnalyzer.WaveformData analyze(Context ctx, Uri uri, String fileName, int targetBins)
            throws IOException {
        return analyze(ctx, uri, fileName, targetBins, null);
    }

    public static WaveformAnalyzer.WaveformData analyze(Context ctx, Uri uri, String fileName,
                                                        int targetBins, ProgressListener progress)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        ParcelFileDescriptor pfd = null;

        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Cannot open file");
            extractor.setDataSource(pfd.getFileDescriptor());

            int trackIdx = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i;
                    format = f;
                    break;
                }
            }
            if (trackIdx < 0 || format == null) {
                throw new IOException("No audio track found in " + fileName);
            }
            extractor.selectTrack(trackIdx);

            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
            int bitrate = format.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 0;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            // Accumulate per-chunk stats then resample into exact `targetBins`
            // (robust even when container durationUs is approximate)
            int maxRawBins = Math.max(targetBins * 4, 2048);
            float[] rawPeaks = new float[maxRawBins];
            double[] rawSumSq = new double[maxRawBins];
            long[] rawCount = new long[maxRawBins];
            int rawIdx = 0;
            long framesPerRawBin = Math.max(64L,
                    durationUs > 0 ? ((durationUs * sampleRate / 1000000L) / maxRawBins) : 256L);
            long curBinFrames = 0L;
            long totalDecodedFrames = 0L;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastNotify = 0L;
            if (progress != null) progress.onProgress(0L, Math.max(1L, durationUs), durationUs > 0 ? 0 : -1);

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            int n = extractor.readSampleData(inBuf, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN);
                        outBuf.limit(info.offset + info.size);
                        outBuf.position(info.offset);
                        int bpf = channels * 2;
                        int frames = info.size / bpf;
                        for (int f = 0; f < frames; f++) {
                            int off = info.offset + f * bpf;
                            for (int c = 0; c < channels; c++) {
                                float s = outBuf.getShort(off + c * 2) * (1.0f / 32768.0f);
                                float a = Math.abs(s);
                                if (a > rawPeaks[rawIdx]) rawPeaks[rawIdx] = a;
                                rawSumSq[rawIdx] += (double) s * (double) s;
                                rawCount[rawIdx]++;
                            }
                            curBinFrames++;
                            totalDecodedFrames++;
                            if (curBinFrames >= framesPerRawBin) {
                                curBinFrames = 0L;
                                if (rawIdx + 1 < maxRawBins) {
                                    rawIdx++;
                                } else {
                                    // Compact pairs by 2x if track was longer than estimated
                                    int half = maxRawBins / 2;
                                    for (int k = 0; k < half; k++) {
                                        rawPeaks[k] = Math.max(rawPeaks[2 * k], rawPeaks[2 * k + 1]);
                                        rawSumSq[k] = rawSumSq[2 * k] + rawSumSq[2 * k + 1];
                                        rawCount[k] = rawCount[2 * k] + rawCount[2 * k + 1];
                                    }
                                    for (int k = half; k < maxRawBins; k++) {
                                        rawPeaks[k] = 0f;
                                        rawSumSq[k] = 0.0;
                                        rawCount[k] = 0L;
                                    }
                                    rawIdx = half;
                                    framesPerRawBin *= 2L;
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if (progress != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotify >= 60L) {
                            lastNotify = now;
                            if (durationUs > 0L) {
                                long pts = Math.max(0L, info.presentationTimeUs);
                                int pct = (int) Math.min(99L, (pts * 100L) / durationUs);
                                progress.onProgress(pts, durationUs, pct);
                            } else {
                                progress.onProgress(totalDecodedFrames, 0L, -1);
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (progress != null) progress.onProgress(Math.max(1L, durationUs), Math.max(1L, durationUs), 100);

            int usedRaw = Math.max(1, rawIdx + (rawCount[rawIdx] > 0 ? 1 : 0));
            int bins = Math.max(16, Math.min(targetBins, usedRaw));
            float[] peaks = new float[bins];
            float[] rms = new float[bins];
            float globalMax = 0f;

            for (int b = 0; b < bins; b++) {
                int rStart = (b * usedRaw) / bins;
                int rEnd = Math.max(rStart + 1, ((b + 1) * usedRaw) / bins);
                float pk = 0f;
                double sq = 0.0;
                long cnt = 0L;
                for (int r = rStart; r < rEnd && r < usedRaw; r++) {
                    if (rawPeaks[r] > pk) pk = rawPeaks[r];
                    sq += rawSumSq[r];
                    cnt += rawCount[r];
                }
                peaks[b] = pk;
                rms[b] = cnt > 0 ? (float) Math.sqrt(sq / cnt) : 0f;
                if (pk > globalMax) globalMax = pk;
            }

            String low = fileName.toLowerCase(Locale.US);
            String codecTag = low.endsWith(".flac") ? "FLAC"
                    : (low.endsWith(".ogg") ? "OGG OPUS" : "MP3");
            if (bitrate > 0 && !low.endsWith(".flac")) {
                codecTag += " " + Math.round(bitrate / 1000f) + "k";
            }
            long actualDurMs = (sampleRate > 0 && totalDecodedFrames > 0)
                    ? (totalDecodedFrames * 1000L / sampleRate)
                    : (durationUs / 1000L);

            WavWriter.AudioFileMeta meta = WavWriter.AudioFileMeta.forCompressed(
                    sampleRate, channels, actualDurMs, codecTag);
            return new WaveformAnalyzer.WaveformData(peaks, rms, meta, globalMax);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            if (pfd != null) {
                try { pfd.close(); } catch (IOException ignored) {}
            }
        }
    }
}
