package com.adam.nowplaying;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screensaver that plays the pictures of a shared Google Photos album, with the clock and what is playing. */
public class PhotoDream extends DreamService implements PlayerState.Listener {

    private static final long SHOW_MS = 12_000;
    private static final long FADE_MS = 1_400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ImageView front, back;
    private TextView clock, now, message;
    private List<String> urls;
    private int index;
    private Bitmap ready;          // downloaded and waiting for its turn
    private boolean fetching;
    private volatile boolean alive;
    private int w, h;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        alive = true;
        PlayerService.dreaming = true;
        PlayerService.start(this);

        w = getResources().getDisplayMetrics().widthPixels;
        h = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = new ImageView(this);
        front = new ImageView(this);
        for (ImageView v : new ImageView[]{back, front}) {
            v.setScaleType(ImageView.ScaleType.MATRIX);
            v.setAlpha(0f);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 16));
        info.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(170, 0, 0, 0), Color.TRANSPARENT}));
        clock = Ui.text(this, 34, Color.WHITE, "sans-serif-light", 1);
        clock.setShadowLayer(8, 0, 2, Color.BLACK);
        now = Ui.text(this, 15, Color.argb(220, 255, 255, 255), "sans-serif", 1);
        now.setShadowLayer(6, 0, 1, Color.BLACK);
        info.addView(clock);
        info.addView(now);
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        message = Ui.text(this, 16, Color.argb(200, 255, 255, 255), "sans-serif", 3);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        root.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            wakeUp();
        });
        setContentView(root);

        PlayerState.get().addListener(this);
        onStateChanged();
        tickClock.run();

        if (PhotoAlbum.albumUrl(this) == null) {
            message.setText("No photo album set.\nSee the app's README to add a shared album link.");
            return;
        }
        message.setText("Loading photos…");
        PhotoAlbum.load(this, false, (list, err) -> {
            if (!alive) return;
            if (list.isEmpty()) {
                message.setText(err);
                return;
            }
            urls = new java.util.ArrayList<>(list);
            Collections.shuffle(urls);
            message.setVisibility(View.GONE);
            prefetch();
            ui.postDelayed(this::advance, 600);
        });
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        alive = false;
        ui.removeCallbacksAndMessages(null);
        PlayerState.get().removeListener(this);
        PlayerService.dreaming = false;
        io.shutdownNow();
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        ready = null;
    }

    private final Runnable tickClock = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            clock.setText(DateFormat.format(DateFormat.is24HourFormat(PhotoDream.this) ? "H:mm" : "h:mm", new Date()));
            ui.postDelayed(this, 10_000);
        }
    };

    @Override
    public void onStateChanged() {
        PlayerState s = PlayerState.get();
        if (s.hasTrack() && s.isPlaying()) {
            now.setText(s.trackName + "  ·  " + s.artists);
            now.setVisibility(View.VISIBLE);
        } else {
            now.setVisibility(View.GONE);
        }
    }

    /** Downloads the next picture in the background so the change is instant. */
    private void prefetch() {
        if (fetching || urls == null || !alive) return;
        fetching = true;
        final String url = PhotoAlbum.sized(urls.get(index % urls.size()), w, h);
        index++;
        try {
            io.execute(() -> {
                Bitmap b = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    InputStream in = c.getInputStream();
                    b = BitmapFactory.decodeStream(in);
                    in.close();
                } catch (Exception ignored) {
                }
                final Bitmap got = b;
                ui.post(() -> {
                    fetching = false;
                    if (!alive) return;
                    if (got == null) {
                        prefetch();         // skip pictures that fail and try the next one
                        return;
                    }
                    ready = got;
                });
            });
        } catch (Exception e) {
            fetching = false;
        }
    }

    /** Fills the screen like center-crop, but keeps the upper part of tall pictures so heads stay in frame. */
    private android.graphics.Matrix cropMatrix(Bitmap b) {
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dx = (w - b.getWidth() * scale) / 2f;
        float dy = (h - b.getHeight() * scale) * 0.25f;   // 0 = top edge, 0.5 = centre
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        return m;
    }

    private void advance() {
        if (!alive) return;
        if (ready == null) {
            ui.postDelayed(this::advance, 800);
            return;
        }
        Bitmap next = ready;
        ready = null;
        // the view that is currently hidden gets the new picture and fades in over the other one
        ImageView in = back, out = front;
        Bitmap old = out.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) out.getDrawable()).getBitmap() : null;
        in.setImageBitmap(next);
        in.setImageMatrix(cropMatrix(next));
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setAlpha(0f);
        in.bringToFront();
        View info = (View) clock.getParent();
        info.bringToFront();
        message.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new LinearInterpolator()).withEndAction(() -> {
            out.setAlpha(0f);
            out.setImageDrawable(null);
            if (old != null && old != next) old.recycle();
        }).start();
        android.animation.ObjectAnimator zx = android.animation.ObjectAnimator.ofFloat(in, View.SCALE_X, 1f, 1.08f);
        android.animation.ObjectAnimator zy = android.animation.ObjectAnimator.ofFloat(in, View.SCALE_Y, 1f, 1.08f);
        zx.setDuration(SHOW_MS + FADE_MS);
        zy.setDuration(SHOW_MS + FADE_MS);
        zx.setInterpolator(new LinearInterpolator());
        zy.setInterpolator(new LinearInterpolator());
        zx.start();
        zy.start();
        front = in;
        back = out;
        prefetch();
        ui.postDelayed(this::advance, SHOW_MS);
    }
}
