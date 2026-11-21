// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * JNI driver interface to our custom C AAudio capture engine (librekorder_aaudio.so).
 * Requests direct hardware MMAP Exclusive mode with AAUDIO_INPUT_PRESET_UNPROCESSED.
 */
public final class NativeAAudioEngine implements AudioCaptureEngine {

    private static final int AAUDIO_SHARING_EXCLUSIVE = 0;
    private static final int AAUDIO_SHARING_SHARED = 1;
    private static final int AAUDIO_PERF_NONE = 10;
    private static final int AAUDIO_PERF_LOW_LATENCY = 12;

    private static final boolean NATIVE_LOADED;

    static {
        boolean loaded = false;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                System.loadLibrary("rekorder_aaudio");
                loaded = true;
            } catch (UnsatisfiedLinkError ignored) {
                loaded = false;
            }
        }
        NATIVE_LOADED = loaded;
    }

    public static boolean isNativeAvailable() {
        return NATIVE_LOADED;
    }

    private final Object lifecycleLock = new Object();
    private long handle = 0L;
    private final int[] statsScratch = new int[4];

    @Override
    public StreamInfo open(AudioConfig config) throws IOException {
        if (!NATIVE_LOADED) {
            throw new IOException("Native AAudio driver unavailable on this device");
        }

        int[] formatCandidates = buildFormatLadder(config.outputFormat);
        int[][] modeCandidates;
        if (config.engineMode == AudioConfig.EngineMode.AAUDIO_EXCLUSIVE) {
            modeCandidates = new int[][]{
                    {AAUDIO_SHARING_EXCLUSIVE, AAUDIO_PERF_LOW_LATENCY},
                    {AAUDIO_SHARING_SHARED, AAUDIO_PERF_LOW_LATENCY},
                    {AAUDIO_SHARING_SHARED, AAUDIO_PERF_NONE}
            };
        } else {
            modeCandidates = new int[][]{
                    {AAUDIO_SHARING_SHARED, AAUDIO_PERF_LOW_LATENCY},
                    {AAUDIO_SHARING_SHARED, AAUDIO_PERF_NONE}
            };
        }

        int[] outInfo = new int[10];
        int lastError = 0;

        for (int[] mode : modeCandidates) {
            for (int fmt : formatCandidates) {
                long h = nativeOpen(
                        config.deviceId,
                        config.sampleRate,
                        config.channelCount,
                        fmt,
                        config.inputPreset.nativeValue,
                        mode[0],
                        mode[1],
                        outInfo
                );
                if (h != 0L) {
                    this.handle = h;
                    String sharingTag = (outInfo[3] == AAUDIO_SHARING_EXCLUSIVE) ? "EXCL" : "SHARED";
                    if (outInfo[4] == AAUDIO_PERF_LOW_LATENCY && outInfo[3] == AAUDIO_SHARING_EXCLUSIVE) {
                        sharingTag = "EXCL·LL";
                    }
                    String presetTag = AudioConfig.InputPreset.fromNativeValue(outInfo[5]).shortLabel;
                    return new StreamInfo(
                            "AAUDIO",
                            sharingTag,
                            presetTag,
                            outInfo[0],
                            outInfo[1],
                            outInfo[2],
                            outInfo[6],
                            outInfo[7],
                            outInfo[8]
                    );
                }
                lastError = outInfo[9];
            }
        }

        // Last resort: let hardware pick native rate, mono fallback, or default preset
        int[][] fallbacks = {
                {config.deviceId, 0, config.channelCount, config.inputPreset.nativeValue},
                {config.deviceId, 0, 1, config.inputPreset.nativeValue},
                {0, 0, config.channelCount, 0},
                {0, 0, 1, 0}
        };
        for (int[] fb : fallbacks) {
            long h = nativeOpen(
                    fb[0],
                    fb[1],
                    fb[2],
                    0,
                    fb[3],
                    AAUDIO_SHARING_SHARED,
                    AAUDIO_PERF_NONE,
                    outInfo
            );
            if (h != 0L) {
                this.handle = h;
                String sharingTag = (outInfo[3] == AAUDIO_SHARING_EXCLUSIVE) ? "EXCL" : "SHARED";
                String presetTag = AudioConfig.InputPreset.fromNativeValue(outInfo[5]).shortLabel;
                return new StreamInfo(
                        "AAUDIO",
                        sharingTag,
                        presetTag,
                        outInfo[0],
                        outInfo[1],
                        outInfo[2],
                        outInfo[6],
                        outInfo[7],
                        outInfo[8]
                );
            }
            lastError = outInfo[9];
        }

        throw new IOException("AAudio open failed: " + safeErrorText(lastError) + " (" + lastError + ")");
    }

    private static int[] buildFormatLadder(AudioConfig.OutputFormat outFmt) {
        boolean api31 = Build.VERSION.SDK_INT >= 31;
        if (outFmt.isFloat) {
            return api31
                    ? new int[]{PcmConverter.FMT_PCM_FLOAT, PcmConverter.FMT_PCM_I32, PcmConverter.FMT_PCM_I24_PACKED, 0}
                    : new int[]{PcmConverter.FMT_PCM_FLOAT, 0};
        }
        if (outFmt.bitsPerSample == 24) {
            return api31
                    ? new int[]{PcmConverter.FMT_PCM_I24_PACKED, PcmConverter.FMT_PCM_FLOAT, PcmConverter.FMT_PCM_I32, 0}
                    : new int[]{PcmConverter.FMT_PCM_FLOAT, 0};
        }
        // 16-bit target: prefer I16 direct, fallback Float32 / Unspecified
        return new int[]{PcmConverter.FMT_PCM_I16, PcmConverter.FMT_PCM_FLOAT, 0};
    }

    @Override
    public void start() throws IOException {
        if (handle == 0L) throw new IOException("AAudio stream not open");
        int res = nativeStart(handle);
        if (res != 0) {
            throw new IOException("AAudio start failed: " + safeErrorText(res) + " (" + res + ")");
        }
    }

    @Override
    public void setPaused(boolean paused) {
        synchronized (lifecycleLock) {
            if (handle != 0L) {
                nativeSetPaused(handle, paused);
            }
        }
    }

    @Override
    public int read(ByteBuffer directDst, int maxBytes) {
        if (handle == 0L) return 0;
        return nativeDrain(handle, directDst, maxBytes);
    }

    @Override
    public void pollTelemetry(Telemetry out) {
        if (handle == 0L) {
            out.xrunCount = 0;
            out.ringOverruns = 0;
            out.errorCode = 0;
            out.errorText = null;
            return;
        }
        nativeGetTelemetry(handle, statsScratch);
        out.xrunCount = statsScratch[0];
        out.ringOverruns = statsScratch[1];
        out.errorCode = statsScratch[2];
        out.errorText = (out.errorCode != 0) ? safeErrorText(out.errorCode) : null;
    }

    @Override
    public void stopAndClose() {
        synchronized (lifecycleLock) {
            long h = handle;
            handle = 0L;
            if (h != 0L) {
                nativeStopAndClose(h);
            }
        }
    }

    private static String safeErrorText(int code) {
        if (!NATIVE_LOADED) return "UNAVAILABLE";
        try {
            return nativeErrorText(code);
        } catch (Throwable t) {
            return "ERR_" + code;
        }
    }

    private static native long nativeOpen(
            int deviceId,
            int sampleRate,
            int channelCount,
            int format,
            int inputPreset,
            int sharingMode,
            int performanceMode,
            int[] outInfo
    );

    private static native int nativeStart(long handle);

    private static native void nativeSetPaused(long handle, boolean paused);

    private static native int nativeDrain(long handle, ByteBuffer directDst, int maxBytes);

    private static native void nativeGetTelemetry(long handle, int[] outStats);

    private static native void nativeStopAndClose(long handle);

    private static native String nativeErrorText(int errorCode);
}
