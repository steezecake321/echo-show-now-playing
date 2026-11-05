package com.adam.nowplaying;

import android.content.Intent;
import android.service.dreams.DreamService;

/** Screensaver: Rusty-style clock when idle, the player while music is loaded. Tap outside the controls to open the app. */
public class NowPlayingDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        PlayerService.dreaming = true;
        PlayerService.start(this);
        setContentView(new NowPlayingView(this, () -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            wakeUp();
        }));
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PlayerService.dreaming = false;
    }
}
