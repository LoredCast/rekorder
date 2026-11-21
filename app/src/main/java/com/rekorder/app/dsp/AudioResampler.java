// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.dsp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming cubic-Hermite resampler with 2nd-order Butterworth anti-aliasing filter
 * when downsampling (e.g. 96/192 kHz capture -> 48 kHz for OGG Opus or MP3).
 * Zero per-block allocations.
 */
public final class AudioResampler {

    private final int inRate;
    private final int outRate;
    private final int channels;
    private final double step;

    // 4-point cubic history per channel [ch][0..3]
    private final float[][] hist = new float[2][4];
    private double fracPos = 0.0;

    // Optional anti-aliasing Butterworth lowpass (active when outRate < inRate)
    private final boolean useLpf;
    private double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
    private final double[] z1 = new double[2];
    private final double[] z2 = new double[2];

    public AudioResampler(int inRate, int outRate, int channels) {
        this.inRate = inRate;
        this.outRate = outRate;
        this.channels = (channels == 2) ? 2 : 1;
        this.step = (double) inRate / (double) outRate;
        this.useLpf = outRate < inRate;

        if (useLpf) {
            double fc = outRate * 0.44; // Anti-aliasing cutoff below output Nyquist
            double w0 = 2.0 * Math.PI * fc / inRate;
            double cosW0 = Math.cos(w0);
            double alpha = Math.sin(w0) / Math.sqrt(2.0);
            double a0 = 1.0 + alpha;
            b0 = ((1.0 - cosW0) * 0.5) / a0;
            b1 = (1.0 - cosW0) / a0;
            b2 = ((1.0 - cosW0) * 0.5) / a0;
            a1 = (-2.0 * cosW0) / a0;
            a2 = (1.0 - alpha) / a0;
        }
    }

    public boolean isPassthrough() {
        return inRate == outRate;
    }

    /**
     * Resamples `inFrames` interleaved float32 frames from `inBuf` into `outBuf`.
     * Returns exact number of output frames written to `outBuf`.
     */
    public int process(ByteBuffer inBuf, int inFrames, ByteBuffer outBuf) {
        inBuf.order(ByteOrder.LITTLE_ENDIAN);
        outBuf.order(ByteOrder.LITTLE_ENDIAN);
        outBuf.clear();
        if (inFrames <= 0) return 0;

        int maxInFrames = inBuf.capacity() / (channels * 4);
        if (inFrames > maxInFrames) inFrames = maxInFrames;
        int inBytes = inFrames * channels * 4;
        if (inBuf.limit() < inBytes) {
            inBuf.limit(inBytes);
        }

        if (inRate == outRate) {
            int maxOutBytes = Math.min(inBytes, outBuf.capacity());
            maxOutBytes -= (maxOutBytes % (channels * 4));
            for (int i = 0; i < maxOutBytes; i += 4) {
                outBuf.putFloat(i, inBuf.getFloat(i));
            }
            return maxOutBytes / (channels * 4);
        }

        int maxOutFrames = outBuf.capacity() / (channels * 4);
        int outFrames = 0;

        for (int i = 0; i < inFrames; i++) {
            int inOff = i * channels * 4;
            for (int c = 0; c < channels; c++) {
                double s = inBuf.getFloat(inOff + c * 4);
                if (!Double.isFinite(s)) s = 0.0;
                if (useLpf) {
                    double y = b0 * s + z1[c];
                    double nz1 = b1 * s - a1 * y + z2[c];
                    double nz2 = b2 * s - a2 * y;
                    if (!Double.isFinite(y) || !Double.isFinite(nz1) || !Double.isFinite(nz2)) {
                        z1[c] = 0.0;
                        z2[c] = 0.0;
                        y = 0.0;
                    } else {
                        z1[c] = (Math.abs(nz1) < 1e-24) ? 0.0 : nz1;
                        z2[c] = (Math.abs(nz2) < 1e-24) ? 0.0 : nz2;
                    }
                    s = y;
                }
                hist[c][0] = hist[c][1];
                hist[c][1] = hist[c][2];
                hist[c][2] = hist[c][3];
                hist[c][3] = (float) s;
            }

            while (fracPos < 1.0 && outFrames < maxOutFrames) {
                float t = (float) fracPos;
                int outOff = outFrames * channels * 4;
                for (int c = 0; c < channels; c++) {
                    float y = cubicHermite(hist[c][0], hist[c][1], hist[c][2], hist[c][3], t);
                    outBuf.putFloat(outOff + c * 4, y);
                }
                outFrames++;
                fracPos += step;
            }
            fracPos -= 1.0;
        }
        return outFrames;
    }

    private static float cubicHermite(float y0, float y1, float y2, float y3, float t) {
        float c0 = y1;
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * t + c2) * t + c1) * t + c0;
    }
}
