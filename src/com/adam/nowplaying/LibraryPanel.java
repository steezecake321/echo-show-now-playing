package com.adam.nowplaying;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Full-screen browser for your Spotify library and search; tapping something plays it on the Echo. */
final class LibraryPanel extends FrameLayout implements PlayerState.Listener {

    private static final int HEADER = 0, PLAYLIST = 1, ALBUM = 2, ARTIST = 3, TRACK = 4, PLAYALL = 5, QUEUED = 6;

    static final class Item {
        int kind;
        String uri, id, title, subtitle, image;
    }

    private static final class Result {
        final List<Item> items = new ArrayList<>();
        boolean more;
    }

    private interface Fetcher {
        void fetch(int offset, SpotifyWebApi.Callback<Result> cb);
    }

    private static final class Page {
        String title;
        Fetcher fetcher;
        String contextUri;      // set on playlist/album pages: tracks play inside this context
        boolean tab;
        final List<Item> items = new ArrayList<>();
        boolean more, loading;
        String message;
        Runnable messageAction;
        String messageActionLabel;
    }

    private static final String[] TAB_NAMES = {"Pinned", "Playlists", "Liked", "Albums", "Artists", "Recent", "Top", "Queue"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context ctx;
    private final Deque<Page> stack = new ArrayDeque<>();
    private final ListView listView;
    private final Adapter adapter = new Adapter();
    private final TextView titleView;
    private final TextView back;
    private final TextView status;
    private final EditText search;
    private final LinearLayout tabRow;
    private final List<TextView> tabPills = new ArrayList<>();
    private final TextView message;
    private final TextView messageButton;
    private final ImageView miniArt;
    private final TextView miniTitle, miniSub;
    private final ImageView miniPlay;
    private int selectedTab = 1;
    private Page page;

    LibraryPanel(Context c) {
        super(c);
        ctx = c;
        setBackgroundColor(Color.rgb(18, 18, 20));
        setClickable(true);
        setVisibility(GONE);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -1));

        // header: back, title, status, search
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 2));
        back = Ui.text(c, 30, Color.WHITE, "sans-serif-light", 1);
        back.setText("‹");
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 14), Ui.dp(c, 4));
        back.setOnClickListener(v -> back());
        header.addView(back);
        titleView = Ui.text(c, 20, Color.WHITE, "sans-serif-medium", 1);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        status = Ui.text(c, 13, Ui.GREEN, "sans-serif-medium", 1);
        status.setPadding(0, 0, Ui.dp(c, 12), 0);
        header.addView(status);
        search = new EditText(c);
        search.setHint("Search Spotify");
        search.setHintTextColor(Color.argb(120, 255, 255, 255));
        search.setTextColor(Color.WHITE);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
        search.setBackground(Ui.round(c, Color.argb(30, 255, 255, 255), 22));
        search.setOnEditorActionListener((v, action, e) -> {
            String q = search.getText().toString().trim();
            if (!q.isEmpty()) {
                hideKeyboard();
                openSearch(q);
            }
            return true;
        });
        header.addView(search, new LinearLayout.LayoutParams(Ui.dp(c, 260), Ui.dp(c, 42)));
        col.addView(header);

        // tabs
        HorizontalScrollView tabScroll = new HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(c);
        tabRow.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14), Ui.dp(c, 6));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView pill = Ui.pill(c, TAB_NAMES[i], false);
            pill.setOnClickListener(v -> selectTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(c, 8);
            tabRow.addView(pill, lp);
            tabPills.add(pill);
        }
        tabScroll.addView(tabRow);
        col.addView(tabScroll);

        // list + message
        FrameLayout body = new FrameLayout(c);
        listView = new ListView(c);
        listView.setDivider(null);
        listView.setSelector(android.R.color.transparent);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onRow(page.items.get(pos)));
        listView.setOnItemLongClickListener((p, v, pos, id) -> onRowLong(page.items.get(pos)));
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (page != null && page.more && !page.loading && first + visible >= total - 4) loadMore(page);
            }
        });
        body.addView(listView, new LayoutParams(-1, -1));
        LinearLayout msgBox = new LinearLayout(c);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setGravity(Gravity.CENTER);
        msgBox.setPadding(Ui.dp(c, 40), 0, Ui.dp(c, 40), 0);
        message = Ui.text(c, 16, Color.argb(200, 255, 255, 255), "sans-serif", 6);
        message.setGravity(Gravity.CENTER);
        msgBox.addView(message);
        messageButton = Ui.pill(c, "", true);
        LinearLayout.LayoutParams mb = new LinearLayout.LayoutParams(-2, -2);
        mb.topMargin = Ui.dp(c, 14);
        msgBox.addView(messageButton, mb);
        body.addView(msgBox, new LayoutParams(-1, -1));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        // mini player
        LinearLayout mini = new LinearLayout(c);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setBackgroundColor(Color.rgb(36, 36, 40));
        mini.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 6));
        mini.setOnClickListener(v -> hide());
        miniArt = new ImageView(c);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mini.addView(miniArt, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        LinearLayout mt = new LinearLayout(c);
        mt.setOrientation(LinearLayout.VERTICAL);
        mt.setPadding(Ui.dp(c, 12), 0, 0, 0);
        miniTitle = Ui.text(c, 15, Color.WHITE, "sans-serif-medium", 1);
        miniSub = Ui.text(c, 12, Ui.DIM, "sans-serif", 1);
        mt.addView(miniTitle);
        mt.addView(miniSub);
        mini.addView(mt, new LinearLayout.LayoutParams(0, -2, 1f));
        miniPlay = new ImageView(c);
        miniPlay.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        miniPlay.setOnClickListener(v -> PlayerService.togglePlay(c));
        mini.addView(miniPlay, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        col.addView(mini, new LinearLayout.LayoutParams(-1, Ui.dp(c, 56)));
    }

    // ---------- show / hide / navigation ----------

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void show() {
        setVisibility(VISIBLE);
        bringToFront();
        PlayerState.get().addListener(this);
        onStateChanged();
        if (page == null || page.tab) selectTab(selectedTab);
        else render();
    }

    void hide() {
        hideKeyboard();
        setVisibility(GONE);
        PlayerState.get().removeListener(this);
    }

    /** Pops one level; closes the panel at the top. */
    void back() {
        hideKeyboard();
        if (stack.size() > 1) {
            stack.pop();
            page = stack.peek();
            render();
        } else {
            hide();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        search.clearFocus();
    }

    private void selectTab(int idx) {
        selectedTab = idx;
        stack.clear();
        Page p = tabPage(idx);
        p.tab = true;
        stack.push(p);
        page = p;
        render();
        if (p.fetcher != null) loadMore(p);
    }

    private void open(Page p) {
        stack.push(p);
        page = p;
        render();
        loadMore(p);
    }

    private void render() {
        titleView.setText(page.title);
        back.setText(stack.size() > 1 ? "‹" : "×");
        for (int i = 0; i < tabPills.size(); i++) {
            Ui.restylePill(tabPills.get(i), page.tab ? i == selectedTab : false);
        }
        tabRow.getParent().requestLayout();
        ((View) tabRow.getParent()).setVisibility(page.tab ? VISIBLE : GONE);
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
        showMessage();
    }

    private void showMessage() {
        String m = page.message;
        if (m == null && page.items.isEmpty() && page.loading) m = "Loading…";
        if (m == null && page.items.isEmpty() && !page.loading && page.fetcher != null) m = "Nothing here yet.";
        message.setText(m);
        ((View) message.getParent()).setVisibility(m == null ? GONE : VISIBLE);
        boolean btn = page.messageActionLabel != null && m != null;
        messageButton.setVisibility(btn ? VISIBLE : GONE);
        if (btn) {
            messageButton.setText(page.messageActionLabel);
            messageButton.setOnClickListener(v -> {
                if (page.messageAction != null) page.messageAction.run();
            });
        }
    }

    private void say(String s) {
        status.setText(s);
        ui.removeCallbacks(clearStatus);
        ui.postDelayed(clearStatus, 3500);
    }

    private final Runnable clearStatus = new Runnable() { @Override public void run() { status.setText(""); } };

    // ---------- pages ----------

    private Page tabPage(int idx) {
        Page p = new Page();
        p.title = TAB_NAMES[idx];
        if (idx == 0) {
            p.fetcher = null;
            for (Pins.Pin pin : Pins.list(ctx)) {
                Item it = new Item();
                it.uri = pin.uri;
                it.id = pin.uri.substring(pin.uri.lastIndexOf(':') + 1);
                it.title = pin.title;
                it.subtitle = pin.subtitle;
                it.image = pin.image;
                it.kind = kindOfUri(pin.uri);
                p.items.add(it);
            }
            if (p.items.isEmpty()) {
                p.message = "Nothing pinned yet.\nTouch and hold a playlist, album or artist to pin it here.";
            }
            return p;
        }
        if (SpotifyWebApi.clientId(ctx) == null) {
            p.message = "This speaker isn't linked to a Spotify developer app yet.";
            return p;
        }
        if (!SpotifyWebApi.hasLibraryAccess(ctx)) {
            p.message = SpotifyWebApi.isSignedIn(ctx)
                    ? "This app now browses your library, so Spotify needs to ask your permission once more."
                    : "Sign in to Spotify to browse your library from the Echo.";
            p.messageActionLabel = "Sign in to Spotify";
            p.messageAction = () -> SpotifyWebApi.startLogin(ctx);
            return p;
        }
        switch (idx) {
            case 1: p.fetcher = (o, cb) -> paged("/me/playlists?limit=50&offset=" + o, null, PLAYLIST, cb); break;
            case 2: p.fetcher = (o, cb) -> paged("/me/tracks?limit=50&offset=" + o, "track", TRACK, cb); break;
            case 3: p.fetcher = (o, cb) -> paged("/me/albums?limit=50&offset=" + o, "album", ALBUM, cb); break;
            case 4: p.fetcher = (o, cb) -> paged("/me/following?type=artist&limit=50", "artists", ARTIST, cb); break;
            case 5: p.fetcher = (o, cb) -> paged("/me/player/recently-played?limit=50", "track", TRACK, cb); break;
            case 6: p.fetcher = (o, cb) -> paged("/me/top/tracks?limit=50&time_range=medium_term&offset=" + o, null, TRACK, cb); break;
            default: p.fetcher = (o, cb) -> queue(cb); break;
        }
        return p;
    }

    private static int kindOfUri(String uri) {
        if (uri.startsWith("spotify:album:")) return ALBUM;
        if (uri.startsWith("spotify:artist:")) return ARTIST;
        return PLAYLIST;
    }

    private void openDetail(Item it) {
        Page p = new Page();
        p.title = it.title;
        final String id = it.id;
        final String cover = it.image;
        if (it.kind == PLAYLIST) {
            p.contextUri = it.uri;
            p.fetcher = (o, cb) -> paged("/playlists/" + id + "/tracks?limit=50&market=from_token&offset=" + o, "track", TRACK, cb);
        } else if (it.kind == ALBUM) {
            p.contextUri = it.uri;
            p.fetcher = (o, cb) -> albumTracks(id, o, cover, cb);
        } else {
            p.fetcher = (o, cb) -> paged("/artists/" + id + "/albums?include_groups=album,single&limit=50&offset=" + o, null, ALBUM, cb);
        }
        final Item playAll = new Item();
        playAll.kind = PLAYALL;
        playAll.uri = it.uri;
        playAll.title = "Play " + (it.kind == ARTIST ? "this artist" : it.kind == ALBUM ? "album" : "playlist");
        playAll.subtitle = it.subtitle;
        p.items.add(playAll);
        open(p);
    }

    private void openSearch(String q) {
        Page p = new Page();
        p.title = "Search: " + q;
        p.fetcher = (o, cb) -> search(q, cb);
        open(p);
    }

    // ---------- fetching ----------

    private void loadMore(Page p) {
        if (p.fetcher == null || p.loading) return;
        p.loading = true;
        showMessage();
        final int offset = countReal(p);
        p.fetcher.fetch(offset, (res, err) -> {
            p.loading = false;
            if (res != null) {
                p.items.addAll(res.items);
                p.more = res.more;
                if (err == null) p.message = null;
            } else {
                p.more = false;
                if ("signin".equals(err)) {
                    p.message = "Spotify needs you to sign in again.";
                    p.messageActionLabel = "Sign in to Spotify";
                    p.messageAction = () -> SpotifyWebApi.startLogin(ctx);
                } else if (err != null && err.contains("403") && p.contextUri != null && p.contextUri.startsWith("spotify:playlist:")) {
                    p.message = "Spotify only lets apps list the songs in playlists you own or edit. "
                            + "Use Play playlist above to play it.";
                } else {
                    p.message = err;
                    p.messageActionLabel = "Try again";
                    p.messageAction = () -> {
                        p.message = null;
                        p.messageActionLabel = null;
                        loadMore(p);
                    };
                }
            }
            if (p == page) {
                adapter.notifyDataSetChanged();
                showMessage();
            }
        });
    }

    private static int countReal(Page p) {
        int n = 0;
        for (Item i : p.items) if (i.kind != HEADER && i.kind != PLAYALL) n++;
        return n;
    }

    /** Generic paging endpoint: wrapKey names the object inside each entry ("track"/"album"), or null for plain entries. */
    private void paged(String path, final String wrapKeyIn, int kind, SpotifyWebApi.Callback<Result> cb) {
        SpotifyWebApi.getJson(ctx, path, (json, err) -> {
            if (json == null) {
                cb.done(null, err);
                return;
            }
            Result r = new Result();
            try {
                String wrapKey = wrapKeyIn;
                JSONObject root = json;
                if ("artists".equals(wrapKey)) {
                    root = json.optJSONObject("artists");
                    wrapKey = null;
                }
                JSONArray arr = root == null ? null : root.optJSONArray("items");
                Set<String> seen = new HashSet<>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    if (wrapKey != null) e = e.optJSONObject(wrapKey);
                    Item it = parse(e, kind);
                    if (it == null) continue;
                    if (path.contains("recently-played") && !seen.add(it.uri)) continue;
                    r.items.add(it);
                }
                r.more = root != null && !root.isNull("next") && root.optString("next", "").length() > 0
                        && !path.contains("recently-played") && !path.contains("/me/following");
            } catch (Exception ex) {
                cb.done(null, "Couldn't read Spotify's answer (" + ex.getClass().getSimpleName() + ")");
                return;
            }
            cb.done(r, null);
        });
    }

    private void albumTracks(String id, int offset, String cover, SpotifyWebApi.Callback<Result> cb) {
        SpotifyWebApi.getJson(ctx, "/albums/" + id + "/tracks?limit=50&offset=" + offset, (json, err) -> {
            if (json == null) {
                cb.done(null, err);
                return;
            }
            Result r = new Result();
            JSONArray arr = json.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                Item it = parse(arr.optJSONObject(i), TRACK);
                if (it == null) continue;
                it.image = cover;
                r.items.add(it);
            }
            r.more = !json.isNull("next") && json.optString("next", "").length() > 0;
            cb.done(r, null);
        });
    }

    private void queue(SpotifyWebApi.Callback<Result> cb) {
        SpotifyWebApi.getJson(ctx, "/me/player/queue", (json, err) -> {
            if (json == null) {
                cb.done(null, err);
                return;
            }
            Result r = new Result();
            JSONObject now = json.optJSONObject("currently_playing");
            if (now != null) {
                r.items.add(header("Now playing"));
                Item it = parse(now, QUEUED);
                if (it != null) r.items.add(it);
            }
            JSONArray q = json.optJSONArray("queue");
            if (q != null && q.length() > 0) {
                r.items.add(header("Next up"));
                for (int i = 0; i < q.length(); i++) {
                    Item it = parse(q.optJSONObject(i), QUEUED);
                    if (it != null) r.items.add(it);
                }
            }
            cb.done(r, null);
        });
    }

    private void search(String q, SpotifyWebApi.Callback<Result> cb) {
        SpotifyWebApi.getJson(ctx, "/search?type=track,album,artist,playlist&limit=8&q=" + SpotifyWebApi.urlEncode(q),
                (json, err) -> {
                    if (json == null) {
                        cb.done(null, err);
                        return;
                    }
                    Result r = new Result();
                    addSection(r, "Songs", json.optJSONObject("tracks"), TRACK);
                    addSection(r, "Artists", json.optJSONObject("artists"), ARTIST);
                    addSection(r, "Albums", json.optJSONObject("albums"), ALBUM);
                    addSection(r, "Playlists", json.optJSONObject("playlists"), PLAYLIST);
                    cb.done(r, null);
                });
    }

    private void addSection(Result r, String name, JSONObject block, int kind) {
        JSONArray arr = block == null ? null : block.optJSONArray("items");
        List<Item> got = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Item it = parse(arr.optJSONObject(i), kind);
            if (it != null) got.add(it);
        }
        if (got.isEmpty()) return;
        r.items.add(header(name));
        r.items.addAll(got);
    }

    private static Item header(String t) {
        Item h = new Item();
        h.kind = HEADER;
        h.title = t;
        return h;
    }

    private static Item parse(JSONObject o, int kind) {
        if (o == null) return null;
        String uri = o.optString("uri", "");
        if (uri.isEmpty() || uri.startsWith("spotify:local")) return null;
        Item it = new Item();
        it.kind = kind;
        it.uri = uri;
        it.id = o.optString("id", uri.substring(uri.lastIndexOf(':') + 1));
        it.title = o.optString("name", "");
        JSONArray images = o.optJSONArray("images");
        if (images == null && o.optJSONObject("album") != null) images = o.optJSONObject("album").optJSONArray("images");
        it.image = pickImage(images);
        String type = o.optString("type", "");
        if (type.equals("artist") || kind == ARTIST) {
            it.kind = ARTIST;
            it.subtitle = "Artist";
        } else if (type.equals("playlist") || kind == PLAYLIST) {
            JSONObject owner = o.optJSONObject("owner");
            it.kind = PLAYLIST;
            it.subtitle = "Playlist" + (owner != null && owner.optString("display_name").length() > 0
                    ? " · " + owner.optString("display_name") : "");
        } else {
            StringBuilder names = new StringBuilder();
            JSONArray artists = o.optJSONArray("artists");
            for (int i = 0; artists != null && i < artists.length(); i++) {
                if (names.length() > 0) names.append(", ");
                names.append(artists.optJSONObject(i).optString("name"));
            }
            if (type.equals("album") || (kind == ALBUM && !type.equals("track"))) {
                it.kind = ALBUM;
                String year = o.optString("release_date", "");
                it.subtitle = names + (year.length() >= 4 ? " · " + year.substring(0, 4) : "");
            } else {
                it.subtitle = names.toString();
            }
        }
        return it;
    }

    /** Smallest image that is still at least ~120px wide, so lists stay light. */
    private static String pickImage(JSONArray images) {
        if (images == null || images.length() == 0) return null;
        String best = null;
        int bestW = Integer.MAX_VALUE;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            if (im == null) continue;
            int w = im.optInt("width", 0);
            if (w >= 120 && w < bestW) {
                bestW = w;
                best = im.optString("url", null);
            }
        }
        return best != null ? best : images.optJSONObject(0).optString("url", null);
    }

    // ---------- tapping ----------

    private void onRow(Item it) {
        switch (it.kind) {
            case PLAYLIST:
            case ALBUM:
            case ARTIST:
                openDetail(it);
                break;
            case PLAYALL:
                play("{\"context_uri\":\"" + it.uri + "\"}", it.title);
                break;
            case TRACK:
                playTrack(it);
                break;
            default:
                break;
        }
    }

    private boolean onRowLong(Item it) {
        if (it.kind != PLAYLIST && it.kind != ALBUM && it.kind != ARTIST) return false;
        Pins.Pin pin = new Pins.Pin();
        pin.uri = it.uri;
        pin.title = it.title;
        pin.subtitle = it.subtitle;
        pin.image = it.image;
        boolean now = Pins.toggle(ctx, pin);
        say(now ? "Pinned" : "Unpinned");
        if (page.tab && selectedTab == 0) selectTab(0);
        else adapter.notifyDataSetChanged();
        return true;
    }

    private void onAction(Item it) {
        if (it.kind == TRACK) {
            SpotifyWebApi.send(ctx, "POST", "/me/player/queue?uri=" + SpotifyWebApi.urlEncode(it.uri), null, (ok, err) -> {
                if (ok) say("Added to queue");
                else say("signin".equals(err) ? "Sign in again" : "Start playing on the Echo first");
            });
        } else {
            onRowPlayNow(it);
        }
    }

    private void onRowPlayNow(Item it) {
        play("{\"context_uri\":\"" + it.uri + "\"}", it.title);
    }

    private void playTrack(Item it) {
        String body;
        if (page.contextUri != null) {
            body = "{\"context_uri\":\"" + page.contextUri + "\",\"offset\":{\"uri\":\"" + it.uri + "\"}}";
        } else {
            // a list with no context (liked, recent, top, search): play from here on
            StringBuilder uris = new StringBuilder();
            boolean started = false;
            int n = 0;
            for (Item i : page.items) {
                if (i == it) started = true;
                if (started && i.kind == TRACK && n < 100) {
                    if (n++ > 0) uris.append(',');
                    uris.append('"').append(i.uri).append('"');
                }
            }
            body = "{\"uris\":[" + uris + "]}";
        }
        play(body, it.title, true);
    }

    private void play(String body, String what) {
        play(body, what, false);
    }

    private void play(String body, String what, boolean exact) {
        say("Starting…");
        SpotifyWebApi.playOnEcho(ctx, body, exact, (ok, err) -> {
            if (ok) say("Playing on the Echo");
            else if ("signin".equals(err)) say("Sign in again");
            else say(err.length() > 70 ? err.substring(0, 70) + "…" : err);
        });
    }

    // ---------- mini player ----------

    @Override
    public void onStateChanged() {
        PlayerState s = PlayerState.get();
        miniTitle.setText(s.hasTrack() ? s.trackName : "Nothing playing");
        miniSub.setText(s.hasTrack() ? s.artists : "Pick something to play on the Echo");
        if (s.art != null) miniArt.setImageBitmap(s.art);
        else miniArt.setImageDrawable(null);
        miniPlay.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlay.setColorFilter(Color.WHITE);
    }

    // ---------- list adapter ----------

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return page == null ? 0 : page.items.size(); }
        @Override public Object getItem(int i) { return page.items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return page.items.get(i).kind == HEADER ? 0 : 1; }
        @Override public boolean isEnabled(int i) { return page.items.get(i).kind != HEADER && page.items.get(i).kind != QUEUED; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Item it = page.items.get(pos);
            Context c = ctx;
            if (it.kind == HEADER) {
                TextView t = convert instanceof TextView ? (TextView) convert : null;
                if (t == null) {
                    t = Ui.text(c, 13, Ui.GREEN, "sans-serif-medium", 1);
                    t.setLetterSpacing(0.12f);
                    t.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 4));
                }
                t.setText(it.title.toUpperCase(java.util.Locale.US));
                return t;
            }
            Row r;
            if (convert != null && convert.getTag() instanceof Row) {
                r = (Row) convert.getTag();
            } else {
                r = new Row(c);
                r.root.setTag(r);
            }
            r.title.setText(it.title);
            r.sub.setText(it.subtitle == null ? "" : it.subtitle);
            if (it.kind == PLAYALL) {
                r.image.setImageResource(R.drawable.ic_play);
                r.image.setColorFilter(Ui.GREEN);
                r.image.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
                r.image.setTag(null);
            } else {
                r.image.clearColorFilter();
                r.image.setPadding(0, 0, 0, 0);
                Ui.load(r.image, it.image, 56);
            }
            boolean pinned = (it.kind == PLAYLIST || it.kind == ALBUM || it.kind == ARTIST) && Pins.isPinned(c, it.uri);
            r.pinned.setVisibility(pinned ? VISIBLE : GONE);
            if (it.kind == PLAYALL || it.kind == QUEUED) {
                r.action.setVisibility(GONE);
            } else {
                r.action.setVisibility(VISIBLE);
                r.action.setText(it.kind == TRACK ? "+" : "▶");
                r.action.setOnClickListener(v -> onAction(it));
            }
            r.image.setBackgroundColor(Color.argb(30, 255, 255, 255));
            return r.root;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView image;
        final TextView title, sub, action, pinned;

        Row(Context c) {
            root = new LinearLayout(c);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(Ui.dp(c, 16), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5));
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(image, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
            title = Ui.text(c, 17, Color.WHITE, "sans-serif", 1);
            sub = Ui.text(c, 13, Ui.DIM, "sans-serif", 1);
            texts.addView(title);
            texts.addView(sub);
            root.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            pinned = Ui.text(c, 12, Ui.GREEN, "sans-serif-medium", 1);
            pinned.setText("PINNED");
            pinned.setPadding(0, 0, Ui.dp(c, 8), 0);
            root.addView(pinned);
            action = Ui.text(c, 20, Color.WHITE, "sans-serif-medium", 1);
            action.setGravity(Gravity.CENTER);
            action.setBackground(Ui.round(c, Color.argb(40, 255, 255, 255), 22));
            root.addView(action, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        }
    }
}
