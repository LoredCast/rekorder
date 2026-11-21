// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.net.Uri;

import com.rekorder.app.storage.RecordingStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Crash-safe sidecar & recovery manager for temporary float32 capture scratch files.
 * If the OS kills Rekorder or battery dies mid-capture or mid-encoding, any captured audio
 * sitting in cache is automatically recovered into a lossless 24-bit WAV on next launch.
 */
public final class ScratchRecovery {

    public static final String SCRATCH_PREFIX = "rek_norm_scratch_";
    public static final String SCRATCH_EXT = ".f32";

    public static final class ScratchMeta {
        public final int sampleRate;
        public final int channels;
        public final float normTargetDb;
        public final float maxPeak;

        public ScratchMeta(int sampleRate, int channels, float normTargetDb, float maxPeak) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.normTargetDb = normTargetDb;
            this.maxPeak = maxPeak;
        }
    }

    public static final class RecoveredTake {
        public final Uri uri;
        public final String displayName;
        public final long sizeBytes;

        public RecoveredTake(Uri uri, String displayName, long sizeBytes) {
            this.uri = uri;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }

    private ScratchRecovery() {}

    public static File metaFileFor(File scratchFile) {
        return new File(scratchFile.getAbsolutePath() + ".meta");
    }

    public static void writeMeta(File scratchFile, int sampleRate, int channels,
                                 float normTargetDb, float maxPeak) {
        if (scratchFile == null) return;
        File meta = metaFileFor(scratchFile);
        File tmp = new File(meta.getAbsolutePath() + ".tmp");
        String content = String.format(Locale.US, "sr=%d\nch=%d\ntarget=%.2f\npeak=%.6f\n",
                sampleRate, channels, normTargetDb, maxPeak);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(content.getBytes(StandardCharsets.US_ASCII));
            fos.getFD().sync();
        } catch (IOException ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(meta);
    }

    public static ScratchMeta readMeta(File scratchFile) {
        int sr = 48000;
        int ch = 1;
        float target = -1.0f;
        float peak = 0f;

        File meta = metaFileFor(scratchFile);
        if (meta.isFile() && meta.length() > 0 && meta.length() < 4096) {
            try (FileInputStream fis = new FileInputStream(meta)) {
                byte[] data = new byte[(int) meta.length()];
                int n = fis.read(data);
                if (n > 0) {
                    String s = new String(data, 0, n, StandardCharsets.US_ASCII);
                    for (String line : s.split("\n")) {
                        int eq = line.indexOf('=');
                        if (eq <= 0) continue;
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        try {
                            if ("sr".equals(k)) sr = Integer.parseInt(v);
                            else if ("ch".equals(k)) ch = Integer.parseInt(v);
                            else if ("target".equals(k)) target = Float.parseFloat(v);
                            else if ("peak".equals(k)) peak = Float.parseFloat(v);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
        }
        if (sr < 8000 || sr > 192000) sr = 48000;
        if (ch != 1 && ch != 2) ch = 1;
        return new ScratchMeta(sr, ch, target, peak);
    }

    public static void deleteScratchAndMeta(File scratchFile) {
        if (scratchFile == null) return;
        File meta = metaFileFor(scratchFile);
        if (meta.exists()) {
            //noinspection ResultOfMethodCallIgnored
            meta.delete();
        }
        if (scratchFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            scratchFile.delete();
        }
    }

    public static List<File> findOrphanedScratches(Context ctx, File excludeActive) {
        List<File> found = new ArrayList<>();
        File[] dirs = {ctx.getCacheDir(), ctx.getExternalCacheDir()};
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (!name.startsWith(SCRATCH_PREFIX) || !name.endsWith(SCRATCH_EXT)) continue;
                if (excludeActive != null && f.getAbsolutePath().equals(excludeActive.getAbsolutePath())) continue;
                if (f.length() >= 4096L) {
                    found.add(f);
                } else {
                    deleteScratchAndMeta(f);
                }
            }
        }
        return found;
    }

    /**
     * Recovers an orphaned float32 scratch file into a permanent 24-bit lossless WAV file.
     * Deletes the scratch only after the WAV output is completely flushed and verified > 0 bytes.
     */
    public static RecoveredTake recoverScratch(Context ctx, File scratchFile, ProgressListener progress)
            throws IOException {
        ScratchMeta meta = readMeta(scratchFile);
        RecordingStorage.RecordingTarget target = RecordingStorage.createTarget(
                ctx, AudioConfig.OutputFormat.WAV_24, meta.sampleRate, meta.channels, "_recovered", false);
        long written = 0L;
        try {
            boolean applyNorm = meta.maxPeak > 1e-6f;
            written = PostNormalizer.convertScratchToLosslessWav(
                    scratchFile, target.channel,
                    meta.sampleRate, meta.channels,
                    meta.maxPeak, applyNorm, meta.normTargetDb, progress);
            target.close();
            if (written > WavWriter.headerSize(AudioConfig.OutputFormat.WAV_24)) {
                deleteScratchAndMeta(scratchFile);
                return new RecoveredTake(target.uri, target.displayName, written);
            } else {
                target.deleteSilently(ctx);
                deleteScratchAndMeta(scratchFile);
                return null;
            }
        } catch (IOException e) {
            target.deleteSilently(ctx);
            throw e;
        }
    }
}
