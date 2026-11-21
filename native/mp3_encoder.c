/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Rekorder Built-in Pure-C MPEG-1 Layer III (MP3) CBR Encoder
 *
 * 100% self-contained, zero third-party dependencies, zero libm dependency.
 * Compiles freestanding into librekorder_aaudio.so across arm64-v8a,
 * armeabi-v7a, x86_64, and x86.
 */

#include <stdint.h>
#include <stddef.h>

#include "mp3_encoder.h"
#include "mp3_tables.h"
#include "mp3_window.h"

extern void *calloc(size_t nmemb, size_t size);
extern void free(void *ptr);
extern void *memcpy(void *dst, const void *src, size_t n);
extern void *memset(void *dst, int c, size_t n);

#define MP3_PI 3.14159265358979323846

/* ---------- Freestanding exact math (zero libm dependency) ---------- */

static double mp3_fabs_d(double x) { return x < 0.0 ? -x : x; }
static float mp3_fabs_f(float x) { return x < 0.0f ? -x : x; }

static double mp3_sqrt_d(double x) {
    if (x <= 0.0) return 0.0;
    double g = x > 1.0 ? x * 0.5 : 1.0;
    for (int i = 0; i < 32; i++) {
        double next = 0.5 * (g + x / g);
        if (mp3_fabs_d(next - g) <= 1e-15 * next) break;
        g = next;
    }
    return g;
}

static float mp3_sqrt_f(float x) {
    if (x <= 0.0f) return 0.0f;
    /* Fast IEEE-754 half-exponent initial guess + 4 Newton-Raphson iterations -> full 24-bit ULP accuracy */
    union { float f; uint32_t u; } v;
    v.f = x;
    v.u = (v.u >> 1) + 0x1fc00000u;
    float g = v.f;
    g = 0.5f * (g + x / g);
    g = 0.5f * (g + x / g);
    g = 0.5f * (g + x / g);
    g = 0.5f * (g + x / g);
    return g;
}

static void mp3_divmod_u32(uint32_t num, uint32_t den, uint32_t *qOut, uint32_t *rOut) {
    uint32_t q = 0u;
    uint32_t r = 0u;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((num >> i) & 1u);
        if (r >= den) {
            r -= den;
            q |= (1u << i);
        }
    }
    if (qOut) *qOut = q;
    if (rOut) *rOut = r;
}

static double mp3_sin_d(double x) {
    /* Reduce to [-pi, pi] using 32-bit int (input angles are bounded < 300 rad) */
    const double twoPi = 2.0 * MP3_PI;
    int32_t k = (int32_t)(x / twoPi + (x >= 0.0 ? 0.5 : -0.5));
    x -= (double)k * twoPi;
    if (x > MP3_PI * 0.5) x = MP3_PI - x;
    else if (x < -MP3_PI * 0.5) x = -MP3_PI - x;
    double x2 = x * x;
    double term = x;
    double sum = x;
    for (int i = 1; i <= 9; i++) {
        term = -term * x2 / (double)((2 * i) * (2 * i + 1));
        sum += term;
    }
    return sum;
}

static double mp3_cos_d(double x) {
    return mp3_sin_d(x + MP3_PI * 0.5);
}

/* ---------- ISO/IEC 11172-3 Constants & Tables ---------- */

/* Long-block scalefactor band boundaries (ISO 11172-3 Table B.8b) */
static const uint16_t SFB_44100[23] = {
    0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 52, 62, 74, 90, 110, 134, 162, 196, 238, 288, 342, 418, 576
};
static const uint16_t SFB_48000[23] = {
    0, 4, 8, 12, 16, 20, 24, 30, 36, 42, 50, 60, 72, 88, 106, 128, 156, 190, 230, 276, 330, 384, 576
};
static const uint16_t SFB_32000[23] = {
    0, 4, 8, 12, 16, 20, 24, 30, 36, 44, 54, 66, 82, 102, 126, 156, 194, 240, 296, 364, 448, 550, 576
};

typedef struct {
    const uint32_t *codes;
    const uint8_t *lens;
    uint8_t dim;
    uint8_t linbits;
    uint16_t maxVal;
} HuffMeta;

static HuffMeta get_huff_meta(int tid) {
    HuffMeta m = { NULL, NULL, 0, 0, 0 };
    switch (tid) {
        case 1:  m.codes = huff_code_1;  m.lens = huff_len_1;  m.dim = 2;  m.linbits = 0;  m.maxVal = 1;    break;
        case 2:  m.codes = huff_code_2;  m.lens = huff_len_2;  m.dim = 3;  m.linbits = 0;  m.maxVal = 2;    break;
        case 5:  m.codes = huff_code_5;  m.lens = huff_len_5;  m.dim = 4;  m.linbits = 0;  m.maxVal = 3;    break;
        case 7:  m.codes = huff_code_7;  m.lens = huff_len_7;  m.dim = 6;  m.linbits = 0;  m.maxVal = 5;    break;
        case 10: m.codes = huff_code_10; m.lens = huff_len_10; m.dim = 8;  m.linbits = 0;  m.maxVal = 7;    break;
        case 13: m.codes = huff_code_13; m.lens = huff_len_13; m.dim = 16; m.linbits = 0;  m.maxVal = 15;   break;
        case 15: m.codes = huff_code_15; m.lens = huff_len_15; m.dim = 16; m.linbits = 0;  m.maxVal = 15;   break;
        case 16: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 1;  m.maxVal = 16;   break;
        case 17: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 2;  m.maxVal = 18;   break;
        case 18: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 3;  m.maxVal = 22;   break;
        case 19: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 4;  m.maxVal = 30;   break;
        case 20: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 6;  m.maxVal = 78;   break;
        case 21: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 8;  m.maxVal = 270;  break;
        case 22: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 10; m.maxVal = 1038; break;
        case 23: m.codes = huff_code_16; m.lens = huff_len_16; m.dim = 16; m.linbits = 13; m.maxVal = 8206; break;
        case 24: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 4;  m.maxVal = 30;   break;
        case 25: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 5;  m.maxVal = 46;   break;
        case 26: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 6;  m.maxVal = 78;   break;
        case 27: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 7;  m.maxVal = 142;  break;
        case 28: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 8;  m.maxVal = 270;  break;
        case 29: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 9;  m.maxVal = 526;  break;
        case 30: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 11; m.maxVal = 2062; break;
        case 31: m.codes = huff_code_24; m.lens = huff_len_24; m.dim = 16; m.linbits = 13; m.maxVal = 8206; break;
        default: break;
    }
    return m;
}

typedef struct {
    uint8_t *buf;
    int32_t capBytes;
    int32_t bitPos;
} BitWriter;

static void bw_init(BitWriter *bw, uint8_t *buf, int32_t capBytes) {
    bw->buf = buf;
    bw->capBytes = capBytes;
    bw->bitPos = 0;
    memset(buf, 0, (size_t)capBytes);
}

static void bw_put(BitWriter *bw, uint32_t val, int32_t nBits) {
    for (int32_t i = nBits - 1; i >= 0; i--) {
        int32_t byteIdx = bw->bitPos >> 3;
        if (byteIdx < bw->capBytes && ((val >> i) & 1u)) {
            bw->buf[byteIdx] |= (uint8_t)(0x80u >> (bw->bitPos & 7));
        }
        bw->bitPos++;
    }
}

typedef struct {
    int32_t part2_3_length;
    int32_t big_values;
    int32_t global_gain;
    int32_t table_select[3];
    int32_t region0_count;
    int32_t region1_count;
    int32_t count1_quads;
    int32_t ix[576];
    uint8_t sgn[576];
} GranuleEncoded;

struct RekorderMp3Encoder {
    int32_t sampleRate;
    int32_t channels;
    int32_t bitrateBps;
    int32_t srIdx;
    int32_t brIdx;
    int32_t baseFrameBytes;
    int32_t padRemPerFrame;
    int32_t padAccum;
    const uint16_t *sfb;

    /* Precomputed transforms */
    float mCos[32][64];
    float mdctCos[18][36];
    float cs[8];
    float ca[8];
    float invStepScale[256]; /* 2^(-(gg - 210) / 4.0) */

    /* Per-channel filterbank & MDCT state */
    float fifo[2][512];
    float mdctPrev[2][32][18];

    /* Pending PCM accumulator (up to 1152 frames per MP3 frame) */
    float pcmBuf[2][1152];
    int32_t pcmFill;
};

static int32_t region_bits(const int32_t *ix, int32_t start, int32_t end, int tid) {
    if (tid == 0) return 0;
    HuffMeta hm = get_huff_meta(tid);
    int32_t bits = 0;
    for (int32_t i = start; i < end; i += 2) {
        int32_t x = ix[i];
        int32_t y = ix[i + 1];
        int32_t bx = x > 15 ? 15 : x;
        int32_t by = y > 15 ? 15 : y;
        bits += hm.lens[bx * hm.dim + by];
        if (bx == 15 && hm.linbits > 0) bits += hm.linbits;
        if (x > 0) bits++;
        if (by == 15 && hm.linbits > 0) bits += hm.linbits;
        if (y > 0) bits++;
    }
    return bits;
}

static int pick_best_table(const int32_t *ix, int32_t start, int32_t end, int32_t *outBits) {
    int32_t maxv = 0;
    for (int32_t i = start; i < end; i++) {
        if (ix[i] > maxv) maxv = ix[i];
    }
    if (maxv == 0) { *outBits = 0; return 0; }
    if (maxv <= 1) { *outBits = region_bits(ix, start, end, 1); return 1; }
    if (maxv <= 2) { *outBits = region_bits(ix, start, end, 2); return 2; }
    if (maxv <= 3) { *outBits = region_bits(ix, start, end, 5); return 5; }
    if (maxv <= 5) { *outBits = region_bits(ix, start, end, 7); return 7; }
    if (maxv <= 7) { *outBits = region_bits(ix, start, end, 10); return 10; }
    if (maxv <= 15) {
        int32_t b13 = region_bits(ix, start, end, 13);
        int32_t b15 = region_bits(ix, start, end, 15);
        if (b15 < b13) { *outBits = b15; return 15; }
        *outBits = b13; return 13;
    }
    /* Escaped range > 15: pick smallest matching linbits in both T16..23 and T24..31 families */
    int c1 = 23;
    for (int t = 16; t <= 23; t++) {
        if (maxv <= get_huff_meta(t).maxVal) { c1 = t; break; }
    }
    int c2 = 31;
    for (int t = 24; t <= 31; t++) {
        if (maxv <= get_huff_meta(t).maxVal) { c2 = t; break; }
    }
    int32_t b1 = region_bits(ix, start, end, c1);
    int32_t b2 = region_bits(ix, start, end, c2);
    if (b2 < b1) { *outBits = b2; return c2; }
    *outBits = b1;
    return c1;
}

static int quantize_and_measure(const RekorderMp3Encoder *enc, const float *xr, float maxXr,
                                int32_t gg, GranuleEncoded *out) {
    float scale = enc->invStepScale[gg];
    /* 8191^(4/3) = 165140.4f */
    if (maxXr * scale > 165000.0f) {
        return -1;
    }
    for (int i = 0; i < 576; i++) {
        float a = mp3_fabs_f(xr[i]) * scale;
        if (a > 0.0f) {
            /* Exact x^(3/4) = sqrt(x * sqrt(x)) */
            float s1 = mp3_sqrt_f(a);
            float qf = mp3_sqrt_f(a * s1) + 0.4054f;
            int32_t q = (int32_t)qf;
            if (q > 8191) return -1;
            out->ix[i] = q;
            out->sgn[i] = (xr[i] < 0.0f) ? 1u : 0u;
        } else {
            out->ix[i] = 0;
            out->sgn[i] = 0u;
        }
    }

    /* Trim trailing zeros (rzero) */
    int32_t nz = 576;
    while (nz > 0 && out->ix[nz - 1] == 0) nz--;
    /* Make nz even for pair boundary */
    if (nz & 1) nz++;
    if (nz > 576) nz = 576;

    /* Pull trailing {-1, 0, +1} quadruples into count1 region (Table B: 4 bits + nonzero signs) */
    int32_t bvEnd = nz;
    int32_t c1Quads = 0;
    while (bvEnd >= 4) {
        int32_t i = bvEnd - 4;
        if (out->ix[i] <= 1 && out->ix[i + 1] <= 1 && out->ix[i + 2] <= 1 && out->ix[i + 3] <= 1) {
            bvEnd -= 4;
            c1Quads++;
        } else {
            break;
        }
    }

    int32_t r0Idx = 7; /* SFB[8] = 36 lines */
    int32_t r1Idx = 5; /* SFB[14] = 106/110/126 lines */
    int32_t r0End = enc->sfb[r0Idx + 1];
    int32_t r1End = enc->sfb[r0Idx + r1Idx + 2];

    int32_t end0 = bvEnd < r0End ? bvEnd : r0End;
    int32_t end1 = bvEnd < r1End ? bvEnd : r1End;

    int32_t b0 = 0, b1 = 0, b2 = 0;
    int t0 = (end0 > 0) ? pick_best_table(out->ix, 0, end0, &b0) : 0;
    int t1 = (end1 > r0End) ? pick_best_table(out->ix, r0End, end1, &b1) : 0;
    int t2 = (bvEnd > r1End) ? pick_best_table(out->ix, r1End, bvEnd, &b2) : 0;

    int32_t c1Bits = 0;
    for (int32_t q = 0; q < c1Quads; q++) {
        int32_t i = bvEnd + q * 4;
        c1Bits += 4; /* Count1 Table B is always 4 bits */
        if (out->ix[i])     c1Bits++;
        if (out->ix[i + 1]) c1Bits++;
        if (out->ix[i + 2]) c1Bits++;
        if (out->ix[i + 3]) c1Bits++;
    }

    out->global_gain = gg;
    out->big_values = bvEnd >> 1;
    out->count1_quads = c1Quads;
    out->region0_count = r0Idx;
    out->region1_count = r1Idx;
    out->table_select[0] = t0;
    out->table_select[1] = t1;
    out->table_select[2] = t2;
    out->part2_3_length = b0 + b1 + b2 + c1Bits;
    return out->part2_3_length;
}

static void write_granule_huffman(const RekorderMp3Encoder *enc, BitWriter *bw, const GranuleEncoded *g) {
    int32_t r0End = enc->sfb[g->region0_count + 1];
    int32_t r1End = enc->sfb[g->region0_count + g->region1_count + 2];
    int32_t bvEnd = g->big_values * 2;

    for (int32_t i = 0; i < bvEnd; i += 2) {
        int tid = (i < r0End) ? g->table_select[0]
                : ((i < r1End) ? g->table_select[1] : g->table_select[2]);
        if (tid == 0) continue;
        HuffMeta hm = get_huff_meta(tid);
        int32_t x = g->ix[i];
        int32_t y = g->ix[i + 1];
        int32_t bx = x > 15 ? 15 : x;
        int32_t by = y > 15 ? 15 : y;
        uint32_t idx = (uint32_t)(bx * hm.dim + by);
        bw_put(bw, hm.codes[idx], (int32_t)hm.lens[idx]);
        if (bx == 15 && hm.linbits > 0) bw_put(bw, (uint32_t)(x - 15), (int32_t)hm.linbits);
        if (x > 0) bw_put(bw, g->sgn[i], 1);
        if (by == 15 && hm.linbits > 0) bw_put(bw, (uint32_t)(y - 15), (int32_t)hm.linbits);
        if (y > 0) bw_put(bw, g->sgn[i + 1], 1);
    }

    /* Count1 Table B (count1table_select = 1): 4-bit bitwise-inverted (v,w,x,y) + sign bits */
    for (int32_t q = 0; q < g->count1_quads; q++) {
        int32_t i = bvEnd + q * 4;
        uint32_t v = (uint32_t)(g->ix[i]     != 0);
        uint32_t w = (uint32_t)(g->ix[i + 1] != 0);
        uint32_t x = (uint32_t)(g->ix[i + 2] != 0);
        uint32_t y = (uint32_t)(g->ix[i + 3] != 0);
        uint32_t code = ((v << 3) | (w << 2) | (x << 1) | y) ^ 0xFu;
        bw_put(bw, code, 4);
        if (v) bw_put(bw, g->sgn[i],     1);
        if (w) bw_put(bw, g->sgn[i + 1], 1);
        if (x) bw_put(bw, g->sgn[i + 2], 1);
        if (y) bw_put(bw, g->sgn[i + 3], 1);
    }
}

static void analyze_granule(RekorderMp3Encoder *enc, int ch, const float *pcm576, float *xrOut) {
    float sbBuf[32][18];
    float *fifo = enc->fifo[ch];

    for (int slot = 0; slot < 18; slot++) {
        /* Shift 512-sample FIFO by 32 and load 32 newest samples reversed at [31..0] */
        for (int i = 511; i >= 32; i--) {
            fifo[i] = fifo[i - 32];
        }
        const float *in32 = pcm576 + slot * 32;
        for (int j = 0; j < 32; j++) {
            fifo[31 - j] = in32[j];
        }
        float y[64];
        for (int j = 0; j < 64; j++) {
            float sum = 0.0f;
            for (int k = 0; k < 8; k++) {
                int idx = j + (k << 6);
                sum += mp3_enwindow[idx] * fifo[idx];
            }
            y[j] = sum;
        }
        for (int sb = 0; sb < 32; sb++) {
            float sum = 0.0f;
            const float *mRow = enc->mCos[sb];
            for (int j = 0; j < 64; j++) {
                sum += mRow[j] * y[j];
            }
            if ((sb & 1) && (slot & 1)) {
                sum = -sum;
            }
            sbBuf[sb][slot] = sum;
        }
    }

    /* 36 -> 18 MDCT per subband */
    for (int sb = 0; sb < 32; sb++) {
        float inp[36];
        memcpy(inp, enc->mdctPrev[ch][sb], 18 * sizeof(float));
        memcpy(inp + 18, sbBuf[sb], 18 * sizeof(float));
        memcpy(enc->mdctPrev[ch][sb], sbBuf[sb], 18 * sizeof(float));

        for (int k = 0; k < 18; k++) {
            float sum = 0.0f;
            const float *mc = enc->mdctCos[k];
            for (int i = 0; i < 36; i++) {
                sum += mc[i] * inp[i];
            }
            xrOut[sb * 18 + k] = sum;
        }
    }

    /* Forward alias reduction (exact inverse of ISO decoder's backward alias reduction) */
    for (int sb = 1; sb < 32; sb++) {
        for (int b = 0; b < 8; b++) {
            int i0 = sb * 18 - 1 - b;
            int i1 = sb * 18 + b;
            float bu = xrOut[i0];
            float bd = xrOut[i1];
            xrOut[i0] = bu * enc->cs[b] + bd * enc->ca[b];
            xrOut[i1] = bd * enc->cs[b] - bu * enc->ca[b];
        }
    }
}

static int32_t encode_one_frame(RekorderMp3Encoder *enc, uint8_t *outBuf, int32_t outCap) {
    int32_t pad = 0;
    enc->padAccum += enc->padRemPerFrame;
    if (enc->padAccum >= enc->sampleRate) {
        enc->padAccum -= enc->sampleRate;
        pad = 1;
    }
    int32_t frameBytes = enc->baseFrameBytes + pad;
    if (frameBytes > outCap) {
        return -1;
    }

    int32_t sideBytes = (enc->channels == 1) ? 17 : 32;
    int32_t mainDataBits = (frameBytes - 4 - sideBytes) * 8;
    int32_t totalSlots = 2 * enc->channels;
    int32_t perGranuleBudget = (enc->channels == 1) ? (mainDataBits >> 1) : (mainDataBits >> 2);

    GranuleEncoded ge[2][2];
    GranuleEncoded trial;
    int32_t bitsRemaining = mainDataBits;
    int32_t slotsLeft = totalSlots;

    for (int gr = 0; gr < 2; gr++) {
        for (int ch = 0; ch < enc->channels; ch++) {
            float xr[576];
            analyze_granule(enc, ch, enc->pcmBuf[ch] + gr * 576, xr);

            float maxXr = 0.0f;
            for (int i = 0; i < 576; i++) {
                float a = mp3_fabs_f(xr[i]);
                if (a > maxXr) maxXr = a;
            }

            /* Dynamic bit-sharing across granules inside the same frame (never overflows frame) */
            uint32_t qBudget = 0u;
            mp3_divmod_u32(bitsRemaining > 0 ? (uint32_t)bitsRemaining : 0u, (uint32_t)slotsLeft, &qBudget, NULL);
            int32_t budget = (int32_t)qBudget;
            if (budget > perGranuleBudget + 512) budget = perGranuleBudget + 512;
            if (budget > 4095) budget = 4095; /* 12-bit part2_3_length max */

            int32_t lo = 0, hi = 255;
            int found = 0;
            while (lo <= hi) {
                int32_t mid = (lo + hi) >> 1;
                int32_t bits = quantize_and_measure(enc, xr, maxXr, mid, &trial);
                if (bits >= 0 && bits <= budget) {
                    memcpy(&ge[gr][ch], &trial, sizeof(GranuleEncoded));
                    found = 1;
                    hi = mid - 1;
                } else {
                    lo = mid + 1;
                }
            }
            if (!found) {
                memset(&ge[gr][ch], 0, sizeof(GranuleEncoded));
                ge[gr][ch].global_gain = 255;
            }
            bitsRemaining -= ge[gr][ch].part2_3_length;
            slotsLeft--;
        }
    }

    /* Pack MPEG-1 Layer III frame header (4 bytes) + side info + main_data */
    BitWriter bw;
    bw_init(&bw, outBuf, frameBytes);

    /* 32-bit MPEG-1 Layer III header */
    bw_put(&bw, 0x7FFu, 11);              /* Sync word */
    bw_put(&bw, 3u, 2);                   /* MPEG Version 1 */
    bw_put(&bw, 1u, 2);                   /* Layer III */
    bw_put(&bw, 1u, 1);                   /* No CRC */
    bw_put(&bw, (uint32_t)enc->brIdx, 4); /* Bitrate index */
    bw_put(&bw, (uint32_t)enc->srIdx, 2); /* Sample rate index */
    bw_put(&bw, (uint32_t)pad, 1);        /* Padding */
    bw_put(&bw, 0u, 1);                   /* Private */
    bw_put(&bw, (enc->channels == 1) ? 3u : 0u, 2); /* Mono (3) or Stereo (0) */
    bw_put(&bw, 0u, 2);                   /* Mode ext */
    bw_put(&bw, 0u, 1);                   /* Copyright */
    bw_put(&bw, 1u, 1);                   /* Original */
    bw_put(&bw, 0u, 2);                   /* Emphasis */

    /* Side information */
    bw_put(&bw, 0u, 9); /* main_data_begin = 0 (self-contained frame) */
    bw_put(&bw, 0u, (enc->channels == 1) ? 5 : 3); /* private_bits */
    for (int ch = 0; ch < enc->channels; ch++) {
        bw_put(&bw, 0u, 4); /* scfsi */
    }
    for (int gr = 0; gr < 2; gr++) {
        for (int ch = 0; ch < enc->channels; ch++) {
            const GranuleEncoded *g = &ge[gr][ch];
            bw_put(&bw, (uint32_t)g->part2_3_length, 12);
            bw_put(&bw, (uint32_t)g->big_values, 9);
            bw_put(&bw, (uint32_t)g->global_gain, 8);
            bw_put(&bw, 0u, 4); /* scalefac_compress = 0 (no scale factors) */
            bw_put(&bw, 0u, 1); /* window_switching_flag = 0 (long window) */
            bw_put(&bw, (uint32_t)g->table_select[0], 5);
            bw_put(&bw, (uint32_t)g->table_select[1], 5);
            bw_put(&bw, (uint32_t)g->table_select[2], 5);
            bw_put(&bw, (uint32_t)g->region0_count, 4);
            bw_put(&bw, (uint32_t)g->region1_count, 3);
            bw_put(&bw, 0u, 1); /* preflag */
            bw_put(&bw, 0u, 1); /* scalefac_scale */
            bw_put(&bw, 1u, 1); /* count1table_select = 1 (Table B) */
        }
    }

    /* Main data Huffman payloads */
    for (int gr = 0; gr < 2; gr++) {
        for (int ch = 0; ch < enc->channels; ch++) {
            write_granule_huffman(enc, &bw, &ge[gr][ch]);
        }
    }

    return frameBytes;
}

RekorderMp3Encoder *mp3_enc_create(int32_t sampleRate, int32_t channels, int32_t bitrateKbps) {
    int32_t srIdx;
    const uint16_t *sfb;
    if (sampleRate == 44100)      { srIdx = 0; sfb = SFB_44100; }
    else if (sampleRate == 48000) { srIdx = 1; sfb = SFB_48000; }
    else if (sampleRate == 32000) { srIdx = 2; sfb = SFB_32000; }
    else return NULL;

    if (channels != 1 && channels != 2) return NULL;

    int32_t brIdx;
    if (bitrateKbps <= 128)      { bitrateKbps = 128; brIdx = 9; }
    else if (bitrateKbps <= 192) { bitrateKbps = 192; brIdx = 11; }
    else if (bitrateKbps <= 256) { bitrateKbps = 256; brIdx = 13; }
    else                         { bitrateKbps = 320; brIdx = 14; }

    RekorderMp3Encoder *enc = (RekorderMp3Encoder *)calloc(1u, sizeof(RekorderMp3Encoder));
    if (!enc) return NULL;

    enc->sampleRate = sampleRate;
    enc->channels = channels;
    enc->bitrateBps = bitrateKbps * 1000;
    enc->srIdx = srIdx;
    enc->brIdx = brIdx;
    enc->sfb = sfb;
    uint32_t baseF = 0u, remF = 0u;
    mp3_divmod_u32((uint32_t)(144 * enc->bitrateBps), (uint32_t)sampleRate, &baseF, &remF);
    enc->baseFrameBytes = (int32_t)baseF;
    enc->padRemPerFrame = (int32_t)remF;
    enc->padAccum = 0;

    /* Precompute polyphase cosine matrix M[32][64] */
    for (int i = 0; i < 32; i++) {
        for (int k = 0; k < 64; k++) {
            enc->mCos[i][k] = (float)mp3_cos_d((double)(2 * i + 1) * (double)(k - 16) * MP3_PI / 64.0);
        }
    }

    /* Precompute windowed MDCT matrix [18][36] */
    for (int k = 0; k < 18; k++) {
        for (int i = 0; i < 36; i++) {
            double win = mp3_sin_d(MP3_PI / 36.0 * ((double)i + 0.5));
            double c = mp3_cos_d(MP3_PI / 72.0 * (double)(2 * i + 19) * (double)(2 * k + 1));
            enc->mdctCos[k][i] = (float)(win * c / 9.0);
        }
    }

    /* Precompute ISO 11172-3 alias reduction butterflies */
    static const double ci[8] = { -0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037 };
    for (int b = 0; b < 8; b++) {
        double d = mp3_sqrt_d(1.0 + ci[b] * ci[b]);
        enc->cs[b] = (float)(1.0 / d);
        enc->ca[b] = (float)(ci[b] / d);
    }

    /* Precompute exact 2^(-(gg - 210) / 4.0) without pow() */
    static const double qRoots[4] = {
        1.0,
        1.1892071150027210667, /* 2^(1/4) */
        1.4142135623730950488, /* 2^(2/4) */
        1.6817928305074290861  /* 2^(3/4) */
    };
    for (int gg = 0; gg < 256; gg++) {
        int exp4 = 210 - gg;
        int q = (exp4 >= 0) ? (exp4 / 4) : -((-exp4 + 3) / 4);
        int r = exp4 - q * 4;
        double v = qRoots[r];
        if (q > 0) {
            for (int k = 0; k < q; k++) v *= 2.0;
        } else if (q < 0) {
            for (int k = 0; k < -q; k++) v *= 0.5;
        }
        enc->invStepScale[gg] = (float)v;
    }

    return enc;
}

static float mp3_sanitize_f(float x) {
    if (!(x >= -16.0f && x <= 16.0f)) return 0.0f;
    if (x > 1.0f) return 1.0f;
    if (x < -1.0f) return -1.0f;
    return x;
}

int32_t mp3_enc_encode(RekorderMp3Encoder *enc, const float *interleavedPcm,
                       int32_t numFrames, uint8_t *outBuf, int32_t outCap) {
    if (!enc || !interleavedPcm || !outBuf || numFrames <= 0) return 0;
    int32_t ch = enc->channels;
    int32_t written = 0;
    int32_t idx = 0;

    while (idx < numFrames) {
        int32_t need = 1152 - enc->pcmFill;
        int32_t take = numFrames - idx;
        if (take > need) take = need;

        if (ch == 1) {
            const float *src = interleavedPcm + idx;
            float *dst = enc->pcmBuf[0] + enc->pcmFill;
            for (int32_t i = 0; i < take; i++) {
                dst[i] = mp3_sanitize_f(src[i]);
            }
        } else {
            const float *src = interleavedPcm + idx * 2;
            float *dstL = enc->pcmBuf[0] + enc->pcmFill;
            float *dstR = enc->pcmBuf[1] + enc->pcmFill;
            for (int32_t i = 0; i < take; i++) {
                dstL[i] = mp3_sanitize_f(src[i * 2]);
                dstR[i] = mp3_sanitize_f(src[i * 2 + 1]);
            }
        }
        enc->pcmFill += take;
        idx += take;

        if (enc->pcmFill == 1152) {
            int32_t n = encode_one_frame(enc, outBuf + written, outCap - written);
            if (n < 0) return -1;
            written += n;
            enc->pcmFill = 0;
        }
    }
    return written;
}

int32_t mp3_enc_flush(RekorderMp3Encoder *enc, uint8_t *outBuf, int32_t outCap) {
    if (!enc || !outBuf) return 0;
    int32_t written = 0;

    if (enc->pcmFill > 0) {
        int32_t rem = 1152 - enc->pcmFill;
        memset(enc->pcmBuf[0] + enc->pcmFill, 0, (size_t)rem * sizeof(float));
        if (enc->channels == 2) {
            memset(enc->pcmBuf[1] + enc->pcmFill, 0, (size_t)rem * sizeof(float));
        }
        int32_t n = encode_one_frame(enc, outBuf + written, outCap - written);
        if (n < 0) return -1;
        written += n;
        enc->pcmFill = 0;
    }

    /* Emit 1 trailing silence frame so the 1057-sample analysis/synthesis delay flushes cleanly */
    memset(enc->pcmBuf[0], 0, 1152 * sizeof(float));
    if (enc->channels == 2) {
        memset(enc->pcmBuf[1], 0, 1152 * sizeof(float));
    }
    int32_t tail = encode_one_frame(enc, outBuf + written, outCap - written);
    if (tail > 0) {
        written += tail;
    }
    return written;
}

void mp3_enc_destroy(RekorderMp3Encoder *enc) {
    if (enc) {
        free(enc);
    }
}
