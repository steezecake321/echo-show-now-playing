package com.adam.nowplaying;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Slim rounded progress/volume bar with no thumb; drag or tap to set a value 0..1. */
final class ThinBar extends View {

    interface OnSeek {
        void onDragging(float fraction);
        void onSeek(float fraction);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float thickness;
    private float fraction;
    private boolean dragging;
    private OnSeek onSeek;

    ThinBar(Context ctx, float thicknessPx) {
        super(ctx);
        thickness = thicknessPx;
        track.setColor(Color.argb(60, 255, 255, 255));
        fill.setColor(Color.argb(235, 255, 255, 255));
    }

    void setOnSeek(OnSeek l) {
        onSeek = l;
    }

    boolean isDragging() {
        return dragging;
    }

    void setFraction(float f) {
        if (dragging) return;
        f = Math.max(0f, Math.min(1f, f));
        if (f != fraction) {
            fraction = f;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = isEnabled() ? thickness : thickness * 0.8f;
        float cy = getHeight() / 2f;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        float rad = h / 2f;
        c.drawRoundRect(left, cy - h / 2, right, cy + h / 2, rad, rad, track);
        if (isEnabled() && fraction > 0) {
            float x = left + (right - left) * fraction;
            c.drawRoundRect(left, cy - h / 2, Math.max(x, left + h), cy + h / 2, rad, rad, fill);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        float f = Math.max(0f, Math.min(1f, (e.getX() - left) / Math.max(1f, right - left)));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
            case MotionEvent.ACTION_MOVE:
                fraction = f;
                invalidate();
                if (onSeek != null) onSeek.onDragging(f);
                return true;
            case MotionEvent.ACTION_UP:
                fraction = f;
                dragging = false;
                invalidate();
                if (onSeek != null) onSeek.onSeek(f);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
        }
        return true;
    }
}
