// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.storage;

import android.content.Context;

import com.rekorder.app.audio.WavWriter;
import com.rekorder.app.audio.WaveformAnalyzer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Compact binary waveform peak/RMS cache (~2.6 KB per file) stored in app cache.
 * Makes reopening multi-hour WAV/FLAC/OGG/MP3 recordings instantaneous (<5 ms).
 */
public final class WaveformCache {

    private static final int MAGIC = 0x52454B57; // "REKW"
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 160;

    private WaveformCache() {}

    private static File cacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "wavecache");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static File cacheFile(Context ctx, String fileName, long fileSizeBytes) {
        long h = 1125899906842597L;
        if (fileName != null) {
            for (int i = 0; i < fileName.length(); i++) {
                h = 31L * h + fileName.charAt(i);
            }
        }
        h = 31L * h + fileSizeBytes;
        return new File(cacheDir(ctx), Long.toHexString(h) + ".wfc");
    }

    public static WaveformAnalyzer.WaveformData get(Context ctx, String fileName, long fileSizeBytes) {
        if (fileName == null || fileSizeBytes <= 0L) return null;
        File f = cacheFile(ctx, fileName, fileSizeBytes);
        if (!f.isFile() || f.length() < 32L) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            long cachedSize = in.readLong();
            if (cachedSize != fileSizeBytes) return null;

            int sr = in.readInt();
            int ch = in.readInt();
            int bits = in.readInt();
            boolean isFloat = in.readBoolean();
            int pcmFmt = in.readInt();
            long dataOff = in.readLong();
            long dataBytes = in.readLong();
            boolean isRaw = in.readBoolean();
            boolean isComp = in.readBoolean();
            long compDurMs = in.readLong();
            String compTag = in.readUTF();
            float maxPeak = in.readFloat();
            int n = in.readInt();
            if (n <= 0 || n > 4096) return null;

            float[] peaks = new float[n];
            float[] rms = new float[n];
            for (int i = 0; i < n; i++) peaks[i] = in.readFloat();
            for (int i = 0; i < n; i++) rms[i] = in.readFloat();

            // Touch mtime for LRU
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());

            WavWriter.AudioFileMeta meta = isComp
                    ? WavWriter.AudioFileMeta.forCompressed(sr, ch, compDurMs, compTag.isEmpty() ? null : compTag)
                    : new WavWriter.AudioFileMeta(sr, ch, bits, isFloat, pcmFmt, dataOff, dataBytes, isRaw);
            return new WaveformAnalyzer.WaveformData(peaks, rms, meta, maxPeak);
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    public static void put(Context ctx, String fileName, long fileSizeBytes, WaveformAnalyzer.WaveformData data) {
        if (fileName == null || fileSizeBytes <= 0L || data == null || data.meta == null) return;
        File dir = cacheDir(ctx);
        pruneIfNeeded(dir);
        File f = cacheFile(ctx, fileName, fileSizeBytes);
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        WavWriter.AudioFileMeta m = data.meta;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(fileSizeBytes);
            out.writeInt(m.sampleRate);
            out.writeInt(m.channels);
            out.writeInt(m.bitsPerSample);
            out.writeBoolean(m.isFloat);
            out.writeInt(m.pcmFormat);
            out.writeLong(m.dataOffset);
            out.writeLong(m.dataBytes);
            out.writeBoolean(m.isRawPcm);
            out.writeBoolean(m.isCompressed);
            out.writeLong(m.compressedDurationMs);
            out.writeUTF(m.compressedTag != null ? m.compressedTag : "");
            out.writeFloat(data.maxPeak);
            out.writeInt(data.peaks.length);
            for (float p : data.peaks) out.writeFloat(p);
            for (float r : data.rms) out.writeFloat(r);
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    public static void rename(Context ctx, String oldName, String newName, long sizeBytes) {
        if (oldName == null || newName == null || sizeBytes <= 0L) return;
        File oldF = cacheFile(ctx, oldName, sizeBytes);
        if (oldF.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            oldF.renameTo(cacheFile(ctx, newName, sizeBytes));
        }
    }

    public static void delete(Context ctx, String fileName, long sizeBytes) {
        if (fileName == null || sizeBytes <= 0L) return;
        File f = cacheFile(ctx, fileName, sizeBytes);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void pruneIfNeeded(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < MAX_ENTRIES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toRemove = files.length - (MAX_ENTRIES * 3 / 4);
        for (int i = 0; i < toRemove && i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
