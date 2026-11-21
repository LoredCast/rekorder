// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.dsp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Real-time 64-bit internal precision studio DSP processor:
 *   0. Digital Input Preamp Trim (-12..+24 dB)
 *   1. Surgical 50/60 Hz De-Hum Mains Notch (+ 2nd harmonic)
 *   2. Smooth Stereo-Linked Soft Noise Gate (-36 dB floor, 6 dB knee)
 *   3. 2nd-order Butterworth High-Pass Filter (80/120 Hz rumble & wind cut)
 *   4. 5-Band Parametric/Graphic Equalizer (RBJ Audio EQ Cookbook biquads)
 *   5. Stereo-linked Feed-Forward Soft-Knee Compressor + transparent peak ceiling
 *
 * Also provides an ITU-R BS.1770 K-weighted integrated LUFS loudness meter.
 * Operates in-place on interleaved little-endian float32 PCM buffers with zero allocations.
 */
public final class DspChain {

    private static final double SOFT_KNEE_DB = 6.0;
    private static final double GATE_FLOOR_LIN = 0.0158489; // -36 dB gentle floor (no harsh silence chopping)

    private final int sampleRate;
    private final int channels;
    private DspConfig config;

    /* Biquad filter: Direct Form II Transposed (double state per channel for 0 quantization drift) */
    static final class Biquad {
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
            setHighPassQ(fs, fc, 1.0 / Math.sqrt(2.0));
        }

        void setHighPassQ(double fs, double fc, double q) {
            if (fc <= 1.0 || fc >= fs * 0.45) {
                setIdentity();
                return;
            }
            double w0 = 2.0 * Math.PI * fc / fs;
            double cosW0 = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            b0 = ((1.0 + cosW0) * 0.5) / a0;
            b1 = (-(1.0 + cosW0)) / a0;
            b2 = ((1.0 + cosW0) * 0.5) / a0;
            a1 = (-2.0 * cosW0) / a0;
            a2 = (1.0 - alpha) / a0;
            active = true;
        }

        void setNotch(double fs, double fc, double q) {
            if (fc <= 1.0 || fc >= fs * 0.45 || q <= 0.1) {
                setIdentity();
                return;
            }
            double w0 = 2.0 * Math.PI * fc / fs;
            double cosW0 = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            b0 = 1.0 / a0;
            b1 = (-2.0 * cosW0) / a0;
            b2 = 1.0 / a0;
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

    private final Biquad notch1 = new Biquad();
    private final Biquad notch2 = new Biquad();
    private final Biquad hpf = new Biquad();
    private final Biquad[] eqBands = new Biquad[5];

    // Preamp, Gate & Compressor state
    private double inputGainLinear = 1.0;
    private double gateEnvDb = -96.0;
    private double gateGain = 1.0;
    private double gateAttCoeff;
    private double gateRelCoeff;
    private double gateSmoothCoeff;

    private double compEnvDb = -96.0;
    private double attackCoeff;
    private double releaseCoeff;
    private double makeupLinear = 1.0;
    private volatile float lastGainReductionDb = 0f;

    public DspChain(int sampleRate, int channels, DspConfig config) {
        this.sampleRate = Math.max(8000, sampleRate);
        this.channels = (channels == 2) ? 2 : 1;
        for (int i = 0; i < 5; i++) eqBands[i] = new Biquad();
        gateAttCoeff = Math.exp(-1.0 / (0.005 * this.sampleRate));   // 5 ms open
        gateRelCoeff = Math.exp(-1.0 / (0.160 * this.sampleRate));   // 160 ms smooth close
        gateSmoothCoeff = Math.exp(-1.0 / (0.008 * this.sampleRate)); // 8 ms gain smoother
        reconfigure(config);
    }

    public synchronized void reconfigure(DspConfig cfg) {
        this.config = (cfg != null) ? cfg : DspConfig.rawBypass();

        if (this.config.hasRealTimeFx() && this.config.hasDeHum()) {
            notch1.setNotch(sampleRate, this.config.deHumHz, 25.0);
            notch2.setNotch(sampleRate, this.config.deHumHz * 2.0, 30.0);
        } else {
            notch1.setIdentity();
            notch2.setIdentity();
        }

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
        notch1.reset();
        notch2.reset();
        hpf.reset();
        for (Biquad b : eqBands) b.reset();
        gateEnvDb = -96.0;
        gateGain = 1.0;
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
        boolean runDeHum = cfg.hasDeHum();
        boolean runGate = cfg.gateEnabled;
        double gateThresh = cfg.gateThresholdDb;
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

            // 0. Digital Input Preamp Trim
            if (runGain) {
                s0 *= inGain;
                if (channels == 2) s1 *= inGain;
            }

            // 1. 50/60 Hz De-Hum Mains Notch (+ 2nd harmonic)
            if (runDeHum) {
                if (notch1.active) {
                    s0 = notch1.process(s0, 0);
                    if (channels == 2) s1 = notch1.process(s1, 1);
                }
                if (notch2.active) {
                    s0 = notch2.process(s0, 0);
                    if (channels == 2) s1 = notch2.process(s1, 1);
                }
            }

            // 2. Smooth Stereo-Linked Soft Noise Gate (soft 6 dB knee -> -36 dB floor)
            if (runGate) {
                double det = Math.abs(s0);
                if (channels == 2) {
                    double a1 = Math.abs(s1);
                    if (a1 > det) det = a1;
                }
                double inDb = (det > 1e-5) ? (20.0 * Math.log10(det)) : -100.0;
                double c = (inDb > gateEnvDb) ? gateAttCoeff : gateRelCoeff;
                gateEnvDb = c * gateEnvDb + (1.0 - c) * inDb;

                double targetGain;
                if (gateEnvDb >= gateThresh) {
                    targetGain = 1.0;
                } else if (gateEnvDb <= gateThresh - 12.0) {
                    targetGain = GATE_FLOOR_LIN;
                } else {
                    // Smooth downward 3:1 expansion across the 12 dB transition band
                    double below = gateThresh - gateEnvDb; // [0..12]
                    targetGain = Math.pow(10.0, (-3.0 * below) / 20.0);
                    if (targetGain < GATE_FLOOR_LIN) targetGain = GATE_FLOOR_LIN;
                }
                gateGain = gateSmoothCoeff * gateGain + (1.0 - gateSmoothCoeff) * targetGain;
                s0 *= gateGain;
                if (channels == 2) s1 *= gateGain;
            }

            // 3. High-Pass + 5-Band Studio EQ
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

            // 4. Stereo-linked Feed-Forward Soft-Knee Compressor + transparent peak limiter
            if (runComp) {
                double det = Math.abs(s0);
                if (channels == 2) {
                    double a1 = Math.abs(s1);
                    if (a1 > det) det = a1;
                }
                double inDb = (det > 1e-5) ? (20.0 * Math.log10(det)) : -100.0;

                double coeff = (inDb > compEnvDb) ? attackCoeff : releaseCoeff;
                compEnvDb = coeff * compEnvDb + (1.0 - coeff) * inDb;

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

    /**
     * Computes linear gain multiplier for BS.1770 Integrated LUFS Normalization,
     * strictly capped so true peaks never exceed -1.0 dBFS (0.89125).
     */
    public static float computeLufsGain(float measuredLufs, float targetLufs, float maxPeakLinear) {
        if (!Float.isFinite(measuredLufs) || measuredLufs <= -70f || maxPeakLinear <= 1e-6f) {
            return 1.0f;
        }
        float deltaDb = targetLufs - measuredLufs;
        if (deltaDb > 36f) deltaDb = 36f;
        if (deltaDb < -36f) deltaDb = -36f;
        float gain = (float) Math.pow(10.0, deltaDb / 20.0);
        // True-peak safety cap at -1.0 dBFS so LUFS lift never introduces clipping
        float peakCap = 0.89125f / maxPeakLinear;
        if (gain > peakCap) gain = peakCap;
        return gain;
    }

    /**
     * Lightweight ITU-R BS.1770-4 K-weighted integrated loudness meter (LUFS).
     * Uses streaming 100ms sub-blocks -> 400ms gated blocks (-70 LUFS absolute gate
     * + -10 LU relative gate) with bounded memory even on multi-hour takes.
     */
    public static final class LufsMeter {
        private final int channels;
        private final int stepFrames; // 100ms hop
        private final Biquad preShelf = new Biquad();
        private final Biquad rlbHpf = new Biquad();

        private double stepEnergy = 0.0;
        private int stepCount = 0;
        private final double[] ring4 = new double[4]; // 4 x 100ms = 400ms BS.1770 window
        private int ringFilled = 0;
        private int ringIdx = 0;

        // Histogram of 400ms block loudness in 0.5 LU bins from -70.0 to +10.0 LUFS (161 bins -> O(1) RAM!)
        private static final int BINS = 161;
        private final double[] binEnergySum = new double[BINS];
        private final long[] binCount = new long[BINS];
        private double fallbackEnergySum = 0.0;
        private long fallbackFrames = 0L;

        public LufsMeter(int sampleRate, int channels) {
            int fs = Math.max(8000, sampleRate);
            this.channels = (channels == 2) ? 2 : 1;
            this.stepFrames = Math.max(1, fs / 10); // 100ms hop
            // ITU-R BS.1770 Stage 1 (+4.0 dB high-shelf @ 1681.97 Hz) + Stage 2 (RLB HPF @ 38.14 Hz)
            preShelf.setHighShelf(fs, 1681.97, 4.0);
            rlbHpf.setHighPassQ(fs, 38.14, 0.5003);
        }

        public void feedInterleavedFloat(ByteBuffer floatBuf, int numFrames) {
            if (floatBuf == null || numFrames <= 0) return;
            floatBuf.order(ByteOrder.LITTLE_ENDIAN);
            int maxFrames = floatBuf.capacity() / (channels * 4);
            if (numFrames > maxFrames) numFrames = maxFrames;

            for (int f = 0; f < numFrames; f++) {
                int off = f * channels * 4;
                double s0 = floatBuf.getFloat(off);
                if (!Double.isFinite(s0)) s0 = 0.0;
                double k0 = rlbHpf.process(preShelf.process(s0, 0), 0);
                double frameE = k0 * k0;
                if (channels == 2) {
                    double s1 = floatBuf.getFloat(off + 4);
                    if (!Double.isFinite(s1)) s1 = 0.0;
                    double k1 = rlbHpf.process(preShelf.process(s1, 1), 1);
                    frameE += k1 * k1;
                }
                stepEnergy += frameE;
                fallbackEnergySum += frameE;
                fallbackFrames++;
                stepCount++;
                if (stepCount >= stepFrames) {
                    double meanE = stepEnergy / stepCount;
                    stepEnergy = 0.0;
                    stepCount = 0;
                    ring4[ringIdx] = meanE;
                    ringIdx = (ringIdx + 1) & 3;
                    if (ringFilled < 4) ringFilled++;
                    if (ringFilled == 4) {
                        double blockE = 0.25 * (ring4[0] + ring4[1] + ring4[2] + ring4[3]);
                        if (blockE > 1e-10) {
                            double lufs = -0.691 + 10.0 * Math.log10(blockE);
                            if (lufs > -70.0) {
                                int bin = (int) Math.round((lufs + 70.0) * 2.0);
                                if (bin < 0) bin = 0;
                                if (bin >= BINS) bin = BINS - 1;
                                binEnergySum[bin] += blockE;
                                binCount[bin]++;
                            }
                        }
                    }
                }
            }
        }

        public float getIntegratedLufs() {
            double absSum = 0.0;
            long absCnt = 0L;
            for (int i = 0; i < BINS; i++) {
                absSum += binEnergySum[i];
                absCnt += binCount[i];
            }
            if (absCnt == 0L) {
                if (fallbackFrames <= 0L) return -70.0f;
                double mean = fallbackEnergySum / fallbackFrames;
                if (mean <= 1e-10) return -70.0f;
                return (float) (-0.691 + 10.0 * Math.log10(mean));
            }
            double ungatedLufs = -0.691 + 10.0 * Math.log10(absSum / absCnt);
            double relThresh = ungatedLufs - 10.0;
            int minBin = (int) Math.ceil((relThresh + 70.0) * 2.0);
            if (minBin < 0) minBin = 0;

            double relSum = 0.0;
            long relCnt = 0L;
            for (int i = minBin; i < BINS; i++) {
                relSum += binEnergySum[i];
                relCnt += binCount[i];
            }
            if (relCnt == 0L) {
                return (float) ungatedLufs;
            }
            return (float) (-0.691 + 10.0 * Math.log10(relSum / relCnt));
        }
    }
}
