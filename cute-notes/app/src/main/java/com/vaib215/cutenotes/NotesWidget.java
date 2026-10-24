package com.vaib215.cutenotes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.io.File;
import java.util.List;

/** The latest note on the home screen, and a Write button for a new one. */
public class NotesWidget extends AppWidgetProvider {
    /** Backgrounds, chosen in a note's options: a dark card, black, or none. */
    static final String[] BACKGROUND_NAMES = {"Card", "Black", "See-through"};
    private static final int[] BACKGROUNDS = {R.drawable.widget_card, R.drawable.widget_black, 0};

    @Override
    public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, views(c));
    }

    /** Refreshes any widgets on the home screen; called whenever a note is saved or deleted. */
    static void update(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, NotesWidget.class));
        if (ids.length > 0) m.updateAppWidget(ids, views(c));
    }

    private static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_notes);
        int bg = Math.max(0, Math.min(BACKGROUNDS.length - 1, Ui.prefs(c).getInt("widgetBg", 0)));
        v.setInt(R.id.widget, "setBackgroundResource", BACKGROUNDS[bg]);
        List<File> notes = Notes.all(c);
        Intent open;
        if (notes.isEmpty()) {
            v.setTextViewText(R.id.title, "No notes yet");
            v.setTextViewText(R.id.preview, "Tap Write and write big with your finger.");
            open = new Intent(c, NotesActivity.class);
        } else {
            File latest = notes.get(0);
            String[] tp = Notes.titleAndPreview(Notes.read(latest));
            v.setTextViewText(R.id.title, tp[0]);
            v.setTextViewText(R.id.preview, tp[1]);
            open = new Intent(c, WriteActivity.class).putExtra(WriteActivity.EXTRA_NOTE, latest.getPath());
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        v.setOnClickPendingIntent(R.id.widget, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent write = new Intent(c, WriteActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        v.setOnClickPendingIntent(R.id.write, PendingIntent.getActivity(c, 1, write,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }
}
