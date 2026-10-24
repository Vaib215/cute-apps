package com.vaib215.cutenotes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.mlkit.vision.digitalink.recognition.Ink;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One note. Text on top, behaving like any text: tap to move the cursor, hold to select.
 * Below it, a pad to write on, big; each word turns into text at the cursor (replacing the
 * selection, if any). The strip between them offers other readings of the last word.
 * Scribble can be switched off for the keyboard, and the mic takes dictation.
 */
public class WriteActivity extends Activity implements InkView.Listener, Voice.Listener {
    static final String EXTRA_NOTE = "note";
    private static final long[] PAUSES = {400, 600, 1000};
    private static final String[] PAUSE_NAMES = {"0.4 s", "0.6 s", "1 s"};

    private SharedPreferences prefs;
    private Ui ui;
    private File file;
    private EditText text;
    private InkView pad;
    private LinearLayout strip;
    private ImageView scribbleKey, micKey;
    private View space, back, enter;
    private boolean scribble;
    private Voice voice;
    private AutoCorrect autoCorrect;
    private String corrected; // what was written, if auto-correct changed the last word
    private String heard, problem, preview;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable save = this::save;

    // The last thing written or said, so it can be swapped for another reading or joined to
    // the word before. `quiet` marks our own edits, which shouldn't make us forget it.
    private int wordStart = -1, wordEnd = -1;
    private boolean wordSpaced, quiet;
    private List<String> readings = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Ui.prefs(this);
        scribble = prefs.getBoolean("scribble", true);
        String path = getIntent().getStringExtra(EXTRA_NOTE);
        file = path != null ? new File(path) : Notes.create(this);
        voice = new Voice(this, this);
        build();
        if (state != null) text.setSelection(Math.min(state.getInt("cursor"), text.length()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Handwriting hw = Handwriting.get();
        hw.listen(this::showStrip);
        hw.start();
        autoCorrect = prefs.getBoolean("autocorrect", true) ? new AutoCorrect(this) : null;
        showStrip();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voice.isOn()) setVoice(false);
        save();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("cursor", text.getSelectionStart());
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        build(); // the pad is sized from the screen
    }

    private void save() {
        handler.removeCallbacks(save);
        Notes.write(file, text.getText().toString());
        NotesWidget.update(this);
    }

    // ---- Layout ----

    private void build() {
        ui = Ui.from(this);
        ui.bars(this);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), 0);
        top.addView(ui.iconButton(this, R.drawable.ic_back, v -> finish()));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(ui.iconButton(this, R.drawable.ic_more, v -> showSheet()));

        String saved = text == null ? Notes.read(file) : text.getText().toString();
        int cursor = text == null ? saved.length() : text.getSelectionStart();
        text = new EditText(this) {
            @Override
            protected void onSelectionChanged(int start, int end) {
                super.onSelectionChanged(start, end);
                // Moving the cursor away from the last word means we're done with it.
                if (!quiet && wordStart >= 0 && (start != wordEnd || end != wordEnd)) forgetWord();
                if (pad != null) showLine();
            }
        };
        text.setBackground(null);
        text.setGravity(Gravity.TOP | Gravity.START);
        int font = prefs.getInt("font", 0);
        text.setTypeface(Ui.font(this, font));
        text.setTextSize(prefs.getInt("size", 24) * Ui.fontScale(font));
        text.setLineSpacing(0, 1.2f);
        text.setTextColor(ui.fg);
        text.setHintTextColor(ui.dim);
        text.setHint(scribble ? "Write on the pad" : "Type something");
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setShowSoftInputOnFocus(!scribble);
        int side = Ui.dp(this, 20);
        text.setPadding(side, Ui.dp(this, 4), side, side);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable caret = new GradientDrawable();
            caret.setColor(Ui.INK);
            caret.setSize(Ui.dp(this, 2), 1);
            text.setTextCursorDrawable(caret);
        }
        quiet = true;
        text.setText(saved);
        text.setSelection(Math.min(cursor, saved.length()));
        quiet = false;
        text.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                showLine();
                handler.removeCallbacks(save);
                handler.postDelayed(save, 1000);
            }
        });

        strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        HorizontalScrollView stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.addView(strip, new FrameLayout.LayoutParams(-2, -1));

        this.pad = new InkView(this, ui);
        this.pad.listener = this;
        this.pad.pause = PAUSES[prefs.getInt("pause", 1)];
        this.pad.setFont(Ui.font(this, font), Ui.fontScale(font));
        GradientDrawable padBg = new GradientDrawable();
        padBg.setColor(ui.sheet);
        padBg.setCornerRadius(Ui.dp(this, 20));
        this.pad.setBackground(padBg);
        showLine();

        LinearLayout keys = new LinearLayout(this);
        int k = Ui.dp(this, 12);
        keys.setPadding(k, Ui.dp(this, 8), k, k);
        int h = Ui.dp(this, 56), gap = Ui.dp(this, 8);
        scribbleKey = ui.key(this, R.drawable.ic_scribble, false, v -> setScribble(!scribble));
        micKey = ui.key(this, R.drawable.ic_mic, false, v -> setVoice(!voice.isOn()));
        space = ui.key(this, R.drawable.ic_space, false, v -> type(" "));
        back = ui.key(this, R.drawable.ic_backspace, false, null);
        Ui.repeat(back, this::backspace);
        enter = ui.key(this, R.drawable.ic_enter, false, v -> type("\n"));
        keys.addView(scribbleKey, new LinearLayout.LayoutParams(Ui.dp(this, 64), h));
        keys.addView(micKey, keyParams(0, Ui.dp(this, 64), h, gap));
        keys.addView(space, keyParams(2, 0, h, gap));
        keys.addView(back, keyParams(1.2f, 0, h, gap));
        keys.addView(enter, keyParams(1.2f, 0, h, gap));

        // Upright: text, then the pad under it. Sideways: text on the left, pad on the right,
        // as tall as the screen allows.
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(ui.bg);
        boolean sideways = scribble
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout notePart = root, padPart = root;
        if (sideways) {
            notePart = new LinearLayout(this);
            padPart = new LinearLayout(this);
            notePart.setOrientation(LinearLayout.VERTICAL);
            padPart.setOrientation(LinearLayout.VERTICAL);
            root.addView(notePart, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(padPart, new LinearLayout.LayoutParams(0, -1, 1.2f));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
        }
        notePart.addView(top);
        notePart.addView(text, new LinearLayout.LayoutParams(-1, 0, 1));
        padPart.addView(stripScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        LinearLayout.LayoutParams pp = sideways
                ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.36f));
        pp.leftMargin = pp.rightMargin = Ui.dp(this, 12);
        padPart.addView(this.pad, pp);
        padPart.addView(keys);

        setContentView(root);
        text.requestFocus();
        showMode();
    }

    private static LinearLayout.LayoutParams keyParams(float weight, int width, int height, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height, weight);
        p.leftMargin = left;
        return p;
    }

    /** Shows the pad and its keys when scribbling, and the state of the pen and mic keys. */
    private void showMode() {
        int v = scribble ? View.VISIBLE : View.GONE;
        pad.setVisibility(v);
        space.setVisibility(v);
        back.setVisibility(v);
        enter.setVisibility(v);
        paintKey(scribbleKey, scribble ? ui.fg : 0);
        paintKey(micKey, voice.isOn() ? Ui.INK : 0);
        showStrip();
    }

    /** Fills a key with `fill`, or back to plain with 0. */
    private void paintKey(ImageView key, int fill) {
        ((GradientDrawable) key.getBackground()).setColor(fill == 0 ? ui.sheet : fill);
        key.setImageTintList(ColorStateList.valueOf(fill == 0 ? ui.fg : fill == Ui.INK ? 0xFFFFFFFF : ui.sheet));
    }

    /**
     * What the strip says: what's being heard, a problem, setup progress, what's being
     * written, or other readings of the last word.
     */
    private void showStrip() {
        strip.removeAllViews();
        if (voice.isOn()) {
            TextView t = ui.text(this, heard == null ? "Listening…" : heard, 16,
                    heard == null ? ui.dim : ui.fg);
            t.setSingleLine();
            t.setEllipsize(TextUtils.TruncateAt.START);
            t.setMaxWidth(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 24));
            strip.addView(t);
            return;
        }
        if (problem != null) {
            strip.addView(ui.text(this, problem, 14, Ui.INK));
            return;
        }
        Handwriting.State s = Handwriting.get().state();
        if (scribble && s != Handwriting.State.READY) {
            String msg = s == Handwriting.State.DOWNLOADING
                    ? "Getting handwriting ready (about 20 MB, just once)…"
                    : s == Handwriting.State.FAILED
                    ? "Handwriting needs the internet once to download. Tap to try again."
                    : "Getting handwriting ready…";
            TextView t = ui.text(this, msg, 14, s == Handwriting.State.FAILED ? Ui.INK : ui.dim);
            if (s == Handwriting.State.FAILED) t.setOnClickListener(v -> Handwriting.get().start());
            strip.addView(t);
            return;
        }
        if (preview != null) {
            strip.addView(ui.text(this, preview + "…", 18, ui.dim));
            return;
        }
        if (wordStart < 0) return;
        if (wordSpaced) {
            strip.addView(ui.chip(this, "Join ⇤", false, v -> join()), chipParams());
        }
        String current = text.getText().subSequence(wordStart + (wordSpaced ? 1 : 0), wordEnd).toString();
        for (String r : readings) {
            if (r.equals(current)) continue;
            strip.addView(ui.chip(this, r, false, v -> swap(r)), chipParams());
        }
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = Ui.dp(this, 8);
        return p;
    }

    // ---- Modes ----

    private void setScribble(boolean on) {
        scribble = on;
        prefs.edit().putBoolean("scribble", on).apply();
        preview = null;
        build(); // sideways, the layout changes with the pad
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (on) {
            imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        } else {
            text.post(() -> {
                text.requestFocus();
                imm.showSoftInput(text, 0);
            });
        }
    }

    private void setVoice(boolean on) {
        problem = null;
        heard = null;
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (on) voice.start();
        else voice.stop();
        showMode();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            setVoice(true);
        } else {
            problem = "Voice needs the microphone. Allow it in Settings › Apps › Cute Notes.";
            showStrip();
        }
    }

    // ---- Writing ----

    @Override
    public void onWriting(Ink written, InkView.Group group) {
        if (Handwriting.get().state() != Handwriting.State.READY) return;
        Handwriting.get().recognize(written, before(), pad.getWidth(), pad.getHeight(), found -> {
            if (!pad.isOpen(group) || found.isEmpty()) return;
            preview = found.get(0);
            showStrip();
        });
    }

    @Override
    public void onInk(Ink written, InkView.Group group) {
        preview = null;
        problem = null;
        if (Handwriting.get().state() != Handwriting.State.READY) {
            pad.done(group);
            showStrip();
            return;
        }
        Handwriting.get().recognize(written, before(), pad.getWidth(), pad.getHeight(), found -> {
            pad.done(group);
            if (found.isEmpty()) showStrip();
            else if (autoCorrect == null) insert(found);
            else {
                List<String> fixed = autoCorrect.correct(found);
                insert(fixed);
                corrected = fixed.get(0).equals(found.get(0)) ? null : found.get(0);
            }
        });
    }

    /** The text just before the cursor, which helps tell ambiguous letters apart. */
    private String before() {
        int at = text.getSelectionStart();
        return text.getText().subSequence(Math.max(0, at - 20), at).toString();
    }

    /** Shows the line with the cursor on the pad, to strike words out of. */
    private void showLine() {
        int at = text.getSelectionStart();
        CharSequence all = text.getText();
        int start = at, end = at;
        while (start > 0 && all.charAt(start - 1) != '\n') start--;
        while (end < all.length() && all.charAt(end) != '\n') end++;
        pad.setLine(all.subSequence(start, end).toString(), start, at - start);
    }

    /** Lines through words on the pad: delete them, each with the spaces before it. */
    @Override
    public void onStrike(List<int[]> words) {
        Editable e = text.getText();
        for (int i = words.size() - 1; i >= 0; i--) {
            int a = words.get(i)[0], b = words.get(i)[1];
            if (a > 0 && e.charAt(a - 1) == ' ') {
                while (a > 0 && e.charAt(a - 1) == ' ') a--;
            } else {
                while (b < e.length() && e.charAt(b) == ' ') b++; // first on its line: the space after
            }
            e.delete(a, b);
        }
        forgetWord();
    }

    // ---- Voice ----

    @Override
    public void onHeard(String partial) {
        heard = partial;
        showStrip();
    }

    @Override
    public void onSaid(List<String> guesses) {
        heard = null;
        boolean capital = startsSentence();
        List<String> out = new ArrayList<>();
        for (String g : guesses) {
            g = g.trim();
            if (g.isEmpty()) continue;
            if (capital) g = g.substring(0, 1).toUpperCase() + g.substring(1);
            if (!out.contains(g)) out.add(g);
        }
        if (!out.isEmpty()) insert(out);
        showStrip();
    }

    @Override
    public void onStopped(String why) {
        problem = why;
        heard = null;
        showMode();
    }

    /** At the start of the note, a line or a sentence? */
    private boolean startsSentence() {
        CharSequence s = text.getText();
        int i = text.getSelectionStart() - 1;
        while (i >= 0 && s.charAt(i) == ' ') i--;
        return i < 0 || ".!?\n".indexOf(s.charAt(i)) >= 0;
    }

    // ---- Editing ----

    /** Puts the best reading at the cursor, with a space before it if it starts a new word. */
    private void insert(List<String> found) {
        String best = found.get(0);
        Editable e = text.getText();
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        boolean space = start > 0 && !Character.isWhitespace(e.charAt(start - 1))
                && Character.isLetterOrDigit(best.codePointAt(0));
        String s = (space ? " " : "") + best;
        quiet = true;
        e.replace(start, end, s);
        text.setSelection(start + s.length());
        quiet = false;
        wordStart = start;
        wordEnd = start + s.length();
        wordSpaced = space;
        readings = found;
        showStrip();
    }

    private void swap(String reading) {
        // Putting back what was written, after a correction: it was meant, so learn it.
        if (reading.equals(corrected) && autoCorrect != null) autoCorrect.learn(reading);
        corrected = null;
        int from = wordStart + (wordSpaced ? 1 : 0);
        quiet = true;
        text.getText().replace(from, wordEnd, reading);
        wordEnd = from + reading.length();
        text.setSelection(wordEnd);
        quiet = false;
        showStrip();
    }

    /** The last word was the end of the one before it: take out the space. */
    private void join() {
        quiet = true;
        text.getText().delete(wordStart, wordStart + 1);
        wordEnd--;
        wordSpaced = false;
        text.setSelection(wordEnd);
        quiet = false;
        showStrip();
    }

    private void forgetWord() {
        if (wordStart < 0) return;
        wordStart = wordEnd = -1;
        showStrip();
    }

    private void type(String s) {
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        text.getText().replace(start, end, s);
        text.setSelection(start + s.length());
        forgetWord();
    }

    /** Right after writing a word, takes the whole word away; otherwise one letter. */
    private void backspace() {
        Editable e = text.getText();
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        if (start != end) {
            e.delete(start, end);
        } else if (wordStart >= 0 && start == wordEnd) {
            e.delete(wordStart, wordEnd);
        } else if (start > 0) {
            e.delete(e.toString().offsetByCodePoints(start, -1), start);
        }
        forgetWord();
    }

    // ---- Options ----

    private void showSheet() {
        Sheet sheet = new Sheet(this, ui);
        int gap = Ui.dp(this, 8);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.addView(ui.pill(this, ui.day ? R.drawable.ic_night : R.drawable.ic_day,
                ui.day ? "Night" : "Day", false, v -> {
                    sheet.dismiss();
                    prefs.edit().putBoolean("day", !ui.day).apply();
                    build();
                }), cell(gap, 0));
        grid.addView(ui.pill(this, R.drawable.ic_share, "Share", false, v -> {
            sheet.dismiss();
            share();
        }), cell(gap, 1));
        grid.addView(ui.pill(this, R.drawable.ic_delete, "Delete", false, v -> {
            sheet.dismiss();
            confirmDelete();
        }), cell(gap, 2));
        sheet.body.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        sheet.body.addView(label(R.drawable.ic_font, "Font"));
        GridLayout fonts = new GridLayout(this);
        fonts.setColumnCount(3);
        int chosenFont = prefs.getInt("font", 0);
        for (int i = 0; i < Ui.FONTS.length; i++) {
            int which = i;
            TextView c = ui.chip(this, Ui.FONTS[i], i == chosenFont, v -> {
                prefs.edit().putInt("font", which).apply();
                sheet.dismiss();
                text.setTypeface(Ui.font(this, which));
                text.setTextSize(prefs.getInt("size", 24) * Ui.fontScale(which));
                pad.setFont(Ui.font(this, which), Ui.fontScale(which));
            });
            c.setTypeface(Ui.font(this, i));
            c.setTextSize(15 * Ui.fontScale(i));
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            GridLayout.LayoutParams fp = cell(gap, i % 3);
            fp.topMargin = i >= 3 ? gap : 0;
            fonts.addView(c, fp);
        }
        sheet.body.addView(fonts, new LinearLayout.LayoutParams(-1, -2));

        sheet.body.addView(label(R.drawable.ic_size, "Text size"));
        SeekBar size = new SeekBar(this);
        ui.tint(size);
        size.setMax(40 - 16);
        size.setProgress(prefs.getInt("size", 24) - 16);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (!user) return;
                prefs.edit().putInt("size", p + 16).apply();
                text.setTextSize((p + 16) * Ui.fontScale(prefs.getInt("font", 0)));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        sheet.body.addView(size);

        sheet.body.addView(label(R.drawable.ic_timer, "Turn writing into text after a pause of"));
        GridLayout pauses = new GridLayout(this);
        pauses.setColumnCount(3);
        int chosen = prefs.getInt("pause", 1);
        for (int i = 0; i < PAUSES.length; i++) {
            int which = i;
            TextView c = ui.chip(this, PAUSE_NAMES[i], i == chosen, v -> {
                prefs.edit().putInt("pause", which).apply();
                pad.pause = PAUSES[which];
                sheet.dismiss();
            });
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            pauses.addView(c, cell(gap, i));
        }
        sheet.body.addView(pauses, new LinearLayout.LayoutParams(-1, -2));

        sheet.body.addView(label(R.drawable.ic_spell, "Auto-correct handwriting"));
        LinearLayout ac = new LinearLayout(this);
        boolean acOn = prefs.getBoolean("autocorrect", true);
        for (boolean on : new boolean[]{true, false}) {
            ac.addView(ui.chip(this, on ? "On" : "Off", on == acOn, v -> {
                prefs.edit().putBoolean("autocorrect", on).apply();
                autoCorrect = on ? new AutoCorrect(this) : null;
                sheet.dismiss();
            }), chipParams());
        }
        sheet.body.addView(ac);

        sheet.body.addView(label(R.drawable.ic_widgets, "Home-screen widget background"));
        LinearLayout bgs = new LinearLayout(this);
        int bg = prefs.getInt("widgetBg", 0);
        for (int i = 0; i < NotesWidget.BACKGROUND_NAMES.length; i++) {
            int which = i;
            bgs.addView(ui.chip(this, NotesWidget.BACKGROUND_NAMES[i], i == bg, v -> {
                prefs.edit().putInt("widgetBg", which).apply();
                NotesWidget.update(this);
                sheet.dismiss();
            }), chipParams());
        }
        sheet.body.addView(bgs);
        sheet.show();
    }

    private GridLayout.LayoutParams cell(int gap, int column) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(column, 1f));
        p.width = 0;
        p.height = Ui.dp(this, 46);
        if (column > 0) p.leftMargin = gap;
        return p;
    }

    private LinearLayout label(int icon, String s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 10));
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(ui.dim));
        row.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        TextView t = ui.text(this, s, 14, ui.dim);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private void share() {
        String s = text.getText().toString().trim();
        if (s.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(send, null));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setMessage("Delete this note?")
                .setPositiveButton("Delete", (d, w) -> {
                    text.setText("");
                    save();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
