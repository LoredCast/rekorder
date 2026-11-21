// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Direct Android SDK AudioRecord capture engine using MediaRecorder.AudioSource.UNPROCESSED
 * (or user-selected source) with zero audio effects attached and explicit hardware routing.
 */
public final class AudioRecordEngine implements AudioCaptureEngine {

    private final Context context;
    private AudioRecord recorder;
    private volatile boolean paused = false;
    private volatile int lastReadError = 0;
    private int bytesPerFrame = 4;

    public AudioRecordEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public StreamInfo open(AudioConfig config) throws IOException {
        lastReadError = 0;
        int rate = config.sampleRate;
        if (rate <= 0) {
            rate = resolveNativeRate();
        }
        int primaryChMask = (config.channelCount == 2)
                ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;
        int[] chCandidates = (config.channelCount == 2)
                ? new int[]{AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO}
                : new int[]{AudioFormat.CHANNEL_IN_MONO};

        int[] encodings = buildEncodingLadder(config.outputFormat);
        int[] rateCandidates = (rate == 48000) ? new int[]{48000, 44100} : new int[]{rate, 48000, 44100};
        int[] presetCandidates = buildPresetLadder(config.inputPreset.nativeValue);

        AudioRecord built = null;
        int actualRate = rate;
        int actualEnc = AudioFormat.ENCODING_PCM_FLOAT;
        int actualPreset = config.inputPreset.nativeValue;

        outer:
        for (int tryPreset : presetCandidates) {
            for (int tryChMask : chCandidates) {
                for (int tryRate : rateCandidates) {
                    for (int enc : encodings) {
                        int minBuf = AudioRecord.getMinBufferSize(tryRate, tryChMask, enc);
                        if (minBuf <= 0) continue;
                        int bufBytes = Math.max(minBuf * 4, 65536);
                        try {
                            AudioFormat af = new AudioFormat.Builder()
                                    .setSampleRate(tryRate)
                                    .setChannelMask(tryChMask)
                                    .setEncoding(enc)
                                    .build();
                            AudioRecord ar = new AudioRecord.Builder()
                                    .setAudioSource(tryPreset)
                                    .setAudioFormat(af)
                                    .setBufferSizeInBytes(bufBytes)
                                    .build();
                            if (ar.getState() == AudioRecord.STATE_INITIALIZED) {
                                built = ar;
                                actualRate = ar.getSampleRate();
                                actualEnc = enc;
                                actualPreset = tryPreset;
                                break outer;
                            } else {
                                ar.release();
                            }
                        } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException e) {
                            if (e instanceof SecurityException) {
                                throw new IOException("Microphone permission denied", e);
                            }
                        }
                    }
                }
            }
        }

        if (built == null) {
            throw new IOException("AudioRecord could not initialize with requested parameters");
        }

        int actualDevId = 0;
        if (config.deviceId > 0) {
            AudioDeviceInfo dev = AudioDeviceHelper.findDeviceById(context, config.deviceId);
            if (dev != null) {
                built.setPreferredDevice(dev);
                actualDevId = dev.getId();
            }
        }

        this.recorder = built;
        int hwFmt = encodingToPcmFormat(actualEnc);
        int actualCh = built.getChannelCount();
        this.bytesPerFrame = PcmConverter.bytesPerSample(hwFmt) * actualCh;
        String presetTag = AudioConfig.InputPreset.fromNativeValue(actualPreset).shortLabel;

        return new StreamInfo(
                "AUDIOREC",
                "DIRECT",
                presetTag,
                actualRate,
                actualCh,
                hwFmt,
                actualDevId,
                built.getBufferSizeInFrames(),
                this.bytesPerFrame
        );
    }

    private static int[] buildPresetLadder(int requested) {
        int vr = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int mic = android.media.MediaRecorder.AudioSource.MIC;
        if (requested == mic) return new int[]{mic};
        if (requested == vr) return new int[]{vr, mic};
        return new int[]{requested, vr, mic};
    }

    private int resolveNativeRate() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            String prop = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            if (prop != null) {
                try {
                    int r = Integer.parseInt(prop);
                    if (r >= 8000 && r <= 192000) return r;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 48000;
    }

    private static int[] buildEncodingLadder(AudioConfig.OutputFormat outFmt) {
        boolean api31 = Build.VERSION.SDK_INT >= 31;
        if (outFmt.isFloat) {
            return new int[]{AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT};
        }
        if (outFmt.bitsPerSample == 24) {
            return api31
                    ? new int[]{AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT}
                    : new int[]{AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT};
        }
        return new int[]{AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT};
    }

    private static int encodingToPcmFormat(int enc) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) return PcmConverter.FMT_PCM_I24_PACKED;
            if (enc == AudioFormat.ENCODING_PCM_32BIT) return PcmConverter.FMT_PCM_I32;
        }
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) return PcmConverter.FMT_PCM_FLOAT;
        return PcmConverter.FMT_PCM_I16;
    }

    @Override
    public void start() throws IOException {
        if (recorder == null) throw new IOException("AudioRecord not open");
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IOException("AudioRecord failed to start recording");
        }
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    @Override
    public int read(ByteBuffer directDst, int maxBytes) {
        AudioRecord ar = recorder;
        if (ar == null) return 0;
        int aligned = (maxBytes / bytesPerFrame) * bytesPerFrame;
        if (aligned <= 0) return 0;
        directDst.clear();
        directDst.limit(aligned);
        int n = ar.read(directDst, aligned, AudioRecord.READ_BLOCKING);
        if (n < 0) {
            lastReadError = n;
            return 0;
        }
        if (n == 0 || paused) return 0;
        return n;
    }

    @Override
    public void pollTelemetry(Telemetry out) {
        out.xrunCount = 0;
        out.ringOverruns = 0;
        int err = lastReadError;
        out.errorCode = err;
        out.errorText = (err != 0) ? ("AudioRecord error " + err) : null;
    }

    @Override
    public void stopAndClose() {
        AudioRecord ar = recorder;
        recorder = null;
        if (ar != null) {
            try {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            ar.release();
        }
    }
}
