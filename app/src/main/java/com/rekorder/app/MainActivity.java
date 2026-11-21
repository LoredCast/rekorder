// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rekorder.app.audio.AudioCaptureEngine;
import com.rekorder.app.audio.AudioConfig;
import com.rekorder.app.audio.AudioDeviceHelper;
import com.rekorder.app.audio.AudioProcessor;
import com.rekorder.app.audio.CompressedAudioDecoder;
import com.rekorder.app.audio.ScratchRecovery;
import com.rekorder.app.audio.WavWriter;
import com.rekorder.app.audio.WaveformAnalyzer;
import com.rekorder.app.dsp.DspConfig;
import com.rekorder.app.dsp.DspPresetStore;
import com.rekorder.app.storage.MarkerStore;
import com.rekorder.app.storage.RecordingStorage;
import com.rekorder.app.storage.WaveformCache;
import com.rekorder.app.ui.DspSheetDialog;
import com.rekorder.app.ui.EditExportDialog;
import com.rekorder.app.ui.LevelMeterView;
import com.rekorder.app.ui.ProcessingBannerView;
import com.rekorder.app.ui.RecordButtonView;
import com.rekorder.app.ui.RecordingsSheetDialog;
import com.rekorder.app.ui.SettingsSheetDialog;
import com.rekorder.app.ui.WaveformView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity
        implements RecordingService.Callback, PlaybackService.Callback {

    private static final int REQ_RECORD_AUDIO = 101;
    private static final int REQ_PICK_FOLDER = 102;
    public static final String ACTION_QUICK_RECORD = "com.rekorder.app.ACTION_QUICK_RECORD";

    private static final int PENDING_NONE = 0;
    private static final int PENDING_RECORD = 1;
    private static final int PENDING_MONITOR = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private AudioConfig config;
    private DspConfig dspConfig;
    private RecordingService service;
    private boolean serviceBound = false;
    private PlaybackService playbackService;
    private boolean playbackBound = false;
    private int pendingAction = PENDING_NONE;
    private boolean recoveryChecked = false;

    private static final float[] PLAYBACK_SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private Uri previewUri;
    private String previewFileName;
    private long previewFileBytes;
    private WavWriter.AudioFileMeta previewMeta;
    private List<Long> previewMarkers = Collections.emptyList();
    private List<MarkerStore.MarkerEntry> previewMarkerEntries = Collections.emptyList();
    private boolean exportBusy = false;
    private boolean livePlaybackFx = false;
    private boolean trimLoopEnabled = false;

    // UI references
    private LinearLayout rootLayout;
    private LinearLayout mainContent;
    private LinearLayout leftPane;
    private LinearLayout rightPane;
    private TextView statusBadge;
    private ImageView fxTopBtn;
    private TextView pathText;
    private TextView telemetryText;
    private TextView fxStripText;
    private WaveformView waveformView;
    private LevelMeterView levelMeterView;
    private ProcessingBannerView processingBanner;
    private LinearLayout contextActionRow;
    private LinearLayout previewBar;
    private TextView previewNameText;
    private TextView previewSpecText;
    private ImageView previewPlayBtn;
    private TextView speedBtn;
    private TextView fxAuditionBtn;
    private TextView trimToggleBtn;
    private TextView prevMarkBtn;
    private TextView addMarkBtn;
    private TextView nextMarkBtn;
    private TextView timecodeText;
    private TextView subInfoText;
    private ImageView leftTransportBtn;
    private RecordButtonView recordButton;
    private ImageView rightTransportBtn;
    private float dp;

    private AudioManager audioManager;
    private final AudioDeviceCallback usbDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (playbackService != null) {
                playbackService.setPreferredOutputDevice(config.outputDeviceId, config.outputDeviceType);
            }
            for (AudioDeviceInfo d : addedDevices) {
                if (d.isSource() && (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                        || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET)) {
                    mainHandler.post(() -> toast("USB Audio input connected: " + d.getProductName()));
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (playbackService != null) {
                playbackService.setPreferredOutputDevice(config.outputDeviceId, config.outputDeviceType);
            }
        }
    };

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RecordingService.LocalBinder lb = (RecordingService.LocalBinder) binder;
            service = lb.getService();
            serviceBound = true;
            service.setCallback(MainActivity.this);
            runPendingActionIfReady();
            checkAndRecoverOrphanedScratches();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            service = null;
        }
    };

    private final ServiceConnection playbackConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlaybackService.LocalBinder lb = (PlaybackService.LocalBinder) binder;
            playbackService = lb.getService();
            playbackBound = true;
            playbackService.setPreferredOutputDevice(config.outputDeviceId, config.outputDeviceType);
            playbackService.setLiveDsp(livePlaybackFx, dspConfig);
            playbackService.setCallback(MainActivity.this);
            updateSpeedButtonLabel();
            syncActiveBackgroundPlayback();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackBound = false;
            playbackService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.dp = getResources().getDisplayMetrics().density;
        this.config = AudioConfig.load(this);
        this.dspConfig = DspPresetStore.loadActive(this);

        setContentView(buildUi());
        configureAmoledWindow();
        refreshIdleUi();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(usbDeviceCallback, mainHandler);
        }

        bindService(new Intent(this, RecordingService.class), serviceConn, Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, PlaybackService.class), playbackConn, Context.BIND_AUTO_CREATE);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientationLayout(newConfig);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (config != null && config.volumeKeyTrigger && event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
                if (st == RecordingService.State.RECORDING || st == RecordingService.State.PAUSED) {
                    service.togglePause();
                } else if (st == RecordingService.State.IDLE) {
                    onRecordButtonClicked();
                }
                return true;
            } else if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
                if (st == RecordingService.State.RECORDING || st == RecordingService.State.PAUSED) {
                    long ms = service.addMarker();
                    if (ms >= 0L) {
                        toast("Marked " + formatTimecode(ms));
                        refreshContextActionRow();
                    }
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        if (ACTION_QUICK_RECORD.equals(act)) {
            mainHandler.post(() -> {
                RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
                if (st == RecordingService.State.IDLE) {
                    if (!hasRecordPermission()) {
                        pendingAction = PENDING_RECORD;
                        requestAudioAndNotifPermissions();
                    } else if (serviceBound && service != null) {
                        onRecordButtonClicked();
                    } else {
                        pendingAction = PENDING_RECORD;
                    }
                }
            });
            return;
        }
        Uri extUri = null;
        if (Intent.ACTION_VIEW.equals(act)) {
            extUri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(act)) {
            extUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (extUri != null) {
            RecordingStorage.RecordingItem item = RecordingStorage.fromExternalUri(this, extUri);
            if (item != null) {
                mainHandler.post(() -> {
                    if (!isRecordingBusy()) {
                        toast("Opened " + item.name);
                        loadWaveformPreview(item.uri, item.name, item.sizeBytes, false);
                    }
                });
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (audioManager != null) {
            audioManager.unregisterAudioDeviceCallback(usbDeviceCallback);
        }
        // Intentionally do NOT stop PlaybackService here so audio keeps playing in background!
        if (playbackBound) {
            if (playbackService != null) {
                playbackService.setCallback(null);
            }
            unbindService(playbackConn);
            playbackBound = false;
        }
        if (serviceBound) {
            if (service != null) {
                if (service.getState() == RecordingService.State.MONITORING) {
                    service.stopSession();
                }
                service.setCallback(null);
            }
            unbindService(serviceConn);
            serviceBound = false;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void configureAmoledWindow() {
        Window w = getWindow();
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

    private View buildUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFF000000);

        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = sys.top;
                bottom = sys.bottom;
                left = sys.left;
                right = sys.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });

        // 1. Top App Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), dp(10), dp(8), dp(4));

        TextView brand = new TextView(this);
        brand.setText("REKORDER");
        brand.setTextColor(0xFFF2F2F2);
        brand.setTextSize(15f);
        brand.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        brand.setLetterSpacing(0.18f);
        topBar.addView(brand);

        statusBadge = new TextView(this);
        statusBadge.setText("RAW");
        statusBadge.setTextColor(0xFF7A7A7A);
        statusBadge.setTextSize(10f);
        statusBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusBadge.setPadding(dp(7), dp(3), dp(7), dp(3));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(10);
        topBar.addView(statusBadge, badgeLp);
        styleBadge(0xFF1F1F1F, 0xFF7A7A7A);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        fxTopBtn = topIconBtn(R.drawable.ic_fx, "DSP FX & Presets");
        fxTopBtn.setOnClickListener(v -> openDspSheet());
        topBar.addView(fxTopBtn);

        ImageView listBtn = topIconBtn(R.drawable.ic_list, "Recordings");
        listBtn.setOnClickListener(v -> openRecordingsSheet());
        topBar.addView(listBtn);

        ImageView settingsBtn = topIconBtn(R.drawable.ic_settings, "Audio Engine Settings");
        settingsBtn.setOnClickListener(v -> openSettingsSheet());
        topBar.addView(settingsBtn);

        rootLayout.addView(topBar);

        // 2. Custom Storage Path Bar
        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable pathBg = new GradientDrawable();
        pathBg.setColor(0xFF070707);
        pathBg.setCornerRadius(dp(8));
        pathBg.setStroke(dp(1), 0xFF1A1A1A);
        pathRow.setBackground(pathBg);

        ImageView folderIc = new ImageView(this);
        folderIc.setImageResource(R.drawable.ic_folder);
        folderIc.setColorFilter(0xFF7A7A7A);
        pathRow.addView(folderIc, new LinearLayout.LayoutParams(dp(16), dp(16)));

        pathText = new TextView(this);
        pathText.setTextColor(0xFF9A9A9A);
        pathText.setTextSize(11f);
        pathText.setTypeface(Typeface.MONOSPACE);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ptLp.leftMargin = dp(8);
        pathRow.addView(pathText, ptLp);

        pathRow.setOnClickListener(v -> {
            if (isRecordingBusy()) {
                toast("Stop recording to change folder");
            } else {
                launchFolderPicker();
            }
        });

        LinearLayout.LayoutParams pathOuter = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pathOuter.setMargins(dp(16), dp(4), dp(16), dp(4));
        rootLayout.addView(pathRow, pathOuter);

        // 3. Signal Chain Telemetry Bar + Clickable DSP FX Strip
        telemetryText = new TextView(this);
        telemetryText.setTextColor(0xFF6E6E6E);
        telemetryText.setTextSize(10.5f);
        telemetryText.setTypeface(Typeface.MONOSPACE);
        telemetryText.setGravity(Gravity.CENTER);
        telemetryText.setPadding(dp(16), dp(3), dp(16), dp(3));
        telemetryText.setOnClickListener(v -> openSettingsSheet());
        rootLayout.addView(telemetryText);

        fxStripText = new TextView(this);
        fxStripText.setTextSize(10f);
        fxStripText.setTypeface(Typeface.MONOSPACE);
        fxStripText.setGravity(Gravity.CENTER);
        fxStripText.setSingleLine(true);
        fxStripText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fxStripText.setPadding(dp(12), dp(4), dp(12), dp(4));
        fxStripText.setOnClickListener(v -> openDspSheet());
        LinearLayout.LayoutParams fxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fxLp.gravity = Gravity.CENTER_HORIZONTAL;
        fxLp.setMargins(dp(16), dp(2), dp(16), dp(4));
        rootLayout.addView(fxStripText, fxLp);

        // 4-8. Responsive Main Content (Portrait: Vertical | Landscape: 2-Column Side-by-Side)
        mainContent = new LinearLayout(this);
        leftPane = new LinearLayout(this);
        leftPane.setOrientation(LinearLayout.VERTICAL);
        rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setGravity(Gravity.CENTER_VERTICAL);

        // 4. Center Waveform Preview Stage (prominent AMOLED canvas)
        waveformView = new WaveformView(this);
        waveformView.setVisualGain(config.visualGain);
        waveformView.setOnZoomToggleListener(newZoom -> {
            config = config.withVisualGain(newZoom);
            config.save(this);
            toast("Waveform display scale: " + newZoom + "x (does not alter audio)");
        });
        waveformView.setOnScrubListener((fraction, finished) -> {
            if (previewMeta == null || previewUri == null) return;
            long totalMs = previewMeta.durationMs();
            long targetMs = (long) (fraction * totalMs);
            timecodeText.setText(formatTimecode(targetMs));
            if (isPlaybackActiveForPreview()) {
                playbackService.seekFraction(fraction);
            } else if (finished && playbackService != null) {
                playbackService.playTrack(previewUri, previewFileName, previewFileBytes, previewMeta, targetMs);
                updatePreviewPlayIcon(true);
            }
        });
        waveformView.setOnTrimChangeListener((startFrac, endFrac) -> {
            syncLoopRangeIfActive();
            refreshContextActionRow();
        });

        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        waveLp.topMargin = dp(2);
        leftPane.addView(waveformView, waveLp);

        // 5. Studio dBFS + Compressor GR Meter (Mono or Dual Stereo L/R)
        levelMeterView = new LevelMeterView(this);
        levelMeterView.setChannelCount(config.channelCount);
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        meterLp.topMargin = dp(4);
        leftPane.addView(levelMeterView, meterLp);

        // 5b. Live Processing / Loading Progress Banner (non-blocking AMOLED progress card)
        processingBanner = new ProcessingBannerView(this);
        LinearLayout.LayoutParams procLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        procLp.setMargins(dp(16), dp(6), dp(16), dp(2));
        rightPane.addView(processingBanner, procLp);

        // 5c. Contextual Action Strip (+MARK / DISCARD while recording/paused, LOOP / EXPORT TRIM in trim mode)
        contextActionRow = new LinearLayout(this);
        contextActionRow.setOrientation(LinearLayout.HORIZONTAL);
        contextActionRow.setGravity(Gravity.CENTER);
        contextActionRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams ctxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctxLp.setMargins(dp(16), dp(6), dp(16), dp(2));
        rightPane.addView(contextActionRow, ctxLp);

        // 6. Preview Info & Playback Bar
        previewBar = buildPreviewBar();
        previewBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.setMargins(dp(16), dp(6), dp(16), dp(2));
        rightPane.addView(previewBar, prevLp);

        // 7. Large Tabular Monospace Timecode + Sub-Info
        timecodeText = new TextView(this);
        timecodeText.setText("00:00.00");
        timecodeText.setTextColor(0xFFF5F5F5);
        timecodeText.setTextSize(40f);
        timecodeText.setTypeface(Typeface.MONOSPACE);
        timecodeText.setGravity(Gravity.CENTER);
        timecodeText.setPadding(0, dp(8), 0, dp(2));
        rightPane.addView(timecodeText);

        subInfoText = new TextView(this);
        subInfoText.setTextColor(0xFF666666);
        subInfoText.setTextSize(11f);
        subInfoText.setTypeface(Typeface.MONOSPACE);
        subInfoText.setGravity(Gravity.CENTER);
        subInfoText.setPadding(dp(16), 0, dp(16), dp(12));
        rightPane.addView(subInfoText);

        // 8. Bottom Transport Controls
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.setPadding(dp(24), dp(6), dp(24), dp(24));

        leftTransportBtn = circleTransportBtn(R.drawable.ic_monitor, "Monitor Raw Mic");
        leftTransportBtn.setOnClickListener(v -> onLeftTransportClicked());
        transport.addView(leftTransportBtn);

        recordButton = new RecordButtonView(this);
        recordButton.setOnClickListener(v -> onRecordButtonClicked());
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recLp.leftMargin = dp(34);
        recLp.rightMargin = dp(34);
        transport.addView(recordButton, recLp);

        rightTransportBtn = circleTransportBtn(R.drawable.ic_list, "Recordings");
        rightTransportBtn.setOnClickListener(v -> onRightTransportClicked());
        transport.addView(rightTransportBtn);

        rightPane.addView(transport);

        mainContent.addView(leftPane);
        mainContent.addView(rightPane);
        rootLayout.addView(mainContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        applyOrientationLayout(getResources().getConfiguration());

        return rootLayout;
    }

    private void applyOrientationLayout(Configuration cfg) {
        if (mainContent == null || leftPane == null || rightPane == null) return;
        boolean land = (cfg != null && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (land) {
            mainContent.setOrientation(LinearLayout.HORIZONTAL);
            leftPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f));
            rightPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
            if (timecodeText != null) timecodeText.setTextSize(32f);
        } else {
            mainContent.setOrientation(LinearLayout.VERTICAL);
            leftPane.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            rightPane.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (timecodeText != null) timecodeText.setTextSize(40f);
        }
    }

    private LinearLayout buildPreviewBar() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(6), dp(7));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF080808);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF1E1E1E);
        card.setBackground(bg);

        // Row 1: Play + Title/Spec + Edit/Export + Share + Close
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        previewPlayBtn = new ImageView(this);
        previewPlayBtn.setImageResource(R.drawable.ic_play);
        previewPlayBtn.setColorFilter(0xFF00E5A0);
        previewPlayBtn.setPadding(dp(7), dp(7), dp(7), dp(7));
        previewPlayBtn.setOnClickListener(v -> togglePreviewPlayback());
        topRow.addView(previewPlayBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), 0, dp(4), 0);

        previewNameText = new TextView(this);
        previewNameText.setTextColor(0xFFEDEDED);
        previewNameText.setTextSize(12f);
        previewNameText.setTypeface(Typeface.MONOSPACE);
        previewNameText.setSingleLine(true);
        previewNameText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(previewNameText);

        previewSpecText = new TextView(this);
        previewSpecText.setTextColor(0xFF6E6E6E);
        previewSpecText.setTextSize(10f);
        previewSpecText.setTypeface(Typeface.MONOSPACE);
        previewSpecText.setSingleLine(true);
        previewSpecText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(previewSpecText);

        topRow.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView editBtn = new ImageView(this);
        editBtn.setImageResource(R.drawable.ic_edit);
        editBtn.setContentDescription("Process, Trim or Export");
        editBtn.setColorFilter(0xFFFFB800);
        editBtn.setPadding(dp(7), dp(7), dp(7), dp(7));
        editBtn.setOnClickListener(v -> openEditExportDialog());
        topRow.addView(editBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        ImageView shareBtn = new ImageView(this);
        shareBtn.setImageResource(R.drawable.ic_share);
        shareBtn.setColorFilter(0xFF9A9A9A);
        shareBtn.setPadding(dp(7), dp(7), dp(7), dp(7));
        shareBtn.setOnClickListener(v -> {
            if (previewUri != null) {
                shareUri(previewUri, RecordingStorage.guessMime(previewFileName != null ? previewFileName : ".wav"));
            }
        });
        topRow.addView(shareBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        ImageView closeBtn = new ImageView(this);
        closeBtn.setImageResource(R.drawable.ic_close);
        closeBtn.setColorFilter(0xFF666666);
        closeBtn.setPadding(dp(7), dp(7), dp(7), dp(7));
        closeBtn.setOnClickListener(v -> closePreview());
        topRow.addView(closeBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        card.addView(topRow);

        // Row 2: Compact horizontally-scrollable transport micro-controls
        HorizontalScrollView microScroll = new HorizontalScrollView(this);
        microScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout microRow = new LinearLayout(this);
        microRow.setOrientation(LinearLayout.HORIZONTAL);
        microRow.setGravity(Gravity.CENTER_VERTICAL);
        microRow.setPadding(dp(4), dp(6), dp(4), dp(1));

        TextView rewBtn = microChip("-10s", 0xFF9A9A9A);
        rewBtn.setOnClickListener(v -> skipPreviewBy(-10_000L));
        microRow.addView(rewBtn, microChipLp());

        TextView fwdBtn = microChip("+10s", 0xFF9A9A9A);
        fwdBtn.setOnClickListener(v -> skipPreviewBy(10_000L));
        microRow.addView(fwdBtn, microChipLp());

        speedBtn = microChip("1.0x", 0xFF00E5A0);
        speedBtn.setOnClickListener(v -> cyclePlaybackSpeed());
        microRow.addView(speedBtn, microChipLp());

        fxAuditionBtn = microChip("FX", 0xFF777777);
        fxAuditionBtn.setOnClickListener(v -> toggleLivePlaybackFx());
        microRow.addView(fxAuditionBtn, microChipLp());

        trimToggleBtn = microChip("TRIM", 0xFFFFB800);
        trimToggleBtn.setOnClickListener(v -> toggleWaveformTrimMode());
        microRow.addView(trimToggleBtn, microChipLp());

        prevMarkBtn = microChip("<MK", 0xFFFFB800);
        prevMarkBtn.setVisibility(View.GONE);
        prevMarkBtn.setOnClickListener(v -> jumpToPrevMarker());
        microRow.addView(prevMarkBtn, microChipLp());

        addMarkBtn = microChip("+MK", 0xFFFFB800);
        addMarkBtn.setOnClickListener(v -> addOrEditPlaybackMarker());
        microRow.addView(addMarkBtn, microChipLp());

        nextMarkBtn = microChip("MK>", 0xFFFFB800);
        nextMarkBtn.setVisibility(View.GONE);
        nextMarkBtn.setOnClickListener(v -> jumpToNextMarker());
        microRow.addView(nextMarkBtn, microChipLp());

        microScroll.addView(microRow);
        card.addView(microScroll);
        return card;
    }

    private TextView microChip(String label, int color) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0E0E0E);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), 0xFF222222);
        tv.setBackground(bg);
        return tv;
    }

    private LinearLayout.LayoutParams microChipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        return lp;
    }

    /* ---------- User Actions ---------- */

    private void onRecordButtonClicked() {
        RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
        if (st == RecordingService.State.FINALIZING) return;
        if (st == RecordingService.State.RECORDING || st == RecordingService.State.PAUSED) {
            service.stopSession();
            return;
        }
        stopBackgroundPlayback();
        closePreviewSilent();
        if (!hasRecordPermission()) {
            pendingAction = PENDING_RECORD;
            requestAudioAndNotifPermissions();
            return;
        }
        if (service != null) {
            waveformView.clearLive();
            levelMeterView.reset();
            service.startRecording(config, dspConfig);
        } else {
            pendingAction = PENDING_RECORD;
        }
    }

    private void onLeftTransportClicked() {
        RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
        if (st == RecordingService.State.FINALIZING) return;
        if (st == RecordingService.State.RECORDING || st == RecordingService.State.PAUSED) {
            service.togglePause();
            return;
        }
        if (st == RecordingService.State.MONITORING) {
            service.stopSession();
            return;
        }
        stopBackgroundPlayback();
        closePreviewSilent();
        if (!hasRecordPermission()) {
            pendingAction = PENDING_MONITOR;
            requestAudioAndNotifPermissions();
            return;
        }
        if (service != null) {
            waveformView.clearLive();
            levelMeterView.reset();
            service.startMonitoring(config, dspConfig);
        } else {
            pendingAction = PENDING_MONITOR;
        }
    }

    private void onRightTransportClicked() {
        if (isRecordingBusy()) {
            toast("Stop recording to browse files");
            return;
        }
        if (previewUri != null && previewMeta != null) {
            togglePreviewPlayback();
        } else {
            openRecordingsSheet();
        }
    }

    private void togglePreviewPlayback() {
        if (previewUri == null || previewMeta == null || playbackService == null) return;
        if (isPlaybackPlayingForPreview()) {
            playbackService.pausePlayback();
            updatePreviewPlayIcon(false);
        } else if (isPlaybackActiveForPreview()) {
            playbackService.resumePlayback();
            updatePreviewPlayIcon(true);
        } else {
            playbackService.playTrack(previewUri, previewFileName, previewFileBytes, previewMeta, -1L);
            updatePreviewPlayIcon(true);
        }
    }

    private boolean isPlaybackActiveForPreview() {
        return playbackService != null && playbackService.isActive()
                && previewUri != null && previewUri.equals(playbackService.getCurrentUri());
    }

    private boolean isPlaybackPlayingForPreview() {
        return playbackService != null && playbackService.isPlaying()
                && previewUri != null && previewUri.equals(playbackService.getCurrentUri());
    }

    private void stopBackgroundPlayback() {
        if (playbackService != null) {
            playbackService.stopPlayback();
        }
    }

    private void syncActiveBackgroundPlayback() {
        if (playbackService == null || !playbackService.isActive()) return;
        if (isRecordingBusy()) return;
        Uri playingUri = playbackService.getCurrentUri();
        if (playingUri == null) return;
        if (playingUri.equals(previewUri) && previewMeta != null) {
            updatePreviewPlayIcon(playbackService.isPlaying());
            return;
        }
        loadWaveformPreview(playingUri, playbackService.getCurrentFileName(),
                playbackService.getCurrentFileBytes(), false, true);
    }

    private void checkAndRecoverOrphanedScratches() {
        if (recoveryChecked) return;
        recoveryChecked = true;
        if (service != null && service.getState() != RecordingService.State.IDLE) return;
        final File activeScratch = (service != null) ? service.getActiveScratchFile() : null;
        ioExecutor.execute(() -> {
            List<File> orphans = ScratchRecovery.findOrphanedScratches(this, activeScratch);
            if (orphans.isEmpty()) return;
            for (File f : orphans) {
                mainHandler.post(() -> {
                    styleBadge(0xFFFFB800, 0xFFFFB800);
                    statusBadge.setText("RECOVER");
                    processingBanner.showProgress("RECOVERING INTERRUPTED AUDIO",
                            "Converting saved capture buffer to lossless 24-bit WAV...", 0, 0xFFFFB800);
                });
                try {
                    ScratchRecovery.RecoveredTake rec = ScratchRecovery.recoverScratch(this, f,
                            (done, total, pct) -> mainHandler.post(() -> processingBanner.showProgress(
                                    "RECOVERING INTERRUPTED AUDIO",
                                    RecordingStorage.formatBytes(done) + " / " + RecordingStorage.formatBytes(total),
                                    pct, 0xFFFFB800)));
                    if (rec != null) {
                        mainHandler.post(() -> {
                            processingBanner.hide();
                            toast("Recovered interrupted recording: " + rec.displayName);
                            if (!isRecordingBusy() && previewUri == null) {
                                loadWaveformPreview(rec.uri, rec.displayName, rec.sizeBytes, false);
                            }
                        });
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        processingBanner.hide();
                        toast("Recovery postponed (free up storage): " + e.getMessage());
                    });
                }
            }
        });
    }

    private void openDspSheet() {
        if (isRecordingBusy()) {
            toast("Stop recording before changing DSP presets");
            return;
        }
        DspSheetDialog dlg = new DspSheetDialog(this, dspConfig, newDsp -> {
            dspConfig = newDsp;
            DspPresetStore.saveActive(this, dspConfig);
            if (service != null && service.getState() == RecordingService.State.MONITORING) {
                service.updateLiveDspConfig(dspConfig);
            }
            if (playbackService != null && livePlaybackFx) {
                playbackService.setLiveDsp(true, dspConfig);
            }
            refreshFxStrip();
        });
        dlg.show();
    }

    private void openSettingsSheet() {
        if (isRecordingBusy()) {
            toast("Stop recording before changing audio engine settings");
            return;
        }
        if (service != null && service.getState() == RecordingService.State.MONITORING) {
            service.stopSession();
        }
        SettingsSheetDialog dlg = new SettingsSheetDialog(this, config, new SettingsSheetDialog.Listener() {
            @Override
            public void onConfigUpdated(AudioConfig newConfig) {
                config = newConfig;
                config.save(MainActivity.this);
                if (playbackService != null) {
                    playbackService.setPreferredOutputDevice(config.outputDeviceId, config.outputDeviceType);
                }
                refreshIdleUi();
            }

            @Override
            public void onRequestFolderPicker() {
                launchFolderPicker();
            }

            @Override
            public void onResetFolderDefault() {
                RecordingStorage.setCustomTreeUri(MainActivity.this, null);
                refreshIdleUi();
            }
        });
        dlg.show();
    }

    private void openRecordingsSheet() {
        if (isRecordingBusy()) {
            toast("Stop recording to browse files");
            return;
        }
        if (service != null && service.getState() == RecordingService.State.MONITORING) {
            service.stopSession();
        }
        List<RecordingStorage.RecordingItem> items = RecordingStorage.listRecordings(this);
        RecordingsSheetDialog dlg = new RecordingsSheetDialog(this, items, new RecordingsSheetDialog.Listener() {
            @Override
            public void onSelectRecording(RecordingStorage.RecordingItem item) {
                loadWaveformPreview(item.uri, item.name, item.sizeBytes, false);
            }

            @Override
            public void onRenameRecording(RecordingStorage.RecordingItem oldItem, RecordingStorage.RecordingItem newItem) {
                if (oldItem.uri.equals(previewUri)) {
                    previewUri = newItem.uri;
                    previewFileName = newItem.name;
                    previewNameText.setText(newItem.name);
                }
            }

            @Override
            public void onShareRecording(RecordingStorage.RecordingItem item) {
                shareUri(item.uri, item.mimeType);
            }

            @Override
            public void onShareMultiple(List<RecordingStorage.RecordingItem> items) {
                shareMultipleUris(items);
            }

            @Override
            public void onDeleteRecording(RecordingStorage.RecordingItem item) {
                if (playbackService != null && item.uri.equals(playbackService.getCurrentUri())) {
                    stopBackgroundPlayback();
                }
                if (item.uri.equals(previewUri)) {
                    closePreview();
                }
                RecordingStorage.deleteRecording(MainActivity.this, item);
            }
        });
        dlg.show();
    }

    private void launchFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER);
        } catch (Exception e) {
            toast("No system folder picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri tree = data.getData();
            if (tree != null) {
                RecordingStorage.setCustomTreeUri(this, tree);
                refreshIdleUi();
                toast("Storage path updated");
            }
        }
    }

    /* ---------- Permissions ---------- */

    private boolean hasRecordPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioAndNotifPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS},
                    REQ_RECORD_AUDIO
            );
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (hasRecordPermission()) {
                runPendingActionIfReady();
            } else {
                pendingAction = PENDING_NONE;
                toast("Microphone permission is required for audio capture");
            }
        }
    }

    private void runPendingActionIfReady() {
        if (!serviceBound || service == null || !hasRecordPermission()) return;
        int act = pendingAction;
        pendingAction = PENDING_NONE;
        if (act == PENDING_RECORD) {
            waveformView.clearLive();
            levelMeterView.reset();
            service.startRecording(config, dspConfig);
        } else if (act == PENDING_MONITOR) {
            waveformView.clearLive();
            levelMeterView.reset();
            service.startMonitoring(config, dspConfig);
        }
    }

    /* ---------- RecordingService Callbacks ---------- */

    @Override
    public void onStateChanged(RecordingService.State st, AudioCaptureEngine.StreamInfo info, AudioConfig cfg) {
        mainHandler.post(() -> applyServiceState(st, info, cfg));
    }

    @Override
    public void onLiveMeter(float peakL, float rmsL, float peakR, float rmsR, int channels,
                            boolean clipped, float gainReductionDb,
                            long elapsedMs, long fileBytes, int totalDrops, int totalClips) {
        mainHandler.post(() -> {
            RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
            if (st != RecordingService.State.RECORDING
                    && st != RecordingService.State.PAUSED
                    && st != RecordingService.State.MONITORING) {
                return;
            }
            float maxPeak = (channels == 2) ? Math.max(peakL, peakR) : peakL;
            float maxRms = (channels == 2) ? Math.max(rmsL, rmsR) : rmsL;
            if (st != RecordingService.State.PAUSED) {
                waveformView.pushLiveSample(maxPeak, maxRms, clipped);
            }
            levelMeterView.updateLevelStereo(peakL, rmsL, peakR, rmsR, channels, clipped, totalClips, gainReductionDb);
            if (st == RecordingService.State.RECORDING || st == RecordingService.State.PAUSED) {
                timecodeText.setText(formatTimecode(elapsedMs));
                String dropStr = totalDrops > 0 ? "  ·  " + totalDrops + " XRUN" : "  ·  0 drops";
                String voxStr = (service != null && service.isVoxWaiting()) ? "  ·  VOX WAIT" : "";
                subInfoText.setText(RecordingStorage.formatBytes(fileBytes) + dropStr + voxStr);
                subInfoText.setTextColor(totalDrops > 0 ? 0xFFFFB800
                        : (!voxStr.isEmpty() ? 0xFF00E5A0 : 0xFF7A7A7A));
                AudioCaptureEngine.StreamInfo activeInfo = service.getActiveStreamInfo();
                if (activeInfo != null) {
                    telemetryText.setText(activeInfo.telemetryLine(config, totalDrops));
                }
            } else if (st == RecordingService.State.MONITORING) {
                String hp = config.headphoneMonitor ? " · HP AUDIO" : "";
                subInfoText.setText(dspConfig.hasRealTimeFx()
                        ? "MONITORING WITH LIVE FX (" + dspConfig.presetName + ")" + hp
                        : "MONITORING RAW INPUT" + hp + " · NO DISK WRITE");
                subInfoText.setTextColor(0xFF00E5A0);
            }
        });
    }

    @Override
    public void onProcessingProgress(String stage, String detail, int percent) {
        mainHandler.post(() -> {
            styleBadge(0xFFFFB800, 0xFFFFB800);
            statusBadge.setText(percent >= 0 ? ("PROC " + percent + "%") : "PROC");
            previewBar.setVisibility(View.GONE);
            processingBanner.showProgress(stage, detail, percent, 0xFFFFB800);
            subInfoText.setText(detail != null && !detail.isEmpty() ? detail : stage);
            subInfoText.setTextColor(0xFFFFB800);
        });
    }

    @Override
    public void onRecordingSaved(Uri uri, String fileName, long fileBytes, String note) {
        mainHandler.post(() -> {
            String msg = "Saved " + fileName + " (" + RecordingStorage.formatBytes(fileBytes) + ")"
                    + (note != null ? " · " + note : "");
            toast(msg);
            if (!isRecordingBusy()) {
                processingBanner.hide();
                loadWaveformPreview(uri, fileName, fileBytes, false);
            }
        });
    }

    @Override
    public void onWarning(String message) {
        mainHandler.post(() -> toast(message));
    }

    @Override
    public void onError(String message) {
        mainHandler.post(() -> {
            processingBanner.hide();
            toast("Audio error: " + message);
        });
    }

    /* ---------- Waveform Preview & Playback ---------- */

    private void loadWaveformPreview(Uri uri, String fileName, long fileBytes, boolean autoPlay) {
        loadWaveformPreview(uri, fileName, fileBytes, autoPlay, false);
    }

    private void loadWaveformPreview(Uri uri, String fileName, long fileBytes,
                                     boolean autoPlay, boolean preservePlayback) {
        if (!preservePlayback) {
            stopBackgroundPlayback();
        }
        previewUri = uri;
        previewFileName = fileName;
        previewFileBytes = fileBytes > 0 ? fileBytes : RecordingStorage.querySize(this, uri);
        previewMeta = null;
        previewMarkerEntries = MarkerStore.loadEntries(this, fileName);
        previewMarkers = MarkerStore.load(this, fileName);
        trimLoopEnabled = false;
        if (playbackService != null) playbackService.setLoopRange(0L, 0L);

        previewBar.setVisibility(View.VISIBLE);
        previewNameText.setText(fileName);
        previewSpecText.setText("Analyzing waveform...");
        updatePreviewPlayIcon(preservePlayback && isPlaybackPlayingForPreview());
        updateSpeedButtonLabel();
        updateFxAuditionChip();
        updateMarkerButtonsVisibility();
        waveformView.setTrimMode(false);
        refreshContextActionRow();
        levelMeterView.reset();

        final Uri targetUri = uri;
        final long knownSize = previewFileBytes;

        // Instant hit from binary WaveformCache (<5 ms for multi-hour takes!)
        WaveformAnalyzer.WaveformData cached = WaveformCache.get(this, fileName, knownSize);
        if (cached != null) {
            applyLoadedWaveform(targetUri, fileName, knownSize, cached, autoPlay, preservePlayback);
            return;
        }

        waveformView.setPreviewLoading(true);
        styleBadge(0xFF00E5A0, 0xFF00E5A0);
        statusBadge.setText("LOAD");
        processingBanner.showProgress("BUILDING WAVEFORM PREVIEW",
                fileName + " · " + RecordingStorage.formatBytes(previewFileBytes), 0, 0xFF00E5A0);

        ioExecutor.execute(() -> {
            try {
                WaveformAnalyzer.WaveformData data;
                if (CompressedAudioDecoder.isCompressedName(fileName)) {
                    data = CompressedAudioDecoder.analyze(this, targetUri, fileName, 320,
                            (done, total, pct) -> mainHandler.post(() -> {
                                if (!targetUri.equals(previewUri)) return;
                                statusBadge.setText(pct >= 0 ? ("LOAD " + pct + "%") : "LOAD");
                                previewSpecText.setText(pct >= 0
                                        ? ("Decoding waveform · " + pct + "%")
                                        : "Decoding waveform...");
                                waveformView.setPreviewLoadingPercent(pct);
                                String det = fileName + " · " + RecordingStorage.formatBytes(knownSize);
                                processingBanner.showProgress("DECODING WAVEFORM PREVIEW", det, pct, 0xFF00E5A0);
                            }));
                } else {
                    try (InputStream in = RecordingStorage.openInputStream(this, targetUri)) {
                        data = WaveformAnalyzer.analyze(in, knownSize, fileName, 320,
                                (done, total, pct) -> mainHandler.post(() -> {
                                    if (!targetUri.equals(previewUri)) return;
                                    statusBadge.setText(pct >= 0 ? ("LOAD " + pct + "%") : "LOAD");
                                    previewSpecText.setText(pct >= 0
                                            ? ("Analyzing waveform · " + pct + "%")
                                            : "Analyzing waveform...");
                                    waveformView.setPreviewLoadingPercent(pct);
                                    String det = RecordingStorage.formatBytes(done) + " / "
                                            + RecordingStorage.formatBytes(total);
                                    processingBanner.showProgress("BUILDING WAVEFORM PREVIEW", det, pct, 0xFF00E5A0);
                                }));
                    }
                }
                WaveformCache.put(this, fileName, knownSize, data);
                mainHandler.post(() -> applyLoadedWaveform(targetUri, fileName, knownSize, data, autoPlay, preservePlayback));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!targetUri.equals(previewUri)) return;
                    processingBanner.hide();
                    statusBadge.setText("PREVIEW");
                    waveformView.setPreviewLoading(false);
                    previewSpecText.setText("Cannot decode: " + e.getMessage());
                });
            }
        });
    }

    private void applyLoadedWaveform(Uri targetUri, String fileName, long knownSize,
                                     WaveformAnalyzer.WaveformData data,
                                     boolean autoPlay, boolean preservePlayback) {
        if (!targetUri.equals(previewUri)) return;
        processingBanner.hide();
        previewMeta = data.meta;
        waveformView.setPreviewWaveform(data.peaks, data.rms);
        waveformView.setMarkers(previewMarkers, data.meta.durationMs());
        nextMarkBtn.setVisibility(previewMarkers.isEmpty() ? View.GONE : View.VISIBLE);
        String markSuffix = previewMarkers.isEmpty() ? "" : (" · " + previewMarkers.size() + " mk");
        previewSpecText.setText(data.meta.summaryLabel() + " · " + RecordingStorage.formatBytes(knownSize) + markSuffix);
        boolean alreadyPlaying = preservePlayback && isPlaybackPlayingForPreview();
        styleBadge(0xFF00E5A0, 0xFF00E5A0);
        statusBadge.setText(alreadyPlaying ? "PLAY" : "PREVIEW");
        if (!alreadyPlaying) {
            timecodeText.setText("00:00.00");
            subInfoText.setText("DURATION " + formatTimecode(data.meta.durationMs())
                    + "  ·  SCRUB OR PINCH TO ZOOM");
            subInfoText.setTextColor(0xFF7A7A7A);
        }
        updatePreviewPlayIcon(alreadyPlaying);
        if (autoPlay && playbackService != null) {
            playbackService.playTrack(targetUri, fileName, knownSize, data.meta, 0L);
            updatePreviewPlayIcon(true);
        }
    }

    /* ---------- Preview Tools: Skip, Speed, Markers, Trim & Offline FX/Export ---------- */

    private void skipPreviewBy(long deltaMs) {
        if (previewMeta == null || previewUri == null || playbackService == null) return;
        if (isPlaybackActiveForPreview()) {
            playbackService.seekByDeltaMs(deltaMs);
        } else {
            long target = Math.max(0L, Math.min(previewMeta.durationMs(), deltaMs));
            playbackService.playTrack(previewUri, previewFileName, previewFileBytes, previewMeta, target);
            updatePreviewPlayIcon(true);
        }
    }

    private void cyclePlaybackSpeed() {
        float cur = (playbackService != null) ? playbackService.getPlaybackSpeed() : 1.0f;
        int nextIdx = 2; // 1.0x
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            if (Math.abs(PLAYBACK_SPEEDS[i] - cur) < 0.05f) {
                nextIdx = (i + 1) % PLAYBACK_SPEEDS.length;
                break;
            }
        }
        float next = PLAYBACK_SPEEDS[nextIdx];
        if (playbackService != null) playbackService.setPlaybackSpeed(next);
        updateSpeedButtonLabel();
        toast(String.format(Locale.US, "Playback speed: %.2gx", next));
    }

    private void updateSpeedButtonLabel() {
        if (speedBtn == null) return;
        float cur = (playbackService != null) ? playbackService.getPlaybackSpeed() : 1.0f;
        speedBtn.setText(cur == 1.0f ? "1.0x" : String.format(Locale.US, "%.2gx", cur));
    }

    private void toggleLivePlaybackFx() {
        if (!livePlaybackFx && !dspConfig.hasAnyRealTimeModuleEnabled()) {
            toast("Enable Preamp, Gate, De-Hum, EQ, or Comp in DSP FX first");
            openDspSheet();
            return;
        }
        livePlaybackFx = !livePlaybackFx;
        if (playbackService != null) {
            playbackService.setLiveDsp(livePlaybackFx, dspConfig);
        }
        updateFxAuditionChip();
        toast(livePlaybackFx ? "Live playback FX ON (" + dspConfig.presetName + ")" : "Live playback FX bypassed (raw)");
    }

    private void updateFxAuditionChip() {
        if (fxAuditionBtn == null) return;
        fxAuditionBtn.setText(livePlaybackFx ? "FX ON" : "FX");
        fxAuditionBtn.setTextColor(livePlaybackFx ? 0xFF00E5A0 : 0xFF777777);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(livePlaybackFx ? 0xFF0A1612 : 0xFF0E0E0E);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), livePlaybackFx ? 0xFF00E5A0 : 0xFF222222);
        fxAuditionBtn.setBackground(bg);
    }

    private void updateMarkerButtonsVisibility() {
        int vis = previewMarkers.isEmpty() ? View.GONE : View.VISIBLE;
        if (prevMarkBtn != null) prevMarkBtn.setVisibility(vis);
        if (nextMarkBtn != null) nextMarkBtn.setVisibility(vis);
    }

    private void syncWaveformMarkers() {
        if (previewMeta == null) return;
        waveformView.setMarkers(previewMarkers, previewMeta.durationMs());
        updateMarkerButtonsVisibility();
    }

    private void jumpToPrevMarker() {
        if (previewMeta == null || previewUri == null || previewMarkers.isEmpty() || playbackService == null) return;
        long curPos = isPlaybackActiveForPreview()
                ? playbackService.getCurrentPositionMs()
                : (long) (waveformView.getPlayheadFraction() * previewMeta.durationMs());
        long target = MarkerStore.findPrevMarker(previewMarkers, curPos);
        if (target < 0L) target = previewMarkers.get(previewMarkers.size() - 1);
        seekOrPlayPreviewAt(target);
        toast("Marker · " + formatMarkerToast(target));
    }

    private void jumpToNextMarker() {
        if (previewMeta == null || previewUri == null || previewMarkers.isEmpty() || playbackService == null) return;
        long curPos = isPlaybackActiveForPreview()
                ? playbackService.getCurrentPositionMs()
                : (long) (waveformView.getPlayheadFraction() * previewMeta.durationMs());
        long target = MarkerStore.findNextMarker(previewMarkers, curPos);
        if (target < 0L) target = previewMarkers.get(0);
        seekOrPlayPreviewAt(target);
        toast("Marker · " + formatMarkerToast(target));
    }

    private void seekOrPlayPreviewAt(long targetMs) {
        if (isPlaybackActiveForPreview()) {
            playbackService.seekToMs(targetMs);
        } else if (playbackService != null) {
            playbackService.playTrack(previewUri, previewFileName, previewFileBytes, previewMeta, targetMs);
            updatePreviewPlayIcon(true);
        }
        if (previewMeta != null && previewMeta.durationMs() > 0) {
            waveformView.setPlayheadFraction((float) targetMs / (float) previewMeta.durationMs());
            timecodeText.setText(formatTimecode(targetMs));
        }
    }

    private String formatMarkerToast(long ms) {
        for (MarkerStore.MarkerEntry e : previewMarkerEntries) {
            if (Math.abs(e.timeMs - ms) < 5L && !e.label.isEmpty()) {
                return e.label + " (" + formatTimecode(ms) + ")";
            }
        }
        return formatTimecode(ms);
    }

    private void addOrEditPlaybackMarker() {
        if (previewMeta == null || previewFileName == null) return;
        long posMs = isPlaybackActiveForPreview()
                ? playbackService.getCurrentPositionMs()
                : (long) (waveformView.getPlayheadFraction() * previewMeta.durationMs());
        int nearIdx = MarkerStore.findNearestIndex(previewMarkerEntries, posMs, 1000L);
        if (nearIdx >= 0) {
            final MarkerStore.MarkerEntry existing = previewMarkerEntries.get(nearIdx);
            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setHint("Marker label (optional)");
            input.setText(existing.label);
            input.setSelection(existing.label.length());
            input.setTextColor(0xFFF2F2F2);
            input.setHintTextColor(0xFF666666);
            LinearLayout wrap = new LinearLayout(this);
            wrap.setPadding(dp(18), dp(10), dp(18), dp(4));
            wrap.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Marker @ " + formatTimecode(existing.timeMs))
                    .setView(wrap)
                    .setPositiveButton("Save Label", (d, w) -> {
                        List<MarkerStore.MarkerEntry> updated = new ArrayList<>(previewMarkerEntries);
                        updated.set(nearIdx, new MarkerStore.MarkerEntry(existing.timeMs, input.getText().toString()));
                        MarkerStore.saveEntries(this, previewFileName, updated);
                        previewMarkerEntries = updated;
                        previewMarkers = MarkerStore.load(this, previewFileName);
                        syncWaveformMarkers();
                    })
                    .setNeutralButton("Delete", (d, w) -> {
                        List<MarkerStore.MarkerEntry> updated = new ArrayList<>(previewMarkerEntries);
                        updated.remove(nearIdx);
                        MarkerStore.saveEntries(this, previewFileName, updated);
                        previewMarkerEntries = updated;
                        previewMarkers = MarkerStore.load(this, previewFileName);
                        syncWaveformMarkers();
                        toast("Marker removed");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            previewMarkerEntries = MarkerStore.addMarker(this, previewFileName, posMs, "");
            previewMarkers = MarkerStore.load(this, previewFileName);
            syncWaveformMarkers();
            toast("Marked " + formatTimecode(posMs) + " (tap +MK again here to name/delete)");
        }
    }

    private void syncLoopRangeIfActive() {
        if (playbackService == null) return;
        if (trimLoopEnabled && previewMeta != null && waveformView.isTrimMode()) {
            long dur = previewMeta.durationMs();
            long sMs = (long) (waveformView.getTrimStartFraction() * dur);
            long eMs = (long) (waveformView.getTrimEndFraction() * dur);
            playbackService.setLoopRange(sMs, eMs);
        } else {
            playbackService.setLoopRange(0L, 0L);
        }
    }

    private void toggleWaveformTrimMode() {
        if (previewMeta == null) return;
        boolean next = !waveformView.isTrimMode();
        waveformView.setTrimMode(next);
        if (!next) {
            trimLoopEnabled = false;
            syncLoopRangeIfActive();
        } else {
            toast("Drag amber handles on waveform to set trim/loop range");
        }
        refreshContextActionRow();
    }

    private void openEditExportDialog() {
        if (previewUri == null || previewMeta == null) return;
        if (exportBusy || isRecordingBusy()) {
            toast("Busy processing audio");
            return;
        }
        long dur = previewMeta.durationMs();
        long startMs = waveformView.isTrimMode() ? (long) (waveformView.getTrimStartFraction() * dur) : 0L;
        long endMs = waveformView.isTrimMode() ? (long) (waveformView.getTrimEndFraction() * dur) : dur;

        EditExportDialog dlg = new EditExportDialog(
                this, previewFileName, dur, startMs, endMs, previewMarkers.size(), dspConfig, config,
                new EditExportDialog.Listener() {
                    @Override
                    public void onToggleWaveformTrim() {
                        waveformView.setTrimMode(true);
                        refreshContextActionRow();
                        toast("Drag amber handles on waveform, then tap EXPORT TRIM");
                    }

                    @Override
                    public void onExportRequested(long tStart, long tEnd, boolean cutMiddle,
                                                  int fadeInMs, int fadeOutMs, DspConfig dspToApply,
                                                  AudioConfig.OutputFormat outFmt, int kbps) {
                        runOfflineExport(tStart, tEnd, cutMiddle, fadeInMs, fadeOutMs, dspToApply, outFmt, kbps);
                    }

                    @Override
                    public void onSplitAtMarkersRequested(int fadeInMs, int fadeOutMs, DspConfig dspToApply,
                                                          AudioConfig.OutputFormat outFmt, int kbps) {
                        runSplitAtMarkers(fadeInMs, fadeOutMs, dspToApply, outFmt, kbps);
                    }
                });
        dlg.show();
    }

    private void runOfflineExport(long startMs, long endMs, boolean cutMiddle,
                                  int fadeInMs, int fadeOutMs, DspConfig dspToApply,
                                  AudioConfig.OutputFormat outFmt, int kbps) {
        if (previewUri == null || previewMeta == null || exportBusy) return;
        stopBackgroundPlayback();
        exportBusy = true;
        trimLoopEnabled = false;
        waveformView.setTrimMode(false);
        refreshContextActionRow();

        final Uri srcUri = previewUri;
        final String srcName = previewFileName;
        final WavWriter.AudioFileMeta srcMeta = previewMeta;
        final String stage = "EXPORTING " + outFmt.shortLabel + (outFmt.usesBitrate ? " " + kbps + "k" : "");

        styleBadge(0xFFFFB800, 0xFFFFB800);
        statusBadge.setText("PROC");
        processingBanner.showProgress(stage, "Starting non-destructive export...", 0, 0xFFFFB800);

        ioExecutor.execute(() -> {
            try {
                AudioProcessor.ProcessResult res = AudioProcessor.processFile(
                        this, srcUri, srcName, srcMeta,
                        startMs, endMs, cutMiddle, fadeInMs, fadeOutMs, null,
                        dspToApply, outFmt, kbps,
                        (done, total, pct) -> mainHandler.post(() -> {
                            statusBadge.setText("PROC " + pct + "%");
                            processingBanner.showProgress(stage, srcName + " -> " + outFmt.shortLabel, pct, 0xFFFFB800);
                        }));
                mainHandler.post(() -> {
                    exportBusy = false;
                    processingBanner.hide();
                    toast("Exported " + res.displayName + " (" + res.summary + ")");
                    loadWaveformPreview(res.uri, res.displayName, res.sizeBytes, false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    exportBusy = false;
                    processingBanner.hide();
                    statusBadge.setText("PREVIEW");
                    toast("Export failed: " + e.getMessage());
                });
            }
        });
    }

    private void runSplitAtMarkers(int fadeInMs, int fadeOutMs, DspConfig dspToApply,
                                   AudioConfig.OutputFormat outFmt, int kbps) {
        if (previewUri == null || previewMeta == null || exportBusy) return;
        stopBackgroundPlayback();
        exportBusy = true;
        trimLoopEnabled = false;
        waveformView.setTrimMode(false);
        refreshContextActionRow();

        final Uri srcUri = previewUri;
        final String srcName = previewFileName;
        final WavWriter.AudioFileMeta srcMeta = previewMeta;
        final List<MarkerStore.MarkerEntry> markers = new ArrayList<>(previewMarkerEntries);
        final String stage = "SPLITTING INTO CHAPTERS (" + outFmt.shortLabel + ")";

        styleBadge(0xFFFFB800, 0xFFFFB800);
        statusBadge.setText("SPLIT");
        processingBanner.showProgress(stage, "Splitting at " + markers.size() + " marker(s)...", 0, 0xFFFFB800);

        ioExecutor.execute(() -> {
            try {
                List<AudioProcessor.ProcessResult> parts = AudioProcessor.splitAtMarkers(
                        this, srcUri, srcName, srcMeta, markers,
                        fadeInMs, fadeOutMs, dspToApply, outFmt, kbps,
                        (done, total, pct) -> mainHandler.post(() -> {
                            statusBadge.setText("SPLIT " + pct + "%");
                            processingBanner.showProgress(stage,
                                    "Part " + done + " / " + total + " -> " + outFmt.shortLabel, pct, 0xFFFFB800);
                        }));
                mainHandler.post(() -> {
                    exportBusy = false;
                    processingBanner.hide();
                    statusBadge.setText("PREVIEW");
                    toast("Exported " + parts.size() + " split chapter files");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    exportBusy = false;
                    processingBanner.hide();
                    statusBadge.setText("PREVIEW");
                    toast("Split failed: " + e.getMessage());
                });
            }
        });
    }

    private void shareMultipleUris(List<RecordingStorage.RecordingItem> items) {
        if (items == null || items.isEmpty()) return;
        if (items.size() == 1) {
            shareUri(items.get(0).uri, items.get(0).mimeType);
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>(items.size());
        for (RecordingStorage.RecordingItem it : items) uris.add(it.uri);
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.setType("audio/*");
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Share " + items.size() + " Recordings"));
        } catch (Exception e) {
            toast("No share target available");
        }
    }

    private void refreshContextActionRow() {
        if (contextActionRow == null) return;
        contextActionRow.removeAllViews();

        RecordingService.State st = (service != null) ? service.getState() : RecordingService.State.IDLE;
        if (st == RecordingService.State.RECORDING || st == RecordingService.State.PAUSED) {
            int count = service.getSessionMarkers().size();
            TextView markBtn = actionPill(count > 0 ? ("+ MARK (" + count + ")") : "+ MARK", 0xFFFFB800);
            markBtn.setOnClickListener(v -> {
                long ms = service.addMarker();
                if (ms >= 0L) {
                    toast("Marked " + formatTimecode(ms));
                    refreshContextActionRow();
                }
            });
            contextActionRow.addView(markBtn);

            if (st == RecordingService.State.PAUSED) {
                TextView discardBtn = actionPill("DISCARD TAKE", 0xFFFF2D2D);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.leftMargin = dp(10);
                discardBtn.setOnClickListener(v -> confirmDiscardTake());
                contextActionRow.addView(discardBtn, dlp);
            }
            contextActionRow.setVisibility(View.VISIBLE);
            return;
        }

        if (previewMeta != null && waveformView.isTrimMode()) {
            long dur = previewMeta.durationMs();
            long sMs = (long) (waveformView.getTrimStartFraction() * dur);
            long eMs = (long) (waveformView.getTrimEndFraction() * dur);
            TextView rangeTv = new TextView(this);
            rangeTv.setText(formatTimecode(sMs) + "-" + formatTimecode(eMs));
            rangeTv.setTextColor(0xFFFFB800);
            rangeTv.setTextSize(10f);
            rangeTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            contextActionRow.addView(rangeTv);

            TextView loopBtn = actionPill(trimLoopEnabled ? "LOOP ON" : "LOOP",
                    trimLoopEnabled ? 0xFF00E5A0 : 0xFF888888);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = dp(8);
            loopBtn.setOnClickListener(v -> {
                trimLoopEnabled = !trimLoopEnabled;
                syncLoopRangeIfActive();
                if (trimLoopEnabled && playbackService != null) {
                    if (isPlaybackActiveForPreview()) {
                        playbackService.seekToMs(sMs);
                    } else {
                        playbackService.playTrack(previewUri, previewFileName, previewFileBytes, previewMeta, sMs);
                        updatePreviewPlayIcon(true);
                    }
                }
                refreshContextActionRow();
            });
            contextActionRow.addView(loopBtn, llp);

            TextView exportBtn = actionPill("EXPORT", 0xFF00E5A0);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.leftMargin = dp(8);
            exportBtn.setOnClickListener(v -> openEditExportDialog());
            contextActionRow.addView(exportBtn, elp);

            TextView cancelBtn = actionPill("×", 0xFF777777);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(8);
            cancelBtn.setOnClickListener(v -> {
                trimLoopEnabled = false;
                syncLoopRangeIfActive();
                waveformView.setTrimMode(false);
                refreshContextActionRow();
            });
            contextActionRow.addView(cancelBtn, clp);

            contextActionRow.setVisibility(View.VISIBLE);
            return;
        }

        contextActionRow.setVisibility(View.GONE);
    }

    private TextView actionPill(String label, int color) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0A0A0A);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), color);
        tv.setBackground(bg);
        return tv;
    }

    private void confirmDiscardTake() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Discard current recording?")
                .setMessage("The paused take will be permanently deleted.")
                .setPositiveButton("Discard", (d, w) -> {
                    if (service != null) service.discardSession();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void closePreview() {
        stopBackgroundPlayback();
        processingBanner.hide();
        closePreviewSilent();
        refreshIdleUi();
    }

    private void closePreviewSilent() {
        previewUri = null;
        previewFileName = null;
        previewMeta = null;
        previewMarkers = Collections.emptyList();
        previewMarkerEntries = Collections.emptyList();
        trimLoopEnabled = false;
        if (playbackService != null) playbackService.setLoopRange(0L, 0L);
        waveformView.setTrimMode(false);
        previewBar.setVisibility(View.GONE);
        refreshContextActionRow();
    }

    @Override
    public void onPlaybackProgress(Uri uri, long currentMs, long totalMs, boolean playing) {
        mainHandler.post(() -> {
            if (previewMeta == null || previewUri == null || !previewUri.equals(uri)) return;
            float frac = totalMs > 0 ? (float) currentMs / (float) totalMs : 0f;
            waveformView.setPlayheadFraction(frac);
            timecodeText.setText(formatTimecode(currentMs));
            subInfoText.setText(formatTimecode(currentMs) + " / " + formatTimecode(totalMs)
                    + "  ·  " + RecordingStorage.formatBytes(previewFileBytes));
            updatePreviewPlayIcon(playing);
        });
    }

    @Override
    public void onPlaybackComplete(Uri uri) {
        mainHandler.post(() -> {
            if (previewUri == null || !previewUri.equals(uri)) return;
            waveformView.setPlayheadFraction(0f);
            updatePreviewPlayIcon(false);
            if (previewMeta != null) {
                timecodeText.setText("00:00.00");
                subInfoText.setText("DURATION " + formatTimecode(previewMeta.durationMs()) + "  ·  TAP WAVEFORM TO SCRUB");
            }
        });
    }

    @Override
    public void onPlaybackStopped() {
        mainHandler.post(() -> {
            updatePreviewPlayIcon(false);
            if (previewMeta != null) {
                waveformView.setPlayheadFraction(0f);
                timecodeText.setText("00:00.00");
                subInfoText.setText("DURATION " + formatTimecode(previewMeta.durationMs()) + "  ·  TAP WAVEFORM TO SCRUB");
            }
        });
    }

    @Override
    public void onPlaybackError(String message) {
        mainHandler.post(() -> {
            updatePreviewPlayIcon(false);
            toast("Playback error: " + message);
        });
    }

    /* ---------- UI State Synchronization ---------- */

    private void applyServiceState(RecordingService.State st, AudioCaptureEngine.StreamInfo info, AudioConfig cfg) {
        boolean keepOn = (st == RecordingService.State.RECORDING && config.keepScreenOn);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        switch (st) {
            case RECORDING:
                processingBanner.hide();
                recordButton.setRecordingState(true, false);
                recordButton.setEnabled(true);
                recordButton.setAlpha(1f);
                waveformView.setMode(WaveformView.Mode.LIVE_RECORDING);
                styleBadge(0xFFFF2D2D, 0xFFFF2D2D);
                statusBadge.setText("REC");
                leftTransportBtn.setImageResource(R.drawable.ic_pause);
                leftTransportBtn.setColorFilter(0xFFF2F2F2);
                rightTransportBtn.setImageResource(R.drawable.ic_list);
                rightTransportBtn.setColorFilter(0xFF3A3A3A);
                if (info != null) {
                    telemetryText.setText(info.telemetryLine(config, 0));
                    telemetryText.setTextColor(0xFFE0E0E0);
                }
                break;
            case PAUSED:
                recordButton.setRecordingState(true, true);
                recordButton.setEnabled(true);
                recordButton.setAlpha(1f);
                waveformView.setMode(WaveformView.Mode.LIVE_PAUSED);
                styleBadge(0xFFFFB800, 0xFFFFB800);
                statusBadge.setText("PAUSED");
                leftTransportBtn.setImageResource(R.drawable.ic_play);
                leftTransportBtn.setColorFilter(0xFFFFB800);
                break;
            case FINALIZING:
                recordButton.setRecordingState(false, false);
                recordButton.setEnabled(false);
                recordButton.setAlpha(0.45f);
                leftTransportBtn.setColorFilter(0xFF3A3A3A);
                rightTransportBtn.setColorFilter(0xFF3A3A3A);
                styleBadge(0xFFFFB800, 0xFFFFB800);
                statusBadge.setText("PROC");
                break;
            case MONITORING:
                processingBanner.hide();
                recordButton.setRecordingState(false, false);
                recordButton.setEnabled(true);
                recordButton.setAlpha(1f);
                waveformView.setMode(WaveformView.Mode.LIVE_MONITORING);
                styleBadge(0xFF00E5A0, 0xFF00E5A0);
                statusBadge.setText("MONITOR");
                leftTransportBtn.setImageResource(R.drawable.ic_monitor);
                leftTransportBtn.setColorFilter(0xFF00E5A0);
                timecodeText.setText("LIVE MON");
                if (info != null) {
                    telemetryText.setText(info.telemetryLine(config, 0));
                    telemetryText.setTextColor(0xFF00E5A0);
                }
                break;
            case IDLE:
            default:
                recordButton.setRecordingState(false, false);
                recordButton.setEnabled(true);
                recordButton.setAlpha(1f);
                leftTransportBtn.setImageResource(R.drawable.ic_monitor);
                leftTransportBtn.setColorFilter(0xFFB0B0B0);
                if (previewUri == null) {
                    processingBanner.hide();
                    refreshIdleUi();
                } else {
                    updatePreviewPlayIcon(isPlaybackPlayingForPreview());
                }
                break;
        }
        refreshContextActionRow();
    }

    private void refreshFxStrip() {
        boolean active = dspConfig != null && dspConfig.hasAnyActiveFx();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0xFF09130F : 0xFF070707);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), active ? 0xFF00E5A0 : 0xFF1E1E1E);
        fxStripText.setBackground(bg);

        if (active) {
            fxStripText.setText("FX AUTO · " + dspConfig.presetName + " (" + dspConfig.activeModulesSummary() + ")");
            fxStripText.setTextColor(0xFF00E5A0);
            fxTopBtn.setColorFilter(0xFF00E5A0);
        } else {
            fxStripText.setText("FX · RAW BYPASS (TAP FOR EQ / COMP / NORM)");
            fxStripText.setTextColor(0xFF666666);
            fxTopBtn.clearColorFilter();
        }
    }

    private void refreshIdleUi() {
        pathText.setText(RecordingStorage.getDisplayPath(this));
        refreshFxStrip();
        levelMeterView.setChannelCount(config.channelCount);
        if (isRecordingBusy() || (service != null && service.getState() == RecordingService.State.MONITORING)) {
            return;
        }
        String cfgLine = config.engineMode.shortLabel
                + " · " + config.inputPreset.shortLabel
                + " · " + AudioConfig.formatSampleRate(config.sampleRate)
                + " · " + config.formatShortDisplay()
                + " · " + (config.channelCount == 2 ? "ST" : "MONO");
        telemetryText.setText(cfgLine);
        telemetryText.setTextColor(0xFF7A7A7A);

        if (previewUri == null) {
            waveformView.setMode(WaveformView.Mode.IDLE);
            levelMeterView.reset();
            styleBadge(0xFF262626, 0xFF8A8A8A);
            statusBadge.setText(dspConfig.hasAnyActiveFx() ? "FX" : "RAW");
            timecodeText.setText("00:00.00");
            AudioDeviceHelper.HalDiagnostics diag = AudioDeviceHelper.probeHal(this);
            subInfoText.setText(diag.supportsUnprocessed
                    ? "HAL UNPROCESSED MIC VERIFIED · TAP CONFIG OR FX"
                    : "UNPROCESSED DIRECT CAPTURE · TAP CONFIG OR FX");
            subInfoText.setTextColor(0xFF555555);
            rightTransportBtn.setImageResource(R.drawable.ic_list);
            rightTransportBtn.setColorFilter(0xFFB0B0B0);
        }
    }

    private void updatePreviewPlayIcon(boolean playing) {
        previewPlayBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (!isRecordingBusy() && previewUri != null) {
            rightTransportBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            rightTransportBtn.setColorFilter(playing ? 0xFF00E5A0 : 0xFFF2F2F2);
            statusBadge.setText(playing ? "PLAY" : "PREVIEW");
        }
    }

    private boolean isRecordingBusy() {
        if (service == null) return false;
        RecordingService.State st = service.getState();
        return st == RecordingService.State.RECORDING
                || st == RecordingService.State.PAUSED
                || st == RecordingService.State.FINALIZING;
    }

    private void shareUri(Uri uri, String mimeType) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mimeType != null ? mimeType : "audio/wav");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "Share recording"));
        } catch (Exception e) {
            toast("No app available to share recording");
        }
    }

    private void styleBadge(int borderColor, int textColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF080808);
        bg.setCornerRadius(dp(4));
        bg.setStroke(dp(1), borderColor);
        statusBadge.setBackground(bg);
        statusBadge.setTextColor(textColor);
    }

    private ImageView topIconBtn(int drawableRes, String desc) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setContentDescription(desc);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return iv;
    }

    private ImageView circleTransportBtn(int drawableRes, String desc) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setContentDescription(desc);
        iv.setColorFilter(0xFFB0B0B0);
        iv.setPadding(dp(15), dp(15), dp(15), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF080808);
        bg.setStroke(dp(1), 0xFF242424);
        iv.setBackground(bg);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(54)));
        return iv;
    }

    private static String formatTimecode(long ms) {
        if (ms < 0) ms = 0;
        long centis = (ms / 10L) % 100L;
        long totalSec = ms / 1000L;
        long sec = totalSec % 60L;
        long min = (totalSec / 60L) % 60L;
        long hr = totalSec / 3600L;
        if (hr > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d", hr, min, sec, centis);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, centis);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }
}
