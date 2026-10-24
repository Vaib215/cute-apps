package com.vaib215.cutesleep;

import android.content.Context;
import android.view.ViewGroup;

/**
 * The whole bedside screen: the clock, and notifications if there are any to show. Upright,
 * they go under the clock; sideways, to its right. Without them the clock has it all.
 */
final class Stand extends ViewGroup {
    final Face face;
    final Panel panel;

    Stand(Context c) {
        super(c);
        face = new Face(c);
        panel = new Panel(c);
        panel.setVisibility(GONE);
        addView(face);
        addView(panel);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        if (panel.getVisibility() == GONE) {
            face.measure(exactly(w), exactly(h));
            return;
        }
        if (w > h) {
            int pw = Math.round(w * 0.4f);
            panel.measure(exactly(pw), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            face.measure(exactly(w - pw), exactly(h));
        } else {
            panel.measure(exactly(w), MeasureSpec.makeMeasureSpec(Math.round(h * 0.42f), MeasureSpec.AT_MOST));
            face.measure(exactly(w), exactly(h - panel.getMeasuredHeight()));
        }
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        face.layout(0, 0, face.getMeasuredWidth(), face.getMeasuredHeight());
        if (panel.getVisibility() == GONE) return;
        if (w > h) {
            // Sideways: centred down the right-hand side.
            int top = (h - panel.getMeasuredHeight()) / 2;
            panel.layout(w - panel.getMeasuredWidth(), top, w, top + panel.getMeasuredHeight());
        } else {
            panel.layout(0, h - panel.getMeasuredHeight(), w, h);
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() { return false; }
}
