package com.vaib215.cutesleep;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the phone's notifications, for showing beside the clock, and dismisses them. Needs
 * the user to switch on Notification access for Cute Sleep.
 */
public class Alerts extends NotificationListenerService {
    /** One notification, as shown on the clock. */
    static final class Alert {
        final String key, packageName, title, text;
        final android.graphics.drawable.Icon icon;
        final long when;

        Alert(String key, String packageName, String title, String text, android.graphics.drawable.Icon icon, long when) {
            this.key = key;
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.icon = icon;
            this.when = when;
        }
    }

    private static Alerts running;
    private static Runnable watcher;

    /** Has the user let Cute Sleep read notifications? */
    static boolean allowed(Context c) {
        ComponentName me = new ComponentName(c, Alerts.class);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(me);
        }
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return list != null && list.contains(me.flattenToString());
        } catch (Exception e) {
            return false;
        }
    }

    /** `r` runs (on the main thread) whenever the notifications change; null to stop. */
    static void watch(Runnable r) {
        watcher = r;
        if (r != null) r.run();
    }

    /** Newest first; only ones that can be dismissed, and not this app's own. */
    static List<Alert> current() {
        List<Alert> out = new ArrayList<>();
        Alerts a = running;
        if (a == null) return out;
        StatusBarNotification[] all;
        try {
            all = a.getActiveNotifications();
        } catch (Exception e) {
            return out;
        }
        if (all == null) return out;
        for (StatusBarNotification s : all) {
            Notification n = s.getNotification();
            if (!s.isClearable() || s.isOngoing() || s.getPackageName().equals(a.getPackageName())) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if (n.visibility == Notification.VISIBILITY_SECRET) continue;
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) continue;
            out.add(new Alert(s.getKey(), s.getPackageName(), title == null ? "" : title.toString(),
                    text == null ? "" : text.toString(), n.getSmallIcon(), s.getPostTime()));
        }
        out.sort((x, y) -> Long.compare(y.when, x.when));
        return out;
    }

    static void dismiss(String key) {
        Alerts a = running;
        if (a != null) a.cancelNotification(key);
    }

    private static void changed() {
        Runnable r = watcher;
        if (r != null) r.run();
    }

    @Override
    public void onListenerConnected() {
        running = this;
        changed();
    }

    @Override
    public void onListenerDisconnected() {
        running = null;
        changed();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification s) {
        changed();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification s) {
        changed();
    }
}
