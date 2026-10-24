package com.vaib215.cutenotes;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import com.google.mlkit.vision.digitalink.recognition.Ink;

import java.util.ArrayList;
import java.util.List;

/**
 * The writing pad. Strokes are collected until the pen rests for a moment, or until a new
 * word is started back on the left, then handed over as one piece of ink; they fade once read.
 */
final class InkView extends View {
    interface Listener {
        /** A word (or a few) to read and put in the note. */
        void onInk(Ink ink, Group group);
        /** Still being written: read it for a preview only. */
        void onWriting(Ink ink, Group group);
        /** Lines were ruled through these words ([start, end) in the note) shown on the pad. */
        void onStrike(List<int[]> words);
    }

    /** Strokes read together as one word or phrase. */
    static final class Group {
        final List<Path> paths = new ArrayList<>();
        final List<Ink.Stroke> strokes = new ArrayList<>();
        final List<float[]> bounds = new ArrayList<>(); // per stroke: left, top, right, bottom
        int alpha = 255;

        Ink ink() {
            Ink.Builder b = Ink.builder();
            for (Ink.Stroke s : strokes) b.addStroke(s);
            return b.build();
        }

        float[] box() {
            float[] r = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (float[] b : bounds) {
                r[0] = Math.min(r[0], b[0]);
                r[1] = Math.min(r[1], b[1]);
                r[2] = Math.max(r[2], b[2]);
                r[3] = Math.max(r[3], b[3]);
            }
            return r;
        }
    }

    Listener listener;
    long pause = 600; // ms the pen rests before the ink is read

    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caret = new Paint();
    // The line with the cursor, shown along the top to be struck out.
    private String line;
    private int lineStart, cursor; // where `line` starts in the note; the cursor, within `line`
    private final List<float[]> words = new ArrayList<>(); // left, right, start, end (in the note)
    private float lineTop, lineBottom;
    private final List<Group> reading = new ArrayList<>(); // handed over, fading once read
    private Group open = new Group(); // being written
    private Ink.Stroke.Builder stroke;
    private Path path;
    private float lastX, lastY;
    private final float[] box = new float[4];
    private boolean writing;
    private final Runnable commit = this::commit;

    InkView(Context c, Ui ui) {
        super(c);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
        pen.setStrokeWidth(Ui.dp(c, 5));
        pen.setColor(Ui.INK);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(Ui.dp(c, 1.5f));
        guide.setColor(ui.line);
        guide.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 6), Ui.dp(c, 6)}, 0));
        hint.setColor(ui.dim);
        hint.setTextSize(Ui.dp(c, 16));
        hint.setTextAlign(Paint.Align.CENTER);
        word.setColor(ui.fg);
        word.setAlpha(170);
        word.setTextSize(Ui.dp(c, 26));
        caret.setColor(Ui.INK);
    }

    /** The note's font, for the line shown along the top. */
    void setFont(android.graphics.Typeface face, float scale) {
        word.setTypeface(face);
        word.setTextSize(Ui.dp(getContext(), 26) * scale);
        invalidate();
    }

    /** Shows `s`, which starts at `start` in the note, with the cursor `at` within it. */
    void setLine(String s, int start, int at) {
        if (s.trim().isEmpty()) s = null;
        if ((s == null ? line == null : s.equals(line)) && start == lineStart && at == cursor) return;
        line = s;
        lineStart = start;
        cursor = at;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(commit);
                writing = true;
                lastX = x;
                lastY = y;
                box[0] = box[2] = x;
                box[1] = box[3] = y;
                stroke = Ink.Stroke.builder();
                stroke.addPoint(Ink.Point.create(x, y, e.getEventTime()));
                path = new Path();
                path.moveTo(x, y);
                path.lineTo(x + 0.1f, y); // so a dot shows
                open.paths.add(path);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!writing) return true;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    addPoint(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i));
                }
                addPoint(x, y, e.getEventTime());
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (!writing) return true;
                writing = false;
                addPoint(x, y, e.getEventTime());
                endStroke();
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (writing) {
                    writing = false;
                    open.paths.remove(path);
                    invalidate();
                }
                if (!open.strokes.isEmpty()) postDelayed(commit, pause);
                return true;
        }
        return true;
    }

    private void addPoint(float x, float y, long t) {
        stroke.addPoint(Ink.Point.create(x, y, t));
        path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
        lastX = x;
        lastY = y;
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.max(box[2], x);
        box[3] = Math.max(box[3], y);
    }

    private void endStroke() {
        // A line ruled through words shown along the top deletes them.
        List<int[]> struck = open.strokes.isEmpty() ? struck(box) : null;
        if (struck != null && !struck.isEmpty()) {
            open.paths.remove(path);
            Group g = new Group();
            g.paths.add(path);
            reading.add(g);
            done(g);
            stroke = null;
            listener.onStrike(struck);
            return;
        }
        // A tall stroke well to the left of a wide word is the start of the next word:
        // read the word before it now rather than waiting for a pause.
        if (!open.strokes.isEmpty() && startsNewWord(box)) {
            Path p = path;
            open.paths.remove(p);
            commit();
            open.paths.add(p);
        }
        open.strokes.add(stroke.build());
        open.bounds.add(box.clone());
        stroke = null;
        invalidate();
        listener.onWriting(open.ink(), open);
        postDelayed(commit, pause);
    }

    /** The words a flat stroke runs through (at least half of each), or null if it isn't one. */
    private List<int[]> struck(float[] s) {
        if (words.isEmpty()) return null;
        float w = s[2] - s[0], h = s[3] - s[1];
        float slack = (lineBottom - lineTop) / 2;
        if (w < Ui.dp(getContext(), 20) || h > w * 0.35f
                || s[3] < lineTop - slack || s[1] > lineBottom + slack) return null;
        List<int[]> out = new ArrayList<>();
        for (float[] word : words) {
            float covered = Math.min(s[2], word[1]) - Math.max(s[0], word[0]);
            if (covered >= (word[1] - word[0]) / 2) out.add(new int[]{(int) word[2], (int) word[3]});
        }
        return out;
    }

    private boolean startsNewWord(float[] s) {
        float[] w = open.box();
        float width = w[2] - w[0], height = w[3] - w[1];
        return width > getWidth() * 0.35f
                && s[2] < w[0] + width * 0.3f
                && s[3] - s[1] > height * 0.3f;
    }

    /** Hands over what has been written since the last pause. */
    private void commit() {
        removeCallbacks(commit);
        if (open.strokes.isEmpty() || writing) return;
        Group g = open;
        open = new Group();
        reading.add(g);
        listener.onInk(g.ink(), g);
    }

    /** Is this group still being written? */
    boolean isOpen(Group g) { return g == open; }

    /** The ink has been read (or couldn't be): fade it out. */
    void done(Group g) {
        ValueAnimator a = ValueAnimator.ofInt(255, 0).setDuration(200);
        a.addUpdateListener(v -> {
            g.alpha = (int) v.getAnimatedValue();
            if (g.alpha == 0) reading.remove(g);
            invalidate();
        });
        a.start();
    }

    /** Throws away anything written and not yet read. */
    void clear() {
        removeCallbacks(commit);
        writing = false;
        open = new Group();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float base = getHeight() * 0.68f;
        float inset = Ui.dp(getContext(), 20);
        canvas.drawLine(inset, base, getWidth() - inset, base, guide);
        drawLine(canvas);
        if (line == null && reading.isEmpty() && open.paths.isEmpty()) {
            canvas.drawText("Write here, big", getWidth() / 2f, getHeight() * 0.4f, hint);
        }
        for (Group g : reading) draw(canvas, g);
        draw(canvas, open);
    }

    /**
     * As much of the line as fits, in whole words: the words before the cursor first, then
     * those after it. Notes where each word sits, for striking out.
     */
    private void drawLine(Canvas canvas) {
        words.clear();
        if (line == null) return;
        List<int[]> all = new ArrayList<>(); // start, end within `line`
        for (int i = 0; i < line.length(); ) {
            if (Character.isWhitespace(line.charAt(i))) { i++; continue; }
            int e = i;
            while (e < line.length() && !Character.isWhitespace(line.charAt(e))) e++;
            all.add(new int[]{i, e});
            i = e;
        }
        if (all.isEmpty()) return;
        float left = Ui.dp(getContext(), 20), room = getWidth() - 2 * left;
        int first = 0;
        while (first < all.size() - 1 && all.get(first + 1)[0] < cursor) first++;
        int last = first;
        while (first > 0 && fits(all, first - 1, last, room)) first--;
        while (last < all.size() - 1 && fits(all, first, last + 1, room)) last++;

        String pre = first > 0 ? "… " : "";
        int from = all.get(first)[0], to = all.get(last)[1];
        String shown = pre + line.substring(from, to) + (last < all.size() - 1 ? " …" : "");
        float y = Ui.dp(getContext(), 14) - word.ascent();
        canvas.drawText(shown, left, y, word);
        lineTop = y + word.ascent();
        lineBottom = y + word.descent();
        float x = left + word.measureText(pre);
        for (int k = first; k <= last; k++) {
            int[] w = all.get(k);
            float x0 = x + word.measureText(line, from, w[0]), x1 = x + word.measureText(line, from, w[1]);
            words.add(new float[]{x0, x1, lineStart + w[0], lineStart + w[1]});
        }
        if (cursor >= from && cursor <= to) {
            float cx = x + word.measureText(line, from, cursor);
            canvas.drawRect(cx, lineTop, cx + Ui.dp(getContext(), 2), lineBottom, caret);
        }
    }

    private boolean fits(List<int[]> all, int first, int last, float room) {
        float w = word.measureText(line, all.get(first)[0], all.get(last)[1]);
        return w + word.measureText("… ") * 2 <= room;
    }

    private void draw(Canvas canvas, Group g) {
        pen.setAlpha(g.alpha);
        for (Path p : g.paths) canvas.drawPath(p, pen);
    }
}
