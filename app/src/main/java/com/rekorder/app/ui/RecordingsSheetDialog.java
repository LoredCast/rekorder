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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pitch-black (#000000) AMOLED modal listing WAV, RAW, FLAC, OGG & MP3 recordings
 * with live search/filter, sorting, multi-select batch share/delete, async O(1) duration probes, and rename.
 */
public final class RecordingsSheetDialog extends Dialog {

    public interface Listener {
        void onSelectRecording(RecordingStorage.RecordingItem item);
        void onRenameRecording(RecordingStorage.RecordingItem oldItem, RecordingStorage.RecordingItem newItem);
        void onShareRecording(RecordingStorage.RecordingItem item);
        void onShareMultiple(List<RecordingStorage.RecordingItem> items);
        void onDeleteRecording(RecordingStorage.RecordingItem item);
    }

    private enum SortMode {
        NEWEST("NEWEST"),
        NAME("NAME"),
        SIZE("SIZE"),
        DURATION("LENGTH");
        final String label;
        SortMode(String label) { this.label = label; }
    }

    private final Listener listener;
    private final float dp;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService probeExec = Executors.newSingleThreadExecutor();
    private final Map<String, Long> durationCache = new HashMap<>();

    private List<RecordingStorage.RecordingItem> allItems;
    private String currentQuery = "";
    private SortMode sortMode = SortMode.NEWEST;
    private boolean selectionMode = false;
    private final Set<String> selectedNames = new HashSet<>();
    private LinearLayout listContainer;
    private TextView headerTitle;
    private TextView selectToggleBtn;
    private TextView sortBtn;
    private LinearLayout batchBar;
    private TextView batchShareBtn;
    private TextView batchDeleteBtn;

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

        if (!allItems.isEmpty()) {
            selectToggleBtn = smallPill(ctx, "SELECT", 0xFF9A9A9A, 0xFF282828);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.rightMargin = dp(8);
            selectToggleBtn.setOnClickListener(v -> {
                selectionMode = !selectionMode;
                if (!selectionMode) selectedNames.clear();
                updateSelectionUi(ctx);
                populateList(ctx);
            });
            header.addView(selectToggleBtn, stLp);
        }

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

        // Search / Filter + Sort bar
        if (!allItems.isEmpty()) {
            LinearLayout searchRow = new LinearLayout(ctx);
            searchRow.setOrientation(LinearLayout.HORIZONTAL);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.setMargins(dp(16), 0, dp(16), dp(10));

            EditText searchEt = new EditText(ctx);
            searchEt.setHint("Search name or format...");
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
            searchRow.addView(searchEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            sortBtn = smallPill(ctx, "SORT: " + sortMode.label, 0xFFE0E0E0, 0xFF282828);
            LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sortLp.leftMargin = dp(8);
            sortBtn.setOnClickListener(v -> {
                SortMode[] vals = SortMode.values();
                sortMode = vals[(sortMode.ordinal() + 1) % vals.length];
                sortBtn.setText("SORT: " + sortMode.label);
                populateList(ctx);
            });
            searchRow.addView(sortBtn, sortLp);
            root.addView(searchRow, srlp);
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

        // Batch action bar (hidden unless multi-select is active)
        batchBar = new LinearLayout(ctx);
        batchBar.setOrientation(LinearLayout.HORIZONTAL);
        batchBar.setGravity(Gravity.CENTER_VERTICAL);
        batchBar.setBackgroundColor(0xFF080808);
        batchBar.setPadding(dp(16), dp(10), dp(16), dp(12));
        batchBar.setVisibility(View.GONE);

        TextView allBtn = smallPill(ctx, "ALL", 0xFFB0B0B0, 0xFF2A2A2A);
        allBtn.setOnClickListener(v -> {
            List<RecordingStorage.RecordingItem> vis = getFilteredSorted();
            if (selectedNames.size() == vis.size()) {
                selectedNames.clear();
            } else {
                for (RecordingStorage.RecordingItem it : vis) selectedNames.add(it.name);
            }
            updateSelectionUi(ctx);
            populateList(ctx);
        });
        batchBar.addView(allBtn);

        View batchSpacer = new View(ctx);
        batchBar.addView(batchSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        batchShareBtn = smallPill(ctx, "SHARE (0)", 0xFF00E5A0, 0xFF00E5A0);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bsLp.rightMargin = dp(8);
        batchShareBtn.setOnClickListener(v -> {
            List<RecordingStorage.RecordingItem> picked = getSelectedItems();
            if (!picked.isEmpty() && listener != null) {
                listener.onShareMultiple(picked);
            }
        });
        batchBar.addView(batchShareBtn, bsLp);

        batchDeleteBtn = smallPill(ctx, "DELETE (0)", 0xFFFF2D2D, 0xFFFF2D2D);
        batchDeleteBtn.setOnClickListener(v -> confirmBatchDelete(ctx));
        batchBar.addView(batchDeleteBtn);

        root.addView(batchBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        populateList(ctx);
        return root;
    }

    private List<RecordingStorage.RecordingItem> getFilteredSorted() {
        List<RecordingStorage.RecordingItem> filtered = new ArrayList<>();
        for (RecordingStorage.RecordingItem item : allItems) {
            if (currentQuery.isEmpty() || item.name.toLowerCase(Locale.US).contains(currentQuery)) {
                filtered.add(item);
            }
        }
        Collections.sort(filtered, (a, b) -> {
            switch (sortMode) {
                case NAME:
                    return a.name.compareToIgnoreCase(b.name);
                case SIZE:
                    return Long.compare(b.sizeBytes, a.sizeBytes);
                case DURATION: {
                    long da = durationCache.getOrDefault(a.name + ":" + a.sizeBytes, 0L);
                    long db = durationCache.getOrDefault(b.name + ":" + b.sizeBytes, 0L);
                    int cmp = Long.compare(db, da);
                    return cmp != 0 ? cmp : Long.compare(b.sizeBytes, a.sizeBytes);
                }
                case NEWEST:
                default:
                    return Long.compare(b.lastModified, a.lastModified);
            }
        });
        return filtered;
    }

    private List<RecordingStorage.RecordingItem> getSelectedItems() {
        List<RecordingStorage.RecordingItem> out = new ArrayList<>();
        for (RecordingStorage.RecordingItem item : allItems) {
            if (selectedNames.contains(item.name)) out.add(item);
        }
        return out;
    }

    private void updateSelectionUi(Context ctx) {
        if (selectToggleBtn != null) {
            selectToggleBtn.setText(selectionMode ? "DONE" : "SELECT");
            selectToggleBtn.setTextColor(selectionMode ? 0xFF00E5A0 : 0xFF9A9A9A);
        }
        if (batchBar != null) {
            batchBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            int n = selectedNames.size();
            batchShareBtn.setText("SHARE (" + n + ")");
            batchDeleteBtn.setText("DELETE (" + n + ")");
        }
    }

    private void confirmBatchDelete(Context ctx) {
        List<RecordingStorage.RecordingItem> picked = getSelectedItems();
        if (picked.isEmpty()) return;
        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete " + picked.size() + " recordings?")
                .setMessage("This permanently removes the selected files.")
                .setPositiveButton("Delete", (d, w) -> {
                    for (RecordingStorage.RecordingItem item : picked) {
                        RecordingStorage.deleteRecording(ctx, item);
                        MarkerStore.delete(ctx, item.name);
                        WaveformCache.delete(ctx, item.name, item.sizeBytes);
                        allItems.remove(item);
                        if (listener != null) listener.onDeleteRecording(item);
                    }
                    selectedNames.clear();
                    selectionMode = false;
                    updateSelectionUi(ctx);
                    populateList(ctx);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void populateList(Context ctx) {
        listContainer.removeAllViews();
        List<RecordingStorage.RecordingItem> filtered = getFilteredSorted();
        headerTitle.setText(selectionMode
                ? ("SELECTED (" + selectedNames.size() + ")")
                : ("RECORDINGS (" + filtered.size() + ")"));

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
        boolean checked = selectionMode && selectedNames.contains(item.name);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(6), dp(11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(checked ? 0xFF0C1712 : 0xFF070707);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), checked ? 0xFF00E5A0 : 0xFF1C1C1C);
        row.setBackground(bg);

        if (selectionMode) {
            TextView chk = new TextView(ctx);
            chk.setText(checked ? "[✓]" : "[ ]");
            chk.setTextColor(checked ? 0xFF00E5A0 : 0xFF555555);
            chk.setTextSize(12f);
            chk.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            chk.setPadding(0, 0, dp(10), 0);
            row.addView(chk);
        }

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

        if (!selectionMode) {
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
        }

        row.setOnClickListener(v -> {
            if (selectionMode) {
                if (!selectedNames.remove(item.name)) selectedNames.add(item.name);
                updateSelectionUi(ctx);
                populateList(ctx);
            } else {
                dismiss();
                if (listener != null) listener.onSelectRecording(item);
            }
        });
        row.setOnLongClickListener(v -> {
            if (!selectionMode) {
                selectionMode = true;
                selectedNames.clear();
                selectedNames.add(item.name);
                updateSelectionUi(ctx);
                populateList(ctx);
                return true;
            }
            return false;
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

    private TextView smallPill(Context ctx, String text, int fgColor, int borderColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(fgColor);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0A0A0A);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), borderColor);
        tv.setBackground(bg);
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }
}
