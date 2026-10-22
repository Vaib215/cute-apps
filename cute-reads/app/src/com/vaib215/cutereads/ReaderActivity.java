package com.vaib215.cutereads;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Just the page. Tap the page number for options; back goes to the library. */
public class ReaderActivity extends Activity {
    static final String EXTRA_BOOK = "book";
    static final int[] SLEEP_MINUTES = {0, 2, 5, 10, 30}; // 0 = never turn the screen off

    SharedPreferences prefs;
    PageView pages;
    File book;
    Sheet sheet;
    TextView subtitle;
    PopupWindow popup;
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable letScreenSleep =
            () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    /** Highlights {"s","e","t","n"?} and bookmarks {"b":true,"s","t"} of this book, in book order. */
    final List<JSONObject> marks = new ArrayList<>();
    /** The highlight entries of `marks`, index-aligned with pages.highlights. */
    final List<JSONObject> highlightMarks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        Library.copyBundled(this);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        pages = new PageView(this);
        readLookPrefs();
        pages.listener = new PageView.Listener() {
            public void onPageChanged(int offset) { savePosition(offset); }
            public void onMenu() { showSheet(); }
            public void onTap() { dismissPopup(); keepAwake(); }
            public void onSelect(int start, int end, float x, float y) { offerSelection(start, end, x, y); }
            public void onHighlightTap(int i, float x, float y) { offerHighlight(i, x, y); }
        };
        setContentView(pages);
        applyLight(getWindow());
        applyColors();

        String requested = getIntent().getStringExtra(EXTRA_BOOK);
        File f = new File(Library.dir(this), requested != null ? requested : prefs.getString("book", ""));
        if (!f.exists()) {
            List<File> all = Library.books(this);
            f = all.isEmpty() ? null : all.get(0);
        }
        if (f == null) openLibrary();
        else open(f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepAwake();
        // Silence notifications while reading, if the user granted Do Not Disturb access.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.isNotificationPolicyAccessGranted()
                && nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
            // Persisted, so a killed process still turns it back off next time.
            prefs.edit().putBoolean("silenced", true).apply();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissPopup();
        if (prefs.getBoolean("silenced", false)) {
            getSystemService(NotificationManager.class)
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            prefs.edit().putBoolean("silenced", false).apply();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(getWindow());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { pages.turn(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { pages.turn(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        openLibrary();
    }

    void openLibrary() {
        startActivity(new Intent(this, LibraryActivity.class));
        overridePendingTransition(0, 0);
        finish();
    }

    /** Screen stays on; with a timer set it may sleep that long after the last touch. */
    void keepAwake() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(letScreenSleep);
        int minutes = prefs.getInt("sleep", 0);
        if (minutes > 0) handler.postDelayed(letScreenSleep, minutes * 60_000L);
    }

    void savePosition(int offset) {
        keepAwake();
        dismissPopup();
        if (book == null) return;
        prefs.edit()
                .putInt("pos:" + book.getName(), offset)
                .putInt("pct:" + book.getName(), pages.percent())
                .apply();
    }

    // ---- The book ----

    void open(File f) {
        book = f;
        prefs.edit().putString("book", f.getName())
                .putLong("opened:" + f.getName(), System.currentTimeMillis()).apply();
        try {
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<Integer> breaks = new ArrayList<>();
            pages.setBook(format(raw, breaks), breaks, prefs.getInt("pos:" + f.getName(), 0));
            loadMarks();
        } catch (Exception e) {
            pages.setBook("Could not open " + f.getName(), new ArrayList<>(), 0);
        }
    }

    /** Paragraphs separated by blank lines; "# " paragraphs are headings that start a page. */
    CharSequence format(String raw, List<Integer> breaks) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int gap = Math.round(pages.textSizeSp * 0.6f * getResources().getDisplayMetrics().scaledDensity);
        for (String para : raw.replace("\r", "").split("\n\\s*\n")) {
            String p = para.trim().replaceAll("\\s*\n\\s*", " ");
            if (p.isEmpty()) continue;
            if (sb.length() > 0) {
                int s = sb.length();
                sb.append("\n\n");
                // Shrink the blank line so paragraphs get a small gap, not a full line.
                sb.setSpan(new AbsoluteSizeSpan(gap), s + 1, s + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int start = sb.length();
            if (p.startsWith("# ")) {
                sb.append(p.substring(2));
                int end = sb.length();
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                breaks.add(start);
            } else {
                sb.append(p);
            }
        }
        return sb;
    }

    // ---- Look ----

    void readLookPrefs() {
        pages.typeface = Ui.font(this, prefs.getInt("font", 0));
        pages.textSizeSp = prefs.getInt("size", 18);
    }

    /** Overrides screen brightness only inside the reader; the phone's setting is untouched. */
    void applyLight(android.view.Window w) {
        if (!prefs.contains("light")) return; // until first moved, follow the phone's brightness
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = prefs.getInt("light", 40) / 100f;
        w.setAttributes(lp);
    }

    void applyColors() {
        Ui ui = Ui.from(prefs);
        pages.setBackgroundColor(ui.bg);
        pages.textColor = ui.fg;
        pages.highlightColor = ui.day ? 0x80FFD54F : 0x66B8862B;
        pages.invalidate();
    }

    void relayout() {
        readLookPrefs();
        open(book); // reformat so paragraph gaps scale with the text
    }

    // ---- Options sheet ----

    void showSheet() {
        if (sheet != null && sheet.isShowing()) return;
        dismissPopup();
        Ui ui = Ui.from(prefs);
        sheet = new Sheet(this, ui);
        LinearLayout body = sheet.body;

        TextView title = ui.text(this, Library.title(book), 18, ui.fg);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title);
        subtitle = ui.text(this, progressText(), 14, ui.dim);
        body.addView(subtitle);

        // Night/day toggle, search, bookmark this page, highlights & bookmarks, contents, library:
        // a 3-column grid of pills.
        boolean marked = pages.bookmarked();
        boolean day = prefs.getBoolean("day", false);
        View[] buttons = {
                ui.pill(this, day ? R.drawable.ic_day : R.drawable.ic_night, day ? "Day" : "Night", false, v -> setDay(!day)),
                ui.pill(this, R.drawable.ic_search, "Find", false, v -> { sheet.dismiss(); showSearch(); }),
                ui.pill(this, marked ? R.drawable.ic_bookmark_on : R.drawable.ic_bookmark, "Saved", marked,
                        v -> { toggleBookmark(); sheet.dismiss(); showSheet(); }),
                ui.pill(this, R.drawable.ic_highlights, "Notes", false, v -> { sheet.dismiss(); showMarks(); }),
                ui.pill(this, R.drawable.ic_contents, "Contents", false, v -> { sheet.dismiss(); showContents(); }),
                ui.pill(this, R.drawable.ic_library, "Library", false, v -> { sheet.dismiss(); openLibrary(); }),
        };
        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            line.setPadding(0, Ui.dp(this, r == 0 ? 16 : 8), 0, 0);
            for (int c = 0; c < 3; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
                if (c > 0) lp.leftMargin = Ui.dp(this, 8);
                line.addView(buttons[r * 3 + c], lp);
            }
            body.addView(line);
        }

        // Go to page: type a number.
        LinearLayout go = row();
        go.setPadding(0, Ui.dp(this, 16), 0, 0);
        EditText field = ui.field(this, "Go to page  (1–" + pages.pageCount() + ")");
        go.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        Runnable jump = () -> {
            try {
                pages.goToPage(Integer.parseInt(field.getText().toString().trim()));
                sheet.dismiss();
            } catch (NumberFormatException ignored) {
            }
        };
        field.setOnEditorActionListener((v, action, e) -> { jump.run(); return true; });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(-2, -2);
        goLp.leftMargin = Ui.dp(this, 8);
        go.addView(ui.iconButton(this, R.drawable.ic_go, true, v -> jump.run()), goLp);
        body.addView(go);

        // Fonts, each name shown in its own face.
        LinearLayout fonts = row();
        int current = prefs.getInt("font", 0);
        for (int i = 0; i < Ui.FONTS.length; i++) {
            final int f = i;
            TextView chip = ui.chip(this, Ui.FONTS[i], i == current, v -> {
                prefs.edit().putInt("font", f).apply();
                sheet.dismiss();
                relayout();
                showSheet();
            });
            chip.setTypeface(Ui.font(this, i));
            fonts.addView(chip, gap());
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        scroll.addView(fonts);
        body.addView(scroll);

        // Brightness: small sun to big sun.
        body.addView(slider(ui, ui.icon(this, R.drawable.ic_day, 16, ui.dim),
                ui.icon(this, R.drawable.ic_day, 26, ui.fg),
                1, 100, prefs.getInt("light", 40), (v, done) -> {
                    prefs.edit().putInt("light", v).apply();
                    applyLight(getWindow());
                    applyLight(sheet.getWindow());
                }));
        // Warmth: white bulb to amber bulb.
        body.addView(slider(ui, ui.icon(this, R.drawable.ic_warm, 20, ui.dim),
                ui.icon(this, R.drawable.ic_warm, 22, 0xFFFFB04A),
                0, 100, prefs.getInt("warm", 0), (v, done) -> {
                    prefs.edit().putInt("warm", v).apply();
                    applyColors();
                }));
        // Text size: small A to big A.
        TextView small = ui.text(this, "A", 14, ui.dim);
        TextView big = ui.text(this, "A", 24, ui.fg);
        small.setTypeface(Ui.font(this, 0));
        big.setTypeface(Ui.font(this, 0));
        body.addView(slider(ui, small, big, 14, 32, prefs.getInt("size", 18),
                (v, done) -> relayoutWhenDone("size", v, done)));

        // Screen timer: ∞ keeps the screen on for as long as the book is open.
        LinearLayout timer = row();
        timer.setPadding(0, Ui.dp(this, 14), 0, 0);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22));
        iconLp.rightMargin = Ui.dp(this, 14);
        timer.addView(ui.icon(this, R.drawable.ic_timer, 22, ui.dim), iconLp);
        int sleep = prefs.getInt("sleep", 0);
        for (int m : SLEEP_MINUTES) {
            timer.addView(ui.chip(this, m == 0 ? "∞" : m + "m", m == sleep, v -> {
                prefs.edit().putInt("sleep", m).apply();
                keepAwake();
                sheet.dismiss();
                showSheet();
            }), gap());
        }
        HorizontalScrollView timerScroll = new HorizontalScrollView(this);
        timerScroll.setHorizontalScrollBarEnabled(false);
        timerScroll.addView(timer);
        body.addView(timerScroll);

        applyLight(sheet.getWindow());
        sheet.show();
    }

    void relayoutWhenDone(String key, int value, boolean done) {
        if (!done) return;
        prefs.edit().putInt(key, value).apply();
        relayout();
    }

    String progressText() {
        if (pages.pageCount() == 0) return "Opening…";
        return "Page " + pages.page() + " of " + pages.pageCount() + "  ·  " + pages.percent() + "%";
    }

    void setDay(boolean day) {
        prefs.edit().putBoolean("day", day).apply();
        applyColors();
        sheet.dismiss();
        showSheet(); // redraw the sheet in the new theme
    }

    // ---- Contents ----

    String chapterTitle(int offset) {
        CharSequence t = pages.text();
        int end = TextUtils.indexOf(t, '\n', offset);
        return t.subSequence(offset, end < 0 ? t.length() : end).toString().trim();
    }

    /** Chapter list with page numbers; the current chapter is highlighted. */
    void showContents() {
        Ui ui = Ui.from(prefs);
        Sheet contents = new Sheet(this, ui);
        contents.body.addView(heading(ui, "Contents"));

        List<Integer> chapters = pages.chapters();
        boolean addedStart = chapters.isEmpty() || pages.pageOf(chapters.get(0)) > 1;
        if (addedStart) chapters.add(0, 0);
        int current = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (pages.pageOf(chapters.get(i)) <= pages.page()) current = i;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        View currentRow = null;
        for (int i = 0; i < chapters.size(); i++) {
            int offset = chapters.get(i);
            int page = pages.pageOf(offset);
            LinearLayout row = row();
            row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            String name = addedStart && i == 0 ? "Beginning" : chapterTitle(offset);
            TextView t = ui.text(this, name, 16, i == current ? ui.fg : ui.dim);
            if (i == current) t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine();
            t.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(ui.text(this, String.valueOf(page), 14, ui.dim));
            row.setOnClickListener(v -> { contents.dismiss(); pages.goToPage(page); });
            list.addView(row);
            if (i == current) currentRow = row;
        }
        ScrollView scroll = listScroll(contents, list, chapters.size() > 10);
        final View target = currentRow;
        if (target != null) scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - scroll.getHeight() / 3)));
        contents.show();
    }

    // ---- Search ----

    void showSearch() {
        Ui ui = Ui.from(prefs);
        Sheet search = new Sheet(this, ui);
        EditText field = ui.field(this, "Search this book");
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setText(prefs.getString("lastSearch", ""));
        field.setSelection(field.getText().length());
        search.body.addView(field);
        TextView status = ui.text(this, "", 13, ui.dim);
        status.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        search.body.addView(status);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        listScroll(search, results, true);

        field.setOnEditorActionListener((v, action, e) -> {
            String q = field.getText().toString().trim();
            if (q.isEmpty()) return true;
            prefs.edit().putString("lastSearch", q).apply();
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(field.getWindowToken(), 0);
            status.setText("Searching…");
            results.removeAllViews();
            String text = pages.text().toString();
            new Thread(() -> {
                List<Integer> hits = find(text, q, 300);
                runOnUiThread(() -> {
                    status.setText(hits.isEmpty() ? "No matches"
                            : hits.size() + (hits.size() == 300 ? "+" : "") + " matches");
                    for (int at : hits) results.addView(searchRow(ui, search, text, at, q.length()));
                });
            }).start();
            return true;
        });
        search.typeInto(field);
        search.show();
    }

    static List<Integer> find(String text, String query, int limit) {
        List<Integer> hits = new ArrayList<>();
        String hay = text.toLowerCase(Locale.ROOT), needle = query.toLowerCase(Locale.ROOT);
        for (int i = hay.indexOf(needle); i >= 0 && hits.size() < limit; i = hay.indexOf(needle, i + 1)) {
            hits.add(i);
        }
        return hits;
    }

    View searchRow(Ui ui, Sheet search, String text, int at, int len) {
        int from = Math.max(0, at - 50), to = Math.min(text.length(), at + len + 70);
        String snippet = (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").trim()
                + (to < text.length() ? "…" : "");
        SpannableString s = new SpannableString(snippet);
        String match = text.substring(at, at + len).replaceAll("\\s+", " ");
        int i = snippet.indexOf(match);
        if (i >= 0) s.setSpan(new StyleSpan(Typeface.BOLD), i, i + match.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        TextView t = ui.text(this, s, 15, ui.fg);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);
        row.addView(ui.text(this, "Page " + pages.pageOf(at), 13, ui.dim));
        row.setOnClickListener(v -> { search.dismiss(); pages.show(at, at + len); });
        return row;
    }

    // ---- Highlights, notes, bookmarks: stored per book in name.hl ----

    File marksFile() {
        return new File(book.getParentFile(), Library.name(book) + ".hl");
    }

    void loadMarks() {
        marks.clear();
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(marksFile().toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) marks.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        index();
    }

    /** Sorts marks and hands highlight ranges and bookmark offsets to the page. */
    void index() {
        marks.sort((a, b) -> Integer.compare(a.optInt("s"), b.optInt("s")));
        highlightMarks.clear();
        pages.highlights.clear();
        pages.bookmarks.clear();
        for (JSONObject m : marks) {
            if (m.optBoolean("b")) {
                pages.bookmarks.add(m.optInt("s"));
            } else {
                highlightMarks.add(m);
                pages.highlights.add(new int[]{m.optInt("s"), m.optInt("e")});
            }
        }
        pages.invalidate();
    }

    void saveMarks() {
        JSONArray a = new JSONArray();
        for (JSONObject m : marks) a.put(m);
        try {
            Files.write(marksFile().toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        index();
    }

    void toggleBookmark() {
        int from = pages.pageStart(), to = pages.pageEnd();
        boolean removed = marks.removeIf(m -> m.optBoolean("b") && m.optInt("s") >= from && m.optInt("s") < to);
        if (!removed) {
            String t = pages.text().subSequence(from, Math.min(to, from + 160)).toString()
                    .replaceAll("\\s+", " ").trim();
            try {
                marks.add(new JSONObject().put("b", true).put("s", from).put("t", t));
            } catch (Exception ignored) {
            }
        }
        saveMarks();
    }

    JSONObject addHighlight(int start, int end) {
        JSONObject m = new JSONObject();
        try {
            m.put("s", start).put("e", end).put("t", pages.text().subSequence(start, end).toString());
        } catch (Exception ignored) {
        }
        marks.add(m);
        pages.clearSelection();
        saveMarks();
        return m;
    }

    void offerSelection(int start, int end, float x, float y) {
        showPopup(x, y,
                new String[]{"Highlight", "Note"},
                new Runnable[]{() -> addHighlight(start, end), () -> editNote(addHighlight(start, end))});
    }

    void offerHighlight(int index, float x, float y) {
        JSONObject m = highlightMarks.get(index);
        showPopup(x, y,
                new String[]{m.optString("n").isEmpty() ? "Note" : "Edit note", "Remove"},
                new Runnable[]{() -> editNote(m), () -> { marks.remove(m); saveMarks(); }});
    }

    void editNote(JSONObject m) {
        Ui ui = Ui.from(prefs);
        EditText input = new EditText(this);
        input.setText(m.optString("n"));
        input.setHint("Your note");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog d = new AlertDialog.Builder(this, ui.day
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Note")
                .setView(box)
                .setPositiveButton("Save", (dlg, w) -> {
                    try {
                        String n = input.getText().toString().trim();
                        if (n.isEmpty()) m.remove("n");
                        else m.put("n", n);
                    } catch (Exception ignored) {
                    }
                    saveMarks();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    /** A small pill of actions just above the text they act on. */
    void showPopup(float x, float y, String[] labels, Runnable[] actions) {
        dismissPopup();
        Ui ui = Ui.from(prefs);
        LinearLayout pill = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.fg);
        bg.setCornerRadius(Ui.dp(this, 20));
        pill.setBackground(bg);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor((ui.sheet & 0x00FFFFFF) | 0x55000000);
                pill.addView(divider, new LinearLayout.LayoutParams(Ui.dp(this, 1), -1));
            }
            TextView t = ui.text(this, labels[i], 15, ui.sheet);
            t.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
            Runnable action = actions[i];
            t.setOnClickListener(v -> { dismissPopup(); action.run(); });
            pill.addView(t);
        }
        pill.measure(0, 0);
        popup = new PopupWindow(pill, -2, -2, false);
        popup.setElevation(Ui.dp(this, 6));
        int px = Math.round(Math.max(Ui.dp(this, 12),
                Math.min(pages.getWidth() - pill.getMeasuredWidth() - Ui.dp(this, 12), x - pill.getMeasuredWidth() / 2f)));
        int py = Math.round(y - pill.getMeasuredHeight() - Ui.dp(this, 10));
        if (py < Ui.dp(this, 8)) py = Math.round(y + Ui.dp(this, 40));
        popup.showAtLocation(pages, Gravity.NO_GRAVITY, px, py);
    }

    void dismissPopup() {
        if (popup != null) popup.dismiss();
        popup = null;
    }

    /** Highlights (with notes) and bookmarks in book order, with pages; tap to go there. */
    void showMarks() {
        Ui ui = Ui.from(prefs);
        Sheet list = new Sheet(this, ui);
        list.body.addView(heading(ui, "Notes"));

        if (marks.isEmpty()) {
            TextView none = ui.text(this, "Nothing yet. Press and hold a word and drag to highlight; "
                    + "bookmark a page from this panel.", 15, ui.dim);
            none.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
            list.body.addView(none);
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (JSONObject m : marks) {
            boolean bookmark = m.optBoolean("b");
            int start = m.optInt("s");
            int page = pages.pageOf(start);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

            LinearLayout where = row();
            if (bookmark) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16));
                lp.rightMargin = Ui.dp(this, 6);
                where.addView(ui.icon(this, R.drawable.ic_bookmark_on, 16, ui.dim), lp);
            }
            where.addView(ui.text(this, (bookmark ? "Bookmark · " : "") + "Page " + page, 13, ui.dim));
            row.addView(where);

            TextView quote = ui.text(this, m.optString("t"), bookmark ? 14 : 16, bookmark ? ui.dim : ui.fg);
            quote.setTypeface(Ui.font(this, prefs.getInt("font", 0)));
            quote.setMaxLines(bookmark ? 2 : 4);
            quote.setEllipsize(TextUtils.TruncateAt.END);
            quote.setPadding(0, Ui.dp(this, 4), 0, 0);
            row.addView(quote);

            String note = m.optString("n");
            if (!note.isEmpty()) {
                TextView n = ui.text(this, note, 14, ui.fg);
                n.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), 0, 0);
                LinearLayout noteRow = row();
                View line = new View(this);
                line.setBackgroundColor(pages.highlightColor | 0xFF000000);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(Ui.dp(this, 3), -1);
                lineLp.topMargin = Ui.dp(this, 6);
                noteRow.addView(line, lineLp);
                noteRow.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(noteRow);
            }
            row.setOnClickListener(v -> { list.dismiss(); pages.goToPage(page); });
            rows.addView(row);
        }
        listScroll(list, rows, marks.size() > 4);
        list.show();
    }

    // ---- helpers ----

    TextView heading(Ui ui, String text) {
        TextView h = ui.text(this, text, 18, ui.fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, Ui.dp(this, 8));
        return h;
    }

    /** A list inside a sheet; long lists get a fixed height and scroll. */
    ScrollView listScroll(Sheet s, View content, boolean tall) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f);
        s.body.addView(scroll, new LinearLayout.LayoutParams(-1, tall ? max : -2));
        return scroll;
    }

    interface Change { void on(int value, boolean done); }

    /** A slider between two icons that show its ends. */
    LinearLayout slider(Ui ui, View low, View high, int min, int max, int value, Change change) {
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 10), 0, 0);
        if (low instanceof TextView) ((TextView) low).setGravity(Gravity.CENTER);
        if (high instanceof TextView) ((TextView) high).setGravity(Gravity.CENTER);
        r.addView(low, new LinearLayout.LayoutParams(Ui.dp(this, 28), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        ui.tint(bar);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean user) { if (user) change.on(v, false); }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { change.on(b.getProgress(), true); }
        });
        r.addView(bar, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(high, new LinearLayout.LayoutParams(Ui.dp(this, 28), -2));
        return r;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 8);
        return lp;
    }
}
