// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.dsp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

/**
 * Immutable configuration for Rekorder's studio DSP chain:
 * High-Pass Filter + 5-Band Equalizer + Soft-Knee Compressor + True Peak Normalizer,
 * with named presets and Auto-Apply persistence.
 */
public final class DspConfig {

    public static final float[] EQ_FREQS_HZ = {80f, 250f, 1000f, 4000f, 10000f};
    public static final String[] EQ_LABELS = {"80Hz", "250Hz", "1kHz", "4kHz", "10kHz"};

    public final String presetName;
    public final boolean autoApply;

    // Input Preamp Trim (digital gain before EQ/Compressor: [-12, +24] dB, 0 = bypass)
    public final float inputGainDb;

    // Equalizer + HPF
    public final boolean eqEnabled;
    public final int hpfHz;           // 0 = off, 80, or 120
    public final float[] eqBandsDb;   // length 5, each [-12, +12]

    // Compressor
    public final boolean compEnabled;
    public final float compThresholdDb; // [-40, 0]
    public final float compRatio;       // [1.5, 20]
    public final float compAttackMs;    // [1, 100]
    public final float compReleaseMs;   // [20, 1000]
    public final float compMakeupDb;    // [0, 24]

    // True Peak Normalizer (exact constant-gain pass on Stop)
    public final boolean normEnabled;
    public final float normTargetDb;    // [-6.0, -0.3]

    public DspConfig(String presetName, boolean autoApply,
                     boolean eqEnabled, int hpfHz, float[] eqBandsDb,
                     boolean compEnabled, float compThresholdDb, float compRatio,
                     float compAttackMs, float compReleaseMs, float compMakeupDb,
                     boolean normEnabled, float normTargetDb) {
        this(presetName, autoApply, 0f, eqEnabled, hpfHz, eqBandsDb,
                compEnabled, compThresholdDb, compRatio, compAttackMs, compReleaseMs, compMakeupDb,
                normEnabled, normTargetDb);
    }

    public DspConfig(String presetName, boolean autoApply, float inputGainDb,
                     boolean eqEnabled, int hpfHz, float[] eqBandsDb,
                     boolean compEnabled, float compThresholdDb, float compRatio,
                     float compAttackMs, float compReleaseMs, float compMakeupDb,
                     boolean normEnabled, float normTargetDb) {
        this.presetName = (presetName != null && !presetName.trim().isEmpty()) ? presetName.trim() : "Custom";
        this.autoApply = autoApply;
        this.inputGainDb = clamp(inputGainDb, -12f, 24f);
        this.eqEnabled = eqEnabled;
        this.hpfHz = (hpfHz == 80 || hpfHz == 120) ? hpfHz : 0;
        this.eqBandsDb = new float[5];
        if (eqBandsDb != null) {
            for (int i = 0; i < 5 && i < eqBandsDb.length; i++) {
                this.eqBandsDb[i] = clamp(eqBandsDb[i], -12f, 12f);
            }
        }
        this.compEnabled = compEnabled;
        this.compThresholdDb = clamp(compThresholdDb, -40f, 0f);
        this.compRatio = clamp(compRatio, 1.0f, 20.0f);
        this.compAttackMs = clamp(compAttackMs, 0.5f, 100f);
        this.compReleaseMs = clamp(compReleaseMs, 20f, 1000f);
        this.compMakeupDb = clamp(compMakeupDb, 0f, 24f);
        this.normEnabled = normEnabled;
        this.normTargetDb = clamp(normTargetDb, -12f, -0.1f);
    }

    public static DspConfig rawBypass() {
        return new DspConfig(
                "Raw (Bypass)", false, 0f,
                false, 0, new float[]{0f, 0f, 0f, 0f, 0f},
                false, -18f, 4.0f, 10f, 120f, 4f,
                false, -1.0f
        );
    }

    public boolean hasInputGain() {
        return Math.abs(inputGainDb) >= 0.05f;
    }

    public boolean hasRealTimeFx() {
        return autoApply && (hasInputGain() || eqEnabled || compEnabled);
    }

    public boolean hasNormalizer() {
        return autoApply && normEnabled;
    }

    public boolean hasAnyActiveFx() {
        return autoApply && (hasInputGain() || eqEnabled || compEnabled || normEnabled);
    }

    public DspConfig withAutoApply(boolean enable) {
        return new DspConfig(presetName, enable, inputGainDb, eqEnabled, hpfHz, eqBandsDb,
                compEnabled, compThresholdDb, compRatio, compAttackMs, compReleaseMs, compMakeupDb,
                normEnabled, normTargetDb);
    }

    public DspConfig withName(String name) {
        return new DspConfig(name, autoApply, inputGainDb, eqEnabled, hpfHz, eqBandsDb,
                compEnabled, compThresholdDb, compRatio, compAttackMs, compReleaseMs, compMakeupDb,
                normEnabled, normTargetDb);
    }

    public String activeModulesSummary() {
        if (!hasAnyActiveFx()) return "BYPASS (BIT-EXACT RAW)";
        StringBuilder sb = new StringBuilder();
        if (hasInputGain()) {
            sb.append(String.format(Locale.US, "GAIN %+.0fdB", inputGainDb));
        }
        if (eqEnabled) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("EQ");
            if (hpfHz > 0) sb.append("+HPF");
        }
        if (compEnabled) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "COMP %.0f:1", compRatio));
        }
        if (normEnabled) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "NORM %.1fdB", normTargetDb));
        }
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", presetName);
        o.put("autoApply", autoApply);
        o.put("inputGainDb", inputGainDb);
        o.put("eqEnabled", eqEnabled);
        o.put("hpfHz", hpfHz);
        JSONArray bands = new JSONArray();
        for (float b : eqBandsDb) bands.put(b);
        o.put("eqBandsDb", bands);
        o.put("compEnabled", compEnabled);
        o.put("compThresholdDb", compThresholdDb);
        o.put("compRatio", compRatio);
        o.put("compAttackMs", compAttackMs);
        o.put("compReleaseMs", compReleaseMs);
        o.put("compMakeupDb", compMakeupDb);
        o.put("normEnabled", normEnabled);
        o.put("normTargetDb", normTargetDb);
        return o;
    }

    public static DspConfig fromJson(JSONObject o) {
        if (o == null) return rawBypass();
        float[] bands = new float[5];
        JSONArray arr = o.optJSONArray("eqBandsDb");
        if (arr != null) {
            for (int i = 0; i < 5 && i < arr.length(); i++) {
                bands[i] = (float) arr.optDouble(i, 0.0);
            }
        }
        return new DspConfig(
                o.optString("name", "Custom"),
                o.optBoolean("autoApply", true),
                (float) o.optDouble("inputGainDb", 0.0),
                o.optBoolean("eqEnabled", false),
                o.optInt("hpfHz", 0),
                bands,
                o.optBoolean("compEnabled", false),
                (float) o.optDouble("compThresholdDb", -18.0),
                (float) o.optDouble("compRatio", 4.0),
                (float) o.optDouble("compAttackMs", 10.0),
                (float) o.optDouble("compReleaseMs", 120.0),
                (float) o.optDouble("compMakeupDb", 4.0),
                o.optBoolean("normEnabled", false),
                (float) o.optDouble("normTargetDb", -1.0)
        );
    }

    public float[] copyBands() {
        return Arrays.copyOf(eqBandsDb, 5);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
