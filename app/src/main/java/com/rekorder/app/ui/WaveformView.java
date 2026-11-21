// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * High-contrast pitch-black (#000000) AMOLED studio waveform canvas.
 * Supports:
 *  - Real-time scrolling Peak + RMS live waveform
 *  - Full-track preview with touch scrubbing, two-finger horizontal pinch-to-zoom & pan (up to 32x)
 *  - Vertical amplitude display zoom (VIEW 1x / 2x / 4x)
 *  - Amber recording bookmark markers
 *  - Interactive draggable trim IN/OUT handles
 */
public final class WaveformView extends View {

    public enum Mode {
        IDLE,
        LIVE_RECORDING,
        LIVE_MONITORING,
        LIVE_PAUSED,
        PREVIEW
    }

    public interface OnScrubListener {
        void onScrub(float fraction, boolean finished);
    }

    public interface OnZoomToggleListener {
        void onZoomToggled(int newGain);
    }

    public interface OnTrimChangeListener {
        void onTrimChanged(float startFrac, float endFrac);
    }

    private static final int LIVE_CAPACITY = 260;

    private Mode mode = Mode.IDLE;
    private int visualGain = 1; // 1, 2, or 4 (vertical display-only gain)

    // Horizontal zoom/pan for long-file preview scrubbing
    private float hZoom = 1f;       // 1f .. 32f
    private float hScroll = 0f;     // [0, 1 - 1/hZoom]
    private float lastPanX = 0f;
    private boolean multiPointerActive = false;

    // Live scrolling ring
    private final float[] livePeaks = new float[LIVE_CAPACITY];
    private final float[] liveRms = new float[LIVE_CAPACITY];
    private final boolean[] liveClip = new boolean[LIVE_CAPACITY];
    private int liveHead = 0;
    private int liveCount = 0;

    // Preview waveform
    private float[] previewPeaks;
    private float[] previewRms;
    private boolean loadingPreview = false;
    private int loadingPercent = -1;
    private float playheadFraction = 0f;
    private boolean draggingScrub = false;

    // Markers (fractions in [0, 1])
    private final List<Float> markerFractions = new ArrayList<>();

    // Interactive Trim handles
    private boolean trimMode = false;
    private float trimStartFrac = 0f;
    private float trimEndFrac = 1f;
    private int draggingTrimHandle = 0; // 0 = none, 1 = start, 2 = end

    private OnScrubListener scrubListener;
    private OnZoomToggleListener zoomToggleListener;
    private OnTrimChangeListener trimChangeListener;

    private final ScaleGestureDetector scaleDetector;

    // Paints
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rmsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float dp;
    private float zoomLeft, zoomTop, zoomRight, zoomBottom;
    private float hResetLeft, hResetTop, hResetRight, hResetBottom;

    public WaveformView(Context context) {
        super(context);
        this.dp = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF000000);

        gridPaint.setColor(0xFF141414);
        gridPaint.setStrokeWidth(1f * dp);

        axisPaint.setColor(0xFF222222);
        axisPaint.setStrokeWidth(1f * dp);

        labelPaint.setColor(0xFF444444);
        labelPaint.setTextSize(9.5f * dp);
        labelPaint.setTypeface(Typeface.MONOSPACE);

        peakPaint.setStyle(Paint.Style.FILL);
        rmsPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setStrokeWidth(1.5f * dp);

        markerPaint.setColor(0xFFFFB800);
        markerPaint.setStrokeWidth(1.3f * dp);
        markerPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        trimShadePaint.setColor(0xB8000000);
        trimShadePaint.setStyle(Paint.Style.FILL);

        trimHandlePaint.setColor(0xFFFFB800);
        trimHandlePaint.setStrokeWidth(2.4f * dp);
        trimHandlePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        zoomBgPaint.setColor(0xFF0D0D0D);
        zoomBgPaint.setStyle(Paint.Style.FILL);

        zoomBorderPaint.setColor(0xFF282828);
        zoomBorderPaint.setStyle(Paint.Style.STROKE);
        zoomBorderPaint.setStrokeWidth(1f * dp);

        zoomTextPaint.setColor(0xFF9A9A9A);
        zoomTextPaint.setTextSize(10f * dp);
        zoomTextPaint.setTypeface(Typeface.MONOSPACE);
        zoomTextPaint.setTextAlign(Paint.Align.CENTER);

        idleTextPaint.setColor(0xFF424242);
        idleTextPaint.setTextSize(11.5f * dp);
        idleTextPaint.setTypeface(Typeface.MONOSPACE);
        idleTextPaint.setTextAlign(Paint.Align.CENTER);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mode != Mode.PREVIEW || previewPeaks == null) return false;
                float padLeft = 12f * dp;
                float usableW = Math.max(1f, getWidth() - 24f * dp);
                float focusScreenFrac = Math.max(0f, Math.min(1f, (detector.getFocusX() - padLeft) / usableW));
                float anchorAudioFrac = hScroll + focusScreenFrac / hZoom;

                float nextZoom = Math.max(1f, Math.min(32f, hZoom * detector.getScaleFactor()));
                hZoom = nextZoom;
                float visibleSpan = 1f / hZoom;
                hScroll = Math.max(0f, Math.min(1f - visibleSpan, anchorAudioFrac - focusScreenFrac * visibleSpan));
                invalidate();
                return true;
            }
        });
    }

    public void setOnScrubListener(OnScrubListener l) { this.scrubListener = l; }
    public void setOnZoomToggleListener(OnZoomToggleListener l) { this.zoomToggleListener = l; }
    public void setOnTrimChangeListener(OnTrimChangeListener l) { this.trimChangeListener = l; }

    public void setVisualGain(int gain) {
        this.visualGain = (gain == 2 || gain == 4) ? gain : 1;
        invalidate();
    }

    public int getVisualGain() { return visualGain; }

    public void setMode(Mode m) {
        if (this.mode != m) {
            this.mode = m;
            if (m != Mode.PREVIEW) {
                hZoom = 1f;
                hScroll = 0f;
                trimMode = false;
            }
            invalidate();
        }
    }

    public void clearLive() {
        Arrays.fill(livePeaks, 0f);
        Arrays.fill(liveRms, 0f);
        Arrays.fill(liveClip, false);
        liveHead = 0;
        liveCount = 0;
        invalidate();
    }

    public void pushLiveSample(float peak, float rms, boolean clipped) {
        livePeaks[liveHead] = peak;
        liveRms[liveHead] = rms;
        liveClip[liveHead] = clipped;
        liveHead = (liveHead + 1) % LIVE_CAPACITY;
        if (liveCount < LIVE_CAPACITY) liveCount++;
        postInvalidateOnAnimation();
    }

    public void setPreviewLoading(boolean loading) {
        this.loadingPreview = loading;
        this.loadingPercent = -1;
        if (loading) {
            this.mode = Mode.PREVIEW;
            this.previewPeaks = null;
            this.previewRms = null;
            this.playheadFraction = 0f;
            this.hZoom = 1f;
            this.hScroll = 0f;
            this.trimMode = false;
            this.trimStartFrac = 0f;
            this.trimEndFrac = 1f;
        }
        invalidate();
    }

    public void setPreviewLoadingPercent(int percent) {
        this.loadingPercent = percent;
        if (loadingPreview) postInvalidateOnAnimation();
    }

    public void setPreviewWaveform(float[] peaks, float[] rms) {
        this.loadingPreview = false;
        this.previewPeaks = peaks;
        this.previewRms = rms;
        this.playheadFraction = 0f;
        this.hZoom = 1f;
        this.hScroll = 0f;
        this.trimMode = false;
        this.trimStartFrac = 0f;
        this.trimEndFrac = 1f;
        this.mode = Mode.PREVIEW;
        invalidate();
    }

    public void setMarkers(List<Long> markersMs, long totalDurationMs) {
        markerFractions.clear();
        if (markersMs != null && totalDurationMs > 0L) {
            for (Long ms : markersMs) {
                if (ms != null && ms >= 0L) {
                    float f = Math.max(0f, Math.min(1f, (float) ms / (float) totalDurationMs));
                    markerFractions.add(f);
                }
            }
        }
        invalidate();
    }

    public void setTrimMode(boolean enabled) {
        this.trimMode = enabled;
        if (enabled && (trimEndFrac <= trimStartFrac + 0.01f)) {
            trimStartFrac = 0f;
            trimEndFrac = 1f;
        }
        invalidate();
    }

    public boolean isTrimMode() { return trimMode; }
    public float getTrimStartFraction() { return trimStartFrac; }
    public float getTrimEndFraction() { return trimEndFrac; }

    public void resetTrimRange() {
        trimStartFrac = 0f;
        trimEndFrac = 1f;
        invalidate();
        if (trimChangeListener != null) trimChangeListener.onTrimChanged(trimStartFrac, trimEndFrac);
    }

    public void setPlayheadFraction(float fraction) {
        this.playheadFraction = Math.max(0f, Math.min(1f, fraction));
        // Auto-follow playhead when zoomed in
        if (mode == Mode.PREVIEW && hZoom > 1.05f && !multiPointerActive) {
            float span = 1f / hZoom;
            if (playheadFraction < hScroll || playheadFraction > hScroll + span) {
                hScroll = Math.max(0f, Math.min(1f - span, playheadFraction - span * 0.25f));
            }
        }
        if (mode == Mode.PREVIEW) postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padLeft = 12f * dp;
        float padRight = 12f * dp;
        float padTop = 22f * dp;
        float padBottom = 18f * dp;
        float usableW = w - padLeft - padRight;
        float usableH = h - padTop - padBottom;
        float midY = padTop + usableH * 0.5f;
        float halfH = usableH * 0.47f;

        drawReferenceGrid(canvas, padLeft, w - padRight, midY, halfH);

        if (mode == Mode.LIVE_RECORDING || mode == Mode.LIVE_MONITORING || mode == Mode.LIVE_PAUSED) {
            drawLiveWaveform(canvas, padLeft, usableW, midY, halfH);
        } else if (mode == Mode.PREVIEW) {
            if (loadingPreview) {
                String msg = (loadingPercent >= 0)
                        ? "ANALYZING WAVEFORM · " + loadingPercent + "%"
                        : "ANALYZING WAVEFORM...";
                canvas.drawText(msg, w * 0.5f, midY - 6f * dp, idleTextPaint);
            } else if (previewPeaks != null && previewPeaks.length > 0) {
                drawPreviewWaveform(canvas, padLeft, usableW, midY, halfH);
            } else {
                canvas.drawText("EMPTY RECORDING", w * 0.5f, midY - 6f * dp, idleTextPaint);
            }
        } else {
            canvas.drawText("RAW UNPROCESSED · READY", w * 0.5f, midY - 8f * dp, idleTextPaint);
        }

        drawZoomPill(canvas, w);
        if (mode == Mode.PREVIEW && hZoom > 1.05f) {
            drawHorizZoomPillAndScrollbar(canvas, padLeft, usableW, h);
        } else {
            hResetLeft = hResetRight = hResetTop = hResetBottom = 0f;
        }
    }

    private void drawReferenceGrid(Canvas canvas, float left, float right, float midY, float halfH) {
        canvas.drawLine(left, midY, right, midY, axisPaint);
        float[] levels = {1.0f, 0.5f, 0.126f};
        String[] labels = {"0dB", "-6", "-18"};
        for (int i = 0; i < levels.length; i++) {
            float dy = levels[i] * halfH;
            canvas.drawLine(left, midY - dy, right, midY - dy, gridPaint);
            canvas.drawLine(left, midY + dy, right, midY + dy, gridPaint);
            if (i < 2) {
                canvas.drawText(labels[i], left + 2f * dp, midY - dy + 10f * dp, labelPaint);
            }
        }
    }

    private void drawLiveWaveform(Canvas canvas, float left, float usableW, float midY, float halfH) {
        float rightEdge = left + usableW - 4f * dp;
        float barStep = (usableW - 4f * dp) / LIVE_CAPACITY;
        float barW = Math.max(1.2f * dp, barStep * 0.68f);

        for (int i = 0; i < liveCount; i++) {
            int idx = (liveHead - 1 - i + LIVE_CAPACITY) % LIVE_CAPACITY;
            float x = rightEdge - (i + 1) * barStep;
            if (x + barW < left) break;

            float pk = Math.min(1f, livePeaks[idx] * visualGain);
            float rm = Math.min(1f, liveRms[idx] * visualGain);
            boolean clip = liveClip[idx];

            float pkH = Math.max(1f * dp, pk * halfH);
            float rmH = Math.max(0.8f * dp, rm * halfH);

            int peakColor, rmsColor;
            if (clip || livePeaks[idx] >= 0.99f) {
                peakColor = 0x99FF2D2D; rmsColor = 0xFFFF2D2D;
            } else if (livePeaks[idx] >= 0.707f) {
                peakColor = 0x88FFB800; rmsColor = 0xFFFFB800;
            } else if (mode == Mode.LIVE_MONITORING) {
                peakColor = 0x5500E5A0; rmsColor = 0xFF00E5A0;
            } else if (mode == Mode.LIVE_PAUSED) {
                peakColor = 0x44888888; rmsColor = 0xFF888888;
            } else {
                peakColor = 0x55F2F2F2; rmsColor = 0xFFEBEBEB;
            }

            peakPaint.setColor(peakColor);
            canvas.drawRect(x, midY - pkH, x + barW, midY + pkH, peakPaint);
            rmsPaint.setColor(rmsColor);
            canvas.drawRect(x, midY - rmH, x + barW, midY + rmH, rmsPaint);
        }

        if (mode == Mode.LIVE_RECORDING) cursorPaint.setColor(0xFFFF2D2D);
        else if (mode == Mode.LIVE_MONITORING) cursorPaint.setColor(0xFF00E5A0);
        else cursorPaint.setColor(0xFF666666);
        canvas.drawLine(rightEdge, midY - halfH, rightEdge, midY + halfH, cursorPaint);
    }

    private float audioFracToScreenX(float audioFrac, float left, float usableW) {
        return left + ((audioFrac - hScroll) * hZoom) * usableW;
    }

    private float screenXToAudioFrac(float screenX, float left, float usableW) {
        float viewFrac = Math.max(0f, Math.min(1f, (screenX - left) / Math.max(1f, usableW)));
        return Math.max(0f, Math.min(1f, hScroll + viewFrac / hZoom));
    }

    private void drawPreviewWaveform(Canvas canvas, float left, float usableW, float midY, float halfH) {
        int n = previewPeaks.length;
        float visibleSpan = 1f / hZoom;
        float viewEnd = Math.min(1f, hScroll + visibleSpan);

        int startIdx = Math.max(0, (int) Math.floor(hScroll * n));
        int endIdx = Math.min(n - 1, (int) Math.ceil(viewEnd * n));
        float barStep = (usableW * hZoom) / n;
        float barW = Math.max(1.2f * dp, barStep * 0.72f);

        canvas.save();
        canvas.clipRect(left, midY - halfH - 8f * dp, left + usableW, midY + halfH + 8f * dp);

        for (int i = startIdx; i <= endIdx; i++) {
            float binFrac = (i + 0.5f) / n;
            float x = audioFracToScreenX((float) i / n, left, usableW);
            boolean played = binFrac <= playheadFraction;

            float rawPk = previewPeaks[i];
            float rawRm = (previewRms != null && i < previewRms.length) ? previewRms[i] : rawPk * 0.6f;
            float pk = Math.min(1f, rawPk * visualGain);
            float rm = Math.min(1f, rawRm * visualGain);
            float pkH = Math.max(1f * dp, pk * halfH);
            float rmH = Math.max(0.8f * dp, rm * halfH);

            if (rawPk >= 0.99f) {
                peakPaint.setColor(played ? 0x99FF2D2D : 0x55FF2D2D);
                rmsPaint.setColor(played ? 0xFFFF2D2D : 0xAAFF2D2D);
            } else if (played) {
                peakPaint.setColor(0x5500E5A0);
                rmsPaint.setColor(0xFF00E5A0);
            } else {
                peakPaint.setColor(0x40A0A0A0);
                rmsPaint.setColor(0xFFD0D0D0);
            }

            canvas.drawRect(x, midY - pkH, x + barW, midY + pkH, peakPaint);
            canvas.drawRect(x, midY - rmH, x + barW, midY + rmH, rmsPaint);
        }

        // Draw amber bookmark markers
        for (Float mf : markerFractions) {
            float mx = audioFracToScreenX(mf, left, usableW);
            if (mx >= left && mx <= left + usableW) {
                canvas.drawLine(mx, midY - halfH, mx, midY + halfH, markerPaint);
                canvas.drawCircle(mx, midY + halfH + 2f * dp, 2.5f * dp, markerPaint);
            }
        }

        // Draw Trim overlay & handles when Trim mode is active
        if (trimMode) {
            float x1 = audioFracToScreenX(trimStartFrac, left, usableW);
            float x2 = audioFracToScreenX(trimEndFrac, left, usableW);
            if (x1 > left) {
                canvas.drawRect(left, midY - halfH, Math.min(left + usableW, x1), midY + halfH, trimShadePaint);
            }
            if (x2 < left + usableW) {
                canvas.drawRect(Math.max(left, x2), midY - halfH, left + usableW, midY + halfH, trimShadePaint);
            }
            if (x1 >= left - 4f * dp && x1 <= left + usableW + 4f * dp) {
                canvas.drawLine(x1, midY - halfH, x1, midY + halfH, trimHandlePaint);
                canvas.drawRect(x1, midY - halfH, x1 + 10f * dp, midY - halfH + 14f * dp, trimHandlePaint);
            }
            if (x2 >= left - 4f * dp && x2 <= left + usableW + 4f * dp) {
                canvas.drawLine(x2, midY - halfH, x2, midY + halfH, trimHandlePaint);
                canvas.drawRect(x2 - 10f * dp, midY - halfH, x2, midY - halfH + 14f * dp, trimHandlePaint);
            }
        }

        // Crisp playhead cursor
        float playX = audioFracToScreenX(playheadFraction, left, usableW);
        if (playX >= left - 2f * dp && playX <= left + usableW + 2f * dp) {
            cursorPaint.setColor(0xFFFFFFFF);
            canvas.drawLine(playX, midY - halfH - 4f * dp, playX, midY + halfH + 4f * dp, cursorPaint);
            rmsPaint.setColor(0xFF00E5A0);
            canvas.drawCircle(playX, midY - halfH - 4f * dp, 3f * dp, rmsPaint);
        }

        canvas.restore();
    }

    private void drawZoomPill(Canvas canvas, int w) {
        float pillW = 60f * dp;
        float pillH = 22f * dp;
        zoomRight = w - 12f * dp;
        zoomLeft = zoomRight - pillW;
        zoomTop = 4f * dp;
        zoomBottom = zoomTop + pillH;

        float r = 5f * dp;
        canvas.drawRoundRect(zoomLeft, zoomTop, zoomRight, zoomBottom, r, r, zoomBgPaint);
        canvas.drawRoundRect(zoomLeft, zoomTop, zoomRight, zoomBottom, r, r, zoomBorderPaint);

        String txt = "VIEW " + visualGain + "x";
        zoomTextPaint.setColor(visualGain > 1 ? 0xFFF2F2F2 : 0xFF7A7A7A);
        float baseline = zoomTop + pillH * 0.5f - (zoomTextPaint.descent() + zoomTextPaint.ascent()) * 0.5f;
        canvas.drawText(txt, (zoomLeft + zoomRight) * 0.5f, baseline, zoomTextPaint);
    }

    private void drawHorizZoomPillAndScrollbar(Canvas canvas, float padLeft, float usableW, int h) {
        float pillW = 72f * dp;
        float pillH = 22f * dp;
        hResetRight = zoomLeft - 8f * dp;
        hResetLeft = hResetRight - pillW;
        hResetTop = zoomTop;
        hResetBottom = zoomBottom;

        float r = 5f * dp;
        canvas.drawRoundRect(hResetLeft, hResetTop, hResetRight, hResetBottom, r, r, zoomBgPaint);
        canvas.drawRoundRect(hResetLeft, hResetTop, hResetRight, hResetBottom, r, r, zoomBorderPaint);
        String txt = String.format(Locale.US, "%.1fx ✕", hZoom);
        zoomTextPaint.setColor(0xFF00E5A0);
        float baseline = hResetTop + pillH * 0.5f - (zoomTextPaint.descent() + zoomTextPaint.ascent()) * 0.5f;
        canvas.drawText(txt, (hResetLeft + hResetRight) * 0.5f, baseline, zoomTextPaint);

        // Subtle horizontal viewport scrollbar at bottom edge
        float barY = h - 5f * dp;
        float thumbLeft = padLeft + hScroll * usableW;
        float thumbW = Math.max(16f * dp, usableW / hZoom);
        peakPaint.setColor(0xFF1A1A1A);
        canvas.drawRoundRect(padLeft, barY, padLeft + usableW, barY + 3f * dp, 2f * dp, 2f * dp, peakPaint);
        rmsPaint.setColor(0xFF00E5A0);
        canvas.drawRoundRect(thumbLeft, barY, Math.min(padLeft + usableW, thumbLeft + thumbW),
                barY + 3f * dp, 2f * dp, 2f * dp, rmsPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode == Mode.PREVIEW && previewPeaks != null) {
            scaleDetector.onTouchEvent(event);
        }

        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (x >= zoomLeft - 10f * dp && x <= zoomRight + 10f * dp
                    && y >= zoomTop - 10f * dp && y <= zoomBottom + 10f * dp) {
                int next = (visualGain == 1) ? 2 : (visualGain == 2 ? 4 : 1);
                visualGain = next;
                invalidate();
                if (zoomToggleListener != null) zoomToggleListener.onZoomToggled(next);
                return true;
            }
            if (hZoom > 1.05f && x >= hResetLeft - 8f * dp && x <= hResetRight + 8f * dp
                    && y >= hResetTop - 10f * dp && y <= hResetBottom + 10f * dp) {
                hZoom = 1f;
                hScroll = 0f;
                invalidate();
                return true;
            }
        }

        if (mode != Mode.PREVIEW || previewPeaks == null || previewPeaks.length == 0) {
            return super.onTouchEvent(event);
        }

        float padLeft = 12f * dp;
        float usableW = Math.max(1f, getWidth() - 24f * dp);

        // Two-finger horizontal pan while zoomed
        if (event.getPointerCount() >= 2) {
            float avgX = 0.5f * (event.getX(0) + event.getX(1));
            if (!multiPointerActive) {
                multiPointerActive = true;
                draggingScrub = false;
                draggingTrimHandle = 0;
                lastPanX = avgX;
            } else if (action == MotionEvent.ACTION_MOVE && hZoom > 1.05f) {
                float dx = avgX - lastPanX;
                lastPanX = avgX;
                float span = 1f / hZoom;
                hScroll = Math.max(0f, Math.min(1f - span, hScroll - (dx / usableW) * span));
                invalidate();
            }
            return true;
        }

        if (multiPointerActive) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                multiPointerActive = false;
            }
            return true;
        }

        float audioFrac = screenXToAudioFrac(x, padLeft, usableW);

        // Trim handle grab check in Trim Mode
        if (trimMode) {
            float x1 = audioFracToScreenX(trimStartFrac, padLeft, usableW);
            float x2 = audioFracToScreenX(trimEndFrac, padLeft, usableW);
            if (action == MotionEvent.ACTION_DOWN) {
                float d1 = Math.abs(x - x1);
                float d2 = Math.abs(x - x2);
                if (d1 < 28f * dp && d1 <= d2) {
                    draggingTrimHandle = 1;
                    return true;
                } else if (d2 < 28f * dp) {
                    draggingTrimHandle = 2;
                    return true;
                }
            } else if (action == MotionEvent.ACTION_MOVE && draggingTrimHandle != 0) {
                if (draggingTrimHandle == 1) {
                    trimStartFrac = Math.max(0f, Math.min(trimEndFrac - 0.01f, audioFrac));
                } else {
                    trimEndFrac = Math.min(1f, Math.max(trimStartFrac + 0.01f, audioFrac));
                }
                invalidate();
                if (trimChangeListener != null) trimChangeListener.onTrimChanged(trimStartFrac, trimEndFrac);
                return true;
            } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && draggingTrimHandle != 0) {
                draggingTrimHandle = 0;
                return true;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                draggingScrub = true;
                playheadFraction = audioFrac;
                invalidate();
                if (scrubListener != null) scrubListener.onScrub(audioFrac, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingScrub) {
                    playheadFraction = audioFrac;
                    invalidate();
                    if (scrubListener != null) scrubListener.onScrub(audioFrac, false);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingScrub) {
                    draggingScrub = false;
                    playheadFraction = audioFrac;
                    invalidate();
                    if (scrubListener != null) scrubListener.onScrub(audioFrac, true);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }
}
