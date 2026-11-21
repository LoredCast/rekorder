// SPDX-License-Identifier: GPL-3.0-or-later
import com.rekorder.app.audio.AudioConfig;
import com.rekorder.app.audio.AudioProcessor;
import com.rekorder.app.audio.OggMuxer;
import com.rekorder.app.audio.PcmConverter;
import com.rekorder.app.audio.PostNormalizer;
import com.rekorder.app.audio.ProgressListener;
import com.rekorder.app.audio.WavWriter;
import com.rekorder.app.audio.WaveformAnalyzer;
import com.rekorder.app.dsp.AudioResampler;
import com.rekorder.app.dsp.DspChain;
import com.rekorder.app.dsp.DspConfig;
import com.rekorder.app.storage.MarkerStore;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone host JVM unit tests for PCM conversion, RIFF/WAVE headers,
 * RAW .pcm metadata, WaveformAnalyzer, 5-band EQ + HPF, Soft-Knee Compressor,
 * True Peak Normalizer, AudioResampler, and RFC 3533/7845 Ogg Opus Muxer.
 */
public final class HostUnitTests {

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void checkNear(double actual, double expected, double tol, String msg) {
        if (Math.abs(actual - expected) > tol) {
            throw new AssertionError("FAILED: " + msg + " (actual=" + actual + ", expected=" + expected + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        testPcmConversions();
        testMetering();
        testWav24RoundTrip();
        testWav32FloatWithFactChunk();
        testWavWithExtraJunkChunk();
        testRawPcmMeta();
        testWaveformAnalyzer();
        testDspBypassAndEq();
        testDspCompressorAndStereoLink();
        testPostNormalizerWav24();
        testAudioResampler();
        testOggMuxerOpus();
        testVariableBlockReuseNoOutOfBounds();
        testProgressReportingMonotonic();
        testSafDualChannelNormalize();
        testLosslessScratchRescueWithNanGuard();
        testWavCrashSafeCheckpoint();
        testDspAndResamplerNanRecovery();
        testInputGainPreampAndSoftCeiling();
        testStereoDualChannelMetering();
        testMetadataTagsOpusAndId3();
        testDeHumNotchFilter();
        testSoftNoiseGate();
        testLufsMeterAndPeakCappedGain();
        testCosineFadeAndMarkerStore();
        testDspConfigV11SummaryAndDefaults();
        System.out.println("PASS: All 26 HostUnitTests passed!");
    }

    private static void testPcmConversions() {
        float[] input = {0.0f, 0.5f, -0.5f, 1.0f, -1.0f, 1.5f, -2.0f};
        ByteBuffer fSrc = ByteBuffer.allocate(input.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : input) fSrc.putFloat(v);

        ByteBuffer i24Dst = ByteBuffer.allocate(input.length * 3).order(ByteOrder.LITTLE_ENDIAN);
        int n24 = PcmConverter.convertToOutput(fSrc, input.length * 4, PcmConverter.FMT_PCM_FLOAT,
                AudioConfig.OutputFormat.WAV_24, i24Dst);
        check(n24 == input.length * 3, "I24 byte count");

        ByteBuffer fBack = ByteBuffer.allocate(input.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        int nBack = PcmConverter.convertToOutput(i24Dst, n24, PcmConverter.FMT_PCM_I24_PACKED,
                AudioConfig.OutputFormat.WAV_32F, fBack);
        check(nBack == input.length * 4, "F32 roundtrip byte count");

        checkNear(fBack.getFloat(0), 0.0, 1e-6, "0.0 exact");
        checkNear(fBack.getFloat(4), 0.5, 1e-6, "0.5 roundtrip");
        checkNear(fBack.getFloat(8), -0.5, 1e-6, "-0.5 roundtrip");
        checkNear(fBack.getFloat(12), 1.0, 1e-6, "+1.0 roundtrip");
        checkNear(fBack.getFloat(16), -1.0, 1e-6, "-1.0 roundtrip");
        checkNear(fBack.getFloat(20), 1.0, 1e-6, "+1.5 clamped");
        checkNear(fBack.getFloat(24), -1.0, 1e-6, "-2.0 clamped");

        ByteBuffer fCopy = ByteBuffer.allocate(input.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        int nPass = PcmConverter.convertToOutput(fSrc, input.length * 4, PcmConverter.FMT_PCM_FLOAT,
                AudioConfig.OutputFormat.WAV_32F, fCopy);
        check(nPass == input.length * 4, "Passthrough byte count");
        for (int i = 0; i < input.length; i++) {
            check(Float.floatToRawIntBits(fCopy.getFloat(i * 4)) == Float.floatToRawIntBits(input[i]),
                    "Bit-exact F32 passthrough at " + i);
        }
    }

    private static void testMetering() {
        int n = 4800;
        ByteBuffer buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            float s = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0));
            buf.putFloat(s);
        }
        PcmConverter.MeterResult m = new PcmConverter.MeterResult();
        PcmConverter.computeMeter(buf, n * 4, PcmConverter.FMT_PCM_FLOAT, m);
        checkNear(m.peak, 0.5, 0.002, "Sine peak");
        checkNear(m.rms, 0.5 / Math.sqrt(2.0), 0.002, "Sine RMS");
        check(m.clipCount == 0, "No clips at -6 dBFS");
        checkNear(PcmConverter.linearToDbfs(m.peak), -6.02, 0.05, "dBFS conversion");
    }

    private static void testWav24RoundTrip() throws Exception {
        File tmp = File.createTempFile("rek_test24_", ".wav");
        tmp.deleteOnExit();

        int frames = 4800;
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            WavWriter w = new WavWriter(raf.getChannel(), AudioConfig.OutputFormat.WAV_24, 48000, 2);
            ByteBuffer pcm = ByteBuffer.allocate(frames * 6).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames * 2; i++) {
                int v = (i % 2 == 0) ? 0x123456 : -0x234567;
                pcm.put((byte) (v & 0xFF));
                pcm.put((byte) ((v >>> 8) & 0xFF));
                pcm.put((byte) ((v >>> 16) & 0xFF));
            }
            w.write(pcm, pcm.capacity());
            w.finalizeAndFlush();
            check(w.getTotalFileBytes() == 44 + frames * 6, "24-bit WAV total file size");
        }

        try (FileInputStream fis = new FileInputStream(tmp)) {
            WavWriter.AudioFileMeta meta = WavWriter.parseMeta(fis, tmp.length(), tmp.getName());
            check(meta.sampleRate == 48000 && meta.channels == 2 && meta.bitsPerSample == 24, "24b WAV meta");
            check(meta.dataOffset == 44 && meta.dataBytes == frames * 6L && meta.durationMs() == 100L, "24b WAV sizes");
        }
    }

    private static void testWav32FloatWithFactChunk() throws Exception {
        File tmp = File.createTempFile("rek_test32f_", ".wav");
        tmp.deleteOnExit();

        int frames = 9600;
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            WavWriter w = new WavWriter(raf.getChannel(), AudioConfig.OutputFormat.WAV_32F, 96000, 1);
            ByteBuffer pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) pcm.putFloat(0.25f);
            w.write(pcm, pcm.capacity());
            w.finalizeAndFlush();
            check(w.getTotalFileBytes() == 58 + frames * 4, "32f WAV total file size");
        }

        byte[] hdr = new byte[58];
        try (FileInputStream fis = new FileInputStream(tmp)) {
            check(fis.read(hdr) == 58, "Read 58B header");
        }
        ByteBuffer hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        check(hb.getShort(20) == 3, "WAVE_FORMAT_IEEE_FLOAT tag");
        check(hdr[38] == 'f' && hdr[39] == 'a' && hdr[40] == 'c' && hdr[41] == 't', "fact chunk id");
        check(hb.getInt(46) == frames, "fact chunk frame count");

        try (FileInputStream fis = new FileInputStream(tmp)) {
            WavWriter.AudioFileMeta meta = WavWriter.parseMeta(fis, tmp.length(), tmp.getName());
            check(meta.sampleRate == 96000 && meta.channels == 1 && meta.isFloat && meta.dataOffset == 58, "32f WAV meta");
        }
    }

    private static void testWavWithExtraJunkChunk() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")); b.putInt(56);
        b.put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")); b.putInt(16);
        b.putShort((short) 1); b.putShort((short) 1); b.putInt(44100); b.putInt(88200);
        b.putShort((short) 2); b.putShort((short) 16);
        b.put("JUNK".getBytes("US-ASCII")); b.putInt(4); b.putInt(0);
        b.put("data".getBytes("US-ASCII")); b.putInt(8); b.putLong(0L);

        WavWriter.AudioFileMeta meta = WavWriter.parseMeta(
                new ByteArrayInputStream(b.array()), b.capacity(), "custom.wav");
        check(meta.sampleRate == 44100 && meta.dataOffset == 56 && meta.dataBytes == 8, "Extra-chunk WAV");
    }

    private static void testRawPcmMeta() throws Exception {
        String name = "rek_20261120_180000_96000Hz_f32le_2ch.pcm";
        WavWriter.AudioFileMeta meta = WavWriter.parseMeta(new ByteArrayInputStream(new byte[0]), 96000L * 8L, name);
        check(meta.isRawPcm && meta.sampleRate == 96000 && meta.channels == 2 && meta.isFloat && meta.durationMs() == 1000L, "RAW meta");
    }

    private static void testWaveformAnalyzer() throws Exception {
        File tmp = File.createTempFile("rek_wave_", ".wav");
        tmp.deleteOnExit();

        int halfFrames = 4800;
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            WavWriter w = new WavWriter(raf.getChannel(), AudioConfig.OutputFormat.WAV_24, 48000, 1);
            ByteBuffer fBuf = ByteBuffer.allocate(halfFrames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < halfFrames; i++) fBuf.putFloat((float) (0.125 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0)));
            for (int i = 0; i < halfFrames; i++) fBuf.putFloat((float) (0.75 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0)));
            ByteBuffer i24 = ByteBuffer.allocate(halfFrames * 2 * 3).order(ByteOrder.LITTLE_ENDIAN);
            int n = PcmConverter.convertToOutput(fBuf, fBuf.capacity(), PcmConverter.FMT_PCM_FLOAT,
                    AudioConfig.OutputFormat.WAV_24, i24);
            w.write(i24, n);
            w.finalizeAndFlush();
        }

        try (FileInputStream fis = new FileInputStream(tmp)) {
            WaveformAnalyzer.WaveformData data = WaveformAnalyzer.analyze(fis, tmp.length(), tmp.getName(), 100);
            check(data.peaks.length == 100, "Waveform bin count");
            checkNear(data.peaks[20], 0.125, 0.015, "Quiet half peak");
            checkNear(data.peaks[75], 0.75, 0.015, "Loud half peak");
            check(data.rms[75] > data.rms[20] * 4.0f, "Loud RMS > quiet RMS");
        }
    }

    private static void testDspBypassAndEq() {
        int sr = 48000;
        int n = 4800;

        // 1. Bypass must be 100% bit-exact untouched
        DspChain bypass = new DspChain(sr, 1, DspConfig.rawBypass());
        ByteBuffer buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) buf.putFloat(i * 4, (float) (0.3 * Math.sin(2 * Math.PI * 1000.0 * i / sr)));
        float before = buf.getFloat(1234 * 4);
        bypass.processInPlace(buf, n);
        check(Float.floatToRawIntBits(buf.getFloat(1234 * 4)) == Float.floatToRawIntBits(before), "Bypass bit-exact");

        // 2. 120 Hz HPF attenuates 30 Hz sub-bass rumble by > 20 dB while preserving 1 kHz
        DspConfig hpfCfg = new DspConfig("HPF", true, true, 120, new float[]{0, 0, 0, 0, 0},
                false, -18, 4, 10, 120, 0, false, -1);
        DspChain hpfChain = new DspChain(sr, 1, hpfCfg);

        ByteBuffer sub = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) sub.putFloat(i * 4, (float) (0.5 * Math.sin(2 * Math.PI * 30.0 * i / sr)));
        hpfChain.processInPlace(sub, n);
        double subOutPeak = 0;
        for (int i = n / 2; i < n; i++) subOutPeak = Math.max(subOutPeak, Math.abs(sub.getFloat(i * 4)));
        double attenDb = 20.0 * Math.log10(subOutPeak / 0.5);
        check(attenDb < -20.0, "120Hz HPF attenuates 30Hz by >20dB (got " + attenDb + " dB)");

        // 3. 1 kHz peaking band +6 dB boosts 1 kHz sine by ~+6 dB
        DspConfig eqCfg = new DspConfig("EQ", true, true, 0, new float[]{0, 0, 6.0f, 0, 0},
                false, -18, 4, 10, 120, 0, false, -1);
        DspChain eqChain = new DspChain(sr, 1, eqCfg);
        ByteBuffer mid = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) mid.putFloat(i * 4, (float) (0.2 * Math.sin(2 * Math.PI * 1000.0 * i / sr)));
        eqChain.processInPlace(mid, n);
        double midPeak = 0;
        for (int i = n / 2; i < n; i++) midPeak = Math.max(midPeak, Math.abs(mid.getFloat(i * 4)));
        double boostDb = 20.0 * Math.log10(midPeak / 0.2);
        checkNear(boostDb, 6.0, 0.25, "1kHz +6dB EQ peak boost");
    }

    private static void testDspCompressorAndStereoLink() {
        int sr = 48000;
        int n = 9600; // 200 ms so attack settles completely
        DspConfig cfg = new DspConfig("Comp", true, false, 0, new float[]{0, 0, 0, 0, 0},
                true, -20.0f, 4.0f, 2.0f, 100.0f, 0.0f, false, -1.0f);
        DspChain chain = new DspChain(sr, 2, cfg);

        // Loud L = 0.8 (-1.94 dBFS), Quiet R = 0.08 (-21.94 dBFS) -> ratio L/R = 10.0 must be preserved!
        ByteBuffer st = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            double s = Math.sin(2.0 * Math.PI * 500.0 * i / sr);
            st.putFloat(i * 8, (float) (0.8 * s));
            st.putFloat(i * 8 + 4, (float) (0.08 * s));
        }
        chain.processInPlace(st, n);

        double pkL = 0, pkR = 0;
        for (int i = n / 2; i < n; i++) {
            pkL = Math.max(pkL, Math.abs(st.getFloat(i * 8)));
            pkR = Math.max(pkR, Math.abs(st.getFloat(i * 8 + 4)));
        }
        double outDbL = 20.0 * Math.log10(pkL);
        check(outDbL < -10.0 && outDbL > -18.0, "4:1 compressor attenuates loud signal (got " + outDbL + " dB)");
        check(chain.getLastGainReductionDb() < -8.0f, "Live GR telemetry reports reduction");
        checkNear(pkL / pkR, 10.0, 0.05, "Stereo-linked compression preserves exact L/R imaging ratio");
    }

    private static void testPostNormalizerWav24() throws Exception {
        File tmp = File.createTempFile("rek_norm_", ".wav");
        tmp.deleteOnExit();

        int frames = 4800;
        float inputPeak = 0.1f; // -20 dBFS quiet raw mic recording
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            WavWriter w = new WavWriter(raf.getChannel(), AudioConfig.OutputFormat.WAV_24, 48000, 1);
            ByteBuffer fBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                fBuf.putFloat((float) (inputPeak * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0)));
            }
            ByteBuffer i24 = ByteBuffer.allocate(frames * 3).order(ByteOrder.LITTLE_ENDIAN);
            int n = PcmConverter.convertToOutput(fBuf, fBuf.capacity(), PcmConverter.FMT_PCM_FLOAT,
                    AudioConfig.OutputFormat.WAV_24, i24);
            w.write(i24, n);
            w.finalizeAndFlush();

            float appliedDb = PostNormalizer.normalizePcmInPlace(
                    raf.getChannel(), AudioConfig.OutputFormat.WAV_24,
                    44L, w.getDataBytesWritten(), inputPeak, -1.0f);
            checkNear(appliedDb, 19.0, 0.15, "Applied +19 dB normalization gain");
        }

        try (FileInputStream fis = new FileInputStream(tmp)) {
            WaveformAnalyzer.WaveformData data = WaveformAnalyzer.analyze(fis, tmp.length(), tmp.getName(), 32);
            double normDb = 20.0 * Math.log10(data.maxPeak);
            checkNear(normDb, -1.0, 0.1, "Normalized WAV24 hits exact -1.0 dBFS peak");
        }
    }

    private static void testAudioResampler() {
        // Downsample 1 kHz sine from 96 kHz -> 48 kHz
        int inFrames = 9600;
        ByteBuffer in = ByteBuffer.allocate(inFrames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < inFrames; i++) {
            in.putFloat(i * 4, (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 96000.0)));
        }
        ByteBuffer out = ByteBuffer.allocate(inFrames * 4).order(ByteOrder.LITTLE_ENDIAN);
        AudioResampler rs = new AudioResampler(96000, 48000, 1);
        int outFrames = rs.process(in, inFrames, out);
        check(Math.abs(outFrames - 4800) <= 2, "96k->48k frame count: " + outFrames);

        // Verify 1 kHz tone purity over 80 exact periods (3840 samples @ 48 kHz)
        double re = 0, im = 0, tot = 0;
        int start = 400, end = 400 + 3840, len = end - start;
        for (int i = start; i < end; i++) {
            double v = out.getFloat(i * 4);
            tot += v * v;
            re += v * Math.cos(2.0 * Math.PI * 1000.0 * i / 48000.0);
            im += v * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0);
        }
        double amp = 2.0 * Math.hypot(re, im) / len;
        double toneP = 0.5 * amp * amp;
        double snr = 10.0 * Math.log10(toneP / Math.max(1e-12, tot / len - toneP));
        check(snr > 50.0, "Resampler 96k->48k 1kHz purity SNR=" + snr + " dB");
    }

    private static boolean hasFfprobe() {
        try {
            Process p = new ProcessBuilder("ffprobe", "-version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void testOggMuxerOpus() throws Exception {
        File tmp = File.createTempFile("rek_ogg_", ".ogg");
        tmp.deleteOnExit();

        // Standard RFC 6716 20ms CELT/SILK Opus silence packet: TOC=0xFC (CELT FB 20ms mono, 1 frame) + 2 zero bytes
        byte[] opusSilence = new byte[]{(byte) 0xFC, (byte) 0xFF, (byte) 0xFE};
        check(OggMuxer.opusPacketSamples48k(opusSilence, 0, opusSilence.length) == 960, "Opus TOC 20ms=960");

        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            OggMuxer mux = new OggMuxer(raf.getChannel(), 0x12345678);
            byte[] head = OggMuxer.buildOpusHead(1, 312, 48000);
            byte[] tags = OggMuxer.buildOpusTags("Rekorder");
            mux.writePacket(head, 0, head.length, 0L, OggMuxer.FLAG_BOS);
            mux.writePacket(tags, 0, tags.length, 0L, 0);
            for (int i = 1; i <= 50; i++) {
                mux.writePacket(opusSilence, 0, opusSilence.length, i * 960L,
                        (i == 50) ? OggMuxer.FLAG_EOS : 0);
            }
            check(mux.getTotalBytesWritten() > 1500, "Ogg file written");
        }

        // Always verify OggS page capture pattern and OpusHead/OpusTags framing in pure Java
        byte[] rawOgg;
        try (FileInputStream fis = new FileInputStream(tmp)) {
            rawOgg = fis.readAllBytes();
        }
        String rawStr = new String(rawOgg, java.nio.charset.StandardCharsets.ISO_8859_1);
        check(rawStr.startsWith("OggS") && rawStr.contains("OpusHead") && rawStr.contains("OpusTags"),
                "OggS/OpusHead/OpusTags framing valid");

        // Verify exact OggS magic + CRC via ffprobe if available
        if (hasFfprobe()) {
            Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=codec_name,sample_rate,channels",
                    "-of", "default=noprint_wrappers=1", tmp.getAbsolutePath()).start();
            byte[] outBytes = p.getInputStream().readAllBytes();
            byte[] errBytes = p.getErrorStream().readAllBytes();
            int rc = p.waitFor();
            String err = new String(errBytes);
            String out = new String(outBytes);
            check(rc == 0 && err.isEmpty(), "ffprobe Ogg Opus check failed: " + err);
            check(out.contains("codec_name=opus") && out.contains("sample_rate=48000") && out.contains("channels=1"),
                    "ffprobe Ogg Opus metadata: " + out);
        }
    }

    /**
     * Regression test for ByteBuffer limit narrowing across variable-size hardware audio bursts
     * (e.g., burst 1 = 192 frames -> WavWriter sets limit -> burst 2 = 1024 frames).
     */
    private static void testVariableBlockReuseNoOutOfBounds() throws Exception {
        int[] hwFormats = {
                PcmConverter.FMT_PCM_I16,
                PcmConverter.FMT_PCM_I24_PACKED,
                PcmConverter.FMT_PCM_I32,
                PcmConverter.FMT_PCM_FLOAT
        };
        AudioConfig.OutputFormat[] outFormats = {
                AudioConfig.OutputFormat.WAV_24,
                AudioConfig.OutputFormat.WAV_32F,
                AudioConfig.OutputFormat.WAV_16,
                AudioConfig.OutputFormat.RAW_32F,
                AudioConfig.OutputFormat.RAW_24
        };
        int[] burstFrames = {48, 192, 96, 768, 128, 2048, 64, 4096};
        PcmConverter.MeterResult meter = new PcmConverter.MeterResult();
        DspConfig fxCfg = new DspConfig("FX", true, true, 80, new float[]{1, -1, 2, 1, 0},
                true, -18, 3, 10, 120, 3, false, -1);

        for (int hwFmt : hwFormats) {
            int hwBps = PcmConverter.bytesPerSample(hwFmt);
            for (AudioConfig.OutputFormat outFmt : outFormats) {
                File tmp = File.createTempFile("rek_var_", "." + outFmt.extension);
                tmp.deleteOnExit();
                int ch = 2;
                int maxRead = 4096 * hwBps * ch;
                ByteBuffer rawBuf = ByteBuffer.allocateDirect(maxRead).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer floatBuf = ByteBuffer.allocateDirect(maxRead * 2).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer outBuf = ByteBuffer.allocateDirect(maxRead * 2).order(ByteOrder.LITTLE_ENDIAN);
                DspChain dsp = new DspChain(48000, ch, fxCfg);
                AudioResampler rs = new AudioResampler(44100, 48000, ch);
                ByteBuffer rsOut = ByteBuffer.allocateDirect(maxRead * 3).order(ByteOrder.LITTLE_ENDIAN);

                try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                    WavWriter w = new WavWriter(raf.getChannel(), outFmt, 48000, ch);
                    for (int frames : burstFrames) {
                        int got = frames * hwBps * ch;
                        // Simulate direct-path (no DSP) AND float-path (with DSP + resampler)
                        PcmConverter.computeMeter(rawBuf, got, hwFmt, meter);
                        int ob = PcmConverter.convertToOutput(rawBuf, got, hwFmt, outFmt, outBuf);
                        w.write(outBuf, ob); // Narrows outBuf.limit(ob)!

                        int fb = PcmConverter.convertToOutput(rawBuf, got, hwFmt, AudioConfig.OutputFormat.WAV_32F, floatBuf);
                        dsp.processInPlace(floatBuf, frames);
                        PcmConverter.computeMeter(floatBuf, fb, PcmConverter.FMT_PCM_FLOAT, meter);
                        rs.process(floatBuf, frames, rsOut);
                        int ob2 = PcmConverter.convertToOutput(floatBuf, fb, PcmConverter.FMT_PCM_FLOAT, outFmt, outBuf);
                        w.write(outBuf, ob2);
                    }
                    w.finalizeAndFlush();
                    check(w.getFramesWritten() > 0, "Frames written for " + hwFmt + "->" + outFmt);
                }
            }
        }
    }

    private static void testProgressReportingMonotonic() throws Exception {
        File tmp = File.createTempFile("rek_prog_", ".wav");
        tmp.deleteOnExit();

        // ~3 MB 24-bit stereo WAV (~10.9 seconds @ 48 kHz) so multiple 256 KB / 128 KB chunks run
        int totalFrames = 524288;
        int chunkFrames = 8192;
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            WavWriter w = new WavWriter(raf.getChannel(), AudioConfig.OutputFormat.WAV_24, 48000, 2);
            ByteBuffer fBuf = ByteBuffer.allocate(chunkFrames * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < chunkFrames * 2; i++) fBuf.putFloat(0.2f);
            ByteBuffer i24 = ByteBuffer.allocate(chunkFrames * 6).order(ByteOrder.LITTLE_ENDIAN);
            int n = PcmConverter.convertToOutput(fBuf, fBuf.capacity(), PcmConverter.FMT_PCM_FLOAT,
                    AudioConfig.OutputFormat.WAV_24, i24);
            for (int f = 0; f < totalFrames; f += chunkFrames) {
                w.write(i24, n);
            }
            w.finalizeAndFlush();

            List<Integer> normPcts = new ArrayList<>();
            float db = PostNormalizer.normalizePcmInPlace(
                    raf.getChannel(), AudioConfig.OutputFormat.WAV_24,
                    44L, w.getDataBytesWritten(), 0.2f, -1.0f,
                    (done, total, pct) -> normPcts.add(pct));
            checkNear(db, 12.98, 0.15, "Normalizer gain for 0.2 -> -1 dBFS");
            check(normPcts.size() >= 2, "Normalizer emits start and finish progress");
            check(normPcts.get(0) == 0 && normPcts.get(normPcts.size() - 1) == 100,
                    "Normalizer starts at 0% and ends at 100%: " + normPcts);
            for (int i = 1; i < normPcts.size(); i++) {
                check(normPcts.get(i) >= normPcts.get(i - 1), "Normalizer progress monotonic: " + normPcts);
            }
        }

        List<Integer> wavePcts = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(tmp)) {
            WaveformAnalyzer.WaveformData wd = WaveformAnalyzer.analyze(
                    fis, tmp.length(), tmp.getName(), 128,
                    (done, total, pct) -> wavePcts.add(pct));
            check(wd.peaks.length == 128, "Waveform bins");
            check(wavePcts.size() >= 2, "WaveformAnalyzer emits start and finish progress");
            check(wavePcts.get(0) == 0 && wavePcts.get(wavePcts.size() - 1) == 100,
                    "WaveformAnalyzer starts at 0% and ends at 100%: " + wavePcts);
            for (int i = 1; i < wavePcts.size(); i++) {
                check(wavePcts.get(i) >= wavePcts.get(i - 1), "WaveformAnalyzer progress monotonic: " + wavePcts);
            }
        }
    }

    /** Simulates Android SAF where FileOutputStream's FileChannel is write-only and reads use a separate channel. */
    private static void testSafDualChannelNormalize() throws Exception {
        File tmp = File.createTempFile("rek_saf_norm_", ".wav");
        tmp.deleteOnExit();

        int frames = 4800;
        float inputPeak = 0.25f; // -12.04 dBFS
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            WavWriter w = new WavWriter(fos.getChannel(), AudioConfig.OutputFormat.WAV_24, 48000, 1);
            ByteBuffer fBuf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                fBuf.putFloat((float) (inputPeak * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0)));
            }
            ByteBuffer i24 = ByteBuffer.allocate(frames * 3).order(ByteOrder.LITTLE_ENDIAN);
            int n = PcmConverter.convertToOutput(fBuf, fBuf.capacity(), PcmConverter.FMT_PCM_FLOAT,
                    AudioConfig.OutputFormat.WAV_24, i24);
            w.write(i24, n);
            w.finalizeAndFlush();

            // Notice fos.getChannel() is strictly WRITE-ONLY (just like Android SAF!)
            try (FileInputStream fis = new FileInputStream(tmp)) {
                float db = PostNormalizer.normalizePcmInPlace(
                        fis.getChannel(), fos.getChannel(), AudioConfig.OutputFormat.WAV_24,
                        44L, w.getDataBytesWritten(), inputPeak, -1.0f, null);
                checkNear(db, 11.04, 0.15, "SAF dual-channel normalization gain");
            }
        }

        try (FileInputStream fis = new FileInputStream(tmp)) {
            WaveformAnalyzer.WaveformData wd = WaveformAnalyzer.analyze(fis, tmp.length(), tmp.getName(), 32);
            double normDb = 20.0 * Math.log10(wd.maxPeak);
            checkNear(normDb, -1.0, 0.1, "SAF normalized WAV24 hits -1.0 dBFS");
        }
    }

    /** Verifies fail-safe lossless WAV rescue from float32 scratch (including NaN/Inf sanitization). */
    private static void testLosslessScratchRescueWithNanGuard() throws Exception {
        File scratch = File.createTempFile("rek_scratch_", ".f32");
        File outWav = File.createTempFile("rek_rescue_", ".wav");
        scratch.deleteOnExit();
        outWav.deleteOnExit();

        int frames = 9600;
        try (RandomAccessFile sraf = new RandomAccessFile(scratch, "rw")) {
            ByteBuffer fBuf = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                float s = (float) (0.2 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0));
                if (i == 500) s = Float.NaN;
                if (i == 600) s = Float.POSITIVE_INFINITY;
                fBuf.putFloat(s);
                fBuf.putFloat(-s);
            }
            fBuf.flip();
            sraf.getChannel().write(fBuf);
        }

        List<Integer> pcts = new ArrayList<>();
        try (RandomAccessFile wraf = new RandomAccessFile(outWav, "rw")) {
            long bytes = PostNormalizer.convertScratchToLosslessWav(
                    scratch, wraf.getChannel(), 48000, 2, 0.2f, true, -1.0f,
                    (done, total, pct) -> pcts.add(pct));
            check(bytes == 44L + frames * 6L, "Rescued WAV24 exact byte count");
            check(pcts.get(0) == 0 && pcts.get(pcts.size() - 1) == 100, "Rescue progress 0..100");
        }

        try (FileInputStream fis = new FileInputStream(outWav)) {
            WaveformAnalyzer.WaveformData wd = WaveformAnalyzer.analyze(fis, outWav.length(), outWav.getName(), 32);
            check(wd.meta.sampleRate == 48000 && wd.meta.channels == 2 && wd.meta.bitsPerSample == 24, "Rescue WAV meta");
            double normDb = 20.0 * Math.log10(wd.maxPeak);
            checkNear(normDb, -1.0, 0.15, "Rescued WAV normalized cleanly despite NaN/Inf");
        }
    }

    /** Verifies WavWriter.checkpointHeader() produces valid readable WAV mid-take AND continues cleanly. */
    private static void testWavCrashSafeCheckpoint() throws Exception {
        File tmp = File.createTempFile("rek_ckpt_", ".wav");
        tmp.deleteOnExit();

        int halfFrames = 2400;
        ByteBuffer pcm = ByteBuffer.allocate(halfFrames * 3).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < halfFrames; i++) {
            pcm.put((byte) 0x10).put((byte) 0x20).put((byte) 0x30);
        }

        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            WavWriter w = new WavWriter(raf.getChannel(), AudioConfig.OutputFormat.WAV_24, 48000, 1);
            w.write(pcm, pcm.capacity());
            w.checkpointHeader();

            // Simulate sudden battery pull right after checkpoint: file MUST already be valid!
            try (FileInputStream fis = new FileInputStream(tmp)) {
                WavWriter.AudioFileMeta mid = WavWriter.parseMeta(fis, tmp.length(), tmp.getName());
                check(mid.dataBytes == halfFrames * 3L, "Checkpointed WAV mid-take dataBytes matches");
                check(mid.durationMs() == 50L, "Checkpointed WAV mid-take duration=50ms");
            }

            // Continue writing second half and finalize normally
            w.write(pcm, pcm.capacity());
            w.finalizeAndFlush();
            check(w.getTotalFileBytes() == 44L + halfFrames * 6L, "Final WAV size after checkpoint+append");
        }

        try (FileInputStream fis = new FileInputStream(tmp)) {
            WavWriter.AudioFileMeta end = WavWriter.parseMeta(fis, tmp.length(), tmp.getName());
            check(end.dataBytes == halfFrames * 6L && end.durationMs() == 100L, "Final WAV valid after checkpoint");
        }
    }

    /** Verifies DspChain and AudioResampler survive NaN/Inf glitches without poisoning biquad state. */
    private static void testDspAndResamplerNanRecovery() {
        int n = 4800;
        DspConfig cfg = new DspConfig("FX", true, true, 80, new float[]{2, -1, 3, 2, 1},
                true, -18, 4, 5, 80, 2, false, -1);
        DspChain dsp = new DspChain(48000, 2, cfg);
        ByteBuffer buf = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            float s = (float) (0.3 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0));
            buf.putFloat(i * 8, (i == 100) ? Float.NaN : s);
            buf.putFloat(i * 8 + 4, (i == 100) ? Float.NEGATIVE_INFINITY : s);
        }
        dsp.processInPlace(buf, n);
        for (int i = 0; i < n * 2; i++) {
            float v = buf.getFloat(i * 4);
            check(Float.isFinite(v), "DspChain output sample " + i + " must be finite");
        }

        AudioResampler rs = new AudioResampler(96000, 48000, 2);
        ByteBuffer rsOut = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(200 * 8, Float.NaN);
        int outF = rs.process(buf, n, rsOut);
        check(outF > 2000, "Resampler emitted frames after NaN");
        for (int i = 0; i < outF * 2; i++) {
            check(Float.isFinite(rsOut.getFloat(i * 4)), "Resampler output sample " + i + " must be finite");
        }
    }

    private static void testInputGainPreampAndSoftCeiling() {
        int n = 4800;
        // +6 dB preamp doubles 0.20 -> ~0.40 without compressor/EQ
        DspConfig g6 = new DspConfig("Gain6", true, 6.0f,
                false, 0, new float[]{0, 0, 0, 0, 0},
                false, -18, 4, 10, 120, 0, false, -1);
        check(g6.hasRealTimeFx(), "+6dB input gain activates real-time FX");
        DspChain c1 = new DspChain(48000, 1, g6);
        ByteBuffer b1 = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) b1.putFloat(i * 4, (float) (0.20 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0)));
        c1.processInPlace(b1, n);
        float pk1 = 0f;
        for (int i = 0; i < n; i++) pk1 = Math.max(pk1, Math.abs(b1.getFloat(i * 4)));
        checkNear(pk1, 0.399, 0.01, "+6dB preamp doubles amplitude cleanly");

        // +24 dB on 0.5 signal must soft-limit strictly below 1.0 (never hard clip)
        DspConfig g24 = new DspConfig("Gain24", true, 24.0f,
                false, 0, new float[]{0, 0, 0, 0, 0},
                false, -18, 4, 10, 120, 0, false, -1);
        DspChain c2 = new DspChain(48000, 1, g24);
        ByteBuffer b2 = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) b2.putFloat(i * 4, 0.5f);
        c2.processInPlace(b2, n);
        float v = b2.getFloat(0);
        check(v > 0.94f && v < 1.0f, "+24dB soft ceiling limits cleanly (got " + v + ")");
    }

    private static void testStereoDualChannelMetering() {
        int n = 4800;
        ByteBuffer st = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            double s = Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0);
            st.putFloat(i * 8, (float) (0.8 * s));
            st.putFloat(i * 8 + 4, (float) (0.1 * s));
        }
        PcmConverter.MeterResult m = new PcmConverter.MeterResult();
        PcmConverter.computeMeter(st, n * 8, PcmConverter.FMT_PCM_FLOAT, 2, m);
        check(m.channelCount == 2, "Stereo channel count");
        checkNear(m.peakL, 0.8, 0.005, "Stereo Left peak");
        checkNear(m.peakR, 0.1, 0.005, "Stereo Right peak");
        checkNear(m.rmsL / m.rmsR, 8.0, 0.05, "Stereo L/R RMS ratio");
        checkNear(m.peak, 0.8, 0.005, "Combined stereo peak");
    }

    private static void testMetadataTagsOpusAndId3() throws Exception {
        // Test VorbisComment OpusTags muxing & ffprobe validation
        File tmp = File.createTempFile("rek_tags_", ".ogg");
        tmp.deleteOnExit();
        byte[] opusSilence = new byte[]{(byte) 0xFC, (byte) 0xFF, (byte) 0xFE};
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            OggMuxer mux = new OggMuxer(raf.getChannel(), 0x22334455);
            byte[] head = OggMuxer.buildOpusHead(1, 312, 48000);
            byte[] tags = OggMuxer.buildOpusTags("Rekorder",
                    "TITLE=Studio Take", "ARTIST=Rekorder", "DATE=2026-11-20");
            mux.writePacket(head, 0, head.length, 0L, OggMuxer.FLAG_BOS);
            mux.writePacket(tags, 0, tags.length, 0L, 0);
            mux.writePacket(opusSilence, 0, opusSilence.length, 960L, OggMuxer.FLAG_EOS);
        }
        byte[] rawOgg;
        try (FileInputStream fis = new FileInputStream(tmp)) {
            rawOgg = fis.readAllBytes();
        }
        String rawStr = new String(rawOgg, java.nio.charset.StandardCharsets.ISO_8859_1);
        check(rawStr.contains("TITLE=Studio Take") && rawStr.contains("ARTIST=Rekorder"),
                "OpusTags raw VorbisComment fields present");
        if (hasFfprobe()) {
            Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream_tags",
                    "-of", "default=noprint_wrappers=1", tmp.getAbsolutePath()).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            check(p.waitFor() == 0 && err.isEmpty(), "ffprobe OpusTags check failed: " + err);
            check(out.contains("Studio Take") && out.contains("Rekorder"), "OpusTags metadata parsed by ffprobe: " + out);
        }
    }

    private static void testDeHumNotchFilter() {
        int sr = 48000;
        int n = sr; // 1.0s settle
        DspConfig humCfg = new DspConfig("DeHum", true, 0f, 50, false, -45f,
                false, 0, new float[]{0, 0, 0, 0, 0},
                false, -18f, 3f, 10f, 120f, 0f, false, -1f, false);
        DspChain chain = new DspChain(sr, 1, humCfg);

        ByteBuffer humBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            humBuf.putFloat(i << 2, (float) (0.5 * Math.sin(2.0 * Math.PI * 50.0 * i / sr)));
        }
        chain.processInPlace(humBuf, n);
        // Check RMS over second half after filter settles
        double humSq = 0.0;
        for (int i = n / 2; i < n; i++) {
            double s = humBuf.getFloat(i << 2);
            humSq += s * s;
        }
        double humRms = Math.sqrt(humSq / (n / 2.0));
        check(humRms < 0.02, "50Hz de-hum notch attenuates 50Hz strongly: rms=" + humRms);

        chain.resetState();
        ByteBuffer voiceBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            voiceBuf.putFloat(i << 2, (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / sr)));
        }
        chain.processInPlace(voiceBuf, n);
        double vSq = 0.0;
        for (int i = n / 2; i < n; i++) {
            double s = voiceBuf.getFloat(i << 2);
            vSq += s * s;
        }
        double vRms = Math.sqrt(vSq / (n / 2.0));
        checkNear(vRms, 0.5 / Math.sqrt(2.0), 0.015, "50Hz de-hum preserves 1kHz tone");
    }

    private static void testSoftNoiseGate() {
        int sr = 48000;
        int n = sr; // 1.0s
        DspConfig gateCfg = new DspConfig("Gate", true, 0f, 0, true, -35f,
                false, 0, new float[]{0, 0, 0, 0, 0},
                false, -18f, 3f, 10f, 120f, 0f, false, -1f, false);
        DspChain chain = new DspChain(sr, 1, gateCfg);

        // Quiet floor at -55 dBFS (~0.00178 linear) should attenuate heavily
        ByteBuffer quietBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            quietBuf.putFloat(i << 2, (float) (0.00178 * Math.sin(2.0 * Math.PI * 400.0 * i / sr)));
        }
        chain.processInPlace(quietBuf, n);
        float maxTail = 0f;
        for (int i = n * 3 / 4; i < n; i++) {
            maxTail = Math.max(maxTail, Math.abs(quietBuf.getFloat(i << 2)));
        }
        check(maxTail < 0.0002f, "Soft noise gate attenuates -55dB floor: tailPeak=" + maxTail);

        // Loud voice at -12 dBFS (~0.25 linear) should open gate cleanly
        ByteBuffer loudBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            loudBuf.putFloat(i << 2, (float) (0.25 * Math.sin(2.0 * Math.PI * 400.0 * i / sr)));
        }
        chain.processInPlace(loudBuf, n);
        float maxLoud = 0f;
        for (int i = n / 2; i < n; i++) {
            maxLoud = Math.max(maxLoud, Math.abs(loudBuf.getFloat(i << 2)));
        }
        checkNear(maxLoud, 0.25, 0.01, "Soft noise gate passes -12dB voice cleanly");
    }

    private static void testLufsMeterAndPeakCappedGain() {
        int sr = 48000;
        int frames = sr * 2; // 2 seconds
        ByteBuffer buf = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        // -20 dBFS peak (0.1 linear) 1kHz sine -> RMS ~ -23 dBFS -> BS.1770 LUFS around -23.7 LUFS
        for (int i = 0; i < frames; i++) {
            buf.putFloat(i << 2, (float) (0.1 * Math.sin(2.0 * Math.PI * 1000.0 * i / sr)));
        }
        DspChain.LufsMeter meter = new DspChain.LufsMeter(sr, 1);
        meter.feedInterleavedFloat(buf, frames);
        float lufs = meter.getIntegratedLufs();
        check(lufs > -26.0f && lufs < -21.0f, "BS.1770 1kHz -20dBFS sine LUFS in expected range: " + lufs);

        // Target -16 LUFS with low peak -> lifts toward -16 LUFS
        float liftGain = DspChain.computeLufsGain(lufs, -16.0f, 0.1f);
        check(liftGain > 1.8f && liftGain < 3.0f, "LUFS lift gain reasonable: " + liftGain);

        // Target -14 LUFS when peak is already 0.85 -> strictly capped at -1.0 dBFS (0.89125 / 0.85)
        float cappedGain = DspChain.computeLufsGain(-26.0f, -14.0f, 0.85f);
        checkNear(cappedGain * 0.85, 0.89125, 0.002, "LUFS gain capped at -1.0 dBFS true peak");
    }

    private static void testCosineFadeAndMarkerStore() {
        int frames = 1000;
        ByteBuffer buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) buf.putFloat(i << 2, 1.0f);
        AudioProcessor.applyCosineFade(buf, frames, 1, 0L, frames, 200L, 200L);
        checkNear(buf.getFloat(0), 0.0, 0.001, "Cosine fade-in starts at 0");
        checkNear(buf.getFloat(500 << 2), 1.0, 0.001, "Cosine fade middle untouched");
        checkNear(buf.getFloat((frames - 1) << 2), 0.0, 0.001, "Cosine fade-out ends at 0");

        List<MarkerStore.MarkerEntry> parsed = MarkerStore.parseEntries("15000:Chorus, 5000, 32000:Solo");
        check(parsed.size() == 3, "Parsed 3 mixed labeled/plain markers");
        check(parsed.get(0).timeMs == 5000L && parsed.get(0).label.isEmpty(), "Sorted marker 0");
        check(parsed.get(1).timeMs == 15000L && "Chorus".equals(parsed.get(1).label), "Sorted marker 1");
        String ser = MarkerStore.serializeEntries(parsed);
        check("5000,15000:Chorus,32000:Solo".equals(ser), "Marker serialization round-trip: " + ser);

        List<Long> ms = java.util.Arrays.asList(5000L, 15000L, 32000L);
        check(MarkerStore.findNextMarker(ms, 6000L) == 15000L, "Next marker from 6s");
        check(MarkerStore.findPrevMarker(ms, 20000L) == 15000L, "Prev marker from 20s");
        check("podcast_1".equals(AudioConfig.sanitizePrefix("  podcast_1!@#  ")), "Filename prefix sanitizer");
        check("rek".equals(AudioConfig.sanitizePrefix("   ")), "Blank prefix defaults to rek");
    }

    private static void testDspConfigV11SummaryAndDefaults() {
        DspConfig cfg = new DspConfig("Podcast", true, 3.0f, 60, true, -42f,
                true, 80, new float[]{0, 0, 1, 2, 1},
                true, -18f, 3f, 10f, 120f, 2f,
                true, -16f, true);
        String sum = cfg.activeModulesSummary();
        check(sum.contains("DEHUM 60Hz") && sum.contains("GATE -42dB") && sum.contains("-16 LUFS"),
                "v1.1 DSP summary includes new modules: " + sum);
        check(cfg.hasRealTimeFx() && cfg.hasNormalizer() && cfg.hasDeHum(), "v1.1 DSP flags");
    }
}
