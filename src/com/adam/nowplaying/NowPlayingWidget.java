package com.adam.nowplaying;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.RemoteViews;

/** Home screen widget: art + title + transport controls; tapping anywhere else opens Now Playing. */
public class NowPlayingWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        update(ctx);
        PlayerService.start(ctx);
    }

    static void update(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NowPlayingWidget.class));
        if (ids == null || ids.length == 0) return;

        PlayerState s = PlayerState.get();
        boolean track = s.connected && s.hasTrack();
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);

        if (track) {
            rv.setTextViewText(R.id.w_label, "NOW PLAYING");
            rv.setTextViewText(R.id.w_title, s.trackName);
            rv.setTextViewText(R.id.w_artist, s.artists);
        } else {
            rv.setTextViewText(R.id.w_label, s.deviceName.toUpperCase(java.util.Locale.getDefault()));
            rv.setTextViewText(R.id.w_title, !s.connected ? "Starting…" : s.loggedIn ? "Ready for Spotify" : "Waiting for Spotify");
            rv.setTextViewText(R.id.w_artist, "Pick this speaker in Spotify");
        }
        if (track && s.hasArt()) {
            rv.setImageViewBitmap(R.id.w_art, rounded(PlayerService.scaled(s.art, 200), 22));
        } else {
            rv.setImageViewResource(R.id.w_art, R.drawable.widget_art_placeholder);
        }
        rv.setImageViewResource(R.id.w_play, s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        int alpha = track ? 255 : 90;
        rv.setInt(R.id.w_prev, "setImageAlpha", alpha);
        rv.setInt(R.id.w_play, "setImageAlpha", alpha);
        rv.setInt(R.id.w_next, "setImageAlpha", alpha);

        rv.setOnClickPendingIntent(R.id.w_root, PlayerService.openAppIntent(ctx));
        rv.setOnClickPendingIntent(R.id.w_prev, PlayerService.actionIntent(ctx, PlayerService.ACTION_PREV, 11));
        rv.setOnClickPendingIntent(R.id.w_play, PlayerService.actionIntent(ctx, PlayerService.ACTION_TOGGLE, 12));
        rv.setOnClickPendingIntent(R.id.w_next, PlayerService.actionIntent(ctx, PlayerService.ACTION_NEXT, 13));
        mgr.updateAppWidget(ids, rv);
    }

    private static Bitmap rounded(Bitmap src, float radius) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), radius, radius, p);
        return out;
    }
}
