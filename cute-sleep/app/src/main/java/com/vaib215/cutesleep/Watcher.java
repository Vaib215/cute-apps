package com.vaib215.cutesleep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The automatic way in, for phones whose screen saver doesn't work (ColorOS turns it off).
 * While the phone charges, a screen going off brings the clock up over the lock screen, the
 * way an alarm does. Android only lets this watch in the background with a notification
 * showing, so there's a quiet one.
 */
public class Watcher extends Service {
    private static final String READY = "ready", CLOCK = "clock";
    private static final int READY_ID = 1, CLOCK_ID = 2;
    private static final long DELAY = 1500; // after the screen goes off, so a quick off-on is left alone
    static final float HOT = 40, COOL = 38; // °C: warn above HOT, and again only after cooling below COOL
    private static final String CHIME = "chime", BUZZ = "buzz";
    private static final int ALERT_ID = 3;

    // This charge: did it start below the alert level, and have we told them yet?
    private boolean plugged, startedBelow, alerted, warned;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable show = this::show;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                battery(i);
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // Turned off with the clock up: they want the screen off, so leave it.
                if (!SleepActivity.justShown()) later();
            } else if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                if (!getSystemService(PowerManager.class).isInteractive()) later();
            } else {
                handler.removeCallbacks(show);
            }
        }
    };

    /** Starts or stops watching to match the setting. */
    static void sync(Context c) {
        Intent i = new Intent(c, Watcher.class);
        if (Bedside.prefs(c).getBoolean("auto", true)) c.startForegroundService(i);
        else c.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ready = new NotificationChannel(READY, "Waiting for the charger", NotificationManager.IMPORTANCE_MIN);
        ready.setShowBadge(false);
        nm.createNotificationChannel(ready);
        NotificationChannel clock = new NotificationChannel(CLOCK, "Bedside clock", NotificationManager.IMPORTANCE_HIGH);
        clock.setSound(null, null);
        clock.enableVibration(false);
        clock.setShowBadge(false);
        nm.createNotificationChannel(clock);
        NotificationChannel chime = new NotificationChannel(CHIME, "Charge and heat alerts", NotificationManager.IMPORTANCE_HIGH);
        chime.setShowBadge(false);
        nm.createNotificationChannel(chime);
        NotificationChannel buzz = new NotificationChannel(BUZZ, "Charge and heat alerts at night", NotificationManager.IMPORTANCE_HIGH);
        buzz.setSound(null, null);
        buzz.enableVibration(true);
        buzz.setShowBadge(false);
        nm.createNotificationChannel(buzz);

        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        startForeground(READY_ID, new Notification.Builder(this, READY)
                .setSmallIcon(R.drawable.ic_moon_small)
                .setContentTitle("Cute Sleep is ready")
                .setContentText("The clock comes up when the phone charges with the screen off")
                .setContentIntent(open)
                .setOngoing(true)
                .build());

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(receiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(show);
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Chimes once a charge reaches the chosen level, and if the battery runs hot while charging. */
    private void battery(Intent i) {
        boolean now = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0) return;
        int pct = Math.round(100f * level / scale);
        int at = Bedside.prefs(this).getInt("alertAt", 80);
        if (now && !plugged) { // just plugged in
            startedBelow = pct < at;
            alerted = warned = false;
        }
        plugged = now;
        if (!now) {
            getSystemService(NotificationManager.class).cancel(ALERT_ID);
            return;
        }
        if (at > 0 && startedBelow && !alerted && pct >= at) {
            alerted = true;
            alert(at == 100 ? "Fully charged" : "Charged to " + pct + "%",
                    "Unplug when you can; a battery kept below full lasts longer.");
        }
        float temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        if (Bedside.prefs(this).getBoolean("heat", true)) {
            if (!warned && temp >= HOT) {
                warned = true;
                alert("Battery is warm: " + Math.round(temp) + " °C",
                        "Unplug it, or take it out of its case or off the bed, to let it cool.");
            } else if (warned && temp < COOL) {
                warned = false;
            }
        }
    }

    /** A chime; at night with the clock up, only a buzz (the clock shows it too). */
    private void alert(String title, String text) {
        boolean night = Bedside.dark;
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, night ? BUZZ : CHIME)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(ALERT_ID, n);
    }

    private void later() {
        handler.removeCallbacks(show);
        handler.postDelayed(show, DELAY);
    }

    private boolean charging() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /**
     * Brings the clock up. Apps can't open screens from the background, but a "full-screen"
     * notification can, while the screen is off: it's how alarms ring.
     */
    private void show() {
        if (!charging() || getSystemService(PowerManager.class).isInteractive()) return;
        Intent clock = new Intent(this, SleepActivity.class)
                .putExtra(SleepActivity.EXTRA_AUTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 1, clock,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CLOCK)
                .setSmallIcon(R.drawable.ic_moon_small)
                .setContentTitle("Bedside clock")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .build();
        getSystemService(NotificationManager.class).notify(CLOCK_ID, n);
    }

    /** The clock is up: the notification that opened it has done its job. */
    static void shown(Context c) {
        c.getSystemService(NotificationManager.class).cancel(CLOCK_ID);
    }
}
