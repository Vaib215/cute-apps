package com.vaib215.cutereads;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;

/**
 * "Continue reading": the book you're in, with its cover and how far along you are. One
 * layout fits every size, because launchers don't reliably tell a widget how big it is.
 */
public class ReadingWidget extends AppWidgetProvider {
    /** Widget backgrounds, set in the reader's options: a dark card, black, or none. */
    static final String[] BACKGROUND_NAMES = {"Card", "Black", "See-through"};

    /** The background for the chosen setting; `card` is this widget's own card (0 = none). */
    static int background(Context c, int card) {
        int which = c.getSharedPreferences("reader", Context.MODE_PRIVATE).getInt("widgetBg", 0);
        return which == 1 ? R.drawable.widget_black : which == 2 ? 0 : card;
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, views(c));
    }

    /** Refreshes any widgets on the home screen; called when you stop reading or a book goes. */
    static void update(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        new ReadingWidget().onUpdate(c, m, m.getAppWidgetIds(new ComponentName(c, ReadingWidget.class)));
    }

    private static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_reading);
        v.setInt(R.id.widget, "setBackgroundResource", background(c, R.drawable.widget_card));
        SharedPreferences prefs = c.getSharedPreferences("reader", Context.MODE_PRIVATE);
        File book = new File(Library.dir(c), prefs.getString("book", ""));
        Intent open = new Intent(c, ReaderActivity.class);
        if (!book.isFile()) {
            v.setTextViewText(R.id.title, "Nothing open yet");
            v.setViewVisibility(R.id.cover, View.GONE);
            v.setViewVisibility(R.id.progress, View.GONE);
            v.setTextViewText(R.id.percent, "Tap to choose");
            open = new Intent(c, LibraryActivity.class);
        } else {
            // Sharp enough for a big widget, small enough to send to the home screen.
            int width = 300;
            Bitmap cover = Library.cover(book, width);
            if (cover == null) cover = Library.drawnCover(c, book, width);
            if (cover.getWidth() > width) {
                cover = Bitmap.createScaledBitmap(cover, width, cover.getHeight() * width / cover.getWidth(), true);
            }
            v.setImageViewBitmap(R.id.cover, cover.copy(Bitmap.Config.RGB_565, false));
            v.setViewVisibility(R.id.cover, View.VISIBLE);
            v.setTextViewText(R.id.title, Library.title(book));
            int pct = prefs.getInt("pct:" + book.getName(), 0);
            v.setProgressBar(R.id.progress, 100, pct, false);
            v.setViewVisibility(R.id.progress, View.VISIBLE);
            v.setTextViewText(R.id.percent, pct + "%");
            v.setViewVisibility(R.id.percent, View.VISIBLE);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.widget, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }
}
