package com.vaib215.cutereads;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kindle-style home: covers in a grid, most recently read first. "+" imports EPUB/TXT from Files. */
public class LibraryActivity extends Activity {
    static final int PICK = 1;
    static final String REPO = "https://github.com/Vaib215/cute-apps/tree/main/cute-reads";

    SharedPreferences prefs;
    Ui ui;
    List<File> books = new ArrayList<>();
    final Map<String, Bitmap> covers = new HashMap<>();
    BaseAdapter adapter;
    GridView grid;
    TextView empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        ui = Ui.from(prefs);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 44), pad, 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text(this, "Library", 28, ui.fg);
        title.setTypeface(Typeface.create(Ui.font(this, 0), Typeface.BOLD));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // Source code, issues and sponsoring live on GitHub.
        ImageView repo = ui.icon(this, R.drawable.ic_code, 24, ui.dim);
        repo.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO))));
        LinearLayout.LayoutParams repoLp = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        repoLp.rightMargin = Ui.dp(this, 8);
        head.addView(repo, repoLp);
        TextView add = ui.text(this, "+", 30, ui.fg);
        add.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0);
        add.setOnClickListener(v -> pickFiles());
        head.addView(add);
        root.addView(head);

        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(columns());
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setVerticalSpacing(Ui.dp(this, 22));
        grid.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 24));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, i, id) -> read(books.get(i)));
        grid.setOnItemLongClickListener((p, v, i, id) -> { confirmRemove(books.get(i)); return true; });
        content.addView(grid);

        empty = ui.text(this, "No books yet.\nTap + to add an EPUB or TXT from Files.", 16, ui.dim);
        empty.setGravity(Gravity.CENTER);
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refresh();

        // Opened with a book from another app: add it.
        Intent in = getIntent();
        Uri shared = Intent.ACTION_SEND.equals(in.getAction())
                ? in.getParcelableExtra(Intent.EXTRA_STREAM) : in.getData();
        if (shared != null) add(java.util.Collections.singletonList(shared));
    }

    int columns() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return m.widthPixels > m.heightPixels ? 5 : 3;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        grid.setNumColumns(columns());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(getWindow());
    }

    void refresh() {
        books = Library.books(this);
        books.sort((a, b) -> Long.compare(lastUsed(b), lastUsed(a)));
        empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    long lastUsed(File f) {
        return Math.max(prefs.getLong("opened:" + f.getName(), 0), f.lastModified());
    }

    void read(File book) {
        startActivity(new Intent(this, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_BOOK, book.getName()));
        overridePendingTransition(0, 0);
        finish();
    }

    void confirmRemove(File book) {
        new AlertDialog.Builder(this, ui.day
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Remove “" + Library.title(book) + "” from this phone?")
                .setPositiveButton("Remove", (d, w) -> {
                    Library.remove(book);
                    prefs.edit().remove("pos:" + book.getName()).remove("pct:" + book.getName())
                            .remove("opened:" + book.getName()).apply();
                    ReadingWidget.update(this);
                    covers.remove(book.getName());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Import ----

    void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "text/plain"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != PICK || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        add(uris);
    }

    void add(List<Uri> uris) {
        Toast.makeText(this, "Adding…", Toast.LENGTH_SHORT).show();
        Context app = getApplicationContext();
        new Thread(() -> {
            List<String> lines = new ArrayList<>();
            for (Uri u : uris) {
                try {
                    lines.add("Added “" + Library.importUri(app, u) + "”");
                } catch (Epub.DrmException e) {
                    lines.add("This book is DRM-protected (locked to the store it came from), "
                            + "so Cute Reads can't open it. Look for a DRM-free copy.");
                } catch (Library.DuplicateException e) {
                    lines.add("“" + e.getMessage() + "” is already in your library");
                } catch (Exception | OutOfMemoryError e) {
                    lines.add("Couldn't read this file. Cute Reads opens EPUB and TXT books.");
                }
            }
            runOnUiThread(() -> {
                refresh();
                if (lines.size() == 1 && lines.get(0).startsWith("Added")) {
                    Toast.makeText(this, lines.get(0), Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(this, ui.day
                            ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                            : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setMessage(TextUtils.join("\n\n", lines))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    // ---- Grid ----

    class Adapter extends BaseAdapter {
        public int getCount() { return books.size(); }
        public Object getItem(int i) { return books.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            Holder h = convert != null ? (Holder) convert.getTag() : new Holder(parent.getContext());
            File book = books.get(i);
            int width = Math.max(1, ((GridView) parent).getColumnWidth());
            Bitmap cover = covers.get(book.getName());
            if (cover == null) {
                cover = Library.cover(book, width);
                if (cover == null) cover = Library.drawnCover(LibraryActivity.this, book, width);
                covers.put(book.getName(), cover);
            }
            h.cover.setImageBitmap(cover);
            h.title.setText(Library.title(book));
            boolean started = prefs.contains("pos:" + book.getName());
            h.progress.setText(started ? prefs.getInt("pct:" + book.getName(), 0) + "%" : "New");
            return h.view;
        }
    }

    class Holder {
        final LinearLayout view;
        final ImageView cover;
        final TextView title, progress;

        Holder(Context c) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            cover = new CoverView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setElevation(Ui.dp(c, 3));
            cover.setOutlineProvider(new ViewOutlineProvider() {
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(c, 4));
                }
            });
            cover.setClipToOutline(true);
            view.addView(cover, new LinearLayout.LayoutParams(-1, -2));
            title = ui.text(c, "", 13, ui.fg);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, Ui.dp(c, 8), 0, 0);
            view.addView(title);
            progress = ui.text(c, "", 12, ui.dim);
            view.addView(progress);
            view.setTag(this);
        }
    }

    /** Book-shaped: always 2:3. */
    static class CoverView extends ImageView {
        CoverView(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, width * 3 / 2);
        }
    }
}
