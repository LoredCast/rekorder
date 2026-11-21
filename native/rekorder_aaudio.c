/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Rekorder Native AAudio Direct-Capture Driver (librekorder_aaudio.so)
 *
 * Talks directly to Android's system libaaudio.so with zero third-party libraries
 * (no Oboe, no C++ runtime, no STL). Uses a lock-free C11 atomic SPSC ring buffer
 * so the real-time SCHED_FIFO audio callback performs zero locks, zero syscalls,
 * zero allocations, and zero JNI calls.
 */

#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>
#include <jni.h>

#include "aaudio_minimal.h"
#include "spsc_ring.h"

/* Standard Bionic libc memory functions (ABI-stable across all Android versions). */
extern void *calloc(size_t nmemb, size_t size);
extern void *malloc(size_t size);
extern void free(void *ptr);
extern void *memcpy(void *dst, const void *src, size_t n);

/* 2 MiB power-of-two ring buffer: ~14.5s at 48kHz/24b/mono, ~1.36s at 192kHz/32f/stereo. */
#define REKORDER_RING_CAPACITY (1u << 21)

typedef struct {
    AAudioStream *stream;
    uint8_t *ringBacking;
    SpscRing ring;
    uint32_t bytesPerFrame;
    _Atomic uint32_t paused;
    _Atomic uint32_t stopping;
    _Atomic uint32_t ringOverruns;
    _Atomic int32_t errorCode;
} RekorderStreamCtx;

static uint32_t bytes_per_sample_for_format(aaudio_format_t fmt) {
    switch (fmt) {
        case AAUDIO_FORMAT_PCM_I16:        return 2u;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3u;
        case AAUDIO_FORMAT_PCM_FLOAT:      return 4u;
        case AAUDIO_FORMAT_PCM_I32:        return 4u;
        default:                           return 0u;
    }
}

static aaudio_data_callback_result_t on_audio_data(
    AAudioStream *stream,
    void *userData,
    void *audioData,
    int32_t numFrames
) {
    (void)stream;
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)userData;
    if (!ctx || atomic_load_explicit(&ctx->stopping, memory_order_acquire) != 0u) {
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    if (atomic_load_explicit(&ctx->paused, memory_order_relaxed) != 0u) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    if (numFrames <= 0 || !audioData) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    uint32_t bytes = (uint32_t)numFrames * ctx->bytesPerFrame;
    if (!spsc_write_all(&ctx->ring, (const uint8_t *)audioData, bytes, memcpy)) {
        atomic_fetch_add_explicit(&ctx->ringOverruns, 1u, memory_order_relaxed);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void on_audio_error(
    AAudioStream *stream,
    void *userData,
    aaudio_result_t error
) {
    (void)stream;
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)userData;
    if (ctx) {
        atomic_store_explicit(&ctx->errorCode, (int32_t)error, memory_order_release);
    }
}

JNIEXPORT jlong JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeOpen(
    JNIEnv *env,
    jclass clazz,
    jint deviceId,
    jint sampleRate,
    jint channelCount,
    jint format,
    jint inputPreset,
    jint sharingMode,
    jint performanceMode,
    jintArray outInfo
) {
    (void)clazz;

    jint info[10] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    RekorderStreamCtx *ctx = (RekorderStreamCtx *)calloc(1u, sizeof(RekorderStreamCtx));
    if (!ctx) {
        info[9] = -899; /* Allocation error */
        if (outInfo) {
            (*env)->SetIntArrayRegion(env, outInfo, 0, 10, info);
        }
        return 0;
    }

    ctx->ringBacking = (uint8_t *)malloc(REKORDER_RING_CAPACITY);
    if (!ctx->ringBacking) {
        free(ctx);
        info[9] = -899;
        if (outInfo) {
            (*env)->SetIntArrayRegion(env, outInfo, 0, 10, info);
        }
        return 0;
    }

    spsc_init(&ctx->ring, ctx->ringBacking, REKORDER_RING_CAPACITY);
    atomic_init(&ctx->paused, 0u);
    atomic_init(&ctx->stopping, 0u);
    atomic_init(&ctx->ringOverruns, 0u);
    atomic_init(&ctx->errorCode, AAUDIO_OK);

    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t res = AAudio_createStreamBuilder(&builder);
    if (res != AAUDIO_OK || !builder) {
        free(ctx->ringBacking);
        free(ctx);
        info[9] = (jint)res;
        if (outInfo) {
            (*env)->SetIntArrayRegion(env, outInfo, 0, 10, info);
        }
        return 0;
    }

    /* Configure direct unprocessed input stream.
     * Deliberately do NOT allocate an audio session ID so the HAL can grant
     * true direct-hardware Exclusive MMAP bypass when supported. */
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, (int32_t)deviceId);
    }
    if (sampleRate > 0) {
        AAudioStreamBuilder_setSampleRate(builder, (int32_t)sampleRate);
    }
    if (channelCount > 0) {
        AAudioStreamBuilder_setChannelCount(builder, (int32_t)channelCount);
    }
    if (format > 0) {
        AAudioStreamBuilder_setFormat(builder, (aaudio_format_t)format);
    }
    if (inputPreset > 0) {
        AAudioStreamBuilder_setInputPreset(builder, (aaudio_input_preset_t)inputPreset);
    }
    AAudioStreamBuilder_setSharingMode(builder, (aaudio_sharing_mode_t)sharingMode);
    AAudioStreamBuilder_setPerformanceMode(builder, (aaudio_performance_mode_t)performanceMode);
    AAudioStreamBuilder_setDataCallback(builder, on_audio_data, ctx);
    AAudioStreamBuilder_setErrorCallback(builder, on_audio_error, ctx);

    AAudioStream *stream = NULL;
    res = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK || !stream) {
        free(ctx->ringBacking);
        free(ctx);
        info[9] = (jint)res;
        if (outInfo) {
            (*env)->SetIntArrayRegion(env, outInfo, 0, 10, info);
        }
        return 0;
    }

    int32_t actualRate = AAudioStream_getSampleRate(stream);
    int32_t actualCh = AAudioStream_getChannelCount(stream);
    aaudio_format_t actualFmt = AAudioStream_getFormat(stream);
    uint32_t bps = bytes_per_sample_for_format(actualFmt);

    if (actualRate <= 0 || actualCh <= 0 || bps == 0u) {
        AAudioStream_close(stream);
        free(ctx->ringBacking);
        free(ctx);
        info[9] = -898; /* Unsupported hardware format */
        if (outInfo) {
            (*env)->SetIntArrayRegion(env, outInfo, 0, 10, info);
        }
        return 0;
    }

    ctx->stream = stream;
    ctx->bytesPerFrame = bps * (uint32_t)actualCh;

    info[0] = (jint)actualRate;
    info[1] = (jint)actualCh;
    info[2] = (jint)actualFmt;
    info[3] = (jint)AAudioStream_getSharingMode(stream);
    info[4] = (jint)AAudioStream_getPerformanceMode(stream);
    info[5] = (jint)AAudioStream_getInputPreset(stream);
    info[6] = (jint)AAudioStream_getDeviceId(stream);
    info[7] = (jint)AAudioStream_getFramesPerBurst(stream);
    info[8] = (jint)ctx->bytesPerFrame;
    info[9] = AAUDIO_OK;

    if (outInfo) {
        (*env)->SetIntArrayRegion(env, outInfo, 0, 10, info);
    }

    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeStart(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)(uintptr_t)handle;
    if (!ctx || !ctx->stream) {
        return -899;
    }
    return (jint)AAudioStream_requestStart(ctx->stream);
}

JNIEXPORT void JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeSetPaused(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jboolean paused
) {
    (void)env;
    (void)clazz;
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)(uintptr_t)handle;
    if (ctx) {
        atomic_store_explicit(&ctx->paused, paused ? 1u : 0u, memory_order_relaxed);
    }
}

JNIEXPORT jint JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeDrain(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jobject directDst,
    jint maxBytes
) {
    (void)clazz;
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)(uintptr_t)handle;
    if (!ctx || !directDst || maxBytes <= 0) {
        return 0;
    }
    uint8_t *dst = (uint8_t *)(*env)->GetDirectBufferAddress(env, directDst);
    jlong cap = (*env)->GetDirectBufferCapacity(env, directDst);
    if (!dst || cap <= 0) {
        return 0;
    }
    uint32_t limit = (uint32_t)maxBytes;
    if ((jlong)limit > cap) {
        limit = (uint32_t)cap;
    }
    return (jint)spsc_read_aligned(&ctx->ring, dst, limit, ctx->bytesPerFrame, memcpy);
}

JNIEXPORT void JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeGetTelemetry(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jintArray outStats
) {
    (void)clazz;
    if (!outStats) {
        return;
    }
    jint stats[4] = {0, 0, 0, 0};
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)(uintptr_t)handle;
    if (ctx && ctx->stream) {
        stats[0] = (jint)AAudioStream_getXRunCount(ctx->stream);
        stats[1] = (jint)atomic_load_explicit(&ctx->ringOverruns, memory_order_relaxed);
        stats[2] = (jint)atomic_load_explicit(&ctx->errorCode, memory_order_acquire);
        stats[3] = (jint)AAudioStream_getState(ctx->stream);
    }
    (*env)->SetIntArrayRegion(env, outStats, 0, 4, stats);
}

JNIEXPORT void JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeStopAndClose(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    RekorderStreamCtx *ctx = (RekorderStreamCtx *)(uintptr_t)handle;
    if (!ctx) {
        return;
    }
    atomic_store_explicit(&ctx->stopping, 1u, memory_order_release);
    if (ctx->stream) {
        AAudioStream_requestStop(ctx->stream);
        AAudioStream_close(ctx->stream);
        ctx->stream = NULL;
    }
    if (ctx->ringBacking) {
        free(ctx->ringBacking);
        ctx->ringBacking = NULL;
    }
    free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_rekorder_app_audio_NativeAAudioEngine_nativeErrorText(
    JNIEnv *env,
    jclass clazz,
    jint errorCode
) {
    (void)clazz;
    const char *msg = AAudio_convertResultToText((aaudio_result_t)errorCode);
    if (!msg) {
        msg = "AAUDIO_ERROR_UNKNOWN";
    }
    return (*env)->NewStringUTF(env, msg);
}
