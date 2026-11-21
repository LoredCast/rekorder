// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.rekorder.app.dsp.DspChain;
import com.rekorder.app.dsp.DspConfig;
import com.rekorder.app.storage.RecordingStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * Non-destructive offline audio processor for existing recordings:
 *  - Trim / Crop ([trimStartMs, trimEndMs])
 *  - Apply DSP preset (Input Gain + HPF + 5-Band EQ + Soft-Knee Compressor + 2-Pass Normalizer)
 *  - Convert / Export to WAV (16/24/32f), RAW, FLAC, OGG Opus, or MP3
 *
 * Always writes to a new file so original takes are never modified or lost.
 */
public final class AudioProcessor {

    public static final class ProcessResult {
        public final Uri uri;
        public final String displayName;
        public final long sizeBytes;
        public final String summary;

        public ProcessResult(Uri uri, String displayName, long sizeBytes, String summary) {
            this.uri = uri;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
            this.summary = summary;
        }
    }

    private AudioProcessor() {}

    public static ProcessResult processFile(
            Context ctx,
            Uri srcUri,
            String srcFileName,
            WavWriter.AudioFileMeta srcMeta,
            long trimStartMs,
            long trimEndMs,
            DspConfig dspConfig,
            AudioConfig.OutputFormat outFormat,
            int bitrateKbps,
            ProgressListener progress
    ) throws IOException {
        if (srcUri == null || srcMeta == null) {
            throw new IOException("No recording loaded");
        }
        long durMs = Math.max(1L, srcMeta.durationMs());
        long startMs = Math.max(0L, Math.min(trimStartMs, durMs));
        long endMs = (trimEndMs > startMs && trimEndMs <= durMs) ? trimEndMs : durMs;
        if (endMs <= startMs) {
            throw new IOException("Invalid trim selection");
        }

        boolean applyDsp = dspConfig != null && dspConfig.hasRealTimeFx();
        boolean applyNorm = dspConfig != null && dspConfig.hasNormalizer();
        boolean isTrimmed = (startMs > 10L || endMs < durMs - 10L);

        // Build clean non-destructive output filename preserving original base name
        String base = srcFileName != null ? srcFileName : "recording";
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String tag = isTrimmed ? "_trim" : (applyDsp || applyNorm ? "_fx" : "_conv");
        String outName;
        if (outFormat.hasWavHeader || outFormat.isCompressed) {
            outName = base + tag + "." + outFormat.extension;
        } else {
            outName = base + tag + "_" + srcMeta.sampleRate + "Hz_" + outFormat.rawTag() + "_" + srcMeta.channels + "ch.pcm";
        }

        File scratchFile = null;
        RecordingStorage.RecordingTarget target = null;
        boolean ok = false;

        try {
            // If normalizing, pass 1 decodes+trims+applies DSP to a float32 scratch while measuring true peak,
            // then pass 2 normalizes & encodes into target in a single clean pass (zero double-loss!).
            if (applyNorm) {
                File sDir = RecordingStorage.getBestScratchDir(ctx);
                scratchFile = new File(sDir, "rek_proc_" + System.currentTimeMillis() + ".f32");
                float maxPeak;
                try (RandomAccessFile sraf = new RandomAccessFile(scratchFile, "rw")) {
                    FileChannel sch = sraf.getChannel();
                    maxPeak = streamDecodeAndDsp(ctx, srcUri, srcMeta, startMs, endMs, dspConfig,
                            (buf, frames, sr, ch) -> {
                                int bytes = frames * ch * 4;
                                buf.limit(bytes);
                                buf.position(0);
                                while (buf.hasRemaining()) {
                                    int w = sch.write(buf);
                                    if (w <= 0) throw new IOException("Scratch write failed (disk full)");
                                }
                            },
                            (done, total, pct) -> {
                                if (progress != null) progress.onProgress(done, total, pct / 2);
                            });
                    sch.force(false);
                }

                target = RecordingStorage.createNamedTarget(ctx, outName, false);
                long outBytes;
                if (outFormat.isCompressed) {
                    PostNormalizer.normalizeAndEncodeCompressed(
                            scratchFile, target.channel, outFormat,
                            srcMeta.sampleRate, srcMeta.channels, bitrateKbps,
                            maxPeak, dspConfig.normTargetDb,
                            (done, total, pct) -> {
                                if (progress != null) progress.onProgress(done, total, 50 + pct / 2);
                            });
                    outBytes = target.channel.size();
                } else {
                    outBytes = writeNormalizedScratchToPcm(
                            scratchFile, target.channel, outFormat,
                            srcMeta.sampleRate, srcMeta.channels,
                            maxPeak, dspConfig.normTargetDb,
                            (done, total, pct) -> {
                                if (progress != null) progress.onProgress(done, total, 50 + pct / 2);
                            });
                }
                target.close();
                ok = outBytes > 0L;
                String note = buildSummary(isTrimmed, applyDsp, true, outFormat);
                return new ProcessResult(target.uri, target.displayName, outBytes, note);
            } else {
                // Single-pass direct stream (super fast!)
                target = RecordingStorage.createNamedTarget(ctx, outName, false);
                final WavWriter[] wavHolder = new WavWriter[1];
                final Mp3FileWriter[] mp3Holder = new Mp3FileWriter[1];
                final OggOpusWriter[] oggHolder = new OggOpusWriter[1];
                final FlacFileWriter[] flacHolder = new FlacFileWriter[1];
                final ByteBuffer[] convBufHolder = new ByteBuffer[1];

                if (outFormat == AudioConfig.OutputFormat.MP3) {
                    mp3Holder[0] = new Mp3FileWriter(target.channel, srcMeta.sampleRate, srcMeta.channels,
                            bitrateKbps, target.displayName, null);
                } else if (outFormat == AudioConfig.OutputFormat.OGG_OPUS) {
                    oggHolder[0] = new OggOpusWriter(target.channel, srcMeta.sampleRate, srcMeta.channels,
                            bitrateKbps, target.displayName);
                } else if (outFormat == AudioConfig.OutputFormat.FLAC) {
                    flacHolder[0] = new FlacFileWriter(target.channel, srcMeta.sampleRate, srcMeta.channels);
                } else {
                    wavHolder[0] = new WavWriter(target.channel, outFormat, srcMeta.sampleRate, srcMeta.channels);
                    convBufHolder[0] = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.LITTLE_ENDIAN);
                }

                try {
                    streamDecodeAndDsp(ctx, srcUri, srcMeta, startMs, endMs, dspConfig,
                            (buf, frames, sr, ch) -> {
                                if (mp3Holder[0] != null) {
                                    mp3Holder[0].writeFloatFrames(buf, frames);
                                } else if (oggHolder[0] != null) {
                                    oggHolder[0].writeFloatFrames(buf, frames);
                                } else if (flacHolder[0] != null) {
                                    flacHolder[0].writeFloatFrames(buf, frames);
                                } else if (wavHolder[0] != null) {
                                    int fBytes = frames * ch * 4;
                                    int ob = PcmConverter.convertToOutput(
                                            buf, fBytes, PcmConverter.FMT_PCM_FLOAT, outFormat, convBufHolder[0]);
                                    wavHolder[0].write(convBufHolder[0], ob);
                                }
                            },
                            progress);

                    long outBytes;
                    if (mp3Holder[0] != null) {
                        mp3Holder[0].finalizeAndFlush();
                        outBytes = mp3Holder[0].getTotalFileBytes();
                    } else if (oggHolder[0] != null) {
                        oggHolder[0].finalizeAndFlush();
                        outBytes = oggHolder[0].getTotalFileBytes();
                    } else if (flacHolder[0] != null) {
                        flacHolder[0].finalizeAndFlush();
                        outBytes = flacHolder[0].getTotalFileBytes();
                    } else {
                        wavHolder[0].finalizeAndFlush();
                        outBytes = wavHolder[0].getTotalFileBytes();
                    }
                    target.close();
                    ok = outBytes > 0L;
                    if (progress != null) progress.onProgress(outBytes, outBytes, 100);
                    return new ProcessResult(target.uri, target.displayName, outBytes,
                            buildSummary(isTrimmed, applyDsp, false, outFormat));
                } finally {
                    if (mp3Holder[0] != null) try { mp3Holder[0].finalizeAndFlush(); } catch (Throwable ignored) {}
                    if (oggHolder[0] != null) try { oggHolder[0].finalizeAndFlush(); } catch (Throwable ignored) {}
                    if (flacHolder[0] != null) try { flacHolder[0].finalizeAndFlush(); } catch (Throwable ignored) {}
                    if (wavHolder[0] != null) try { wavHolder[0].finalizeAndFlush(); } catch (Throwable ignored) {}
                }
            }
        } finally {
            if (scratchFile != null && scratchFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                scratchFile.delete();
            }
            if (!ok && target != null) {
                target.deleteSilently(ctx);
            }
        }
    }

    private interface FloatSink {
        void accept(ByteBuffer floatBuf, int frames, int sr, int ch) throws IOException;
    }

    private static float streamDecodeAndDsp(
            Context ctx, Uri srcUri, WavWriter.AudioFileMeta meta,
            long startMs, long endMs, DspConfig dspConfig,
            FloatSink sink, ProgressListener progress) throws IOException {

        boolean runDsp = dspConfig != null && dspConfig.hasRealTimeFx();
        DspChain dsp = runDsp ? new DspChain(meta.sampleRate, meta.channels, dspConfig) : null;

        if (!meta.isCompressed) {
            return streamPcmSource(ctx, srcUri, meta, startMs, endMs, dsp, sink, progress);
        }
        return streamCompressedSource(ctx, srcUri, meta, startMs, endMs, dsp, sink, progress);
    }

    private static float streamPcmSource(
            Context ctx, Uri srcUri, WavWriter.AudioFileMeta meta,
            long startMs, long endMs, DspChain dsp,
            FloatSink sink, ProgressListener progress) throws IOException {

        int bpf = meta.bytesPerFrame();
        long totalFrames = meta.dataBytes / Math.max(1, bpf);
        long startFrame = Math.min((startMs * meta.sampleRate) / 1000L, totalFrames);
        long endFrame = Math.min((endMs * meta.sampleRate) / 1000L, totalFrames);
        long wantFrames = Math.max(0L, endFrame - startFrame);

        int chunkFrames = 4096;
        ByteBuffer rawBuf = ByteBuffer.allocate(chunkFrames * bpf).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer floatBuf = ByteBuffer.allocateDirect(chunkFrames * meta.channels * 4).order(ByteOrder.LITTLE_ENDIAN);
        PcmConverter.MeterResult meter = new PcmConverter.MeterResult();
        float maxPeak = 0f;

        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(srcUri, "r")) {
            if (pfd == null) throw new IOException("Cannot open source file");
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel fc = fis.getChannel();
                fc.position(meta.dataOffset + startFrame * bpf);
                long doneFrames = 0L;
                long lastNotify = 0L;
                long totalBytes = Math.max(1L, wantFrames * bpf);
                if (progress != null) progress.onProgress(0L, totalBytes, 0);

                while (doneFrames < wantFrames) {
                    int step = (int) Math.min(chunkFrames, wantFrames - doneFrames);
                    int need = step * bpf;
                    rawBuf.clear();
                    rawBuf.limit(need);
                    int got = 0;
                    while (got < need) {
                        int n = fc.read(rawBuf);
                        if (n < 0) break;
                        got += n;
                    }
                    int frames = got / bpf;
                    if (frames <= 0) break;

                    int fb = PcmConverter.convertToOutput(
                            rawBuf, frames * bpf, meta.pcmFormat, AudioConfig.OutputFormat.WAV_32F, floatBuf);
                    if (dsp != null) {
                        dsp.processInPlace(floatBuf, frames);
                    }
                    PcmConverter.computeMeter(floatBuf, fb, PcmConverter.FMT_PCM_FLOAT, meta.channels, meter);
                    if (meter.peak > maxPeak) maxPeak = meter.peak;

                    sink.accept(floatBuf, frames, meta.sampleRate, meta.channels);
                    doneFrames += frames;

                    if (progress != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotify >= 50L || doneFrames >= wantFrames) {
                            lastNotify = now;
                            int pct = (int) Math.min(99L, (doneFrames * 100L) / Math.max(1L, wantFrames));
                            progress.onProgress(doneFrames * bpf, totalBytes, pct);
                        }
                    }
                }
                if (progress != null) progress.onProgress(totalBytes, totalBytes, 100);
            }
        }
        return maxPeak;
    }

    private static float streamCompressedSource(
            Context ctx, Uri srcUri, WavWriter.AudioFileMeta meta,
            long startMs, long endMs, DspChain dsp,
            FloatSink sink, ProgressListener progress) throws IOException {

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        ParcelFileDescriptor pfd = null;
        float maxPeak = 0f;

        try {
            pfd = ctx.getContentResolver().openFileDescriptor(srcUri, "r");
            if (pfd == null) throw new IOException("Cannot open source file");
            extractor.setDataSource(pfd.getFileDescriptor());

            int trackIdx = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i;
                    format = f;
                    break;
                }
            }
            if (trackIdx < 0 || format == null) throw new IOException("No audio track found");
            extractor.selectTrack(trackIdx);

            String mime = format.getString(MediaFormat.KEY_MIME);
            int sr = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : meta.sampleRate;
            int ch = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : meta.channels;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            long startFrame = (startMs * sr) / 1000L;
            long endFrame = (endMs * sr) / 1000L;
            long wantFrames = Math.max(1L, endFrame - startFrame);
            long decodedFrames = 0L;

            ByteBuffer floatBuf = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.LITTLE_ENDIAN);
            PcmConverter.MeterResult meter = new PcmConverter.MeterResult();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastNotify = 0L;
            if (progress != null) progress.onProgress(0L, wantFrames, 0);

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            int n = extractor.readSampleData(inBuf, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN);
                        outBuf.limit(info.offset + info.size);
                        outBuf.position(info.offset);
                        int bpf = ch * 2;
                        int frames = info.size / Math.max(1, bpf);

                        long chunkStart = decodedFrames;
                        long chunkEnd = decodedFrames + frames;
                        decodedFrames = chunkEnd;

                        long keepStart = Math.max(chunkStart, startFrame);
                        long keepEnd = Math.min(chunkEnd, endFrame);
                        if (keepEnd > keepStart) {
                            int skipInChunk = (int) (keepStart - chunkStart);
                            int keepFrames = (int) (keepEnd - keepStart);
                            int needFloatBytes = keepFrames * ch * 4;
                            if (floatBuf.capacity() < needFloatBytes) {
                                floatBuf = ByteBuffer.allocateDirect(needFloatBytes).order(ByteOrder.LITTLE_ENDIAN);
                            }
                            floatBuf.clear();
                            int samples = keepFrames * ch;
                            int srcOff = info.offset + skipInChunk * bpf;
                            for (int i = 0; i < samples; i++) {
                                floatBuf.putFloat(i << 2, outBuf.getShort(srcOff + (i << 1)) * (1.0f / 32768.0f));
                            }
                            if (dsp != null) {
                                dsp.processInPlace(floatBuf, keepFrames);
                            }
                            PcmConverter.computeMeter(floatBuf, needFloatBytes, PcmConverter.FMT_PCM_FLOAT, ch, meter);
                            if (meter.peak > maxPeak) maxPeak = meter.peak;
                            sink.accept(floatBuf, keepFrames, sr, ch);
                        }

                        if (progress != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastNotify >= 50L) {
                                lastNotify = now;
                                long progressed = Math.max(0L, Math.min(wantFrames, decodedFrames - startFrame));
                                int pct = (int) Math.min(99L, (progressed * 100L) / wantFrames);
                                progress.onProgress(progressed, wantFrames, pct);
                            }
                        }
                        if (decodedFrames >= endFrame) {
                            outputDone = true;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (progress != null) progress.onProgress(wantFrames, wantFrames, 100);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            if (pfd != null) {
                try { pfd.close(); } catch (IOException ignored) {}
            }
        }
        return maxPeak;
    }

    private static long writeNormalizedScratchToPcm(
            File scratchFile, FileChannel targetChannel, AudioConfig.OutputFormat outFormat,
            int sampleRate, int channels, float maxPeak, float targetDb, ProgressListener progress)
            throws IOException {
        float gain = (maxPeak > 1e-6f) ? DspChain.computeNormalizeGain(maxPeak, targetDb) : 1.0f;
        int bpf = channels * 4;
        int chunkFrames = 8192;
        ByteBuffer fBuf = ByteBuffer.allocate(chunkFrames * bpf).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outBuf = ByteBuffer.allocate(chunkFrames * channels * outFormat.bytesPerSample)
                .order(ByteOrder.LITTLE_ENDIAN);

        WavWriter w = new WavWriter(targetChannel, outFormat, sampleRate, channels);
        try (RandomAccessFile raf = new RandomAccessFile(scratchFile, "r")) {
            FileChannel src = raf.getChannel();
            long total = Math.max(1L, src.size());
            long done = 0L;
            long lastNotify = 0L;
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
                int samples = frames * channels;
                for (int i = 0; i < samples; i++) {
                    float s = fBuf.getFloat(i << 2) * gain;
                    if (!Float.isFinite(s)) s = 0f;
                    if (s > 0.999f) s = 0.999f;
                    else if (s < -0.999f) s = -0.999f;
                    fBuf.putFloat(i << 2, s);
                }
                int ob = PcmConverter.convertToOutput(fBuf, frames * bpf, PcmConverter.FMT_PCM_FLOAT, outFormat, outBuf);
                w.write(outBuf, ob);
                done += (long) frames * bpf;
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= 50L || done >= total) {
                        lastNotify = now;
                        int pct = (int) Math.min(99L, (done * 100L) / total);
                        progress.onProgress(done, total, pct);
                    }
                }
            }
            w.finalizeAndFlush();
            if (progress != null) progress.onProgress(total, total, 100);
            return w.getTotalFileBytes();
        } finally {
            try { w.finalizeAndFlush(); } catch (Throwable ignored) {}
        }
    }

    private static String buildSummary(boolean trimmed, boolean fx, boolean norm, AudioConfig.OutputFormat fmt) {
        StringBuilder sb = new StringBuilder();
        if (trimmed) sb.append("Trimmed");
        if (fx) { if (sb.length() > 0) sb.append(" + "); sb.append("FX"); }
        if (norm) { if (sb.length() > 0) sb.append(" + "); sb.append("Normalized"); }
        if (sb.length() == 0) sb.append("Converted");
        sb.append(" -> ").append(fmt.shortLabel);
        return sb.toString();
    }
}
