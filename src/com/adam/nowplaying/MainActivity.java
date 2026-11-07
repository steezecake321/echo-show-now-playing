package com.adam.nowplaying;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements PlayerState.Listener, Timers.Listener {

    private DevicesPanel devices;
    private LibraryPanel library;
    private ToolsPanel tools;
    private RoundIcon playHere, heart, libraryBtn, toolsBtn;
    private TextView chip;
    private FrameLayout ringBox;
    private TextView ringText;
    private String likeTrackUri;
    private boolean liked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        FrameLayout root = new FrameLayout(this);
        NowPlayingView player = new NowPlayingView(this, null);
        player.setOnDevicesTap(() -> devices.show());
        root.addView(player, new FrameLayout.LayoutParams(-1, -1));

        int size = dp(44);
        View home = new RoundIcon(this, RoundIcon.GRID);
        home.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(20);
        lp.bottomMargin = dp(18);
        root.addView(home, lp);

        playHere = new RoundIcon(this, RoundIcon.ECHO);
        playHere.setContentDescription("Play on this Echo");
        playHere.setOnClickListener(v -> {
            if (PlayerState.get().active) devices.show();
            else PlayerService.command(this, "activate");
        });
        toolsBtn = new RoundIcon(this, RoundIcon.CLOCK);
        toolsBtn.setContentDescription("Timers and alarm");
        toolsBtn.setOnClickListener(v -> tools.show());
        libraryBtn = new RoundIcon(this, RoundIcon.LIBRARY);
        libraryBtn.setContentDescription("Library and search");
        libraryBtn.setOnClickListener(v -> library.show());
        heart = new RoundIcon(this, RoundIcon.HEART);
        heart.setContentDescription("Like this song");
        heart.setOnClickListener(v -> toggleLike());
        View[] buttons = {playHere, toolsBtn, libraryBtn, heart};
        for (int i = 0; i < buttons.length; i++) {
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
            blp.rightMargin = dp(20) + (size + dp(12)) * (i + 1);
            blp.bottomMargin = dp(18);
            root.addView(buttons[i], blp);
        }

        chip = new TextView(this);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        chip.setPadding(dp(14), dp(6), dp(14), dp(6));
        chip.setBackground(Ui.round(this, Color.argb(120, 0, 0, 0), 18));
        chip.setVisibility(View.GONE);
        chip.setOnClickListener(v -> tools.show());
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        chipLp.topMargin = dp(14);
        root.addView(chip, chipLp);

        devices = new DevicesPanel(this);
        root.addView(devices, new FrameLayout.LayoutParams(-1, -1));
        tools = new ToolsPanel(this);
        root.addView(tools, new FrameLayout.LayoutParams(-1, -1));
        library = new LibraryPanel(this);
        root.addView(library, new FrameLayout.LayoutParams(-1, -1));

        ringBox = new FrameLayout(this);
        ringBox.setBackgroundColor(Color.argb(235, 18, 18, 20));
        ringBox.setClickable(true);
        ringBox.setVisibility(View.GONE);
        android.widget.LinearLayout ringCol = new android.widget.LinearLayout(this);
        ringCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        ringCol.setGravity(Gravity.CENTER);
        ringText = Ui.text(this, 34, Color.WHITE, "sans-serif-light", 2);
        ringText.setGravity(Gravity.CENTER);
        ringCol.addView(ringText);
        TextView stop = Ui.pill(this, "Stop", true);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stop.setPadding(dp(40), dp(12), dp(40), dp(12));
        stop.setOnClickListener(v -> Timers.dismissRing());
        android.widget.LinearLayout.LayoutParams stopLp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        stopLp.topMargin = dp(24);
        ringCol.addView(stop, stopLp);
        ringBox.addView(ringCol, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ringBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        Timers.init(this);
        Timers.scheduleAlarm(this);
        PlayerService.start(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("wake", false)) {
            if (android.os.Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        String clientId = intent.getStringExtra("client_id");
        if (clientId != null && !clientId.trim().isEmpty()) {
            SpotifyWebApi.setClientId(this, clientId);
            SpotifyWebApi.startLogin(this);
            return;
        }
        if (intent.getBooleanExtra("show_devices", false)) devices.show();
    }

    @Override
    public void onBackPressed() {
        if (library.isShowing()) library.back();
        else if (tools.isShowing()) tools.hide();
        else if (devices.isShowing()) devices.hide();
        else super.onBackPressed();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.activityVisible = true;
        PlayerState.get().addListener(this);
        Timers.addListener(this);
        onStateChanged();
        onTimersChanged();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PlayerService.activityVisible = false;
        PlayerState.get().removeListener(this);
        Timers.removeListener(this);
    }

    @Override
    public void onTimersChanged() {
        ringBox.setVisibility(Timers.ringingLabel != null ? View.VISIBLE : View.GONE);
        if (Timers.ringingLabel != null) ringText.setText("Time\u2019s up\n" + Timers.ringingLabel);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Timers.Kitchen k : Timers.kitchen) {
            if (sb.length() > 0) sb.append("    ");
            sb.append("Timer ").append(Ui.clock(Math.max(0, (k.endMs - now + 999) / 1000)));
        }
        if (Timers.sleepEndMs > 0) {
            if (sb.length() > 0) sb.append("    ");
            sb.append("Sleep ").append(Ui.clock(Math.max(0, (Timers.sleepEndMs - now) / 1000)));
        }
        chip.setText(sb);
        chip.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void toggleLike() {
        final String uri = PlayerState.get().trackUri;
        if (uri == null || !SpotifyWebApi.hasLibraryAccess(this)) {
            if (!SpotifyWebApi.hasLibraryAccess(this)) library.show();
            return;
        }
        final String id = uri.substring(uri.lastIndexOf(':') + 1);
        final boolean want = !liked;
        liked = want;
        heart.setActive(want);
        final String method = want ? "PUT" : "DELETE";
        // Spotify's newer library endpoint first, the older per-track one as a fallback
        SpotifyWebApi.send(this, method, "/me/library?uris=" + SpotifyWebApi.urlEncode(uri), null, (ok, err) -> {
            if (ok) return;
            SpotifyWebApi.send(this, method, "/me/tracks?ids=" + id, null, (ok2, err2) -> {
                if (!ok2) {
                    liked = !want;
                    heart.setActive(liked);
                }
            });
        });
    }

    private void refreshLiked() {
        final String uri = PlayerState.get().trackUri;
        if (uri == null || uri.equals(likeTrackUri) || !uri.startsWith("spotify:track:")
                || !SpotifyWebApi.hasLibraryAccess(this)) return;
        likeTrackUri = uri;
        liked = false;
        heart.setActive(false);
        final String id = uri.substring(uri.lastIndexOf(':') + 1);
        SpotifyWebApi.getJson(this, "/me/library/contains?uris=" + SpotifyWebApi.urlEncode(uri), (json, err) -> {
            if (json != null) {
                applyLiked(uri, json);
                return;
            }
            SpotifyWebApi.getJson(this, "/me/tracks/contains?ids=" + id, (json2, err2) -> {
                if (json2 == null) android.util.Log.w("NowPlaying", "liked check failed: " + err + " / " + err2);
                else applyLiked(uri, json2);
            });
        });
    }

    private void applyLiked(String uri, org.json.JSONObject json) {
        if (!uri.equals(likeTrackUri)) return;
        org.json.JSONArray a = json.optJSONArray("_array");
        liked = a != null && a.optBoolean(0);
        heart.setActive(liked);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onStateChanged() {
        PlayerState s = PlayerState.get();
        playHere.setVisibility(s.connected && s.loggedIn ? View.VISIBLE : View.GONE);
        boolean web = SpotifyWebApi.hasLibraryAccess(this);
        heart.setVisibility(web && s.hasTrack() ? View.VISIBLE : View.GONE);
        libraryBtn.setVisibility(s.connected && s.loggedIn ? View.VISIBLE : View.GONE);
        refreshLiked();
        playHere.setActive(s.active && s.hasTrack());
        if (s.isPlaying()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Rusty-style small round button: app grid (home) or the Echo Show outline (play here). */
    private static final class RoundIcon extends View {
        static final int GRID = 0, ECHO = 1, HEART = 2, LIBRARY = 3, CLOCK = 4;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        private final android.graphics.drawable.Drawable glyph;

        RoundIcon(android.content.Context c, int kind) {
            super(c);
            this.kind = kind;
            glyph = kind == ECHO ? c.getDrawable(R.drawable.ic_echo_show).mutate() : null;
            bg.setColor(Color.argb(70, 0, 0, 0));
            fg.setColor(Color.WHITE);
            setClickable(true);
        }

        private boolean active;

        /** Green while this Echo is the playing device. */
        void setActive(boolean on) {
            if (active == on) return;
            active = on;
            if (glyph != null) {
                glyph.setTint(on ? Color.rgb(29, 185, 84) : Color.WHITE);
                setContentDescription(on ? "Choose a device" : "Play on this Echo");
            }
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            bg.setAlpha(isPressed() ? 140 : 70);
            c.drawCircle(cx, cy, Math.min(w, h) / 2, bg);
            if (kind == GRID) {
                fg.setStyle(Paint.Style.FILL);
                float step = w * 0.15f, r = w * 0.045f;
                for (int i = -1; i <= 1; i++)
                    for (int j = -1; j <= 1; j++)
                        c.drawRect(cx + i * step - r, cy + j * step - r, cx + i * step + r, cy + j * step + r, fg);
            } else if (kind == HEART) {
                int green = Color.rgb(29, 185, 84);
                fg.setColor(active ? green : Color.WHITE);
                fg.setStyle(active ? Paint.Style.FILL : Paint.Style.STROKE);
                fg.setStrokeWidth(w * 0.055f);
                float u = w * 0.0125f;
                Path hp = new Path();
                hp.moveTo(cx, cy + 14 * u);
                hp.cubicTo(cx - 24 * u, cy - 2 * u, cx - 14 * u, cy - 18 * u, cx, cy - 6 * u);
                hp.cubicTo(cx + 14 * u, cy - 18 * u, cx + 24 * u, cy - 2 * u, cx, cy + 14 * u);
                c.drawPath(hp, fg);
                fg.setColor(Color.WHITE);
                fg.setStyle(Paint.Style.FILL);
            } else if (kind == LIBRARY) {
                fg.setStyle(Paint.Style.STROKE);
                fg.setStrokeWidth(w * 0.06f);
                fg.setStrokeCap(Paint.Cap.ROUND);
                float x0 = cx - w * 0.2f, x1 = cx + w * 0.2f;
                for (int i = -1; i <= 1; i++) c.drawLine(x0, cy + i * w * 0.12f, x1, cy + i * w * 0.12f, fg);
                fg.setStyle(Paint.Style.FILL);
            } else if (kind == CLOCK) {
                fg.setStyle(Paint.Style.STROKE);
                fg.setStrokeWidth(w * 0.055f);
                fg.setStrokeCap(Paint.Cap.ROUND);
                c.drawCircle(cx, cy, w * 0.22f, fg);
                c.drawLine(cx, cy, cx, cy - w * 0.13f, fg);
                c.drawLine(cx, cy, cx + w * 0.09f, cy + w * 0.04f, fg);
                fg.setStyle(Paint.Style.FILL);
            } else if (glyph != null) {
                int half = Math.round(w * 0.3f);
                glyph.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                glyph.draw(c);
            }
        }
    }
}
