package com.adam.nowplaying;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Rusty-style card listing Spotify Connect devices; tap one to move playback there. */
final class DevicesPanel extends FrameLayout {

    private static final int GREEN = Color.rgb(29, 185, 84);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout list;
    private final TextView message;
    private final TextView action;
    private Runnable actionRun;
    private boolean busy;

    DevicesPanel(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.argb(150, 0, 0, 0));
        setClickable(true);
        setOnClickListener(v -> hide());
        setVisibility(GONE);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(24), dp(20), dp(24), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(28, 27, 31));
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(21, Color.WHITE, "sans-serif", 1);
        title.setText("Play on");
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView refresh = text(14, GREEN, "sans-serif-medium", 1);
        refresh.setText("REFRESH");
        refresh.setLetterSpacing(0.1f);
        refresh.setPadding(dp(12), dp(8), dp(4), dp(8));
        refresh.setOnClickListener(v -> load());
        header.addView(refresh);
        card.addView(header);

        message = text(15, Color.argb(190, 255, 255, 255), "sans-serif", 3);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(14);
        card.addView(message, mlp);

        action = text(15, Color.WHITE, "sans-serif-medium", 1);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(GREEN);
        pill.setCornerRadius(dp(22));
        action.setBackground(pill);
        action.setOnClickListener(v -> {
            if (actionRun != null) actionRun.run();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = dp(14);
        card.addView(action, alp);

        ScrollView scroll = new ScrollView(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(8);
        card.addView(scroll, slp);

        int w = Math.min(dp(560), getResources().getDisplayMetrics().widthPixels - dp(40));
        LayoutParams clp = new LayoutParams(w, -2, Gravity.CENTER);
        clp.topMargin = dp(20);
        clp.bottomMargin = dp(20);
        addView(card, clp);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, String family, int lines) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void show() {
        setVisibility(VISIBLE);
        bringToFront();
        load();
    }

    void hide() {
        setVisibility(GONE);
    }

    private void setMessage(String msg, String actionLabel, Runnable run) {
        message.setText(msg);
        message.setVisibility(msg == null ? GONE : VISIBLE);
        action.setText(actionLabel);
        action.setVisibility(actionLabel == null ? GONE : VISIBLE);
        actionRun = run;
    }

    void load() {
        Context ctx = getContext();
        list.removeAllViews();
        if (SpotifyWebApi.clientId(ctx) == null) {
            setMessage("This speaker isn't linked to a Spotify developer app yet.", null, null);
            return;
        }
        if (!SpotifyWebApi.isSignedIn(ctx)) {
            setMessage("Sign in to Spotify once to switch speakers from here. The sign-in page opens in Firefox.",
                    "Sign in to Spotify", () -> SpotifyWebApi.startLogin(ctx));
            return;
        }
        setMessage("Finding your speakers…", null, null);
        SpotifyWebApi.getDevices(ctx, (devices, error) -> {
            if (!isShowing()) return;
            if ("signin".equals(error)) {
                setMessage("Spotify signed this speaker out. Sign in again to switch speakers.",
                        "Sign in to Spotify", () -> SpotifyWebApi.startLogin(ctx));
            } else if (error != null) {
                setMessage(error, "Try again", this::load);
            } else if (devices.isEmpty()) {
                setMessage("No Spotify devices are online right now. Open Spotify on a phone, PC or TV and refresh.",
                        "Refresh", this::load);
            } else {
                setMessage(null, null, null);
                showDevices(devices);
            }
        });
    }

    private void showDevices(List<SpotifyWebApi.Device> devices) {
        list.removeAllViews();
        for (SpotifyWebApi.Device d : devices) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable rbg = new GradientDrawable();
            rbg.setCornerRadius(dp(14));
            rbg.setColor(d.active ? Color.argb(40, 29, 185, 84) : Color.argb(18, 255, 255, 255));
            row.setBackground(rbg);

            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(18, d.active ? GREEN : Color.WHITE, "sans-serif", 1);
            name.setText(d.name);
            TextView type = text(13, Color.argb(150, 255, 255, 255), "sans-serif", 1);
            type.setText(typeLabel(d.type) + (d.restricted ? "  ·  can't be controlled" : ""));
            texts.addView(name);
            texts.addView(type);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            if (d.active) {
                TextView tag = text(12, GREEN, "sans-serif-medium", 1);
                tag.setText("PLAYING NOW");
                tag.setLetterSpacing(0.12f);
                row.addView(tag);
            } else if (!d.restricted) {
                row.setOnClickListener(v -> pick(d, row));
            } else {
                row.setAlpha(0.5f);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            list.addView(row, lp);
        }
    }

    private void pick(SpotifyWebApi.Device d, View row) {
        if (busy) return;
        busy = true;
        row.setAlpha(0.6f);
        boolean play = PlayerState.get().isPlaying() || PlayerState.get().hasTrack();
        SpotifyWebApi.transfer(getContext(), d.id, play, (ok, error) -> {
            busy = false;
            if (!isShowing()) return;
            if (ok) {
                ui.postDelayed(this::load, 1500);
                ui.postDelayed(this::hide, 2200);
            } else if ("signin".equals(error)) {
                load();
            } else {
                row.setAlpha(1f);
                setMessage(error, "Try again", this::load);
            }
        });
    }

    private static String typeLabel(String type) {
        if (type == null || type.isEmpty()) return "Device";
        String t = type.toLowerCase(Locale.US);
        switch (t) {
            case "computer": return "Computer";
            case "smartphone": return "Phone";
            case "tablet": return "Tablet";
            case "speaker": return "Speaker";
            case "tv": return "TV";
            case "avr": return "Receiver";
            case "stb": return "Set-top box";
            case "audiodongle": return "Audio dongle";
            case "gameconsole": return "Game console";
            case "castvideo": case "castaudio": return "Chromecast";
            case "automobile": return "Car";
            default: return type.substring(0, 1).toUpperCase(Locale.US) + type.substring(1).toLowerCase(Locale.US);
        }
    }
}
