// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rekorder.app.R;
import com.rekorder.app.audio.AudioConfig;
import com.rekorder.app.dsp.DspConfig;

import java.util.Locale;

/**
 * Simple, pitch-black (#000000) AMOLED sheet for non-destructive post-recording
 * Trim, DSP Preset application, True Peak Normalization, and Format Export (WAV / FLAC / OGG / MP3).
 */
public final class EditExportDialog extends Dialog {

    public interface Listener {
        void onToggleWaveformTrim();
        void onExportRequested(long trimStartMs, long trimEndMs, DspConfig dspToApply,
                               AudioConfig.OutputFormat outFormat, int bitrateKbps);
    }

    private static final AudioConfig.OutputFormat[] EXPORT_FORMATS = {
            AudioConfig.OutputFormat.WAV_24,
            AudioConfig.OutputFormat.WAV_32F,
            AudioConfig.OutputFormat.WAV_16,
            AudioConfig.OutputFormat.FLAC,
            AudioConfig.OutputFormat.OGG_OPUS,
            AudioConfig.OutputFormat.MP3
    };

    private final String fileName;
    private final long durationMs;
    private final long trimStartMs;
    private final long trimEndMs;
    private final DspConfig activePreset;
    private final Listener listener;
    private final float dp;

    private boolean applyPresetDsp;
    private boolean applyNormOnly;
    private AudioConfig.OutputFormat outFormat;
    private int bitrateKbps;

    public EditExportDialog(Context ctx, String fileName, long durationMs,
                            long trimStartMs, long trimEndMs,
                            DspConfig activePreset, AudioConfig currentAudioCfg,
                            Listener listener) {
        super(ctx, R.style.Theme_Rekorder_Dialog);
        this.fileName = fileName;
        this.durationMs = Math.max(1L, durationMs);
        this.trimStartMs = Math.max(0L, Math.min(trimStartMs, durationMs));
        this.trimEndMs = (trimEndMs > this.trimStartMs) ? trimEndMs : this.durationMs;
        this.activePreset = (activePreset != null) ? activePreset : DspConfig.rawBypass();
        this.applyPresetDsp = this.activePreset.hasAnyActiveFx();
        this.applyNormOnly = false;
        this.outFormat = (currentAudioCfg != null && currentAudioCfg.outputFormat != AudioConfig.OutputFormat.RAW_24
                && currentAudioCfg.outputFormat != AudioConfig.OutputFormat.RAW_32F)
                ? currentAudioCfg.outputFormat : AudioConfig.OutputFormat.WAV_24;
        this.bitrateKbps = (currentAudioCfg != null) ? currentAudioCfg.compressedBitrateKbps : 192;
        this.listener = listener;
        this.dp = ctx.getResources().getDisplayMetrics().density;

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

        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(20), dp(16), dp(16), dp(14));

        TextView title = new TextView(ctx);
        title.setText("PROCESS / TRIM / EXPORT");
        title.setTextColor(0xFFF2F2F2);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("CLOSE");
        close.setTextColor(0xFF888888);
        close.setTextSize(12f);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setPadding(dp(12), dp(8), dp(8), dp(8));
        close.setOnClickListener(v -> dismiss());
        hdr.addView(close);
        root.addView(hdr);

        View sep = new View(ctx);
        sep.setBackgroundColor(0xFF1A1A1A);
        root.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(32));

        TextView srcTv = new TextView(ctx);
        srcTv.setText("SOURCE: " + fileName + "  (original stays untouched)");
        srcTv.setTextColor(0xFF7A7A7A);
        srcTv.setTextSize(10.5f);
        srcTv.setTypeface(Typeface.MONOSPACE);
        body.addView(srcTv);

        // 1. Trim Range
        body.addView(sectionLabel(ctx, "1. TRIM SELECTION"));
        boolean isTrimmed = (trimStartMs > 10L || trimEndMs < durationMs - 10L);
        LinearLayout trimCard = cardBox(ctx);
        TextView trimInfo = new TextView(ctx);
        trimInfo.setText(isTrimmed
                ? String.format(Locale.US, "IN %s  ->  OUT %s  (KEEP %s)",
                        fmtMs(trimStartMs), fmtMs(trimEndMs), fmtMs(trimEndMs - trimStartMs))
                : "FULL TAKE (" + fmtMs(durationMs) + ")");
        trimInfo.setTextColor(isTrimmed ? 0xFFFFB800 : 0xFFE0E0E0);
        trimInfo.setTextSize(12f);
        trimInfo.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        trimCard.addView(trimInfo);

        TextView trimBtn = pillBtn(ctx, isTrimmed ? "ADJUST TRIM HANDLES ON WAVEFORM" : "SET TRIM HANDLES ON WAVEFORM",
                0xFFFFB800);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        trimBtn.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onToggleWaveformTrim();
        });
        trimCard.addView(trimBtn, tlp);
        body.addView(trimCard);

        // 2. DSP / Normalization
        body.addView(sectionLabel(ctx, "2. DSP FX & NORMALIZATION"));
        boolean hasPreset = activePreset.hasAnyActiveFx();
        if (hasPreset) {
            body.addView(optionRow(ctx,
                    "Apply Active Preset: " + activePreset.presetName,
                    activePreset.activeModulesSummary(),
                    applyPresetDsp,
                    () -> {
                        applyPresetDsp = !applyPresetDsp;
                        if (applyPresetDsp) applyNormOnly = false;
                        rebuild(ctx);
                    }));
        }
        body.addView(optionRow(ctx,
                "True Peak Normalize Only (-1.0 dBFS)",
                "Clean constant-gain lift without EQ or compression",
                applyNormOnly,
                () -> {
                    applyNormOnly = !applyNormOnly;
                    if (applyNormOnly) applyPresetDsp = false;
                    rebuild(ctx);
                }));

        // 3. Output Format
        body.addView(sectionLabel(ctx, "3. EXPORT FORMAT"));
        LinearLayout fmtGrid1 = new LinearLayout(ctx);
        fmtGrid1.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final AudioConfig.OutputFormat f = EXPORT_FORMATS[i];
            fmtGrid1.addView(chip(ctx, f.shortLabel, outFormat == f, () -> {
                outFormat = f;
                rebuild(ctx);
            }), chipParams(i < 2));
        }
        body.addView(fmtGrid1);

        LinearLayout fmtGrid2 = new LinearLayout(ctx);
        fmtGrid2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams g2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g2p.topMargin = dp(8);
        for (int i = 3; i < 6; i++) {
            final AudioConfig.OutputFormat f = EXPORT_FORMATS[i];
            fmtGrid2.addView(chip(ctx, f.shortLabel, outFormat == f, () -> {
                outFormat = f;
                rebuild(ctx);
            }), chipParams(i < 5));
        }
        body.addView(fmtGrid2, g2p);

        if (outFormat.usesBitrate) {
            body.addView(sectionLabel(ctx, outFormat.shortLabel + " BITRATE"));
            LinearLayout brRow = new LinearLayout(ctx);
            brRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < AudioConfig.BITRATES_KBPS.length; i++) {
                final int kbps = AudioConfig.BITRATES_KBPS[i];
                brRow.addView(chip(ctx, kbps + "k", bitrateKbps == kbps, () -> {
                    bitrateKbps = kbps;
                    rebuild(ctx);
                }), chipParams(i < AudioConfig.BITRATES_KBPS.length - 1));
            }
            body.addView(brRow);
        }

        // Export CTA
        TextView exportBtn = new TextView(ctx);
        exportBtn.setText("EXPORT NEW COPY (" + outFormat.shortLabel
                + (outFormat.usesBitrate ? " " + bitrateKbps + "k" : "") + ")");
        exportBtn.setTextColor(0xFF000000);
        exportBtn.setTextSize(13f);
        exportBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        exportBtn.setGravity(Gravity.CENTER);
        exportBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(0xFF00E5A0);
        ebg.setCornerRadius(dp(8));
        exportBtn.setBackground(ebg);
        exportBtn.setOnClickListener(v -> {
            DspConfig chosen;
            if (applyPresetDsp) {
                chosen = activePreset.withAutoApply(true);
            } else if (applyNormOnly) {
                chosen = new DspConfig("Normalize", true, 0f,
                        false, 0, new float[]{0, 0, 0, 0, 0},
                        false, -18f, 4f, 10f, 120f, 0f,
                        true, -1.0f);
            } else {
                chosen = DspConfig.rawBypass();
            }
            dismiss();
            if (listener != null) {
                listener.onExportRequested(trimStartMs, trimEndMs, chosen, outFormat, bitrateKbps);
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(22);
        body.addView(exportBtn, elp);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void rebuild(Context ctx) {
        View fresh = buildContent(ctx);
        setContentView(fresh);
        fresh.requestApplyInsets();
    }

    private TextView sectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF686868);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setLetterSpacing(0.12f);
        tv.setPadding(dp(2), dp(18), 0, dp(8));
        return tv;
    }

    private LinearLayout cardBox(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF070707);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF1E1E1E);
        box.setBackground(bg);
        return box;
    }

    private View optionRow(Context ctx, String title, String sub, boolean selected, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF0C1411 : 0xFF070707);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), selected ? 0xFF00E5A0 : 0xFF1C1C1C);
        row.setBackground(bg);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(selected ? 0xFFFFFFFF : 0xFFD0D0D0);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        col.addView(t);

        TextView s = new TextView(ctx);
        s.setText(sub);
        s.setTextColor(0xFF6A6A6A);
        s.setTextSize(10.5f);
        s.setTypeface(Typeface.MONOSPACE);
        col.addView(s);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pill = new TextView(ctx);
        pill.setText(selected ? "ON" : "OFF");
        pill.setTextColor(selected ? 0xFF00E5A0 : 0xFF555555);
        pill.setTextSize(11f);
        pill.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(pill);

        row.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView chip(Context ctx, String label, boolean selected, Runnable onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(selected ? 0xFF00E5A0 : 0xFF8A8A8A);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(10), dp(8), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF0B1612 : 0xFF080808);
        bg.setCornerRadius(dp(7));
        bg.setStroke(dp(1), selected ? 0xFF00E5A0 : 0xFF222222);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private TextView pillBtn(Context ctx, String label, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B0B0B);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), color);
        tv.setBackground(bg);
        return tv;
    }

    private LinearLayout.LayoutParams chipParams(boolean rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightGap) lp.rightMargin = dp(8);
        return lp;
    }

    private static String fmtMs(long ms) {
        if (ms < 0) ms = 0;
        long centis = (ms / 10L) % 100L;
        long s = ms / 1000L;
        long sec = s % 60L;
        long min = (s / 60L) % 60L;
        long hr = s / 3600L;
        if (hr > 0) return String.format(Locale.US, "%d:%02d:%02d.%02d", hr, min, sec, centis);
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, centis);
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }
}
