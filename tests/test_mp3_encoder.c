/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Host test for native/mp3_encoder.c: encodes mono/stereo 44.1k/48k at 128..320kbps MP3 files */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include "../native/mp3_encoder.h"

static void run_case(const char *path, int sr, int ch, int kbps) {
    RekorderMp3Encoder *enc = mp3_enc_create(sr, ch, kbps);
    if (!enc) {
        fprintf(stderr, "FAIL: mp3_enc_create(%d, %d, %d)\n", sr, ch, kbps);
        exit(1);
    }
    int totalFrames = sr * 2; /* 2 seconds */
    float *pcm = (float *)malloc((size_t)totalFrames * (size_t)ch * sizeof(float));
    for (int i = 0; i < totalFrames; i++) {
        double t = (double)i / (double)sr;
        float l = (float)(0.45 * sin(2.0 * 3.14159265358979323846 * 440.0 * t)
                        + 0.25 * sin(2.0 * 3.14159265358979323846 * 3000.0 * t));
        if (ch == 1) {
            pcm[i] = l;
        } else {
            float r = (float)(0.45 * sin(2.0 * 3.14159265358979323846 * 1000.0 * t)
                            + 0.25 * sin(2.0 * 3.14159265358979323846 * 5000.0 * t));
            pcm[i * 2] = l;
            pcm[i * 2 + 1] = r;
        }
    }

    uint8_t outBuf[65536];
    FILE *fp = fopen(path, "wb");
    int pos = 0;
    while (pos < totalFrames) {
        int chunk = 2048;
        if (chunk > totalFrames - pos) chunk = totalFrames - pos;
        int n = mp3_enc_encode(enc, pcm + pos * ch, chunk, outBuf, sizeof(outBuf));
        if (n < 0) { fprintf(stderr, "encode err\n"); exit(1); }
        if (n > 0) fwrite(outBuf, 1, (size_t)n, fp);
        pos += chunk;
    }
    int fl = mp3_enc_flush(enc, outBuf, sizeof(outBuf));
    if (fl > 0) fwrite(outBuf, 1, (size_t)fl, fp);
    fclose(fp);
    free(pcm);
    mp3_enc_destroy(enc);
}

int main(void) {
    run_case("/tmp/test_mono_48k_320.mp3", 48000, 1, 320);
    run_case("/tmp/test_stereo_48k_192.mp3", 48000, 2, 192);
    run_case("/tmp/test_stereo_44k_128.mp3", 44100, 2, 128);
    run_case("/tmp/test_mono_44k_256.mp3", 44100, 1, 256);
    printf("Encoded 4 MP3 test files cleanly.\n");
    return 0;
}
