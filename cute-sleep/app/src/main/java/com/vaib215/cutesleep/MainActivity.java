package com.vaib215.cutesleep;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** How to use it: start it now, have it start by itself while charging, or add the widget. */
public class MainActivity extends Activity implements Face.Listener {
    static final String REPO = "https://github.com/Vaib215/cute-apps/tree/main/cute-sleep";
    private static final int BG = Color.BLACK, CARD = 0xFF1C1C1E, FG = 0xFFE6E6E6, DIM = 0xFF8E8E93,
            PEACH = 0xFFFFE3D3, DARK = 0xFF3B2F2C, LINE = 0xFF3A3A3C;

    private static final String[] ON_OFF = {"On", "Off"};
    private static final int[] LEVELS = {0, 80, 85, 90, 100};
    private static final String[] LEVEL_NAMES = {"Off", "80%", "85%", "90%", "100%"};

    private SharedPreferences prefs;
    private Bedside bedside;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Bedside.prefs(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Watcher.sync(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Quiet.off(this); // the clock isn't up, so nothing should be silenced by it
        build();
        bedside.start();
    }

    @Override
    protected void onPause() {
        bedside.stop();
        super.onPause();
    }

    private void build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        page.setPadding(pad, dp(16), pad, dp(32));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Cute Sleep", 30, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView code = new ImageView(this);
        code.setImageResource(R.drawable.ic_code);
        code.setImageTintList(ColorStateList.valueOf(FG));
        code.setScaleType(ImageView.ScaleType.CENTER);
        code.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO))));
        header.addView(code, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);
        page.addView(text("A calm bedside clock for while your phone charges. Swipe it to change the face; tap to leave.",
                15, DIM), spaced(4));

        // A live preview, which is also the way in.
        Face preview = new Face(this);
        preview.listener = this;
        FrameLayout card = new FrameLayout(this);
        card.setBackground(round(Color.BLACK, LINE));
        card.setClipToOutline(true);
        card.setPadding(dp(1), dp(1), dp(1), dp(1));
        card.addView(preview);
        page.addView(card, spacedSize(-1, dp(220), 20));
        bedside = new Bedside(this, getWindow(), preview, null, false);

        page.addView(button("Sleep now", true, v -> startActivity(new Intent(this, SleepActivity.class))), spaced(16));

        page.addView(choices("Automatically while charging",
                "Put the phone on the charger and lock it: the clock comes up by itself. Unplug it and the clock goes away.",
                ON_OFF, prefs.getBoolean("auto", true) ? 0 : 1, i -> {
                    prefs.edit().putBoolean("auto", i == 0).apply();
                    Watcher.sync(this);
                }), spaced(24));

        page.addView(widgetsCard(), spaced(16));
        page.addView(choices("Clock widget background",
                "A dark card, plain black, or nothing at all so the clocks sit straight on your wallpaper.",
                SleepWidget.BACKGROUND_NAMES, SleepWidget.background(this), i -> {
                    prefs.edit().putInt("widgetBg", i).remove("clearWidgets").apply();
                    SleepWidget.refreshAll(this);
                }), spaced(16));

        int at = prefs.getInt("alertAt", 80), pick = 0;
        for (int k = 0; k < LEVELS.length; k++) if (LEVELS[k] == at) pick = k;
        page.addView(choices("Charge alert",
                "A chime when the battery reaches this level, so you can unplug it. Keeping a battery "
                        + "below full makes it last longer. At night, with the clock up, it just buzzes and shows on the clock.",
                LEVEL_NAMES, pick, i -> prefs.edit().putInt("alertAt", LEVELS[i]).apply()), spaced(16));

        page.addView(choices("Heat warning",
                "Warns if the battery gets hot (over 40 °C) while charging.",
                ON_OFF, prefs.getBoolean("heat", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("heat", i == 0).apply()), spaced(16));

        boolean quiet = prefs.getBoolean("quiet", true), dnd = Quiet.allowed(this);
        page.addView(choices("Quiet at night",
                "Do Not Disturb while the clock is up in a dark room. Alarms still ring."
                        + (quiet && !dnd ? "\n\nCute Sleep needs Do Not Disturb access for this. Tap On to allow it." : ""),
                ON_OFF, quiet ? 0 : 1, i -> {
                    prefs.edit().putBoolean("quiet", i == 0).apply();
                    if (i == 0 && !Quiet.allowed(this)) {
                        Toast.makeText(this, "Allow Cute Sleep here, then come back", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    }
                }), spaced(16));

        page.addView(choices("Red at night",
                "In a dark room the clock turns dim red and the screen goes right down.",
                ON_OFF, prefs.getBoolean("red", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("red", i == 0).apply()), spaced(16));

        boolean alerts = prefs.getBoolean("alerts", false);
        page.addView(choices("Notifications on the clock",
                "Show your notifications beside the clock, each with a cross to dismiss it."
                        + (alerts && !Alerts.allowed(this) ? "\n\nCute Sleep needs Notification access for this. Tap On to allow it." : ""),
                ON_OFF, alerts ? 0 : 1, i -> {
                    prefs.edit().putBoolean("alerts", i == 0).apply();
                    if (i == 0 && !Alerts.allowed(this)) {
                        Toast.makeText(this, "Switch on Cute Sleep here, then come back", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    }
                }), spaced(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
        setContentView(scroll);
    }

    /** A card with a title, a line about it, and a row of chips, one picked. */
    private View choices(String title, String body, String[] labels, int selected, java.util.function.IntConsumer pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 0));
        box.setPadding(dp(18), dp(16), dp(18), dp(18));
        box.addView(text(title, 17, FG));
        TextView t = text(body, 14, DIM);
        t.setLineSpacing(0, 1.2f);
        box.addView(t, spaced(4));
        LinearLayout chips = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            int which = i;
            boolean on = i == selected;
            TextView chip = text(labels[i], 15, on ? DARK : FG);
            chip.setBackground(on ? round(PEACH, 0) : round(0, LINE));
            chip.setPadding(dp(labels.length > 3 ? 12 : 18), dp(8), dp(labels.length > 3 ? 12 : 18), dp(8));
            chip.setOnClickListener(v -> {
                pick.accept(which);
                bedside.stop();
                build();
                bedside.start();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        HorizontalScrollView row = new HorizontalScrollView(this);
        row.setHorizontalScrollBarEnabled(false);
        row.addView(chips);
        box.addView(row, spaced(12));
        return box;
    }

    /** The three home-screen widgets, each added with a tap. */
    private View widgetsCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 0));
        box.setPadding(dp(18), dp(16), dp(18), dp(18));
        box.addView(text("Widgets", 17, FG));
        TextView t = text("For your home screen: a Sleep button, digital clocks (one line, or hours over minutes) and an analog clock. "
                + "Tap any of them to bring up the bedside clock.", 14, DIM);
        t.setLineSpacing(0, 1.2f);
        box.addView(t, spaced(4));
        box.addView(button("Add Sleep button", false, v -> addWidget(SleepWidget.class)), spaced(14));
        box.addView(button("Add digital clock", false, v -> addWidget(SleepWidget.Digital.class)), spaced(8));
        box.addView(button("Add stacked digital clock", false, v -> addWidget(SleepWidget.DigitalStacked.class)), spaced(8));
        box.addView(button("Add analog clock", false, v -> addWidget(SleepWidget.Analog.class)), spaced(8));
        return box;
    }

    private void addWidget(Class<?> widget) {
        AppWidgetManager m = getSystemService(AppWidgetManager.class);
        if (m != null && m.isRequestPinAppWidgetSupported()) {
            m.requestPinAppWidget(new ComponentName(this, widget), null, null);
        } else {
            Toast.makeText(this, "Hold an empty spot on the home screen › Widgets › Cute Sleep", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onTap() {
        startActivity(new Intent(this, SleepActivity.class));
    }

    @Override
    public void onFaceChanged(int face) {
        bedside.faceChanged(face);
    }

    // ---- small helpers ----

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String s, boolean primary, View.OnClickListener click) {
        TextView b = text(s, 16, primary ? DARK : FG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? round(PEACH, 0) : round(0, LINE));
        b.setMinHeight(dp(52));
        b.setOnClickListener(click);
        return b;
    }

    /** A rounded box: filled with `fill` (0 for none), outlined with `line` (0 for none). */
    private GradientDrawable round(int fill, int line) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(22));
        if (fill != 0) d.setColor(fill);
        if (line != 0) d.setStroke(dp(1), line);
        return d;
    }

    private LinearLayout.LayoutParams spaced(int top) {
        return spacedSize(-1, -2, top);
    }

    private LinearLayout.LayoutParams spacedSize(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }
}
