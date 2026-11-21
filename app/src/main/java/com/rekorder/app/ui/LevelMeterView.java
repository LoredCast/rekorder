// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.rekorder.app.audio.PcmConverter;

import java.util.Locale;

/**
 * Precision AMOLED horizontal studio dBFS meter supporting both Mono (single bar)
 * and Stereo (independent L/R dual bars with per-channel peak-hold), CLIP latch, and Compressor GR readout.
 */
public final class LevelMeterView extends View {

    private static final float MIN_DB = -60f;
    private static final float MAX_DB = 0f;

    private int channelCount = 1;
    private float currentDbL = -96f, currentDbR = -96f;
    private float rmsDbL = -96f, rmsDbR = -96f;
    private float peakHoldDbL = -96f, peakHoldDbR = -96f;
    private long peakHoldTimeMsL = 0L, peakHoldTimeMsR = 0L;
    private float gainReductionDb = 0f;
    private boolean clippedLatch = false;
    private int totalClips = 0;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rmsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float dp;

    public LevelMeterView(Context context) {
        super(context);
        this.dp = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF000000);

        trackPaint.setColor(0xFF121212);
        barPaint.setColor(0xFF2E2E2E);
        rmsPaint.setColor(0xFFEBEBEB);
        holdPaint.setColor(0xFFFFFFFF);
        holdPaint.setStrokeWidth(2f * dp);

        tickPaint.setColor(0xFF282828);
        tickPaint.setStrokeWidth(1f * dp);

        textPaint.setColor(0xFF555555);
        textPaint.setTextSize(9f * dp);
        textPaint.setTypeface(Typeface.MONOSPACE);

        chPaint.setColor(0xFF666666);
        chPaint.setTextSize(8.5f * dp);
        chPaint.setTypeface(Typeface.MONOSPACE);

        valuePaint.setColor(0xFF9A9A9A);
        valuePaint.setTextSize(10.5f * dp);
        valuePaint.setTypeface(Typeface.MONOSPACE);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void reset() {
        currentDbL = currentDbR = -96f;
        rmsDbL = rmsDbR = -96f;
        peakHoldDbL = peakHoldDbR = -96f;
        gainReductionDb = 0f;
        clippedLatch = false;
        totalClips = 0;
        invalidate();
    }

    public void setChannelCount(int channels) {
        int ch = (channels == 2) ? 2 : 1;
        if (this.channelCount != ch) {
            this.channelCount = ch;
            requestLayout();
            invalidate();
        }
    }

    public void updateLevel(float linearPeak, float linearRms, boolean clippedNow,
                            int clipCountTotal, float compGrDb) {
        updateLevelStereo(linearPeak, linearRms, linearPeak, linearRms, 1, clippedNow, clipCountTotal, compGrDb);
    }

    public void updateLevelStereo(float linearPeakL, float linearRmsL,
                                  float linearPeakR, float linearRmsR,
                                  int channels, boolean clippedNow,
                                  int clipCountTotal, float compGrDb) {
        int ch = (channels == 2) ? 2 : 1;
        if (this.channelCount != ch) {
            this.channelCount = ch;
            requestLayout();
        }

        long now = System.currentTimeMillis();
        float dbL = PcmConverter.linearToDbfs(linearPeakL);
        float rDbL = PcmConverter.linearToDbfs(linearRmsL);
        currentDbL = (dbL >= currentDbL) ? dbL : Math.max(dbL, currentDbL - 2.2f);
        rmsDbL = rDbL;
        if (dbL >= peakHoldDbL) {
            peakHoldDbL = dbL;
            peakHoldTimeMsL = now;
        } else if (now - peakHoldTimeMsL > 1200L) {
            peakHoldDbL = Math.max(dbL, peakHoldDbL - 1.5f);
        }

        float dbR = PcmConverter.linearToDbfs(linearPeakR);
        float rDbR = PcmConverter.linearToDbfs(linearRmsR);
        currentDbR = (dbR >= currentDbR) ? dbR : Math.max(dbR, currentDbR - 2.2f);
        rmsDbR = rDbR;
        if (dbR >= peakHoldDbR) {
            peakHoldDbR = dbR;
            peakHoldTimeMsR = now;
        } else if (now - peakHoldTimeMsR > 1200L) {
            peakHoldDbR = Math.max(dbR, peakHoldDbR - 1.5f);
        }

        if (compGrDb <= gainReductionDb) {
            gainReductionDb = compGrDb;
        } else {
            gainReductionDb = Math.min(0f, gainReductionDb + 0.6f);
        }

        if (clippedNow || clipCountTotal > 0) {
            clippedLatch = true;
            totalClips = clipCountTotal;
        }
        postInvalidateOnAnimation();
    }

    private float dbToFraction(float db) {
        if (db <= MIN_DB) return 0f;
        if (db >= MAX_DB) return 1f;
        return (db - MIN_DB) / (MAX_DB - MIN_DB);
    }

    private void drawSingleBar(Canvas canvas, float left, float right, float top, float bottom,
                               float peakDb, float rDb, float holdDb) {
        float meterW = right - left;
        float r = 2f * dp;
        canvas.drawRoundRect(left, top, right, bottom, r, r, trackPaint);

        float peakFrac = dbToFraction(peakDb);
        if (peakFrac > 0.001f) {
            float fillRight = left + meterW * peakFrac;
            if (peakDb >= -0.3f) barPaint.setColor(0x99FF2D2D);
            else if (peakDb >= -6f) barPaint.setColor(0x99FFB800);
            else barPaint.setColor(0x55E0E0E0);
            canvas.drawRoundRect(left, top, fillRight, bottom, r, r, barPaint);
        }

        float rmsFrac = dbToFraction(rDb);
        if (rmsFrac > 0.001f) {
            float fillRight = left + meterW * rmsFrac;
            if (rDb >= -3f) rmsPaint.setColor(0xFFFF2D2D);
            else if (rDb >= -9f) rmsPaint.setColor(0xFFFFB800);
            else rmsPaint.setColor(0xFFEBEBEB);
            float inset = Math.min(1.5f * dp, (bottom - top) * 0.2f);
            canvas.drawRoundRect(left, top + inset, fillRight, bottom - inset, r, r, rmsPaint);
        }

        float holdFrac = dbToFraction(holdDb);
        if (holdFrac > 0.005f) {
            float hx = left + meterW * holdFrac;
            holdPaint.setColor(holdDb >= -0.3f ? 0xFFFF2D2D : (holdDb >= -6f ? 0xFFFFB800 : 0xFFFFFFFF));
            canvas.drawLine(hx, top - 0.8f * dp, hx, bottom + 0.8f * dp, holdPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean stereo = (channelCount == 2);
        float left = stereo ? 26f * dp : 16f * dp;
        float valueSpace = 82f * dp;
        float right = w - 16f * dp - valueSpace;
        if (right <= left + 20f * dp) return;
        float meterW = right - left;

        float scaleTop;
        if (stereo) {
            float lTop = 4f * dp, lBot = 10f * dp;
            float rTop = 13f * dp, rBot = 19f * dp;
            canvas.drawText("L", 14f * dp, lBot - 0.5f * dp, chPaint);
            canvas.drawText("R", 14f * dp, rBot - 0.5f * dp, chPaint);
            drawSingleBar(canvas, left, right, lTop, lBot, currentDbL, rmsDbL, peakHoldDbL);
            drawSingleBar(canvas, left, right, rTop, rBot, currentDbR, rmsDbR, peakHoldDbR);
            scaleTop = rBot;
        } else {
            float barTop = 5f * dp, barBot = 13f * dp;
            drawSingleBar(canvas, left, right, barTop, barBot, currentDbL, rmsDbL, peakHoldDbL);
            scaleTop = barBot;
        }

        int[] marks = {-48, -36, -24, -12, -6, -3, 0};
        for (int db : marks) {
            float tx = left + meterW * dbToFraction(db);
            canvas.drawLine(tx, scaleTop + 2f * dp, tx, scaleTop + 5f * dp, tickPaint);
            String s = (db == 0) ? "0" : String.valueOf(db);
            textPaint.setTextAlign(db == 0 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(s, tx, scaleTop + 14f * dp, textPaint);
        }

        float maxDb = stereo ? Math.max(currentDbL, currentDbR) : currentDbL;
        String label;
        if (clippedLatch) {
            valuePaint.setColor(0xFFFF2D2D);
            label = totalClips > 0 ? "CLIP (" + totalClips + ")" : "CLIP";
        } else if (maxDb <= -80f) {
            valuePaint.setColor(0xFF555555);
            label = "-∞ dBFS";
        } else {
            valuePaint.setColor(maxDb >= -6f ? 0xFFFFB800 : 0xFFB0B0B0);
            label = String.format(Locale.US, "%.1f dB", maxDb);
        }
        canvas.drawText(label, w - 16f * dp, scaleTop - (stereo ? 2f : 0.5f) * dp, valuePaint);

        if (gainReductionDb <= -0.3f) {
            valuePaint.setColor(0xFFFFB800);
            canvas.drawText(String.format(Locale.US, "GR %.1f", gainReductionDb),
                    w - 16f * dp, scaleTop + 14f * dp, valuePaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round((channelCount == 2 ? 38f : 32f) * dp);
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec));
    }
}
