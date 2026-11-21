// SPDX-License-Identifier: GPL-3.0-or-later
package com.rekorder.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Minimalist AMOLED Record/Stop button: crisp outer white ring with
 * morphing crimson inner circle -> rounded square.
 */
public final class RecordButtonView extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    private boolean recording = false;
    private boolean paused = false;
    private float morph = 0f; // 0 = circle (idle), 1 = rounded square (recording)
    private float pressScale = 1f;
    private ValueAnimator animator;

    public RecordButtonView(Context context) {
        super(context);
        this.dp = context.getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        setContentDescription("Record or Stop");

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.6f * dp);
        ringPaint.setColor(0xFFEAEAEA);

        innerPaint.setStyle(Paint.Style.FILL);
        innerPaint.setColor(0xFFFF2D2D);
    }

    public void setRecordingState(boolean isRecording, boolean isPaused) {
        this.paused = isPaused;
        if (this.recording == isRecording) {
            invalidate();
            return;
        }
        this.recording = isRecording;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(morph, isRecording ? 1f : 0f);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            morph = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float outerR = Math.min(cx, cy) - 3f * dp;

        canvas.save();
        canvas.scale(pressScale, pressScale, cx, cy);

        ringPaint.setColor(paused ? 0xFF7A7A7A : 0xFFEAEAEA);
        canvas.drawCircle(cx, cy, outerR, ringPaint);

        float idleHalf = outerR - 7.5f * dp;
        float recHalf = outerR * 0.42f;
        float half = idleHalf + (recHalf - idleHalf) * morph;
        float corner = half + (6f * dp - half) * morph;

        innerPaint.setColor(paused ? 0xFFFFB800 : 0xFFFF2D2D);
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, innerPaint);

        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressScale = 0.93f;
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressScale = 1f;
                invalidate();
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(78f * dp);
        setMeasuredDimension(size, size);
    }
}
