/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Intentionally empty shim so OpenJDK jni.h does not pull in host glibc stdio.h
 * when cross-compiling freestanding shared libraries for Android Bionic. */
#ifndef REKORDER_SHIM_STDIO_H
#define REKORDER_SHIM_STDIO_H
#endif
