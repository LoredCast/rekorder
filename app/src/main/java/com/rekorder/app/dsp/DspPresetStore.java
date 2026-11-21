// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.dsp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the active DSP configuration (with Auto-Apply state) plus
 * built-in studio presets, user-created custom presets, and JSON backup export/import.
 */
public final class DspPresetStore {

    private static final String PREFS = "rekorder_dsp_prefs";
    private static final String KEY_ACTIVE = "active_dsp_json";
    private static final String KEY_CUSTOM_LIST = "custom_presets_json";

    public static final class PresetEntry {
        public final DspConfig config;
        public final boolean builtIn;
        public final String description;

        public PresetEntry(DspConfig config, boolean builtIn, String description) {
            this.config = config;
            this.builtIn = builtIn;
            this.description = description;
        }
    }

    private DspPresetStore() {}

    public static List<PresetEntry> getBuiltInPresets() {
        List<PresetEntry> list = new ArrayList<>();
        list.add(new PresetEntry(
                DspConfig.rawBypass(), true,
                "All DSP bypassed · 100% untouched bit-exact mic signal"
        ));
        list.add(new PresetEntry(
                new DspConfig(
                        "Voice / Podcast", true, 0f, 0, true, -46f,
                        true, 80, new float[]{-1.5f, -2.0f, 0.5f, 2.5f, 1.5f},
                        true, -20f, 3.0f, 10f, 140f, 4.0f,
                        true, -16.0f, true
                ), true,
                "80Hz HPF · soft gate · vocal EQ · 3:1 comp · -16 LUFS"
        ));
        list.add(new PresetEntry(
                new DspConfig(
                        "Acoustic / Music", true,
                        true, 0, new float[]{1.0f, 0.0f, 0.0f, 0.5f, 1.5f},
                        true, -16f, 2.0f, 25f, 220f, 2.0f,
                        true, -1.0f
                ), true,
                "Warm air shelf · transparent 2:1 glue comp · -1dB peak"
        ));
        list.add(new PresetEntry(
                new DspConfig(
                        "Loud / Concert", true,
                        true, 80, new float[]{-2.0f, -1.0f, 0.0f, 1.0f, 0.5f},
                        true, -12f, 8.0f, 2f, 100f, 0.0f,
                        true, -1.0f
                ), true,
                "Sub-bass trim · fast 8:1 peak protection · -1dB peak"
        ));
        list.add(new PresetEntry(
                new DspConfig(
                        "Field / Ambient", true,
                        true, 80, new float[]{0f, 0f, 0f, 0f, 0f},
                        false, -18f, 3.0f, 15f, 180f, 0.0f,
                        true, -3.0f
                ), true,
                "80Hz wind cut · zero compression · -3dB clean peak lift"
        ));
        return list;
    }

    public static DspConfig loadActive(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_ACTIVE, null);
        if (raw == null || raw.isEmpty()) return DspConfig.rawBypass();
        try {
            return DspConfig.fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return DspConfig.rawBypass();
        }
    }

    public static void saveActive(Context ctx, DspConfig config) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_ACTIVE, config.toJson().toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    public static List<PresetEntry> loadAllPresets(Context ctx) {
        List<PresetEntry> all = new ArrayList<>(getBuiltInPresets());
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (DspConfig c : loadCustomOnly(sp)) {
            all.add(new PresetEntry(c, false, c.activeModulesSummary()));
        }
        return all;
    }

    public static void saveCustomPreset(Context ctx, DspConfig preset) {
        String name = preset.presetName.trim();
        if (name.isEmpty()) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<DspConfig> existing = loadCustomOnly(sp);
        List<DspConfig> updated = new ArrayList<>();
        boolean replaced = false;
        for (DspConfig c : existing) {
            if (c.presetName.equalsIgnoreCase(name)) {
                updated.add(preset.withName(name));
                replaced = true;
            } else {
                updated.add(c);
            }
        }
        if (!replaced) {
            updated.add(preset.withName(name));
        }
        persistCustomOnly(sp, updated);
    }

    public static void deleteCustomPreset(Context ctx, String name) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<DspConfig> existing = loadCustomOnly(sp);
        List<DspConfig> kept = new ArrayList<>();
        for (DspConfig c : existing) {
            if (!c.presetName.equalsIgnoreCase(name)) {
                kept.add(c);
            }
        }
        persistCustomOnly(sp, kept);
    }

    /** Exports active DSP config + all custom presets as indented JSON for backup/sharing. */
    public static String exportPresetsJson(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject();
            root.put("schema", "rekorder_presets_v1");
            root.put("active", loadActive(ctx).toJson());
            JSONArray custom = new JSONArray();
            for (DspConfig c : loadCustomOnly(sp)) {
                custom.put(c.toJson());
            }
            root.put("custom", custom);
            return root.toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * Imports presets from JSON (either a full bundle exported by exportPresetsJson,
     * a JSON array of presets, or a single preset object). Returns number of presets imported.
     */
    public static int importPresetsJson(Context ctx, String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return 0;
        String trimmed = rawJson.trim();
        int count = 0;
        try {
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        saveCustomPreset(ctx, DspConfig.fromJson(o));
                        count++;
                    }
                }
            } else {
                JSONObject root = new JSONObject(trimmed);
                JSONArray custom = root.optJSONArray("custom");
                if (custom != null) {
                    for (int i = 0; i < custom.length(); i++) {
                        JSONObject o = custom.optJSONObject(i);
                        if (o != null) {
                            saveCustomPreset(ctx, DspConfig.fromJson(o));
                            count++;
                        }
                    }
                    JSONObject active = root.optJSONObject("active");
                    if (active != null) {
                        saveActive(ctx, DspConfig.fromJson(active));
                        if (count == 0) count = 1;
                    }
                } else if (root.has("name") || root.has("eqEnabled") || root.has("compEnabled")) {
                    saveCustomPreset(ctx, DspConfig.fromJson(root));
                    count = 1;
                }
            }
        } catch (JSONException ignored) {
            return 0;
        }
        return count;
    }

    private static List<DspConfig> loadCustomOnly(SharedPreferences sp) {
        List<DspConfig> list = new ArrayList<>();
        String raw = sp.getString(KEY_CUSTOM_LIST, null);
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(DspConfig.fromJson(o));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    private static void persistCustomOnly(SharedPreferences sp, List<DspConfig> list) {
        JSONArray arr = new JSONArray();
        for (DspConfig c : list) {
            try {
                arr.put(c.toJson());
            } catch (JSONException ignored) {
            }
        }
        sp.edit().putString(KEY_CUSTOM_LIST, arr.toString()).apply();
    }
}
