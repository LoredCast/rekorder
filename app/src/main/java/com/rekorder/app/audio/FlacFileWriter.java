// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Lossless FLAC (.flac) writer powered by Android's built-in MediaCodec ("audio/flac") encoder.
 * Zero third-party dependencies; cuts lossless storage roughly in half compared to WAV while
 * preserving exact PCM samples. Includes bounded stall detection for fail-safe rescue.
 */
public final class FlacFileWriter {

    private final FileChannel channel;
    private final int sampleRate;
    private final int channels;
    private final MediaCodec codec;
    private final ByteBuffer pcm16Buf;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();

    private long totalBytesWritten = 0L;
    private long totalInputFrames = 0L;
    private boolean finalized = false;

    public FlacFileWriter(FileChannel channel, int sampleRate, int channels) throws IOException {
        this.channel = channel;
        this.sampleRate = Math.max(8000, Math.min(192000, sampleRate));
        this.channels = (channels == 2) ? 2 : 1;

        MediaFormat fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_FLAC, this.sampleRate, this.channels);
        fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5);
        fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);

        MediaCodec enc;
        try {
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
        } catch (Exception e) {
            throw new IOException("Cannot initialize Android FLAC encoder: " + e.getMessage(), e);
        }
        this.codec = enc;
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

        interleavedFloat.order(ByteOrder.LITTLE_ENDIAN);
        int srcNeed = numFrames * channels * 4;
        if (interleavedFloat.limit() < srcNeed) {
            interleavedFloat.limit(Math.min(srcNeed, interleavedFloat.capacity()));
        }
        pcm16Buf.clear();
        int samples = numFrames * channels;
        for (int i = 0; i < samples; i++) {
            float s = interleavedFloat.getFloat(i << 2);
            if (!Float.isFinite(s)) s = 0f;
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
        int bpf = channels * 2;
        while (pos < totalBytes) {
            drainOutput(false);
            int inIdx = codec.dequeueInputBuffer(10000);
            if (inIdx < 0) {
                if (++stallRetries > 250) {
                    throw new IOException("FLAC encoder input stalled");
                }
                continue;
            }
            stallRetries = 0;
            ByteBuffer inBuf = codec.getInputBuffer(inIdx);
            if (inBuf == null) continue;
            inBuf.clear();
            int chunk = Math.min(inBuf.remaining(), totalBytes - pos);
            chunk -= (chunk % bpf);
            if (chunk <= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, (totalInputFrames * 1000000L) / sampleRate, 0);
                break;
            }
            pcm16Buf.limit(pos + chunk);
            pcm16Buf.position(pos);
            inBuf.put(pcm16Buf);

            long ptsUs = (totalInputFrames * 1000000L) / sampleRate;
            codec.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0);
            totalInputFrames += (chunk / bpf);
            pos += chunk;
        }
        drainOutput(false);
    }

    public long getTotalFileBytes() {
        return totalBytesWritten;
    }

    public void finalizeAndFlush() throws IOException {
        if (finalized) return;
        finalized = true;
        try {
            for (int tries = 0; tries < 50; tries++) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    long ptsUs = (totalInputFrames * 1000000L) / sampleRate;
                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drainOutput(false);
            }
            drainOutput(true);
            patchStreamInfoTotalSamples();
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
                continue;
            }
            if (outIdx < 0) continue;

            ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
            boolean isEos = (bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (outBuf != null && bufInfo.size > 0) {
                outBuf.limit(bufInfo.offset + bufInfo.size);
                outBuf.position(bufInfo.offset);
                while (outBuf.hasRemaining()) {
                    int w = channel.write(outBuf);
                    if (w <= 0) {
                        codec.releaseOutputBuffer(outIdx, false);
                        throw new IOException("Storage write failed during FLAC encoding");
                    }
                    totalBytesWritten += w;
                }
            }
            codec.releaseOutputBuffer(outIdx, false);
            if (isEos) break;
        }
    }

    /**
     * Patches the 36-bit total_samples field in the FLAC STREAMINFO block (bytes 21..25 of file:
     * low 4 bits of byte 21 + bytes 22..25) so all players immediately know exact duration & seek cleanly.
     */
    private void patchStreamInfoTotalSamples() {
        if (totalBytesWritten < 42L || totalInputFrames <= 0L) return;
        try {
            // Byte layout of fLaC file start:
            // [0..3]="fLaC", [4..7]=metadata header (block type 0 = STREAMINFO, length 34),
            // STREAMINFO [8..41]:
            //   [8..9]=min_blocksize, [10..11]=max_blocksize, [12..14]=min_framesize, [15..17]=max_framesize,
            //   [18..25]=20b sampleRate | 3b (ch-1) | 5b (bps-1) | 36b totalSamples!
            long sr20 = ((long) sampleRate) & 0xFFFFFL;
            long ch3 = ((long) (channels - 1)) & 0x7L;
            long bps5 = 15L; // 16 - 1
            long ts36 = totalInputFrames & 0xFFFFFFFFFL;
            long packed = (sr20 << 44) | (ch3 << 41) | (bps5 << 36) | ts36;

            ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            b.putLong(packed);
            b.flip();
            channel.position(18L);
            while (b.hasRemaining()) {
                channel.write(b);
            }
        } catch (IOException ignored) {
            // Best effort if channel is non-seekable
        }
    }
}
