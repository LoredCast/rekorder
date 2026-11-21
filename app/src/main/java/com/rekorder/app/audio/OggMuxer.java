// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Pure-Java RFC 3533 Ogg page muxer + RFC 7845 OpusHead/OpusTags builder
 * + RFC 6716 Opus TOC packet duration parser.
 * Zero external dependencies; writes directly to any FileChannel (including Android SAF).
 */
public final class OggMuxer {

    public static final int FLAG_BOS = 0x02;
    public static final int FLAG_EOS = 0x04;

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04c11db7;
                } else {
                    r <<= 1;
                }
            }
            CRC_TABLE[i] = r;
        }
    }

    private final FileChannel channel;
    private final int serialNumber;
    private int pageSequence = 0;
    private long totalBytesWritten = 0L;

    public OggMuxer(FileChannel channel, int serialNumber) {
        this.channel = channel;
        this.serialNumber = serialNumber;
    }

    public long getTotalBytesWritten() {
        return totalBytesWritten;
    }

    /**
     * Writes one single-packet Ogg page (supports packets of any size via RFC 3533 lacing).
     */
    public void writePacket(byte[] packet, int offset, int length, long granulePosition, int headerFlags)
            throws IOException {
        int fullSegments = length / 255;
        int lastSegLen = length % 255;
        int numSegments = fullSegments + 1; // Always terminated by a < 255 byte lace value
        if (numSegments > 255) {
            throw new IOException("Ogg packet too large for single page: " + length);
        }

        int pageSize = 27 + numSegments + length;
        byte[] page = new byte[pageSize];
        ByteBuffer bb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);

        bb.put((byte) 'O').put((byte) 'g').put((byte) 'g').put((byte) 'S');
        bb.put((byte) 0);                      // capture version
        bb.put((byte) (headerFlags & 0xFF));   // header_type_flag
        bb.putLong(granulePosition);           // absolute granule position (48 kHz units for Opus)
        bb.putInt(serialNumber);               // bitstream serial number
        bb.putInt(pageSequence++);             // page sequence number
        bb.putInt(0);                          // CRC placeholder (bytes 22..25)
        bb.put((byte) (numSegments & 0xFF));   // page_segments

        for (int i = 0; i < fullSegments; i++) {
            bb.put((byte) 255);
        }
        bb.put((byte) (lastSegLen & 0xFF));
        if (length > 0) {
            bb.put(packet, offset, length);
        }

        int crc = computeCrc(page, 0, pageSize);
        bb.putInt(22, crc);

        ByteBuffer out = ByteBuffer.wrap(page);
        while (out.hasRemaining()) {
            int w = channel.write(out);
            if (w <= 0) {
                throw new IOException("Storage write failed (disk full or unavailable)");
            }
            totalBytesWritten += w;
        }
    }

    public static int computeCrc(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (data[i] & 0xFF)) & 0xFF];
        }
        return crc;
    }

    /** RFC 7845 §5.1 OpusHead identification header (19 bytes). */
    public static byte[] buildOpusHead(int channels, int preSkip, int inputSampleRate) {
        ByteBuffer bb = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
        bb.put((byte) 1);                      // Version
        bb.put((byte) (channels & 0xFF));      // Channel count (1 or 2)
        bb.putShort((short) (preSkip & 0xFFFF)); // Pre-skip in 48 kHz samples
        bb.putInt(inputSampleRate);            // Original input sample rate
        bb.putShort((short) 0);                // Output gain (0 dB)
        bb.put((byte) 0);                      // Channel mapping family 0 (mono/stereo)
        return bb.array();
    }

    /** RFC 7845 §5.2 OpusTags comment header (with optional VorbisComment key=value tags). */
    public static byte[] buildOpusTags(String vendor) {
        return buildOpusTags(vendor, (String[]) null);
    }

    public static byte[] buildOpusTags(String vendor, String... comments) {
        byte[] vBytes = vendor.getBytes(StandardCharsets.UTF_8);
        int count = 0;
        int commentsTotal = 0;
        byte[][] cBytes = null;
        if (comments != null && comments.length > 0) {
            cBytes = new byte[comments.length][];
            for (String c : comments) {
                if (c != null && !c.isEmpty()) {
                    byte[] b = c.getBytes(StandardCharsets.UTF_8);
                    cBytes[count++] = b;
                    commentsTotal += 4 + b.length;
                }
            }
        }
        ByteBuffer bb = ByteBuffer.allocate(8 + 4 + vBytes.length + 4 + commentsTotal).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("OpusTags".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(vBytes.length);
        bb.put(vBytes);
        bb.putInt(count);
        for (int i = 0; i < count; i++) {
            bb.putInt(cBytes[i].length);
            bb.put(cBytes[i]);
        }
        return bb.array();
    }

    /**
     * Parses RFC 6716 §3.1 Opus TOC byte to compute exact packet duration in 48 kHz samples.
     */
    public static int opusPacketSamples48k(byte[] packet, int off, int len) {
        if (len <= 0) return 960;
        int toc = packet[off] & 0xFF;
        int cfg = toc >>> 3;
        int frameSamples;
        if (cfg < 12) {
            // SILK-only: 10, 20, 40, 60 ms
            int[] sil = {480, 960, 1920, 2880};
            frameSamples = sil[cfg & 3];
        } else if (cfg < 16) {
            // Hybrid: 10, 20 ms
            frameSamples = ((cfg & 1) == 0) ? 480 : 960;
        } else {
            // CELT-only: 2.5, 5, 10, 20 ms
            int[] cel = {120, 240, 480, 960};
            frameSamples = cel[cfg & 3];
        }
        int code = toc & 3;
        int frameCount;
        if (code == 0) {
            frameCount = 1;
        } else if (code == 1 || code == 2) {
            frameCount = 2;
        } else {
            frameCount = (len >= 2) ? (packet[off + 1] & 0x3F) : 1;
            if (frameCount <= 0) frameCount = 1;
        }
        return frameSamples * frameCount;
    }
}
