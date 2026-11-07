package com.adam.nowplaying;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;

/** Rusty-style player: aurora clock when idle, blurred-art player while a track is loaded. */
final class NowPlayingView extends FrameLayout implements PlayerState.Listener {

    private static final int GREEN = Color.rgb(29, 185, 84);
    private static final int AMBER = Color.rgb(242, 179, 61);

    private final PlayerState state = PlayerState.get();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onOpenApp;

    private ImageView backdrop;
    private View dim;
    private Drawable aurora;
    /** Big-clock mode: shows the clock even while music plays; a tap on it goes back to the player. */
    static boolean clockMode;

    private View idlePanel, playerPanel, statusDot;
    private TextView timersLine;
    private LinearLayout presetRow;
    private String presetsShown = "";
    private TextView bigClock, dateView, idleStatus, smallClock;
    private ImageView artView;
    private TextView titleView, artistView, elapsedView, totalView, nowLabel, deviceName, topName;
    private ImageView shuffleButton, repeatButton, muteButton, deviceIcon;
    private LinearLayout deviceRow;
    private ThinBar progress, volumeBar;
    private IconButton prev, playPause, next;
    private Object backdropFor;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            ui.postDelayed(this, 500);
        }
    };

    private Runnable onDevicesTap;

    NowPlayingView(Context ctx, Runnable onOpenApp) {
        super(ctx);
        this.onOpenApp = onOpenApp;
        build();
    }

    /** Makes the "Now playing / Playing on …" line open the device picker. */
    void setOnDevicesTap(Runnable r) {
        onDevicesTap = r;
        wireDevicesTap();
        onStateChanged();
    }

    private void wireDevicesTap() {
        if (onDevicesTap != null) deviceRow.setOnClickListener(v -> onDevicesTap.run());
        else if (onOpenApp != null) deviceRow.setOnClickListener(v -> onOpenApp.run());
    }

    private ImageView toggleIcon(int res) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setColorFilter(white(220));
        return v;
    }

    private static int deviceIconFor(String type) {
        if (type == null) return R.drawable.ic_dev_generic;
        switch (type.toLowerCase(Locale.US)) {
            case "computer": return R.drawable.ic_dev_computer;
            case "smartphone": case "tablet": return R.drawable.ic_dev_phone;
            case "tv": case "stb": case "castvideo": return R.drawable.ic_dev_tv;
            case "speaker": case "avr": case "audiodongle": case "castaudio": return R.drawable.ic_dev_speaker;
            default: return R.drawable.ic_dev_generic;
        }
    }

    /** Draws a small green dot under the shuffle icon when shuffle is on, like Spotify. */
    private static Drawable withDot(Context ctx, int res, int color, float density) {
        Drawable icon = ctx.getDrawable(res).mutate();
        icon.setTint(color);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        int s = Math.round(4 * density);
        dot.setSize(s, s);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{icon, dot});
        int size = Math.round(24 * density);
        ld.setLayerSize(0, size, size);
        ld.setLayerGravity(0, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ld.setLayerSize(1, s, s);
        ld.setLayerGravity(1, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ld.setLayerInsetTop(1, size + Math.round(4 * density));
        return ld;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        state.addListener(this);
        onStateChanged();
        ui.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        state.removeListener(this);
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        removeAllViews();
        build();
        onStateChanged();
    }

    // ---------- layout ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, String family, int style, int maxLines) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, style));
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static int white(int alpha) {
        return Color.argb(alpha, 255, 255, 255);
    }

    private Drawable radial(int color, float cx, float cy, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{color, Color.TRANSPARENT});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(dp(radiusDp));
        return g;
    }

    private void build() {
        Context ctx = getContext();
        boolean landscape = getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;

        aurora = new LayerDrawable(new Drawable[]{
                new ColorDrawable(Color.rgb(12, 12, 16)),
                radial(Color.argb(150, 24, 110, 62), 0.27f, 0.30f, 420),
                radial(Color.argb(120, 92, 40, 110), 0.52f, 0.62f, 380),
                radial(Color.argb(120, 34, 60, 130), 0.95f, 0.95f, 420),
        });

        backdrop = new ImageView(ctx);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(backdrop, new LayoutParams(-1, -1));
        dim = new View(ctx);
        dim.setBackgroundColor(Color.argb(115, 0, 0, 0));
        addView(dim, new LayoutParams(-1, -1));
        backdropFor = null;

        // top bar: status dot + device name, clock on the right
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(22), dp(16), dp(22), 0);
        statusDot = new View(ctx);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(AMBER);
        statusDot.setBackground(dot);
        top.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        topName = text(15, white(235), "sans-serif-medium", Typeface.NORMAL, 1);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-2, -2);
        nameLp.leftMargin = dp(10);
        top.addView(topName, nameLp);
        TextView via = text(15, white(140), "sans-serif", Typeface.NORMAL, 1);
        via.setText("  ·  Spotify Connect");
        top.addView(via, new LinearLayout.LayoutParams(0, -2, 1f));
        smallClock = text(26, white(235), "sans-serif-light", Typeface.NORMAL, 1);
        top.addView(smallClock);
        addView(top, new LayoutParams(-1, -2, Gravity.TOP));

        // idle panel: big clock
        LinearLayout idle = new LinearLayout(ctx);
        idle.setOrientation(LinearLayout.VERTICAL);
        idle.setGravity(Gravity.CENTER);
        bigClock = text(landscape ? 112 : 96, Color.rgb(242, 238, 232), "sans-serif-light", Typeface.NORMAL, 1);
        dateView = text(15, white(170), "sans-serif-medium", Typeface.NORMAL, 1);
        dateView.setLetterSpacing(0.14f);
        idleStatus = text(15, white(150), "sans-serif", Typeface.NORMAL, 2);
        idleStatus.setGravity(Gravity.CENTER);
        bigClock.setGravity(Gravity.CENTER_HORIZONTAL);
        idle.addView(bigClock, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(14);
        idle.addView(dateView, dlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(18);
        idle.addView(idleStatus, slp);
        timersLine = text(17, white(220), "sans-serif-medium", Typeface.NORMAL, 2);
        timersLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams idleTlp = new LinearLayout.LayoutParams(-2, -2);
        idleTlp.topMargin = dp(12);
        idle.addView(timersLine, idleTlp);
        presetRow = new LinearLayout(ctx);
        presetRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams idlePlp = new LinearLayout.LayoutParams(-2, -2);
        idlePlp.topMargin = dp(18);
        idle.addView(presetRow, idlePlp);
        idle.setOnClickListener(v -> {
            if (clockMode) {
                clockMode = false;
                onStateChanged();
            }
        });
        idlePanel = idle;
        addView(idle, new LayoutParams(-1, -1));

        // player panel
        LinearLayout player = new LinearLayout(ctx);
        player.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        player.setGravity(landscape ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        int pad = dp(22);
        player.setPadding(pad, dp(landscape ? 62 : 70), pad, pad);

        int artSize = landscape
                ? Math.min(h - dp(62) - pad, (int) (w * 0.36f))
                : Math.min(w - 2 * pad, (int) (h * 0.40f));
        artView = new ImageView(ctx);
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(white(30));
        artBg.setCornerRadius(dp(14));
        artView.setBackground(artBg);
        artView.setClipToOutline(true);
        artView.setElevation(dp(10));
        player.addView(artView, new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoLp = landscape
                ? new LinearLayout.LayoutParams(0, -1, 1f)
                : new LinearLayout.LayoutParams(-1, 0, 1f);
        if (landscape) infoLp.leftMargin = dp(40);
        else infoLp.topMargin = dp(22);
        player.addView(info, infoLp);

        nowLabel = text(12, white(150), "sans-serif-medium", Typeface.NORMAL, 1);
        nowLabel.setLetterSpacing(0.2f);
        nowLabel.setText("NOW PLAYING");
        info.addView(nowLabel);

        titleView = text(landscape ? 36 : 30, Color.rgb(242, 238, 232), "sans-serif", Typeface.NORMAL, 2);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(12);
        info.addView(titleView, tlp);

        artistView = text(19, white(175), "sans-serif", Typeface.NORMAL, 1);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(10);
        info.addView(artistView, alp);

        progress = new ThinBar(ctx, dp(4));
        progress.setPadding(0, 0, dp(4), 0);
        progress.setOnSeek(new ThinBar.OnSeek() {
            @Override public void onDragging(float f) {
                if (state.durationMs > 0) elapsedView.setText(fmt((long) (state.durationMs * f)));
            }
            @Override public void onSeek(float f) {
                if (state.durationMs <= 0) return;
                long target = (long) (state.durationMs * f);
                state.setOptimisticPosition(target);
                PlayerService.command(getContext(), "seek", "position_ms", target);
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(28));
        plp.topMargin = dp(16);
        info.addView(progress, plp);

        LinearLayout times = new LinearLayout(ctx);
        elapsedView = text(13, white(160), "sans-serif", Typeface.NORMAL, 1);
        totalView = text(13, white(160), "sans-serif", Typeface.NORMAL, 1);
        totalView.setGravity(Gravity.END);
        times.addView(elapsedView, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(totalView, new LinearLayout.LayoutParams(0, -2, 1f));
        info.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(ctx);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        prev = new IconButton(ctx, IconButton.PREV, false);
        playPause = new IconButton(ctx, IconButton.PLAY, true);
        next = new IconButton(ctx, IconButton.NEXT, false);
        prev.setOnClickListener(v -> PlayerService.command(getContext(), "skip_prev"));
        next.setOnClickListener(v -> PlayerService.command(getContext(), "skip_next"));
        playPause.setOnClickListener(v -> PlayerService.togglePlay(getContext()));
        shuffleButton = toggleIcon(R.drawable.ic_shuffle);
        shuffleButton.setOnClickListener(v -> PlayerService.toggleShuffle(getContext()));
        repeatButton = toggleIcon(R.drawable.ic_repeat);
        repeatButton.setOnClickListener(v -> PlayerService.cycleRepeat(getContext()));
        controls.addView(shuffleButton, new LinearLayout.LayoutParams(dp(46), dp(52)));
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        prevLp.leftMargin = dp(6);
        controls.addView(prev, prevLp);
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        bigLp.leftMargin = dp(14);
        bigLp.rightMargin = dp(14);
        controls.addView(playPause, bigLp);
        controls.addView(next, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout.LayoutParams repLp = new LinearLayout.LayoutParams(dp(46), dp(52));
        repLp.leftMargin = dp(6);
        controls.addView(repeatButton, repLp);
        controls.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        muteButton = new ImageView(ctx);
        muteButton.setImageResource(R.drawable.ic_volume);
        muteButton.setScaleType(ImageView.ScaleType.CENTER);
        muteButton.setColorFilter(white(200));
        muteButton.setOnClickListener(v -> PlayerService.toggleMute(getContext()));
        controls.addView(muteButton, new LinearLayout.LayoutParams(dp(40), dp(44)));
        volumeBar = new ThinBar(ctx, dp(3));
        volumeBar.setPadding(dp(10), 0, dp(4), 0);
        volumeBar.setOnSeek(new ThinBar.OnSeek() {
            @Override public void onDragging(float f) { }
            @Override public void onSeek(float f) {
                int v = Math.round(f * 100);
                state.setOptimisticVolume(v);
                PlayerService.command(getContext(), "set_volume", "volume", v);
            }
        });
        controls.addView(volumeBar, new LinearLayout.LayoutParams(dp(landscape ? 120 : 150), dp(36)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(6);
        info.addView(controls, clp);

        // Spotify-style device line: device icon + name, green when playing elsewhere
        deviceRow = new LinearLayout(ctx);
        deviceRow.setGravity(Gravity.CENTER_VERTICAL);
        deviceRow.setPadding(0, dp(6), dp(12), dp(6));
        deviceIcon = new ImageView(ctx);
        deviceIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        deviceRow.addView(deviceIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        deviceName = text(15, GREEN, "sans-serif-medium", Typeface.NORMAL, 1);
        LinearLayout.LayoutParams dnlp = new LinearLayout.LayoutParams(-2, -2);
        dnlp.leftMargin = dp(10);
        deviceRow.addView(deviceName, dnlp);
        LinearLayout deviceLine = new LinearLayout(ctx);
        deviceLine.setGravity(Gravity.CENTER_VERTICAL);
        deviceLine.addView(deviceRow, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(-1, -2);
        dlp2.topMargin = dp(8);
        info.addView(deviceLine, dlp2);

        playerPanel = player;
        addView(player, new LayoutParams(-1, -1));
        wireDevicesTap();

        if (onOpenApp != null) {
            View.OnClickListener open = v -> onOpenApp.run();
            setOnClickListener(open);
            idle.setOnClickListener(open);
            player.setOnClickListener(open);
            info.setOnClickListener(open);
            artView.setOnClickListener(open);
            titleView.setOnClickListener(open);
            artistView.setOnClickListener(open);
            controls.setOnClickListener(open);
            top.setOnClickListener(open);
        }
    }

    // ---------- state ----------

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private void tick() {
        Date now = new Date();
        String time = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", now).toString();
        bigClock.setText(time);
        smallClock.setText(time);
        updateTimersLine();
        applyPanels();
        dateView.setText(DateFormat.format("EEEE  ·  MMMM d", now).toString().toUpperCase(Locale.getDefault()));
        if (state.durationMs > 0 && !progress.isDragging()) {
            long pos = state.currentPosition();
            progress.setFraction(pos / (float) state.durationMs);
            elapsedView.setText(fmt(pos));
        }
    }

    private void applyPanels() {
        boolean track = state.connected && state.hasTrack() && !clockMode;
        int idleVis = track ? GONE : VISIBLE;
        if (idlePanel.getVisibility() != idleVis) {
            idlePanel.setVisibility(idleVis);
            playerPanel.setVisibility(track ? VISIBLE : GONE);
            smallClock.setVisibility(track ? VISIBLE : INVISIBLE);
            if (!track) refreshPresets();
        }
    }

    private void updateTimersLine() {
        if (idlePanel.getVisibility() != VISIBLE) return;
        Context c = getContext();
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Timers.Kitchen k : Timers.kitchen) {
            if (sb.length() > 0) sb.append("     ");
            sb.append("Timer ").append(Ui.clock(Math.max(0, (k.endMs - now + 999) / 1000)));
        }
        if (Timers.sleepEndMs > 0) {
            if (sb.length() > 0) sb.append("     ");
            sb.append("Sleep ").append(Ui.clock(Math.max(0, (Timers.sleepEndMs - now) / 1000)));
        }
        long alarm = Timers.nextAlarmMs(c);
        if (alarm > 0) {
            if (sb.length() > 0) sb.append("     ");
            sb.append("Alarm ").append(DateFormat.format(DateFormat.is24HourFormat(c) ? "EEE H:mm" : "EEE h:mm a", alarm));
        }
        timersLine.setText(sb);
        timersLine.setVisibility(sb.length() == 0 ? GONE : VISIBLE);
    }

    /** One-tap buttons for pinned playlists, shown on the idle clock when nothing is playing. */
    private void refreshPresets() {
        Context c = getContext();
        java.util.List<Pins.Pin> pins = Pins.list(c);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < pins.size() && i < 4; i++) key.append(pins.get(i).uri);
        boolean show = !clockMode && !state.hasTrack() && state.connected && state.loggedIn && SpotifyWebApi.isSignedIn(c);
        if (!show) key.setLength(0);
        if (key.toString().equals(presetsShown) && presetRow.getChildCount() > 0 == (key.length() > 0)) return;
        presetsShown = key.toString();
        presetRow.removeAllViews();
        if (key.length() == 0) return;
        for (int i = 0; i < pins.size() && i < 4; i++) {
            final Pins.Pin pin = pins.get(i);
            TextView pill = Ui.pill(c, pin.title, false);
            pill.setMaxWidth(dp(200));
            pill.setOnClickListener(v -> SpotifyWebApi.playOnEcho(c, "{\"context_uri\":\"" + pin.uri + "\"}", (ok, err) -> { }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(5);
            lp.rightMargin = dp(5);
            presetRow.addView(pill, lp);
        }
    }

    @Override
    public void onStateChanged() {
        boolean track = state.connected && state.hasTrack() && !clockMode;
        idlePanel.setVisibility(track ? GONE : VISIBLE);
        playerPanel.setVisibility(track ? VISIBLE : GONE);
        smallClock.setVisibility(track ? VISIBLE : INVISIBLE);
        if (!track) refreshPresets();

        topName.setText(state.deviceName);
        boolean ready = state.connected && state.loggedIn;
        ((GradientDrawable) statusDot.getBackground()).setColor(ready ? GREEN : AMBER);
        if (!state.connected) idleStatus.setText("Starting the speaker…");
        else if (!state.loggedIn) idleStatus.setText("Waiting for Spotify");
        else if (state.hasTrack()) idleStatus.setText(state.trackName + "  ·  " + state.artists);
        else idleStatus.setText("Ready for Spotify");

        if (track) {
            titleView.setText(state.trackName);
            artistView.setText(state.artists);
            nowLabel.setText("NOW PLAYING");
            float density = getResources().getDisplayMetrics().density;
            if (state.active) {
                deviceIcon.setImageResource(R.drawable.ic_dev_speaker);
                deviceIcon.setColorFilter(white(170));
                deviceName.setTextColor(white(170));
                deviceName.setText(state.deviceName);
            } else {
                deviceIcon.setImageResource(deviceIconFor(state.otherDeviceType));
                deviceIcon.setColorFilter(GREEN);
                deviceName.setTextColor(GREEN);
                deviceName.setText(state.otherDeviceName != null ? state.otherDeviceName : "Another device");
            }
            shuffleButton.clearColorFilter();
            if (state.shuffle) {
                shuffleButton.setImageDrawable(withDot(getContext(), R.drawable.ic_shuffle, GREEN, density));
            } else {
                shuffleButton.setImageResource(R.drawable.ic_shuffle);
                shuffleButton.setColorFilter(white(220));
            }
            boolean rep = !"off".equals(state.repeat);
            repeatButton.clearColorFilter();
            if (rep) {
                repeatButton.setImageDrawable(withDot(getContext(),
                        "track".equals(state.repeat) ? R.drawable.ic_repeat_one : R.drawable.ic_repeat, GREEN, density));
            } else {
                repeatButton.setImageResource(R.drawable.ic_repeat);
                repeatButton.setColorFilter(white(220));
            }
            muteButton.setImageResource(state.volume == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume);
            totalView.setText(state.durationMs > 0 ? fmt(state.durationMs) : "");
            progress.setEnabled(state.durationMs > 0);
            playPause.setIcon(state.isPlaying() ? IconButton.PAUSE : IconButton.PLAY);
            int c = state.artColor;
            int disc = Color.argb(150,
                    (int) (Color.red(c) * 0.55f + 255 * 0.45f),
                    (int) (Color.green(c) * 0.55f + 255 * 0.45f),
                    (int) (Color.blue(c) * 0.55f + 255 * 0.45f));
            playPause.setDiscColor(disc);
            volumeBar.setEnabled(state.volume >= 0);
            if (state.volume >= 0) volumeBar.setFraction(state.volume / 100f);
        }

        Object want = track && state.art != null ? state.art : aurora;
        if (want != backdropFor) {
            backdropFor = want;
            if (want == aurora) {
                backdrop.setImageDrawable(null);
                backdrop.setBackground(aurora);
                artView.setImageBitmap(null);
                dim.setVisibility(GONE);
            } else {
                backdrop.setBackground(null);
                backdrop.setImageBitmap(state.artBlur);
                artView.setImageBitmap(state.art);
                dim.setVisibility(VISIBLE);
            }
        }
        tick();
    }
}
