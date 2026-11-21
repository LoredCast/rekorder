// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Sleek pitch-black (#000000) AMOLED live processing & loading indicator card.
 * Displays active stage title, exact percentage (or smooth animated indeterminate sweep),
 * custom Canvas progress bar, and MB / ETA / gain detail line.
 */
public final class ProcessingBannerView extends LinearLayout {

    private final TextView titleText;
    private final TextView percentText;
    private final TextView detailText;
    private final SmoothProgressBar barView;
    private final GradientDrawable cardBg;
    private final float dp;

    public ProcessingBannerView(Context ctx) {
        super(ctx);
        this.dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(11), dp(14), dp(11));

        cardBg = new GradientDrawable();
        cardBg.setColor(0xFF080808);
        cardBg.setCornerRadius(dp(8));
        cardBg.setStroke(dp(1), 0xFFFFB800);
        setBackground(cardBg);

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        titleText = new TextView(ctx);
        titleText.setTextColor(0xFFFFB800);
        titleText.setTextSize(11.5f);
        titleText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topRow.addView(titleText, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        percentText = new TextView(ctx);
        percentText.setTextColor(0xFFF2F2F2);
        percentText.setTextSize(12f);
        percentText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        percentText.setPadding(dp(10), 0, 0, 0);
        topRow.addView(percentText);

        addView(topRow);

        barView = new SmoothProgressBar(ctx);
        LayoutParams barLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        barLp.topMargin = dp(8);
        barLp.bottomMargin = dp(7);
        addView(barView, barLp);

        detailText = new TextView(ctx);
        detailText.setTextColor(0xFF8A8A8A);
        detailText.setTextSize(10.5f);
        detailText.setTypeface(Typeface.MONOSPACE);
        detailText.setSingleLine(true);
        detailText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(detailText);

        setVisibility(GONE);
    }

    public void showProgress(String stage, String detail, int percent, int accentColor) {
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        cardBg.setStroke(dp(1), accentColor);
        titleText.setText(stage != null ? stage : "PROCESSING");
        titleText.setTextColor(accentColor);
        detailText.setText(detail != null ? detail : "");
        if (percent >= 0) {
            int clamped = Math.max(0, Math.min(100, percent));
            percentText.setText(clamped + "%");
        } else {
            percentText.setText("...");
        }
        barView.update(percent, accentColor);
    }

    public void hide() {
        barView.stopAnim();
        setVisibility(GONE);
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }

    private static final class SmoothProgressBar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;

        private int percent = -1; // -1 = indeterminate sweep
        private int accentColor = 0xFFFFB800;
        private long startTimeMs = System.currentTimeMillis();
        private boolean animating = false;

        SmoothProgressBar(Context ctx) {
            super(ctx);
            this.dp = ctx.getResources().getDisplayMetrics().density;
            trackPaint.setColor(0xFF181818);
            fillPaint.setColor(accentColor);
        }

        void update(int pct, int color) {
            this.percent = pct;
            this.accentColor = color;
            fillPaint.setColor(color);
            if (pct < 0) {
                if (!animating) {
                    animating = true;
                    startTimeMs = System.currentTimeMillis();
                }
                postInvalidateOnAnimation();
            } else {
                animating = false;
                invalidate();
            }
        }

        void stopAnim() {
            animating = false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float r = h * 0.5f;
            canvas.drawRoundRect(0, 0, w, h, r, r, trackPaint);

            if (percent >= 0) {
                float frac = Math.max(0f, Math.min(1f, percent / 100f));
                float fillW = Math.max(h, w * frac);
                if (frac > 0f) {
                    canvas.drawRoundRect(0, 0, Math.min(w, fillW), h, r, r, fillPaint);
                }
            } else {
                // Smooth 1.1s indeterminate sweep so user immediately sees live activity
                float phase = ((System.currentTimeMillis() - startTimeMs) % 1100L) / 1100f;
                float segW = w * 0.34f;
                float left = -segW + (w + segW) * phase;
                float right = left + segW;
                canvas.save();
                canvas.clipRect(0, 0, w, h);
                canvas.drawRoundRect(left, 0, right, h, r, r, fillPaint);
                canvas.restore();
                if (animating && getVisibility() == VISIBLE) {
                    postInvalidateOnAnimation();
                }
            }
        }
    }
}
