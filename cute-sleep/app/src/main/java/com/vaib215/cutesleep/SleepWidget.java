package com.vaib215.cutesleep;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.RemoteViews;

/**
 * Home-screen widgets: the one-tap Sleep button, digital clocks and an analog clock. The
 * clocks keep time by themselves; a tap on any widget brings up the bedside clock.
 */
public class SleepWidget extends AppWidgetProvider {
    /** This widget's look at its current size. */
    protected RemoteViews views(Context c, Bundle size) {
        return new RemoteViews(c.getPackageName(), R.layout.widget);
    }

    /** Clocks sit on a dark card, on black, or straight on the wallpaper (0 = none). */
    static final int[] BACKGROUNDS = {R.drawable.widget_card, R.drawable.widget_black, 0};
    static final String[] BACKGROUND_NAMES = {"Card", "Black", "See-through"};

    static int background(Context c) {
        android.content.SharedPreferences p = Bedside.prefs(c);
        return p.getInt("widgetBg", p.getBoolean("clearWidgets", false) ? 2 : 0);
    }

    /** Whether this widget takes the background setting. */
    protected boolean hasCard() { return false; }

    @Override
    public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(c, manager, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager manager, int id, Bundle options) {
        update(c, manager, id); // resized
    }

    private void update(Context c, AppWidgetManager manager, int id) {
        RemoteViews v = views(c, manager.getAppWidgetOptions(id));
        if (hasCard()) v.setInt(R.id.widget, "setBackgroundResource", BACKGROUNDS[background(c)]);
        Intent open = new Intent(c, SleepActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(id, v);
    }

    /** Redraws every Cute Sleep widget, e.g. after the background setting changes. */
    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (SleepWidget w : new SleepWidget[]{new SleepWidget(), new Digital(), new DigitalStacked(), new Analog()}) {
            w.onUpdate(c, m, m.getAppWidgetIds(new ComponentName(c, w.getClass())));
        }
    }

    /**
     * The digital clock on one line. The text sizes itself to the widget (the launcher's own
     * idea of a widget's size can't be trusted), so it fills whatever space it's given.
     */
    public static class Digital extends SleepWidget {
        protected int layout() { return R.layout.widget_digital_wide; }
        protected String between() { return ":"; }

        @Override
        protected boolean hasCard() { return true; }

        @Override
        protected RemoteViews views(Context c, Bundle o) {
            RemoteViews v = new RemoteViews(c.getPackageName(), layout());
            v.setCharSequence(R.id.clock, "setFormat24Hour", coloured("HH", between()));
            v.setCharSequence(R.id.clock, "setFormat12Hour", coloured("h", between()));
            return v;
        }

        /** Hours peach (the text colour), the separator grey, minutes coral. */
        private static CharSequence coloured(String hours, String between) {
            SpannableStringBuilder f = new SpannableStringBuilder(hours + between + "mm");
            int m = hours.length() + between.length();
            f.setSpan(new ForegroundColorSpan(0xFF8E8E93), hours.length(), m, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            f.setSpan(new ForegroundColorSpan(0xFFFF8A80), m, f.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return f;
        }
    }

    /** The digital clock with hours over minutes, for tall widgets. */
    public static class DigitalStacked extends Digital {
        @Override
        protected int layout() { return R.layout.widget_digital_tall; }

        @Override
        protected String between() { return "\n"; }
    }

    /** The analog clock, drawn like the bedside clock's analog face. */
    public static class Analog extends SleepWidget {
        @Override
        protected boolean hasCard() { return true; }

        @Override
        protected RemoteViews views(Context c, Bundle o) {
            return new RemoteViews(c.getPackageName(), R.layout.widget_analog);
        }
    }
}
