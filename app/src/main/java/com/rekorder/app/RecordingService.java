// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;

import com.rekorder.app.audio.AudioCaptureEngine;
import com.rekorder.app.audio.AudioConfig;
import com.rekorder.app.audio.AudioDeviceHelper;
import com.rekorder.app.audio.AudioRecordEngine;
import com.rekorder.app.audio.FlacFileWriter;
import com.rekorder.app.audio.Mp3FileWriter;
import com.rekorder.app.audio.NativeAAudioEngine;
import com.rekorder.app.audio.OggOpusWriter;
import com.rekorder.app.audio.PcmConverter;
import com.rekorder.app.audio.PostNormalizer;
import com.rekorder.app.audio.ScratchRecovery;
import com.rekorder.app.audio.WavWriter;
import com.rekorder.app.dsp.AudioResampler;
import com.rekorder.app.dsp.DspChain;
import com.rekorder.app.dsp.DspConfig;
import com.rekorder.app.dsp.DspPresetStore;
import com.rekorder.app.storage.MarkerStore;
import com.rekorder.app.storage.RecordingStorage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground microphone service hosting the raw audio capture loop,
 * real-time studio DSP chain (Input Gain + HPF + 5-band EQ + soft-knee compressor),
 * optional headphone-only low-latency monitor pass-through, live markers,
 * discard-on-pause, and fail-safe lossless (WAV/RAW/FLAC) or compressed (OGG/MP3) writers.
 */
public final class RecordingService extends Service {

    public enum State {
        IDLE,
        MONITORING,
        RECORDING,
        PAUSED,
        FINALIZING
    }

    public interface Callback {
        void onStateChanged(State state, AudioCaptureEngine.StreamInfo info, AudioConfig config);
        void onLiveMeter(float peakL, float rmsL, float peakR, float rmsR, int channels,
                         boolean clipped, float gainReductionDb,
                         long elapsedMs, long fileBytes, int totalDrops, int totalClips);
        void onProcessingProgress(String stage, String detail, int percent);
        void onRecordingSaved(Uri uri, String fileName, long fileBytes, String note);
        void onWarning(String message);
        void onError(String message);
    }

    public static final String ACTION_STOP = "com.rekorder.app.ACTION_STOP";
    public static final String ACTION_TOGGLE_PAUSE = "com.rekorder.app.ACTION_TOGGLE_PAUSE";
    public static final String ACTION_START_DEFAULT = "com.rekorder.app.ACTION_START_DEFAULT";

    private static final String CHANNEL_ID = "rekorder_capture";
    private static final int NOTIF_ID = 1001;

    private static final long MIN_START_FREE_BYTES = 16L * 1024L * 1024L;
    private static final long WARN_LOW_FREE_BYTES = 100L * 1024L * 1024L;
    private static final long STOP_LOW_FREE_BYTES = 24L * 1024L * 1024L;
    private static final long CHECKPOINT_INTERVAL_MS = 15_000L;
    private static final long WAKELOCK_RENEW_INTERVAL_MS = 30L * 60L * 1000L;

    private static volatile boolean sRecordingGlobally = false;

    public static boolean isRecordingGlobally() {
        return sRecordingGlobally;
    }

    private final LocalBinder binder = new LocalBinder();
    private volatile Callback callback;

    private volatile State state = State.IDLE;
    private volatile AudioCaptureEngine.StreamInfo activeStreamInfo;
    private volatile AudioConfig activeConfig;
    private volatile DspConfig activeDspConfig = DspConfig.rawBypass();
    private volatile DspChain activeDspChain;
    private volatile File activeScratchFile;
    private volatile long currentElapsedMs = 0L;

    private final List<Long> sessionMarkers = Collections.synchronizedList(new ArrayList<>());

    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private final AtomicBoolean pausedFlag = new AtomicBoolean(false);
    private final AtomicBoolean discardFlag = new AtomicBoolean(false);
    private final AtomicBoolean checkpointRequested = new AtomicBoolean(false);
    private volatile boolean isForegroundActive = false;
    private volatile String lastProcStage = "";
    private volatile String lastProcDetail = "";
    private volatile int lastProcPercent = -1;
    private volatile long lastNotifProgressMs = 0L;
    private Thread workerThread;
    private AudioCaptureEngine activeEngine;
    private PowerManager.WakeLock wakeLock;

    public final class LocalBinder extends Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_STOP.equals(act)) {
                stopSession();
            } else if (ACTION_TOGGLE_PAUSE.equals(act)) {
                togglePause();
            } else if (ACTION_START_DEFAULT.equals(act)) {
                if (state == State.IDLE) {
                    startRecording(AudioConfig.load(this), DspPresetStore.loadActive(this));
                }
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSessionBlocking(5000);
        super.onDestroy();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
        if (cb != null) {
            cb.onStateChanged(state, activeStreamInfo, activeConfig);
            if (state == State.FINALIZING) {
                cb.onProcessingProgress(lastProcStage, lastProcDetail, lastProcPercent);
            }
        }
    }

    public State getState() {
        return state;
    }

    public AudioCaptureEngine.StreamInfo getActiveStreamInfo() {
        return activeStreamInfo;
    }

    public File getActiveScratchFile() {
        return activeScratchFile;
    }

    public long addMarker() {
        if (state != State.RECORDING && state != State.PAUSED) return -1L;
        long ms = Math.max(0L, currentElapsedMs);
        sessionMarkers.add(ms);
        return ms;
    }

    public List<Long> getSessionMarkers() {
        synchronized (sessionMarkers) {
            return new ArrayList<>(sessionMarkers);
        }
    }

    public void updateLiveDspConfig(DspConfig dspConfig) {
        this.activeDspConfig = (dspConfig != null) ? dspConfig : DspConfig.rawBypass();
        DspChain chain = activeDspChain;
        if (chain != null) {
            chain.reconfigure(this.activeDspConfig);
        }
    }

    public synchronized void startMonitoring(AudioConfig config, DspConfig dspConfig) {
        if (state != State.IDLE) return;
        startCaptureInternal(config, dspConfig, false);
    }

    public synchronized void startRecording(AudioConfig config, DspConfig dspConfig) {
        if (state == State.MONITORING) {
            stopSessionBlocking(1000);
        }
        if (state != State.IDLE) return;

        long freeBytes = RecordingStorage.getRecordingStorageAvailableBytes(this);
        if (freeBytes < MIN_START_FREE_BYTES) {
            notifyError("Not enough free storage (" + RecordingStorage.formatBytes(freeBytes) + " left)");
            return;
        }

        Intent self = new Intent(this, RecordingService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(self);
        } else {
            startService(self);
        }
        activeConfig = config;
        promoteToForeground(getString(R.string.notif_recording), false);
        isForegroundActive = true;
        sRecordingGlobally = true;
        acquireWakeLock();
        startCaptureInternal(config, dspConfig, true);
    }

    public synchronized void togglePause() {
        if (state == State.RECORDING) {
            pausedFlag.set(true);
            checkpointRequested.set(true);
            if (activeEngine != null) activeEngine.setPaused(true);
            state = State.PAUSED;
            promoteToForeground(getString(R.string.notif_paused), true);
            notifyState();
        } else if (state == State.PAUSED) {
            pausedFlag.set(false);
            if (activeEngine != null) activeEngine.setPaused(false);
            state = State.RECORDING;
            promoteToForeground(getString(R.string.notif_recording), false);
            notifyState();
        }
    }

    /**
     * Non-blocking stop request safe to call on the UI main thread.
     */
    public synchronized void stopSession() {
        if (state == State.IDLE) return;
        discardFlag.set(false);
        workerRunning.set(false);
        pausedFlag.set(false);
        if (state == State.RECORDING || state == State.PAUSED) {
            state = State.FINALIZING;
            notifyState();
            notifyProcessing("STOPPING CAPTURE", "Flushing hardware audio ring...", -1);
        } else if (state == State.MONITORING) {
            Thread t = workerThread;
            if (t != null) t.interrupt();
        }
    }

    /**
     * Cleanly discards the active/paused recording and deletes its unfinished file & scratch buffer.
     */
    public synchronized void discardSession() {
        if (state != State.RECORDING && state != State.PAUSED) return;
        discardFlag.set(true);
        workerRunning.set(false);
        pausedFlag.set(false);
        state = State.FINALIZING;
        notifyState();
        notifyProcessing("DISCARDING TAKE", "Cleaning up temporary buffers...", -1);
    }

    private synchronized void stopSessionBlocking(long timeoutMs) {
        workerRunning.set(false);
        pausedFlag.set(false);
        Thread t = workerThread;
        workerThread = null;
        if (t != null) {
            if (state != State.FINALIZING) {
                t.interrupt();
            }
            try {
                t.join(timeoutMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        releaseWakeLock();
        if (isForegroundActive) {
            isForegroundActive = false;
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
            stopSelf();
        }
        sRecordingGlobally = false;
        state = State.IDLE;
        activeStreamInfo = null;
        notifyState();
    }

    private void startCaptureInternal(AudioConfig config, DspConfig dspConfig, boolean writeToDisk) {
        activeConfig = config;
        activeDspConfig = (dspConfig != null) ? dspConfig : DspConfig.rawBypass();
        pausedFlag.set(false);
        discardFlag.set(false);
        checkpointRequested.set(false);
        sessionMarkers.clear();
        currentElapsedMs = 0L;
        workerRunning.set(true);
        state = writeToDisk ? State.RECORDING : State.MONITORING;

        final DspConfig sessionDsp = activeDspConfig;
        workerThread = new Thread(() -> runCaptureLoop(config, sessionDsp, writeToDisk), "RekorderCapture");
        workerThread.start();
    }

    private static final class OpenedEngine {
        final AudioCaptureEngine engine;
        final AudioCaptureEngine.StreamInfo info;

        OpenedEngine(AudioCaptureEngine engine, AudioCaptureEngine.StreamInfo info) {
            this.engine = engine;
            this.info = info;
        }
    }

    private OpenedEngine openCaptureEngineWithFallback(AudioConfig config, boolean forceDefaultDevice)
            throws IOException {
        int devId = forceDefaultDevice ? 0 : config.deviceId;
        if (devId > 0 && AudioDeviceHelper.findDeviceById(this, devId) == null) {
            devId = 0;
        }
        AudioConfig effective = new AudioConfig(
                config.engineMode, config.inputPreset, config.outputFormat,
                config.sampleRate, config.channelCount, devId,
                config.keepScreenOn, config.visualGain, config.compressedBitrateKbps,
                config.headphoneMonitor, config.outputDeviceId, config.outputDeviceType
        );

        boolean wantAAudio = (effective.engineMode != AudioConfig.EngineMode.AUDIORECORD)
                && NativeAAudioEngine.isNativeAvailable();

        if (wantAAudio) {
            NativeAAudioEngine nativeEng = new NativeAAudioEngine();
            try {
                AudioCaptureEngine.StreamInfo info = nativeEng.open(effective);
                return new OpenedEngine(nativeEng, info);
            } catch (Throwable aaudioErr) {
                nativeEng.stopAndClose();
                AudioRecordEngine fallback = new AudioRecordEngine(this);
                AudioCaptureEngine.StreamInfo info = fallback.open(effective);
                return new OpenedEngine(fallback, info);
            }
        } else {
            AudioRecordEngine recEng = new AudioRecordEngine(this);
            AudioCaptureEngine.StreamInfo info = recEng.open(effective);
            return new OpenedEngine(recEng, info);
        }
    }

    private AudioTrack openHeadphoneMonitorTrack(int sampleRate, int channels,
                                                 int preferredOutputDeviceId, int preferredOutputDeviceType) {
        android.media.AudioDeviceInfo outDev = AudioDeviceHelper.resolveSafeMonitorOutput(
                this, preferredOutputDeviceId, preferredOutputDeviceType);
        if (outDev == null) return null;
        int chMask = (channels == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) return null;
        try {
            AudioTrack.Builder tb = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            AudioTrack t = tb.build();
            if (t.getState() == AudioTrack.STATE_INITIALIZED) {
                t.setPreferredDevice(outDev);
                t.play();
                return t;
            }
            t.release();
        } catch (Throwable ignored) {}
        return null;
    }

    private void runCaptureLoop(AudioConfig config, DspConfig dspConfig, boolean writeToDisk) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        AudioCaptureEngine engine = null;
        AudioTrack monitorTrack = null;
        RecordingStorage.RecordingTarget target = null;
        WavWriter wavWriter = null;
        Mp3FileWriter mp3Writer = null;
        OggOpusWriter oggWriter = null;
        FlacFileWriter flacWriter = null;
        File scratchFile = null;
        RandomAccessFile scratchRaf = null;
        FileChannel scratchChannel = null;
        boolean scratchSafelySaved = false;
        long scratchBytesWritten = 0L;

        Uri savedUri = null;
        String savedName = null;
        long savedBytes = 0L;
        String savedNote = null;
        String sessionNotice = null;
        int wavPartIndex = 1;

        try {
            OpenedEngine opened = openCaptureEngineWithFallback(config, false);
            engine = opened.engine;
            AudioCaptureEngine.StreamInfo info = opened.info;

            activeEngine = engine;
            activeStreamInfo = info;

            final int sessionRate = info.sampleRate;
            final int sessionCh = info.channelCount;
            int curHwRate = info.sampleRate;
            int curHwCh = info.channelCount;
            int curHwFmt = info.hardwareFormat;
            int curHwBpf = info.bytesPerFrame;
            AudioResampler reconnectResampler = null;

            DspChain dspChain = new DspChain(sessionRate, sessionCh, dspConfig);
            activeDspChain = dspChain;

            if (config.headphoneMonitor) {
                monitorTrack = openHeadphoneMonitorTrack(sessionRate, sessionCh,
                        config.outputDeviceId, config.outputDeviceType);
                if (monitorTrack == null) {
                    notifyWarning("Live audio monitor muted (select/connect headphones to avoid feedback)");
                }
            }

            AudioConfig.OutputFormat activeFormat = config.outputFormat;
            boolean isCompressed = activeFormat.isCompressed;
            boolean wantNormalize = writeToDisk && dspConfig.hasNormalizer();
            boolean useScratchForCompressedNorm = writeToDisk && isCompressed && wantNormalize;

            if (writeToDisk) {
                target = RecordingStorage.createTarget(this, activeFormat, sessionRate, sessionCh, "", false);
                if (target.fellBackToDefault) {
                    notifyWarning("Custom folder unavailable · saving safely to App Storage");
                }
                savedUri = target.uri;
                savedName = target.displayName;

                if (useScratchForCompressedNorm) {
                    File scratchDir = RecordingStorage.getBestScratchDir(this);
                    scratchFile = new File(scratchDir,
                            ScratchRecovery.SCRATCH_PREFIX + System.currentTimeMillis() + ScratchRecovery.SCRATCH_EXT);
                    activeScratchFile = scratchFile;
                    scratchRaf = new RandomAccessFile(scratchFile, "rw");
                    scratchChannel = scratchRaf.getChannel();
                    ScratchRecovery.writeMeta(scratchFile, sessionRate, sessionCh, dspConfig.normTargetDb, 0f);
                } else if (activeFormat == AudioConfig.OutputFormat.MP3) {
                    try {
                        mp3Writer = new Mp3FileWriter(target.channel, sessionRate, sessionCh,
                                config.compressedBitrateKbps, savedName, null);
                    } catch (Throwable encErr) {
                        target.deleteSilently(this);
                        activeFormat = AudioConfig.OutputFormat.WAV_24;
                        isCompressed = false;
                        target = RecordingStorage.createTarget(this, activeFormat, sessionRate, sessionCh, "", false);
                        savedUri = target.uri;
                        savedName = target.displayName;
                        wavWriter = new WavWriter(target.channel, activeFormat, sessionRate, sessionCh);
                        notifyWarning("MP3 encoder unavailable · recording safely as 24-bit WAV");
                    }
                } else if (activeFormat == AudioConfig.OutputFormat.FLAC) {
                    try {
                        flacWriter = new FlacFileWriter(target.channel, sessionRate, sessionCh);
                    } catch (Throwable flacErr) {
                        target.deleteSilently(this);
                        activeFormat = AudioConfig.OutputFormat.WAV_24;
                        isCompressed = false;
                        target = RecordingStorage.createTarget(this, activeFormat, sessionRate, sessionCh, "", false);
                        savedUri = target.uri;
                        savedName = target.displayName;
                        wavWriter = new WavWriter(target.channel, activeFormat, sessionRate, sessionCh);
                        notifyWarning("FLAC encoder unavailable · recording safely as 24-bit WAV");
                    }
                } else if (activeFormat == AudioConfig.OutputFormat.OGG_OPUS) {
                    try {
                        oggWriter = new OggOpusWriter(target.channel, sessionRate, sessionCh,
                                config.compressedBitrateKbps, savedName);
                    } catch (Throwable opusErr) {
                        target.deleteSilently(this);
                        try {
                            activeFormat = AudioConfig.OutputFormat.MP3;
                            isCompressed = true;
                            target = RecordingStorage.createTarget(this, activeFormat, sessionRate, sessionCh, "", false);
                            savedUri = target.uri;
                            savedName = target.displayName;
                            mp3Writer = new Mp3FileWriter(target.channel, sessionRate, sessionCh,
                                    config.compressedBitrateKbps, savedName, null);
                            notifyWarning("Opus unavailable · recording safely as MP3");
                        } catch (Throwable mp3Err) {
                            if (target != null) target.deleteSilently(this);
                            activeFormat = AudioConfig.OutputFormat.WAV_24;
                            isCompressed = false;
                            target = RecordingStorage.createTarget(this, activeFormat, sessionRate, sessionCh, "", false);
                            savedUri = target.uri;
                            savedName = target.displayName;
                            wavWriter = new WavWriter(target.channel, activeFormat, sessionRate, sessionCh);
                            notifyWarning("Compressed encoder unavailable · recording safely as 24-bit WAV");
                        }
                    }
                } else {
                    wavWriter = new WavWriter(target.channel, activeFormat, sessionRate, sessionCh);
                }
            }

            if (!workerRunning.get()) {
                return;
            }
            engine.start();
            notifyState();

            boolean isNativeRing = (engine instanceof NativeAAudioEngine);
            int maxReadBytes = (65536 / Math.max(1, curHwBpf)) * Math.max(1, curHwBpf);
            ByteBuffer rawBuf = ByteBuffer.allocateDirect(maxReadBytes).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer floatBuf = ByteBuffer.allocateDirect(maxReadBytes * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer adaptBuf = ByteBuffer.allocateDirect(maxReadBytes * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer outBuf = ByteBuffer.allocateDirect(maxReadBytes * 4).order(ByteOrder.LITTLE_ENDIAN);

            PcmConverter.MeterResult meter = new PcmConverter.MeterResult();
            AudioCaptureEngine.Telemetry telem = new AudioCaptureEngine.Telemetry();

            int meterBucketFrames = Math.max(256, sessionRate / 45);
            int bucketFramesAcc = 0;
            float bucketPeakL = 0f, bucketPeakR = 0f;
            double bucketRmsSqL = 0.0, bucketRmsSqR = 0.0;
            int bucketChunks = 0;
            int bucketClips = 0;
            float bucketWorstGr = 0f;
            int totalClips = 0;
            float sessionMaxPeak = 0f;
            long recordedFrames = 0L;
            long lastTelemetryPoll = 0L;
            long lastCheckpointMs = System.currentTimeMillis();
            long lastSpaceCheckMs = System.currentTimeMillis();
            long lastWakeRenewMs = System.currentTimeMillis();
            long lastHeadphoneCheckMs = System.currentTimeMillis();
            boolean lowSpaceWarned = false;
            int lastDrops = 0;

            while (workerRunning.get()) {
                rawBuf.clear();
                floatBuf.clear();
                outBuf.clear();

                int alignedMax = (maxReadBytes / Math.max(1, curHwBpf)) * Math.max(1, curHwBpf);
                int got = engine.read(rawBuf, alignedMax);

                long now = System.currentTimeMillis();

                // Headphone plug/unplug safety check for live audio monitoring every 500ms
                if (config.headphoneMonitor && (now - lastHeadphoneCheckMs >= 500L)) {
                    lastHeadphoneCheckMs = now;
                    boolean safeOut = AudioDeviceHelper.resolveSafeMonitorOutput(
                            this, config.outputDeviceId, config.outputDeviceType) != null;
                    if (!safeOut && monitorTrack != null) {
                        try { monitorTrack.stop(); } catch (IllegalStateException ignored) {}
                        monitorTrack.release();
                        monitorTrack = null;
                        notifyWarning("Headphones unplugged · live audio monitor muted");
                    } else if (safeOut && monitorTrack == null) {
                        monitorTrack = openHeadphoneMonitorTrack(sessionRate, sessionCh,
                                config.outputDeviceId, config.outputDeviceType);
                    }
                }

                if (now - lastTelemetryPoll >= 250L) {
                    lastTelemetryPoll = now;
                    engine.pollTelemetry(telem);
                    lastDrops = telem.xrunCount + telem.ringOverruns;

                    if (telem.errorCode != 0) {
                        engine.stopAndClose();
                        engine = null;
                        activeEngine = null;
                        notifyWarning("Mic disconnected (" + telem.errorText + ") · reconnecting...");

                        OpenedEngine reconnected = attemptMicReconnect(config, sessionRate, sessionCh);
                        if (reconnected == null) {
                            if (writeToDisk && recordedFrames > 0L) {
                                sessionNotice = "Saved take (mic disconnected)";
                                notifyWarning("Mic stayed unavailable · saving captured audio safely");
                                break;
                            } else if (workerRunning.get()) {
                                throw new IOException("Audio hardware disconnected: " + telem.errorText);
                            } else {
                                break;
                            }
                        }

                        engine = reconnected.engine;
                        activeEngine = engine;
                        activeStreamInfo = reconnected.info;
                        curHwRate = reconnected.info.sampleRate;
                        curHwCh = reconnected.info.channelCount;
                        curHwFmt = reconnected.info.hardwareFormat;
                        curHwBpf = reconnected.info.bytesPerFrame;
                        isNativeRing = (engine instanceof NativeAAudioEngine);
                        reconnectResampler = (curHwRate != sessionRate)
                                ? new AudioResampler(curHwRate, sessionRate, sessionCh) : null;
                        if (pausedFlag.get()) {
                            engine.setPaused(true);
                        }
                        engine.start();
                        notifyState();
                        notifyWarning("Reconnected mic (" + reconnected.info.engineTag + ") · take continued");
                        continue;
                    }

                    if (pausedFlag.get()) {
                        long elapsedMs = (recordedFrames * 1000L) / sessionRate;
                        currentElapsedMs = elapsedMs;
                        long bytes = currentWrittenBytes(wavWriter, mp3Writer, oggWriter, flacWriter, scratchBytesWritten);
                        notifyMeter(0f, 0f, 0f, 0f, sessionCh, false, 0f, elapsedMs, bytes, lastDrops, totalClips);
                    }
                }

                if (writeToDisk && (checkpointRequested.getAndSet(false)
                        || (now - lastCheckpointMs >= CHECKPOINT_INTERVAL_MS))) {
                    lastCheckpointMs = now;
                    if (wavWriter != null) {
                        wavWriter.checkpointHeader();
                    }
                    if (scratchChannel != null && scratchFile != null && scratchBytesWritten > 0L) {
                        try { scratchChannel.force(false); } catch (IOException ignored) {}
                        ScratchRecovery.writeMeta(scratchFile, sessionRate, sessionCh,
                                dspConfig.normTargetDb, sessionMaxPeak);
                    }
                }

                if (writeToDisk && (now - lastSpaceCheckMs >= 5000L)) {
                    lastSpaceCheckMs = now;
                    long availRec = RecordingStorage.getRecordingStorageAvailableBytes(this);
                    long availScratch = (scratchFile != null)
                            ? RecordingStorage.getAvailableBytes(scratchFile.getParentFile())
                            : Long.MAX_VALUE;
                    long minAvail = Math.min(availRec, availScratch);
                    if (minAvail < STOP_LOW_FREE_BYTES) {
                        sessionNotice = "Stopped safely (low storage: "
                                + RecordingStorage.formatBytes(minAvail) + " left)";
                        notifyWarning(sessionNotice);
                        break;
                    } else if (!lowSpaceWarned && minAvail < WARN_LOW_FREE_BYTES) {
                        lowSpaceWarned = true;
                        notifyWarning("Low storage warning: " + RecordingStorage.formatBytes(minAvail) + " remaining");
                    }
                }

                if (writeToDisk && (now - lastWakeRenewMs >= WAKELOCK_RENEW_INTERVAL_MS)) {
                    lastWakeRenewMs = now;
                    acquireWakeLock();
                }

                if (got <= 0) {
                    if (isNativeRing) {
                        try {
                            Thread.sleep(4);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                    continue;
                }

                int hwFrames = got / Math.max(1, curHwBpf);
                if (hwFrames <= 0) continue;

                boolean needAdapt = (curHwCh != sessionCh) || (reconnectResampler != null);
                boolean needFloatPath = needAdapt || activeDspConfig.hasRealTimeFx()
                        || isCompressed || useScratchForCompressedNorm || (monitorTrack != null);
                float grDb = 0f;
                int frames;

                if (needFloatPath) {
                    int floatBytes = PcmConverter.convertToOutput(
                            rawBuf, hwFrames * curHwBpf, curHwFmt, AudioConfig.OutputFormat.WAV_32F, floatBuf);
                    frames = hwFrames;
                    ByteBuffer workingFloat = floatBuf;

                    if (curHwCh != sessionCh) {
                        adaptChannelsInFloat(workingFloat, frames, curHwCh, sessionCh, adaptBuf);
                        workingFloat = adaptBuf;
                        floatBytes = frames * sessionCh * 4;
                    }
                    if (reconnectResampler != null) {
                        ByteBuffer rsDst = (workingFloat == floatBuf) ? adaptBuf : floatBuf;
                        frames = reconnectResampler.process(workingFloat, frames, rsDst);
                        if (frames <= 0) continue;
                        workingFloat = rsDst;
                        floatBytes = frames * sessionCh * 4;
                    }

                    if (activeDspConfig.hasRealTimeFx()) {
                        dspChain.processInPlace(workingFloat, frames);
                        grDb = dspChain.getLastGainReductionDb();
                    }
                    PcmConverter.computeMeter(workingFloat, floatBytes, PcmConverter.FMT_PCM_FLOAT, sessionCh, meter);

                    // Non-blocking headphone pass-through monitor
                    if (monitorTrack != null && !pausedFlag.get()) {
                        workingFloat.limit(floatBytes);
                        workingFloat.position(0);
                        monitorTrack.write(workingFloat, floatBytes, AudioTrack.WRITE_NON_BLOCKING);
                        workingFloat.clear();
                    }

                    if (writeToDisk && !pausedFlag.get()) {
                        if (Float.isFinite(meter.peak) && meter.peak > sessionMaxPeak) {
                            sessionMaxPeak = meter.peak;
                        }
                        try {
                            if (useScratchForCompressedNorm && scratchChannel != null) {
                                workingFloat.limit(floatBytes);
                                workingFloat.position(0);
                                while (workingFloat.hasRemaining()) {
                                    int w = scratchChannel.write(workingFloat);
                                    if (w <= 0) throw new IOException("Scratch write failed (disk full)");
                                    scratchBytesWritten += w;
                                }
                            } else if (mp3Writer != null) {
                                mp3Writer.writeFloatFrames(workingFloat, frames);
                            } else if (flacWriter != null) {
                                flacWriter.writeFloatFrames(workingFloat, frames);
                            } else if (oggWriter != null) {
                                oggWriter.writeFloatFrames(workingFloat, frames);
                            } else if (wavWriter != null) {
                                int outBytes = PcmConverter.convertToOutput(
                                        workingFloat, floatBytes, PcmConverter.FMT_PCM_FLOAT, activeFormat, outBuf);
                                wavWriter.write(outBuf, outBytes);
                            }
                            recordedFrames += frames;
                            currentElapsedMs = (recordedFrames * 1000L) / sessionRate;
                            savedBytes = currentWrittenBytes(wavWriter, mp3Writer, oggWriter, flacWriter, scratchBytesWritten);
                        } catch (Throwable writeErr) {
                            if (oggWriter != null || mp3Writer != null || flacWriter != null) {
                                long partialBytes = currentWrittenBytes(null, mp3Writer, oggWriter, flacWriter, 0L);
                                try {
                                    if (oggWriter != null) oggWriter.finalizeAndFlush();
                                    if (mp3Writer != null) mp3Writer.finalizeAndFlush();
                                    if (flacWriter != null) flacWriter.finalizeAndFlush();
                                } catch (Throwable ignored) {}
                                oggWriter = null;
                                mp3Writer = null;
                                flacWriter = null;
                                if (target != null) {
                                    try { target.close(); } catch (IOException ignored) {}
                                }
                                if (savedUri != null && partialBytes > 0L) {
                                    notifySaved(savedUri, savedName, partialBytes,
                                            "Part 1 saved (encoder interrupted — continuing lossless)");
                                }
                                try {
                                    activeFormat = AudioConfig.OutputFormat.WAV_24;
                                    isCompressed = false;
                                    target = RecordingStorage.createTarget(this, activeFormat,
                                            sessionRate, sessionCh, "_rescue", false);
                                    savedUri = target.uri;
                                    savedName = target.displayName;
                                    wavWriter = new WavWriter(target.channel, activeFormat, sessionRate, sessionCh);
                                    int outBytes = PcmConverter.convertToOutput(
                                            workingFloat, floatBytes, PcmConverter.FMT_PCM_FLOAT, activeFormat, outBuf);
                                    wavWriter.write(outBuf, outBytes);
                                    recordedFrames += frames;
                                    currentElapsedMs = (recordedFrames * 1000L) / sessionRate;
                                    savedBytes = wavWriter.getTotalFileBytes();
                                    notifyWarning("Encoder error · seamlessly switched to lossless 24-bit WAV");
                                } catch (Throwable rescueErr) {
                                    target = null;
                                    savedUri = null;
                                    savedBytes = 0L;
                                    sessionNotice = "Stopped safely (storage unavailable)";
                                    notifyWarning(sessionNotice);
                                    break;
                                }
                            } else {
                                sessionNotice = "Stopped safely (storage write limit reached)";
                                notifyWarning("Storage full or disconnected · finalizing captured audio safely");
                                break;
                            }
                        }
                    }
                } else {
                    frames = hwFrames;
                    PcmConverter.computeMeter(rawBuf, hwFrames * curHwBpf, curHwFmt, sessionCh, meter);
                    if (writeToDisk && wavWriter != null && !pausedFlag.get()) {
                        if (Float.isFinite(meter.peak) && meter.peak > sessionMaxPeak) {
                            sessionMaxPeak = meter.peak;
                        }
                        try {
                            int outBytes = PcmConverter.convertToOutput(
                                    rawBuf, hwFrames * curHwBpf, curHwFmt, activeFormat, outBuf);
                            wavWriter.write(outBuf, outBytes);
                            recordedFrames += frames;
                            currentElapsedMs = (recordedFrames * 1000L) / sessionRate;
                            savedBytes = wavWriter.getTotalFileBytes();
                        } catch (Throwable writeErr) {
                            sessionNotice = "Stopped safely (storage write limit reached)";
                            notifyWarning("Storage full or disconnected · finalizing captured audio safely");
                            break;
                        }
                    }
                }

                // Automatic seamless WAV part-splitting before the 4 GiB RIFF ceiling!
                if (writeToDisk && wavWriter != null && wavWriter.shouldSplitForSizeLimit()) {
                    try {
                        wavWriter.finalizeAndFlush();
                        long partBytes = wavWriter.getTotalFileBytes();
                        Uri partUri = savedUri;
                        String partName = savedName;
                        if (target != null) {
                            try { target.close(); } catch (IOException ignored) {}
                        }
                        notifySaved(partUri, partName, partBytes,
                                "Part " + wavPartIndex + " saved (auto-split before 4 GB WAV limit)");

                        wavPartIndex++;
                        target = RecordingStorage.createTarget(this, activeFormat,
                                sessionRate, sessionCh, "_part" + wavPartIndex, false);
                        savedUri = target.uri;
                        savedName = target.displayName;
                        wavWriter = new WavWriter(target.channel, activeFormat, sessionRate, sessionCh);
                        savedBytes = wavWriter.getTotalFileBytes();
                        notifyWarning("Continued seamlessly into " + savedName);
                    } catch (Throwable splitErr) {
                        target = null;
                        wavWriter = null;
                        savedUri = null;
                        savedBytes = 0L;
                        notifyWarning("Could not open next WAV part · previous part saved safely");
                        break;
                    }
                }

                if (meter.peakL > bucketPeakL) bucketPeakL = meter.peakL;
                if (meter.peakR > bucketPeakR) bucketPeakR = meter.peakR;
                if (grDb < bucketWorstGr) bucketWorstGr = grDb;
                bucketRmsSqL += (double) meter.rmsL * (double) meter.rmsL * frames;
                bucketRmsSqR += (double) meter.rmsR * (double) meter.rmsR * frames;
                bucketChunks += frames;
                bucketClips += meter.clipCount;
                totalClips += meter.clipCount;
                bucketFramesAcc += frames;

                if (bucketFramesAcc >= meterBucketFrames) {
                    float rmsL = bucketChunks > 0 ? (float) Math.sqrt(bucketRmsSqL / bucketChunks) : 0f;
                    float rmsR = bucketChunks > 0 ? (float) Math.sqrt(bucketRmsSqR / bucketChunks) : 0f;
                    long elapsedMs = (recordedFrames * 1000L) / sessionRate;
                    currentElapsedMs = elapsedMs;
                    notifyMeter(bucketPeakL, rmsL, bucketPeakR, rmsR, sessionCh,
                            bucketClips > 0, bucketWorstGr, elapsedMs, savedBytes, lastDrops, totalClips);
                    bucketFramesAcc = 0;
                    bucketPeakL = 0f;
                    bucketPeakR = 0f;
                    bucketRmsSqL = 0.0;
                    bucketRmsSqR = 0.0;
                    bucketChunks = 0;
                    bucketClips = 0;
                    bucketWorstGr = 0f;
                }
            }

            // Stop monitor & microphone immediately
            if (monitorTrack != null) {
                try { monitorTrack.stop(); } catch (IllegalStateException ignored) {}
                monitorTrack.release();
                monitorTrack = null;
            }
            activeEngine = null;
            if (engine != null) {
                engine.stopAndClose();
                engine = null;
            }

            // Handle explicit user discard (while paused)
            if (discardFlag.get()) {
                scratchSafelySaved = true;
                if (mp3Writer != null) { try { mp3Writer.finalizeAndFlush(); } catch (Throwable ignored) {} mp3Writer = null; }
                if (oggWriter != null) { try { oggWriter.finalizeAndFlush(); } catch (Throwable ignored) {} oggWriter = null; }
                if (flacWriter != null) { try { flacWriter.finalizeAndFlush(); } catch (Throwable ignored) {} flacWriter = null; }
                if (wavWriter != null) { try { wavWriter.finalizeAndFlush(); } catch (Throwable ignored) {} wavWriter = null; }
                if (target != null) {
                    target.deleteSilently(this);
                    target = null;
                }
                savedUri = null;
                savedBytes = 0L;
                sessionMarkers.clear();
                notifyWarning("Recording discarded");
                return;
            }

            // Finalize & optionally run exact True Peak Normalization with multi-tier lossless rescue
            if (writeToDisk && target != null) {
                state = State.FINALIZING;
                notifyState();
                acquireWakeLock();

                if (useScratchForCompressedNorm && scratchChannel != null && scratchFile != null) {
                    notifyProcessing("PREPARING " + activeFormat.shortLabel,
                            "Syncing captured buffer before normalization...", -1);
                    try { scratchChannel.force(false); } catch (IOException ignored) {}
                    ScratchRecovery.writeMeta(scratchFile, sessionRate, sessionCh,
                            dspConfig.normTargetDb, sessionMaxPeak);
                    try { scratchRaf.close(); } catch (IOException ignored) {}
                    scratchRaf = null;
                    scratchChannel = null;

                    if (scratchBytesWritten <= 0L) {
                        scratchSafelySaved = true;
                        target.deleteSilently(this);
                        target = null;
                        savedUri = null;
                        savedBytes = 0L;
                    } else {
                        float estGain = DspChain.computeNormalizeGain(sessionMaxPeak, dspConfig.normTargetDb);
                        float estDb = (sessionMaxPeak > 1e-6f) ? (float) (20.0 * Math.log10(estGain)) : 0f;
                        String fmtTag = activeFormat.usesBitrate
                                ? (activeFormat.shortLabel + " " + config.compressedBitrateKbps + "k")
                                : activeFormat.shortLabel;
                        String stageTitle = String.format(Locale.US, "NORMALIZING & ENCODING %s (%+.1f dB)",
                                fmtTag, estDb);
                        long procStartMs = System.currentTimeMillis();

                        try {
                            float appliedDb = PostNormalizer.normalizeAndEncodeCompressed(
                                    scratchFile, target.channel, activeFormat,
                                    sessionRate, sessionCh, config.compressedBitrateKbps,
                                    sessionMaxPeak, dspConfig.normTargetDb,
                                    (done, total, pct) -> {
                                        String eta = formatEta(procStartMs, done, total);
                                        String detail = RecordingStorage.formatBytes(done) + " / "
                                                + RecordingStorage.formatBytes(total)
                                                + (eta.isEmpty() ? "" : "  ·  " + eta);
                                        notifyProcessing(stageTitle, detail, pct);
                                    });
                            savedBytes = target.channel.size();
                            if (savedBytes > 0L) {
                                scratchSafelySaved = true;
                                savedNote = combineNotes(
                                        String.format(Locale.US, "Normalized %+.1f dB -> %.1f dBFS",
                                                appliedDb, dspConfig.normTargetDb),
                                        sessionNotice);
                            }
                        } catch (Throwable primaryErr) {
                            target.deleteSilently(this);
                            target = null;
                            savedUri = null;
                            savedBytes = 0L;

                            if (activeFormat == AudioConfig.OutputFormat.OGG_OPUS) {
                                notifyWarning("Opus encoding failed · rescuing take as MP3");
                                try {
                                    RecordingStorage.RecordingTarget mp3Rescue = RecordingStorage.createTarget(
                                            this, AudioConfig.OutputFormat.MP3, sessionRate, sessionCh, "_rescue", false);
                                    target = mp3Rescue;
                                    long rStart = System.currentTimeMillis();
                                    float appliedDb = PostNormalizer.normalizeAndEncodeCompressed(
                                            scratchFile, target.channel, AudioConfig.OutputFormat.MP3,
                                            sessionRate, sessionCh, config.compressedBitrateKbps,
                                            sessionMaxPeak, dspConfig.normTargetDb,
                                            (done, total, pct) -> {
                                                String eta = formatEta(rStart, done, total);
                                                notifyProcessing("RESCUING AS MP3 " + config.compressedBitrateKbps + "k",
                                                        RecordingStorage.formatBytes(done) + " / "
                                                                + RecordingStorage.formatBytes(total)
                                                                + (eta.isEmpty() ? "" : "  ·  " + eta),
                                                        pct);
                                            });
                                    long sz = target.channel.size();
                                    if (sz > 0L) {
                                        savedUri = target.uri;
                                        savedName = target.displayName;
                                        savedBytes = sz;
                                        savedNote = String.format(Locale.US,
                                                "Rescued as MP3 (Opus failed) · %+.1f dB", appliedDb);
                                        scratchSafelySaved = true;
                                    } else {
                                        target.deleteSilently(this);
                                        target = null;
                                    }
                                } catch (Throwable mp3RescueErr) {
                                    if (target != null) {
                                        target.deleteSilently(this);
                                        target = null;
                                    }
                                }
                            }

                            if (!scratchSafelySaved) {
                                notifyWarning("Encoding failed · rescuing as lossless 24-bit WAV");
                                try {
                                    RecordingStorage.RecordingTarget wavRescue = RecordingStorage.createTarget(
                                            this, AudioConfig.OutputFormat.WAV_24, sessionRate, sessionCh,
                                            "_rescue", true);
                                    target = wavRescue;
                                    long wStart = System.currentTimeMillis();
                                    long wavBytes = PostNormalizer.convertScratchToLosslessWav(
                                            scratchFile, target.channel,
                                            sessionRate, sessionCh,
                                            sessionMaxPeak, true, dspConfig.normTargetDb,
                                            (done, total, pct) -> {
                                                String eta = formatEta(wStart, done, total);
                                                notifyProcessing("RESCUING AS LOSSLESS 24-BIT WAV",
                                                        RecordingStorage.formatBytes(done) + " / "
                                                                + RecordingStorage.formatBytes(total)
                                                                + (eta.isEmpty() ? "" : "  ·  " + eta),
                                                        pct);
                                            });
                                    if (wavBytes > WavWriter.headerSize(AudioConfig.OutputFormat.WAV_24)) {
                                        savedUri = target.uri;
                                        savedName = target.displayName;
                                        savedBytes = wavBytes;
                                        savedNote = "Rescued as lossless 24-bit WAV (no audio lost)";
                                        scratchSafelySaved = true;
                                    } else {
                                        target.deleteSilently(this);
                                        target = null;
                                    }
                                } catch (Throwable wavRescueErr) {
                                    if (target != null) {
                                        target.deleteSilently(this);
                                        target = null;
                                    }
                                    notifyError("Storage full — raw audio preserved in cache for auto-recovery");
                                }
                            }
                        }
                    }
                } else if (mp3Writer != null) {
                    notifyProcessing("FINALIZING MP3", "Flushing trailing frames & syncing to disk...", -1);
                    try { mp3Writer.finalizeAndFlush(); } catch (Throwable ignored) {}
                    savedBytes = mp3Writer.getTotalFileBytes();
                    savedNote = sessionNotice;
                } else if (flacWriter != null) {
                    notifyProcessing("FINALIZING FLAC", "Writing STREAMINFO duration & syncing...", -1);
                    try { flacWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
                    savedBytes = flacWriter.getTotalFileBytes();
                    savedNote = sessionNotice;
                } else if (oggWriter != null) {
                    notifyProcessing("FINALIZING OGG OPUS", "Writing EOS Ogg page & syncing to disk...", -1);
                    try { oggWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
                    savedBytes = oggWriter.getTotalFileBytes();
                    savedNote = sessionNotice;
                } else if (wavWriter != null) {
                    notifyProcessing("FINALIZING " + activeFormat.shortLabel,
                            "Patching RIFF/WAVE header & flushing to disk...", -1);
                    try { wavWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
                    savedBytes = wavWriter.getTotalFileBytes();
                    savedNote = sessionNotice;

                    if (wantNormalize && wavWriter.getDataBytesWritten() > 0L) {
                        float estGain = DspChain.computeNormalizeGain(sessionMaxPeak, dspConfig.normTargetDb);
                        float estDb = (sessionMaxPeak > 1e-6f) ? (float) (20.0 * Math.log10(estGain)) : 0f;
                        String stageTitle = String.format(Locale.US, "NORMALIZING %s (%+.1f dB)",
                                activeFormat.shortLabel, estDb);
                        long procStartMs = System.currentTimeMillis();
                        long dataOff = WavWriter.headerSize(activeFormat);
                        try (RecordingStorage.ReadableChannelHandle rh = target.openReadChannel(this)) {
                            float appliedDb = PostNormalizer.normalizePcmInPlace(
                                    rh.channel, target.channel, activeFormat,
                                    dataOff, wavWriter.getDataBytesWritten(),
                                    sessionMaxPeak, dspConfig.normTargetDb,
                                    (done, total, pct) -> {
                                        String eta = formatEta(procStartMs, done, total);
                                        String detail = RecordingStorage.formatBytes(done) + " / "
                                                + RecordingStorage.formatBytes(total)
                                                + String.format(Locale.US, "  ·  target %.1f dBFS", dspConfig.normTargetDb)
                                                + (eta.isEmpty() ? "" : "  ·  " + eta);
                                        notifyProcessing(stageTitle, detail, pct);
                                    });
                            if (Math.abs(appliedDb) >= 0.1f) {
                                savedNote = combineNotes(
                                        String.format(Locale.US, "Normalized %+.1f dB -> %.1f dBFS",
                                                appliedDb, dspConfig.normTargetDb),
                                        sessionNotice);
                            }
                        } catch (Throwable normErr) {
                            savedNote = combineNotes("Saved intact (normalization skipped)", sessionNotice);
                            notifyWarning("Normalization skipped (" + normErr.getMessage() + ") · original audio saved");
                        }
                    }
                }
            }

        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            notifyError(msg);
        } finally {
            if (monitorTrack != null) {
                try { monitorTrack.stop(); } catch (IllegalStateException ignored) {}
                monitorTrack.release();
            }
            activeEngine = null;
            activeDspChain = null;
            if (engine != null) {
                engine.stopAndClose();
            }
            if (mp3Writer != null) {
                try { mp3Writer.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
            if (flacWriter != null) {
                try { flacWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
            if (oggWriter != null) {
                try { oggWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
            if (wavWriter != null) {
                try { wavWriter.finalizeAndFlush(); } catch (Throwable ignored) {}
            }
            if (scratchRaf != null) {
                try { scratchRaf.close(); } catch (IOException ignored) {}
            }
            if (scratchFile != null && (scratchSafelySaved || scratchBytesWritten < 4096L)) {
                ScratchRecovery.deleteScratchAndMeta(scratchFile);
            }
            activeScratchFile = null;
            if (target != null) {
                try { target.close(); } catch (IOException ignored) {}
            }

            if (writeToDisk && savedName != null && savedBytes > 0L && !sessionMarkers.isEmpty()) {
                MarkerStore.save(this, savedName, getSessionMarkers());
            }
            sessionMarkers.clear();

            workerRunning.set(false);
            workerThread = null;
            sRecordingGlobally = false;
            state = State.IDLE;
            activeStreamInfo = null;
            lastProcStage = "";
            lastProcDetail = "";
            lastProcPercent = -1;
            releaseWakeLock();
            if (isForegroundActive) {
                isForegroundActive = false;
                try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
                stopSelf();
            }
            notifyState();

            if (writeToDisk && savedUri != null && savedBytes > 0L) {
                notifySaved(savedUri, savedName, savedBytes, savedNote);
            }
        }
    }

    private OpenedEngine attemptMicReconnect(AudioConfig baseCfg, int targetRate, int targetCh) {
        AudioConfig matchCfg = new AudioConfig(
                baseCfg.engineMode, baseCfg.inputPreset, baseCfg.outputFormat,
                targetRate, targetCh, baseCfg.deviceId,
                baseCfg.keepScreenOn, baseCfg.visualGain, baseCfg.compressedBitrateKbps,
                baseCfg.headphoneMonitor, baseCfg.outputDeviceId, baseCfg.outputDeviceType
        );
        for (int attempt = 0; attempt < 8 && workerRunning.get(); attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 250L : 650L);
            } catch (InterruptedException ignored) {
                return null;
            }
            if (!workerRunning.get()) return null;
            boolean forceDefault = (attempt >= 2);
            try {
                return openCaptureEngineWithFallback(matchCfg, forceDefault);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void adaptChannelsInFloat(ByteBuffer src, int frames, int inCh, int outCh, ByteBuffer dst) {
        src.order(ByteOrder.LITTLE_ENDIAN);
        dst.order(ByteOrder.LITTLE_ENDIAN);
        dst.clear();
        int srcNeed = frames * inCh * 4;
        if (src.limit() < srcNeed) {
            src.limit(Math.min(srcNeed, src.capacity()));
        }
        if (inCh == 1 && outCh == 2) {
            for (int f = 0; f < frames; f++) {
                float s = src.getFloat(f << 2);
                int off = f << 3;
                dst.putFloat(off, s);
                dst.putFloat(off + 4, s);
            }
        } else if (inCh == 2 && outCh == 1) {
            for (int f = 0; f < frames; f++) {
                int off = f << 3;
                float s = 0.5f * (src.getFloat(off) + src.getFloat(off + 4));
                dst.putFloat(f << 2, s);
            }
        }
    }

    private static String combineNotes(String primary, String secondary) {
        if (primary == null || primary.isEmpty()) return secondary;
        if (secondary == null || secondary.isEmpty()) return primary;
        return primary + " · " + secondary;
    }

    private static long currentWrittenBytes(WavWriter wav, Mp3FileWriter mp3,
                                            OggOpusWriter ogg, FlacFileWriter flac, long scratchBytes) {
        if (wav != null) return wav.getTotalFileBytes();
        if (mp3 != null) return mp3.getTotalFileBytes();
        if (ogg != null) return ogg.getTotalFileBytes();
        if (flac != null) return flac.getTotalFileBytes();
        return scratchBytes;
    }

    private void notifyState() {
        Callback cb = callback;
        if (cb != null) cb.onStateChanged(state, activeStreamInfo, activeConfig);
    }

    private void notifySaved(Uri uri, String fileName, long fileBytes, String note) {
        Callback cb = callback;
        if (cb != null) cb.onRecordingSaved(uri, fileName, fileBytes, note);
    }

    private void notifyWarning(String message) {
        Callback cb = callback;
        if (cb != null) cb.onWarning(message);
    }

    private void notifyError(String message) {
        Callback cb = callback;
        if (cb != null) cb.onError(message);
    }

    private void notifyProcessing(String stage, String detail, int percent) {
        lastProcStage = stage;
        lastProcDetail = detail;
        lastProcPercent = percent;
        Callback cb = callback;
        if (cb != null) cb.onProcessingProgress(stage, detail, percent);

        long now = System.currentTimeMillis();
        if (isForegroundActive && (now - lastNotifProgressMs >= 400L || percent == 100 || percent == 0)) {
            lastNotifProgressMs = now;
            updateProcessingNotification(stage, detail, percent);
        }
    }

    private void updateProcessingNotification(String stage, String detail, int percent) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notif = nb
                .setSmallIcon(R.drawable.ic_mic_notif)
                .setContentTitle(stage)
                .setContentText(detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, Math.min(100, percent)), percent < 0)
                .setContentIntent(contentPi)
                .build();
        nm.notify(NOTIF_ID, notif);
    }

    private static String formatEta(long startMs, long done, long total) {
        if (done <= 0L || total <= done) return "";
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs < 350L) return "";
        double rate = (double) done / (double) elapsedMs;
        if (rate <= 0.0) return "";
        long remSec = Math.round(((double) (total - done) / rate) / 1000.0);
        if (remSec <= 0L) return "<1s left";
        if (remSec < 60L) return "~" + remSec + "s left";
        return "~" + (remSec / 60L) + "m " + (remSec % 60L) + "s left";
    }

    private void notifyMeter(float peakL, float rmsL, float peakR, float rmsR, int channels,
                             boolean clipped, float grDb,
                             long elapsedMs, long fileBytes, int totalDrops, int totalClips) {
        Callback cb = callback;
        if (cb != null) {
            cb.onLiveMeter(peakL, rmsL, peakR, rmsR, channels, clipped, grDb,
                    elapsedMs, fileBytes, totalDrops, totalClips);
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void promoteToForeground(String statusText, boolean isPaused) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent pauseIntent = new Intent(this, RecordingService.class);
        pauseIntent.setAction(ACTION_TOGGLE_PAUSE);
        PendingIntent pausePi = PendingIntent.getService(
                this, 2, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        String detail = (activeConfig != null)
                ? activeConfig.formatShortDisplay() + " · " + activeConfig.inputPreset.shortLabel
                : "Raw Audio";

        Notification notif = nb
                .setSmallIcon(R.drawable.ic_mic_notif)
                .setContentTitle(statusText)
                .setContentText(detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setUsesChronometer(!isPaused)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(null, isPaused ? "Resume" : "Pause", pausePi).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), stopPi).build())
                .build();

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rekorder:RawAudioCapture");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null) {
            wakeLock.acquire(12 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) {}
        }
    }
}
