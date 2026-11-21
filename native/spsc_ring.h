/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef REKORDER_SPSC_RING_H
#define REKORDER_SPSC_RING_H

#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>

/*
 * Lock-free Single-Producer Single-Consumer (SPSC) byte ring buffer.
 * Capacity MUST be a power of two and < 2^31.
 * Uses 32-bit atomic indices and division-free alignment so every Android ABI
 * (arm64-v8a, armeabi-v7a, x86_64, x86) compiles cleanly with zero libgcc/libatomic calls.
 */
typedef struct {
    uint8_t *buf;
    uint32_t capacity;
    uint32_t mask;
    _Atomic uint32_t writePos;
    _Atomic uint32_t readPos;
} SpscRing;

static inline uint32_t spsc_align_down(uint32_t value, uint32_t align) {
    if (align <= 1u) {
        return value;
    }
    if ((align & (align - 1u)) == 0u) {
        return value & ~(align - 1u);
    }
    /* Division-free floor(value / align) * align via binary long subtraction. */
    uint32_t rem = value;
    uint32_t shift = 0u;
    while (shift < 31u && (align << shift) <= (rem >> 1u)) {
        shift++;
    }
    uint32_t aligned = 0u;
    for (;;) {
        uint32_t chunk = align << shift;
        if (rem >= chunk) {
            rem -= chunk;
            aligned += chunk;
        }
        if (shift == 0u) {
            break;
        }
        shift--;
    }
    return aligned;
}

static inline void spsc_init(SpscRing *r, uint8_t *backing, uint32_t capacityPow2) {
    r->buf = backing;
    r->capacity = capacityPow2;
    r->mask = capacityPow2 - 1u;
    atomic_init(&r->writePos, 0u);
    atomic_init(&r->readPos, 0u);
}

static inline uint32_t spsc_available_read(const SpscRing *r) {
    uint32_t w = atomic_load_explicit(&r->writePos, memory_order_acquire);
    uint32_t rd = atomic_load_explicit(&r->readPos, memory_order_relaxed);
    return w - rd;
}

static inline uint32_t spsc_available_write(const SpscRing *r) {
    uint32_t w = atomic_load_explicit(&r->writePos, memory_order_relaxed);
    uint32_t rd = atomic_load_explicit(&r->readPos, memory_order_acquire);
    return r->capacity - (w - rd);
}

/* Producer-only: writes all `len` bytes if space permits, else returns 0 (never corrupts framing). */
static inline int spsc_write_all(SpscRing *r, const uint8_t *src, uint32_t len,
                                 void *(*copyFn)(void *, const void *, size_t)) {
    uint32_t w = atomic_load_explicit(&r->writePos, memory_order_relaxed);
    uint32_t rd = atomic_load_explicit(&r->readPos, memory_order_acquire);
    uint32_t freeBytes = r->capacity - (w - rd);
    if (len > freeBytes) {
        return 0;
    }
    uint32_t idx = w & r->mask;
    uint32_t first = r->capacity - idx;
    if (first > len) {
        first = len;
    }
    copyFn(r->buf + idx, src, first);
    uint32_t second = len - first;
    if (second > 0u) {
        copyFn(r->buf, src + first, second);
    }
    atomic_store_explicit(&r->writePos, w + len, memory_order_release);
    return 1;
}

/* Consumer-only: reads up to `maxLen` bytes aligned down to `alignBytes` (bytesPerFrame). */
static inline uint32_t spsc_read_aligned(SpscRing *r, uint8_t *dst, uint32_t maxLen,
                                         uint32_t alignBytes,
                                         void *(*copyFn)(void *, const void *, size_t)) {
    uint32_t w = atomic_load_explicit(&r->writePos, memory_order_acquire);
    uint32_t rd = atomic_load_explicit(&r->readPos, memory_order_relaxed);
    uint32_t avail = w - rd;
    if (avail > maxLen) {
        avail = maxLen;
    }
    avail = spsc_align_down(avail, alignBytes);
    if (avail == 0u) {
        return 0u;
    }
    uint32_t idx = rd & r->mask;
    uint32_t first = r->capacity - idx;
    if (first > avail) {
        first = avail;
    }
    copyFn(dst, r->buf + idx, first);
    uint32_t second = avail - first;
    if (second > 0u) {
        copyFn(dst + first, r->buf, second);
    }
    atomic_store_explicit(&r->readPos, rd + avail, memory_order_release);
    return avail;
}

#endif /* REKORDER_SPSC_RING_H */
