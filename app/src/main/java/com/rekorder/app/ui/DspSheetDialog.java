// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.rekorder.app.R;
import com.rekorder.app.dsp.DspConfig;
import com.rekorder.app.dsp.DspPresetStore;

import java.util.List;
import java.util.Locale;

/**
 * Pitch-black (#000000) AMOLED studio DSP & Presets dialog:
 *  - Auto-Apply master toggle
 *  - Built-in & Custom Presets (load / save / delete)
 *  - 5-Band Equalizer + High-Pass Rumble Filter (80/120 Hz)
 *  - Feed-Forward Soft-Knee Compressor (Threshold, Ratio, Attack, Release, Makeup)
 *  - Exact True Peak Normalizer (-0.5 / -1.0 / -3.0 / -6.0 dBFS)
 */
public final class DspSheetDialog extends Dialog {

    public interface Listener {
        void onDspConfigChanged(DspConfig newConfig);
    }

    private static final float[] NORM_TARGETS = {-0.5f, -1.0f, -3.0f, -6.0f};
    private static final float[] LUFS_TARGETS = {-14.0f, -16.0f, -18.0f, -23.0f};
    private static final int[] HPF_OPTIONS = {0, 80, 120};
    private static final int[] DEHUM_OPTIONS = {0, 50, 60};

    private String presetName;
    private boolean autoApply;
    private float inputGainDb;
    private int deHumHz;
    private boolean gateEnabled;
    private float gateThresholdDb;
    private boolean eqEnabled;
    private int hpfHz;
    private final float[] eqBandsDb;
    private boolean compEnabled;
    private float compThresholdDb;
    private float compRatio;
    private float compAttackMs;
    private float compReleaseMs;
    private float compMakeupDb;
    private boolean normEnabled;
    private float normTargetDb;
    private boolean normUseLufs;

    private final Listener listener;
    private final float dp;

    public DspSheetDialog(Context ctx, DspConfig current, Listener listener) {
        super(ctx, R.style.Theme_Rekorder_Dialog);
        this.listener = listener;
        this.dp = ctx.getResources().getDisplayMetrics().density;

        this.eqBandsDb = new float[5];
        loadFieldsFrom(current);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildContent(ctx));
        Window w = getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                w.getDecorView();
                WindowInsetsController ctrl = w.getInsetsController();
                if (ctrl != null) {
                    ctrl.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            }
        }
    }

    private void loadFieldsFrom(DspConfig c) {
        this.presetName = c.presetName;
        this.autoApply = c.autoApply;
        this.inputGainDb = c.inputGainDb;
        this.deHumHz = c.deHumHz;
        this.gateEnabled = c.gateEnabled;
        this.gateThresholdDb = c.gateThresholdDb;
        this.eqEnabled = c.eqEnabled;
        this.hpfHz = c.hpfHz;
        System.arraycopy(c.eqBandsDb, 0, this.eqBandsDb, 0, 5);
        this.compEnabled = c.compEnabled;
        this.compThresholdDb = c.compThresholdDb;
        this.compRatio = c.compRatio;
        this.compAttackMs = c.compAttackMs;
        this.compReleaseMs = c.compReleaseMs;
        this.compMakeupDb = c.compMakeupDb;
        this.normEnabled = c.normEnabled;
        this.normTargetDb = c.normTargetDb;
        this.normUseLufs = c.normUseLufs;
    }

    private DspConfig buildCurrentConfig() {
        return new DspConfig(
                presetName, autoApply, inputGainDb, deHumHz, gateEnabled, gateThresholdDb,
                eqEnabled, hpfHz, eqBandsDb,
                compEnabled, compThresholdDb, compRatio, compAttackMs, compReleaseMs, compMakeupDb,
                normEnabled, normTargetDb, normUseLufs
        );
    }

    private void emit() {
        if (listener != null) {
            listener.onDspConfigChanged(buildCurrentConfig());
        }
    }

    private void emitAndRebuild(Context ctx, LinearLayout oldRoot) {
        emit();
        int scrollY = 0;
        for (int i = 0; i < oldRoot.getChildCount(); i++) {
            View child = oldRoot.getChildAt(i);
            if (child instanceof ScrollView) {
                scrollY = child.getScrollY();
                break;
            }
        }
        View fresh = buildContent(ctx);
        setContentView(fresh);
        fresh.requestApplyInsets();
        final int targetScroll = scrollY;
        if (fresh instanceof LinearLayout) {
            LinearLayout fl = (LinearLayout) fresh;
            for (int i = 0; i < fl.getChildCount(); i++) {
                View child = fl.getChildAt(i);
                if (child instanceof ScrollView) {
                    child.post(() -> child.scrollTo(0, targetScroll));
                    break;
                }
            }
        }
    }

    private View buildContent(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        // Header
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(16), dp(14));

        TextView title = new TextView(ctx);
        title.setText("DSP FX & PRESETS");
        title.setTextColor(0xFFF2F2F2);
        title.setTextSize(14f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.14f);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = new TextView(ctx);
        done.setText("DONE");
        done.setTextColor(0xFF00E5A0);
        done.setTextSize(12.5f);
        done.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        done.setPadding(dp(14), dp(8), dp(8), dp(8));
        done.setOnClickListener(v -> dismiss());
        header.addView(done);

        root.addView(header);
        root.addView(hairline(ctx));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(0xFF000000);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(36));

        // 1. Master Auto-Apply Card
        body.addView(buildAutoApplyRow(ctx, root));

        // 2. Presets Section + Save Custom Preset
        LinearLayout presetHdr = new LinearLayout(ctx);
        presetHdr.setOrientation(LinearLayout.HORIZONTAL);
        presetHdr.setGravity(Gravity.CENTER_VERTICAL);
        presetHdr.setPadding(dp(2), dp(16), 0, dp(8));

        TextView pLbl = new TextView(ctx);
        pLbl.setText("PRESETS (TAP TO APPLY)");
        pLbl.setTextColor(0xFF686868);
        pLbl.setTextSize(10.5f);
        pLbl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pLbl.setLetterSpacing(0.12f);
        presetHdr.addView(pLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView expBtn = pillBtn(ctx, "EXPORT", 0xFF9A9A9A, 0xFF282828);
        LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        expLp.rightMargin = dp(6);
        expBtn.setOnClickListener(v -> exportPresetsToClipboard(ctx));
        presetHdr.addView(expBtn, expLp);

        TextView impBtn = pillBtn(ctx, "IMPORT", 0xFF9A9A9A, 0xFF282828);
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        impLp.rightMargin = dp(6);
        impBtn.setOnClickListener(v -> promptImportPresets(ctx, root));
        presetHdr.addView(impBtn, impLp);

        TextView saveBtn = pillBtn(ctx, "+ SAVE", 0xFF00E5A0, 0xFF00E5A0);
        saveBtn.setOnClickListener(v -> promptSavePreset(ctx, root));
        presetHdr.addView(saveBtn);
        body.addView(presetHdr);

        List<DspPresetStore.PresetEntry> presets = DspPresetStore.loadAllPresets(ctx);
        for (DspPresetStore.PresetEntry pe : presets) {
            body.addView(buildPresetRow(ctx, root, pe));
        }

        // 2b. Digital Input Gain / Preamp Trim (-12 to +24 dB, 0.5 dB steps -> 0..72)
        body.addView(buildSectionToggleHeader(ctx, "INPUT GAIN / PREAMP TRIM",
                Math.abs(inputGainDb) >= 0.05f, () -> {
                    inputGainDb = (Math.abs(inputGainDb) >= 0.05f) ? 0f : 6f;
                    if (Math.abs(inputGainDb) >= 0.05f) autoApply = true;
                    presetName = "Custom";
                    emitAndRebuild(ctx, root);
                }, "0 dB", () -> {
                    inputGainDb = 0f;
                    presetName = "Custom";
                    emitAndRebuild(ctx, root);
                }));
        LinearLayout gainCard = cardBox(ctx);
        int initGainProg = Math.round((inputGainDb + 12f) * 2f);
        gainCard.addView(sliderRow(ctx, "Preamp", 72, Math.max(0, Math.min(72, initGainProg)),
                prog -> formatSignedDb((prog - 24) * 0.5f),
                prog -> {
                    inputGainDb = (prog - 24) * 0.5f;
                    if (Math.abs(inputGainDb) >= 0.05f) autoApply = true;
                    presetName = "Custom";
                    emit();
                }));
        body.addView(gainCard);

        // 2c. Soft Noise Gate + 50/60 Hz De-Hum Mains Notch
        body.addView(buildSectionToggleHeader(ctx, "NOISE GATE & DE-HUM NOTCH",
                gateEnabled || deHumHz > 0, () -> {
                    gateEnabled = !gateEnabled;
                    if (gateEnabled) autoApply = true;
                    presetName = "Custom";
                    emitAndRebuild(ctx, root);
                }, null, null));
        LinearLayout gateCard = cardBox(ctx);
        LinearLayout humRow = new LinearLayout(ctx);
        humRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < DEHUM_OPTIONS.length; i++) {
            final int hz = DEHUM_OPTIONS[i];
            String lbl = (hz == 0) ? "DE-HUM OFF" : (hz + "Hz HUM");
            humRow.addView(chip(ctx, lbl, deHumHz == hz, () -> {
                deHumHz = hz;
                if (hz > 0) autoApply = true;
                presetName = "Custom";
                emitAndRebuild(ctx, root);
            }), chipParams(i < DEHUM_OPTIONS.length - 1));
        }
        gateCard.addView(humRow);
        // Gate Threshold: -60..-20 dB (prog 0..40 -> prog - 60)
        gateCard.addView(sliderRow(ctx, "Gate Thr", 40, Math.max(0, Math.min(40, Math.round(gateThresholdDb + 60f))),
                prog -> (prog - 60) + " dB",
                prog -> {
                    gateThresholdDb = prog - 60f;
                    gateEnabled = true;
                    autoApply = true;
                    presetName = "Custom";
                    emit();
                }));
        body.addView(gateCard);

        // 3. 5-Band Studio Equalizer + High-Pass Filter
        body.addView(buildSectionToggleHeader(ctx, "EQUALIZER (5-BAND + HPF)", eqEnabled, () -> {
            eqEnabled = !eqEnabled;
            if (eqEnabled) autoApply = true;
            presetName = "Custom";
            emitAndRebuild(ctx, root);
        }, "FLAT", () -> {
            for (int i = 0; i < 5; i++) eqBandsDb[i] = 0f;
            hpfHz = 0;
            presetName = "Custom";
            emitAndRebuild(ctx, root);
        }));

        LinearLayout eqCard = cardBox(ctx);
        // HPF chips
        LinearLayout hpfRow = new LinearLayout(ctx);
        hpfRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < HPF_OPTIONS.length; i++) {
            final int hz = HPF_OPTIONS[i];
            String lbl = (hz == 0) ? "HPF OFF" : ("HPF " + hz + "Hz");
            hpfRow.addView(chip(ctx, lbl, hpfHz == hz, () -> {
                hpfHz = hz;
                if (hz > 0) {
                    eqEnabled = true;
                    autoApply = true;
                }
                presetName = "Custom";
                emitAndRebuild(ctx, root);
            }), chipParams(i < HPF_OPTIONS.length - 1));
        }
        eqCard.addView(hpfRow);

        // 5 EQ band sliders (-12.0 to +12.0 dB in 0.5 dB steps -> SeekBar 0..48)
        for (int b = 0; b < 5; b++) {
            final int idx = b;
            int initProg = Math.round((eqBandsDb[idx] + 12f) * 2f);
            eqCard.addView(sliderRow(ctx, DspConfig.EQ_LABELS[idx], 48, initProg,
                    prog -> formatSignedDb((prog - 24) * 0.5f),
                    prog -> {
                        eqBandsDb[idx] = (prog - 24) * 0.5f;
                        if (!eqEnabled) eqEnabled = true;
                        if (!autoApply) autoApply = true;
                        presetName = "Custom";
                        emit();
                    }));
        }
        body.addView(eqCard);

        // 4. Studio Compressor
        body.addView(buildSectionToggleHeader(ctx, "COMPRESSOR (SOFT-KNEE)", compEnabled, () -> {
            compEnabled = !compEnabled;
            if (compEnabled) autoApply = true;
            presetName = "Custom";
            emitAndRebuild(ctx, root);
        }, null, null));

        LinearLayout compCard = cardBox(ctx);
        // Threshold: -40 .. 0 dB (prog 0..40 -> prog - 40)
        compCard.addView(sliderRow(ctx, "Threshold", 40, Math.round(compThresholdDb + 40f),
                prog -> (prog - 40) + " dB",
                prog -> {
                    compThresholdDb = prog - 40f;
                    compEnabled = true; autoApply = true; presetName = "Custom";
                    emit();
                }));
        // Ratio: 1.5 .. 20.0 (prog 0..37 -> 1.5 + prog*0.5)
        compCard.addView(sliderRow(ctx, "Ratio", 37, Math.round((compRatio - 1.5f) * 2f),
                prog -> String.format(Locale.US, "%.1f:1", 1.5f + prog * 0.5f),
                prog -> {
                    compRatio = 1.5f + prog * 0.5f;
                    compEnabled = true; autoApply = true; presetName = "Custom";
                    emit();
                }));
        // Attack: 1 .. 100 ms (prog 0..99 -> prog + 1)
        compCard.addView(sliderRow(ctx, "Attack", 99, Math.max(0, Math.round(compAttackMs) - 1),
                prog -> (prog + 1) + " ms",
                prog -> {
                    compAttackMs = prog + 1f;
                    compEnabled = true; autoApply = true; presetName = "Custom";
                    emit();
                }));
        // Release: 20 .. 1000 ms (prog 0..49 -> 20 + prog*20)
        compCard.addView(sliderRow(ctx, "Release", 49, Math.max(0, Math.round((compReleaseMs - 20f) / 20f)),
                prog -> (20 + prog * 20) + " ms",
                prog -> {
                    compReleaseMs = 20f + prog * 20f;
                    compEnabled = true; autoApply = true; presetName = "Custom";
                    emit();
                }));
        // Makeup gain: 0 .. +24 dB (prog 0..48 -> prog*0.5)
        compCard.addView(sliderRow(ctx, "Makeup", 48, Math.round(compMakeupDb * 2f),
                prog -> String.format(Locale.US, "+%.1f dB", prog * 0.5f),
                prog -> {
                    compMakeupDb = prog * 0.5f;
                    compEnabled = true; autoApply = true; presetName = "Custom";
                    emit();
                }));
        body.addView(compCard);

        // 5. Normalizer (True Peak dBFS or ITU-R BS.1770 Integrated LUFS)
        body.addView(buildSectionToggleHeader(ctx, "NORMALIZER (PEAK / LUFS)", normEnabled, () -> {
            normEnabled = !normEnabled;
            if (normEnabled) autoApply = true;
            presetName = "Custom";
            emitAndRebuild(ctx, root);
        }, null, null));

        LinearLayout normCard = cardBox(ctx);
        LinearLayout modeRow = new LinearLayout(ctx);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 0, 0, dp(8));
        modeRow.addView(chip(ctx, "TRUE PEAK (dBFS)", !normUseLufs, () -> {
            if (normUseLufs) {
                normUseLufs = false;
                normTargetDb = -1.0f;
                normEnabled = true;
                autoApply = true;
                presetName = "Custom";
                emitAndRebuild(ctx, root);
            }
        }), chipParams(true));
        modeRow.addView(chip(ctx, "LOUDNESS (LUFS)", normUseLufs, () -> {
            if (!normUseLufs) {
                normUseLufs = true;
                normTargetDb = -16.0f;
                normEnabled = true;
                autoApply = true;
                presetName = "Custom";
                emitAndRebuild(ctx, root);
            }
        }), chipParams(false));
        normCard.addView(modeRow);

        TextView normDesc = new TextView(ctx);
        normDesc.setText(normUseLufs
                ? "BS.1770 K-weighted integrated loudness (-16 LUFS podcast / -14 LUFS streaming) with -1 dBFS peak safety cap."
                : "Exact constant-gain peak normalization applied automatically on Stop (preserves full dynamics, zero pumping).");
        normDesc.setTextColor(0xFF6E6E6E);
        normDesc.setTextSize(11f);
        normDesc.setPadding(0, 0, 0, dp(10));
        normCard.addView(normDesc);

        LinearLayout targetRow = new LinearLayout(ctx);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        float[] targets = normUseLufs ? LUFS_TARGETS : NORM_TARGETS;
        for (int i = 0; i < targets.length; i++) {
            final float tDb = targets[i];
            boolean sel = Math.abs(normTargetDb - tDb) < 0.25f;
            String lbl = normUseLufs
                    ? String.format(Locale.US, "%.0f LUFS", tDb)
                    : String.format(Locale.US, "%.1f dB", tDb);
            targetRow.addView(chip(ctx, lbl, sel, () -> {
                normTargetDb = tDb;
                normEnabled = true;
                autoApply = true;
                presetName = "Custom";
                emitAndRebuild(ctx, root);
            }), chipParams(i < targets.length - 1));
        }
        normCard.addView(targetRow);
        body.addView(normCard);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildAutoApplyRow(Context ctx, LinearLayout root) {
        LinearLayout card = cardBox(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(autoApply ? 0xFF0A1410 : 0xFF070707);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), autoApply ? 0xFF00E5A0 : 0xFF222222);
        card.setBackground(bg);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(ctx);
        t.setText("AUTO-APPLY ON RECORD");
        t.setTextColor(autoApply ? 0xFF00E5A0 : 0xFFC8C8C8);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(t);

        TextView s = new TextView(ctx);
        s.setText(autoApply
                ? "Active preset (" + presetName + ") auto-applied on every recording"
                : "Bypassed · recording stays 100% untouched bit-exact raw mic");
        s.setTextColor(0xFF7A7A7A);
        s.setTextSize(11f);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);

        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView statePill = pillBtn(ctx, autoApply ? "ON" : "OFF",
                autoApply ? 0xFF00E5A0 : 0xFF666666,
                autoApply ? 0xFF00E5A0 : 0xFF2A2A2A);
        card.addView(statePill);

        card.setOnClickListener(v -> {
            autoApply = !autoApply;
            emitAndRebuild(ctx, root);
        });
        return card;
    }

    private View buildPresetRow(Context ctx, LinearLayout root, DspPresetStore.PresetEntry pe) {
        boolean selected = presetName.equalsIgnoreCase(pe.config.presetName);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF101010 : 0xFF060606);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), selected ? 0xFFF2F2F2 : 0xFF1D1D1D);
        row.setBackground(bg);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(ctx);
        t.setText(pe.config.presetName + (pe.builtIn ? "" : "  [SAVED]"));
        t.setTextColor(selected ? 0xFFFFFFFF : 0xFFD0D0D0);
        t.setTextSize(13f);
        t.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        col.addView(t);

        TextView sub = new TextView(ctx);
        sub.setText(pe.description);
        sub.setTextColor(0xFF686868);
        sub.setTextSize(11f);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!pe.builtIn) {
            ImageView del = new ImageView(ctx);
            del.setImageResource(R.drawable.ic_delete);
            del.setColorFilter(0xFF994444);
            del.setPadding(dp(8), dp(8), dp(8), dp(8));
            del.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
            del.setOnClickListener(v -> {
                DspPresetStore.deleteCustomPreset(ctx, pe.config.presetName);
                if (presetName.equalsIgnoreCase(pe.config.presetName)) {
                    presetName = "Custom";
                }
                emitAndRebuild(ctx, root);
            });
            row.addView(del);
        }

        row.setOnClickListener(v -> {
            loadFieldsFrom(pe.config);
            emitAndRebuild(ctx, root);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void promptSavePreset(Context ctx, LinearLayout root) {
        EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("e.g. Vocal Booth, Guitar Mic");
        input.setTextColor(0xFFF2F2F2);
        input.setHintTextColor(0xFF666666);
        if (!"Custom".equalsIgnoreCase(presetName) && !"Raw (Bypass)".equalsIgnoreCase(presetName)) {
            input.setText(presetName);
            input.setSelection(presetName.length());
        }
        int pad = dp(18);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setPadding(pad, dp(10), pad, dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Save DSP Preset")
                .setView(wrap)
                .setPositiveButton("Save", (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(ctx, "Enter a preset name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    presetName = name;
                    DspConfig toSave = buildCurrentConfig();
                    DspPresetStore.saveCustomPreset(ctx, toSave);
                    Toast.makeText(ctx, "Saved preset: " + name, Toast.LENGTH_SHORT).show();
                    emitAndRebuild(ctx, root);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportPresetsToClipboard(Context ctx) {
        String json = DspPresetStore.exportPresetsJson(ctx);
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Rekorder Presets JSON", json));
            Toast.makeText(ctx, "Presets JSON copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void promptImportPresets(Context ctx, LinearLayout root) {
        EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setHint("Paste exported Rekorder Presets JSON...");
        input.setTextColor(0xFFF2F2F2);
        input.setHintTextColor(0xFF666666);
        input.setTextSize(11f);
        input.setTypeface(Typeface.MONOSPACE);

        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
            if (clip != null && clip.toString().trim().startsWith("{")) {
                input.setText(clip.toString());
            }
        }
        int pad = dp(16);
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setPadding(pad, dp(10), pad, dp(4));
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Import DSP Presets (JSON)")
                .setView(wrap)
                .setPositiveButton("Import", (d, which) -> {
                    int n = DspPresetStore.importPresetsJson(ctx, input.getText().toString());
                    if (n > 0) {
                        loadFieldsFrom(DspPresetStore.loadActive(ctx));
                        Toast.makeText(ctx, "Imported " + n + " preset(s)", Toast.LENGTH_SHORT).show();
                        emitAndRebuild(ctx, root);
                    } else {
                        Toast.makeText(ctx, "Invalid preset JSON", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface ValueFormatter {
        String format(int progress);
    }

    private interface IntConsumer {
        void accept(int progress);
    }

    private View sliderRow(Context ctx, String label, int max, int current,
                           ValueFormatter fmt, IntConsumer onChange) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextColor(0xFFB0B0B0);
        lbl.setTextSize(11.5f);
        lbl.setTypeface(Typeface.MONOSPACE);
        row.addView(lbl, new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, current)));
        sb.setProgressTintList(ColorStateList.valueOf(0xFFF2F2F2));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFF2D2D));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A2A2A));
        row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valTv = new TextView(ctx);
        valTv.setText(fmt.format(sb.getProgress()));
        valTv.setTextColor(0xFFE0E0E0);
        valTv.setTextSize(11.5f);
        valTv.setTypeface(Typeface.MONOSPACE);
        valTv.setGravity(Gravity.END);
        row.addView(valTv, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valTv.setText(fmt.format(progress));
                if (fromUser) {
                    onChange.accept(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        return row;
    }

    private View buildSectionToggleHeader(Context ctx, String label, boolean enabled, Runnable onToggle,
                                          String extraBtnText, Runnable extraAction) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(18), 0, dp(8));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFF686868);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setLetterSpacing(0.12f);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (extraBtnText != null && extraAction != null) {
            TextView ex = pillBtn(ctx, extraBtnText, 0xFF888888, 0xFF282828);
            ex.setOnClickListener(v -> extraAction.run());
            LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            exLp.rightMargin = dp(8);
            row.addView(ex, exLp);
        }

        TextView tog = pillBtn(ctx, enabled ? "ON" : "OFF",
                enabled ? 0xFF00E5A0 : 0xFF666666,
                enabled ? 0xFF00E5A0 : 0xFF2A2A2A);
        tog.setOnClickListener(v -> onToggle.run());
        row.addView(tog);
        return row;
    }

    private TextView chip(Context ctx, String text, boolean selected, Runnable onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(selected ? 0xFFFFFFFF : 0xFF888888);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setPadding(dp(6), dp(9), dp(6), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF141414 : 0xFF060606);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), selected ? 0xFFF2F2F2 : 0xFF1E1E1E);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private LinearLayout.LayoutParams chipParams(boolean rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightGap) lp.rightMargin = dp(7);
        return lp;
    }

    private TextView pillBtn(Context ctx, String label, int textColor, int borderColor) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B0B0B);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), borderColor);
        tv.setBackground(bg);
        return tv;
    }

    private LinearLayout cardBox(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF070707);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF1D1D1D);
        card.setBackground(bg);
        return card;
    }

    private View hairline(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0xFF181818);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private static String formatSignedDb(float db) {
        if (Math.abs(db) < 0.05f) return "0.0 dB";
        return String.format(Locale.US, "%+.1f dB", db);
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }
}
