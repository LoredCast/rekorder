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
import com.rekorder.app.storage.MarkerStore;
import com.rekorder.app.storage.RecordingStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Non-destructive offline audio processor for existing recordings:
 *  - Trim / Crop ([trimStartMs, trimEndMs]) OR Cut-Out Middle (remove [trimStartMs, trimEndMs])
 *  - Smooth cosine Fade-In / Fade-Out
 *  - Apply DSP preset (Preamp + De-Hum + Noise Gate + HPF + 5-Band EQ + Soft-Knee Compressor +
 *    2-Pass True Peak or BS.1770 LUFS Normalizer)
 *  - Split recording at bookmarks into separate chapter files
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
        return processFile(ctx, srcUri, srcFileName, srcMeta, trimStartMs, trimEndMs,
                false, 0, 0, null, dspConfig, outFormat, bitrateKbps, progress);
    }

    public static ProcessResult processFile(
            Context ctx,
            Uri srcUri,
            String srcFileName,
            WavWriter.AudioFileMeta srcMeta,
            long trimStartMs,
            long trimEndMs,
            boolean cutMiddle,
            int fadeInMs,
            int fadeOutMs,
            String customSuffix,
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
        if (cutMiddle && !isTrimmed) {
            throw new IOException("Select an inner range to cut out");
        }

        // Compute expected output duration & frames for accurate fade-out placement
        long outDurMs = cutMiddle
                ? Math.max(1L, startMs + (durMs - endMs))
                : Math.max(1L, endMs - startMs);
        long totalOutFrames = Math.max(1L, (outDurMs * srcMeta.sampleRate) / 1000L);
        long fadeInFrames = Math.min(totalOutFrames / 2L, (Math.max(0, fadeInMs) * (long) srcMeta.sampleRate) / 1000L);
        long fadeOutFrames = Math.min(totalOutFrames / 2L, (Math.max(0, fadeOutMs) * (long) srcMeta.sampleRate) / 1000L);

        // Build clean non-destructive output filename preserving original base name
        String base = srcFileName != null ? srcFileName : "recording";
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String tag;
        if (customSuffix != null && !customSuffix.isEmpty()) {
            tag = customSuffix;
        } else if (cutMiddle) {
            tag = "_cut";
        } else if (isTrimmed) {
            tag = "_trim";
        } else if (applyDsp || applyNorm || fadeInMs > 0 || fadeOutMs > 0) {
            tag = "_fx";
        } else {
            tag = "_conv";
        }

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
            if (applyNorm) {
                File sDir = RecordingStorage.getBestScratchDir(ctx);
                scratchFile = new File(sDir, "rek_proc_" + System.currentTimeMillis() + ".f32");
                final float[] maxPeakHolder = new float[]{0f};
                final DspChain.LufsMeter lufsMeter = dspConfig.normUseLufs
                        ? new DspChain.LufsMeter(srcMeta.sampleRate, srcMeta.channels) : null;
                final PcmConverter.MeterResult peakMeter = new PcmConverter.MeterResult();

                try (RandomAccessFile sraf = new RandomAccessFile(scratchFile, "rw")) {
                    FileChannel sch = sraf.getChannel();
                    streamEditPipeline(ctx, srcUri, srcMeta, startMs, endMs, durMs, cutMiddle,
                            totalOutFrames, fadeInFrames, fadeOutFrames, dspConfig,
                            (buf, frames, sr, ch) -> {
                                int bytes = frames * ch * 4;
                                PcmConverter.computeMeter(buf, bytes, PcmConverter.FMT_PCM_FLOAT, ch, peakMeter);
                                if (peakMeter.peak > maxPeakHolder[0]) maxPeakHolder[0] = peakMeter.peak;
                                if (lufsMeter != null) lufsMeter.feedInterleavedFloat(buf, frames);
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

                float gain;
                if (lufsMeter != null) {
                    float measuredLufs = lufsMeter.getIntegratedLufs();
                    gain = DspChain.computeLufsGain(measuredLufs, dspConfig.normTargetDb, maxPeakHolder[0]);
                } else {
                    gain = (maxPeakHolder[0] > 1e-6f)
                            ? DspChain.computeNormalizeGain(maxPeakHolder[0], dspConfig.normTargetDb)
                            : 1.0f;
                }

                target = RecordingStorage.createNamedTarget(ctx, outName, false);
                long outBytes;
                if (outFormat.isCompressed) {
                    PostNormalizer.normalizeAndEncodeCompressedByGain(
                            scratchFile, target.channel, outFormat,
                            srcMeta.sampleRate, srcMeta.channels, bitrateKbps,
                            gain,
                            (done, total, pct) -> {
                                if (progress != null) progress.onProgress(done, total, 50 + pct / 2);
                            });
                    outBytes = target.channel.size();
                } else {
                    outBytes = writeNormalizedScratchToPcmByGain(
                            scratchFile, target.channel, outFormat,
                            srcMeta.sampleRate, srcMeta.channels, gain,
                            (done, total, pct) -> {
                                if (progress != null) progress.onProgress(done, total, 50 + pct / 2);
                            });
                }
                target.close();
                ok = outBytes > 0L;
                String note = buildSummary(isTrimmed, cutMiddle, fadeInMs > 0 || fadeOutMs > 0,
                        applyDsp, true, dspConfig.normUseLufs, outFormat);
                return new ProcessResult(target.uri, target.displayName, outBytes, note);
            } else {
                // Single-pass direct stream
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
                    streamEditPipeline(ctx, srcUri, srcMeta, startMs, endMs, durMs, cutMiddle,
                            totalOutFrames, fadeInFrames, fadeOutFrames, dspConfig,
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
                            buildSummary(isTrimmed, cutMiddle, fadeInMs > 0 || fadeOutMs > 0,
                                    applyDsp, false, false, outFormat));
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

    /**
     * Splits a recording into separate non-destructive chapter files at every bookmark timestamp.
     */
    public static List<ProcessResult> splitAtMarkers(
            Context ctx,
            Uri srcUri,
            String srcFileName,
            WavWriter.AudioFileMeta srcMeta,
            List<MarkerStore.MarkerEntry> markers,
            int fadeInMs,
            int fadeOutMs,
            DspConfig dspConfig,
            AudioConfig.OutputFormat outFormat,
            int bitrateKbps,
            ProgressListener progress
    ) throws IOException {
        if (srcUri == null || srcMeta == null) throw new IOException("No recording loaded");
        long durMs = Math.max(1L, srcMeta.durationMs());
        List<Long> cuts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        cuts.add(0L);
        labels.add("");
        if (markers != null) {
            for (MarkerStore.MarkerEntry m : markers) {
                if (m.timeMs > 200L && m.timeMs < durMs - 200L) {
                    if (m.timeMs - cuts.get(cuts.size() - 1) >= 200L) {
                        cuts.add(m.timeMs);
                        labels.add(m.label);
                    }
                }
            }
        }
        cuts.add(durMs);
        int segCount = cuts.size() - 1;
        if (segCount < 2) {
            throw new IOException("Add at least 1 marker inside the recording to split");
        }

        List<ProcessResult> results = new ArrayList<>(segCount);
        for (int i = 0; i < segCount; i++) {
            final int segIdx = i;
            long sMs = cuts.get(i);
            long eMs = cuts.get(i + 1);
            String lbl = (i < labels.size()) ? labels.get(i) : "";
            String cleanLbl = lbl.replaceAll("[^A-Za-z0-9_-]", "");
            if (cleanLbl.length() > 16) cleanLbl = cleanLbl.substring(0, 16);
            String sfx = String.format(Locale.US, "_part%02d%s", i + 1, cleanLbl.isEmpty() ? "" : ("_" + cleanLbl));
            ProcessResult r = processFile(ctx, srcUri, srcFileName, srcMeta, sMs, eMs,
                    false, fadeInMs, fadeOutMs, sfx, dspConfig, outFormat, bitrateKbps,
                    (done, total, pct) -> {
                        if (progress != null) {
                            int overall = Math.min(99, (segIdx * 100 + pct) / segCount);
                            progress.onProgress(segIdx + 1, segCount, overall);
                        }
                    });
            results.add(r);
        }
        if (progress != null) progress.onProgress(segCount, segCount, 100);
        return results;
    }

    /** Public helper for unit-testing smooth cosine fade curves directly on float32 PCM. */
    public static void applyCosineFade(ByteBuffer floatBuf, int frames, int channels,
                                       long globalFrameStart, long totalFrames,
                                       long fadeInFrames, long fadeOutFrames) {
        if (fadeInFrames <= 0L && fadeOutFrames <= 0L) return;
        floatBuf.order(ByteOrder.LITTLE_ENDIAN);
        long fadeOutStart = Math.max(0L, totalFrames - fadeOutFrames);
        for (int f = 0; f < frames; f++) {
            long gf = globalFrameStart + f;
            float gain = 1.0f;
            if (fadeInFrames > 0L && gf < fadeInFrames) {
                double t = (double) gf / (double) fadeInFrames;
                gain *= (float) (0.5 * (1.0 - Math.cos(Math.PI * t)));
            }
            if (fadeOutFrames > 0L && gf >= fadeOutStart) {
                long rem = totalFrames - 1L - gf;
                double t = (rem <= 0L) ? 0.0 : ((double) rem / (double) fadeOutFrames);
                gain *= (float) (0.5 * (1.0 - Math.cos(Math.PI * Math.min(1.0, t))));
            }
            if (gain < 0.9999f) {
                int off = f * channels * 4;
                for (int c = 0; c < channels; c++) {
                    floatBuf.putFloat(off + (c << 2), floatBuf.getFloat(off + (c << 2)) * gain);
                }
            }
        }
    }

    private interface FloatSink {
        void accept(ByteBuffer floatBuf, int frames, int sr, int ch) throws IOException;
    }

    private static void streamEditPipeline(
            Context ctx, Uri srcUri, WavWriter.AudioFileMeta meta,
            long startMs, long endMs, long durMs, boolean cutMiddle,
            long totalOutFrames, long fadeInFrames, long fadeOutFrames,
            DspConfig dspConfig, FloatSink sink, ProgressListener progress) throws IOException {

        final long[] outCursor = new long[]{0L};
        FloatSink fadedSink = (buf, frames, sr, ch) -> {
            applyCosineFade(buf, frames, ch, outCursor[0], totalOutFrames, fadeInFrames, fadeOutFrames);
            outCursor[0] += frames;
            sink.accept(buf, frames, sr, ch);
        };

        boolean runDsp = dspConfig != null && dspConfig.hasRealTimeFx();
        DspChain dsp = runDsp ? new DspChain(meta.sampleRate, meta.channels, dspConfig) : null;

        if (!cutMiddle) {
            streamRange(ctx, srcUri, meta, startMs, endMs, dsp, fadedSink, progress);
            return;
        }

        // Cut-out middle: stream [0..startMs] with tiny 10ms clickless edge fade-out,
        // then [endMs..durMs] with tiny 10ms clickless edge fade-in.
        long spliceFrames = Math.max(1L, (meta.sampleRate * 10L) / 1000L);
        long part1Frames = (startMs * meta.sampleRate) / 1000L;
        long part2Frames = ((durMs - endMs) * meta.sampleRate) / 1000L;
        boolean hasPart1 = startMs > 10L;
        boolean hasPart2 = endMs < durMs - 10L;

        if (hasPart1) {
            final long[] p1Pos = new long[]{0L};
            long edgeOut = hasPart2 ? Math.min(spliceFrames, part1Frames / 2L) : 0L;
            streamRange(ctx, srcUri, meta, 0L, startMs, dsp,
                    (buf, frames, sr, ch) -> {
                        if (edgeOut > 0L) {
                            applyCosineFade(buf, frames, ch, p1Pos[0], part1Frames, 0L, edgeOut);
                        }
                        p1Pos[0] += frames;
                        fadedSink.accept(buf, frames, sr, ch);
                    },
                    (done, total, pct) -> {
                        if (progress != null) progress.onProgress(done, total, hasPart2 ? pct / 2 : pct);
                    });
        }
        if (hasPart2) {
            if (dsp != null) dsp.resetState();
            final long[] p2Pos = new long[]{0L};
            long edgeIn = hasPart1 ? Math.min(spliceFrames, part2Frames / 2L) : 0L;
            streamRange(ctx, srcUri, meta, endMs, durMs, dsp,
                    (buf, frames, sr, ch) -> {
                        if (edgeIn > 0L) {
                            applyCosineFade(buf, frames, ch, p2Pos[0], part2Frames, edgeIn, 0L);
                        }
                        p2Pos[0] += frames;
                        fadedSink.accept(buf, frames, sr, ch);
                    },
                    (done, total, pct) -> {
                        if (progress != null) progress.onProgress(done, total, hasPart1 ? (50 + pct / 2) : pct);
                    });
        }
    }

    private static void streamRange(
            Context ctx, Uri srcUri, WavWriter.AudioFileMeta meta,
            long startMs, long endMs, DspChain dsp,
            FloatSink sink, ProgressListener progress) throws IOException {
        if (!meta.isCompressed) {
            streamPcmSource(ctx, srcUri, meta, startMs, endMs, dsp, sink, progress);
        } else {
            streamCompressedSource(ctx, srcUri, meta, startMs, endMs, dsp, sink, progress);
        }
    }

    private static void streamPcmSource(
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

                    PcmConverter.convertToOutput(
                            rawBuf, frames * bpf, meta.pcmFormat, AudioConfig.OutputFormat.WAV_32F, floatBuf);
                    if (dsp != null) {
                        dsp.processInPlace(floatBuf, frames);
                    }

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
    }

    private static void streamCompressedSource(
            Context ctx, Uri srcUri, WavWriter.AudioFileMeta meta,
            long startMs, long endMs, DspChain dsp,
            FloatSink sink, ProgressListener progress) throws IOException {

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        ParcelFileDescriptor pfd = null;

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
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long lastNotify = 0L;
            if (progress != null) progress.onProgress(0L, wantFrames, 0);

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(5000);
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
                    MediaFormat outFmt = codec.getOutputFormat();
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sr = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        ch = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        int frameBytesI16 = ch * 2;
                        int totalOut = info.size / frameBytesI16;
                        long segStart = decodedFrames;
                        long segEnd = decodedFrames + totalOut;
                        decodedFrames = segEnd;

                        long keepStart = Math.max(segStart, startFrame);
                        long keepEnd = Math.min(segEnd, endFrame);
                        if (keepEnd > keepStart) {
                            int skipFrames = (int) (keepStart - segStart);
                            int keepFrames = (int) (keepEnd - keepStart);
                            int needFloatBytes = keepFrames * ch * 4;
                            if (floatBuf.capacity() < needFloatBytes) {
                                floatBuf = ByteBuffer.allocateDirect(needFloatBytes).order(ByteOrder.LITTLE_ENDIAN);
                            }
                            outBuf.order(ByteOrder.LITTLE_ENDIAN);
                            int baseByte = info.offset + skipFrames * frameBytesI16;
                            int totalSamples = keepFrames * ch;
                            for (int i = 0; i < totalSamples; i++) {
                                short s = outBuf.getShort(baseByte + (i << 1));
                                floatBuf.putFloat(i << 2, s / 32768.0f);
                            }
                            if (dsp != null) {
                                dsp.processInPlace(floatBuf, keepFrames);
                            }
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
    }

    private static long writeNormalizedScratchToPcmByGain(
            File scratchFile, FileChannel targetChannel, AudioConfig.OutputFormat outFormat,
            int sampleRate, int channels, float gain, ProgressListener progress)
            throws IOException {
        if (!Float.isFinite(gain) || gain <= 0f) gain = 1.0f;
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

    private static String buildSummary(boolean trimmed, boolean cutMiddle, boolean faded,
                                       boolean fx, boolean norm, boolean lufs,
                                       AudioConfig.OutputFormat fmt) {
        StringBuilder sb = new StringBuilder();
        if (cutMiddle) sb.append("Cut");
        else if (trimmed) sb.append("Trimmed");
        if (faded) { if (sb.length() > 0) sb.append(" + "); sb.append("Faded"); }
        if (fx) { if (sb.length() > 0) sb.append(" + "); sb.append("FX"); }
        if (norm) { if (sb.length() > 0) sb.append(" + "); sb.append(lufs ? "LUFS" : "Norm"); }
        if (sb.length() == 0) sb.append("Converted");
        sb.append(" -> ").append(fmt.shortLabel);
        return sb.toString();
    }
}
