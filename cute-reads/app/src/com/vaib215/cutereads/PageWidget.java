package com.vaib215.cutereads;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.RemoteViews;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The page you're on, as a widget: the reader's own page, in your reading font and colours,
 * with ‹ › to turn pages. The text sizes itself to fit the widget, whatever its shape
 * (launchers don't reliably tell widgets their size). Turning here moves your place in the
 * book, so the reader carries on from the widget's page.
 */
public class PageWidget extends AppWidgetProvider {
    private static final String NEXT = "com.vaib215.cutereads.NEXT_PAGE", PREV = "com.vaib215.cutereads.PREV_PAGE";
    private static final int[] LAYOUTS = {R.layout.widget_page_0, R.layout.widget_page_1,
            R.layout.widget_page_2, R.layout.widget_page_3, R.layout.widget_page_4};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c, id));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (NEXT.equals(a) || PREV.equals(a)) turn(c, NEXT.equals(a) ? 1 : -1);
        else super.onReceive(c, i);
    }

    /** Refreshes every page widget; called when you stop reading. */
    static void update(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        new PageWidget().onUpdate(c, m, m.getAppWidgetIds(new ComponentName(c, PageWidget.class)));
    }

    // ---- The reader's pages ----

    private static File pagesFile(File book) {
        return new File(book.getParentFile(), Library.name(book) + ".pages");
    }

    /** Remembers where the reader's pages start, so the widget shows the same pages. */
    static void savePages(Context c, File book, int[] starts) {
        if (book == null || starts == null || starts.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int s : starts) sb.append(s).append(',');
        try {
            Files.write(pagesFile(book).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static int[] loadPages(File book) {
        try {
            String[] parts = new String(Files.readAllBytes(pagesFile(book).toPath()), StandardCharsets.UTF_8).split(",");
            int n = 0;
            int[] out = new int[parts.length];
            for (String p : parts) if (!p.isEmpty()) out[n++] = Integer.parseInt(p.trim());
            return java.util.Arrays.copyOf(out, n);
        } catch (Exception e) {
            return new int[0];
        }
    }

    /** The page (0-based) holding char offset `at`. */
    private static int pageOf(int[] starts, int at) {
        int page = 0;
        while (page + 1 < starts.length && starts[page + 1] <= at) page++;
        return page;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    private static File book(Context c) {
        return new File(Library.dir(c), prefs(c).getString("book", ""));
    }

    // ---- Turning and showing ----

    private static void turn(Context c, int delta) {
        File book = book(c);
        int[] starts = loadPages(book);
        if (!book.isFile() || starts.length == 0) return;
        SharedPreferences prefs = prefs(c);
        int page = Math.max(0, Math.min(starts.length - 1, pageOf(starts, prefs.getInt("pos:" + book.getName(), 0)) + delta));
        prefs.edit()
                .putInt("pos:" + book.getName(), starts[page])
                .putInt("pct:" + book.getName(), starts.length <= 1 ? 100 : Math.round(100f * page / (starts.length - 1)))
                .apply();
        update(c);
        ReadingWidget.update(c); // its progress moves too
    }

    private static RemoteViews views(Context c, int id) {
        SharedPreferences prefs = prefs(c);
        int font = Math.max(0, Math.min(LAYOUTS.length - 1, prefs.getInt("font", 0)));
        // Day colours, unless the widget is set to black (dark text wouldn't show on it).
        boolean day = prefs.getBoolean("day", false) && prefs.getInt("widgetBg", 0) != 1;
        RemoteViews v = new RemoteViews(c.getPackageName(), LAYOUTS[font]);
        int fg = day ? Ui.DAY_FG : Ui.NIGHT_FG, dim = day ? 0xFF8A8580 : 0xFF8A8A8A;
        v.setInt(R.id.widget, "setBackgroundResource",
                ReadingWidget.background(c, day ? R.drawable.widget_page_day : R.drawable.widget_page_night));
        v.setTextColor(R.id.text, fg);
        v.setTextColor(R.id.where, dim);
        v.setInt(R.id.prev, "setColorFilter", dim);
        v.setInt(R.id.next, "setColorFilter", dim);

        File book = book(c);
        int[] starts = book.isFile() ? loadPages(book) : new int[0];
        CharSequence text = starts.length == 0 ? "" : BookText.plain(c, book, prefs.getInt("size", 18));
        if (text.length() == 0) {
            v.setTextViewText(R.id.text, book.isFile()
                    ? "Open this book in Cute Reads once, and its pages show here."
                    : "Open a book in Cute Reads and its page shows here.");
            v.setTextViewText(R.id.where, "");
        } else {
            int page = pageOf(starts, prefs.getInt("pos:" + book.getName(), 0));
            int start = Math.min(starts[page], text.length());
            int end = page + 1 < starts.length ? Math.min(starts[page + 1], text.length()) : text.length();
            SpannableStringBuilder shown = new SpannableStringBuilder(text, start, end);
            while (shown.length() > 0 && shown.charAt(0) == '\n') shown.delete(0, 1);
            for (int i = 0; i < shown.length(); i++) {
                if (shown.charAt(i) == 0xFFFC) shown.replace(i, i + 1, " "); // a picture: the reader shows it
            }
            // The gaps between paragraphs are a fixed size in the reader; here they must shrink
            // with the text, or a long page won't fit however small the text gets.
            for (AbsoluteSizeSpan gap : shown.getSpans(0, shown.length(), AbsoluteSizeSpan.class)) {
                int a = shown.getSpanStart(gap), b = shown.getSpanEnd(gap);
                shown.removeSpan(gap);
                shown.setSpan(new RelativeSizeSpan(0.6f), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            v.setTextViewText(R.id.text, shown);
            v.setTextViewText(R.id.where, (page + 1) + " / " + starts.length);
        }

        Intent open = new Intent(c, ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent read = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.text, read);
        v.setOnClickPendingIntent(R.id.where, read);
        v.setOnClickPendingIntent(R.id.prev, button(c, PREV));
        v.setOnClickPendingIntent(R.id.next, button(c, NEXT));
        return v;
    }

    private static PendingIntent button(Context c, String action) {
        Intent i = new Intent(c, PageWidget.class).setAction(action);
        return PendingIntent.getBroadcast(c, NEXT.equals(action) ? 1 : 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
