// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.rekorder.app.audio.AudioConfig;
import com.rekorder.app.audio.RawAudioPlayer;
import com.rekorder.app.audio.WavWriter;

/**
 * Dedicated foreground media playback service enabling uninterrupted background playback
 * when Rekorder is minimized or the screen is off.
 *
 * Zero third-party dependencies — uses native Android MediaSession, MediaStyle notification
 * controls (-10s / Play-Pause / +10s / Stop + lock-screen scrubber), AudioFocus management,
 * headphone-unplug auto-pause (ACTION_AUDIO_BECOMING_NOISY), and WakeLock handling.
 */
public final class PlaybackService extends Service implements RawAudioPlayer.Listener {

    public interface Callback {
        void onPlaybackProgress(Uri uri, long currentMs, long totalMs, boolean playing);
        void onPlaybackComplete(Uri uri);
        void onPlaybackStopped();
        void onPlaybackError(String message);
    }

    private static final String CHANNEL_ID = "rekorder_playback";
    private static final int NOTIF_ID = 1002;

    private static final String ACTION_PLAY_PAUSE = "com.rekorder.app.PLAYBACK_PLAY_PAUSE";
    private static final String ACTION_REWIND = "com.rekorder.app.PLAYBACK_REWIND";
    private static final String ACTION_FORWARD = "com.rekorder.app.PLAYBACK_FORWARD";
    private static final String ACTION_STOP = "com.rekorder.app.PLAYBACK_STOP";
    private static final long SEEK_STEP_MS = 10_000L;

    private final LocalBinder binder = new LocalBinder();
    private volatile Callback callback;

    private RawAudioPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;

    private volatile Uri currentUri;
    private volatile String currentFileName;
    private volatile long currentFileBytes;
    private volatile WavWriter.AudioFileMeta currentMeta;

    private volatile boolean isForeground = false;
    private volatile boolean resumeOnFocusGain = false;
    private volatile boolean noisyReceiverRegistered = false;
    private volatile long lastSessionStatePushMs = 0L;

    public final class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                if (isPlaying()) {
                    pausePlayback();
                }
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                if (isPlaying()) pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    resumeOnFocusGain = true;
                    pausePlayback();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setVolume(0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (player != null) player.setVolume(1.0f);
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    resumePlayback();
                }
                break;
            default:
                break;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        player = new RawAudioPlayer(this, this);
        AudioConfig savedCfg = AudioConfig.load(this);
        player.setPreferredOutputDevice(savedCfg.outputDeviceId, savedCfg.outputDeviceType);

        mediaSession = new MediaSession(this, "RekorderPlayback");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onStop() {
                stopPlayback();
            }

            @Override
            public void onSeekTo(long pos) {
                seekToMs(pos);
            }

            @Override
            public void onRewind() {
                seekByDeltaMs(-SEEK_STEP_MS);
            }

            @Override
            public void onFastForward() {
                seekByDeltaMs(SEEK_STEP_MS);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE:
                    if (isPlaying()) pausePlayback();
                    else resumePlayback();
                    break;
                case ACTION_REWIND:
                    seekByDeltaMs(-SEEK_STEP_MS);
                    break;
                case ACTION_FORWARD:
                    seekByDeltaMs(SEEK_STEP_MS);
                    break;
                case ACTION_STOP:
                    stopPlayback();
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopPlaybackInternal(false);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
        if (cb != null && currentUri != null && player != null && player.isActive()) {
            cb.onPlaybackProgress(currentUri, player.getCurrentPositionMs(),
                    player.getTotalDurationMs(), player.isPlaying());
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean isActive() {
        return player != null && player.isActive();
    }

    public Uri getCurrentUri() {
        return currentUri;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    public long getCurrentFileBytes() {
        return currentFileBytes;
    }

    public WavWriter.AudioFileMeta getCurrentMeta() {
        return currentMeta;
    }

    public long getCurrentPositionMs() {
        return (player != null) ? player.getCurrentPositionMs() : 0L;
    }

    public synchronized void playTrack(Uri uri, String fileName, long fileBytes,
                                       WavWriter.AudioFileMeta meta, long startMs) {
        if (uri == null || meta == null) return;

        this.currentUri = uri;
        this.currentFileName = (fileName != null && !fileName.isEmpty()) ? fileName : "Recording";
        this.currentFileBytes = fileBytes;
        this.currentMeta = meta;
        this.resumeOnFocusGain = false;

        requestAudioFocus();
        registerNoisyReceiver();
        acquireWakeLock();

        if (mediaSession != null) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, this.currentFileName)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Rekorder · " + meta.summaryLabel())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.durationMs())
                    .build());
            mediaSession.setActive(true);
        }

        Intent self = new Intent(this, PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(self);
            } else {
                startService(self);
            }
        } catch (Throwable ignored) {}

        player.setVolume(1.0f);
        player.playOrResume(uri, meta, startMs);
        updateMediaStateAndNotification(true, Math.max(0L, startMs >= 0L ? startMs : player.getCurrentPositionMs()), true);
    }

    public synchronized void pausePlayback() {
        if (player == null || !player.isActive()) return;
        player.pause();
        releaseWakeLock();
        updateMediaStateAndNotification(false, player.getCurrentPositionMs(), false);
    }

    public synchronized void resumePlayback() {
        if (player == null) return;
        if (player.isActive()) {
            requestAudioFocus();
            registerNoisyReceiver();
            acquireWakeLock();
            player.setVolume(1.0f);
            player.resume();
            updateMediaStateAndNotification(true, player.getCurrentPositionMs(), true);
        } else if (currentUri != null && currentMeta != null) {
            playTrack(currentUri, currentFileName, currentFileBytes, currentMeta, 0L);
        }
    }

    public float getPlaybackSpeed() {
        return (player != null) ? player.getPlaybackSpeed() : 1.0f;
    }

    public void setPlaybackSpeed(float speed) {
        if (player != null) {
            player.setPlaybackSpeed(speed);
            if (player.isActive()) {
                pushPlaybackState(player.isPlaying(), player.getCurrentPositionMs());
            }
        }
    }

    public void setPreferredOutputDevice(int deviceId, int deviceType) {
        if (player != null) {
            player.setPreferredOutputDevice(deviceId, deviceType);
        }
    }

    public void seekFraction(float fraction) {
        if (player != null && player.isActive()) {
            player.seekFraction(fraction);
            pushPlaybackState(player.isPlaying(), player.getCurrentPositionMs());
        }
    }

    public void seekToMs(long targetMs) {
        if (player != null && player.isActive()) {
            player.seekToMs(targetMs);
            pushPlaybackState(player.isPlaying(), player.getCurrentPositionMs());
        }
    }

    public void seekByDeltaMs(long deltaMs) {
        if (player != null && player.isActive()) {
            player.seekByDeltaMs(deltaMs);
            pushPlaybackState(player.isPlaying(), player.getCurrentPositionMs());
        }
    }

    public synchronized void stopPlayback() {
        stopPlaybackInternal(true);
    }

    private void stopPlaybackInternal(boolean notifyCb) {
        resumeOnFocusGain = false;
        if (player != null) {
            player.stop();
        }
        unregisterNoisyReceiver();
        abandonAudioFocus();
        releaseWakeLock();

        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build());
            mediaSession.setActive(false);
        }

        if (isForeground) {
            isForeground = false;
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        }
        stopSelf();

        if (notifyCb) {
            Callback cb = callback;
            if (cb != null) cb.onPlaybackStopped();
        }
    }

    /* ---------- RawAudioPlayer.Listener ---------- */

    @Override
    public void onPlaybackProgress(long currentMs, long totalMs, boolean playing) {
        long now = System.currentTimeMillis();
        if (now - lastSessionStatePushMs >= 1000L) {
            lastSessionStatePushMs = now;
            pushPlaybackState(playing, currentMs);
        }
        Callback cb = callback;
        if (cb != null) {
            cb.onPlaybackProgress(currentUri, currentMs, totalMs, playing);
        }
    }

    @Override
    public void onPlaybackComplete() {
        Uri doneUri = currentUri;
        releaseWakeLock();
        unregisterNoisyReceiver();
        abandonAudioFocus();

        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                    .build());
        }
        if (isForeground) {
            isForeground = false;
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        }
        stopSelf();

        Callback cb = callback;
        if (cb != null) {
            cb.onPlaybackComplete(doneUri);
        }
    }

    @Override
    public void onPlaybackError(String message) {
        stopPlaybackInternal(false);
        Callback cb = callback;
        if (cb != null) {
            cb.onPlaybackError(message);
        }
    }

    /* ---------- MediaSession & Notification Helpers ---------- */

    private void pushPlaybackState(boolean playing, long posMs) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_FAST_FORWARD;
        int state = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        float speed = playing ? getPlaybackSpeed() : 0.0f;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, Math.max(0L, posMs), speed)
                .build());
    }

    private void updateMediaStateAndNotification(boolean playing, long posMs, boolean promoteForeground) {
        pushPlaybackState(playing, posMs);
        Notification notif = buildNotification(playing);
        if (promoteForeground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIF_ID, notif);
                }
                isForeground = true;
                return;
            } catch (Throwable ignored) {
                // Fall through to regular notify if FGS promotion was restricted
            }
        }
        if (!playing && isForeground) {
            isForeground = false;
            try { stopForeground(STOP_FOREGROUND_DETACH); } catch (Throwable ignored) {}
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notif);
        }
    }

    private Notification buildNotification(boolean playing) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent rewPi = serviceActionPi(ACTION_REWIND, 11);
        PendingIntent togglePi = serviceActionPi(ACTION_PLAY_PAUSE, 12);
        PendingIntent fwdPi = serviceActionPi(ACTION_FORWARD, 13);
        PendingIntent stopPi = serviceActionPi(ACTION_STOP, 14);

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);
        if (mediaSession != null) {
            style.setMediaSession(mediaSession.getSessionToken());
        }

        String title = (currentFileName != null) ? currentFileName : "Rekorder";
        String subtitle = (currentMeta != null) ? currentMeta.summaryLabel() : "Audio Playback";

        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return nb
                .setSmallIcon(R.drawable.ic_mic_notif)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(contentPi)
                .setDeleteIntent(stopPi)
                .setStyle(style)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_rewind), "-10s", rewPi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "Pause" : "Play", togglePi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_forward), "+10s", fwdPi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_close), "Stop", stopPi).build())
                .build();
    }

    private PendingIntent serviceActionPi(String action, int reqCode) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(
                this, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_playback_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    /* ---------- AudioFocus, Noisy Receiver, WakeLock ---------- */

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            audioManager.requestAudioFocus(focusRequest);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
        noisyReceiverRegistered = true;
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        noisyReceiverRegistered = false;
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rekorder:BackgroundPlayback");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(12 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) {}
        }
    }
}
