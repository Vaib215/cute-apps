package com.vaib215.cutereads;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Colours for day/night + warmth, fonts, and small view helpers shared by both screens. */
final class Ui {
    static final String[] FONTS = {"Literata", "Charis", "Merriweather", "Atkinson", "System"};

    static final int DAY_FG = 0xFF1E1E1E, NIGHT_FG = 0xFFE6E6E6;

    final boolean day;
    final int bg, fg, dim, sheet, line;

    private Ui(boolean day, float warmth) {
        this.day = day;
        bg = warm(day ? 0xFFF6F4EF : Color.BLACK, warmth);
        fg = warm(day ? DAY_FG : NIGHT_FG, warmth);
        dim = warm(day ? 0xFF8A8580 : 0xFF8A8A8A, warmth);
        sheet = warm(day ? 0xFFFFFFFF : 0xFF1C1C1E, warmth);
        line = warm(day ? 0xFFD8D5CF : 0xFF3A3A3C, warmth);
    }

    static Ui from(SharedPreferences prefs) {
        return new Ui(prefs.getBoolean("day", false), prefs.getInt("warm", 0) / 100f);
    }

    /** Like Night Shift: keep red, cut green a little and blue a lot. */
    static int warm(int color, float w) {
        return Color.rgb(Color.red(color),
                Math.round(Color.green(color) * (1 - 0.18f * w)),
                Math.round(Color.blue(color) * (1 - 0.5f * w)));
    }

    static final int[] FONT_RES = {R.font.literata, R.font.charis, R.font.merriweather, R.font.atkinson};

    static Typeface font(Context c, int i) {
        if (i < 0 || i >= FONT_RES.length) return Typeface.DEFAULT;
        try {
            return c.getResources().getFont(FONT_RES[i]);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    static void immersive(Window w) {
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
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
        t.setOnClickListener(click);
        return t;
    }

    ImageView icon(Context c, int res, int sizeDp, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setMinimumHeight(dp(c, sizeDp));
        v.setAdjustViewBounds(false);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        v.setMaxWidth(dp(c, sizeDp));
        v.setMaxHeight(dp(c, sizeDp));
        return v;
    }

    /** A round icon button; the selected one is filled. */
    ImageView iconButton(Context c, int res, boolean selected, View.OnClickListener click) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(selected ? sheet : fg));
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (selected) d.setColor(fg);
        else d.setStroke(dp(c, 1), line);
        v.setBackground(d);
        int p = dp(c, 11);
        v.setPadding(p, p, p, p);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)));
        v.setOnClickListener(click);
        return v;
    }

    /** A wide rounded pill: icon then label, left-aligned; the selected one is filled. */
    LinearLayout pill(Context c, int res, String label, boolean selected, View.OnClickListener click) {
        int color = selected ? sheet : fg;
        LinearLayout p = new LinearLayout(c);
        p.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
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

    EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(dim);
        e.setTextColor(fg);
        e.setTextSize(15);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 23));
        d.setStroke(dp(c, 1), line);
        e.setBackground(d);
        e.setPadding(dp(c, 18), dp(c, 11), dp(c, 18), dp(c, 11));
        return e;
    }

    void tint(SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(fg));
        bar.setThumbTintList(ColorStateList.valueOf(fg));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(line));
    }
}
