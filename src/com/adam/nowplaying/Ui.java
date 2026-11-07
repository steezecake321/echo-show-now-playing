package com.adam.nowplaying;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small shared helpers for the hand-built panels: sizes, text, pills and a tiny image loader. */
final class Ui {

    static final int GREEN = Color.rgb(29, 185, 84);
    static final int CARD = Color.rgb(28, 27, 31);
    static final int DIM = Color.argb(150, 255, 255, 255);

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int color, String family, int lines) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** A pill-shaped tappable label. */
    static TextView pill(Context c, String label, boolean selected) {
        TextView t = text(c, 14, selected ? Color.BLACK : Color.WHITE, "sans-serif-medium", 1);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(round(c, selected ? GREEN : Color.argb(36, 255, 255, 255), 20));
        t.setClickable(true);
        return t;
    }

    static void restylePill(TextView t, boolean selected) {
        Context c = t.getContext();
        t.setTextColor(selected ? Color.BLACK : Color.WHITE);
        t.setBackground(round(c, selected ? GREEN : Color.argb(36, 255, 255, 255), 20));
    }

    static String clock(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    // ---------- tiny image loader (small bitmaps, 1 GB device) ----------

    private static final LruCache<String, Bitmap> cache = new LruCache<>(40);
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Loads url into the view, sized to about px pixels; ignores results for recycled views. */
    static void load(final ImageView view, final String url, final int px) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null) return;
        Bitmap hit = cache.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                InputStream in = c.getInputStream();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2; // covers are 300-640 px; the list shows them around 56-90 px
                b = BitmapFactory.decodeStream(in, null, o);
                in.close();
                if (b != null && b.getWidth() > px * 2) {
                    Bitmap s = Bitmap.createScaledBitmap(b, px * 2, px * 2 * b.getHeight() / b.getWidth(), true);
                    if (s != b) b.recycle();
                    b = s;
                }
            } catch (Exception ignored) {
            }
            final Bitmap result = b;
            if (result != null) cache.put(url, result);
            main.post(() -> {
                if (result != null && url.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }

    static View spacer(Context c, int wDp) {
        View v = new View(c);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, wDp), 1));
        return v;
    }
}
