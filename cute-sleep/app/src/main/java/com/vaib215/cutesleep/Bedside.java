package com.vaib215.cutesleep;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.view.Window;
import android.view.WindowManager;

import java.util.Calendar;

/**
 * Everything around the clock, shared by the screen saver and the full-screen activity:
 * battery, the next alarm, and night (a dark room, or late hours on phones without a
 * light sensor), which turns the clock red and the screen down.
 */
final class Bedside implements SensorEventListener {
    private static final float DARK = 3, LIGHT = 12; // lux; apart, so it doesn't flicker

    private final Context context;
    private final Window window;
    private final Face face;
    private final Panel panel; // notifications, or null
    private final SharedPreferences prefs;
    private final SensorManager sensors;
    private final Sensor light;
    private final boolean dims;
    private boolean night;
    /** Is the clock showing night right now? (For the charge alert: buzz rather than chime.) */
    static volatile boolean dark;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) battery(i);
            else update();
        }
    };

    /** `dims`: turn the screen down at night (the clock screens; not the app's own page). */
    Bedside(Context context, Window window, Face face, Panel panel, boolean dims) {
        this.context = context;
        this.panel = panel;
        this.dims = dims;
        this.window = window;
        this.face = face;
        prefs = prefs(context);
        sensors = context.getSystemService(SensorManager.class);
        light = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        face.setFace(prefs.getInt("face", 0));
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sleep", Context.MODE_PRIVATE);
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_TIME_TICK);
        Intent sticky = context.registerReceiver(receiver, f);
        if (sticky != null) battery(sticky);
        if (light != null && redAtNight()) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        if (panel != null && prefs.getBoolean("alerts", false)) Alerts.watch(() -> panel.show(Alerts.current()));
        update();
    }

    void stop() {
        if (dims) {
            dark = false;
            Quiet.off(context);
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (sensors != null) sensors.unregisterListener(this);
        if (panel != null) Alerts.watch(null);
    }

    private boolean redAtNight() {
        return prefs.getBoolean("red", true);
    }

    private void battery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0) return;
        int pct = Math.round(100f * level / scale);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        face.setBattery(pct, charging || plugged);

        String e = "";
        if (charging && android.os.Build.VERSION.SDK_INT >= 28) {
            long ms = context.getSystemService(BatteryManager.class).computeChargeTimeRemaining();
            if (ms > 0) {
                long min = Math.max(1, Math.round(ms / 60_000.0));
                e = "full in " + (min < 60 ? min + " min" : min / 60 + " h" + (min % 60 == 0 ? "" : " " + min % 60 + " min"));
            }
        }
        face.setEta(e);

        float temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int at = prefs.getInt("alertAt", 80);
        String note = "";
        if (plugged && prefs.getBoolean("heat", true) && temp >= Watcher.HOT) {
            note = "Battery is warm (" + Math.round(temp) + " °C)";
        } else if (plugged && at > 0 && pct >= at) {
            note = at == 100 ? "Fully charged, unplug when you can" : "Charged to " + pct + "%, unplug when you can";
        }
        face.setNote(note);
    }

    private void update() {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo next = am == null ? null : am.getNextAlarmClock();
        long in = next == null ? Long.MAX_VALUE : next.getTriggerTime() - System.currentTimeMillis();
        face.setAlarm(in < 24 * 3600_000L
                ? DateFormat.getTimeFormat(context).format(next.getTriggerTime()) : "");
        if (light == null) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            setNight(redAtNight() && (hour >= 22 || hour < 7));
        }
        face.invalidate();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float lux = e.values[0];
        if (!night && lux < DARK) setNight(true);
        else if (night && lux > LIGHT) setNight(false);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    private void setNight(boolean on) {
        night = on;
        face.setNight(on);
        if (panel != null) panel.setNight(on);
        if (!dims) return;
        dark = on;
        if (on) Quiet.on(context);
        else Quiet.off(context);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        window.setAttributes(lp);
    }

    void faceChanged(int f) {
        prefs.edit().putInt("face", f).apply();
    }
}
