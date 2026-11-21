// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import com.rekorder.app.dsp.DspChain;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Exact two-pass True Peak Normalizer:
 *  - For WAV (16/24/32f) and RAW (.pcm): fast in-place streaming pass directly on the FileChannel.
 *  - For OGG Opus and MP3: normalizes float32 capture scratch directly into single-pass encoder
 *    so there is ZERO double-lossy generation loss.
 */
public final class PostNormalizer {

    private PostNormalizer() {}

    /**
     * Applies exact uniform linear peak gain in-place on a WAV or RAW PCM FileChannel.
     * Returns applied gain in dB (0.0f if skipped).
     */
    public static float normalizePcmInPlace(FileChannel channel, AudioConfig.OutputFormat fmt,
                                            long dataOffset, long dataBytes,
                                            float maxPeakLinear, float targetDb) throws IOException {
        return normalizePcmInPlace(channel, fmt, dataOffset, dataBytes, maxPeakLinear, targetDb, null);
    }

    public static float normalizePcmInPlace(FileChannel channel, AudioConfig.OutputFormat fmt,
                                            long dataOffset, long dataBytes,
                                            float maxPeakLinear, float targetDb,
                                            ProgressListener progress) throws IOException {
        return normalizePcmInPlace(channel, channel, fmt, dataOffset, dataBytes, maxPeakLinear, targetDb, progress);
    }

    /**
     * Applies exact uniform linear peak gain in-place using separate read/write channels
     * (required on Android SAF where FileOutputStream's channel is write-only).
     */
    public static float normalizePcmInPlace(FileChannel readChannel, FileChannel writeChannel,
                                            AudioConfig.OutputFormat fmt,
                                            long dataOffset, long dataBytes,
                                            float maxPeakLinear, float targetDb,
                                            ProgressListener progress) throws IOException {
        if (dataBytes <= 0L || maxPeakLinear <= 1e-6f || !Float.isFinite(maxPeakLinear)) return 0f;
        float gain = DspChain.computeNormalizeGain(maxPeakLinear, targetDb);
        return normalizePcmInPlaceByGain(readChannel, writeChannel, fmt, dataOffset, dataBytes, gain, progress);
    }

    /** Applies an explicit linear gain factor in-place on a WAV or RAW PCM FileChannel (Peak or LUFS). */
    public static float normalizePcmInPlaceByGain(FileChannel readChannel, FileChannel writeChannel,
                                                  AudioConfig.OutputFormat fmt,
                                                  long dataOffset, long dataBytes,
                                                  float gain, ProgressListener progress) throws IOException {
        if (dataBytes <= 0L || !Float.isFinite(gain) || gain <= 0f) return 0f;
        if (Math.abs(gain - 1.0f) < 0.01f) return 0f;

        int bps = fmt.bytesPerSample;
        int chunkBytes = (262144 / bps) * bps; // 256 KB chunks for high flash throughput
        ByteBuffer buf = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);

        long pos = 0L;
        long lastNotify = 0L;
        if (progress != null) progress.onProgress(0L, dataBytes, 0);

        while (pos < dataBytes) {
            int toRead = (int) Math.min(chunkBytes, dataBytes - pos);
            toRead -= (toRead % bps);
            if (toRead <= 0) break;

            buf.clear();
            buf.limit(toRead);
            readChannel.position(dataOffset + pos);
            int got = 0;
            while (got < toRead) {
                int n = readChannel.read(buf);
                if (n < 0) break;
                got += n;
            }
            got -= (got % bps);
            if (got <= 0) break;

            scalePcmBufferInPlace(buf, got, fmt, gain);

            buf.limit(got);
            buf.position(0);
            writeChannel.position(dataOffset + pos);
            while (buf.hasRemaining()) {
                int w = writeChannel.write(buf);
                if (w <= 0) {
                    throw new IOException("Storage write failed during normalization");
                }
            }
            pos += got;

            if (progress != null) {
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 50L || pos >= dataBytes) {
                    lastNotify = now;
                    int pct = (int) Math.min(99L, (pos * 100L) / dataBytes);
                    progress.onProgress(pos, dataBytes, pct);
                }
            }
        }
        writeChannel.force(false);
        if (progress != null) progress.onProgress(dataBytes, dataBytes, 100);
        return (float) (20.0 * Math.log10(gain));
    }

    private static void scalePcmBufferInPlace(ByteBuffer buf, int byteLen,
                                              AudioConfig.OutputFormat fmt, float gain) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        if (buf.limit() < byteLen) {
            buf.limit(Math.min(byteLen, buf.capacity()));
        }
        if (fmt.isFloat) {
            int n = byteLen >> 2;
            for (int i = 0; i < n; i++) {
                float raw = buf.getFloat(i << 2);
                if (!Float.isFinite(raw)) raw = 0f;
                float s = raw * gain;
                if (s > 0.999f) s = 0.999f;
                else if (s < -0.999f) s = -0.999f;
                buf.putFloat(i << 2, s);
            }
        } else if (fmt.bitsPerSample == 24) {
            int n = byteLen / 3;
            for (int i = 0, off = 0; i < n; i++, off += 3) {
                int v = (buf.get(off + 2) << 16)
                        | ((buf.get(off + 1) & 0xFF) << 8)
                        | (buf.get(off) & 0xFF);
                double scaled = v * (double) gain;
                int nv = (int) Math.round(scaled);
                if (nv > 8388607) nv = 8388607;
                else if (nv < -8388608) nv = -8388608;
                buf.put(off, (byte) (nv & 0xFF));
                buf.put(off + 1, (byte) ((nv >>> 8) & 0xFF));
                buf.put(off + 2, (byte) ((nv >>> 16) & 0xFF));
            }
        } else { // 16-bit PCM
            int n = byteLen >> 1;
            for (int i = 0; i < n; i++) {
                short v = buf.getShort(i << 1);
                int nv = (int) Math.round(v * (double) gain);
                if (nv > 32767) nv = 32767;
                else if (nv < -32768) nv = -32768;
                buf.putShort(i << 1, (short) nv);
            }
        }
    }

    /**
     * Reads interleaved float32 samples from `scratchFile`, multiplies by exact peak normalization gain,
     * and encodes cleanly in a single pass into OGG Opus or MP3 on `targetChannel`.
     */
    public static float normalizeAndEncodeCompressed(
            File scratchFile, FileChannel targetChannel, AudioConfig.OutputFormat format,
            int sampleRate, int channels, int bitrateKbps,
            float maxPeakLinear, float targetDb) throws IOException {
        return normalizeAndEncodeCompressed(scratchFile, targetChannel, format,
                sampleRate, channels, bitrateKbps, maxPeakLinear, targetDb, null);
    }

    public static float normalizeAndEncodeCompressed(
            File scratchFile, FileChannel targetChannel, AudioConfig.OutputFormat format,
            int sampleRate, int channels, int bitrateKbps,
            float maxPeakLinear, float targetDb, ProgressListener progress) throws IOException {

        float gain = (maxPeakLinear > 1e-6f) ? DspChain.computeNormalizeGain(maxPeakLinear, targetDb) : 1.0f;
        return normalizeAndEncodeCompressedByGain(scratchFile, targetChannel, format,
                sampleRate, channels, bitrateKbps, gain, progress);
    }

    public static float normalizeAndEncodeCompressedByGain(
            File scratchFile, FileChannel targetChannel, AudioConfig.OutputFormat format,
            int sampleRate, int channels, int bitrateKbps,
            float gain, ProgressListener progress) throws IOException {

        if (!Float.isFinite(gain) || gain <= 0f) gain = 1.0f;

        int bpf = channels * 4;
        int chunkFrames = 4096;
        ByteBuffer buf = ByteBuffer.allocateDirect(chunkFrames * bpf).order(ByteOrder.LITTLE_ENDIAN);

        Mp3FileWriter mp3Writer = null;
        OggOpusWriter oggWriter = null;
        FlacFileWriter flacWriter = null;

        try (RandomAccessFile raf = new RandomAccessFile(scratchFile, "r")) {
            FileChannel src = raf.getChannel();
            long totalBytes = Math.max(1L, src.size());
            long doneBytes = 0L;
            long lastNotify = 0L;
            if (progress != null) progress.onProgress(0L, totalBytes, 0);

            if (format == AudioConfig.OutputFormat.MP3) {
                mp3Writer = new Mp3FileWriter(targetChannel, sampleRate, channels, bitrateKbps);
            } else if (format == AudioConfig.OutputFormat.FLAC) {
                flacWriter = new FlacFileWriter(targetChannel, sampleRate, channels);
            } else {
                oggWriter = new OggOpusWriter(targetChannel, sampleRate, channels, bitrateKbps);
            }

            while (true) {
                buf.clear();
                int got = 0;
                while (buf.hasRemaining()) {
                    int n = src.read(buf);
                    if (n < 0) break;
                    got += n;
                }
                int frames = got / bpf;
                if (frames <= 0) break;

                if (Math.abs(gain - 1.0f) >= 0.005f) {
                    int samples = frames * channels;
                    for (int i = 0; i < samples; i++) {
                        float raw = buf.getFloat(i << 2);
                        if (!Float.isFinite(raw)) raw = 0f;
                        float s = raw * gain;
                        if (s > 0.999f) s = 0.999f;
                        else if (s < -0.999f) s = -0.999f;
                        buf.putFloat(i << 2, s);
                    }
                }

                if (mp3Writer != null) {
                    mp3Writer.writeFloatFrames(buf, frames);
                } else if (flacWriter != null) {
                    flacWriter.writeFloatFrames(buf, frames);
                } else {
                    oggWriter.writeFloatFrames(buf, frames);
                }

                doneBytes += (long) frames * bpf;
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= 50L || doneBytes >= totalBytes) {
                        lastNotify = now;
                        int pct = (int) Math.min(99L, (doneBytes * 100L) / totalBytes);
                        progress.onProgress(doneBytes, totalBytes, pct);
                    }
                }
            }

            if (mp3Writer != null) mp3Writer.finalizeAndFlush();
            if (flacWriter != null) flacWriter.finalizeAndFlush();
            if (oggWriter != null) oggWriter.finalizeAndFlush();
            if (progress != null) progress.onProgress(totalBytes, totalBytes, 100);
        } finally {
            // Ensure encoder resources are released even if encoding throws mid-file
            if (mp3Writer != null) {
                try { mp3Writer.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
            if (flacWriter != null) {
                try { flacWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
            if (oggWriter != null) {
                try { oggWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
        }

        return (float) (20.0 * Math.log10(gain));
    }

    /**
     * Lossless Fail-Safe Rescue & Crash Recovery:
     * Converts an interleaved float32 capture scratch file into a standard 24-bit RIFF/WAVE file
     * (optionally applying True Peak Normalization) so zero captured audio is ever lost if a
     * compressed encoder fails or if the process was killed before finishing.
     */
    public static long convertScratchToLosslessWav(
            File scratchFile, FileChannel targetChannel,
            int sampleRate, int channels,
            float maxPeakLinear, boolean applyNormalize, float targetDb,
            ProgressListener progress) throws IOException {

        int safeCh = (channels == 2) ? 2 : 1;
        int safeRate = (sampleRate >= 8000 && sampleRate <= 192000) ? sampleRate : 48000;
        int bpf = safeCh * 4;

        float gain = 1.0f;
        if (applyNormalize && maxPeakLinear > 1e-6f && Float.isFinite(maxPeakLinear)) {
            gain = DspChain.computeNormalizeGain(maxPeakLinear, targetDb);
        }

        int chunkFrames = 8192;
        ByteBuffer fBuf = ByteBuffer.allocate(chunkFrames * bpf).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer i24Buf = ByteBuffer.allocate(chunkFrames * safeCh * 3).order(ByteOrder.LITTLE_ENDIAN);

        WavWriter wavWriter = new WavWriter(targetChannel, AudioConfig.OutputFormat.WAV_24, safeRate, safeCh);
        try (RandomAccessFile raf = new RandomAccessFile(scratchFile, "r")) {
            FileChannel src = raf.getChannel();
            long totalBytes = Math.max(1L, src.size());
            long doneBytes = 0L;
            long lastNotify = 0L;
            if (progress != null) progress.onProgress(0L, totalBytes, 0);

            while (true) {
                fBuf.clear();
                int got = 0;
                while (fBuf.hasRemaining()) {
                    int n = src.read(fBuf);
                    if (n < 0) break;
                    got += n;
                }
                int frames = got / bpf;
                if (frames <= 0) break;

                int samples = frames * safeCh;
                for (int i = 0; i < samples; i++) {
                    float raw = fBuf.getFloat(i << 2);
                    if (!Float.isFinite(raw)) raw = 0f;
                    float s = raw * gain;
                    if (s > 0.999f) s = 0.999f;
                    else if (s < -0.999f) s = -0.999f;
                    fBuf.putFloat(i << 2, s);
                }

                int outBytes = PcmConverter.convertToOutput(
                        fBuf, frames * bpf, PcmConverter.FMT_PCM_FLOAT, AudioConfig.OutputFormat.WAV_24, i24Buf);
                wavWriter.write(i24Buf, outBytes);

                doneBytes += (long) frames * bpf;
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= 50L || doneBytes >= totalBytes) {
                        lastNotify = now;
                        int pct = (int) Math.min(99L, (doneBytes * 100L) / totalBytes);
                        progress.onProgress(doneBytes, totalBytes, pct);
                    }
                }
            }
            wavWriter.finalizeAndFlush();
            if (progress != null) progress.onProgress(totalBytes, totalBytes, 100);
            return wavWriter.getTotalFileBytes();
        } finally {
            try { wavWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
        }
    }
}
