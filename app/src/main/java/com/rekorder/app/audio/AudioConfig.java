// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.content.SharedPreferences;

public final class AudioConfig {

    public enum EngineMode {
        AAUDIO_EXCLUSIVE(0, "AAudio Exclusive (MMAP)", "AAUDIO EXCL"),
        AAUDIO_SHARED(1, "AAudio Shared (Low-Lat)", "AAUDIO SHRD"),
        AUDIORECORD(2, "AudioRecord (Direct)", "AUDIOREC");

        public final int id;
        public final String label;
        public final String shortLabel;

        EngineMode(int id, String label, String shortLabel) {
            this.id = id;
            this.label = label;
            this.shortLabel = shortLabel;
        }

        public static EngineMode fromId(int id) {
            for (EngineMode m : values()) {
                if (m.id == id) return m;
            }
            return AAUDIO_EXCLUSIVE;
        }
    }

    public enum InputPreset {
        /* Values match both AAudio aaudio_input_preset_t and MediaRecorder.AudioSource */
        UNPROCESSED(9, "Unprocessed (Raw Mic)", "UNPROC"),
        VOICE_RECOGNITION(6, "Voice Recognition (Low-DSP)", "VOICEREC"),
        CAMCORDER(5, "Camcorder (Wide/Stereo)", "CAMCORD"),
        GENERIC(1, "Generic Mic", "MIC");

        public final int nativeValue;
        public final String label;
        public final String shortLabel;

        InputPreset(int nativeValue, String label, String shortLabel) {
            this.nativeValue = nativeValue;
            this.label = label;
            this.shortLabel = shortLabel;
        }

        public static InputPreset fromNativeValue(int v) {
            for (InputPreset p : values()) {
                if (p.nativeValue == v) return p;
            }
            return UNPROCESSED;
        }
    }

    public enum OutputFormat {
        WAV_24(0, "WAV 24-bit PCM", "24b WAV", "wav", "audio/x-wav", 3, 24, false, true, false, false),
        WAV_32F(1, "WAV 32-bit Float", "32f WAV", "wav", "audio/x-wav", 4, 32, true, true, false, false),
        WAV_16(2, "WAV 16-bit PCM", "16b WAV", "wav", "audio/x-wav", 2, 16, false, true, false, false),
        RAW_32F(3, "RAW 32-bit Float (.pcm)", "32f RAW", "pcm", "application/octet-stream", 4, 32, true, false, false, false),
        RAW_24(4, "RAW 24-bit PCM (.pcm)", "24b RAW", "pcm", "application/octet-stream", 3, 24, false, false, false, false),
        FLAC(7, "FLAC Lossless (.flac)", "FLAC", "flac", "audio/flac", 2, 16, false, false, true, false),
        OGG_OPUS(5, "OGG Opus (.ogg)", "OGG", "ogg", "audio/ogg", 4, 32, true, false, true, true),
        MP3(6, "MP3 (.mp3)", "MP3", "mp3", "audio/mpeg", 4, 32, true, false, true, true);

        public final int id;
        public final String label;
        public final String shortLabel;
        public final String extension;
        public final String mimeType;
        public final int bytesPerSample;
        public final int bitsPerSample;
        public final boolean isFloat;
        public final boolean hasWavHeader;
        public final boolean isCompressed;
        public final boolean usesBitrate;

        OutputFormat(int id, String label, String shortLabel, String extension, String mimeType,
                     int bytesPerSample, int bitsPerSample, boolean isFloat, boolean hasWavHeader,
                     boolean isCompressed, boolean usesBitrate) {
            this.id = id;
            this.label = label;
            this.shortLabel = shortLabel;
            this.extension = extension;
            this.mimeType = mimeType;
            this.bytesPerSample = bytesPerSample;
            this.bitsPerSample = bitsPerSample;
            this.isFloat = isFloat;
            this.hasWavHeader = hasWavHeader;
            this.isCompressed = isCompressed;
            this.usesBitrate = usesBitrate;
        }

        public static OutputFormat fromId(int id) {
            for (OutputFormat f : values()) {
                if (f.id == id) return f;
            }
            return WAV_24;
        }

        public String rawTag() {
            if (isFloat) return "f32le";
            if (bitsPerSample == 24) return "s24le";
            return "s16le";
        }
    }

    public static final int[] SAMPLE_RATES = {0, 44100, 48000, 96000, 192000};
    public static final int[] BITRATES_KBPS = {128, 192, 256, 320};
    public static final int[] MAX_DURATION_OPTIONS_MIN = {0, 5, 15, 30, 60, 120};
    public static final int[] SILENCE_THRESH_OPTIONS_DB = {-35, -45, -55};

    public final EngineMode engineMode;
    public final InputPreset inputPreset;
    public final OutputFormat outputFormat;
    public final int sampleRate;            // 0 = hardware native
    public final int channelCount;          // 1 or 2
    public final int deviceId;              // 0 = system default input
    public final int outputDeviceId;        // 0 = system default output (independent of input!)
    public final int outputDeviceType;      // AudioDeviceInfo.TYPE_* fallback across reboots
    public final boolean keepScreenOn;
    public final int visualGain;            // 1, 2, or 4 (display-only waveform boost)
    public final int compressedBitrateKbps; // 128, 192, 256, 320 for OGG / MP3
    public final boolean headphoneMonitor;  // Live low-latency pass-through when headphones plugged in
    public final boolean silenceSkipEnabled;// VOX: auto-skip dead air after 1.5s below threshold
    public final int silenceThresholdDb;    // -35, -45, -55 dBFS
    public final int maxDurationMinutes;    // 0 = unlimited; auto-stop cleanly when reached
    public final String filenamePrefix;     // sanitized prefix, default "rek"
    public final boolean volumeKeyTrigger;  // VolUp = Rec/Pause, VolDown = +MARK while recording

    private static final String PREFS = "rekorder_prefs";

    public AudioConfig(EngineMode engineMode, InputPreset inputPreset, OutputFormat outputFormat,
                       int sampleRate, int channelCount, int deviceId,
                       boolean keepScreenOn, int visualGain, int compressedBitrateKbps) {
        this(engineMode, inputPreset, outputFormat, sampleRate, channelCount, deviceId,
                keepScreenOn, visualGain, compressedBitrateKbps, false, 0, 0,
                false, -45, 0, "rek", false);
    }

    public AudioConfig(EngineMode engineMode, InputPreset inputPreset, OutputFormat outputFormat,
                       int sampleRate, int channelCount, int deviceId,
                       boolean keepScreenOn, int visualGain, int compressedBitrateKbps,
                       boolean headphoneMonitor) {
        this(engineMode, inputPreset, outputFormat, sampleRate, channelCount, deviceId,
                keepScreenOn, visualGain, compressedBitrateKbps, headphoneMonitor, 0, 0,
                false, -45, 0, "rek", false);
    }

    public AudioConfig(EngineMode engineMode, InputPreset inputPreset, OutputFormat outputFormat,
                       int sampleRate, int channelCount, int deviceId,
                       boolean keepScreenOn, int visualGain, int compressedBitrateKbps,
                       boolean headphoneMonitor, int outputDeviceId) {
        this(engineMode, inputPreset, outputFormat, sampleRate, channelCount, deviceId,
                keepScreenOn, visualGain, compressedBitrateKbps, headphoneMonitor, outputDeviceId, 0,
                false, -45, 0, "rek", false);
    }

    public AudioConfig(EngineMode engineMode, InputPreset inputPreset, OutputFormat outputFormat,
                       int sampleRate, int channelCount, int deviceId,
                       boolean keepScreenOn, int visualGain, int compressedBitrateKbps,
                       boolean headphoneMonitor, int outputDeviceId, int outputDeviceType) {
        this(engineMode, inputPreset, outputFormat, sampleRate, channelCount, deviceId,
                keepScreenOn, visualGain, compressedBitrateKbps, headphoneMonitor,
                outputDeviceId, outputDeviceType, false, -45, 0, "rek", false);
    }

    public AudioConfig(EngineMode engineMode, InputPreset inputPreset, OutputFormat outputFormat,
                       int sampleRate, int channelCount, int deviceId,
                       boolean keepScreenOn, int visualGain, int compressedBitrateKbps,
                       boolean headphoneMonitor, int outputDeviceId, int outputDeviceType,
                       boolean silenceSkipEnabled, int silenceThresholdDb,
                       int maxDurationMinutes, String filenamePrefix, boolean volumeKeyTrigger) {
        this.engineMode = engineMode;
        this.inputPreset = inputPreset;
        this.outputFormat = outputFormat;
        this.sampleRate = sampleRate;
        this.channelCount = (channelCount == 2) ? 2 : 1;
        this.deviceId = Math.max(0, deviceId);
        this.outputDeviceId = Math.max(0, outputDeviceId);
        this.outputDeviceType = Math.max(0, outputDeviceType);
        this.keepScreenOn = keepScreenOn;
        this.visualGain = (visualGain == 2 || visualGain == 4) ? visualGain : 1;
        this.compressedBitrateKbps = (compressedBitrateKbps == 128 || compressedBitrateKbps == 192
                || compressedBitrateKbps == 256 || compressedBitrateKbps == 320)
                ? compressedBitrateKbps : 192;
        this.headphoneMonitor = headphoneMonitor;
        this.silenceSkipEnabled = silenceSkipEnabled;
        this.silenceThresholdDb = Math.max(-65, Math.min(-20, silenceThresholdDb));
        this.maxDurationMinutes = Math.max(0, Math.min(720, maxDurationMinutes));
        this.filenamePrefix = sanitizePrefix(filenamePrefix);
        this.volumeKeyTrigger = volumeKeyTrigger;
    }

    public static String sanitizePrefix(String raw) {
        if (raw == null) return "rek";
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.length() > 24) cleaned = cleaned.substring(0, 24);
        return cleaned.isEmpty() ? "rek" : cleaned;
    }

    public static AudioConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new AudioConfig(
                EngineMode.fromId(sp.getInt("engine_mode", EngineMode.AAUDIO_EXCLUSIVE.id)),
                InputPreset.fromNativeValue(sp.getInt("input_preset", InputPreset.UNPROCESSED.nativeValue)),
                OutputFormat.fromId(sp.getInt("output_format", OutputFormat.WAV_24.id)),
                sp.getInt("sample_rate", 48000),
                sp.getInt("channels", 1),
                sp.getInt("device_id", 0),
                sp.getBoolean("keep_screen_on", true),
                sp.getInt("visual_gain", 1),
                sp.getInt("bitrate_kbps", 192),
                sp.getBoolean("headphone_monitor", false),
                sp.getInt("output_device_id", 0),
                sp.getInt("output_device_type", 0),
                sp.getBoolean("silence_skip", false),
                sp.getInt("silence_thresh_db", -45),
                sp.getInt("max_duration_min", 0),
                sp.getString("filename_prefix", "rek"),
                sp.getBoolean("volume_key_trigger", false)
        );
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("engine_mode", engineMode.id)
                .putInt("input_preset", inputPreset.nativeValue)
                .putInt("output_format", outputFormat.id)
                .putInt("sample_rate", sampleRate)
                .putInt("channels", channelCount)
                .putInt("device_id", deviceId)
                .putInt("output_device_id", outputDeviceId)
                .putInt("output_device_type", outputDeviceType)
                .putBoolean("keep_screen_on", keepScreenOn)
                .putInt("visual_gain", visualGain)
                .putInt("bitrate_kbps", compressedBitrateKbps)
                .putBoolean("headphone_monitor", headphoneMonitor)
                .putBoolean("silence_skip", silenceSkipEnabled)
                .putInt("silence_thresh_db", silenceThresholdDb)
                .putInt("max_duration_min", maxDurationMinutes)
                .putString("filename_prefix", filenamePrefix)
                .putBoolean("volume_key_trigger", volumeKeyTrigger)
                .apply();
    }

    public AudioConfig withVisualGain(int newGain) {
        return new AudioConfig(engineMode, inputPreset, outputFormat, sampleRate, channelCount,
                deviceId, keepScreenOn, newGain, compressedBitrateKbps, headphoneMonitor,
                outputDeviceId, outputDeviceType, silenceSkipEnabled, silenceThresholdDb,
                maxDurationMinutes, filenamePrefix, volumeKeyTrigger);
    }

    public String formatShortDisplay() {
        if (outputFormat.usesBitrate) {
            return outputFormat.shortLabel + " " + compressedBitrateKbps + "k";
        }
        return outputFormat.shortLabel;
    }

    public static String formatSampleRate(int rate) {
        if (rate <= 0) return "Native";
        if (rate % 1000 == 0) return (rate / 1000) + " kHz";
        return String.format(java.util.Locale.US, "%.1f kHz", rate / 1000.0);
    }
}
