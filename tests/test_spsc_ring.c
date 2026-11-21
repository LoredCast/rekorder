/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Host multithreaded stress & correctness test for native/spsc_ring.h */
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../native/spsc_ring.h"

#define TEST_RING_CAP (1u << 16) /* 64 KiB to force thousands of wrap-arounds */
#define TOTAL_FRAMES  (1024u * 1024u) /* 1M stereo 24-bit frames = 6 MiB */
#define FRAME_BYTES   6u /* 2ch * 3 bytes (24-bit packed) */

static SpscRing g_ring;

static void *producer_thread(void *arg) {
    (void)arg;
    uint8_t chunk[257 * FRAME_BYTES]; /* intentionally odd frame count */
    uint32_t sentFrames = 0;
    uint32_t seq = 0;

    while (sentFrames < TOTAL_FRAMES) {
        uint32_t nFrames = 257u;
        if (nFrames > TOTAL_FRAMES - sentFrames) {
            nFrames = TOTAL_FRAMES - sentFrames;
        }
        uint32_t bytes = nFrames * FRAME_BYTES;
        for (uint32_t i = 0; i < bytes; i++) {
            chunk[i] = (uint8_t)((seq++ * 31u + 7u) & 0xFFu);
        }
        while (!spsc_write_all(&g_ring, chunk, bytes, memcpy)) {
            /* spin briefly while consumer drains */
        }
        sentFrames += nFrames;
    }
    return NULL;
}

int main(void) {
    /* 1. Division-free alignment checks */
    assert(spsc_align_down(0, 6) == 0);
    assert(spsc_align_down(5, 6) == 0);
    assert(spsc_align_down(6, 6) == 6);
    assert(spsc_align_down(17, 6) == 12);
    assert(spsc_align_down(1000, 3) == 999);
    assert(spsc_align_down(1000, 6) == 996);
    assert(spsc_align_down(65536, 6) == 65532);
    assert(spsc_align_down(65535, 8) == 65528);
    assert(spsc_align_down(12345, 1) == 12345);

    /* 2. Full-buffer atomic rejection (no partial writes) */
    uint8_t smallBacking[64];
    SpscRing small;
    spsc_init(&small, smallBacking, 64u);
    uint8_t data[48];
    memset(data, 0xAB, sizeof(data));
    assert(spsc_write_all(&small, data, 48u, memcpy) == 1);
    assert(spsc_write_all(&small, data, 24u, memcpy) == 0); /* would exceed 64 */
    assert(spsc_available_read(&small) == 48u);

    uint8_t out[64];
    uint32_t got = spsc_read_aligned(&small, out, 20u, 6u, memcpy);
    assert(got == 18u); /* aligned down from 20 to 18 */
    assert(spsc_available_read(&small) == 30u);

    /* 3. Multithreaded concurrent SPSC stress test across wrap-arounds */
    uint8_t *backing = (uint8_t *)malloc(TEST_RING_CAP);
    assert(backing != NULL);
    spsc_init(&g_ring, backing, TEST_RING_CAP);

    pthread_t prod;
    assert(pthread_create(&prod, NULL, producer_thread, NULL) == 0);

    uint8_t rx[1999]; /* intentionally non-multiple of 6 to exercise alignment */
    uint64_t totalExpected = (uint64_t)TOTAL_FRAMES * FRAME_BYTES;
    uint64_t received = 0;
    uint32_t seq = 0;

    while (received < totalExpected) {
        uint32_t n = spsc_read_aligned(&g_ring, rx, sizeof(rx), FRAME_BYTES, memcpy);
        assert((n % FRAME_BYTES) == 0u);
        for (uint32_t i = 0; i < n; i++) {
            uint8_t expected = (uint8_t)((seq++ * 31u + 7u) & 0xFFu);
            if (rx[i] != expected) {
                fprintf(stderr, "MISMATCH at byte %llu: got 0x%02x expected 0x%02x\n",
                        (unsigned long long)(received + i), rx[i], expected);
                return 1;
            }
        }
        received += n;
    }

    pthread_join(prod, NULL);
    assert(spsc_available_read(&g_ring) == 0u);
    free(backing);

    printf("PASS: test_spsc_ring (%llu bytes verified bit-for-bit across 2 threads)\n",
           (unsigned long long)received);
    return 0;
}
