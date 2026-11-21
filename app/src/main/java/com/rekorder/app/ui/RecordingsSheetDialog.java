// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import android.widget.TextView;
import android.widget.Toast;

import com.rekorder.app.R;
import com.rekorder.app.storage.MarkerStore;
import com.rekorder.app.storage.RecordingStorage;
import com.rekorder.app.storage.WaveformCache;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pitch-black (#000000) AMOLED modal listing WAV, RAW, FLAC, OGG & MP3 recordings
 * with live search/filter, async O(1) duration probes, rename, share, and delete.
 */
public final class RecordingsSheetDialog extends Dialog {

    public interface Listener {
        void onSelectRecording(RecordingStorage.RecordingItem item);
        void onRenameRecording(RecordingStorage.RecordingItem oldItem, RecordingStorage.RecordingItem newItem);
        void onShareRecording(RecordingStorage.RecordingItem item);
        void onDeleteRecording(RecordingStorage.RecordingItem item);
    }

    private final Listener listener;
    private final float dp;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService probeExec = Executors.newSingleThreadExecutor();
    private final Map<String, Long> durationCache = new HashMap<>();

    private List<RecordingStorage.RecordingItem> allItems;
    private String currentQuery = "";
    private LinearLayout listContainer;
    private TextView headerTitle;

    public RecordingsSheetDialog(Context ctx, List<RecordingStorage.RecordingItem> items, Listener listener) {
        super(ctx, R.style.Theme_Rekorder_Dialog);
        this.listener = listener;
        this.allItems = new ArrayList<>(items);
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
        setOnDismissListener(d -> probeExec.shutdownNow());
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
        header.setPadding(dp(20), dp(16), dp(16), dp(10));

        headerTitle = new TextView(ctx);
        headerTitle.setText("RECORDINGS (" + allItems.size() + ")");
        headerTitle.setTextColor(0xFFF2F2F2);
        headerTitle.setTextSize(14f);
        headerTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        headerTitle.setLetterSpacing(0.14f);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("CLOSE");
        close.setTextColor(0xFF888888);
        close.setTextSize(12f);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setPadding(dp(12), dp(8), dp(8), dp(8));
        close.setOnClickListener(v -> dismiss());
        header.addView(close);
        root.addView(header);

        // Folder subtitle
        TextView pathTv = new TextView(ctx);
        pathTv.setText(RecordingStorage.getDisplayPath(ctx));
        pathTv.setTextColor(0xFF5A5A5A);
        pathTv.setTextSize(10.5f);
        pathTv.setTypeface(Typeface.MONOSPACE);
        pathTv.setPadding(dp(20), 0, dp(20), dp(10));
        root.addView(pathTv);

        // Search / Filter bar
        if (!allItems.isEmpty()) {
            EditText searchEt = new EditText(ctx);
            searchEt.setHint("Search name or format (wav, flac, mp3, ogg)...");
            searchEt.setHintTextColor(0xFF555555);
            searchEt.setTextColor(0xFFF0F0F0);
            searchEt.setTextSize(12f);
            searchEt.setTypeface(Typeface.MONOSPACE);
            searchEt.setSingleLine(true);
            searchEt.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable sbg = new GradientDrawable();
            sbg.setColor(0xFF070707);
            sbg.setCornerRadius(dp(7));
            sbg.setStroke(dp(1), 0xFF1F1F1F);
            searchEt.setBackground(sbg);
            searchEt.setText(currentQuery);
            searchEt.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    currentQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                    populateList(ctx);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.setMargins(dp(16), 0, dp(16), dp(10));
            root.addView(searchEt, slp);
        }

        View sep = new View(ctx);
        sep.setBackgroundColor(0xFF181818);
        root.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(0xFF000000);
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        populateList(ctx);
        return root;
    }

    private void populateList(Context ctx) {
        listContainer.removeAllViews();
        List<RecordingStorage.RecordingItem> filtered = new ArrayList<>();
        for (RecordingStorage.RecordingItem item : allItems) {
            if (currentQuery.isEmpty() || item.name.toLowerCase(Locale.US).contains(currentQuery)) {
                filtered.add(item);
            }
        }
        headerTitle.setText("RECORDINGS (" + filtered.size() + ")");

        if (filtered.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(allItems.isEmpty()
                    ? "No .wav, .pcm, .flac, .ogg, or .mp3 recordings yet."
                    : "No recordings match \"" + currentQuery + "\"");
            empty.setTextColor(0xFF555555);
            empty.setTextSize(12.5f);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            listContainer.addView(empty);
            return;
        }

        for (RecordingStorage.RecordingItem item : filtered) {
            listContainer.addView(buildItemRow(ctx, item));
        }
    }

    private View buildItemRow(Context ctx, RecordingStorage.RecordingItem item) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(6), dp(11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF070707);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF1C1C1C);
        row.setBackground(bg);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(item.name);
        name.setTextColor(0xFFF0F0F0);
        name.setTextSize(12.5f);
        name.setTypeface(Typeface.MONOSPACE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        info.addView(name);

        TextView sub = new TextView(ctx);
        sub.setTextColor(0xFF686868);
        sub.setTextSize(10.5f);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setPadding(0, dp(3), 0, 0);
        String key = item.name + ":" + item.sizeBytes;
        Long cachedDur = durationCache.get(key);
        sub.setText(formatSubline(item, cachedDur != null ? cachedDur : -1L));
        info.addView(sub);

        if (cachedDur == null && !probeExec.isShutdown()) {
            probeExec.execute(() -> {
                long dur = RecordingStorage.probeDurationMs(ctx, item);
                durationCache.put(key, dur);
                if (dur > 0L) {
                    mainHandler.post(() -> sub.setText(formatSubline(item, dur)));
                }
            });
        }

        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView renameBtn = iconBtn(ctx, R.drawable.ic_edit, "Rename");
        renameBtn.setOnClickListener(v -> promptRename(ctx, item));
        row.addView(renameBtn);

        ImageView shareBtn = iconBtn(ctx, R.drawable.ic_share, "Share");
        shareBtn.setOnClickListener(v -> {
            if (listener != null) listener.onShareRecording(item);
        });
        row.addView(shareBtn);

        ImageView delBtn = iconBtn(ctx, R.drawable.ic_delete, "Delete");
        delBtn.setColorFilter(0xFF994444);
        delBtn.setOnClickListener(v -> confirmDelete(ctx, item));
        row.addView(delBtn);

        row.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectRecording(item);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private String formatSubline(RecordingStorage.RecordingItem item, long durMs) {
        StringBuilder sb = new StringBuilder();
        if (durMs > 0L) {
            sb.append(formatDurationShort(durMs)).append(" · ");
        }
        sb.append(RecordingStorage.formatBytes(item.sizeBytes));
        if (item.lastModified > 0) {
            sb.append(" · ").append(dateFmt.format(new Date(item.lastModified)));
        }
        return sb.toString();
    }

    private static String formatDurationShort(long ms) {
        long s = ms / 1000L;
        long sec = s % 60L;
        long min = (s / 60L) % 60L;
        long hr = s / 3600L;
        if (hr > 0) return String.format(Locale.US, "%d:%02d:%02d", hr, min, sec);
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    private void promptRename(Context ctx, RecordingStorage.RecordingItem item) {
        String base = item.name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(base);
        et.setSelection(base.length());
        et.setSingleLine(true);
        et.setTextColor(0xFFF2F2F2);
        et.setTypeface(Typeface.MONOSPACE);

        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Rename recording")
                .setView(et)
                .setPositiveButton("Rename", (d, w) -> {
                    String entered = et.getText().toString().trim();
                    RecordingStorage.RecordingItem renamed = RecordingStorage.renameRecording(ctx, item, entered);
                    if (renamed != null) {
                        MarkerStore.rename(ctx, item.name, renamed.name);
                        WaveformCache.rename(ctx, item.name, renamed.name, item.sizeBytes);
                        if (listener != null) listener.onRenameRecording(item, renamed);
                        allItems = new ArrayList<>(RecordingStorage.listRecordings(ctx));
                        populateList(ctx);
                    } else {
                        Toast.makeText(ctx, "Rename failed (name may already exist)", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(Context ctx, RecordingStorage.RecordingItem item) {
        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete recording?")
                .setMessage(item.name)
                .setPositiveButton("Delete", (d, which) -> {
                    MarkerStore.delete(ctx, item.name);
                    WaveformCache.delete(ctx, item.name, item.sizeBytes);
                    if (listener != null) listener.onDeleteRecording(item);
                    allItems = new ArrayList<>(RecordingStorage.listRecordings(ctx));
                    populateList(ctx);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private ImageView iconBtn(Context ctx, int resId, String desc) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(resId);
        iv.setContentDescription(desc);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        return iv;
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }
}
