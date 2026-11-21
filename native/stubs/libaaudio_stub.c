/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Link-time stub for Android system libaaudio.so (never shipped in APK). */
#include "../aaudio_minimal.h"

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { (void)b; return 0; }
const char *AAudio_convertResultToText(aaudio_result_t r) { (void)r; return ""; }

void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder *b, int32_t d) { (void)b; (void)d; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t r) { (void)b; (void)r; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t c) { (void)b; (void)c; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t f) { (void)b; (void)f; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, aaudio_sharing_mode_t m) { (void)b; (void)m; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, aaudio_direction_t d) { (void)b; (void)d; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, aaudio_performance_mode_t m) { (void)b; (void)m; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *u) { (void)b; (void)cb; (void)u; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *u) { (void)b; (void)cb; (void)u; }
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder *b, aaudio_input_preset_t p) { (void)b; (void)p; }

aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s) { (void)b; (void)s; return 0; }
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { (void)b; return 0; }

aaudio_result_t AAudioStream_close(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { (void)s; return 0; }

aaudio_stream_state_t AAudioStream_getState(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getSampleRate(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getChannelCount(AAudioStream *s) { (void)s; return 0; }
aaudio_format_t AAudioStream_getFormat(AAudioStream *s) { (void)s; return 0; }
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *s) { (void)s; return 0; }
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getDeviceId(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getFramesPerBurst(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getXRunCount(AAudioStream *s) { (void)s; return 0; }
aaudio_input_preset_t AAudioStream_getInputPreset(AAudioStream *s) { (void)s; return 0; }
