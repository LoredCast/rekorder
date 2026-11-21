// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.storage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lightweight bookmark/marker store keyed by recording filename.
 * Stores millisecond timestamps + optional short labels ("ms" or "ms:label") in SharedPreferences
 * so markers work identically across default storage and custom SAF folders without extra sidecars.
 */
public final class MarkerStore {

    public static final class MarkerEntry {
        public final long timeMs;
        public final String label; // empty if unnamed

        public MarkerEntry(long timeMs, String label) {
            this.timeMs = Math.max(0L, timeMs);
            this.label = sanitizeLabel(label);
        }

        public static String sanitizeLabel(String raw) {
            if (raw == null) return "";
            String s = raw.replace(',', ' ').replace('|', ' ').replace('\n', ' ').trim();
            return s.length() > 32 ? s.substring(0, 32) : s;
        }
    }

    private static final String PREFS = "rekorder_markers";

    private MarkerStore() {}

    public static List<Long> load(Context ctx, String fileName) {
        List<MarkerEntry> entries = loadEntries(ctx, fileName);
        List<Long> out = new ArrayList<>(entries.size());
        for (MarkerEntry e : entries) out.add(e.timeMs);
        return out;
    }

    public static List<MarkerEntry> loadEntries(Context ctx, String fileName) {
        if (ctx == null || fileName == null || fileName.isEmpty()) return new ArrayList<>();
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(fileName, "");
        return parseEntries(raw);
    }

    public static List<MarkerEntry> parseEntries(String raw) {
        List<MarkerEntry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String tok : raw.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            int sep = t.indexOf(':');
            String msPart = (sep >= 0) ? t.substring(0, sep) : t;
            String lblPart = (sep >= 0) ? t.substring(sep + 1) : "";
            try {
                long ms = Long.parseLong(msPart.trim());
                if (ms >= 0L) out.add(new MarkerEntry(ms, lblPart));
            } catch (NumberFormatException ignored) {}
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.timeMs));
        return out;
    }

    public static String serializeEntries(List<MarkerEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        List<MarkerEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, Comparator.comparingLong(a -> a.timeMs));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(',');
            MarkerEntry e = sorted.get(i);
            sb.append(e.timeMs);
            if (!e.label.isEmpty()) {
                sb.append(':').append(e.label);
            }
        }
        return sb.toString();
    }

    public static void save(Context ctx, String fileName, List<Long> markersMs) {
        if (ctx == null || fileName == null || fileName.isEmpty()) return;
        List<MarkerEntry> entries = new ArrayList<>();
        if (markersMs != null) {
            for (Long ms : markersMs) {
                if (ms != null && ms >= 0L) entries.add(new MarkerEntry(ms, ""));
            }
        }
        saveEntries(ctx, fileName, entries);
    }

    public static void saveEntries(Context ctx, String fileName, List<MarkerEntry> entries) {
        if (ctx == null || fileName == null || fileName.isEmpty()) return;
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (entries == null || entries.isEmpty()) {
            ed.remove(fileName).apply();
            return;
        }
        ed.putString(fileName, serializeEntries(entries)).apply();
    }

    public static List<MarkerEntry> addMarker(Context ctx, String fileName, long timeMs, String label) {
        List<MarkerEntry> list = loadEntries(ctx, fileName);
        list.add(new MarkerEntry(timeMs, label));
        Collections.sort(list, Comparator.comparingLong(a -> a.timeMs));
        saveEntries(ctx, fileName, list);
        return list;
    }

    public static int findNearestIndex(List<MarkerEntry> entries, long posMs, long toleranceMs) {
        if (entries == null || entries.isEmpty()) return -1;
        int best = -1;
        long bestDist = toleranceMs + 1L;
        for (int i = 0; i < entries.size(); i++) {
            long d = Math.abs(entries.get(i).timeMs - posMs);
            if (d <= toleranceMs && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    public static long findPrevMarker(List<Long> markersMs, long posMs) {
        if (markersMs == null || markersMs.isEmpty()) return -1L;
        // 350ms grace so tapping Prev while playing right after a marker jumps to the earlier one
        long cutoff = Math.max(0L, posMs - 350L);
        long prev = -1L;
        for (long m : markersMs) {
            if (m < cutoff) prev = m;
            else break;
        }
        return prev;
    }

    public static long findNextMarker(List<Long> markersMs, long posMs) {
        if (markersMs == null || markersMs.isEmpty()) return -1L;
        long cutoff = posMs + 150L;
        for (long m : markersMs) {
            if (m > cutoff) return m;
        }
        return -1L;
    }

    public static void rename(Context ctx, String oldName, String newName) {
        if (ctx == null || oldName == null || newName == null || oldName.equals(newName)) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.contains(oldName)) return;
        String val = sp.getString(oldName, null);
        sp.edit().remove(oldName).putString(newName, val).apply();
    }

    public static void delete(Context ctx, String fileName) {
        if (ctx == null || fileName == null || fileName.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(fileName).apply();
    }
}
