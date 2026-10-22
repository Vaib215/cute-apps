package com.vaib215.cutereads;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Books live in the app's external files dir as name.txt (reader format, see {@link Epub}),
 * name.meta (title=, author=), and optionally name.jpg (cover), name.toc (contents),
 * name.files/ (pictures) and name.hl (highlights, notes, bookmarks).
 */
final class Library {
    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "books");
        d.mkdirs();
        return d;
    }

    static List<File> books(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".txt"));
        List<File> list = new ArrayList<>();
        if (files != null) for (File f : files) list.add(f);
        return list;
    }

    static String name(File book) {
        return book.getName().replaceFirst("\\.txt$", "");
    }

    static Properties meta(File book) {
        Properties p = new Properties();
        File m = new File(book.getParentFile(), name(book) + ".meta");
        try (InputStream in = new FileInputStream(m)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        return p;
    }

    static String title(File book) {
        String t = meta(book).getProperty("title");
        if (t != null && !t.isEmpty()) return t;
        StringBuilder out = new StringBuilder();
        for (String w : name(book).split("[-_ ]+")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    static String author(File book) {
        return meta(book).getProperty("author", "");
    }

    static void remove(File book) {
        for (String ext : new String[]{".txt", ".meta", ".jpg", ".hl", ".toc"}) {
            new File(book.getParentFile(), name(book) + ext).delete();
        }
        File images = new File(book.getParentFile(), name(book) + ".files");
        File[] list = images.listFiles();
        if (list != null) for (File f : list) f.delete();
        images.delete();
    }

    // ---- Covers ----

    /** The book's own cover, scaled down to about `width`, or null. */
    static Bitmap cover(File book, int width) {
        File f = new File(book.getParentFile(), name(book) + ".jpg");
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static final int[] SPINES = {
            0xFF2E3A59, 0xFF5B3A29, 0xFF2F4F4F, 0xFF6B2D2D, 0xFF3D3D3D, 0xFF4A3B5C, 0xFF35524A};

    /** A plain typeset cover, like Kindle draws for books without one. */
    static Bitmap drawnCover(Context c, File book, int width) {
        int height = width * 3 / 2;
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        String title = title(book);
        canvas.drawColor(SPINES[Math.abs(title.hashCode()) % SPINES.length]);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(width / 120f);
        border.setColor(0x99D9C7A0);
        float inset = width / 14f;
        canvas.drawRect(inset, inset, width - inset, height - inset, border);

        Typeface serif = Ui.font(c, 0);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFF2EBDD);
        tp.setTypeface(Typeface.create(serif, Typeface.BOLD));
        tp.setTextSize(width / 8.5f);
        int textWidth = Math.round(width - 4 * inset);
        StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), tp, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).build();
        canvas.save();
        canvas.translate(2 * inset, height * 0.28f);
        t.draw(canvas);
        canvas.restore();

        String author = author(book);
        if (!author.isEmpty()) {
            TextPaint ap = new TextPaint(tp);
            ap.setTypeface(serif);
            ap.setTextSize(width / 15f);
            ap.setColor(0xCCF2EBDD);
            StaticLayout a = StaticLayout.Builder.obtain(author, 0, author.length(), ap, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build();
            canvas.save();
            canvas.translate(2 * inset, height - 2.2f * inset - a.getHeight());
            a.draw(canvas);
            canvas.restore();
        }
        return b;
    }

    // ---- Import from Files ----

    static class DuplicateException extends IOException {
        DuplicateException(String title) { super(title); }
    }

    /** Imports an EPUB or plain-text file; returns the new book's title. */
    static String importUri(Context c, Uri uri) throws IOException {
        String display = displayName(c, uri);
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            copy(in, buf);
            data = buf.toByteArray();
        }
        String fallback = display.replaceFirst("\\.[^.]+$", "");
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        if (zip) {
            Epub e = Epub.read(data);
            String title = e.title.isEmpty() ? fallback : e.title;
            save(c, title, e.author, e.text.toString(), e.cover, e.images, e.tocTitles, e.tocLevels);
            return title;
        }
        save(c, fallback, "", plainText(new String(data, StandardCharsets.UTF_8)), null,
                new HashMap<>(), new ArrayList<>(), new ArrayList<>());
        return fallback;
    }

    /** Plain text; Project Gutenberg files also get their _italics_ and CHAPTER headings. */
    static String plainText(String text) {
        text = text.replace("\r", "").replaceAll(Epub.MARKERS, "");
        Matcher start = Pattern.compile("(?m)^\\*\\*\\* ?START OF.*\\*\\*\\*$").matcher(text);
        if (!start.find()) return text;
        Matcher end = Pattern.compile("(?m)^\\*\\*\\* ?END OF.*\\*\\*\\*$").matcher(text);
        text = text.substring(start.end(), end.find() ? end.start() : text.length());
        text = text.replaceAll("_([^_\\n]{1,300}?)_", Epub.ITALIC_ON + "$1" + Epub.ITALIC_OFF);
        StringBuilder out = new StringBuilder();
        for (String p : text.split("\n\\s*\n")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            boolean heading = !t.contains("\n") && t.length() < 60 && t.matches("[^a-z]*[A-Z][^a-z]*");
            out.append(heading ? "# " : "").append(t).append("\n\n");
        }
        return out.toString();
    }

    private static void save(Context c, String title, String author, String text, byte[] cover,
                             Map<String, byte[]> images, List<String> tocTitles, List<Integer> tocLevels)
            throws IOException {
        File d = dir(c);
        for (File other : books(c)) {
            if (title(other).equalsIgnoreCase(title) && author(other).equalsIgnoreCase(author)) {
                throw new DuplicateException(title);
            }
        }
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "book";
        String name = slug;
        for (int i = 2; new File(d, name + ".txt").exists(); i++) name = slug + "-" + i;

        if (!images.isEmpty()) {
            File imgDir = new File(d, name + ".files");
            imgDir.mkdirs();
            for (Map.Entry<String, byte[]> img : images.entrySet()) {
                Files.write(new File(imgDir, img.getKey()).toPath(), img.getValue());
            }
        }
        if (!tocTitles.isEmpty()) {
            org.json.JSONArray toc = new org.json.JSONArray();
            try {
                for (int i = 0; i < tocTitles.size(); i++) {
                    toc.put(new org.json.JSONObject().put("t", tocTitles.get(i)).put("l", tocLevels.get(i)));
                }
            } catch (org.json.JSONException ignored) {
            }
            Files.write(new File(d, name + ".toc").toPath(), toc.toString().getBytes(StandardCharsets.UTF_8));
        }
        Properties p = new Properties();
        p.setProperty("title", title);
        p.setProperty("author", author);
        try (OutputStream out = new FileOutputStream(new File(d, name + ".meta"))) {
            p.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), null);
        }
        if (cover != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(cover, 0, cover.length);
            if (bmp != null) {
                try (OutputStream out = new FileOutputStream(new File(d, name + ".jpg"))) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }
            }
        }
        // The text goes last: a book shows up in the library only once it is complete.
        Files.write(new File(d, name + ".txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    // ---- small helpers ----

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Book" : last;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }
}
