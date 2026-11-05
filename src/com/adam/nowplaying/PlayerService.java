package com.adam.nowplaying;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

/** Always-on link to Soloist; drives the notification, lock screen media controls, widget and auto-open. */
public class PlayerService extends Service implements SoloistClient.Listener, PlayerState.Listener {

    static final String ACTION_TOGGLE = "com.adam.nowplaying.TOGGLE";
    static final String ACTION_NEXT = "com.adam.nowplaying.NEXT";
    static final String ACTION_PREV = "com.adam.nowplaying.PREV";
    static final String ACTION_CMD = "com.adam.nowplaying.CMD";
    static final String ACTION_LOGIN = "com.adam.nowplaying.LOGIN";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;
    private static final long LOCAL_PLAY_GRACE_MS = 6000;

    private static PlayerService instance;
    static boolean activityVisible;
    static boolean dreaming;
    private static long lastLocalCommandMs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final PlayerState state = PlayerState.get();
    private SoloistClient client;
    private MediaSession session;
    private boolean primed;
    private boolean wasPlaying;
    private String metaSig;
    private String widgetSig;

    // ---------- entry points for the UI, widget and notification ----------

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, PlayerService.class));
        } catch (Exception ignored) {
        }
    }

    static void command(Context c, String name) {
        lastLocalCommandMs = System.currentTimeMillis();
        PlayerService s = instance;
        if (s != null) {
            s.client.command(name);
        } else {
            start(c.getApplicationContext(), new Intent(c, PlayerService.class).setAction(ACTION_CMD).putExtra("cmd", name));
        }
    }

    static void command(Context c, String name, String key, long value) {
        lastLocalCommandMs = System.currentTimeMillis();
        PlayerService s = instance;
        if (s != null) {
            s.client.command(name, key, value);
        } else {
            start(c.getApplicationContext(), new Intent(c, PlayerService.class).setAction(ACTION_CMD)
                    .putExtra("cmd", name).putExtra("key", key).putExtra("value", value));
        }
    }

    static void command(Context c, String name, String key, boolean value) {
        lastLocalCommandMs = System.currentTimeMillis();
        PlayerService s = instance;
        if (s != null) s.client.command(name, key, value);
        else start(c);
    }

    static void toggleShuffle(Context c) {
        PlayerState st = PlayerState.get();
        boolean on = !st.shuffle;
        command(c, "set_shuffle", "enabled", on);
        st.setOptimisticShuffle(on);
    }

    /** Cycles repeat off → whole list → this song → off, like Spotify. */
    static void cycleRepeat(Context c) {
        PlayerState st = PlayerState.get();
        switch (st.repeat) {
            case "off":
                command(c, "set_repeat_context", "enabled", true);
                st.setOptimisticRepeat("context");
                break;
            case "context":
                command(c, "set_repeat_track", "enabled", true);
                st.setOptimisticRepeat("track");
                break;
            default:
                command(c, "set_repeat_track", "enabled", false);
                command(c, "set_repeat_context", "enabled", false);
                st.setOptimisticRepeat("off");
                break;
        }
    }

    private static int volumeBeforeMute = 40;

    static void toggleMute(Context c) {
        PlayerState st = PlayerState.get();
        int target;
        if (st.volume > 0) {
            volumeBeforeMute = st.volume;
            target = 0;
        } else {
            target = volumeBeforeMute > 0 ? volumeBeforeMute : 40;
        }
        command(c, "set_volume", "volume", target);
        st.setOptimisticVolume(target);
    }

    static void togglePlay(Context c) {
        PlayerState st = PlayerState.get();
        boolean playing = st.isPlaying();
        command(c, playing ? "pause" : "play");
        st.setOptimisticStatus(playing ? "paused" : "playing");
    }

    private static void start(Context c, Intent i) {
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    // ---------- lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "NowPlaying");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { command(PlayerService.this, "play"); state.setOptimisticStatus("playing"); }
            @Override public void onPause() { command(PlayerService.this, "pause"); state.setOptimisticStatus("paused"); }
            @Override public void onSkipToNext() { command(PlayerService.this, "skip_next"); }
            @Override public void onSkipToPrevious() { command(PlayerService.this, "skip_prev"); }
            @Override public void onSeekTo(long pos) {
                command(PlayerService.this, "seek", "position_ms", pos);
                state.setOptimisticPosition(pos);
            }
        });
        session.setSessionActivity(openAppIntent(this));
        session.setActive(true);

        startForeground(NOTIF_ID, buildNotification());
        state.addListener(this);
        client = new SoloistClient(this);
        client.start();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        SpotifyWebApi.ensureListener(this);
    }

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            ui.postDelayed(PlayerService.this::showOverLockScreen, 400);
        }
    };

    private void showOverLockScreen() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked() || activityVisible || dreaming) return;
        if (!state.connected || !state.hasTrack() || !state.isPlaying()) return;
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) {
            togglePlay(this);
        } else if (ACTION_NEXT.equals(action)) {
            command(this, "skip_next");
        } else if (ACTION_PREV.equals(action)) {
            command(this, "skip_prev");
        } else if (ACTION_LOGIN.equals(action)) {
            String id = intent.getStringExtra("client_id");
            if (id != null && !id.trim().isEmpty()) SpotifyWebApi.setClientId(this, id);
            SpotifyWebApi.startLogin(this);
        } else if (ACTION_CMD.equals(action)) {
            String cmd = intent.getStringExtra("cmd");
            String key = intent.getStringExtra("key");
            if (cmd != null && key != null) client.command(cmd, key, intent.getLongExtra("value", 0));
            else if (cmd != null) client.command(cmd);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(screenOn);
        state.removeListener(this);
        client.stop();
        session.release();
        instance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- Soloist events (WebSocket thread) ----------

    @Override
    public void onConnected() {
        ui.post(() -> state.setConnected(true));
    }

    @Override
    public void onDisconnected() {
        ui.post(() -> {
            primed = false;
            state.setConnected(false);
        });
    }

    @Override
    public void onMessage(JSONObject msg) {
        ui.post(() -> {
            state.handle(msg);
            if (!primed && "playback_state".equals(msg.optString("type"))) {
                primed = true;
                wasPlaying = state.isPlaying();
            }
        });
    }

    // ---------- state fan-out ----------

    @Override
    public void onStateChanged() {
        updateSession();
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
        String sig = state.connected + "|" + state.loggedIn + "|" + state.trackUri + "|" + state.status + "|" + state.hasArt();
        if (!sig.equals(widgetSig)) {
            widgetSig = sig;
            NowPlayingWidget.update(this);
        }
        lookUpOtherDevice();
        lookUpMissingMetadata();
        boolean playing = state.isPlaying();
        if (primed && playing && !wasPlaying && state.active
                && System.currentTimeMillis() - lastLocalCommandMs > LOCAL_PLAY_GRACE_MS) {
            maybeAutoOpen();
        }
        if (primed) wasPlaying = playing;
    }

    private long lastDeviceLookup;
    private boolean lastActive = true;
    private boolean lookupRunning;

    /** Soloist doesn't name the other device; ask the Web API which one is active. */
    private void lookUpOtherDevice() {
        boolean becameInactive = lastActive && !state.active;
        lastActive = state.active;
        if (state.active) {
            state.setOtherDevice(null, null);
            return;
        }
        if (lookupRunning || !state.connected || !state.hasTrack() || !SpotifyWebApi.isSignedIn(this)) return;
        long now = System.currentTimeMillis();
        long wait = becameInactive || state.otherDeviceName == null ? 10_000 : 60_000;
        if (!becameInactive && now - lastDeviceLookup < wait) return;
        lastDeviceLookup = now;
        lookupRunning = true;
        SpotifyWebApi.getDevices(this, (devices, error) -> {
            lookupRunning = false;
            if (error != null || state.active) return;
            String name = null, type = null;
            for (SpotifyWebApi.Device d : devices) {
                if (d.active) {
                    name = d.name;
                    type = d.type;
                }
            }
            state.setOtherDevice(name, type);
        });
    }

    private String metadataLookupUri;

    private void lookUpMissingMetadata() {
        if (!state.needsMetadata() || state.trackUri.equals(metadataLookupUri) || !SpotifyWebApi.isSignedIn(this)) return;
        metadataLookupUri = state.trackUri;
        SpotifyWebApi.getTrack(this, state.trackUri, (track, error) -> state.fillMetadata(track));
    }

    private void updateSession() {
        String sig = state.trackUri + "|" + state.trackName + "|" + state.hasArt();
        if (!sig.equals(metaSig)) {
            metaSig = sig;
            MediaMetadata.Builder m = new MediaMetadata.Builder();
            if (state.hasTrack()) {
                m.putString(MediaMetadata.METADATA_KEY_TITLE, state.trackName);
                m.putString(MediaMetadata.METADATA_KEY_ARTIST, state.artists);
                m.putString(MediaMetadata.METADATA_KEY_ALBUM, state.album);
                m.putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs);
                if (state.hasArt()) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, scaled(state.art, 320));
            }
            session.setMetadata(m.build());
        }
        int st = state.isPlaying() ? PlaybackState.STATE_PLAYING
                : state.hasTrack() ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(st, state.currentPosition(), state.isPlaying() ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
    }

    static Bitmap scaled(Bitmap b, int max) {
        if (b.getWidth() <= max && b.getHeight() <= max) return b;
        float k = max / (float) Math.max(b.getWidth(), b.getHeight());
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
    }

    static PendingIntent openAppIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent actionIntent(Context c, String action, int code) {
        Intent i = new Intent(c, PlayerService.class).setAction(action);
        return PendingIntent.getForegroundService(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(openAppIntent(this))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setColor(state.artColor);
        Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(session.getSessionToken());
        if (state.connected && state.hasTrack()) {
            b.setContentTitle(state.trackName).setContentText(state.artists).setSubText(state.deviceName);
            if (state.hasArt()) b.setLargeIcon(scaled(state.art, 256));
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_prev), "Previous", actionIntent(this, ACTION_PREV, 1)).build());
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, state.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play),
                    state.isPlaying() ? "Pause" : "Play", actionIntent(this, ACTION_TOGGLE, 2)).build());
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_next), "Next", actionIntent(this, ACTION_NEXT, 3)).build());
            style.setShowActionsInCompactView(0, 1, 2);
        } else {
            b.setContentTitle(state.deviceName)
                    .setContentText(!state.connected ? "Starting the speaker…"
                            : state.loggedIn ? "Ready for Spotify" : "Waiting for Spotify");
        }
        b.setStyle(style);
        return b.build();
    }

    // ---------- auto-open ----------

    private void maybeAutoOpen() {
        if (activityVisible || dreaming) return;
        PowerManager pm = getSystemService(PowerManager.class);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (pm == null || !pm.isInteractive() || (km != null && km.isKeyguardLocked())) return;
        String fg = foregroundPackage();
        if (fg == null || !fg.equals(homePackage())) return;
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private String homePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
    }

    private String foregroundPackage() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 24L * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
        }
        return last;
    }
}
