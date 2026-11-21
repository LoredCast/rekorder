// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.dsp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Real-time 64-bit internal precision studio DSP processor:
 *   1. 2nd-order Butterworth High-Pass Filter (80/120 Hz rumble & wind cut)
 *   2. 5-Band Parametric/Graphic Equalizer (RBJ Audio EQ Cookbook biquads)
 *   3. Stereo-linked Feed-Forward Soft-Knee Compressor + transparent peak ceiling
 *
 * Operates in-place on interleaved little-endian float32 PCM buffers with zero allocations.
 */
public final class DspChain {

    private static final double SOFT_KNEE_DB = 6.0;

    private final int sampleRate;
    private final int channels;
    private DspConfig config;

    /* Biquad filter: Direct Form II Transposed (double state per channel for 0 quantization drift) */
    private static final class Biquad {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
        final double[] z1 = new double[2];
        final double[] z2 = new double[2];
        boolean active = false;

        void reset() {
            z1[0] = z1[1] = 0.0;
            z2[0] = z2[1] = 0.0;
        }

        void setIdentity() {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0;
            active = false;
        }

        void setHighPass(double fs, double fc) {
            if (fc <= 1.0 || fc >= fs * 0.45) {
                setIdentity();
                return;
            }
            double w0 = 2.0 * Math.PI * fc / fs;
            double cosW0 = Math.cos(w0);
            double alpha = Math.sin(w0) / Math.sqrt(2.0); // Q = 0.7071 Butterworth
            double a0 = 1.0 + alpha;
            b0 = ((1.0 + cosW0) * 0.5) / a0;
            b1 = (-(1.0 + cosW0)) / a0;
            b2 = ((1.0 + cosW0) * 0.5) / a0;
            a1 = (-2.0 * cosW0) / a0;
            a2 = (1.0 - alpha) / a0;
            active = true;
        }

        void setLowShelf(double fs, double fc, double gainDb) {
            if (Math.abs(gainDb) < 0.05 || fc >= fs * 0.45) {
                setIdentity();
                return;
            }
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * fc / fs;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double alpha = sinW0 * 0.5 * Math.sqrt(2.0); // shelf slope S=1
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

            double a0 = (A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha;
            b0 = (A * ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0;
            b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)) / a0;
            b2 = (A * ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0;
            a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW0)) / a0;
            a2 = ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0;
            active = true;
        }

        void setHighShelf(double fs, double fc, double gainDb) {
            if (Math.abs(gainDb) < 0.05 || fc >= fs * 0.45) {
                setIdentity();
                return;
            }
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * fc / fs;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double alpha = sinW0 * 0.5 * Math.sqrt(2.0);
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

            double a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha;
            b0 = (A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha)) / a0;
            b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)) / a0;
            b2 = (A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha)) / a0;
            a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosW0)) / a0;
            a2 = ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha) / a0;
            active = true;
        }

        void setPeaking(double fs, double fc, double q, double gainDb) {
            if (Math.abs(gainDb) < 0.05 || fc >= fs * 0.45) {
                setIdentity();
                return;
            }
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * fc / fs;
            double cosW0 = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha / A;
            b0 = (1.0 + alpha * A) / a0;
            b1 = (-2.0 * cosW0) / a0;
            b2 = (1.0 - alpha * A) / a0;
            a1 = (-2.0 * cosW0) / a0;
            a2 = (1.0 - alpha / A) / a0;
            active = true;
        }

        double process(double x, int ch) {
            double y = b0 * x + z1[ch];
            double nz1 = b1 * x - a1 * y + z2[ch];
            double nz2 = b2 * x - a2 * y;
            if (!Double.isFinite(y) || !Double.isFinite(nz1) || !Double.isFinite(nz2)) {
                z1[ch] = 0.0;
                z2[ch] = 0.0;
                return 0.0;
            }
            z1[ch] = (Math.abs(nz1) < 1e-24) ? 0.0 : nz1;
            z2[ch] = (Math.abs(nz2) < 1e-24) ? 0.0 : nz2;
            return y;
        }
    }

    private final Biquad hpf = new Biquad();
    private final Biquad[] eqBands = new Biquad[5];

    // Compressor & Preamp state
    private double inputGainLinear = 1.0;
    private double compEnvDb = -96.0;
    private double attackCoeff;
    private double releaseCoeff;
    private double makeupLinear = 1.0;
    private volatile float lastGainReductionDb = 0f;

    public DspChain(int sampleRate, int channels, DspConfig config) {
        this.sampleRate = Math.max(8000, sampleRate);
        this.channels = (channels == 2) ? 2 : 1;
        for (int i = 0; i < 5; i++) eqBands[i] = new Biquad();
        reconfigure(config);
    }

    public synchronized void reconfigure(DspConfig cfg) {
        this.config = (cfg != null) ? cfg : DspConfig.rawBypass();

        if (this.config.hasRealTimeFx() && this.config.eqEnabled) {
            if (this.config.hpfHz > 0) {
                hpf.setHighPass(sampleRate, this.config.hpfHz);
            } else {
                hpf.setIdentity();
            }
            eqBands[0].setLowShelf(sampleRate, DspConfig.EQ_FREQS_HZ[0], this.config.eqBandsDb[0]);
            eqBands[1].setPeaking(sampleRate, DspConfig.EQ_FREQS_HZ[1], 1.0, this.config.eqBandsDb[1]);
            eqBands[2].setPeaking(sampleRate, DspConfig.EQ_FREQS_HZ[2], 1.0, this.config.eqBandsDb[2]);
            eqBands[3].setPeaking(sampleRate, DspConfig.EQ_FREQS_HZ[3], 1.0, this.config.eqBandsDb[3]);
            eqBands[4].setHighShelf(sampleRate, DspConfig.EQ_FREQS_HZ[4], this.config.eqBandsDb[4]);
        } else {
            hpf.setIdentity();
            for (Biquad b : eqBands) b.setIdentity();
        }

        inputGainLinear = this.config.hasInputGain()
                ? Math.pow(10.0, this.config.inputGainDb / 20.0)
                : 1.0;

        double attSec = Math.max(0.0005, this.config.compAttackMs * 0.001);
        double relSec = Math.max(0.020, this.config.compReleaseMs * 0.001);
        attackCoeff = Math.exp(-1.0 / (attSec * sampleRate));
        releaseCoeff = Math.exp(-1.0 / (relSec * sampleRate));
        makeupLinear = Math.pow(10.0, this.config.compMakeupDb / 20.0);
    }

    public void resetState() {
        hpf.reset();
        for (Biquad b : eqBands) b.reset();
        compEnvDb = -96.0;
        lastGainReductionDb = 0f;
    }

    public float getLastGainReductionDb() {
        return lastGainReductionDb;
    }

    /**
     * Processes `numFrames` interleaved float32 samples in-place in `floatBuf` [0, numFrames * channels * 4).
     */
    public synchronized void processInPlace(ByteBuffer floatBuf, int numFrames) {
        DspConfig cfg = this.config;
        if (!cfg.hasRealTimeFx() || numFrames <= 0) {
            lastGainReductionDb = 0f;
            return;
        }
        floatBuf.order(ByteOrder.LITTLE_ENDIAN);
        int maxFrames = floatBuf.capacity() / (channels * 4);
        if (numFrames > maxFrames) numFrames = maxFrames;
        int neededBytes = numFrames * channels * 4;
        if (floatBuf.limit() < neededBytes) {
            floatBuf.limit(neededBytes);
        }

        boolean runGain = cfg.hasInputGain();
        double inGain = inputGainLinear;
        boolean runEq = cfg.eqEnabled;
        boolean runComp = cfg.compEnabled;
        double thresh = cfg.compThresholdDb;
        double ratio = cfg.compRatio;
        double halfKnee = SOFT_KNEE_DB * 0.5;
        double invSlope = (1.0 / ratio) - 1.0;

        float worstGrDb = 0f;

        for (int f = 0; f < numFrames; f++) {
            int baseOff = f * channels * 4;
            double s0 = floatBuf.getFloat(baseOff);
            if (!Double.isFinite(s0)) s0 = 0.0;
            double s1 = (channels == 2) ? floatBuf.getFloat(baseOff + 4) : 0.0;
            if (!Double.isFinite(s1)) s1 = 0.0;

            // 0. Digital Input Preamp Trim (pre-EQ/Compressor)
            if (runGain) {
                s0 *= inGain;
                if (channels == 2) s1 *= inGain;
            }

            // 1. High-Pass + 5-Band Studio EQ
            if (runEq) {
                if (hpf.active) {
                    s0 = hpf.process(s0, 0);
                    if (channels == 2) s1 = hpf.process(s1, 1);
                }
                for (int b = 0; b < 5; b++) {
                    Biquad filt = eqBands[b];
                    if (filt.active) {
                        s0 = filt.process(s0, 0);
                        if (channels == 2) s1 = filt.process(s1, 1);
                    }
                }
            }

            // 2. Stereo-linked Feed-Forward Soft-Knee Compressor + transparent peak limiter
            if (runComp) {
                double det = Math.abs(s0);
                if (channels == 2) {
                    double a1 = Math.abs(s1);
                    if (a1 > det) det = a1;
                }
                double inDb = (det > 1e-5) ? (20.0 * Math.log10(det)) : -100.0;

                // Envelope follower in dB domain
                double coeff = (inDb > compEnvDb) ? attackCoeff : releaseCoeff;
                compEnvDb = coeff * compEnvDb + (1.0 - coeff) * inDb;

                // Soft-knee gain computer
                double overDb = compEnvDb - thresh;
                double grDb;
                if (overDb <= -halfKnee) {
                    grDb = 0.0;
                } else if (overDb >= halfKnee) {
                    grDb = overDb * invSlope;
                } else {
                    double x = overDb + halfKnee;
                    grDb = invSlope * (x * x) / (2.0 * SOFT_KNEE_DB);
                }
                if (grDb < worstGrDb) worstGrDb = (float) grDb;

                double gain = Math.pow(10.0, grDb / 20.0) * makeupLinear;
                s0 = softLimit(s0 * gain);
                if (channels == 2) {
                    s1 = softLimit(s1 * gain);
                }
            } else if (runGain && cfg.inputGainDb > 0f) {
                // Transparent ceiling when boosting quiet raw mics without compressor
                s0 = softLimit(s0);
                if (channels == 2) s1 = softLimit(s1);
            }

            floatBuf.putFloat(baseOff, (float) s0);
            if (channels == 2) {
                floatBuf.putFloat(baseOff + 4, (float) s1);
            }
        }

        lastGainReductionDb = runComp ? worstGrDb : 0f;
    }

    /** Transparent soft saturation only above -0.5 dBFS (0.944), hard-bounded below 0.995. */
    private static double softLimit(double x) {
        double ax = Math.abs(x);
        if (ax <= 0.944) return x;
        double excess = (ax - 0.944) / 0.051;
        double limited = 0.944 + 0.051 * Math.tanh(excess);
        return x >= 0.0 ? limited : -limited;
    }

    /** Computes linear gain multiplier for exact True Peak Normalization (capped at +36 dB boost). */
    public static float computeNormalizeGain(float maxPeakLinear, float targetDb) {
        if (maxPeakLinear <= 1e-6f) return 1.0f;
        float targetLin = (float) Math.pow(10.0, targetDb / 20.0);
        float gain = targetLin / maxPeakLinear;
        float maxBoost = 63.0957f; // +36 dB safety cap so pure silence doesn't amplify noise floor
        if (gain > maxBoost) gain = maxBoost;
        return gain;
    }
}
