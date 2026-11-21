// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bit-exact PCM sample conversion and studio Peak/RMS metering.
 * Zero per-block heap allocations; little-endian native layout throughout.
 */
public final class PcmConverter {

    /* Values match aaudio_format_t */
    public static final int FMT_PCM_I16 = 1;
    public static final int FMT_PCM_FLOAT = 2;
    public static final int FMT_PCM_I24_PACKED = 3;
    public static final int FMT_PCM_I32 = 4;

    public static final class MeterResult {
        public float peak;
        public float rms;
        public int clipCount;
        public float peakL;
        public float rmsL;
        public float peakR;
        public float rmsR;
        public int channelCount = 1;
    }

    private PcmConverter() {}

    public static int bytesPerSample(int fmt) {
        switch (fmt) {
            case FMT_PCM_I16:        return 2;
            case FMT_PCM_I24_PACKED: return 3;
            case FMT_PCM_FLOAT:      return 4;
            case FMT_PCM_I32:        return 4;
            default:                 return 0;
        }
    }

    public static String formatTag(int fmt) {
        switch (fmt) {
            case FMT_PCM_I16:        return "I16";
            case FMT_PCM_FLOAT:      return "F32";
            case FMT_PCM_I24_PACKED: return "I24";
            case FMT_PCM_I32:        return "I32";
            default:                 return "?";
        }
    }

    public static boolean isDirectPassthrough(int srcFmt, AudioConfig.OutputFormat dstFmt) {
        if (srcFmt == FMT_PCM_FLOAT && dstFmt.isFloat) return true;
        if (srcFmt == FMT_PCM_I24_PACKED && !dstFmt.isFloat && dstFmt.bitsPerSample == 24) return true;
        if (srcFmt == FMT_PCM_I16 && !dstFmt.isFloat && dstFmt.bitsPerSample == 16) return true;
        return false;
    }

    /**
     * Scans little-endian hardware PCM bytes [0, byteLen) and computes linear Peak, RMS, and clip count.
     */
    public static void computeMeter(ByteBuffer src, int byteLen, int srcFmt, MeterResult out) {
        computeMeter(src, byteLen, srcFmt, 1, out);
    }

    /**
     * Scans interleaved hardware PCM bytes [0, byteLen) and computes combined + per-channel L/R Peak & RMS.
     */
    public static void computeMeter(ByteBuffer src, int byteLen, int srcFmt, int channels, MeterResult out) {
        src.order(ByteOrder.LITTLE_ENDIAN);
        int safeLen = Math.min(byteLen, src.capacity());
        if (src.limit() < safeLen) {
            src.limit(safeLen);
        }
        byteLen = safeLen;
        int ch = (channels == 2) ? 2 : 1;
        float peakL = 0f, peakR = 0f;
        double sumSqL = 0.0, sumSqR = 0.0;
        int clips = 0;
        int samplesL = 0, samplesR = 0;

        int bps = bytesPerSample(srcFmt);
        if (bps > 0) {
            int totalSamples = byteLen / bps;
            for (int i = 0; i < totalSamples; i++) {
                float s = readSampleAsFloat(src, i, srcFmt);
                if (!Float.isFinite(s)) s = 0f;
                float a = Math.abs(s);
                if (a >= 0.999f) clips++;
                if (ch == 1 || (i & 1) == 0) {
                    if (a > peakL) peakL = a;
                    sumSqL += (double) s * (double) s;
                    samplesL++;
                } else {
                    if (a > peakR) peakR = a;
                    sumSqR += (double) s * (double) s;
                    samplesR++;
                }
            }
        }

        out.channelCount = ch;
        out.peakL = peakL;
        out.rmsL = samplesL > 0 ? (float) Math.sqrt(sumSqL / samplesL) : 0f;
        if (ch == 2) {
            out.peakR = peakR;
            out.rmsR = samplesR > 0 ? (float) Math.sqrt(sumSqR / samplesR) : 0f;
            out.peak = Math.max(peakL, peakR);
            int total = samplesL + samplesR;
            out.rms = total > 0 ? (float) Math.sqrt((sumSqL + sumSqR) / total) : 0f;
        } else {
            out.peakR = peakL;
            out.rmsR = out.rmsL;
            out.peak = peakL;
            out.rms = out.rmsL;
        }
        out.clipCount = clips;
    }

    /**
     * Converts hardware PCM in `src` [0, srcBytes) into `dst` (starting at 0, cleared/flipped by caller).
     * Returns exact number of bytes written into `dst`.
     */
    public static int convertToOutput(ByteBuffer src, int srcBytes, int srcFmt,
                                      AudioConfig.OutputFormat dstFmt, ByteBuffer dst) {
        src.order(ByteOrder.LITTLE_ENDIAN);
        dst.order(ByteOrder.LITTLE_ENDIAN);
        if (srcBytes <= 0) {
            dst.clear();
            return 0;
        }
        int safeSrcBytes = Math.min(srcBytes, src.capacity());
        if (src.limit() < safeSrcBytes) {
            src.limit(safeSrcBytes);
        }
        dst.clear(); // Always restore dst position=0, limit=capacity across reused buffers

        if (isDirectPassthrough(srcFmt, dstFmt)) {
            int copyBytes = Math.min(safeSrcBytes, dst.capacity());
            int savedPos = src.position();
            int savedLim = src.limit();
            src.position(0);
            src.limit(copyBytes);
            dst.put(src);
            src.limit(savedLim);
            src.position(savedPos);
            return copyBytes;
        }

        int srcBps = bytesPerSample(srcFmt);
        if (srcBps <= 0) return 0;
        int maxDstSamples = dst.capacity() / dstFmt.bytesPerSample;
        int sampleCount = Math.min(safeSrcBytes / srcBps, maxDstSamples);
        int outPos = 0;

        if (dstFmt.isFloat) {
            for (int i = 0; i < sampleCount; i++) {
                float s = readSampleAsFloat(src, i, srcFmt);
                dst.putFloat(outPos, s);
                outPos += 4;
            }
        } else if (dstFmt.bitsPerSample == 24) {
            for (int i = 0; i < sampleCount; i++) {
                int v24 = readSampleAsInt24(src, i, srcFmt);
                dst.put(outPos, (byte) (v24 & 0xFF));
                dst.put(outPos + 1, (byte) ((v24 >>> 8) & 0xFF));
                dst.put(outPos + 2, (byte) ((v24 >>> 16) & 0xFF));
                outPos += 3;
            }
        } else { // 16-bit PCM
            for (int i = 0; i < sampleCount; i++) {
                short v16 = readSampleAsInt16(src, i, srcFmt);
                dst.putShort(outPos, v16);
                outPos += 2;
            }
        }
        return outPos;
    }

    private static float readSampleAsFloat(ByteBuffer src, int idx, int fmt) {
        switch (fmt) {
            case FMT_PCM_FLOAT:
                return src.getFloat(idx << 2);
            case FMT_PCM_I24_PACKED: {
                int off = idx * 3;
                int v = (src.get(off + 2) << 16)
                        | ((src.get(off + 1) & 0xFF) << 8)
                        | (src.get(off) & 0xFF);
                return v * (1.0f / 8388608.0f);
            }
            case FMT_PCM_I32:
                return (float) (src.getInt(idx << 2) * (1.0 / 2147483648.0));
            case FMT_PCM_I16:
                return src.getShort(idx << 1) * (1.0f / 32768.0f);
            default:
                return 0f;
        }
    }

    private static int readSampleAsInt24(ByteBuffer src, int idx, int fmt) {
        switch (fmt) {
            case FMT_PCM_I24_PACKED: {
                int off = idx * 3;
                return (src.get(off + 2) << 16)
                        | ((src.get(off + 1) & 0xFF) << 8)
                        | (src.get(off) & 0xFF);
            }
            case FMT_PCM_FLOAT: {
                float s = src.getFloat(idx << 2);
                if (s > 1.0f) s = 1.0f;
                else if (s < -1.0f) s = -1.0f;
                int v = Math.round(s * 8388607.0f);
                if (v > 8388607) v = 8388607;
                else if (v < -8388608) v = -8388608;
                return v;
            }
            case FMT_PCM_I32:
                return src.getInt(idx << 2) >> 8;
            case FMT_PCM_I16:
                return ((int) src.getShort(idx << 1)) << 8;
            default:
                return 0;
        }
    }

    private static short readSampleAsInt16(ByteBuffer src, int idx, int fmt) {
        switch (fmt) {
            case FMT_PCM_I16:
                return src.getShort(idx << 1);
            case FMT_PCM_FLOAT: {
                float s = src.getFloat(idx << 2);
                if (s > 1.0f) s = 1.0f;
                else if (s < -1.0f) s = -1.0f;
                int v = Math.round(s * 32767.0f);
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                return (short) v;
            }
            case FMT_PCM_I24_PACKED: {
                int off = idx * 3;
                int v = (src.get(off + 2) << 16)
                        | ((src.get(off + 1) & 0xFF) << 8)
                        | (src.get(off) & 0xFF);
                return (short) (v >> 8);
            }
            case FMT_PCM_I32:
                return (short) (src.getInt(idx << 2) >> 16);
            default:
                return 0;
        }
    }

    public static float linearToDbfs(float linear) {
        if (linear <= 1e-5f) return -96.0f;
        float db = (float) (20.0 * Math.log10(linear));
        return Math.max(-96.0f, Math.min(6.0f, db));
    }
}
