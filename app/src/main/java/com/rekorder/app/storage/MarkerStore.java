// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.storage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight bookmark/marker store keyed by recording filename.
 * Stores millisecond timestamps in SharedPreferences so markers work identically
 * across default storage and custom SAF folders without creating extra sidecar files.
 */
public final class MarkerStore {

    private static final String PREFS = "rekorder_markers";

    private MarkerStore() {}

    public static List<Long> load(Context ctx, String fileName) {
        List<Long> out = new ArrayList<>();
        if (fileName == null || fileName.isEmpty()) return out;
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(fileName, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String tok : raw.split(",")) {
            try {
                long ms = Long.parseLong(tok.trim());
                if (ms >= 0L) out.add(ms);
            } catch (NumberFormatException ignored) {}
        }
        Collections.sort(out);
        return out;
    }

    public static void save(Context ctx, String fileName, List<Long> markersMs) {
        if (fileName == null || fileName.isEmpty()) return;
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (markersMs == null || markersMs.isEmpty()) {
            ed.remove(fileName).apply();
            return;
        }
        List<Long> sorted = new ArrayList<>(markersMs);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sorted.get(i));
        }
        ed.putString(fileName, sb.toString()).apply();
    }

    public static void rename(Context ctx, String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName)) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.contains(oldName)) return;
        String val = sp.getString(oldName, null);
        sp.edit().remove(oldName).putString(newName, val).apply();
    }

    public static void delete(Context ctx, String fileName) {
        if (fileName == null || fileName.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(fileName).apply();
    }
}
