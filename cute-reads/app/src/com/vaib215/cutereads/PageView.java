package com.vaib215.cutereads;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lays the whole book out once, splits it into screen-sized pages, draws one page.
 * Bottom bar: ‹ previous, page number (options), › next. Press-and-drag on text selects.
 */
public class PageView extends View {
    interface Listener {
        void onPageChanged(int offset);
        void onMenu();
        void onTap();                                       // dismiss any popup
        void onSelect(int start, int end, float x, float y);
        void onHighlightTap(int index, float x, float y);
    }

    Listener listener;
    int textSizeSp = 20;
    Typeface typeface = Typeface.DEFAULT;
    int textColor = 0xFFE6E6E6;
    int highlightColor = 0x66B8862B;
    List<int[]> highlights = new ArrayList<>();             // {start, end} char offsets
    List<Integer> bookmarks = new ArrayList<>();            // char offsets

    private CharSequence text = "";
    private Set<Integer> breaks = new HashSet<>();
    private StaticLayout layout;
    private int[] pageStarts = {0}; // first line of each page
    private int page;
    private int offset; // char offset of the current page's first line; survives relayout
    private int generation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path markPath = new Path();

    // Touch state
    private float downX, downY;
    private boolean moved, inBar, selecting;
    private int anchorStart, anchorEnd;
    int selStart = -1, selEnd = -1;
    private final Runnable longPress = this::startSelection;
    private float lastX, lastY;
    private int edgeDirection;
    private final Runnable edgeTurn = new Runnable() {
        public void run() {
            if (!selecting || edgeDirection == 0) return;
            turn(edgeDirection, true);
            extendSelection(lastX, edgeDirection > 0 ? getHeight() - barHeight() - 1 : padTop());
            postDelayed(this, 1200);
        }
    };

    public PageView(Context context) {
        super(context);
        numberPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setBook(CharSequence text, List<Integer> breaks, int offset) {
        this.text = text;
        this.breaks = new HashSet<>(breaks);
        this.offset = offset;
        relayout();
    }

    CharSequence text() { return text; }

    /** Heading offsets in reading order. */
    List<Integer> chapters() {
        List<Integer> list = new ArrayList<>(breaks);
        java.util.Collections.sort(list);
        return list;
    }

    /** 1-based page holding this char offset (0 while laying out). */
    int pageOf(int charOffset) {
        return layout == null ? 0 : pageAt(charOffset) + 1;
    }

    void goToPage(int oneBased) {
        if (layout == null) return;
        page = Math.max(0, Math.min(pageStarts.length - 1, oneBased - 1));
        offset = layout.getLineStart(pageStarts[page]);
        listener.onPageChanged(offset);
        invalidate();
    }

    void turn(int delta) {
        turn(delta, false);
    }

    private void turn(int delta, boolean keepSelection) {
        int next = Math.max(0, Math.min(pageStarts.length - 1, page + delta));
        if (layout == null || next == page) return;
        page = next;
        if (!keepSelection) selStart = selEnd = -1;
        offset = layout.getLineStart(pageStarts[page]);
        listener.onPageChanged(offset);
        invalidate();
    }

    int page() { return page + 1; }

    int pageCount() { return layout == null ? 0 : pageStarts.length; }

    int percent() {
        return pageStarts.length <= 1 ? 100 : Math.round(100f * page / (pageStarts.length - 1));
    }

    /** Where each page starts (char offsets), or null while laying out. */
    int[] pageOffsets() {
        if (layout == null) return null;
        int[] out = new int[pageStarts.length];
        for (int i = 0; i < out.length; i++) out[i] = layout.getLineStart(pageStarts[i]);
        return out;
    }

    /** Char range of the current page. */
    int pageStart() { return layout == null ? 0 : layout.getLineStart(pageStarts[page]); }

    int pageEnd() {
        if (layout == null) return 0;
        return page + 1 < pageStarts.length ? layout.getLineStart(pageStarts[page + 1]) : text.length();
    }

    boolean bookmarked() {
        for (int b : bookmarks) if (b >= pageStart() && b < pageEnd()) return true;
        return false;
    }

    /** Go to the page holding [start, end) and mark it until the next tap. */
    void show(int start, int end) {
        goToPage(pageOf(start));
        selStart = start;
        selEnd = end;
        invalidate();
    }

    void relayoutNow() { relayout(); }

    void clearSelection() {
        selStart = selEnd = -1;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                moved = selecting = false;
                inBar = downY > getHeight() - barHeight();
                if (!inBar) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selecting) {
                    extendSelection(e.getX(), e.getY());
                    lastX = e.getX();
                    lastY = e.getY();
                    // Holding at the bottom (or top) edge carries the selection onto the next page.
                    int edge = e.getY() > getHeight() - barHeight() ? 1 : e.getY() < padTop() ? -1 : 0;
                    if (edge != edgeDirection) {
                        removeCallbacks(edgeTurn);
                        edgeDirection = edge;
                        if (edge != 0) postDelayed(edgeTurn, 700);
                    }
                } else if (Math.hypot(e.getX() - downX, e.getY() - downY)
                        > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                removeCallbacks(edgeTurn);
                edgeDirection = 0;
                if (selecting) {
                    selecting = false;
                    if (selStart >= 0) listener.onSelect(selStart, selEnd, e.getX(), selectionTop());
                } else if (!moved) {
                    tap(e.getX(), e.getY());
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                removeCallbacks(edgeTurn);
                edgeDirection = 0;
                selecting = false;
                return true;
        }
        return false;
    }

    private void tap(float x, float y) {
        listener.onTap();
        if (selStart >= 0) { clearSelection(); return; }
        if (inBar) {
            if (x < getWidth() / 3f) turn(-1);
            else if (x > getWidth() * 2 / 3f) turn(1);
            else listener.onMenu();
            return;
        }
        int at = offsetAt(x, y);
        for (int i = 0; i < highlights.size(); i++) {
            int[] h = highlights.get(i);
            if (at >= h[0] && at < h[1]) { listener.onHighlightTap(i, x, y); return; }
        }
    }

    private void startSelection() {
        int at = offsetAt(downX, downY);
        if (at < 0) return;
        anchorStart = wordStart(at);
        anchorEnd = wordEnd(at);
        if (anchorEnd <= anchorStart) return;
        selecting = true;
        selStart = anchorStart;
        selEnd = anchorEnd;
        listener.onTap();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void extendSelection(float x, float y) {
        int at = offsetAt(x, y);
        if (at < 0) return;
        if (at >= anchorStart) { selStart = anchorStart; selEnd = Math.max(anchorEnd, wordEnd(at)); }
        else { selStart = wordStart(at); selEnd = anchorEnd; }
        invalidate();
    }

    /** Char offset under a point, limited to the current page. */
    private int offsetAt(float x, float y) {
        if (layout == null) return -1;
        int first = pageStarts[page];
        int last = (page + 1 < pageStarts.length ? pageStarts[page + 1] : layout.getLineCount()) - 1;
        int line = layout.getLineForVertical(Math.round(y - padTop() + layout.getLineTop(first)));
        line = Math.max(first, Math.min(last, line));
        return layout.getOffsetForHorizontal(line, x - padX());
    }

    private float selectionTop() {
        if (selStart < pageStart()) return padTop(); // began on an earlier page
        int line = layout.getLineForOffset(selStart);
        return layout.getLineTop(line) - layout.getLineTop(pageStarts[page]) + padTop();
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '’' || c == '-';
    }

    private int wordStart(int i) {
        while (i > 0 && wordChar(text.charAt(i - 1))) i--;
        return i;
    }

    private int wordEnd(int i) {
        while (i < text.length() && wordChar(text.charAt(i))) i++;
        return i;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        relayout();
    }

    private int px(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

    /** Side margin; on wide screens the text keeps a comfortable line length. */
    private int padX() { return Math.max(px(24), (getWidth() - px(620)) / 2); }
    private boolean landscape() { return getWidth() > getHeight(); }
    private int padTop() { return landscape() ? px(20) : px(40); }   // portrait: clears the camera hole
    private int padBottom() { return px(56); }   // room for the bottom bar
    private int barHeight() { return px(64); }

    /** Layout is slow for a whole book, so it runs off the main thread. */
    private void relayout() {
        int width = getWidth() - 2 * padX();
        int height = getHeight() - padTop() - padBottom();
        if (width <= 0 || height <= 0) return;

        final int gen = ++generation;
        final CharSequence t = text;
        final Set<Integer> br = breaks;
        final int keep = offset;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(textColor);
        paint.setTypeface(typeface);
        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, textSizeSp, getResources().getDisplayMetrics()));

        // Same line height in every font: 1.5x the text size, whatever the font's own leading.
        Paint.FontMetrics fm = paint.getFontMetrics();
        float extra = 1.5f * paint.getTextSize() - (fm.descent - fm.ascent);

        // Pictures fit the page, leaving room for the line spacing below them.
        PageImage.maxWidth = width;
        PageImage.maxHeight = Math.max(px(80), height - Math.round(Math.max(0, extra)) - px(8));

        new Thread(() -> {
            StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), paint, width)
                    .setLineSpacing(extra, 1f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build();
            int[] starts = paginate(l, br, height);
            main.post(() -> {
                if (gen != generation) return;
                layout = l;
                pageStarts = starts;
                page = pageAt(keep);
                offset = l.getLineStart(starts[page]);
                invalidate();
            });
        }).start();
    }

    private static int[] paginate(StaticLayout l, Set<Integer> breaks, int height) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int top = l.getLineTop(0);
        for (int i = 1; i < l.getLineCount(); i++) {
            boolean full = l.getLineBottom(i) - top > height;
            boolean chapter = breaks.contains(l.getLineStart(i));
            if (full || chapter) {
                // Don't open a page with the blank line between paragraphs.
                int first = (!chapter && isBlank(l, i) && i + 1 < l.getLineCount()) ? i + 1 : i;
                starts.add(first);
                top = l.getLineTop(first);
                i = first;
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    private static boolean isBlank(StaticLayout l, int line) {
        return l.getLineEnd(line) - l.getLineStart(line) <= 1;
    }

    private int pageAt(int charOffset) {
        int line = layout.getLineForOffset(Math.min(charOffset, text.length()));
        int p = 0;
        while (p + 1 < pageStarts.length && pageStarts[p + 1] <= line) p++;
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (layout == null) return;
        int first = pageStarts[page];
        int end = page + 1 < pageStarts.length ? pageStarts[page + 1] : layout.getLineCount();
        int top = layout.getLineTop(first);
        int bottom = layout.getLineTop(end);
        canvas.save();
        canvas.translate(padX(), padTop());
        canvas.clipRect(0, 0, getWidth(), bottom - top);
        canvas.translate(0, -top);
        int from = layout.getLineStart(first), to = layout.getLineStart(Math.min(end, layout.getLineCount() - 1));
        markPaint.setColor(highlightColor);
        for (int[] h : highlights) {
            if (h[1] <= from || h[0] >= to && end < layout.getLineCount()) continue;
            layout.getSelectionPath(h[0], h[1], markPath);
            canvas.drawPath(markPath, markPaint);
        }
        if (selStart >= 0) {
            markPaint.setColor(0x664A8BFF);
            layout.getSelectionPath(selStart, selEnd, markPath);
            canvas.drawPath(markPath, markPaint);
        }
        layout.getPaint().setColor(textColor);
        layout.draw(canvas);
        canvas.restore();

        if (bookmarked()) {
            // A ribbon hanging from the top-right corner.
            float x = getWidth() - px(44), w = px(16), h = px(30);
            markPath.reset();
            markPath.moveTo(x, 0);
            markPath.lineTo(x + w, 0);
            markPath.lineTo(x + w, h);
            markPath.lineTo(x + w / 2, h - px(6));
            markPath.lineTo(x, h);
            markPath.close();
            markPaint.setColor((textColor & 0x00FFFFFF) | 0x99000000);
            canvas.drawPath(markPath, markPaint);
        }

        // Bottom bar: ‹  page  ›
        int dim = (textColor & 0x00FFFFFF) | 0x80000000;
        float base = getHeight() - px(22);
        numberPaint.setColor(dim);
        numberPaint.setTextSize(px(12));
        canvas.drawText((page + 1) + " / " + pageStarts.length, getWidth() / 2f, base, numberPaint);
        numberPaint.setTextSize(px(30));
        numberPaint.setColor(page > 0 ? dim : (textColor & 0x00FFFFFF) | 0x30000000);
        canvas.drawText("‹", px(40), base + px(3), numberPaint);
        numberPaint.setColor(page < pageStarts.length - 1 ? dim : (textColor & 0x00FFFFFF) | 0x30000000);
        canvas.drawText("›", getWidth() - px(40), base + px(3), numberPaint);
    }
}
