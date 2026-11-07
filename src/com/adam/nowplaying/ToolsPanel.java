package com.adam.nowplaying;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Card with the sleep timer, kitchen timers and the wake-up alarm. */
final class ToolsPanel extends FrameLayout implements Timers.Listener {

    private final Context ctx;
    private final LinearLayout body;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // alarm form state, saved on every tap
    private boolean aOn;
    private int aHour, aMinute, aRepeat, aVolume;
    private String aUri;

    ToolsPanel(Context c) {
        super(c);
        ctx = c;
        setBackgroundColor(Color.argb(150, 0, 0, 0));
        setClickable(true);
        setOnClickListener(v -> hide());
        setVisibility(GONE);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(Ui.dp(c, 24), Ui.dp(c, 18), Ui.dp(c, 24), Ui.dp(c, 14));
        card.setBackground(Ui.round(c, Ui.CARD, 22));

        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, 21, Color.WHITE, "sans-serif", 1);
        title.setText("Timers & alarm");
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = Ui.text(c, 14, Ui.GREEN, "sans-serif-medium", 1);
        close.setText("DONE");
        close.setLetterSpacing(0.1f);
        close.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8));
        close.setOnClickListener(v -> hide());
        header.addView(close);
        card.addView(header);

        ScrollView scroll = new ScrollView(c);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        int w = Math.min(Ui.dp(c, 640), getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 40));
        LayoutParams clp = new LayoutParams(w, getResources().getDisplayMetrics().heightPixels - Ui.dp(c, 24), Gravity.CENTER);
        clp.topMargin = Ui.dp(c, 16);
        clp.bottomMargin = Ui.dp(c, 16);
        addView(card, clp);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void show() {
        aOn = Timers.alarmOn(ctx);
        aHour = Timers.alarmHour(ctx);
        aMinute = Timers.alarmMinute(ctx);
        aRepeat = Timers.alarmRepeat(ctx);
        aUri = Timers.alarmUri(ctx);
        aVolume = Timers.alarmVolume(ctx);
        setVisibility(VISIBLE);
        bringToFront();
        Timers.addListener(this);
        rebuild();
        ((View) body.getParent()).scrollTo(0, 0);
    }

    void hide() {
        setVisibility(GONE);
        Timers.removeListener(this);
    }

    @Override
    public void onTimersChanged() {
        if (isShowing()) rebuild();
    }

    private TextView section(String name) {
        TextView t = Ui.text(ctx, 13, Ui.GREEN, "sans-serif-medium", 1);
        t.setText(name.toUpperCase(Locale.US));
        t.setLetterSpacing(0.12f);
        t.setPadding(0, Ui.dp(ctx, 16), 0, Ui.dp(ctx, 6));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addPill(LinearLayout row, String label, boolean selected, Runnable tap) {
        TextView p = Ui.pill(ctx, label, selected);
        p.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(ctx, 8);
        row.addView(p, lp);
    }

    private View scrollRow(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(ctx);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        return h;
    }

    private void rebuild() {
        body.removeAllViews();

        // ----- big clock -----
        body.addView(section("Clock"));
        LinearLayout clk = row();
        addPill(clk, "Show big clock", false, () -> {
            NowPlayingView.clockMode = true;
            hide();
        });
        body.addView(clk);

        // ----- sleep timer -----
        body.addView(section("Sleep timer"));
        LinearLayout sleep = row();
        boolean sleeping = Timers.sleepEndMs > 0;
        for (int m : new int[]{15, 30, 45, 60, 90}) {
            addPill(sleep, m + " min", false, () -> Timers.startSleep(m));
        }
        if (sleeping) addPill(sleep, "Cancel", true, Timers::cancelSleep);
        body.addView(scrollRow(sleep));
        if (sleeping) {
            long left = Math.max(0, (Timers.sleepEndMs - System.currentTimeMillis()) / 1000);
            TextView t = Ui.text(ctx, 14, Color.WHITE, "sans-serif", 1);
            t.setText("Music fades out and pauses in " + Ui.clock(left));
            t.setPadding(0, Ui.dp(ctx, 8), 0, 0);
            body.addView(t);
        }

        // ----- kitchen timers -----
        body.addView(section("Kitchen timer"));
        LinearLayout k = row();
        for (int m : new int[]{1, 3, 5, 10, 15, 20, 30, 45}) {
            addPill(k, m + " min", false, () -> Timers.addKitchen(m * 60));
        }
        body.addView(scrollRow(k));
        List<Timers.Kitchen> running = Timers.kitchen;
        for (Timers.Kitchen kt : running) {
            LinearLayout r = row();
            r.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
            r.setBackground(Ui.round(ctx, Color.argb(18, 255, 255, 255), 14));
            long left = Math.max(0, (kt.endMs - System.currentTimeMillis() + 999) / 1000);
            TextView time = Ui.text(ctx, 26, Color.WHITE, "sans-serif-light", 1);
            time.setText(Ui.clock(left));
            r.addView(time, new LinearLayout.LayoutParams(Ui.dp(ctx, 110), -2));
            TextView name = Ui.text(ctx, 14, Ui.DIM, "sans-serif", 1);
            name.setText(kt.label);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView x = Ui.pill(ctx, "Cancel", false);
            x.setOnClickListener(v -> Timers.cancelKitchen(kt));
            r.addView(x);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(ctx, 8);
            body.addView(r, lp);
        }

        // ----- alarm -----
        body.addView(section("Wake-up alarm"));
        LinearLayout time = row();
        time.addView(stepper(() -> save(aOn, aHour - 1, aMinute), () -> save(aOn, aHour + 1, aMinute), String.format(Locale.US, "%d", aHour % 12 == 0 ? 12 : aHour % 12)));
        TextView colon = Ui.text(ctx, 34, Color.WHITE, "sans-serif-light", 1);
        colon.setText(":");
        colon.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 4));
        time.addView(colon);
        time.addView(stepper(() -> save(aOn, aHour, aMinute - 5), () -> save(aOn, aHour, aMinute + 5), String.format(Locale.US, "%02d", aMinute)));
        TextView ampm = Ui.pill(ctx, aHour < 12 ? "AM" : "PM", false);
        ampm.setOnClickListener(v -> save(aOn, aHour + 12, aMinute));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.leftMargin = Ui.dp(ctx, 14);
        time.addView(ampm, ap);
        View grow = new View(ctx);
        time.addView(grow, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView onoff = Ui.pill(ctx, aOn ? "Alarm on" : "Alarm off", aOn);
        onoff.setOnClickListener(v -> save(!aOn, aHour, aMinute));
        time.addView(onoff);
        body.addView(time);

        LinearLayout rep = row();
        rep.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        String[] repNames = {"Once", "Every day", "Weekdays"};
        for (int i = 0; i < 3; i++) {
            final int val = i;
            addPill(rep, repNames[i], aRepeat == i, () -> {
                aRepeat = val;
                save(aOn, aHour, aMinute);
            });
        }
        body.addView(rep);

        LinearLayout music = row();
        music.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        addPill(music, "Last played", aUri.isEmpty(), () -> {
            aUri = "";
            save(aOn, aHour, aMinute);
        });
        for (Pins.Pin pin : Pins.list(ctx)) {
            addPill(music, pin.title, pin.uri.equals(aUri), () -> {
                aUri = pin.uri;
                save(aOn, aHour, aMinute);
            });
        }
        body.addView(scrollRow(music));

        LinearLayout vol = row();
        vol.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        int[] vols = {30, 45, 60};
        String[] volNames = {"Quiet", "Normal", "Loud"};
        for (int i = 0; i < 3; i++) {
            final int v = vols[i];
            addPill(vol, volNames[i], aVolume == v, () -> {
                aVolume = v;
                save(aOn, aHour, aMinute);
            });
        }
        body.addView(vol);

        TextView next = Ui.text(ctx, 13, Ui.DIM, "sans-serif", 2);
        next.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4));
        long when = Timers.nextAlarmMs(ctx);
        next.setText(when == 0 ? "Pin playlists (touch and hold one in the library) to choose them here. The volume rises slowly."
                : "Next: " + new java.text.SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(new java.util.Date(when))
                + ". The volume rises slowly from quiet.");
        body.addView(next);
    }

    private View stepper(Runnable minus, Runnable plus, String value) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView up = Ui.pill(ctx, "+", false);
        up.setOnClickListener(x -> plus.run());
        TextView num = Ui.text(ctx, 38, Color.WHITE, "sans-serif-light", 1);
        num.setText(value);
        num.setGravity(Gravity.CENTER);
        TextView down = Ui.pill(ctx, "–", false);
        down.setOnClickListener(x -> minus.run());
        v.addView(up, new LinearLayout.LayoutParams(Ui.dp(ctx, 56), -2));
        v.addView(num, new LinearLayout.LayoutParams(Ui.dp(ctx, 56), -2));
        v.addView(down, new LinearLayout.LayoutParams(Ui.dp(ctx, 56), -2));
        return v;
    }

    private void save(boolean on, int hour, int minute) {
        aOn = on;
        if (minute >= 60) { minute -= 60; }
        if (minute < 0) { minute += 60; }
        aHour = (hour + 24) % 24;
        aMinute = minute;
        Timers.saveAlarm(ctx, aOn, aHour, aMinute, aRepeat, aUri, aVolume);
    }
}
