package com.adam.nowplaying;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements PlayerState.Listener {

    private DevicesPanel devices;
    private RoundIcon playHere;

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
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        plp.rightMargin = dp(20) + size + dp(12);
        plp.bottomMargin = dp(18);
        root.addView(playHere, plp);

        devices = new DevicesPanel(this);
        root.addView(devices, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
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
        if (devices.isShowing()) devices.hide();
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
        onStateChanged();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PlayerService.activityVisible = false;
        PlayerState.get().removeListener(this);
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
        static final int GRID = 0, ECHO = 1;
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
            if (active == on || glyph == null) return;
            active = on;
            glyph.setTint(on ? Color.rgb(29, 185, 84) : Color.WHITE);
            setContentDescription(on ? "Choose a device" : "Play on this Echo");
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
            } else if (glyph != null) {
                int half = Math.round(w * 0.3f);
                glyph.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                glyph.draw(c);
            }
        }
    }
}
