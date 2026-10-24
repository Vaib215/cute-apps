package com.vaib215.cutenotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** All notes, newest first, and a button to start a new one. */
public class NotesActivity extends Activity {
    static final String REPO = "https://github.com/Vaib215/cute-apps/tree/main/cute-notes";

    private Ui ui;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Get handwriting ready while the list is up, so the first note is quick.
        Handwriting.get().start();
        if (Ui.prefs(this).getBoolean("autocorrect", true)) AutoCorrect.load(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        ui = Ui.from(this);
        ui.bars(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.bg);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 8));
        TextView title = ui.text(this, "Notes", 30, ui.fg);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(ui.iconButton(this, R.drawable.ic_code,
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)))));
        column.addView(header);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(this, 96)); // room above the write button
        List<File> notes = Notes.all(this);
        for (File f : notes) list.addView(row(f));
        if (notes.isEmpty()) {
            TextView empty = ui.text(this, "No notes yet.\nTap Write, then write big with your finger anywhere on the page.",
                    16, ui.dim);
            empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), 0);
            empty.setLineSpacing(0, 1.3f);
            list.addView(empty);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column);

        root.addView(writeButton(), new FrameLayout.LayoutParams(-2, Ui.dp(this, 56),
                Gravity.BOTTOM | Gravity.END) {{
            setMargins(0, 0, Ui.dp(NotesActivity.this, 20), Ui.dp(NotesActivity.this, 24));
        }});
        setContentView(root);
    }

    private View row(File f) {
        String[] tp = Notes.titleAndPreview(Notes.read(f));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        row.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));

        TextView title = ui.text(this, tp[0], 18, ui.fg);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title);
        String when = when(f.lastModified());
        TextView sub = ui.text(this, tp[1].isEmpty() ? when : when + "  ·  " + tp[1], 14, ui.dim);
        sub.setSingleLine();
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        row.addView(sub);

        row.setOnClickListener(v -> startActivity(
                new Intent(this, WriteActivity.class).putExtra(WriteActivity.EXTRA_NOTE, f.getPath())));
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this, ui.dialogTheme())
                    .setMessage("Delete “" + tp[0] + "”?")
                    .setPositiveButton("Delete", (d, w) -> {
                        f.delete();
                        NotesWidget.update(this);
                        build();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        return row;
    }

    private String when(long t) {
        int flags = DateUtils.isToday(t) ? DateUtils.FORMAT_SHOW_TIME
                : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
        return DateUtils.formatDateTime(this, t, flags);
    }

    private View writeButton() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 24), 0);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.dp(this, 28));
        d.setColor(0xFFFFE3D3);
        b.setBackground(d);
        b.setElevation(Ui.dp(this, 4));
        ImageView pen = new ImageView(this);
        pen.setImageResource(R.drawable.ic_edit);
        pen.setImageTintList(ColorStateList.valueOf(0xFF3B2F2C));
        b.addView(pen, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView t = ui.text(this, "Write", 16, 0xFF3B2F2C);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(v -> startActivity(new Intent(this, WriteActivity.class)));
        return b;
    }
}
