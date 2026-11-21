// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.audio;

/**
 * Lightweight progress callback for long-running audio normalization, encoding,
 * and waveform preview extraction.
 */
public interface ProgressListener {
    void onProgress(long doneBytesOrUnits, long totalBytesOrUnits, int percent);
}
