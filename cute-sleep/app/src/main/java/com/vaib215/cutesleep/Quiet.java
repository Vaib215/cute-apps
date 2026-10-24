package com.vaib215.cutesleep;

import android.app.NotificationManager;
import android.content.Context;

/**
 * Do Not Disturb (alarms only) while the clock is up in a dark room. Only ever undoes what it
 * did itself: if Do Not Disturb was already on, it's left alone. The flag is saved, so it's
 * still undone if the app was closed in between.
 */
final class Quiet {
    static boolean allowed(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    static void on(Context c) {
        if (!allowed(c) || !Bedside.prefs(c).getBoolean("quiet", true)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) return;
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
        Bedside.prefs(c).edit().putBoolean("silenced", true).apply();
    }

    static void off(Context c) {
        if (!Bedside.prefs(c).getBoolean("silenced", false)) return;
        Bedside.prefs(c).edit().putBoolean("silenced", false).apply();
        if (!allowed(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        }
    }
}
