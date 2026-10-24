package com.vaib215.cutesleep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * The bedside clock. Three faces, swiped between: a big clock, an analog clock, and the time
 * with this month's calendar. Each lays itself out for upright or sideways. At night
 * everything turns a dim red. The picture drifts a few pixels each minute, so nothing sits
 * in one place for hours.
 */
final class Face extends View {
    interface Listener {
        void onTap();
        void onFaceChanged(int face);
    }

    static final int FACES = 3;
    private static final int PEACH = 0xFFFFE3D3, CORAL = 0xFFFF8A80, GREY = 0xFF8E8E93, LINE = 0xFF2C2C2E;
    static final int NIGHT = 0xFFB3261E, NIGHT_DIM = 0xFF6E1712;

    Listener listener;
    private int face;
    private boolean night;
    private int battery = -1; // percent, or -1 for unknown
    private boolean charging;
    private String alarm = "", eta = "", note = "";
    private final android.graphics.drawable.Drawable bolt, bell;

    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final float dp;
    private float slide; // horizontal drag while swiping between faces
    private final Runnable tick = this::tick;

    Face(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        bolt = c.getDrawable(R.drawable.ic_bolt).mutate();
        bell = c.getDrawable(R.drawable.ic_alarm).mutate();
        Typeface bold = font(c, 600), regular = font(c, 450);
        digits.setTypeface(bold);
        digits.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(regular);
        small.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                slide = b.getX() - a.getX();
                invalidate();
                return true;
            }
        });
    }

    /** Fredoka, rounded and friendly: bold for numbers, regular for words. */
    static Typeface font(Context c, int weight) {
        try {
            return c.getResources().getFont(weight >= 500 ? R.font.fredoka_bold : R.font.fredoka_regular);
        } catch (Exception e) {
            return weight >= 500 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
    }

    void setFace(int f) {
        face = Math.max(0, Math.min(FACES - 1, f));
        tick();
    }

    void setNight(boolean on) {
        if (night == on) return;
        night = on;
        invalidate();
    }

    void setBattery(int percent, boolean charging) {
        battery = percent;
        this.charging = charging;
        invalidate();
    }
    void setAlarm(String s) { alarm = s; invalidate(); }

    /** "full in 40 min", or blank. */
    void setEta(String s) { eta = s; invalidate(); }

    /** A word about the battery (charged enough, too warm), in the accent colour; blank for none. */
    void setNote(String s) { note = s; invalidate(); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        gestures.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (Math.abs(slide) > getWidth() * 0.18f) {
                face = (face + (slide < 0 ? 1 : FACES - 1)) % FACES;
                if (listener != null) listener.onFaceChanged(face);
                tick();
            }
            slide = 0;
            invalidate();
        }
        return true;
    }

    // ---- Ticking ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    /** Redraws on the next second (analog, by day) or the next minute. */
    private void tick() {
        removeCallbacks(tick);
        invalidate();
        long now = System.currentTimeMillis();
        long step = face == 1 && !night ? 1000 : 60_000;
        postDelayed(tick, step - now % step + 20);
    }

    // ---- Drawing ----

    private int main() { return night ? NIGHT : PEACH; }
    private int accent() { return night ? NIGHT : CORAL; }
    private int dim() { return night ? NIGHT_DIM : GREY; }

    @Override
    protected void onDraw(Canvas canvas) {
        Calendar now = Calendar.getInstance();
        // Drift: a different few-pixel offset every minute, so nothing burns in.
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        float drift = 5 * dp;
        float dx = (float) Math.sin(m * 0.7) * drift, dy = (float) Math.cos(m * 1.3) * drift;
        canvas.save();
        canvas.translate(dx + slide, dy);
        // Fade a little while being swiped away.
        int alpha = Math.round(255 * Math.max(0.2f, 1 - Math.abs(slide) / getWidth()));
        canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), alpha);
        float w = getWidth(), h = getHeight();
        boolean wide = w > h;
        switch (face) {
            case 0: drawBig(canvas, now, w, h, wide); break;
            case 1: drawAnalog(canvas, now, w, h, wide); break;
            default: drawCalendar(canvas, now, w, h, wide); break;
        }
        canvas.restore();
        canvas.restore();
    }

    private String hours(Calendar c) {
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hr = c.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && hr == 0) hr = 12;
        return h24 ? String.format(Locale.ROOT, "%02d", hr) : String.valueOf(hr);
    }

    private static String minutes(Calendar c) {
        return String.format(Locale.ROOT, "%02d", c.get(Calendar.MINUTE));
    }

    private String date(Calendar c, String pattern) {
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), pattern), c).toString();
    }

    /** Sizes `p` so `text` is `width` wide but no taller than `height`. */
    private static void fit(Paint p, String text, float width, float height) {
        p.setTextSize(100);
        float tw = p.measureText(text);
        Paint.FontMetrics fm = p.getFontMetrics();
        float th = -fm.ascent * 0.72f; // digits are about cap height
        p.setTextSize(Math.min(100 * width / tw, 100 * height / th));
    }

    /** One line of small text centred at (x, y). */
    private void line(Canvas c, String s, float x, float y, float size, int color) {
        if (s.isEmpty()) return;
        small.setTextSize(size);
        small.setColor(color);
        c.drawText(s, x, y, small);
    }

    /** Battery (with a bolt while charging) and the next alarm, quietly, centred on x. */
    private void footer(Canvas c, float x, float y, float size) {
        small.setTextSize(size);
        small.setColor(dim());
        float icon = size * 1.15f, gap = size * 0.25f, space = size * 1.6f;
        String pct = battery < 0 ? "" : battery + "%" + (eta.isEmpty() ? "" : "  ·  " + eta);
        if (!note.isEmpty()) line(c, note, x, y - size * 1.9f, size, accent());
        small.setTextSize(size);
        small.setColor(dim());
        float width = 0;
        if (!pct.isEmpty()) width += (charging ? icon + gap : 0) + small.measureText(pct);
        if (!alarm.isEmpty()) width += (width > 0 ? space : 0) + icon + gap + small.measureText(alarm);
        if (width == 0) return;
        small.setTextAlign(Paint.Align.LEFT);
        float at = x - width / 2, top = y - size * 0.36f - icon / 2;
        if (!pct.isEmpty()) {
            if (charging) {
                glyph(c, bolt, at, top, icon);
                at += icon + gap;
            }
            c.drawText(pct, at, y, small);
            at += small.measureText(pct) + space;
        }
        if (!alarm.isEmpty()) {
            glyph(c, bell, at, top, icon);
            c.drawText(alarm, at + icon + gap, y, small);
        }
        small.setTextAlign(Paint.Align.CENTER);
    }

    private void glyph(Canvas c, android.graphics.drawable.Drawable d, float x, float y, float size) {
        d.setTint(dim());
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
        d.draw(c);
    }

    private void drawBig(Canvas c, Calendar now, float w, float h, boolean wide) {
        String hh = hours(now), mm = minutes(now);
        float u = Math.min(w, h);
        if (wide) {
            String time = hh + ":" + mm;
            fit(digits, time, w * 0.82f, h * 0.55f);
            float base = h * 0.5f + digits.getTextSize() * 0.36f;
            // Hours peach, minutes coral, the colon in between dimmer.
            float total = digits.measureText(time), x = w / 2 - total / 2;
            digits.setTextAlign(Paint.Align.LEFT);
            digits.setColor(main());
            c.drawText(hh, x, base, digits);
            x += digits.measureText(hh);
            digits.setColor(dim());
            c.drawText(":", x, base, digits);
            x += digits.measureText(":");
            digits.setColor(accent());
            c.drawText(mm, x, base, digits);
            digits.setTextAlign(Paint.Align.CENTER);
            line(c, date(now, "EEEEdMMMM"), w / 2, h * 0.5f - digits.getTextSize() * 0.5f, u * 0.075f, dim());
            footer(c, w / 2, h - u * 0.07f, u * 0.05f);
        } else {
            fit(digits, "00", w * 0.78f, h * 0.3f);
            float size = digits.getTextSize(), gap = size * 0.08f;
            float mid = h * 0.52f;
            digits.setColor(main());
            c.drawText(hh, w / 2, mid - gap, digits);
            digits.setColor(accent());
            c.drawText(mm, w / 2, mid + size * 0.72f + gap, digits);
            line(c, date(now, "EEEEdMMMM"), w / 2, mid - size * 0.72f - gap - u * 0.08f, u * 0.065f, dim());
            footer(c, w / 2, h - u * 0.09f, u * 0.045f);
        }
    }

    private void drawAnalog(Canvas c, Calendar now, float w, float h, boolean wide) {
        float u = Math.min(w, h);
        float r = u * (wide ? 0.4f : 0.4f);
        float cx = wide ? w * 0.4f : w / 2, cy = wide ? h / 2 : h * 0.42f;

        stroke.setColor(dim());
        for (int i = 0; i < 60; i++) {
            boolean hour = i % 5 == 0, quarter = i % 15 == 0;
            if (!hour && night) continue;
            stroke.setStrokeWidth((quarter ? 4 : hour ? 2.5f : 1) * dp);
            stroke.setColor(hour ? (quarter ? main() : dim()) : (night ? NIGHT_DIM : LINE));
            double a = Math.toRadians(i * 6);
            float r1 = r * (quarter ? 0.86f : hour ? 0.9f : 0.94f);
            c.drawLine(cx + (float) Math.sin(a) * r1, cy - (float) Math.cos(a) * r1,
                    cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, stroke);
        }
        float sec = now.get(Calendar.SECOND), min = now.get(Calendar.MINUTE) + sec / 60f;
        float hr = now.get(Calendar.HOUR) + min / 60f;
        hand(c, cx, cy, hr * 30, r * 0.5f, 7 * dp, main());
        hand(c, cx, cy, min * 6, r * 0.78f, 5 * dp, main());
        if (!night) hand(c, cx, cy, sec * 6, r * 0.86f, 1.6f * dp, accent());
        fill.setColor(accent());
        c.drawCircle(cx, cy, 5 * dp, fill);

        if (wide) {
            float x = w * 0.82f;
            line(c, date(now, "EEEE"), x, h * 0.42f, u * 0.07f, dim());
            digits.setColor(main());
            digits.setTextSize(u * 0.26f);
            c.drawText(String.valueOf(now.get(Calendar.DAY_OF_MONTH)), x, h * 0.42f + u * 0.25f, digits);
            line(c, date(now, "MMMM"), x, h * 0.42f + u * 0.36f, u * 0.06f, dim());
            footer(c, x, h * 0.42f + u * 0.5f, u * 0.04f);
        } else {
            line(c, date(now, "EEEEdMMMM"), w / 2, cy + r + u * 0.2f, u * 0.065f, dim());
            footer(c, w / 2, h - u * 0.09f, u * 0.045f);
        }
    }

    private void hand(Canvas c, float cx, float cy, float degrees, float length, float width, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        double a = Math.toRadians(degrees);
        c.drawLine(cx - (float) Math.sin(a) * length * 0.12f, cy + (float) Math.cos(a) * length * 0.12f,
                cx + (float) Math.sin(a) * length, cy - (float) Math.cos(a) * length, stroke);
    }

    private void drawCalendar(Canvas c, Calendar now, float w, float h, boolean wide) {
        float u = Math.min(w, h);
        String time = hours(now) + ":" + minutes(now);
        RectF grid;
        if (wide) {
            float x = w * 0.27f;
            fit(digits, time, w * 0.4f, h * 0.3f);
            digits.setColor(main());
            c.drawText(time, x, h * 0.5f + digits.getTextSize() * 0.36f, digits);
            line(c, date(now, "EEEEdMMMM"), x, h * 0.5f - digits.getTextSize() * 0.5f, u * 0.065f, dim());
            footer(c, x, h * 0.5f + digits.getTextSize() * 0.36f + u * 0.15f, u * 0.045f);
            grid = new RectF(w * 0.55f, h * 0.14f, w * 0.94f, h * 0.88f);
        } else {
            fit(digits, time, w * 0.7f, h * 0.16f);
            digits.setColor(main());
            float base = h * 0.3f;
            c.drawText(time, w / 2, base, digits);
            line(c, date(now, "EEEEdMMMM"), w / 2, base - digits.getTextSize() * 0.72f - u * 0.06f, u * 0.06f, dim());
            grid = new RectF(w * 0.08f, h * 0.42f, w * 0.92f, h * 0.8f);
            footer(c, w / 2, h - u * 0.09f, u * 0.045f);
        }
        month(c, now, grid);
    }

    /** This month as a grid, today ringed. */
    private void month(Canvas c, Calendar now, RectF r) {
        Calendar d = (Calendar) now.clone();
        d.set(Calendar.DAY_OF_MONTH, 1);
        int first = d.getFirstDayOfWeek();
        int lead = (d.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        int days = d.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (lead + days + 6) / 7;
        float cw = r.width() / 7, ch = r.height() / (rows + 2);
        float size = Math.min(cw, ch) * 0.48f;

        small.setTextAlign(Paint.Align.LEFT);
        line(c, date(now, "MMMM").toUpperCase(Locale.getDefault()), r.left + cw * 0.2f, r.top + ch * 0.6f, size * 0.85f, accent());
        small.setTextAlign(Paint.Align.CENTER);
        Calendar wd = (Calendar) now.clone();
        for (int i = 0; i < 7; i++) {
            wd.set(Calendar.DAY_OF_WEEK, (first - 1 + i) % 7 + 1);
            String name = DateFormat.format("EEEEE", wd).toString();
            line(c, name, r.left + cw * (i + 0.5f), r.top + ch * 1.6f, size * 0.8f, dim());
        }
        int today = now.get(Calendar.DAY_OF_MONTH);
        for (int day = 1; day <= days; day++) {
            int cell = lead + day - 1;
            float x = r.left + cw * (cell % 7 + 0.5f), y = r.top + ch * (cell / 7 + 2.6f);
            if (day == today) {
                fill.setColor(accent());
                c.drawCircle(x, y - size * 0.36f, size * 0.95f, fill);
            }
            line(c, String.valueOf(day), x, y, size, day == today ? 0xFF000000 : main());
        }
    }
}
