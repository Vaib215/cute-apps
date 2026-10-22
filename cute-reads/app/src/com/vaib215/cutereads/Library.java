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
import android.text.Html;
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
import java.net.URLDecoder;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Books live in the app's external files dir as name.txt (reader format), name.meta
 * (title=, author=) and an optional name.jpg cover.
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
        for (String ext : new String[]{".txt", ".meta", ".jpg", ".hl"}) {
            new File(book.getParentFile(), name(book) + ext).delete();
        }
    }

    static void copyBundled(Context c) {
        try {
            for (String n : c.getAssets().list("books")) {
                File dest = new File(dir(c), n);
                if (dest.exists()) continue;
                try (InputStream in = c.getAssets().open("books/" + n);
                     OutputStream out = new FileOutputStream(dest)) {
                    copy(in, out);
                }
            }
        } catch (IOException ignored) {
        }
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

    /** Imports an EPUB or plain-text file; returns the new book's title. */
    static String importUri(Context c, Uri uri) throws IOException {
        String display = displayName(c, uri);
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            copy(in, buf);
            data = buf.toByteArray();
        }
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        if (zip) return importEpub(c, data, display);

        String text = new String(data, StandardCharsets.UTF_8).replace("\r", "");
        Matcher start = Pattern.compile("(?m)^\\*\\*\\* ?START OF.*\\*\\*\\*$").matcher(text);
        Matcher end = Pattern.compile("(?m)^\\*\\*\\* ?END OF.*\\*\\*\\*$").matcher(text);
        if (start.find()) text = text.substring(start.end(), end.find() ? end.start() : text.length());
        String title = display.replaceFirst("\\.[^.]+$", "");
        save(c, title, "", text, null);
        return title;
    }

    private static String importEpub(Context c, byte[] data, String display) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                copy(z, buf);
                files.put(e.getName(), buf.toByteArray());
            }
        }
        String container = str(files.get("META-INF/container.xml"));
        Matcher m = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(container);
        if (!m.find()) throw new IOException("Not an EPUB");
        String opfPath = m.group(1);
        String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        String opf = str(files.get(opfPath));

        String title = firstTag(opf, "dc:title");
        if (title.isEmpty()) title = display.replaceFirst("\\.[^.]+$", "");
        String author = firstTag(opf, "dc:creator");

        Map<String, Map<String, String>> items = new HashMap<>();
        Matcher im = Pattern.compile("<(?:\\w+:)?item\\b[^>]*>").matcher(opf);
        while (im.find()) {
            Map<String, String> a = attrs(im.group());
            items.put(a.getOrDefault("id", ""), a);
        }

        StringBuilder text = new StringBuilder();
        Matcher sm = Pattern.compile("<(?:\\w+:)?itemref\\b[^>]*>").matcher(opf);
        while (sm.find()) {
            Map<String, String> item = items.get(attrs(sm.group()).get("idref"));
            if (item == null) continue;
            byte[] page = files.get(path(base, item.get("href")));
            if (page != null) htmlToText(str(page), text);
        }

        byte[] cover = null;
        String coverId = null;
        Matcher cm = Pattern.compile("<meta\\b[^>]*name=\"cover\"[^>]*>").matcher(opf);
        if (cm.find()) coverId = attrs(cm.group()).get("content");
        for (Map<String, String> item : items.values()) {
            boolean image = item.getOrDefault("media-type", "").startsWith("image/");
            if (image && (item.getOrDefault("properties", "").contains("cover-image")
                    || item.getOrDefault("id", "").equals(coverId))) {
                cover = files.get(path(base, item.get("href")));
            }
        }
        save(c, title, author, text.toString(), cover);
        return title;
    }

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9]+)[^>]*>|<[!?][^>]*>|([^<]+)");
    private static final Pattern ALT = Pattern.compile("alt\\s*=\\s*\"([^\"]{1,2})\"");
    private static final Pattern BLOCK = Pattern.compile(
            "p|div|br|li|h[1-6]|blockquote|section|article|tr|hr|pre|dt|dd|figcaption|header|footer");

    /** XHTML chapter to paragraphs; h1/h2 become "# " headings (a new page). */
    static void htmlToText(String html, StringBuilder out) {
        html = html.replaceAll("(?is)<(head|script|style)\\b[^>]*>.*?</\\1>", "")
                // Project Gutenberg licence boilerplate
                .replaceAll("(?is)<(header|footer)\\b[^>]*class=\"[^\"]*pg-boilerplate[^>]*>.*?</\\1>", "")
                // picture captions: the pictures themselves aren't shown
                .replaceAll("(?is)<(span|div|p|figcaption)\\b[^>]*class=\"[^\"]*\\bcaption\\b[^>]*>.*?</\\1>", "")
                .replaceAll("(?is)<figcaption\\b[^>]*>.*?</figcaption>", "");
        Matcher m = TAG.matcher(html);
        StringBuilder para = new StringBuilder();
        boolean heading = false;
        while (m.find()) {
            if (m.group(3) != null) {
                para.append(m.group(3));
                continue;
            }
            if (m.group(2) == null) continue; // <?xml>, <!DOCTYPE>, comments
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            if (tag.equals("img")) {
                // Decorative drop caps are images whose alt text is the letter.
                Matcher alt = ALT.matcher(m.group());
                if (alt.find()) para.append(alt.group(1));
                continue;
            }
            if (!BLOCK.matcher(tag).matches()) continue;
            if (heading && tag.equals("br")) { para.append(' '); continue; } // one heading, not two
            flush(para, heading, out);
            boolean big = tag.equals("h1") || tag.equals("h2");
            if (big) heading = m.group(1).isEmpty();
        }
        flush(para, heading, out);
    }

    private static void flush(StringBuilder para, boolean heading, StringBuilder out) {
        String s = para.toString();
        para.setLength(0);
        if (s.indexOf('&') >= 0) s = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        s = s.replaceAll("[\\s\\u00A0]+", " ").trim();
        if (s.isEmpty()) return;
        out.append(heading ? "# " : "").append(s).append("\n\n");
    }

    private static void save(Context c, String title, String author, String text, byte[] cover)
            throws IOException {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "book";
        File d = dir(c);
        String name = slug;
        for (int i = 2; new File(d, name + ".txt").exists(); i++) name = slug + "-" + i;

        Files.write(new File(d, name + ".txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
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

    private static String firstTag(String xml, String tag) {
        Matcher m = Pattern.compile("(?s)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(xml);
        return m.find() ? Html.fromHtml(m.group(1), Html.FROM_HTML_MODE_LEGACY).toString().trim() : "";
    }

    private static Map<String, String> attrs(String tag) {
        Map<String, String> a = new HashMap<>();
        Matcher m = Pattern.compile("([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(tag);
        while (m.find()) a.put(m.group(1), m.group(3) != null ? m.group(3) : m.group(4));
        return a;
    }

    /** Resolves an OPF href (relative, maybe %-encoded, maybe with ../) to a zip path. */
    private static String path(String base, String href) {
        if (href == null) return "";
        try {
            href = URLDecoder.decode(href.replaceFirst("#.*$", ""), "UTF-8");
        } catch (Exception ignored) {
        }
        List<String> parts = new ArrayList<>();
        for (String s : (base + href).split("/")) {
            if (s.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else if (!s.isEmpty() && !s.equals(".")) parts.add(s);
        }
        return String.join("/", parts);
    }

    private static String str(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }
}
