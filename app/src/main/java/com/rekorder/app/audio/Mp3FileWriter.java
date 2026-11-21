// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import com.rekorder.app.dsp.AudioResampler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Streams interleaved float32 PCM into our built-in native C MP3 encoder (librekorder_mp3.so)
 * and writes standard ID3v2.3 metadata + CBR MPEG-1 Layer III frames directly to any FileChannel.
 */
public final class Mp3FileWriter {

    private final FileChannel channel;
    private final int channels;
    private final AudioResampler resampler;
    private final NativeMp3Encoder encoder;
    private final ByteBuffer resampledBuf;
    private final ByteBuffer mp3OutBuf;

    private long totalBytesWritten = 0L;
    private boolean finalized = false;

    public Mp3FileWriter(FileChannel channel, int inputSampleRate, int channels, int bitrateKbps) throws IOException {
        this(channel, inputSampleRate, channels, bitrateKbps, null, null);
    }

    public Mp3FileWriter(FileChannel channel, int inputSampleRate, int channels, int bitrateKbps,
                         String title, String comment) throws IOException {
        this.channel = channel;
        this.channels = (channels == 2) ? 2 : 1;
        int mp3Rate = resolveMp3Rate(inputSampleRate);
        this.resampler = new AudioResampler(inputSampleRate, mp3Rate, this.channels);
        try {
            this.encoder = new NativeMp3Encoder(mp3Rate, this.channels, bitrateKbps);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
        this.resampledBuf = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.LITTLE_ENDIAN);
        this.mp3OutBuf = ByteBuffer.allocateDirect(65536);

        // Write clean standard ID3v2.3 metadata tag header before first MPEG frame
        byte[] id3 = buildId3v23Tag(
                (title != null && !title.isEmpty()) ? title : "Rekorder Recording",
                "Rekorder",
                new SimpleDateFormat("yyyy", Locale.US).format(new Date()),
                (comment != null && !comment.isEmpty()) ? comment
                        : (mp3Rate + " Hz · " + bitrateKbps + " kbps · " + (this.channels == 2 ? "Stereo" : "Mono")));
        ByteBuffer tagBuf = ByteBuffer.wrap(id3);
        while (tagBuf.hasRemaining()) {
            int w = channel.write(tagBuf);
            if (w <= 0) throw new IOException("Storage write failed writing ID3 header");
            totalBytesWritten += w;
        }
    }

    /** Builds a minimal, standard ISO-8859-1 ID3v2.3 tag (TIT2, TPE1, TYER, COMM). */
    public static byte[] buildId3v23Tag(String title, String artist, String year, String comment) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream(160);
        writeTextFrame(frames, "TIT2", title);
        writeTextFrame(frames, "TPE1", artist);
        writeTextFrame(frames, "TYER", year);
        writeCommentFrame(frames, comment);
        byte[] body = frames.toByteArray();
        int len = body.length;
        byte[] out = new byte[10 + len];
        out[0] = 'I'; out[1] = 'D'; out[2] = '3';
        out[3] = 3;   out[4] = 0;   out[5] = 0; // v2.3.0, flags=0
        // Syncsafe 28-bit size
        out[6] = (byte) ((len >>> 21) & 0x7F);
        out[7] = (byte) ((len >>> 14) & 0x7F);
        out[8] = (byte) ((len >>> 7) & 0x7F);
        out[9] = (byte) (len & 0x7F);
        System.arraycopy(body, 0, out, 10, len);
        return out;
    }

    private static void writeTextFrame(ByteArrayOutputStream out, String id, String text) {
        if (text == null || text.isEmpty()) return;
        byte[] str = text.getBytes(StandardCharsets.ISO_8859_1);
        int size = 1 + str.length;
        writeFrameHeader(out, id, size);
        out.write(0); // ISO-8859-1 encoding byte
        out.write(str, 0, str.length);
    }

    private static void writeCommentFrame(ByteArrayOutputStream out, String comment) {
        if (comment == null || comment.isEmpty()) return;
        byte[] str = comment.getBytes(StandardCharsets.ISO_8859_1);
        int size = 1 + 3 + 1 + str.length; // encoding + "eng" + empty desc NUL + text
        writeFrameHeader(out, "COMM", size);
        out.write(0);
        out.write('e'); out.write('n'); out.write('g');
        out.write(0);
        out.write(str, 0, str.length);
    }

    private static void writeFrameHeader(ByteArrayOutputStream out, String id, int size) {
        for (int i = 0; i < 4; i++) out.write(id.charAt(i));
        out.write((size >>> 24) & 0xFF);
        out.write((size >>> 16) & 0xFF);
        out.write((size >>> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(0); out.write(0); // flags
    }

    public static int resolveMp3Rate(int inputRate) {
        if (inputRate == 44100 || inputRate == 48000 || inputRate == 32000) {
            return inputRate;
        }
        return 48000;
    }

    public void writeFloatFrames(ByteBuffer interleavedFloat, int numFrames) throws IOException {
        if (numFrames <= 0 || finalized) return;

        int maxChunk = 4096;
        if (numFrames > maxChunk) {
            int bpf = channels * 4;
            int done = 0;
            ByteBuffer sliceBuf = ByteBuffer.allocateDirect(maxChunk * bpf).order(ByteOrder.LITTLE_ENDIAN);
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

        ByteBuffer feedBuf;
        int feedFrames;
        if (resampler.isPassthrough() && interleavedFloat.isDirect()) {
            feedBuf = interleavedFloat;
            feedFrames = numFrames;
        } else {
            feedFrames = resampler.process(interleavedFloat, numFrames, resampledBuf);
            feedBuf = resampledBuf;
        }
        if (feedFrames <= 0) return;

        mp3OutBuf.clear();
        int n = encoder.encode(feedBuf, feedFrames, mp3OutBuf);
        if (n < 0) {
            throw new IOException("MP3 encode buffer overflow");
        }
        writeOutBytes(n);
    }

    public long getTotalFileBytes() {
        return totalBytesWritten;
    }

    public void finalizeAndFlush() throws IOException {
        if (finalized) return;
        finalized = true;
        try {
            int n = encoder.flush(mp3OutBuf);
            if (n > 0) {
                writeOutBytes(n);
            }
            channel.force(false);
        } finally {
            encoder.close();
        }
    }

    private void writeOutBytes(int length) throws IOException {
        if (length <= 0) return;
        mp3OutBuf.limit(length);
        mp3OutBuf.position(0);
        while (mp3OutBuf.hasRemaining()) {
            int w = channel.write(mp3OutBuf);
            if (w <= 0) {
                throw new IOException("Storage write failed (disk full or unavailable)");
            }
            totalBytesWritten += w;
        }
    }
}
