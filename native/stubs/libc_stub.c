/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Link-time stub for Android Bionic libc.so (never shipped in APK). */
#include <stddef.h>

void *malloc(size_t n) { (void)n; return 0; }
void *calloc(size_t nmemb, size_t n) { (void)nmemb; (void)n; return 0; }
void free(void *p) { (void)p; }
void *memcpy(void *dst, const void *src, size_t n) { (void)src; (void)n; return dst; }
void *memset(void *dst, int c, size_t n) { (void)c; (void)n; return dst; }
