package com.vaib215.cutenotes;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Day/night colours and small view helpers shared by both screens. */
final class Ui {
    /** Fonts for the note; the handwriting ones run small, so they're drawn a little larger. */
    static final String[] FONTS = {"System", "Literata", "Atkinson", "Caveat", "Patrick Hand"};
    private static final String[] FONT_FILES = {null, "literata", "atkinson", "caveat", "patrickhand"};
    private static final float[] FONT_SCALE = {1f, 1f, 1f, 1.3f, 1.1f};
    private static final Typeface[] loaded = new Typeface[FONTS.length];

    static Typeface font(Context c, int i) {
        if (i < 0 || i >= FONTS.length || FONT_FILES[i] == null) return Typeface.DEFAULT;
        if (loaded[i] == null) {
            try {
                loaded[i] = Typeface.createFromAsset(c.getAssets(), "fonts/" + FONT_FILES[i] + ".ttf");
            } catch (Exception e) {
                loaded[i] = Typeface.DEFAULT;
            }
        }
        return loaded[i];
    }

    static float fontScale(int i) {
        return i >= 0 && i < FONT_SCALE.length ? FONT_SCALE[i] : 1f;
    }

    /** The pen, the cursor and the heart in the icon. */
    static final int INK = 0xFFFF6B6B;

    final boolean day;
    final int bg, fg, dim, sheet, line;

    private Ui(boolean day) {
        this.day = day;
        bg = day ? 0xFFF6F4EF : Color.BLACK;
        fg = day ? 0xFF1E1E1E : 0xFFE6E6E6;
        dim = day ? 0xFF8A8580 : 0xFF8A8A8A;
        sheet = day ? 0xFFFFFFFF : 0xFF1C1C1E;
        line = day ? 0xFFD8D5CF : 0xFF3A3A3C;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    static Ui from(Context c) {
        return new Ui(prefs(c).getBoolean("day", false));
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Status and navigation bars in the page colour, with dark icons by day. */
    void bars(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        w.getDecorView().setBackgroundColor(bg);
        w.getDecorView().setSystemUiVisibility(day
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }

    int dialogTheme() {
        return day ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** A rounded pill; the selected one is filled. */
    TextView chip(Context c, String s, boolean selected, View.OnClickListener click) {
        TextView t = text(c, s, 15, selected ? sheet : fg);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 20));
        if (selected) d.setColor(fg);
        else d.setStroke(dp(c, 1), line);
        t.setBackground(d);
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        t.setSingleLine();
        t.setOnClickListener(click);
        return t;
    }

    /** A plain icon you tap, 48dp square. */
    ImageView iconButton(Context c, int res, View.OnClickListener click) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(fg));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        v.setOnClickListener(click);
        return v;
    }

    /** A big key in the bar under the writing space; the selected one is filled. */
    ImageView key(Context c, int res, boolean selected, View.OnClickListener click) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(selected ? sheet : fg));
        v.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 16));
        d.setColor(selected ? fg : sheet);
        v.setBackground(d);
        v.setOnClickListener(click);
        return v;
    }

    /** A wide rounded pill: icon then label, left-aligned; the selected one is filled. */
    LinearLayout pill(Context c, int res, String label, boolean selected, View.OnClickListener click) {
        int color = selected ? sheet : fg;
        LinearLayout p = new LinearLayout(c);
        p.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        p.setPadding(dp(c, 12), 0, dp(c, 2), 0);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 23));
        if (selected) d.setColor(fg);
        else d.setStroke(dp(c, 1), line);
        p.setBackground(d);
        ImageView icon = new ImageView(c);
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(color));
        p.addView(icon, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        TextView t = text(c, label, 13, color);
        t.setSingleLine();
        t.setPadding(dp(c, 6), 0, 0, 0);
        p.addView(t);
        p.setOnClickListener(click);
        return p;
    }

    void tint(SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(fg));
        bar.setThumbTintList(ColorStateList.valueOf(fg));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(line));
    }

    /** Runs `action` on tap, and again and again while held, like a keyboard's delete key. */
    static void repeat(View v, Runnable action) {
        Handler h = new Handler(Looper.getMainLooper());
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            action.run();
            h.postDelayed(tick[0], 70);
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    action.run();
                    h.postDelayed(tick[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    h.removeCallbacks(tick[0]);
                    return true;
            }
            return true;
        });
    }
}
