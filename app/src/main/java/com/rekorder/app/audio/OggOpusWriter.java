// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;

import com.rekorder.app.dsp.AudioResampler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Encodes audio to standard RFC 7845 Ogg Opus (.ogg) using Android's built-in
 * MediaCodec ("audio/opus", Android 10+ / API 29+) paired with our pure-Java OggMuxer.
 */
public final class OggOpusWriter {

    private static final int OPUS_RATE = 48000;
    private static final int DEFAULT_PRESKIP = 312;

    private final FileChannel channel;
    private final int channels;
    private final int inputSampleRate;
    private final int bitrateKbps;
    private final String titleTag;
    private final AudioResampler resampler;
    private final MediaCodec codec;
    private final OggMuxer muxer;

    private final ByteBuffer resampledBuf;
    private final ByteBuffer pcm16Buf;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();

    private boolean headersWritten = false;
    private int preSkip = DEFAULT_PRESKIP;
    private long granulePos = 0L;
    private long totalInputFrames48k = 0L;
    private byte[] pendingPacket = null;
    private int pendingPacketLen = 0;
    private int pendingPacketSamples = 0;
    private boolean finalized = false;

    public OggOpusWriter(FileChannel channel, int inputSampleRate, int channels, int bitrateKbps)
            throws IOException {
        this(channel, inputSampleRate, channels, bitrateKbps, null);
    }

    public OggOpusWriter(FileChannel channel, int inputSampleRate, int channels, int bitrateKbps, String title)
            throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IOException("OGG Opus recording requires Android 10 (API 29) or newer");
        }
        this.channel = channel;
        this.channels = (channels == 2) ? 2 : 1;
        this.inputSampleRate = inputSampleRate;
        this.bitrateKbps = bitrateKbps;
        this.titleTag = title;
        this.resampler = new AudioResampler(inputSampleRate, OPUS_RATE, this.channels);
        this.muxer = new OggMuxer(channel, (int) (System.nanoTime() & 0x7FFFFFFFL));

        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, this.channels);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);

        MediaCodec enc;
        try {
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
        } catch (Exception e) {
            throw new IOException("Cannot initialize Android Opus encoder: " + e.getMessage(), e);
        }
        this.codec = enc;
        this.resampledBuf = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.LITTLE_ENDIAN);
        this.pcm16Buf = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
    }

    public void writeFloatFrames(ByteBuffer interleavedFloat, int numFrames) throws IOException {
        if (numFrames <= 0 || finalized) return;

        int maxChunk = 4096;
        if (numFrames > maxChunk) {
            int bpf = channels * 4;
            int done = 0;
            ByteBuffer sliceBuf = ByteBuffer.allocate(maxChunk * bpf).order(ByteOrder.LITTLE_ENDIAN);
            interleavedFloat.order(ByteOrder.LITTLE_ENDIAN);
            int totalBytes = numFrames * bpf;
            if (interleavedFloat.limit() < totalBytes) {
                interleavedFloat.limit(Math.min(totalBytes, interleavedFloat.capacity()));
            }
            while (done < numFrames) {
                int step = Math.min(maxChunk, numFrames - done);
                int stepBytes = step * bpf;
                sliceBuf.clear();
                interleavedFloat.limit(done * bpf + stepBytes);
                interleavedFloat.position(done * bpf);
                sliceBuf.put(interleavedFloat);
                writeFloatFrames(sliceBuf, step);
                done += step;
            }
            interleavedFloat.clear();
            return;
        }

        ByteBuffer srcFloat;
        int frames48k;
        if (resampler.isPassthrough()) {
            srcFloat = interleavedFloat;
            frames48k = numFrames;
        } else {
            frames48k = resampler.process(interleavedFloat, numFrames, resampledBuf);
            srcFloat = resampledBuf;
        }
        if (frames48k <= 0) return;

        int chunkFrames = pcm16Buf.capacity() / (channels * 2);
        int done = 0;
        while (done < frames48k) {
            int step = Math.min(chunkFrames, frames48k - done);
            feedFloatSlice(srcFloat, done, step);
            done += step;
        }
    }

    private void feedFloatSlice(ByteBuffer srcFloat, int frameOffset, int frameCount) throws IOException {
        srcFloat.order(ByteOrder.LITTLE_ENDIAN);
        int srcNeed = (frameOffset + frameCount) * channels * 4;
        if (srcFloat.limit() < srcNeed) {
            srcFloat.limit(Math.min(srcNeed, srcFloat.capacity()));
        }
        pcm16Buf.clear(); // CRITICAL: reset limit=capacity before indexed putShort()!

        int samples = frameCount * channels;
        int srcByteStart = frameOffset * channels * 4;
        for (int i = 0; i < samples; i++) {
            float s = srcFloat.getFloat(srcByteStart + (i << 2));
            if (s > 1.0f) s = 1.0f;
            else if (s < -1.0f) s = -1.0f;
            int v = Math.round(s * 32767.0f);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            pcm16Buf.putShort(i << 1, (short) v);
        }

        int totalBytes = samples * 2;
        int pos = 0;
        int stallRetries = 0;
        while (pos < totalBytes) {
            drainOutput(false);
            int inIdx = codec.dequeueInputBuffer(10000);
            if (inIdx < 0) {
                if (++stallRetries > 250) {
                    throw new IOException("Opus encoder input stalled");
                }
                continue;
            }
            stallRetries = 0;
            ByteBuffer inBuf = codec.getInputBuffer(inIdx);
            if (inBuf == null) continue;
            inBuf.clear();
            int chunk = Math.min(inBuf.remaining(), totalBytes - pos);
            int bpf = channels * 2;
            chunk -= (chunk % bpf);
            if (chunk <= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, (totalInputFrames48k * 1000000L) / OPUS_RATE, 0);
                break;
            }

            pcm16Buf.limit(pos + chunk);
            pcm16Buf.position(pos);
            inBuf.put(pcm16Buf);

            long ptsUs = (totalInputFrames48k * 1000000L) / OPUS_RATE;
            codec.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0);
            totalInputFrames48k += (chunk / bpf);
            pos += chunk;
        }
        drainOutput(false);
    }

    public long getTotalFileBytes() {
        return muxer.getTotalBytesWritten();
    }

    public void finalizeAndFlush() throws IOException {
        if (finalized) return;
        finalized = true;
        try {
            // Signal End-Of-Stream to MediaCodec
            for (int tries = 0; tries < 50; tries++) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    long ptsUs = (totalInputFrames48k * 1000000L) / OPUS_RATE;
                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drainOutput(false);
            }
            drainOutput(true);
            flushPendingPacket(true);
            channel.force(false);
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }
    }

    private void drainOutput(boolean waitForEos) throws IOException {
        int spins = 0;
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(bufInfo, waitForEos ? 20000 : 0);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEos || ++spins > 40) break;
                continue;
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFmt = codec.getOutputFormat();
                if (!headersWritten && outFmt != null) {
                    ByteBuffer csd0 = outFmt.getByteBuffer("csd-0");
                    if (csd0 != null) {
                        parseCsdPreSkip(csd0);
                    }
                }
                continue;
            }
            if (outIdx < 0) continue;

            ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
            boolean isConfig = (bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean isEos = (bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

            if (outBuf != null && bufInfo.size > 0) {
                outBuf.limit(bufInfo.offset + bufInfo.size);
                outBuf.position(bufInfo.offset);
                if (isConfig) {
                    parseCsdPreSkip(outBuf);
                } else {
                    ensureHeadersWritten();
                    byte[] pkt = new byte[bufInfo.size];
                    outBuf.get(pkt);
                    int samples = OggMuxer.opusPacketSamples48k(pkt, 0, pkt.length);
                    // Buffer 1 packet so we can cleanly stamp FLAG_EOS on the true final audio page!
                    flushPendingPacket(false);
                    pendingPacket = pkt;
                    pendingPacketLen = pkt.length;
                    pendingPacketSamples = samples;
                }
            }

            codec.releaseOutputBuffer(outIdx, false);
            if (isEos) break;
        }
    }

    private void ensureHeadersWritten() throws IOException {
        if (headersWritten) return;
        headersWritten = true;
        byte[] head = OggMuxer.buildOpusHead(channels, preSkip, inputSampleRate);
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        byte[] tags = OggMuxer.buildOpusTags("Rekorder",
                "TITLE=" + ((titleTag != null && !titleTag.isEmpty()) ? titleTag : "Rekorder Recording"),
                "ARTIST=Rekorder",
                "DATE=" + date,
                "COMMENT=" + inputSampleRate + "Hz " + bitrateKbps + "kbps");
        muxer.writePacket(head, 0, head.length, 0L, OggMuxer.FLAG_BOS);
        muxer.writePacket(tags, 0, tags.length, 0L, 0);
    }

    private void flushPendingPacket(boolean eos) throws IOException {
        if (pendingPacket == null) {
            if (eos) {
                ensureHeadersWritten();
                muxer.writePacket(new byte[0], 0, 0, granulePos, OggMuxer.FLAG_EOS);
            }
            return;
        }
        long prev = granulePos;
        granulePos += pendingPacketSamples;
        long gp = eos ? Math.max(prev + 1L, Math.min(granulePos, totalInputFrames48k + preSkip)) : granulePos;
        muxer.writePacket(pendingPacket, 0, pendingPacketLen, gp, eos ? OggMuxer.FLAG_EOS : 0);
        pendingPacket = null;
        pendingPacketLen = 0;
        pendingPacketSamples = 0;
    }

    /** Extracts exact pre-skip if Android MediaCodec provides an OpusHead CSD-0 buffer. */
    private void parseCsdPreSkip(ByteBuffer csd) {
        int savedPos = csd.position();
        int rem = csd.remaining();
        if (rem >= 19) {
            byte[] hdr = new byte[19];
            csd.get(hdr);
            if (hdr[0] == 'O' && hdr[1] == 'p' && hdr[2] == 'u' && hdr[3] == 's'
                    && hdr[4] == 'H' && hdr[5] == 'e' && hdr[6] == 'a' && hdr[7] == 'd') {
                int ps = (hdr[10] & 0xFF) | ((hdr[11] & 0xFF) << 8);
                if (ps >= 0 && ps < 48000) {
                    preSkip = ps;
                }
            }
        }
        csd.position(savedPos);
    }
}
