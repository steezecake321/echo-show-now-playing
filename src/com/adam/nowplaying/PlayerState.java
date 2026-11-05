package com.adam.nowplaying;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide playback state fed by Soloist's WebSocket events. Main thread only. */
final class PlayerState {

    interface Listener {
        void onStateChanged();
    }

    private static final PlayerState INSTANCE = new PlayerState();

    static PlayerState get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    boolean connected;
    boolean loggedIn;
    boolean active = true;
    String deviceName = "Spotify speaker";
    String status = "stopped";
    String trackUri;
    String trackName;
    String artists = "";
    String album = "";
    String coverUrl;
    long durationMs;
    long positionMs;
    long positionStampMs;
    double speed = 1.0;
    int volume = -1;

    Bitmap art;
    Bitmap artBlur;
    int artColor = Color.rgb(29, 185, 84);
    private String artLoadedUrl;
    private String artLoadingUrl;

    private PlayerState() {
    }

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) l.onStateChanged();
    }

    boolean isPlaying() {
        return "playing".equals(status);
    }

    boolean hasTrack() {
        return trackName != null;
    }

    boolean hasArt() {
        return art != null && coverUrl != null && coverUrl.equals(artLoadedUrl);
    }

    long currentPosition() {
        long pos = positionMs;
        if (isPlaying() && positionStampMs > 0) {
            pos += (long) ((System.currentTimeMillis() - positionStampMs) * speed);
        }
        if (durationMs > 0) pos = Math.min(pos, durationMs);
        return Math.max(0, pos);
    }

    void setConnected(boolean c) {
        connected = c;
        if (!c) loggedIn = false;
        notifyChanged();
    }

    void setOptimisticStatus(String s) {
        if (isPlaying() && !"playing".equals(s)) positionMs = currentPosition();
        status = s;
        positionStampMs = System.currentTimeMillis();
        notifyChanged();
    }

    void setOptimisticPosition(long ms) {
        positionMs = ms;
        positionStampMs = System.currentTimeMillis();
        notifyChanged();
    }

    String otherDeviceName;
    String otherDeviceType;
    boolean shuffle;
    String repeat = "off";

    void setOtherDevice(String name, String type) {
        boolean same = (name == null ? otherDeviceName == null : name.equals(otherDeviceName))
                && (type == null ? otherDeviceType == null : type.equals(otherDeviceType));
        if (same) return;
        otherDeviceName = name;
        otherDeviceType = type;
        notifyChanged();
    }

    void setOptimisticShuffle(boolean on) {
        shuffle = on;
        notifyChanged();
    }

    void setOptimisticRepeat(String mode) {
        repeat = mode;
        notifyChanged();
    }

    private void applyOptions(JSONObject o) {
        if (o == null) return;
        if (o.has("shuffle")) shuffle = o.optBoolean("shuffle", shuffle);
        if (o.has("repeat")) repeat = o.optString("repeat", repeat);
    }

    boolean needsMetadata() {
        return trackUri != null && trackUri.startsWith("spotify:track:") && (trackName == null || trackName.isEmpty());
    }

    /** Fills in details Soloist didn't send, if the track hasn't changed meanwhile. */
    void fillMetadata(SpotifyWebApi.Track t) {
        if (t == null || !t.uri.equals(trackUri)) return;
        trackName = t.name;
        artists = t.artists;
        album = t.album;
        if (coverUrl == null) coverUrl = t.coverUrl;
        if (durationMs <= 0) durationMs = t.durationMs;
        ensureArt();
        notifyChanged();
    }

    void setOptimisticVolume(int v) {
        volume = v;
        notifyChanged();
    }

    void handle(JSONObject msg) {
        String type = msg.optString("type");
        boolean hadOptions = msg.has("options");
        applyOptions(msg.optJSONObject("options"));
        switch (type) {
            case "playback_state":
                loggedIn = true;
                status = msg.optString("status", status);
                if (msg.has("item") && !msg.isNull("item")) applyItem(msg.optJSONObject("item"));
                else clearItem();
                applyPosition(msg.optJSONObject("position"));
                if (msg.has("volume")) volume = msg.optInt("volume", volume);
                active = msg.optBoolean("is_active", active);
                break;
            case "playback_changed":
                status = msg.optString("status", status);
                break;
            case "track_changed":
                if (msg.has("item") && !msg.isNull("item")) applyItem(msg.optJSONObject("item"));
                break;
            case "position_sync":
                applyPosition(msg.optJSONObject("position"));
                break;
            case "auth_state":
                loggedIn = msg.optBoolean("logged_in", loggedIn);
                active = msg.optBoolean("is_active", active);
                String name = msg.optString("device_name", "");
                if (!name.isEmpty()) deviceName = name;
                if (!loggedIn) clearItem();
                break;
            default:
                if (msg.has("is_active")) active = msg.optBoolean("is_active", active);
                if (!msg.has("volume") || "command_result".equals(type)) {
                    if (!msg.has("is_active") && !hadOptions) return;
                    break;
                }
                volume = msg.optInt("volume", volume);
                break;
        }
        ensureArt();
        notifyChanged();
    }

    private void clearItem() {
        trackUri = null;
        trackName = null;
        artists = "";
        album = "";
        coverUrl = null;
        durationMs = 0;
        positionMs = 0;
    }

    private void applyItem(JSONObject item) {
        if (item == null) return;
        JSONObject dec = item.optJSONObject("decorations");
        if (dec == null) return;
        trackUri = item.optString("uri", null);
        JSONObject identity = dec.optJSONObject("identity");
        trackName = identity != null ? identity.optString("name", "") : "";

        StringBuilder names = new StringBuilder();
        JSONArray creators = dec.optJSONArray("creators");
        if (creators != null) {
            for (int i = 0; i < creators.length(); i++) {
                JSONObject ent = creators.optJSONObject(i);
                String n = nameOf(ent != null ? ent.optJSONObject("entity") : null);
                if (n != null && !n.isEmpty()) {
                    if (names.length() > 0) names.append(", ");
                    names.append(n);
                }
            }
        }
        artists = names.toString();

        JSONObject parent = dec.optJSONObject("parent");
        String albumName = nameOf(parent != null ? parent.optJSONObject("entity") : null);
        album = albumName != null ? albumName : "";

        coverUrl = null;
        JSONObject visual = dec.optJSONObject("visual_identity");
        JSONArray covers = visual != null ? visual.optJSONArray("cover") : null;
        if (covers != null) {
            String fallback = null;
            for (int i = 0; i < covers.length(); i++) {
                JSONObject cv = covers.optJSONObject(i);
                if (cv == null) continue;
                String size = cv.optString("size");
                String url = cv.optString("url", null);
                if ("large".equals(size)) coverUrl = url;
                if (fallback == null || "default".equals(size)) fallback = url;
            }
            if (coverUrl == null) coverUrl = fallback;
        }

        JSONObject playback = dec.optJSONObject("playback");
        durationMs = playback != null ? playback.optLong("duration_ms", 0) : 0;
    }

    private static String nameOf(JSONObject entity) {
        if (entity == null) return null;
        JSONObject dec = entity.optJSONObject("decorations");
        JSONObject identity = dec != null ? dec.optJSONObject("identity") : null;
        return identity != null ? identity.optString("name", null) : null;
    }

    private void applyPosition(JSONObject pos) {
        if (pos == null) return;
        positionMs = pos.optLong("position_ms", positionMs);
        long stamp = pos.optLong("timestamp_ms", 0);
        positionStampMs = stamp > 0 ? stamp : System.currentTimeMillis();
        speed = pos.optDouble("speed", 1.0);
    }

    private void ensureArt() {
        final String url = coverUrl;
        if (url == null || url.equals(artLoadedUrl) || url.equals(artLoadingUrl)) return;
        artLoadingUrl = url;
        artLoader.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            final Bitmap full = bmp;
            final Bitmap blur = full != null ? Bitmap.createScaledBitmap(full, 10, 10, true) : null;
            final int color = full != null ? Bitmap.createScaledBitmap(full, 1, 1, true).getPixel(0, 0) : 0;
            main.post(() -> {
                if (url.equals(artLoadingUrl)) artLoadingUrl = null;
                if (full == null) return;
                art = full;
                artBlur = blur;
                artColor = color;
                artLoadedUrl = url;
                notifyChanged();
            });
        });
    }
}
