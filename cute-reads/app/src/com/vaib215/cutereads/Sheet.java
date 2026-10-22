package com.vaib215.cutereads;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * A bottom sheet built on the framework Dialog (the Material one needs the support library):
 * slides up, rounded top, drag handle, swipe down or tap outside to close.
 */
class Sheet extends Dialog {
    final LinearLayout body;
    private final LinearLayout root;
    private float downY;

    Sheet(Activity activity, Ui ui) {
        super(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.sheet);
        float r = Ui.dp(activity, 20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);

        View handle = new View(activity);
        GradientDrawable h = new GradientDrawable();
        h.setColor(ui.line);
        h.setCornerRadius(Ui.dp(activity, 2));
        handle.setBackground(h);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = Ui.dp(activity, 10);
        hp.bottomMargin = Ui.dp(activity, 6);
        root.addView(handle, hp);

        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 20);
        body.setPadding(pad, Ui.dp(activity, 8), pad, Ui.dp(activity, 24));
        // Long panels scroll instead of running off the top of the screen.
        int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.85f);
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int w, int h) {
                // Never taller than the window allows, so the rest scrolls instead of being cut off.
                int limit = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED
                        ? max : Math.min(max, MeasureSpec.getSize(h));
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        // Take focus first so a text field doesn't grab it and scroll the sheet down.
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); // wrap, or it gets squeezed to fit
        root.addView(scroll);
        root.setOnTouchListener(this::drag);

        // The window fills the screen; the sheet sits at its bottom and the space above closes it.
        // (A wrap-content window gets clipped above the hidden navigation bar on some phones.)
        FrameLayout outside = new FrameLayout(activity);
        outside.setOnClickListener(v -> dismiss());
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        outside.addView(root, new FrameLayout.LayoutParams(Math.min(screen, Ui.dp(activity, 560)),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        setContentView(outside);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.getDecorView().setPadding(0, 0, 0, 0);
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        w.setDimAmount(0.25f);
        // The page field lifts the sheet above the keyboard; the keyboard stays down until asked.
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    /** Touches that no child takes (handle, padding, labels) drag the sheet down. */
    private boolean drag(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                root.setTranslationY(Math.max(0, e.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (root.getTranslationY() > root.getHeight() / 4f) dismiss();
                else root.animate().translationY(0).setDuration(150).start();
                return true;
        }
        return false;
    }

    /** For a sheet whose first job is typing: focus the field and raise the keyboard. */
    void typeInto(EditText field) {
        setOnShowListener(d -> field.postDelayed(() -> {
            field.requestFocus();
            getContext().getSystemService(InputMethodManager.class).showSoftInput(field, 0);
        }, 150));
    }

    /** Showing a dialog brings the system bars back; this keeps the reader fullscreen. */
    @Override
    public void show() {
        Window w = getWindow();
        w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        super.show();
        Ui.immersive(w);
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }
}
