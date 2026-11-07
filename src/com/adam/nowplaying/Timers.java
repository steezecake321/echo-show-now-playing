package com.adam.nowplaying;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Sleep timer, kitchen timers and the wake-up alarm. Lives in the app process, which the foreground service keeps alive. */
final class Timers {

    interface Listener {
        void onTimersChanged();
    }

    static final class Kitchen {
        long endMs;
        int seconds;
        String label;
    }

    static final int REPEAT_ONCE = 0, REPEAT_DAILY = 1, REPEAT_WEEKDAYS = 2;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    static final List<Kitchen> kitchen = new ArrayList<>();
    static long sleepEndMs;
    static String ringingLabel;          // non-null while a kitchen timer is ringing
    private static MediaPlayer ringer;
    private static int sleepRestoreVolume = -1;
    private static boolean ticking;
    private static Context app;

    private Timers() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        for (Listener l : listeners) l.onTimersChanged();
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            long now = System.currentTimeMillis();
            for (int i = kitchen.size() - 1; i >= 0; i--) {
                if (kitchen.get(i).endMs <= now) {
                    Kitchen k = kitchen.remove(i);
                    ring(k.label);
                }
            }
            if (sleepEndMs > 0) sleepTick(now);
            changed();
            schedule();
        }
    };

    private static void schedule() {
        if (!ticking && (sleepEndMs > 0 || !kitchen.isEmpty())) {
            ticking = true;
            main.postDelayed(tick, 1000);
        }
    }

    // ---------- sleep timer ----------

    static void startSleep(int minutes) {
        sleepEndMs = System.currentTimeMillis() + minutes * 60_000L;
        sleepRestoreVolume = -1;
        changed();
        schedule();
    }

    static void cancelSleep() {
        if (sleepRestoreVolume >= 0 && app != null) PlayerService.command(app, "set_volume", "volume", sleepRestoreVolume);
        sleepEndMs = 0;
        sleepRestoreVolume = -1;
        changed();
    }

    private static void sleepTick(long now) {
        long left = sleepEndMs - now;
        if (app == null) return;
        if (left > 20_000) return;
        if (sleepRestoreVolume < 0) {
            int v = PlayerState.get().volume;
            sleepRestoreVolume = v > 0 ? v : 40;
        }
        if (left > 0) {
            int vol = Math.max(1, Math.round(sleepRestoreVolume * (left / 20000f)));
            PlayerService.command(app, "set_volume", "volume", vol);
        } else {
            PlayerService.command(app, "pause");
            final int restore = sleepRestoreVolume;
            main.postDelayed(() -> PlayerService.command(app, "set_volume", "volume", restore), 2500);
            sleepEndMs = 0;
            sleepRestoreVolume = -1;
        }
    }

    // ---------- kitchen timers ----------

    static void addKitchen(int seconds) {
        if (kitchen.size() >= 4) return;
        Kitchen k = new Kitchen();
        k.seconds = seconds;
        k.endMs = System.currentTimeMillis() + seconds * 1000L;
        k.label = seconds % 60 == 0 ? (seconds / 60) + " min timer" : seconds + " sec timer";
        kitchen.add(k);
        changed();
        schedule();
    }

    static void cancelKitchen(Kitchen k) {
        kitchen.remove(k);
        changed();
    }

    private static void ring(String label) {
        ringingLabel = label;
        if (app == null) return;
        stopRinger();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ringer = new MediaPlayer();
            ringer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ringer.setDataSource(app, uri);
            ringer.setLooping(true);
            ringer.prepare();
            ringer.start();
        } catch (Exception e) {
            ringer = null;
        }
        main.postDelayed(autoStop, 90_000);
        app.startActivity(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("wake", true));
    }

    private static final Runnable autoStop = Timers::dismissRing;

    static void dismissRing() {
        main.removeCallbacks(autoStop);
        ringingLabel = null;
        stopRinger();
        changed();
    }

    private static void stopRinger() {
        if (ringer != null) {
            try {
                ringer.stop();
                ringer.release();
            } catch (Exception ignored) {
            }
            ringer = null;
        }
    }

    // ---------- wake-up alarm ----------

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("alarm", Context.MODE_PRIVATE);
    }

    static boolean alarmOn(Context c) { return prefs(c).getBoolean("on", false); }
    static int alarmHour(Context c) { return prefs(c).getInt("hour", 7); }
    static int alarmMinute(Context c) { return prefs(c).getInt("minute", 30); }
    static int alarmRepeat(Context c) { return prefs(c).getInt("repeat", REPEAT_WEEKDAYS); }
    static String alarmUri(Context c) { return prefs(c).getString("uri", ""); }
    static int alarmVolume(Context c) { return prefs(c).getInt("volume", 45); }

    static void saveAlarm(Context c, boolean on, int hour, int minute, int repeat, String uri, int volume) {
        prefs(c).edit().putBoolean("on", on).putInt("hour", (hour + 24) % 24).putInt("minute", (minute + 60) % 60)
                .putInt("repeat", repeat).putString("uri", uri).putInt("volume", volume).apply();
        scheduleAlarm(c);
        changed();
    }

    /** Next time the alarm should fire, or 0 when it is off. */
    static long nextAlarmMs(Context c) {
        if (!alarmOn(c)) return 0;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, alarmHour(c));
        cal.set(Calendar.MINUTE, alarmMinute(c));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 9; i++) {
            if (cal.getTimeInMillis() > now) {
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
                if (alarmRepeat(c) != REPEAT_WEEKDAYS || !weekend) return cal.getTimeInMillis();
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return 0;
    }

    static void scheduleAlarm(Context c) {
        Context a = c.getApplicationContext();
        AlarmManager am = (AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
        PendingIntent fire = PendingIntent.getBroadcast(a, 7, new Intent(a, AlarmReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(fire);
        long when = nextAlarmMs(a);
        if (when == 0) return;
        PendingIntent show = PendingIntent.getActivity(a, 8, new Intent(a, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), fire);
    }

    /** Called by AlarmReceiver when the alarm goes off. */
    static void fireAlarm(Context c) {
        final Context a = c.getApplicationContext();
        if (alarmRepeat(a) == REPEAT_ONCE) prefs(a).edit().putBoolean("on", false).apply();
        scheduleAlarm(a);
        changed();
        a.startActivity(new Intent(a, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("wake", true));
        final int target = alarmVolume(a);
        String uri = alarmUri(a);
        PlayerService.command(a, "set_volume", "volume", 12);
        String body = uri.isEmpty() ? null : "{\"context_uri\":\"" + uri + "\"}";
        if (SpotifyWebApi.isSignedIn(a)) {
            SpotifyWebApi.playOnEcho(a, body, (ok, err) -> {
                if (!ok) PlayerService.command(a, "activate");
            });
        } else {
            PlayerService.command(a, "activate");
            main.postDelayed(() -> PlayerService.command(a, "play"), 1500);
        }
        // ramp the volume up over about a minute
        for (int i = 1; i <= 12; i++) {
            final int vol = 12 + (target - 12) * i / 12;
            main.postDelayed(() -> PlayerService.command(a, "set_volume", "volume", vol), 5000L * i);
        }
    }
}
