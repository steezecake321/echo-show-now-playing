package com.adam.nowplaying;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Spotify Web API: PKCE sign-in via a loopback redirect, device list, and playback transfer. */
final class SpotifyWebApi {

    static final String REDIRECT = "http://127.0.0.1:8888/callback";
    private static final int PORT = 8888;
    /** Bump when SCOPES grows: older sign-ins then ask the user to sign in again. */
    private static final int SCOPES_VERSION = 2;
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state "
            + "user-library-read user-library-modify playlist-read-private playlist-read-collaborative "
            + "user-read-recently-played user-top-read user-follow-read";

    static final class Device {
        String id, name, type;
        boolean active, restricted;
    }

    interface Callback<T> {
        void done(T result, String error);
    }

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Thread loginServer;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("spotify", Context.MODE_PRIVATE);
    }

    static String clientId(Context c) {
        return prefs(c).getString("client_id", null);
    }

    static void setClientId(Context c, String id) {
        prefs(c).edit().putString("client_id", id.trim()).remove("refresh_token").remove("access_token").apply();
    }

    static boolean isSignedIn(Context c) {
        return prefs(c).getString("refresh_token", null) != null;
    }

    /** True when the stored sign-in was granted the library/search scopes. */
    static boolean hasLibraryAccess(Context c) {
        return isSignedIn(c) && prefs(c).getInt("scopes_v", 1) >= SCOPES_VERSION;
    }

    // ---------- sign-in ----------

    private static final long LOGIN_WINDOW_MS = 20 * 60 * 1000;

    /** Starts a sign-in. Verifier and state are kept on disk so a killed process can still finish it. */
    static void startLogin(Context ctx) {
        final Context app = ctx.getApplicationContext();
        String clientId = clientId(app);
        if (clientId == null) return;
        SecureRandom rnd = new SecureRandom();
        byte[] v = new byte[48];
        rnd.nextBytes(v);
        final String verifier = b64url(v);
        byte[] s = new byte[12];
        rnd.nextBytes(s);
        final String state = b64url(s);
        String challenge;
        try {
            challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            return;
        }
        prefs(app).edit().putString("login_verifier", verifier).putString("login_state", state)
                .putLong("login_started", System.currentTimeMillis()).commit();
        String url = "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(REDIRECT)
                + "&code_challenge_method=S256&code_challenge=" + challenge
                + "&state=" + state
                + "&show_dialog=true";

        ensureListener(app);

        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(new Intent(view).setPackage("org.mozilla.firefox"));
        } catch (android.content.ActivityNotFoundException e) {
            app.startActivity(view);
        }
    }

    private static boolean loginPending(Context app) {
        SharedPreferences p = prefs(app);
        return p.getString("login_state", null) != null
                && System.currentTimeMillis() - p.getLong("login_started", 0) < LOGIN_WINDOW_MS;
    }

    /** Runs the loopback redirect listener while a sign-in is pending; safe to call repeatedly. */
    static synchronized void ensureListener(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Thread t = loginServer;
        if (t != null && t.isAlive()) return;
        if (!loginPending(app)) return;
        t = new Thread(() -> runLoginServer(app), "spotify-login");
        loginServer = t;
        t.start();
    }

    private static void runLoginServer(Context app) {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
            server.setSoTimeout(60 * 1000);
            while (loginPending(app)) {
                Socket sock;
                try {
                    sock = server.accept();
                } catch (java.net.SocketTimeoutException timeout) {
                    continue;
                }
                try {
                    sock.setSoTimeout(5000);
                    String line = readLine(sock.getInputStream());
                    if (line == null || !line.startsWith("GET /callback")) {
                        respond(sock, 404, "Not found");
                        continue;
                    }
                    SharedPreferences p = prefs(app);
                    String state = p.getString("login_state", "");
                    String verifier = p.getString("login_verifier", "");
                    Uri u = Uri.parse("http://127.0.0.1" + line.split(" ")[1]);
                    String code = u.getQueryParameter("code");
                    String err = u.getQueryParameter("error");
                    if (code == null || !state.equals(u.getQueryParameter("state"))) {
                        respond(sock, 400, "That sign-in link is out of date (" + (err != null ? err : "state mismatch")
                                + "). Open Now Playing, tap the speaker button and sign in again.");
                        continue;
                    }
                    String tokenErr = exchange(app, "grant_type=authorization_code&code=" + enc(code)
                            + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + enc(clientId(app))
                            + "&code_verifier=" + enc(verifier));
                    if (tokenErr != null) {
                        respond(sock, 500, "Sign-in failed: " + tokenErr);
                        continue;
                    }
                    p.edit().remove("login_state").remove("login_verifier").remove("login_started")
                            .putInt("scopes_v", SCOPES_VERSION).commit();
                    respond(sock, 200, "Signed in to Spotify. Returning to Now Playing…");
                    main.post(() -> app.startActivity(new Intent(app, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .putExtra("show_devices", true)));
                    return;
                } finally {
                    try {
                        sock.close();
                    } catch (java.io.IOException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (java.io.IOException ignored) {
                }
            }
            synchronized (SpotifyWebApi.class) {
                if (loginServer == Thread.currentThread()) loginServer = null;
            }
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (b.size() > 8192) break;
            if (c != '\r') b.write(c);
        }
        return c < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static void respond(Socket sock, int code, String message) throws java.io.IOException {
        String html = "<!doctype html><meta name=viewport content='width=device-width'><body style='background:#121212;"
                + "color:#eee;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:90vh;"
                + "font-size:22px;text-align:center'>" + message.replace("<", "&lt;") + "</body>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = sock.getOutputStream();
        out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---------- tokens ----------

    private static String exchange(Context app, String form) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
            int status = c.getResponseCode();
            String body = readAll(status < 400 ? c.getInputStream() : c.getErrorStream());
            if (status != 200) return "HTTP " + status + " " + body;
            JSONObject j = new JSONObject(body);
            SharedPreferences.Editor e = prefs(app).edit()
                    .putString("access_token", j.getString("access_token"))
                    .putLong("expires_at", System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000);
            if (j.has("refresh_token")) e.putString("refresh_token", j.getString("refresh_token"));
            e.apply();
            return null;
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    private static String token(Context app, boolean forceRefresh) {
        SharedPreferences p = prefs(app);
        String access = p.getString("access_token", null);
        if (!forceRefresh && access != null && System.currentTimeMillis() < p.getLong("expires_at", 0)) return access;
        String refresh = p.getString("refresh_token", null);
        String clientId = clientId(app);
        if (refresh == null || clientId == null) return null;
        String err = exchange(app, "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(clientId));
        if (err != null) {
            if (err.contains("invalid_grant")) p.edit().remove("refresh_token").remove("access_token").apply();
            return null;
        }
        return prefs(app).getString("access_token", null);
    }

    /** Returns {status, body}; retries once with a refreshed token on 401. */
    private static Object[] call(Context app, String method, String path, String json) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String tok = token(app, attempt == 1);
            if (tok == null) return new Object[]{401, "not signed in"};
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.spotify.com/v1" + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Authorization", "Bearer " + tok);
            if (json == null && (method.equals("PUT") || method.equals("POST"))) json = "";
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            String body = in != null ? readAll(in) : "";
            if (status == 401 && attempt == 0) continue;
            return new Object[]{status, body};
        }
        return new Object[]{401, "unauthorized"};
    }

    // ---------- API ----------

    static void getDevices(Context ctx, Callback<List<Device>> cb) {
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            List<Device> list = new ArrayList<>();
            String error = null;
            try {
                Object[] r = call(app, "GET", "/me/player/devices", null);
                int status = (Integer) r[0];
                if (status == 200) {
                    JSONArray arr = new JSONObject((String) r[1]).optJSONArray("devices");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject d = arr.getJSONObject(i);
                        Device dev = new Device();
                        dev.id = d.optString("id", null);
                        dev.name = d.optString("name", "Unknown");
                        dev.type = d.optString("type", "");
                        dev.active = d.optBoolean("is_active");
                        dev.restricted = d.optBoolean("is_restricted");
                        if (dev.id != null) list.add(dev);
                    }
                } else {
                    error = status == 401 ? "signin" : "Spotify said " + status + ": " + r[1];
                }
            } catch (Exception e) {
                error = "Couldn't reach Spotify (" + e.getClass().getSimpleName() + ")";
            }
            final String err = error;
            main.post(() -> cb.done(list, err));
        });
    }

    static final class Track {
        String uri, name, artists, album, coverUrl;
        long durationMs;
    }

    /** Looks up a track's name, artists, album and cover (Soloist omits these when another device is playing). */
    static void getTrack(Context ctx, String uri, Callback<Track> cb) {
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            Track t = null;
            String error = null;
            try {
                String id = uri.substring(uri.lastIndexOf(':') + 1);
                Object[] r = call(app, "GET", "/tracks/" + id, null);
                if ((Integer) r[0] == 200) {
                    JSONObject j = new JSONObject((String) r[1]);
                    t = new Track();
                    t.uri = uri;
                    t.name = j.optString("name", "");
                    StringBuilder names = new StringBuilder();
                    JSONArray artists = j.optJSONArray("artists");
                    for (int i = 0; artists != null && i < artists.length(); i++) {
                        if (names.length() > 0) names.append(", ");
                        names.append(artists.getJSONObject(i).optString("name"));
                    }
                    t.artists = names.toString();
                    JSONObject album = j.optJSONObject("album");
                    t.album = album != null ? album.optString("name", "") : "";
                    JSONArray images = album != null ? album.optJSONArray("images") : null;
                    if (images != null && images.length() > 0) t.coverUrl = images.getJSONObject(0).optString("url", null);
                    t.durationMs = j.optLong("duration_ms", 0);
                } else {
                    error = "HTTP " + r[0];
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            }
            final Track result = t;
            final String err = error;
            main.post(() -> cb.done(result, err));
        });
    }

    static void transfer(Context ctx, String deviceId, boolean play, Callback<Boolean> cb) {
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            String error = null;
            try {
                Object[] r = call(app, "PUT", "/me/player",
                        "{\"device_ids\":[\"" + deviceId + "\"],\"play\":" + play + "}");
                int status = (Integer) r[0];
                if (status != 204 && status != 200 && status != 202) {
                    error = status == 401 ? "signin" : "Spotify said " + status + ": " + r[1];
                }
            } catch (Exception e) {
                error = "Couldn't reach Spotify (" + e.getClass().getSimpleName() + ")";
            }
            final String err = error;
            main.post(() -> cb.done(err == null, err));
        });
    }

    // ---------- generic calls used by the library, queue, search and alarm ----------

    /** GET path (e.g. "/me/playlists?limit=50"); result is the parsed JSON object. error "signin" means sign in again. */
    static void getJson(Context ctx, String path, Callback<JSONObject> cb) {
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            JSONObject out = null;
            String error = null;
            try {
                Object[] r = call(app, "GET", path, null);
                int status = (Integer) r[0];
                if (status == 200) {
                    String body = ((String) r[1]).trim();
                    out = body.startsWith("[") ? new JSONObject().put("_array", new JSONArray(body)) : new JSONObject(body);
                } else if (status == 204) out = new JSONObject();
                else error = describe(status, (String) r[1]);
            } catch (Exception e) {
                error = "Couldn't reach Spotify (" + e.getClass().getSimpleName() + ")";
            }
            final JSONObject result = out;
            final String err = error;
            main.post(() -> cb.done(result, err));
        });
    }

    /** PUT/POST/DELETE with an optional JSON body. */
    static void send(Context ctx, String method, String path, String json, Callback<Boolean> cb) {
        final Context app = ctx.getApplicationContext();
        io.execute(() -> {
            String error = sendNow(app, method, path, json);
            main.post(() -> cb.done(error == null, error));
        });
    }

    private static String sendNow(Context app, String method, String path, String json) {
        try {
            Object[] r = call(app, method, path, json);
            int status = (Integer) r[0];
            if (status >= 200 && status < 300) return null;
            return describe(status, (String) r[1]);
        } catch (Exception e) {
            return "Couldn't reach Spotify (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String describe(int status, String body) {
        if (status == 401) return "signin";
        String msg = body;
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) msg = e.optString("message", body);
        } catch (Exception ignored) {
        }
        if (status == 403) return "Spotify refused this (403). " + msg;
        if (status == 429) return "Spotify is busy, try again in a moment.";
        return "Spotify said " + status + ": " + msg;
    }

    /**
     * Plays on this Echo: finds the Echo among the Connect devices, wakes/transfers to it if needed,
     * then starts playback. body is the /me/player/play JSON ({"context_uri":..} or {"uris":[..]}), or null to resume.
     */
    static void playOnEcho(Context ctx, String body, Callback<Boolean> cb) {
        playOnEcho(ctx, body, false, cb);
    }

    /** exact: start precisely the first item even when shuffle is on (shuffle is switched off, then back on). */
    static void playOnEcho(Context ctx, String body, final boolean exact, Callback<Boolean> cb) {
        final Context app = ctx.getApplicationContext();
        final boolean shuffled = PlayerState.get().shuffle;
        io.execute(() -> {
            String error = null;
            try {
                String wanted = PlayerState.get().deviceName;
                Object[] r = call(app, "GET", "/me/player/devices", null);
                if ((Integer) r[0] != 200) {
                    error = describe((Integer) r[0], (String) r[1]);
                } else {
                    JSONArray arr = new JSONObject((String) r[1]).optJSONArray("devices");
                    String id = null;
                    boolean active = false;
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject d = arr.getJSONObject(i);
                        if (wanted.equals(d.optString("name"))) {
                            id = d.optString("id", null);
                            active = d.optBoolean("is_active");
                        }
                    }
                    if (id == null) {
                        error = "Spotify can't see this Echo yet. Wait a few seconds and try again.";
                    } else {
                        if (!active) {
                            error = sendNow(app, "PUT", "/me/player", "{\"device_ids\":[\"" + id + "\"],\"play\":false}");
                            if (error == null) Thread.sleep(1200);
                        }
                        if (error == null && exact && shuffled) {
                            sendNow(app, "PUT", "/me/player/shuffle?state=false&device_id=" + id, null);
                        }
                        if (error == null) {
                            error = sendNow(app, "PUT", "/me/player/play?device_id=" + id, body == null ? "{}" : body);
                        }
                        if (error == null && exact && shuffled) {
                            Thread.sleep(900);
                            sendNow(app, "PUT", "/me/player/shuffle?state=true&device_id=" + id, null);
                        }
                    }
                }
            } catch (Exception e) {
                error = "Couldn't reach Spotify (" + e.getClass().getSimpleName() + ")";
            }
            final String err = error;
            main.post(() -> cb.done(err == null, err));
        });
    }

    static String urlEncode(String s) {
        return enc(s);
    }

    // ---------- helpers ----------

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
