package com.adam.nowplaying;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Pinned playlists/albums/artists: one-tap presets shown first in the library and usable by the alarm. */
final class Pins {

    static final class Pin {
        String uri, title, subtitle, image;
    }

    private Pins() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("pins", Context.MODE_PRIVATE);
    }

    static List<Pin> list(Context c) {
        List<Pin> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("pins", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pin p = new Pin();
                p.uri = o.getString("uri");
                p.title = o.optString("title");
                p.subtitle = o.optString("subtitle");
                p.image = o.optString("image", null);
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean isPinned(Context c, String uri) {
        for (Pin p : list(c)) if (p.uri.equals(uri)) return true;
        return false;
    }

    /** Adds or removes; returns true when the item is pinned afterwards. */
    static boolean toggle(Context c, Pin pin) {
        List<Pin> all = list(c);
        boolean removed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).uri.equals(pin.uri)) {
                all.remove(i);
                removed = true;
            }
        }
        if (!removed) all.add(pin);
        JSONArray a = new JSONArray();
        try {
            for (Pin p : all) {
                a.put(new JSONObject().put("uri", p.uri).put("title", p.title)
                        .put("subtitle", p.subtitle).put("image", p.image == null ? "" : p.image));
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("pins", a.toString()).apply();
        return !removed;
    }
}
