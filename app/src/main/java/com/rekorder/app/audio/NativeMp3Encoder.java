// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

import java.nio.ByteBuffer;

/**
 * JNI wrapper for Rekorder's built-in freestanding pure-C MPEG-1 Layer III (MP3) encoder
 * (librekorder_mp3.so). Zero third-party dependencies.
 */
public final class NativeMp3Encoder {

    private static final boolean LOADED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("rekorder_mp3");
            ok = true;
        } catch (UnsatisfiedLinkError ignored) {
            ok = false;
        }
        LOADED = ok;
    }

    public static boolean isAvailable() {
        return LOADED;
    }

    private long handle = 0L;

    public NativeMp3Encoder(int sampleRate, int channels, int bitrateKbps) {
        if (!LOADED) {
            throw new IllegalStateException("Native MP3 encoder library unavailable");
        }
        this.handle = nativeCreate(sampleRate, channels, bitrateKbps);
        if (this.handle == 0L) {
            throw new IllegalArgumentException("Unsupported MP3 parameters: "
                    + sampleRate + "Hz " + channels + "ch " + bitrateKbps + "kbps");
        }
    }

    public int encode(ByteBuffer directFloatIn, int numFrames, ByteBuffer directMp3Out) {
        if (handle == 0L || numFrames <= 0) return 0;
        return nativeEncode(handle, directFloatIn, numFrames, directMp3Out);
    }

    public int flush(ByteBuffer directMp3Out) {
        if (handle == 0L) return 0;
        return nativeFlush(handle, directMp3Out);
    }

    public void close() {
        long h = handle;
        handle = 0L;
        if (h != 0L) {
            nativeDestroy(h);
        }
    }

    private static native long nativeCreate(int sampleRate, int channels, int bitrateKbps);

    private static native int nativeEncode(long handle, ByteBuffer directFloatIn,
                                           int numFrames, ByteBuffer directMp3Out);

    private static native int nativeFlush(long handle, ByteBuffer directMp3Out);

    private static native void nativeDestroy(long handle);
}
