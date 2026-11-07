package com.adam.nowplaying;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        PlayerService.start(ctx);
        Timers.init(ctx);
        Timers.scheduleAlarm(ctx);
    }
}
