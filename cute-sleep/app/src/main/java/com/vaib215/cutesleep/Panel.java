package com.vaib215.cutesleep;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Notifications beside the clock: newest first, each with a cross to dismiss it. Below the
 * clock when upright, to its right when sideways (see {@link Stand}).
 */
final class Panel extends LinearLayout {
    private static final int MAX = 4;
    private final float dp;
    private final Typeface bold, regular;
    private boolean night;
    private List<Alerts.Alert> alerts;

    Panel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        dp = c.getResources().getDisplayMetrics().density;
        bold = Face.font(c, 550);
        regular = Face.font(c, 400);
        int p = dp(16);
        setPadding(p, dp(8), p, dp(8));
    }

    private int dp(float v) { return Math.round(v * dp); }

    void setNight(boolean on) {
        if (night == on) return;
        night = on;
        if (alerts != null) show(alerts);
    }

    boolean isEmpty() { return alerts == null || alerts.isEmpty(); }

    void show(List<Alerts.Alert> list) {
        alerts = list;
        removeAllViews();
        int fg = night ? Face.NIGHT : 0xFFE6E6E6, dim = night ? Face.NIGHT_DIM : 0xFF8E8E93;
        int card = night ? 0xFF140404 : 0xFF1C1C1E;
        for (int i = 0; i < Math.min(MAX, list.size()); i++) {
            Alerts.Alert a = list.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(card);
            bg.setCornerRadius(dp(18));
            row.setBackground(bg);
            row.setPadding(dp(14), dp(10), dp(4), dp(10));

            row.addView(icon(a.packageName, fg), new LayoutParams(dp(28), dp(28)));

            LinearLayout words = new LinearLayout(getContext());
            words.setOrientation(VERTICAL);
            words.setPadding(dp(12), 0, dp(4), 0);
            if (!a.title.isEmpty()) words.addView(text(a.title, 15, fg, bold, 1));
            if (!a.text.isEmpty()) words.addView(text(a.text, 13.5f, dim, regular, 2));
            row.addView(words, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            ImageView close = new ImageView(getContext());
            close.setImageResource(R.drawable.ic_close);
            close.setImageTintList(ColorStateList.valueOf(dim));
            close.setScaleType(ImageView.ScaleType.CENTER);
            close.setContentDescription("Dismiss");
            close.setOnClickListener(v -> Alerts.dismiss(a.key));
            row.addView(close, new LayoutParams(dp(44), dp(44)));

            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (getChildCount() > 0) lp.topMargin = dp(8);
            addView(row, lp);
        }
        if (list.size() > MAX) {
            TextView more = text("+" + (list.size() - MAX) + " more", 13, dim, regular, 1);
            more.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            addView(more, lp);
        }
        setVisibility(list.isEmpty() ? GONE : VISIBLE);
    }

    /**
     * The app's own icon (deep red at night); for system notifications, which have no app
     * to show, a bell. (Their small icons don't reliably tint: some come out as solid squares.)
     */
    private ImageView icon(String pkg, int fg) {
        ImageView v = new ImageView(getContext());
        PackageManager pm = getContext().getPackageManager();
        Drawable d = null;
        if (pm.getLaunchIntentForPackage(pkg) != null) {
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (d != null) {
            v.setImageDrawable(d);
            if (night) v.setColorFilter(new PorterDuffColorFilter(Face.NIGHT, PorterDuff.Mode.MULTIPLY));
        } else {
            v.setImageResource(R.drawable.ic_bell);
            v.setImageTintList(ColorStateList.valueOf(fg));
            v.setPadding(dp(3), dp(3), dp(3), dp(3));
        }
        return v;
    }

    private TextView text(String s, float sp, int color, Typeface face, int lines) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
