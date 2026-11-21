// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import com.rekorder.app.audio.AudioConfig;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manages custom recording directory storage via Android Storage Access Framework (SAF)
 * with seamless fallback to the app's external Music/Rekorder folder.
 */
public final class RecordingStorage {

    private static final String PREFS = "rekorder_prefs";
    private static final String KEY_TREE_URI = "storage_tree_uri";

    public static final class RecordingItem {
        public final Uri uri;
        public final String name;
        public final long sizeBytes;
        public final long lastModified;
        public final String mimeType;
        public final boolean isSaf;
        public final File localFile; // null if SAF

        public RecordingItem(Uri uri, String name, long sizeBytes, long lastModified,
                             String mimeType, boolean isSaf, File localFile) {
            this.uri = uri;
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
            this.mimeType = mimeType;
            this.isSaf = isSaf;
            this.localFile = localFile;
        }
    }

    public static final class ReadableChannelHandle implements Closeable {
        public final FileChannel channel;
        private final Closeable[] closables;

        ReadableChannelHandle(FileChannel channel, Closeable... closables) {
            this.channel = channel;
            this.closables = closables;
        }

        @Override
        public void close() throws IOException {
            for (Closeable c : closables) {
                if (c != null) {
                    try { c.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    public static final class RecordingTarget implements Closeable {
        public final Uri uri;
        public final String displayName;
        public final FileChannel channel;
        public final boolean isSaf;
        public final File localFile;
        public final boolean fellBackToDefault;
        private final Closeable[] closables;

        RecordingTarget(Uri uri, String displayName, FileChannel channel,
                        boolean isSaf, File localFile, boolean fellBackToDefault,
                        Closeable... closables) {
            this.uri = uri;
            this.displayName = displayName;
            this.channel = channel;
            this.isSaf = isSaf;
            this.localFile = localFile;
            this.fellBackToDefault = fellBackToDefault;
            this.closables = closables;
        }

        /**
         * Returns a readable FileChannel for in-place post-processing (e.g. WAV/RAW normalization).
         * Local RandomAccessFile channels are natively read-write; SAF FileOutputStream channels
         * are write-only, so this opens a dedicated read-only descriptor for SAF targets.
         */
        public ReadableChannelHandle openReadChannel(Context ctx) throws IOException {
            if (!isSaf) {
                return new ReadableChannelHandle(channel);
            }
            ParcelFileDescriptor rpfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (rpfd == null) {
                throw new IOException("Cannot open SAF file for reading");
            }
            FileInputStream fis = new FileInputStream(rpfd.getFileDescriptor());
            return new ReadableChannelHandle(fis.getChannel(), fis, rpfd);
        }

        public void deleteSilently(Context ctx) {
            try { close(); } catch (IOException ignored) {}
            if (isSaf) {
                try { DocumentsContract.deleteDocument(ctx.getContentResolver(), uri); } catch (Exception ignored) {}
            } else if (localFile != null && localFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                localFile.delete();
            }
        }

        @Override
        public void close() throws IOException {
            IOException firstErr = null;
            for (Closeable c : closables) {
                if (c != null) {
                    try {
                        c.close();
                    } catch (IOException e) {
                        if (firstErr == null) firstErr = e;
                    }
                }
            }
            if (firstErr != null) throw firstErr;
        }
    }

    private RecordingStorage() {}

    public static Uri getCustomTreeUri(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_TREE_URI, null);
        if (raw == null || raw.isEmpty()) return null;
        Uri uri = Uri.parse(raw);
        if (hasPersistedWriteAccess(ctx, uri)) {
            return uri;
        }
        return null;
    }

    public static void setCustomTreeUri(Context ctx, Uri treeUri) {
        if (treeUri != null) {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                ctx.getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (SecurityException ignored) {
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TREE_URI, treeUri.toString())
                    .apply();
        } else {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(KEY_TREE_URI)
                    .apply();
        }
    }

    private static boolean hasPersistedWriteAccess(Context ctx, Uri treeUri) {
        List<UriPermission> perms = ctx.getContentResolver().getPersistedUriPermissions();
        for (UriPermission p : perms) {
            if (p.getUri().equals(treeUri) && p.isReadPermission() && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    public static String getDisplayPath(Context ctx) {
        Uri tree = getCustomTreeUri(ctx);
        if (tree == null) {
            return "App Storage / Music / Rekorder  (tap to customize)";
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            if (docId != null) {
                if (docId.startsWith("primary:")) {
                    String rel = docId.substring("primary:".length());
                    return rel.isEmpty() ? "/Internal" : "/Internal/" + rel;
                }
                int colon = docId.indexOf(':');
                if (colon > 0) {
                    String vol = docId.substring(0, colon);
                    String rel = docId.substring(colon + 1);
                    return rel.isEmpty() ? "/" + vol : "/" + vol + "/" + rel;
                }
                return "/" + docId;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return tree.getLastPathSegment() != null ? tree.getLastPathSegment() : tree.toString();
    }

    public static String buildFileName(AudioConfig.OutputFormat fmt, int sampleRate, int channels) {
        return buildFileName(fmt, sampleRate, channels, "");
    }

    public static String buildFileName(AudioConfig.OutputFormat fmt, int sampleRate, int channels, String suffix) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String sfx = (suffix != null) ? suffix : "";
        if (fmt.hasWavHeader || fmt.isCompressed) {
            return "rek_" + ts + sfx + "." + fmt.extension;
        }
        return "rek_" + ts + sfx + "_" + sampleRate + "Hz_" + fmt.rawTag() + "_" + channels + "ch.pcm";
    }

    public static RecordingTarget createTarget(Context ctx, AudioConfig.OutputFormat fmt,
                                               int sampleRate, int channels) throws IOException {
        return createTarget(ctx, fmt, sampleRate, channels, "", false);
    }

    public static RecordingTarget createTarget(Context ctx, AudioConfig.OutputFormat fmt,
                                               int sampleRate, int channels, String suffix,
                                               boolean forceLocalDefault) throws IOException {
        String name = buildFileName(fmt, sampleRate, channels, suffix);
        return createNamedTarget(ctx, name, forceLocalDefault);
    }

    /**
     * Creates a recording output target in the configured SAF folder, automatically falling back
     * to default app storage if the SAF folder is unavailable, revoked, or on an ejected volume.
     */
    public static RecordingTarget createNamedTarget(Context ctx, String name, boolean forceLocalDefault)
            throws IOException {
        Uri tree = forceLocalDefault ? null : getCustomTreeUri(ctx);
        boolean fellBack = false;

        if (tree != null) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                String parentDocId = DocumentsContract.getTreeDocumentId(tree);
                Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId);
                // Using application/octet-stream prevents Android SAF from duplicating ".wav.wav"
                Uri created = DocumentsContract.createDocument(cr, parentDocUri, "application/octet-stream", name);
                if (created != null) {
                    String actualName = queryDisplayName(cr, created, name);
                    ParcelFileDescriptor pfd = cr.openFileDescriptor(created, "rw");
                    if (pfd != null) {
                        FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                        return new RecordingTarget(created, actualName, fos.getChannel(), true, null, false, fos, pfd);
                    }
                    try { DocumentsContract.deleteDocument(cr, created); } catch (Exception ignored) {}
                }
                fellBack = true;
            } catch (Exception e) {
                fellBack = true;
            }
        }

        File dir = RecordingFileProvider.getDefaultDir(ctx);
        File f = uniqueFile(dir, name);
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        Uri uri = RecordingFileProvider.uriForFile(f);
        return new RecordingTarget(uri, f.getName(), raf.getChannel(), false, f, fellBack, raf);
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int suffix = 1;
        while (f.exists()) {
            int dot = name.lastIndexOf('.');
            String alt = (dot > 0)
                    ? name.substring(0, dot) + "_" + suffix + name.substring(dot)
                    : name + "_" + suffix;
            f = new File(dir, alt);
            suffix++;
        }
        return f;
    }

    public static long getAvailableBytes(File dir) {
        if (dir == null) return Long.MAX_VALUE;
        try {
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            StatFs sf = new StatFs(dir.getAbsolutePath());
            return sf.getAvailableBytes();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    /** Returns whichever cache directory (external or internal) has more free space for scratch audio. */
    public static File getBestScratchDir(Context ctx) {
        File internal = ctx.getCacheDir();
        File external = ctx.getExternalCacheDir();
        if (external != null) {
            if (!external.exists()) {
                //noinspection ResultOfMethodCallIgnored
                external.mkdirs();
            }
            if (external.canWrite()) {
                long extAvail = getAvailableBytes(external);
                long intAvail = getAvailableBytes(internal);
                if (extAvail >= intAvail) {
                    return external;
                }
            }
        }
        return internal;
    }

    public static long getRecordingStorageAvailableBytes(Context ctx) {
        File dir = RecordingFileProvider.getDefaultDir(ctx);
        return getAvailableBytes(dir);
    }

    public static List<RecordingItem> listRecordings(Context ctx) {
        List<RecordingItem> items = new ArrayList<>();
        Uri tree = getCustomTreeUri(ctx);

        if (tree != null) {
            ContentResolver cr = ctx.getContentResolver();
            try {
                String parentDocId = DocumentsContract.getTreeDocumentId(tree);
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
                String[] proj = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                };
                try (Cursor c = cr.query(childrenUri, proj, null, null, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            String docId = c.getString(0);
                            String name = c.getString(1);
                            long size = c.getLong(2);
                            long mod = c.getLong(3);
                            String mime = c.getString(4);
                            if (name == null || !isSupportedRecordingName(name)) continue;
                            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                            items.add(new RecordingItem(docUri, name, size, mod,
                                    mime != null ? mime : guessMime(name), true, null));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            File dir = RecordingFileProvider.getDefaultDir(ctx);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile() || !isSupportedRecordingName(f.getName())) continue;
                    items.add(new RecordingItem(
                            RecordingFileProvider.uriForFile(f),
                            f.getName(),
                            f.length(),
                            f.lastModified(),
                            guessMime(f.getName()),
                            false,
                            f
                    ));
                }
            }
        }

        Collections.sort(items, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return items;
    }

    public static boolean deleteRecording(Context ctx, RecordingItem item) {
        if (item == null) return false;
        if (item.isSaf) {
            try {
                return DocumentsContract.deleteDocument(ctx.getContentResolver(), item.uri);
            } catch (Exception e) {
                return false;
            }
        }
        return item.localFile != null && item.localFile.delete();
    }

    /**
     * Renames a recording safely (both local files and SAF documents), preserving its original extension.
     * Returns the updated RecordingItem or null if rename failed.
     */
    public static RecordingItem renameRecording(Context ctx, RecordingItem item, String requestedName) {
        if (item == null || requestedName == null) return null;
        String clean = requestedName.trim().replaceAll("[/\\\\:*?\"<>|]", "_");
        if (clean.isEmpty()) return null;

        String oldName = item.name;
        int dot = oldName.lastIndexOf('.');
        String ext = (dot > 0) ? oldName.substring(dot) : "";
        if (!ext.isEmpty() && !clean.toLowerCase(Locale.US).endsWith(ext.toLowerCase(Locale.US))) {
            clean = clean + ext;
        }
        if (clean.equals(oldName)) return item;

        if (item.isSaf) {
            try {
                Uri renamedUri = DocumentsContract.renameDocument(ctx.getContentResolver(), item.uri, clean);
                if (renamedUri != null) {
                    String actual = queryDisplayName(ctx.getContentResolver(), renamedUri, clean);
                    return new RecordingItem(renamedUri, actual, item.sizeBytes,
                            System.currentTimeMillis(), guessMime(actual), true, null);
                }
            } catch (Exception ignored) {}
            return null;
        } else if (item.localFile != null) {
            File dest = new File(item.localFile.getParentFile(), clean);
            if (dest.exists()) return null;
            if (item.localFile.renameTo(dest)) {
                return new RecordingItem(RecordingFileProvider.uriForFile(dest), dest.getName(),
                        dest.length(), dest.lastModified(), guessMime(dest.getName()), false, dest);
            }
        }
        return null;
    }

    /** Ultra-fast O(1) duration probe for recordings list (header-only for WAV/PCM, metadata retriever for compressed). */
    public static long probeDurationMs(Context ctx, RecordingItem item) {
        if (item == null || item.sizeBytes <= 0L) return -1L;
        String low = item.name.toLowerCase(Locale.US);
        if (low.endsWith(".wav") || low.endsWith(".pcm")) {
            try (InputStream in = openInputStream(ctx, item.uri)) {
                com.rekorder.app.audio.WavWriter.AudioFileMeta meta =
                        com.rekorder.app.audio.WavWriter.parseMeta(in, item.sizeBytes, item.name);
                return meta.durationMs();
            } catch (Exception ignored) {
                return -1L;
            }
        }
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(item.uri, "r")) {
            if (pfd == null) return -1L;
            mmr.setDataSource(pfd.getFileDescriptor());
            String dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null && !dur.isEmpty()) {
                return Long.parseLong(dur);
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return -1L;
    }

    public static InputStream openInputStream(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open recording stream");
        return in;
    }

    public static long querySize(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String queryDisplayName(ContentResolver cr, Uri uri, String fallback) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static boolean isSupportedRecordingName(String name) {
        String low = name.toLowerCase(Locale.US);
        return low.endsWith(".wav") || low.endsWith(".pcm")
                || low.endsWith(".flac") || low.endsWith(".ogg") || low.endsWith(".mp3");
    }

    public static String guessMime(String name) {
        String low = name.toLowerCase(Locale.US);
        if (low.endsWith(".wav")) return "audio/wav";
        if (low.endsWith(".flac")) return "audio/flac";
        if (low.endsWith(".ogg")) return "audio/ogg";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
