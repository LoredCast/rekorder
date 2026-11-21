// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lossless RIFF/WAVE (PCM 16-bit, PCM 24-bit, IEEE Float 32-bit with fact chunk)
 * and headerless RAW (.pcm) writer + parser.
 */
public final class WavWriter {

    public static final int WAVE_FORMAT_PCM = 1;
    public static final int WAVE_FORMAT_IEEE_FLOAT = 3;

    /* Cap standard RIFF uint32 chunk cleanly just below 4 GiB. */
    public static final long MAX_WAV_DATA_BYTES = 0xFFFFF000L;
    /* Trigger seamless automatic part-splitting well before the 4 GiB RIFF ceiling (~3.81 GiB). */
    public static final long MAX_WAV_SPLIT_BYTES = 0xF4000000L;

    private final FileChannel channel;
    private final AudioConfig.OutputFormat format;
    private final int sampleRate;
    private final int channelCount;
    private final int bytesPerFrame;

    private long dataBytesWritten = 0L;
    private boolean finalized = false;

    public WavWriter(FileChannel channel, AudioConfig.OutputFormat format,
                     int sampleRate, int channelCount) throws IOException {
        this.channel = channel;
        this.format = format;
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.bytesPerFrame = format.bytesPerSample * channelCount;

        if (format.hasWavHeader) {
            writeHeader(0L);
        }
    }

    public long write(ByteBuffer buf, int length) throws IOException {
        if (length <= 0 || finalized) return 0L;
        if (format.hasWavHeader) {
            long remaining = MAX_WAV_DATA_BYTES - dataBytesWritten;
            if (remaining <= 0L) return 0L;
            if (length > remaining) {
                length = (int) (remaining - (remaining % bytesPerFrame));
                if (length <= 0) return 0L;
            }
        }
        length = Math.min(length, buf.capacity());
        buf.limit(length);
        buf.position(0);
        int total = 0;
        while (buf.hasRemaining()) {
            int n = channel.write(buf);
            if (n <= 0) {
                throw new IOException("Storage write failed (disk full or unavailable)");
            }
            total += n;
        }
        dataBytesWritten += total;
        return total;
    }

    /** Returns true when a WAV file is approaching the 4 GiB RIFF limit and should split cleanly. */
    public boolean shouldSplitForSizeLimit() {
        return format.hasWavHeader && dataBytesWritten >= MAX_WAV_SPLIT_BYTES;
    }

    /**
     * Crash-safe periodic checkpoint: updates the RIFF/WAVE header chunk sizes on disk and restores
     * the channel position back to EOF so a sudden battery loss or process kill still leaves a valid file.
     */
    public void checkpointHeader() {
        if (finalized || !format.hasWavHeader || dataBytesWritten <= 0L) return;
        try {
            long curPos = channel.position();
            channel.position(0L);
            writeHeader(dataBytesWritten);
            channel.position(curPos);
            channel.force(false);
        } catch (IOException ignored) {
            // Best-effort checkpoint; keep recording uninterrupted if channel does not support mid-stream seek
        }
    }

    public long getDataBytesWritten() {
        return dataBytesWritten;
    }

    public long getTotalFileBytes() {
        return dataBytesWritten + (format.hasWavHeader ? headerSize(format) : 0);
    }

    public long getFramesWritten() {
        return bytesPerFrame > 0 ? dataBytesWritten / bytesPerFrame : 0L;
    }

    public void finalizeAndFlush() throws IOException {
        if (finalized) return;
        finalized = true;
        if (format.hasWavHeader) {
            channel.position(0L);
            writeHeader(dataBytesWritten);
        }
        channel.force(false);
    }

    public static int headerSize(AudioConfig.OutputFormat format) {
        if (!format.hasWavHeader) return 0;
        return format.isFloat ? 58 : 44;
    }

    private void writeHeader(long dataBytes) throws IOException {
        long clampedData = Math.min(dataBytes, MAX_WAV_DATA_BYTES);
        int hdrSize = headerSize(format);
        ByteBuffer hdr = ByteBuffer.allocate(hdrSize).order(ByteOrder.LITTLE_ENDIAN);

        int byteRate = sampleRate * bytesPerFrame;
        short blockAlign = (short) bytesPerFrame;
        short bitsPerSample = (short) format.bitsPerSample;

        if (format.isFloat) {
            // 58-byte IEEE Float WAVE header: RIFF(12) + fmt(26) + fact(12) + data(8)
            long riffSize = 50L + clampedData;
            long sampleFrames = bytesPerFrame > 0 ? (clampedData / bytesPerFrame) : 0L;

            putAscii(hdr, "RIFF");
            hdr.putInt((int) riffSize);
            putAscii(hdr, "WAVE");

            putAscii(hdr, "fmt ");
            hdr.putInt(18); // fmt chunk size with cbSize=0
            hdr.putShort((short) WAVE_FORMAT_IEEE_FLOAT);
            hdr.putShort((short) channelCount);
            hdr.putInt(sampleRate);
            hdr.putInt(byteRate);
            hdr.putShort(blockAlign);
            hdr.putShort(bitsPerSample);
            hdr.putShort((short) 0); // cbSize

            putAscii(hdr, "fact");
            hdr.putInt(4);
            hdr.putInt((int) Math.min(sampleFrames, 0xFFFFFFFFL));

            putAscii(hdr, "data");
            hdr.putInt((int) clampedData);
        } else {
            // Standard 44-byte PCM WAVE header
            long riffSize = 36L + clampedData;

            putAscii(hdr, "RIFF");
            hdr.putInt((int) riffSize);
            putAscii(hdr, "WAVE");

            putAscii(hdr, "fmt ");
            hdr.putInt(16);
            hdr.putShort((short) WAVE_FORMAT_PCM);
            hdr.putShort((short) channelCount);
            hdr.putInt(sampleRate);
            hdr.putInt(byteRate);
            hdr.putShort(blockAlign);
            hdr.putShort(bitsPerSample);

            putAscii(hdr, "data");
            hdr.putInt((int) clampedData);
        }

        hdr.flip();
        while (hdr.hasRemaining()) {
            channel.write(hdr);
        }
    }

    private static void putAscii(ByteBuffer buf, String s) {
        for (int i = 0; i < s.length(); i++) {
            buf.put((byte) s.charAt(i));
        }
    }

    /* ---------- Audio File Header / Metadata Parser ---------- */

    public static final class AudioFileMeta {
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;
        public final boolean isFloat;
        public final int pcmFormat;   // PcmConverter.FMT_*
        public final long dataOffset;
        public final long dataBytes;
        public final boolean isRawPcm;
        public final boolean isCompressed;
        public final long compressedDurationMs;
        public final String compressedTag;

        public AudioFileMeta(int sampleRate, int channels, int bitsPerSample, boolean isFloat,
                             int pcmFormat, long dataOffset, long dataBytes, boolean isRawPcm) {
            this(sampleRate, channels, bitsPerSample, isFloat, pcmFormat, dataOffset, dataBytes,
                    isRawPcm, false, 0L, null);
        }

        private AudioFileMeta(int sampleRate, int channels, int bitsPerSample, boolean isFloat,
                              int pcmFormat, long dataOffset, long dataBytes, boolean isRawPcm,
                              boolean isCompressed, long compressedDurationMs, String compressedTag) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.isFloat = isFloat;
            this.pcmFormat = pcmFormat;
            this.dataOffset = dataOffset;
            this.dataBytes = dataBytes;
            this.isRawPcm = isRawPcm;
            this.isCompressed = isCompressed;
            this.compressedDurationMs = compressedDurationMs;
            this.compressedTag = compressedTag;
        }

        public static AudioFileMeta forCompressed(int sampleRate, int channels, long durationMs, String codecTag) {
            return new AudioFileMeta(sampleRate, channels, 16, false, PcmConverter.FMT_PCM_I16,
                    0L, 0L, false, true, Math.max(0L, durationMs), codecTag);
        }

        public int bytesPerFrame() {
            return (bitsPerSample / 8) * channels;
        }

        public long durationMs() {
            if (isCompressed) return compressedDurationMs;
            int bpf = bytesPerFrame();
            if (bpf <= 0 || sampleRate <= 0) return 0L;
            long frames = dataBytes / bpf;
            return (frames * 1000L) / sampleRate;
        }

        public String summaryLabel() {
            String rate = (sampleRate % 1000 == 0)
                    ? (sampleRate / 1000) + "kHz"
                    : String.format(Locale.US, "%.1fkHz", sampleRate / 1000.0);
            if (isCompressed) {
                return (compressedTag != null ? compressedTag : "AUDIO")
                        + " · " + rate + " · " + (channels == 2 ? "ST" : "MONO");
            }
            String fmt = isFloat ? "32f" : (bitsPerSample + "b");
            String kind = isRawPcm ? "RAW" : "WAV";
            return kind + " " + fmt + " · " + rate + " · " + (channels == 2 ? "ST" : "MONO");
        }
    }

    private static final Pattern RAW_NAME_PATTERN = Pattern.compile(
            "_(\\d+)Hz_(f32le|s24le|s16le)_(\\d+)ch\\.pcm$", Pattern.CASE_INSENSITIVE);

    /**
     * Parses a RIFF/WAVE header (handling arbitrary extra chunks) or infers RAW .pcm params from filename.
     */
    public static AudioFileMeta parseMeta(InputStream in, long totalFileSize, String fileName) throws IOException {
        if (fileName != null && fileName.toLowerCase(Locale.US).endsWith(".pcm")) {
            int sr = 48000;
            int ch = 1;
            int bits = 32;
            boolean isFloat = true;
            int pcmFmt = PcmConverter.FMT_PCM_FLOAT;

            Matcher m = RAW_NAME_PATTERN.matcher(fileName);
            if (m.find()) {
                sr = Integer.parseInt(m.group(1));
                String tag = m.group(2).toLowerCase(Locale.US);
                ch = Math.max(1, Math.min(2, Integer.parseInt(m.group(3))));
                if ("s24le".equals(tag)) {
                    bits = 24;
                    isFloat = false;
                    pcmFmt = PcmConverter.FMT_PCM_I24_PACKED;
                } else if ("s16le".equals(tag)) {
                    bits = 16;
                    isFloat = false;
                    pcmFmt = PcmConverter.FMT_PCM_I16;
                }
            }
            return new AudioFileMeta(sr, ch, bits, isFloat, pcmFmt, 0L, Math.max(0L, totalFileSize), true);
        }

        byte[] riff = new byte[12];
        if (!readFully(in, riff, 0, 12)) {
            throw new IOException("Truncated RIFF header");
        }
        if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F'
                || riff[8] != 'W' || riff[9] != 'A' || riff[10] != 'V' || riff[11] != 'E') {
            throw new IOException("Not a valid WAVE file");
        }

        long pos = 12L;
        int audioFmt = -1;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;

        byte[] chunkHdr = new byte[8];
        while (readFully(in, chunkHdr, 0, 8)) {
            pos += 8;
            String id = new String(new char[]{
                    (char) (chunkHdr[0] & 0xFF), (char) (chunkHdr[1] & 0xFF),
                    (char) (chunkHdr[2] & 0xFF), (char) (chunkHdr[3] & 0xFF)
            });
            long chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;

            if ("fmt ".equals(id)) {
                int toRead = (int) Math.min(chunkSize, 40L);
                if (toRead < 16) throw new IOException("Invalid fmt chunk");
                byte[] fmtBuf = new byte[toRead];
                if (!readFully(in, fmtBuf, 0, toRead)) throw new IOException("Truncated fmt chunk");
                ByteBuffer bb = ByteBuffer.wrap(fmtBuf).order(ByteOrder.LITTLE_ENDIAN);
                audioFmt = bb.getShort(0) & 0xFFFF;
                channels = bb.getShort(2) & 0xFFFF;
                sampleRate = bb.getInt(4);
                bitsPerSample = bb.getShort(14) & 0xFFFF;
                // Handle WAVE_FORMAT_EXTENSIBLE (0xFFFE)
                if (audioFmt == 0xFFFE && toRead >= 26) {
                    int subFmt = bb.getShort(24) & 0xFFFF;
                    audioFmt = subFmt;
                }
                long rem = chunkSize - toRead + (chunkSize & 1L);
                skipFully(in, rem);
                pos += chunkSize + (chunkSize & 1L);
            } else if ("data".equals(id)) {
                if (audioFmt < 0 || channels <= 0 || sampleRate <= 0) {
                    throw new IOException("WAVE data chunk before fmt chunk");
                }
                boolean isFloat = (audioFmt == WAVE_FORMAT_IEEE_FLOAT);
                int pcmFmt;
                if (isFloat && bitsPerSample == 32) {
                    pcmFmt = PcmConverter.FMT_PCM_FLOAT;
                } else if (!isFloat && bitsPerSample == 24) {
                    pcmFmt = PcmConverter.FMT_PCM_I24_PACKED;
                } else if (!isFloat && bitsPerSample == 32) {
                    pcmFmt = PcmConverter.FMT_PCM_I32;
                } else if (!isFloat && bitsPerSample == 16) {
                    pcmFmt = PcmConverter.FMT_PCM_I16;
                } else {
                    throw new IOException("Unsupported WAV encoding: tag=" + audioFmt + " bits=" + bitsPerSample);
                }
                long actualData = chunkSize;
                if (totalFileSize > pos && (actualData == 0L || pos + actualData > totalFileSize)) {
                    actualData = totalFileSize - pos;
                }
                return new AudioFileMeta(sampleRate, channels, bitsPerSample, isFloat,
                        pcmFmt, pos, actualData, false);
            } else {
                long toSkip = chunkSize + (chunkSize & 1L);
                skipFully(in, toSkip);
                pos += toSkip;
            }
        }
        throw new IOException("No data chunk in WAVE file");
    }

    private static boolean readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) return false;
            read += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long rem = n;
        byte[] trash = null;
        while (rem > 0) {
            long s = in.skip(rem);
            if (s > 0) {
                rem -= s;
            } else {
                if (trash == null) trash = new byte[1024];
                int r = in.read(trash, 0, (int) Math.min(trash.length, rem));
                if (r < 0) break;
                rem -= r;
            }
        }
    }
}
