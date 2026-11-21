// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.storage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Minimal, dependency-free read-only ContentProvider for sharing recordings stored
 * in the default app-external Music/Rekorder directory (when no SAF tree is chosen).
 */
public final class RecordingFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.rekorder.app.files";

    public static Uri uriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.getName())
                .build();
    }

    public static File getDefaultDir(Context ctx) {
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base != null) {
            File extDir = new File(base, "Rekorder");
            if (!extDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                extDir.mkdirs();
            }
            if (extDir.isDirectory() && extDir.canWrite()) {
                return extDir;
            }
        }
        File internalDir = new File(ctx.getFilesDir(), "Rekorder");
        if (!internalDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            internalDir.mkdirs();
        }
        return internalDir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveSafeFile(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || uri == null) throw new FileNotFoundException("Invalid URI");
        String seg = uri.getLastPathSegment();
        if (seg == null || seg.isEmpty() || seg.contains("/") || seg.contains("\\") || seg.contains("..")) {
            throw new FileNotFoundException("Invalid filename");
        }
        File dir = getDefaultDir(ctx);
        File target = new File(dir, seg);
        try {
            String dirCanon = dir.getCanonicalPath();
            String fileCanon = target.getCanonicalPath();
            if (!fileCanon.startsWith(dirCanon + File.separator)) {
                throw new FileNotFoundException("Path outside recordings directory");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot resolve file");
        }
        if (!target.exists() || !target.isFile()) {
            throw new FileNotFoundException("File not found");
        }
        return target;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveSafeFile(uri);
            String[] cols = (projection != null && projection.length > 0)
                    ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, 0);
        }
    }

    @Override
    public String getType(Uri uri) {
        String seg = (uri != null) ? uri.getLastPathSegment() : null;
        if (seg != null) {
            String low = seg.toLowerCase(Locale.US);
            if (low.endsWith(".wav")) return "audio/wav";
            if (low.endsWith(".flac")) return "audio/flac";
            if (low.endsWith(".ogg")) return "audio/ogg";
            if (low.endsWith(".mp3")) return "audio/mpeg";
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new SecurityException("Read-only provider");
        }
        File file = resolveSafeFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only");
    }
}
