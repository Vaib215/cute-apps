package com.vaib215.cutereads;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After the app is updated, redraw its home-screen widgets with the new version. */
public class Updated extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ReadingWidget.update(c);
        PageWidget.update(c);
    }
}
