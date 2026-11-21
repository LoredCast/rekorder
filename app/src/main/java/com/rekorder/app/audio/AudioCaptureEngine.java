// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Common interface implemented by our custom Native AAudio C driver
 * and the direct Android AudioRecord fallback engine.
 */
public interface AudioCaptureEngine {

    final class StreamInfo {
        public final String engineTag;       // e.g. "AAUDIO" or "AUDIOREC"
        public final String sharingTag;      // e.g. "EXCL", "SHARED", "DIRECT"
        public final String presetTag;       // e.g. "UNPROC", "VOICEREC"
        public final int sampleRate;
        public final int channelCount;
        public final int hardwareFormat;     // PcmConverter.FMT_*
        public final int deviceId;
        public final int framesPerBurst;
        public final int bytesPerFrame;

        public StreamInfo(String engineTag, String sharingTag, String presetTag,
                          int sampleRate, int channelCount, int hardwareFormat,
                          int deviceId, int framesPerBurst, int bytesPerFrame) {
            this.engineTag = engineTag;
            this.sharingTag = sharingTag;
            this.presetTag = presetTag;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.hardwareFormat = hardwareFormat;
            this.deviceId = deviceId;
            this.framesPerBurst = framesPerBurst;
            this.bytesPerFrame = bytesPerFrame;
        }

        public String telemetryLine(AudioConfig cfg, int xruns) {
            String rateStr = (sampleRate % 1000 == 0)
                    ? (sampleRate / 1000) + "kHz"
                    : String.format(java.util.Locale.US, "%.1fkHz", sampleRate / 1000.0);
            String fmtPart;
            if (cfg.outputFormat.usesBitrate) {
                fmtPart = cfg.outputFormat.shortLabel + " " + cfg.compressedBitrateKbps + "k";
            } else if (cfg.outputFormat.isCompressed) {
                fmtPart = cfg.outputFormat.shortLabel;
            } else {
                String hwFmt = PcmConverter.formatTag(hardwareFormat);
                String dstFmt = cfg.outputFormat.isFloat ? "32f" : (cfg.outputFormat.bitsPerSample + "b");
                fmtPart = PcmConverter.isDirectPassthrough(hardwareFormat, cfg.outputFormat)
                        ? dstFmt
                        : (hwFmt + "->" + dstFmt);
            }
            String chStr = channelCount == 2 ? "ST" : "MONO";
            String base = engineTag + " · " + sharingTag + " · " + presetTag
                    + " · " + rateStr + " · " + fmtPart + " · " + chStr;
            if (xruns > 0) {
                return base + " · " + xruns + " DROP";
            }
            return base;
        }
    }

    final class Telemetry {
        public int xrunCount;
        public int ringOverruns;
        public int errorCode;
        public String errorText;
    }

    StreamInfo open(AudioConfig config) throws IOException;

    void start() throws IOException;

    void setPaused(boolean paused);

    /**
     * Reads up to `maxBytes` of raw hardware PCM into `directDst` starting at position 0.
     * Returns number of bytes read (>= 0).
     */
    int read(ByteBuffer directDst, int maxBytes);

    void pollTelemetry(Telemetry out);

    void stopAndClose();
}
