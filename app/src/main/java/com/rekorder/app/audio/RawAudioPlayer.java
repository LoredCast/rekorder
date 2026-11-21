// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.rekorder.app.dsp.AudioResampler;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Universal AudioTrack playback engine supporting:
 *  - Lossless WAV (16-bit, 24-bit, 32-bit float) and RAW (.pcm) via direct FileChannel
 *    with automatic sample-rate (48 kHz resampled) and PCM-16 fallback for Bluetooth/speakers
 *  - Compressed OGG Opus (.ogg) and MP3 (.mp3) via built-in MediaExtractor + MediaCodec
 *    with dynamic output format handling and instant waveform/MediaSession scrubbing.
 */
public final class RawAudioPlayer {

    public interface Listener {
        void onPlaybackProgress(long currentMs, long totalMs, boolean playing);
        void onPlaybackComplete();
        void onPlaybackError(String message);
    }

    private final Context context;
    private volatile Listener listener;

    private Thread playThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong pendingSeekMs = new AtomicLong(-1L);

    private volatile Uri currentUri;
    private volatile WavWriter.AudioFileMeta currentMeta;
    private volatile AudioTrack activeTrack;
    private volatile float volume = 1.0f;
    private volatile float playbackSpeed = 1.0f;
    private volatile int preferredOutputDeviceId = 0;
    private volatile int preferredOutputDeviceType = 0;
    private volatile long currentPosMs = 0L;
    private volatile long totalDurMs = 0L;

    public RawAudioPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isPlaying() {
        return running.get() && !paused.get();
    }

    public boolean isPaused() {
        return running.get() && paused.get();
    }

    public boolean isActive() {
        return running.get();
    }

    public Uri getCurrentUri() {
        return currentUri;
    }

    public WavWriter.AudioFileMeta getCurrentMeta() {
        return currentMeta;
    }

    public long getCurrentPositionMs() {
        return currentPosMs;
    }

    public long getTotalDurationMs() {
        return totalDurMs;
    }

    public void setVolume(float vol) {
        float clamped = Math.max(0f, Math.min(1f, vol));
        this.volume = clamped;
        AudioTrack t = activeTrack;
        if (t != null) {
            try { t.setVolume(clamped); } catch (IllegalStateException ignored) {}
        }
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public void setPlaybackSpeed(float speed) {
        float clamped = Math.max(0.5f, Math.min(2.0f, speed));
        this.playbackSpeed = clamped;
        applySpeedToTrack(activeTrack, clamped);
    }

    public int getPreferredOutputDeviceId() {
        return preferredOutputDeviceId;
    }

    /**
     * Routes playback to a specific Android output sink (e.g. Phone Speaker even while a USB mic
     * is plugged in). Takes effect immediately even mid-playback!
     */
    public void setPreferredOutputDevice(int deviceId, int deviceType) {
        this.preferredOutputDeviceId = Math.max(0, deviceId);
        this.preferredOutputDeviceType = Math.max(0, deviceType);
        applyPreferredOutputDevice(activeTrack);
    }

    private void applyPreferredOutputDevice(AudioTrack t) {
        if (t == null) return;
        try {
            AudioDeviceInfo dev = (preferredOutputDeviceId > 0 || preferredOutputDeviceType > 0)
                    ? AudioDeviceHelper.findOutputDevice(context, preferredOutputDeviceId, preferredOutputDeviceType)
                    : null;
            t.setPreferredDevice(dev);
        } catch (Throwable ignored) {}
    }

    private static void applySpeedToTrack(AudioTrack t, float spd) {
        if (t == null) return;
        try {
            PlaybackParams pp = new PlaybackParams();
            pp.setSpeed(spd);
            pp.setPitch(1.0f);
            t.setPlaybackParams(pp);
        } catch (Throwable ignored) {
            // Some low-end HALs restrict PlaybackParams on certain sample rates
        }
    }

    public synchronized void playOrResume(Uri uri, WavWriter.AudioFileMeta meta, long startMs) {
        if (running.get() && uri != null && uri.equals(currentUri)) {
            if (startMs >= 0L) {
                long clamped = clampMs(startMs, totalDurMs);
                currentPosMs = clamped;
                pendingSeekMs.set(clamped);
            }
            paused.set(false);
            Listener l = listener;
            if (l != null) {
                l.onPlaybackProgress(currentPosMs, totalDurMs, true);
            }
            return;
        }
        stop();

        this.currentUri = uri;
        this.currentMeta = meta;
        this.totalDurMs = Math.max(0L, meta.durationMs());
        long initialMs = clampMs(Math.max(0L, startMs), this.totalDurMs);
        this.currentPosMs = initialMs;
        this.paused.set(false);
        this.pendingSeekMs.set(-1L);
        this.running.set(true);

        playThread = new Thread(() -> {
            if (meta.isCompressed) {
                runCompressedLoop(uri, meta, initialMs);
            } else {
                runPcmLoop(uri, meta, initialMs);
            }
        }, "RekorderPlayer");
        playThread.start();
    }

    public void pause() {
        if (!running.get()) return;
        paused.set(true);
        Listener l = listener;
        if (l != null) {
            l.onPlaybackProgress(currentPosMs, totalDurMs, false);
        }
    }

    public void resume() {
        if (!running.get()) return;
        paused.set(false);
        Listener l = listener;
        if (l != null) {
            l.onPlaybackProgress(currentPosMs, totalDurMs, true);
        }
    }

    public void seekFraction(float fraction) {
        WavWriter.AudioFileMeta m = currentMeta;
        if (m == null) return;
        long totalMs = Math.max(totalDurMs, m.durationMs());
        long targetMs = (long) (Math.max(0f, Math.min(1f, fraction)) * totalMs);
        seekToMs(targetMs);
    }

    public void seekToMs(long targetMs) {
        long clamped = clampMs(targetMs, totalDurMs);
        currentPosMs = clamped;
        pendingSeekMs.set(clamped);
        Listener l = listener;
        if (l != null) {
            l.onPlaybackProgress(clamped, totalDurMs, isPlaying());
        }
    }

    public void seekByDeltaMs(long deltaMs) {
        seekToMs(currentPosMs + deltaMs);
    }

    public synchronized void stop() {
        running.set(false);
        paused.set(false);
        Thread t = playThread;
        playThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        activeTrack = null;
    }

    private static long clampMs(long ms, long maxMs) {
        if (ms < 0L) return 0L;
        if (maxMs > 0L && ms > maxMs) return maxMs;
        return ms;
    }

    private static final class OpenedTrack {
        final AudioTrack track;
        final int outputRate;
        final boolean isFloat;

        OpenedTrack(AudioTrack track, int outputRate, boolean isFloat) {
            this.track = track;
            this.outputRate = outputRate;
            this.isFloat = isFloat;
        }
    }

    /**
     * Opens an AudioTrack with graceful fallback for high sample rates (e.g. 96/192 kHz on Bluetooth)
     * and devices that prefer PCM_16BIT over PCM_FLOAT.
     */
    private OpenedTrack openAudioTrackWithFallback(int desiredRate, int channels, boolean preferFloat)
            throws IOException {
        int chMask = (channels == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int[] rates = (desiredRate == 48000) ? new int[]{48000, 44100} : new int[]{desiredRate, 48000, 44100};
        int[] encs = preferFloat
                ? new int[]{AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT}
                : new int[]{AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT};

        for (int tryRate : rates) {
            for (int enc : encs) {
                int minBuf = AudioTrack.getMinBufferSize(tryRate, chMask, enc);
                if (minBuf <= 0) continue;
                int bufSize = Math.max(minBuf * 3, 24576);
                try {
                    AudioTrack track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(tryRate)
                                    .setChannelMask(chMask)
                                    .setEncoding(enc)
                                    .build())
                            .setBufferSizeInBytes(bufSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    if (track.getState() == AudioTrack.STATE_INITIALIZED) {
                        track.setVolume(volume);
                        applyPreferredOutputDevice(track);
                        if (Math.abs(playbackSpeed - 1.0f) >= 0.01f) {
                            applySpeedToTrack(track, playbackSpeed);
                        }
                        return new OpenedTrack(track, tryRate, enc == AudioFormat.ENCODING_PCM_FLOAT);
                    }
                    track.release();
                } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                }
            }
        }
        throw new IOException("Cannot open audio output for " + desiredRate + " Hz");
    }

    /* ---------- Direct Lossless WAV & RAW PCM Loop ---------- */

    private void runPcmLoop(Uri uri, WavWriter.AudioFileMeta meta, long initialMs) {
        AudioTrack track = null;
        ParcelFileDescriptor pfd = null;
        FileInputStream fis = null;
        try {
            OpenedTrack ot = openAudioTrackWithFallback(meta.sampleRate, meta.channels, true);
            track = ot.track;
            activeTrack = track;
            AudioResampler resampler = (ot.outputRate != meta.sampleRate)
                    ? new AudioResampler(meta.sampleRate, ot.outputRate, meta.channels)
                    : null;

            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Cannot open file for playback");
            fis = new FileInputStream(pfd.getFileDescriptor());
            FileChannel fc = fis.getChannel();

            int bpf = meta.bytesPerFrame();
            long totalFrames = (bpf > 0) ? (meta.dataBytes / bpf) : 0L;
            long framePos = Math.min((initialMs * meta.sampleRate) / 1000L, totalFrames);
            fc.position(meta.dataOffset + framePos * bpf);

            int chunkFrames = 2048;
            ByteBuffer srcBuf = ByteBuffer.allocate(chunkFrames * bpf).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer floatBuf = ByteBuffer.allocateDirect(chunkFrames * meta.channels * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer rsBuf = (resampler != null)
                    ? ByteBuffer.allocateDirect(chunkFrames * 4 * meta.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    : null;
            ByteBuffer i16Buf = (!ot.isFloat)
                    ? ByteBuffer.allocateDirect(chunkFrames * 4 * meta.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                    : null;

            track.play();
            long totalMs = meta.durationMs();
            totalDurMs = totalMs;
            long lastNotify = 0L;

            while (running.get()) {
                long seekToMs = pendingSeekMs.getAndSet(-1L);
                if (seekToMs >= 0L) {
                    framePos = Math.min((seekToMs * meta.sampleRate) / 1000L, totalFrames);
                    fc.position(meta.dataOffset + framePos * bpf);
                    track.pause();
                    track.flush();
                    if (!paused.get()) track.play();
                }

                if (paused.get()) {
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause();
                        currentPosMs = framePos * 1000L / Math.max(1, meta.sampleRate);
                        Listener l = listener;
                        if (l != null) l.onPlaybackProgress(currentPosMs, totalMs, false);
                    }
                    try { Thread.sleep(25); } catch (InterruptedException ignored) { break; }
                    continue;
                } else if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play();
                }

                long remFrames = totalFrames - framePos;
                if (remFrames <= 0) {
                    // Let remaining AudioTrack hardware buffer play out cleanly (~120ms)
                    try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                    running.set(false);
                    currentPosMs = totalMs;
                    Listener l = listener;
                    if (l != null) {
                        l.onPlaybackProgress(totalMs, totalMs, false);
                        l.onPlaybackComplete();
                    }
                    break;
                }

                int readFrames = (int) Math.min(chunkFrames, remFrames);
                int readBytes = readFrames * bpf;
                srcBuf.clear();
                srcBuf.limit(readBytes);

                int got = 0;
                while (got < readBytes) {
                    int n = fc.read(srcBuf);
                    if (n < 0) break;
                    got += n;
                }
                int actualFrames = got / bpf;
                if (actualFrames <= 0) {
                    running.set(false);
                    Listener l = listener;
                    if (l != null) l.onPlaybackComplete();
                    break;
                }

                int floatBytes = PcmConverter.convertToOutput(
                        srcBuf, actualFrames * bpf, meta.pcmFormat, AudioConfig.OutputFormat.WAV_32F, floatBuf);
                ByteBuffer playFloat = floatBuf;
                int playFloatBytes = floatBytes;

                if (resampler != null && rsBuf != null) {
                    int rsFrames = resampler.process(floatBuf, actualFrames, rsBuf);
                    playFloat = rsBuf;
                    playFloatBytes = rsFrames * meta.channels * 4;
                }

                if (playFloatBytes > 0) {
                    if (ot.isFloat) {
                        playFloat.limit(playFloatBytes);
                        playFloat.position(0);
                        track.write(playFloat, playFloatBytes, AudioTrack.WRITE_BLOCKING);
                    } else if (i16Buf != null) {
                        int i16Bytes = PcmConverter.convertToOutput(
                                playFloat, playFloatBytes, PcmConverter.FMT_PCM_FLOAT,
                                AudioConfig.OutputFormat.WAV_16, i16Buf);
                        i16Buf.limit(i16Bytes);
                        i16Buf.position(0);
                        track.write(i16Buf, i16Bytes, AudioTrack.WRITE_BLOCKING);
                    }
                }

                framePos += actualFrames;
                currentPosMs = framePos * 1000L / Math.max(1, meta.sampleRate);

                long now = System.currentTimeMillis();
                Listener l = listener;
                if (l != null && now - lastNotify >= 40) {
                    lastNotify = now;
                    l.onPlaybackProgress(currentPosMs, totalMs, true);
                }
            }
        } catch (Throwable t) {
            Listener l = listener;
            if (running.get() && l != null) {
                l.onPlaybackError(t.getMessage() != null ? t.getMessage() : "Playback error");
            }
        } finally {
            running.set(false);
            activeTrack = null;
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            try { if (fis != null) fis.close(); } catch (IOException ignored) {}
            try { if (pfd != null) pfd.close(); } catch (IOException ignored) {}
        }
    }

    /* ---------- Compressed OGG Opus & MP3 Playback Loop ---------- */

    private void runCompressedLoop(Uri uri, WavWriter.AudioFileMeta meta, long initialMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        AudioTrack track = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Cannot open file for playback");
            extractor.setDataSource(pfd.getFileDescriptor());

            int trackIdx = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i;
                    format = f;
                    break;
                }
            }
            if (trackIdx < 0 || format == null) throw new IOException("No audio track found");
            extractor.selectTrack(trackIdx);

            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : meta.sampleRate;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : meta.channels;

            OpenedTrack ot = openAudioTrackWithFallback(sampleRate, channels, false);
            track = ot.track;
            activeTrack = track;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            if (initialMs > 0L) {
                extractor.seekTo(initialMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            track.play();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcmScratch = new byte[16384];
            boolean inputDone = false;
            long currentMs = initialMs;
            long totalMs = Math.max(meta.durationMs(),
                    format.containsKey(MediaFormat.KEY_DURATION)
                            ? (format.getLong(MediaFormat.KEY_DURATION) / 1000L) : 0L);
            totalDurMs = totalMs;
            long lastNotify = 0L;

            while (running.get()) {
                long seekToMs = pendingSeekMs.getAndSet(-1L);
                if (seekToMs >= 0L) {
                    extractor.seekTo(seekToMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    codec.flush();
                    inputDone = false;
                    currentMs = seekToMs;
                    currentPosMs = seekToMs;
                    track.pause();
                    track.flush();
                    if (!paused.get()) track.play();
                }

                if (paused.get()) {
                    if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause();
                        Listener l = listener;
                        if (l != null) l.onPlaybackProgress(currentMs, totalMs, false);
                    }
                    try { Thread.sleep(25); } catch (InterruptedException ignored) { break; }
                    continue;
                } else if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play();
                }

                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(5000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            int n = extractor.readSampleData(inBuf, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    int newSr = outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
                    int newCh = outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                    if (newSr != sampleRate || newCh != channels) {
                        sampleRate = newSr;
                        channels = newCh;
                        try { track.stop(); } catch (IllegalStateException ignored) {}
                        track.release();
                        OpenedTrack rebuilt = openAudioTrackWithFallback(sampleRate, channels, false);
                        track = rebuilt.track;
                        activeTrack = track;
                        if (!paused.get()) track.play();
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        if (pcmScratch.length < info.size) {
                            pcmScratch = new byte[info.size];
                        }
                        outBuf.limit(info.offset + info.size);
                        outBuf.position(info.offset);
                        outBuf.get(pcmScratch, 0, info.size);
                        track.write(pcmScratch, 0, info.size, AudioTrack.WRITE_BLOCKING);

                        if (info.presentationTimeUs >= 0) {
                            currentMs = info.presentationTimeUs / 1000L;
                        } else {
                            int frames = info.size / Math.max(1, channels * 2);
                            currentMs += (frames * 1000L) / Math.max(1, sampleRate);
                        }
                        currentPosMs = Math.min(currentMs, totalMs > 0 ? totalMs : currentMs);
                        long now = System.currentTimeMillis();
                        Listener l = listener;
                        if (l != null && now - lastNotify >= 40) {
                            lastNotify = now;
                            l.onPlaybackProgress(currentPosMs, totalMs, true);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                        running.set(false);
                        currentPosMs = totalMs;
                        Listener l = listener;
                        if (l != null) {
                            l.onPlaybackProgress(totalMs, totalMs, false);
                            l.onPlaybackComplete();
                        }
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Listener l = listener;
            if (running.get() && l != null) {
                l.onPlaybackError(t.getMessage() != null ? t.getMessage() : "Playback error");
            }
        } finally {
            running.set(false);
            activeTrack = null;
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            try { if (pfd != null) pfd.close(); } catch (IOException ignored) {}
        }
    }
}
