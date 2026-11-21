// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
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
import com.rekorder.app.audio.AudioDeviceHelper;
import com.rekorder.app.audio.NativeMp3Encoder;
import com.rekorder.app.storage.RecordingStorage;

import java.util.List;

/**
 * Pitch-black (#000000) AMOLED audio engine & capture configuration modal.
 * Zero third-party UI libraries; pure Android Views with crisp hairline borders.
 */
public final class SettingsSheetDialog extends Dialog {

    public interface Listener {
        void onConfigUpdated(AudioConfig newConfig);
        void onRequestFolderPicker();
        void onResetFolderDefault();
    }

    private AudioConfig.EngineMode engineMode;
    private AudioConfig.InputPreset inputPreset;
    private AudioConfig.OutputFormat outputFormat;
    private int sampleRate;
    private int channelCount;
    private int deviceId;
    private int outputDeviceId;
    private int outputDeviceType;
    private boolean keepScreenOn;
    private final int visualGain;
    private int compressedBitrateKbps;
    private boolean headphoneMonitor;

    private final Listener listener;
    private final float dp;

    public SettingsSheetDialog(Context ctx, AudioConfig current, Listener listener) {
        super(ctx, R.style.Theme_Rekorder_Dialog);
        this.listener = listener;
        this.dp = ctx.getResources().getDisplayMetrics().density;

        this.engineMode = current.engineMode;
        this.inputPreset = current.inputPreset;
        this.outputFormat = current.outputFormat;
        this.sampleRate = current.sampleRate;
        this.channelCount = current.channelCount;
        this.deviceId = current.deviceId;
        this.outputDeviceId = current.outputDeviceId;
        this.outputDeviceType = current.outputDeviceType;
        this.keepScreenOn = current.keepScreenOn;
        this.visualGain = current.visualGain;
        this.compressedBitrateKbps = current.compressedBitrateKbps;
        this.headphoneMonitor = current.headphoneMonitor;

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

        // Header bar
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(16), dp(14));

        TextView title = new TextView(ctx);
        title.setText("AUDIO ENGINE");
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

        // 1. Capture Driver / Engine
        body.addView(sectionLabel(ctx, "CAPTURE DRIVER"));
        LinearLayout engineGroup = new LinearLayout(ctx);
        engineGroup.setOrientation(LinearLayout.VERTICAL);
        for (AudioConfig.EngineMode m : AudioConfig.EngineMode.values()) {
            engineGroup.addView(optionRow(ctx, m.label, describeEngine(m), engineMode == m, () -> {
                engineMode = m;
                emitAndRebuild(ctx, root);
            }));
        }
        body.addView(engineGroup);

        // 2. Input Source / Preset
        body.addView(sectionLabel(ctx, "INPUT SOURCE (DSP BYPASS)"));
        for (AudioConfig.InputPreset p : AudioConfig.InputPreset.values()) {
            body.addView(optionRow(ctx, p.label, describePreset(p), inputPreset == p, () -> {
                inputPreset = p;
                emitAndRebuild(ctx, root);
            }));
        }

        // 3. Hardware Input Device
        body.addView(sectionLabel(ctx, "MICROPHONE / USB INPUT DEVICE"));
        List<AudioDeviceHelper.InputDeviceItem> devices = AudioDeviceHelper.listInputDevices(ctx);
        for (AudioDeviceHelper.InputDeviceItem d : devices) {
            body.addView(optionRow(ctx, d.label, d.detail, deviceId == d.id, () -> {
                deviceId = d.id;
                emitAndRebuild(ctx, root);
            }));
        }

        // 3b. Independent Audio Output Device (e.g. USB Mic In + Phone Speaker Out!)
        body.addView(sectionLabel(ctx, "AUDIO OUTPUT DEVICE (INDEPENDENT OF INPUT)"));
        List<AudioDeviceHelper.OutputDeviceItem> outDevices = AudioDeviceHelper.listOutputDevices(ctx);
        android.media.AudioDeviceInfo resolvedOut = AudioDeviceHelper.findOutputDevice(
                ctx, outputDeviceId, outputDeviceType);
        for (AudioDeviceHelper.OutputDeviceItem od : outDevices) {
            boolean isSel = (od.id == 0)
                    ? (resolvedOut == null)
                    : (resolvedOut != null && od.id == resolvedOut.getId());
            body.addView(optionRow(ctx, od.label, od.detail, isSel, () -> {
                outputDeviceId = od.id;
                outputDeviceType = (od.info != null) ? od.info.getType() : 0;
                emitAndRebuild(ctx, root);
            }));
        }

        // 4. Lossless / Raw / Compressed Format
        body.addView(sectionLabel(ctx, "RECORDING FORMAT (WAV / RAW / FLAC / OGG / MP3)"));
        for (AudioConfig.OutputFormat f : AudioConfig.OutputFormat.values()) {
            body.addView(optionRow(ctx, f.label, describeFormat(f),
                    outputFormat == f, () -> {
                        outputFormat = f;
                        emitAndRebuild(ctx, root);
                    }));
        }

        if (outputFormat.usesBitrate) {
            body.addView(sectionLabel(ctx, outputFormat.shortLabel + " ENCODING BITRATE"));
            LinearLayout brRow = new LinearLayout(ctx);
            brRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < AudioConfig.BITRATES_KBPS.length; i++) {
                final int kbps = AudioConfig.BITRATES_KBPS[i];
                brRow.addView(chip(ctx, kbps + "k", compressedBitrateKbps == kbps, () -> {
                    compressedBitrateKbps = kbps;
                    emitAndRebuild(ctx, root);
                }), chipParams(i < AudioConfig.BITRATES_KBPS.length - 1));
            }
            body.addView(brRow);
        }

        // 5. Sample Rate chips
        body.addView(sectionLabel(ctx, "SAMPLE RATE"));
        body.addView(buildRateChips(ctx, root));

        // 6. Channels
        body.addView(sectionLabel(ctx, "CHANNELS"));
        LinearLayout chRow = new LinearLayout(ctx);
        chRow.setOrientation(LinearLayout.HORIZONTAL);
        chRow.addView(chip(ctx, "MONO (1 ch)", channelCount == 1, () -> {
            channelCount = 1;
            emitAndRebuild(ctx, root);
        }), chipParams(true));
        chRow.addView(chip(ctx, "STEREO (2 ch)", channelCount == 2, () -> {
            channelCount = 2;
            emitAndRebuild(ctx, root);
        }), chipParams(false));
        body.addView(chRow);

        // 7. Storage Path
        body.addView(sectionLabel(ctx, "RECORDING STORAGE PATH"));
        LinearLayout pathCard = cardBox(ctx);
        TextView pathTxt = new TextView(ctx);
        pathTxt.setText(RecordingStorage.getDisplayPath(ctx));
        pathTxt.setTextColor(0xFFE0E0E0);
        pathTxt.setTextSize(12f);
        pathTxt.setTypeface(Typeface.MONOSPACE);
        pathCard.addView(pathTxt);

        LinearLayout pathBtns = new LinearLayout(ctx);
        pathBtns.setOrientation(LinearLayout.HORIZONTAL);
        pathBtns.setPadding(0, dp(10), 0, 0);

        TextView pickBtn = pillButton(ctx, "CHOOSE FOLDER", 0xFF00E5A0);
        pickBtn.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onRequestFolderPicker();
        });
        pathBtns.addView(pickBtn);

        if (RecordingStorage.getCustomTreeUri(ctx) != null) {
            TextView resetBtn = pillButton(ctx, "RESET DEFAULT", 0xFF888888);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.leftMargin = dp(10);
            resetBtn.setOnClickListener(v -> {
                if (listener != null) listener.onResetFolderDefault();
                View fresh = buildContent(ctx);
                setContentView(fresh);
                fresh.requestApplyInsets();
            });
            pathBtns.addView(resetBtn, rp);
        }
        pathCard.addView(pathBtns);
        body.addView(pathCard);

        // 8. Behavior & Background Reliability
        body.addView(sectionLabel(ctx, "BEHAVIOR & MONITORING"));
        body.addView(optionRow(ctx, "Live Headphone Audio Monitor",
                headphoneMonitor
                        ? "Active when wired/USB/BT headphones are connected (auto-mutes on speaker)"
                        : "Off · visual meter & waveform only (no live audio pass-through)",
                headphoneMonitor, () -> {
                    headphoneMonitor = !headphoneMonitor;
                    emitAndRebuild(ctx, root);
                }));
        body.addView(optionRow(ctx, "Keep AMOLED Screen On While Recording",
                keepScreenOn ? "Enabled (pitch-black pixels draw minimal power)" : "Disabled (screen locks normally, records in background)",
                keepScreenOn, () -> {
                    keepScreenOn = !keepScreenOn;
                    emitAndRebuild(ctx, root);
                }));

        boolean unrestricted = isBatteryUnrestricted(ctx);
        body.addView(optionRow(ctx, "Background Battery Reliability",
                unrestricted
                        ? "UNRESTRICTED · Android will not kill multi-hour takes"
                        : "BATTERY OPTIMIZED · Tap to set Unrestricted for long recordings",
                unrestricted, () -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        ctx.startActivity(i);
                    } catch (Exception ignored) {}
                }));

        // 9. Hardware Diagnostics
        body.addView(sectionLabel(ctx, "HARDWARE HAL DIAGNOSTICS"));
        AudioDeviceHelper.HalDiagnostics diag = AudioDeviceHelper.probeHal(ctx);
        LinearLayout diagCard = cardBox(ctx);
        TextView diagTxt = new TextView(ctx);
        diagTxt.setText(
                "HAL UNPROCESSED SUPPORT : " + (diag.supportsUnprocessed ? "YES (HARDWARE)" : "NOT ADVERTISED") + "\n"
                        + "NATIVE AAUDIO DRIVER    : " + (diag.aaudioAvailable ? "LOADED (librekorder_aaudio.so)" : "FALLBACK (AudioRecord)") + "\n"
                        + "NATIVE MP3 ENCODER      : " + (NativeMp3Encoder.isAvailable() ? "LOADED (librekorder_mp3.so)" : "UNAVAILABLE") + "\n"
                        + "SYSTEM NATIVE RATE      : " + diag.nativeSampleRate + "\n"
                        + "SYSTEM BURST SIZE       : " + diag.framesPerBuffer
        );
        diagTxt.setTextColor(0xFF7A7A7A);
        diagTxt.setTextSize(11f);
        diagTxt.setTypeface(Typeface.MONOSPACE);
        diagTxt.setLineSpacing(dp(4), 1f);
        diagCard.addView(diagTxt);
        body.addView(diagCard);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildRateChips(Context ctx, LinearLayout root) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        int[] r1 = {0, 44100, 48000};
        for (int i = 0; i < r1.length; i++) {
            final int rate = r1[i];
            row1.addView(chip(ctx, AudioConfig.formatSampleRate(rate), sampleRate == rate, () -> {
                sampleRate = rate;
                emitAndRebuild(ctx, root);
            }), chipParams(i < r1.length - 1));
        }

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = dp(8);
        int[] r2 = {96000, 192000};
        for (int i = 0; i < r2.length; i++) {
            final int rate = r2[i];
            row2.addView(chip(ctx, AudioConfig.formatSampleRate(rate), sampleRate == rate, () -> {
                sampleRate = rate;
                emitAndRebuild(ctx, root);
            }), chipParams(i < r2.length - 1));
        }

        col.addView(row1);
        col.addView(row2, r2p);
        return col;
    }

    private void emitAndRebuild(Context ctx, LinearLayout oldRoot) {
        AudioConfig updated = new AudioConfig(
                engineMode, inputPreset, outputFormat, sampleRate,
                channelCount, deviceId, keepScreenOn, visualGain, compressedBitrateKbps,
                headphoneMonitor, outputDeviceId, outputDeviceType
        );
        if (listener != null) {
            listener.onConfigUpdated(updated);
        }
        // Preserve scroll position while refreshing visual selection
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

    private static boolean isBatteryUnrestricted(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    private static String describeFormat(AudioConfig.OutputFormat f) {
        switch (f) {
            case FLAC:
                return "Lossless FLAC (~50% smaller than WAV, built-in Android codec)";
            case OGG_OPUS:
                return "RFC 7845 Ogg Opus (built-in codec + custom Ogg muxer, 48 kHz)";
            case MP3:
                return "MPEG-1 Layer III CBR (built-in native C encoder + ID3v2.3 tags)";
            default:
                return f.hasWavHeader ? "Lossless RIFF/WAVE container" : "Headerless raw interleaved PCM";
        }
    }

    private static String describeEngine(AudioConfig.EngineMode m) {
        switch (m) {
            case AAUDIO_EXCLUSIVE:
                return "Direct hardware MMAP bypass (lowest DSP/mixer interference)";
            case AAUDIO_SHARED:
                return "Native AAudio C driver, shared low-latency stream";
            default:
                return "Android SDK AudioRecord direct UNPROCESSED capture";
        }
    }

    private static String describePreset(AudioConfig.InputPreset p) {
        switch (p) {
            case UNPROCESSED:
                return "Raw mic signal: no AGC, no noise suppression, no AEC";
            case VOICE_RECOGNITION:
                return "Fallback low-DSP path for devices without Unprocessed HAL";
            case CAMCORDER:
                return "Camera mic array path (often enables stereo on phones)";
            default:
                return "Standard microphone input path";
        }
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

    private View optionRow(Context ctx, String title, String subtitle, boolean selected, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF101010 : 0xFF060606);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), selected ? 0xFFF2F2F2 : 0xFF1D1D1D);
        row.setBackground(bg);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(selected ? 0xFFFFFFFF : 0xFFC8C8C8);
        t.setTextSize(13.5f);
        textCol.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextColor(0xFF6E6E6E);
            s.setTextSize(11.5f);
            s.setPadding(0, dp(2), 0, 0);
            textCol.addView(s);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View dot = new View(ctx);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(selected ? 0xFFFF2D2D : 0x00000000);
        dotBg.setStroke(dp(1.5f), selected ? 0xFFFF2D2D : 0xFF3A3A3A);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dpLp.leftMargin = dp(12);
        row.addView(dot, dpLp);

        row.setOnClickListener(v -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView chip(Context ctx, String text, boolean selected, Runnable onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(selected ? 0xFFFFFFFF : 0xFF888888);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setPadding(dp(8), dp(11), dp(8), dp(11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF141414 : 0xFF060606);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), selected ? 0xFFF2F2F2 : 0xFF1E1E1E);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private LinearLayout.LayoutParams chipParams(boolean rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightGap) lp.rightMargin = dp(8);
        return lp;
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

    private TextView pillButton(Context ctx, String label, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0E0E0E);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), 0xFF282828);
        tv.setBackground(bg);
        return tv;
    }

    private View hairline(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0xFF181818);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }
}
