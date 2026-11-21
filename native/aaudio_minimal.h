/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef REKORDER_AAUDIO_MINIMAL_H
#define REKORDER_AAUDIO_MINIMAL_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

typedef int32_t aaudio_result_t;
typedef int32_t aaudio_direction_t;
typedef int32_t aaudio_format_t;
typedef int32_t aaudio_sharing_mode_t;
typedef int32_t aaudio_performance_mode_t;
typedef int32_t aaudio_input_preset_t;
typedef int32_t aaudio_stream_state_t;
typedef int32_t aaudio_data_callback_result_t;

enum {
    AAUDIO_OK = 0,
    AAUDIO_UNSPECIFIED = 0,
};

enum {
    AAUDIO_DIRECTION_OUTPUT = 0,
    AAUDIO_DIRECTION_INPUT = 1,
};

enum {
    AAUDIO_FORMAT_INVALID = -1,
    AAUDIO_FORMAT_UNSPECIFIED = 0,
    AAUDIO_FORMAT_PCM_I16 = 1,
    AAUDIO_FORMAT_PCM_FLOAT = 2,
    AAUDIO_FORMAT_PCM_I24_PACKED = 3, /* API 31+ */
    AAUDIO_FORMAT_PCM_I32 = 4,        /* API 31+ */
};

enum {
    AAUDIO_SHARING_MODE_EXCLUSIVE = 0,
    AAUDIO_SHARING_MODE_SHARED = 1,
};

enum {
    AAUDIO_PERFORMANCE_MODE_NONE = 10,
    AAUDIO_PERFORMANCE_MODE_POWER_SAVING = 11,
    AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12,
};

enum {
    AAUDIO_INPUT_PRESET_GENERIC = 1,
    AAUDIO_INPUT_PRESET_CAMCORDER = 5,
    AAUDIO_INPUT_PRESET_VOICE_RECOGNITION = 6,
    AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION = 7,
    AAUDIO_INPUT_PRESET_UNPROCESSED = 9,       /* API 28+ */
    AAUDIO_INPUT_PRESET_VOICE_PERFORMANCE = 10 /* API 29+ */
};

enum {
    AAUDIO_CALLBACK_RESULT_CONTINUE = 0,
    AAUDIO_CALLBACK_RESULT_STOP = 1,
};

typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(
    AAudioStream *stream,
    void *userData,
    void *audioData,
    int32_t numFrames
);

typedef void (*AAudioStream_errorCallback)(
    AAudioStream *stream,
    void *userData,
    aaudio_result_t error
);

/* API 26+ */
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **builder);
const char *AAudio_convertResultToText(aaudio_result_t returnCode);

void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder *builder, int32_t deviceId);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *builder, int32_t sampleRate);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *builder, int32_t channelCount);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *builder, aaudio_format_t format);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *builder, aaudio_sharing_mode_t sharingMode);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *builder, aaudio_direction_t direction);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *builder, aaudio_performance_mode_t mode);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *builder, AAudioStream_dataCallback callback, void *userData);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *builder, AAudioStream_errorCallback callback, void *userData);
/* API 28+ */
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder *builder, aaudio_input_preset_t inputPreset);

aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *builder, AAudioStream **stream);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *builder);

aaudio_result_t AAudioStream_close(AAudioStream *stream);
aaudio_result_t AAudioStream_requestStart(AAudioStream *stream);
aaudio_result_t AAudioStream_requestStop(AAudioStream *stream);

aaudio_stream_state_t AAudioStream_getState(AAudioStream *stream);
int32_t AAudioStream_getSampleRate(AAudioStream *stream);
int32_t AAudioStream_getChannelCount(AAudioStream *stream);
aaudio_format_t AAudioStream_getFormat(AAudioStream *stream);
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *stream);
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *stream);
int32_t AAudioStream_getDeviceId(AAudioStream *stream);
int32_t AAudioStream_getFramesPerBurst(AAudioStream *stream);
int32_t AAudioStream_getXRunCount(AAudioStream *stream);
/* API 28+ */
aaudio_input_preset_t AAudioStream_getInputPreset(AAudioStream *stream);

#ifdef __cplusplus
}
#endif

#endif /* REKORDER_AAUDIO_MINIMAL_H */
