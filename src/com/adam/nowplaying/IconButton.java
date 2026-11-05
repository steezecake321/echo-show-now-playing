package com.adam.nowplaying;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Transport button that draws its own play/pause/prev/next glyph, optionally on a tinted disc. */
final class IconButton extends View {

    static final int PREV = 0, NEXT = 1, PLAY = 2, PAUSE = 3;

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean withDisc;
    private int discColor = Color.argb(150, 255, 255, 255);
    private int icon;

    IconButton(Context ctx, int icon, boolean withDisc) {
        super(ctx);
        this.icon = icon;
        this.withDisc = withDisc;
        glyph.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
    }

    void setIcon(int icon) {
        if (this.icon != icon) {
            this.icon = icon;
            invalidate();
        }
    }

    void setDiscColor(int color) {
        if (discColor != color) {
            discColor = color;
            invalidate();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        float alpha = isPressed() ? 0.55f : (isEnabled() ? 1f : 0.35f);
        if (withDisc) {
            disc.setColor(discColor);
            disc.setAlpha((int) (Color.alpha(discColor) * alpha));
            c.drawCircle(cx, cy, r, disc);
        }
        glyph.setColor(Color.argb((int) (255 * alpha), 255, 255, 255));
        float s = r * (withDisc ? 0.34f : 0.42f);
        path.reset();
        switch (icon) {
            case PLAY:
                path.moveTo(cx - s * 0.62f, cy - s);
                path.lineTo(cx + s * 0.95f, cy);
                path.lineTo(cx - s * 0.62f, cy + s);
                path.close();
                c.drawPath(path, glyph);
                break;
            case PAUSE:
                float bw = s * 0.42f;
                c.drawRoundRect(cx - s * 0.72f, cy - s, cx - s * 0.72f + bw, cy + s, bw / 3, bw / 3, glyph);
                c.drawRoundRect(cx + s * 0.72f - bw, cy - s, cx + s * 0.72f, cy + s, bw / 3, bw / 3, glyph);
                break;
            case PREV:
            case NEXT:
                float dir = icon == NEXT ? 1f : -1f;
                path.moveTo(cx - dir * s * 0.8f, cy - s * 0.85f);
                path.lineTo(cx + dir * s * 0.55f, cy);
                path.lineTo(cx - dir * s * 0.8f, cy + s * 0.85f);
                path.close();
                c.drawPath(path, glyph);
                float barX = cx + dir * s * 0.62f;
                float barW = s * 0.24f;
                c.drawRect(Math.min(barX, barX + dir * barW), cy - s * 0.85f,
                        Math.max(barX, barX + dir * barW), cy + s * 0.85f, glyph);
                break;
        }
    }
}
