/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef REKORDER_MP3_ENCODER_H
#define REKORDER_MP3_ENCODER_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RekorderMp3Encoder RekorderMp3Encoder;

/* Creates a pure-C MPEG-1 Layer III CBR encoder.
 * sampleRate: 32000, 44100, or 48000.
 * channels: 1 (mono) or 2 (stereo).
 * bitrateKbps: 128, 192, 256, or 320.
 */
RekorderMp3Encoder *mp3_enc_create(int32_t sampleRate, int32_t channels, int32_t bitrateKbps);

/* Feeds interleaved float32 PCM frames (numFrames * channels floats).
 * Encodes complete 1152-frame MPEG-1 Layer III frames into outBuf (capacity outCap).
 * Returns total MP3 bytes written to outBuf, or negative on error.
 */
int32_t mp3_enc_encode(RekorderMp3Encoder *enc, const float *interleavedPcm,
                       int32_t numFrames, uint8_t *outBuf, int32_t outCap);

/* Zero-pads any pending partial frame and flushes the polyphase/MDCT delay tail.
 * Returns total MP3 bytes written to outBuf.
 */
int32_t mp3_enc_flush(RekorderMp3Encoder *enc, uint8_t *outBuf, int32_t outCap);

void mp3_enc_destroy(RekorderMp3Encoder *enc);

#ifdef __cplusplus
}
#endif

#endif /* REKORDER_MP3_ENCODER_H */
