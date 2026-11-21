// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fast streaming Peak + RMS waveform extractor for WAV (16/24/32f) and RAW (.pcm) recordings.
 * Produces compact bin arrays for smooth 60/120fps AMOLED waveform rendering and touch scrubbing.
 */
public final class WaveformAnalyzer {

    public static final class WaveformData {
        public final float[] peaks;
        public final float[] rms;
        public final WavWriter.AudioFileMeta meta;
        public final float maxPeak;

        public WaveformData(float[] peaks, float[] rms, WavWriter.AudioFileMeta meta, float maxPeak) {
            this.peaks = peaks;
            this.rms = rms;
            this.meta = meta;
            this.maxPeak = maxPeak;
        }
    }

    private WaveformAnalyzer() {}

    public static WaveformData analyze(InputStream in, long totalFileSize, String fileName, int targetBins)
            throws IOException {
        return analyze(in, totalFileSize, fileName, targetBins, null);
    }

    public static WaveformData analyze(InputStream in, long totalFileSize, String fileName,
                                       int targetBins, ProgressListener progress) throws IOException {
        WavWriter.AudioFileMeta meta = WavWriter.parseMeta(in, totalFileSize, fileName);
        int bpf = meta.bytesPerFrame();
        long totalFrames = (bpf > 0) ? (meta.dataBytes / bpf) : 0L;
        int bins = Math.max(16, Math.min(targetBins, (int) Math.max(16L, Math.min(totalFrames, targetBins))));

        float[] peaks = new float[bins];
        float[] rms = new float[bins];
        if (totalFrames <= 0 || bpf <= 0) {
            if (progress != null) progress.onProgress(0L, 1L, 100);
            return new WaveformData(peaks, rms, meta, 0f);
        }

        int bps = meta.bitsPerSample / 8;
        int ch = meta.channels;
        int pcmFmt = meta.pcmFormat;

        int chunkFrames = Math.max(256, 131072 / bpf); // 128 KB blocks for fast long-file scans
        byte[] raw = new byte[chunkFrames * bpf];
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        long framesRead = 0L;
        long lastNotify = 0L;
        int curBin = 0;
        float binPeak = 0f;
        double binSumSq = 0.0;
        long binSamples = 0L;
        float globalMaxPeak = 0f;

        if (progress != null) progress.onProgress(0L, meta.dataBytes, 0);

        while (framesRead < totalFrames) {
            long remBytes = (totalFrames - framesRead) * bpf;
            int toRead = (int) Math.min(raw.length, remBytes);
            toRead -= (toRead % bpf);
            if (toRead <= 0) break;

            int got = readAtMost(in, raw, toRead);
            if (got <= 0) break;
            int usableFrames = got / bpf;

            for (int f = 0; f < usableFrames; f++) {
                long frameIdx = framesRead + f;
                int b = (int) ((frameIdx * bins) / totalFrames);
                if (b >= bins) b = bins - 1;

                if (b != curBin) {
                    peaks[curBin] = binPeak;
                    rms[curBin] = binSamples > 0 ? (float) Math.sqrt(binSumSq / binSamples) : 0f;
                    if (binPeak > globalMaxPeak) globalMaxPeak = binPeak;
                    for (int k = curBin + 1; k < b; k++) {
                        peaks[k] = binPeak;
                        rms[k] = rms[curBin];
                    }
                    curBin = b;
                    binPeak = 0f;
                    binSumSq = 0.0;
                    binSamples = 0L;
                }

                int frameOff = f * bpf;
                for (int c = 0; c < ch; c++) {
                    float s = readSample(bb, frameOff + c * bps, pcmFmt);
                    float a = Math.abs(s);
                    if (a > binPeak) binPeak = a;
                    binSumSq += (double) s * (double) s;
                    binSamples++;
                }
            }
            framesRead += usableFrames;

            if (progress != null) {
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 60L || framesRead >= totalFrames) {
                    lastNotify = now;
                    int pct = (int) Math.min(99L, (framesRead * 100L) / totalFrames);
                    progress.onProgress(framesRead * bpf, meta.dataBytes, pct);
                }
            }
        }

        if (curBin < bins) {
            peaks[curBin] = binPeak;
            rms[curBin] = binSamples > 0 ? (float) Math.sqrt(binSumSq / binSamples) : 0f;
            if (binPeak > globalMaxPeak) globalMaxPeak = binPeak;
        }
        if (progress != null) progress.onProgress(meta.dataBytes, meta.dataBytes, 100);

        return new WaveformData(peaks, rms, meta, globalMaxPeak);
    }

    private static float readSample(ByteBuffer bb, int off, int pcmFmt) {
        switch (pcmFmt) {
            case PcmConverter.FMT_PCM_FLOAT:
                return bb.getFloat(off);
            case PcmConverter.FMT_PCM_I24_PACKED: {
                int v = (bb.get(off + 2) << 16)
                        | ((bb.get(off + 1) & 0xFF) << 8)
                        | (bb.get(off) & 0xFF);
                return v * (1.0f / 8388608.0f);
            }
            case PcmConverter.FMT_PCM_I32:
                return (float) (bb.getInt(off) * (1.0 / 2147483648.0));
            case PcmConverter.FMT_PCM_I16:
                return bb.getShort(off) * (1.0f / 32768.0f);
            default:
                return 0f;
        }
    }

    private static int readAtMost(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }
}
