package com.vaib215.cutereads;

import android.graphics.BitmapFactory;
import android.text.Html;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Converts an EPUB into the reader's book format: paragraphs separated by blank lines, "# " for
 * headings, plus invisible markers (Unicode private-use characters) for the rest:
 * italic and bold runs, images, contents anchors and page breaks.
 */
final class Epub {
    static final char ITALIC_ON = '\uE000', ITALIC_OFF = '\uE001';
    static final char BOLD_ON = '\uE002', BOLD_OFF = '\uE003';
    static final char IMAGE = '\uE004';  // IMAGE name IMAGE, a paragraph of its own
    static final char ANCHOR = '\uE005'; // ANCHOR n ANCHOR: contents entry n starts here
    static final char BREAK = '\uE006';  // a paragraph of its own: next paragraph starts a page
    static final String MARKERS = "[\\uE000-\\uE006]";

    static class DrmException extends IOException {
        DrmException() { super("DRM-protected"); }
    }

    String title = "", author = "";
    byte[] cover;
    final StringBuilder text = new StringBuilder();
    final List<String> tocTitles = new ArrayList<>();
    final List<Integer> tocLevels = new ArrayList<>();
    final Map<String, byte[]> images = new LinkedHashMap<>(); // file name -> bytes

    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, List<Integer>> targets = new HashMap<>(); // "path#id" -> toc entries

    static Epub read(byte[] data) throws IOException {
        Epub e = new Epub();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry entry; (entry = z.getNextEntry()) != null; ) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                copy(z, buf);
                e.files.put(entry.getName(), buf.toByteArray());
            }
        }
        e.checkDrm();
        e.convert();
        return e;
    }

    /** Font obfuscation also uses encryption.xml; anything else encrypted is DRM. */
    private void checkDrm() throws DrmException {
        if (files.containsKey("META-INF/rights.xml") || files.containsKey("META-INF/license.lcpl")
                || files.containsKey("META-INF/sinf.xml")) {
            throw new DrmException();
        }
        String enc = str(files.get("META-INF/encryption.xml"));
        Matcher m = Pattern.compile("(?s)<(?:\\w+:)?EncryptionMethod\\b[^>]*Algorithm\\s*=\\s*\"([^\"]+)\"").matcher(enc);
        while (m.find()) {
            String alg = m.group(1);
            if (!alg.equals("http://www.idpf.org/2008/embedding") && !alg.equals("http://ns.adobe.com/pdf/enc#RC")) {
                throw new DrmException();
            }
        }
    }

    private void convert() throws IOException {
        Matcher m = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(str(files.get("META-INF/container.xml")));
        if (!m.find()) throw new IOException("Not an EPUB");
        String opfPath = m.group(1);
        String opf = str(files.get(opfPath));

        title = firstTag(opf, "dc:title");
        author = firstTag(opf, "dc:creator");

        Map<String, Map<String, String>> items = new HashMap<>();
        Matcher im = Pattern.compile("<(?:\\w+:)?item\\b[^>]*>").matcher(opf);
        while (im.find()) {
            Map<String, String> a = attrs(im.group());
            items.put(a.getOrDefault("id", ""), a);
        }

        readToc(opf, opfPath, items);

        Matcher sm = Pattern.compile("<(?:\\w+:)?itemref\\b[^>]*>").matcher(opf);
        while (sm.find()) {
            Map<String, String> item = items.get(attrs(sm.group()).get("idref"));
            if (item == null) continue;
            String path = resolve(opfPath, item.get("href"));
            byte[] page = files.get(path);
            if (page == null) continue;
            if (text.length() > 0) text.append(BREAK).append("\n\n"); // each chapter file starts a page
            new Chapter(path).convert(str(page));
        }

        String coverId = null;
        Matcher cm = Pattern.compile("<meta\\b[^>]*name=\"cover\"[^>]*>").matcher(opf);
        if (cm.find()) coverId = attrs(cm.group()).get("content");
        for (Map<String, String> item : items.values()) {
            boolean image = item.getOrDefault("media-type", "").startsWith("image/");
            if (image && (item.getOrDefault("properties", "").contains("cover-image")
                    || item.getOrDefault("id", "").equals(coverId))) {
                cover = files.get(resolve(opfPath, item.get("href")));
            }
        }
    }

    // ---- Table of contents: EPUB 3 nav, else EPUB 2 NCX ----

    private void readToc(String opf, String opfPath, Map<String, Map<String, String>> items) {
        for (Map<String, String> item : items.values()) {
            if (!item.getOrDefault("properties", "").matches(".*\\bnav\\b.*")) continue;
            String navPath = resolve(opfPath, item.get("href"));
            String nav = str(files.get(navPath));
            Matcher toc = Pattern.compile("(?is)<nav\\b[^>]*toc[^>]*>(.*?)</nav>").matcher(nav);
            if (!toc.find()) toc = Pattern.compile("(?is)<nav\\b[^>]*>(.*?)</nav>").matcher(nav);
            if (!toc.find(0)) continue;
            Matcher t = Pattern.compile("(?is)<ol\\b[^>]*>|</ol>|<a\\b([^>]*)>(.*?)</a>").matcher(toc.group(1));
            int depth = 0;
            while (t.find()) {
                String tok = t.group().toLowerCase(Locale.ROOT);
                if (tok.startsWith("<ol")) depth++;
                else if (tok.startsWith("</ol")) depth--;
                else addEntry(clean(t.group(2)), Math.max(0, depth - 1), resolve(navPath, attrs(t.group(1)).get("href")));
            }
            if (!tocTitles.isEmpty()) return;
        }
        Matcher spine = Pattern.compile("<(?:\\w+:)?spine\\b[^>]*>").matcher(opf);
        if (!spine.find()) return;
        Map<String, String> ncxItem = items.get(attrs(spine.group()).get("toc"));
        if (ncxItem == null) return;
        String ncxPath = resolve(opfPath, ncxItem.get("href"));
        Matcher t = Pattern.compile("(?is)<navPoint\\b|</navPoint>|<text>(.*?)</text>|<content\\b([^>]*)>")
                .matcher(str(files.get(ncxPath)));
        int depth = 0;
        String label = "";
        while (t.find()) {
            String tok = t.group().toLowerCase(Locale.ROOT);
            if (tok.startsWith("<navpoint")) depth++;
            else if (tok.startsWith("</navpoint")) depth--;
            else if (tok.startsWith("<text")) label = clean(t.group(1));
            else addEntry(label, Math.max(0, depth - 1), resolve(ncxPath, attrs(t.group(2)).get("src")));
        }
    }

    private void addEntry(String label, int level, String target) {
        if (label.isEmpty() || target.isEmpty()) return;
        String key = target.contains("#") ? target : target + "#";
        targets.computeIfAbsent(key, k -> new ArrayList<>()).add(tocTitles.size());
        tocTitles.add(label);
        tocLevels.add(level);
    }

    // ---- One XHTML file ----

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][\\w:-]*)([^>]*)>|<[!?][^>]*>|([^<]+)");
    private static final Pattern BLOCK = Pattern.compile(
            "p|div|br|li|h[1-6]|blockquote|section|article|tr|hr|pre|dt|dd|header|footer|table|ul|ol|figure");

    private class Chapter {
        final String path;
        final StringBuilder para = new StringBuilder();
        boolean heading;        // inside h1/h2
        int italic, bold, cell;
        final List<String> heldImages = new ArrayList<>(); // pictures inside a heading go after it

        Chapter(String path) { this.path = path; }

        void convert(String html) {
            html = html.replaceAll(MARKERS, "")
                    .replaceAll("(?is)<(head|script|style)\\b[^>]*>.*?</\\1>", "")
                    // Project Gutenberg licence boilerplate
                    .replaceAll("(?is)<(header|footer|section|div)\\b[^>]*class=\"[^\"]*pg-boilerplate[^>]*>.*?</\\1>", "")
                    // picture captions
                    .replaceAll("(?is)<(span|div|p)\\b[^>]*class=\"[^\"]*\\bcaption\\b[^>]*>.*?</\\1>", "")
                    .replaceAll("(?is)<figcaption\\b[^>]*>.*?</figcaption>", "");
            anchor(path + "#");
            Matcher m = TAG.matcher(html);
            while (m.find()) {
                if (m.group(4) != null) { para.append(m.group(4)); continue; }
                if (m.group(2) == null) continue; // <?xml>, <!DOCTYPE>, comments
                String tag = m.group(2).toLowerCase(Locale.ROOT).replaceFirst("^\\w+:", "");
                boolean close = !m.group(1).isEmpty();
                Map<String, String> a = close ? new HashMap<>() : attrs(m.group(3));
                tag(tag, close, a); // first, so a heading's anchor lands inside the heading
                if (a.containsKey("id")) anchor(path + "#" + a.get("id"));
                if (tag.equals("a") && a.containsKey("name")) anchor(path + "#" + a.get("name"));
            }
            flush();
        }

        void tag(String tag, boolean close, Map<String, String> a) {
            switch (tag) {
                case "i": case "em": case "cite": case "dfn": case "var":
                    italic += close ? -1 : 1;
                    para.append(close ? ITALIC_OFF : ITALIC_ON);
                    return;
                case "b": case "strong":
                    bold += close ? -1 : 1;
                    para.append(close ? BOLD_OFF : BOLD_ON);
                    return;
                case "img": case "image":
                    if (!close) image(a);
                    return;
                case "td": case "th":
                    if (!close && cell++ > 0) para.append(" · "); // table cells on one line
                    return;
            }
            if (!BLOCK.matcher(tag).matches()) return;
            if (heading && tag.equals("br")) { para.append(' '); return; } // one heading, not two
            boolean minor = tag.matches("h[3-6]"); // smaller headings: a bold paragraph
            if (minor && close && bold > 0) { para.append(BOLD_OFF); bold--; }
            flush();
            if (tag.equals("tr")) cell = 0;
            if (tag.equals("li") && !close) para.append("• ");
            if (tag.equals("h1") || tag.equals("h2")) {
                heading = !close;
                if (close) {
                    for (String img : heldImages) text.append(IMAGE).append(img).append(IMAGE).append("\n\n");
                    heldImages.clear();
                }
            } else if (minor && !close) {
                para.append(BOLD_ON);
                bold++;
            }
        }

        void image(Map<String, String> a) {
            String alt = a.getOrDefault("alt", "");
            // Decorative drop caps are pictures of a letter, with the letter as alt text.
            if (alt.matches("\\p{L}{1,2}")) { para.append(alt); return; }
            String src = a.containsKey("src") ? a.get("src") : a.getOrDefault("xlink:href", a.get("href"));
            byte[] img = files.get(resolve(path, src));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (img != null) BitmapFactory.decodeByteArray(img, 0, img.length, o);
            boolean tiny = o.outWidth < 48 || o.outHeight < 48;
            if (img == null || tiny) return; // missing, or a spacer/ornament
            String ext = src.replaceFirst(".*\\.", "").toLowerCase(Locale.ROOT);
            String name = "img" + images.size() + "." + (ext.matches("png|gif|webp|jpe?g") ? ext : "img");
            images.put(name, img);
            if (heading) { heldImages.add(name); return; }
            flush();
            text.append(IMAGE).append(name).append(IMAGE).append("\n\n");
        }

        /**
         * Some books bake picture captions into their contents ("I hope Mr. Bingley will like it.
         * CHAPTER II."); when an entry points at a heading whose text it contains, use the heading.
         */
        void tidyTocTitles(String headingPara) {
            String visible = headingPara.replaceAll(ANCHOR + "\\d+" + ANCHOR, "").replaceAll(MARKERS, "").trim();
            Matcher m = Pattern.compile(ANCHOR + "(\\d+)" + ANCHOR).matcher(headingPara);
            while (m.find() && !visible.isEmpty()) {
                int n = Integer.parseInt(m.group(1));
                String title = tocTitles.get(n);
                if (title.length() > visible.length() && title.contains(visible)) tocTitles.set(n, visible);
            }
        }

        void anchor(String key) {
            List<Integer> entries = targets.remove(key);
            if (entries != null) for (int n : entries) para.append(ANCHOR).append(n).append(ANCHOR);
        }

        void flush() {
            if (italic > 0) para.append(ITALIC_OFF);
            if (bold > 0) para.append(BOLD_OFF);
            String s = para.toString();
            para.setLength(0);
            if (italic > 0) para.append(ITALIC_ON); // carry open styles into the next paragraph
            if (bold > 0) para.append(BOLD_ON);
            if (s.indexOf('&') >= 0) s = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
            s = s.replaceAll("[\\s\\u00A0]+", " ").trim();
            if (s.replaceAll(MARKERS, "").trim().isEmpty() && s.indexOf(ANCHOR) < 0) return;
            if (!heading && s.startsWith("# ")) s = "\u200B" + s; // literal "# " is not a heading
            if (heading) tidyTocTitles(s);
            text.append(heading ? "# " : "").append(s).append("\n\n");
        }
    }

    // ---- helpers ----

    static String clean(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    private static String firstTag(String xml, String tag) {
        Matcher m = Pattern.compile("(?s)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(xml);
        return m.find() ? clean(m.group(1)) : "";
    }

    static Map<String, String> attrs(String tag) {
        Map<String, String> a = new HashMap<>();
        Matcher m = Pattern.compile("([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(tag);
        while (m.find()) a.put(m.group(1), m.group(3) != null ? m.group(3) : m.group(4));
        return a;
    }

    /** A link inside `from` (a zip path) to a zip path, keeping any #fragment. */
    static String resolve(String from, String href) {
        if (href == null || href.isEmpty()) return "";
        String fragment = "";
        int hash = href.indexOf('#');
        if (hash >= 0) { fragment = href.substring(hash); href = href.substring(0, hash); }
        try {
            href = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        String base = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        if (href.isEmpty()) return from + fragment;
        List<String> parts = new ArrayList<>();
        for (String s : (href.startsWith("/") ? href : base + href).split("/")) {
            if (s.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else if (!s.isEmpty() && !s.equals(".")) parts.add(s);
        }
        return String.join("/", parts) + fragment;
    }

    private static String str(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }
}
