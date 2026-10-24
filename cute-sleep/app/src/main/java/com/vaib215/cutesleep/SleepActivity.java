package com.vaib215.cutesleep;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

/**
 * The bedside clock, full screen and over the lock screen, until tapped. Opened by the
 * widget or the app, or by {@link Watcher} when the phone charges with the screen off; then
 * it also goes away when the charger comes out.
 */
public class SleepActivity extends Activity implements Face.Listener {
    static final String EXTRA_AUTO = "auto";
    private static boolean visible;
    private static long hiddenAt;

    private Bedside bedside;
    private boolean auto;

    private final BroadcastReceiver unplugged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (auto) finish();
        }
    };

    /** Was the clock on screen a moment ago? (Then a screen going off was the user's doing.) */
    static boolean justShown() {
        return visible || SystemClock.elapsedRealtime() - hiddenAt < 2000;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        Stand stand = new Stand(this);
        stand.face.listener = this;
        setContentView(stand);
        bedside = new Bedside(this, getWindow(), stand.face, stand.panel, true);
        registerReceiver(unplugged, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        auto = intent.getBooleanExtra(EXTRA_AUTO, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        Watcher.shown(this);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        bedside.start();
    }

    @Override
    protected void onPause() {
        visible = false;
        hiddenAt = SystemClock.elapsedRealtime();
        bedside.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(unplugged);
        super.onDestroy();
    }

    @Override
    public void onTap() {
        finish();
    }

    @Override
    public void onFaceChanged(int face) {
        bedside.faceChanged(face);
    }
}
