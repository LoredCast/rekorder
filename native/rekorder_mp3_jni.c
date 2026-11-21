/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * JNI bridge for Rekorder's built-in pure-C MPEG-1 Layer III (MP3) encoder (librekorder_mp3.so).
 * Depends ONLY on Android Bionic libc.so (zero libm, zero libstdc++, zero third-party deps).
 */

#include <stdint.h>
#include <stddef.h>
#include <jni.h>

#include "mp3_encoder.h"

JNIEXPORT jlong JNICALL
Java_com_rekorder_app_audio_NativeMp3Encoder_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jint sampleRate,
    jint channels,
    jint bitrateKbps
) {
    (void)env;
    (void)clazz;
    RekorderMp3Encoder *enc = mp3_enc_create((int32_t)sampleRate, (int32_t)channels, (int32_t)bitrateKbps);
    return (jlong)(uintptr_t)enc;
}

JNIEXPORT jint JNICALL
Java_com_rekorder_app_audio_NativeMp3Encoder_nativeEncode(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jobject directFloatIn,
    jint numFrames,
    jobject directMp3Out
) {
    (void)clazz;
    RekorderMp3Encoder *enc = (RekorderMp3Encoder *)(uintptr_t)handle;
    if (!enc || !directFloatIn || !directMp3Out || numFrames <= 0) return 0;

    const float *pcm = (const float *)(*env)->GetDirectBufferAddress(env, directFloatIn);
    uint8_t *out = (uint8_t *)(*env)->GetDirectBufferAddress(env, directMp3Out);
    jlong outCap = (*env)->GetDirectBufferCapacity(env, directMp3Out);
    if (!pcm || !out || outCap <= 0) return -1;

    return (jint)mp3_enc_encode(enc, pcm, (int32_t)numFrames, out, (int32_t)outCap);
}

JNIEXPORT jint JNICALL
Java_com_rekorder_app_audio_NativeMp3Encoder_nativeFlush(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jobject directMp3Out
) {
    (void)clazz;
    RekorderMp3Encoder *enc = (RekorderMp3Encoder *)(uintptr_t)handle;
    if (!enc || !directMp3Out) return 0;

    uint8_t *out = (uint8_t *)(*env)->GetDirectBufferAddress(env, directMp3Out);
    jlong outCap = (*env)->GetDirectBufferCapacity(env, directMp3Out);
    if (!out || outCap <= 0) return -1;

    return (jint)mp3_enc_flush(enc, out, (int32_t)outCap);
}

JNIEXPORT void JNICALL
Java_com_rekorder_app_audio_NativeMp3Encoder_nativeDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    RekorderMp3Encoder *enc = (RekorderMp3Encoder *)(uintptr_t)handle;
    if (enc) {
        mp3_enc_destroy(enc);
    }
}
