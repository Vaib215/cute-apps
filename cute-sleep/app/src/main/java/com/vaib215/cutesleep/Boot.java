package com.vaib215.cutesleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an update: watch for the charger again, and redraw the widgets. */
public class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Watcher.sync(c);
        SleepWidget.refreshAll(c);
    }
}
