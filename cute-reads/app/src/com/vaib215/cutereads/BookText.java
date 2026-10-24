package com.vaib215.cutereads;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.StyleSpan;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A book's text as it's shown, shared by the reader and the page widget so that both count
 * characters the same way (saved positions are offsets into this text).
 */
final class BookText {
    private static String cachedKey;
    private static CharSequence cached;

    /** The book formatted without pictures, kept for the next call (the widget turns pages often). */
    static synchronized CharSequence plain(Context c, File book, float sizeSp) {
        String key = book.getPath() + "|" + book.lastModified() + "|" + sizeSp;
        if (!key.equals(cachedKey)) {
            try {
                String raw = new String(Files.readAllBytes(book.toPath()), StandardCharsets.UTF_8);
                cached = format(c, book, raw, sizeSp, new ArrayList<>(), new HashMap<>(), false);
                cachedKey = key;
            } catch (Exception e) {
                return "";
            }
        }
        return cached;
    }

    /**
     * Book text to styled text. Paragraphs are separated by blank lines; "# " paragraphs are
     * headings that start a page; Epub's markers become italics, bold, pictures, page breaks
     * and contents anchors (their offsets go into `anchors`). Without `pictures` (for widgets),
     * a picture is just its placeholder character.
     */
    static CharSequence format(Context c, File book, String raw, float sizeSp, List<Integer> breaks,
                               Map<Integer, Integer> anchors, boolean pictures) {
        anchors.clear();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int gap = Math.round(sizeSp * 0.6f * c.getResources().getDisplayMetrics().scaledDensity);
        File images = new File(book.getParentFile(), Library.name(book) + ".files");
        boolean pageBreak = false, lastHeading = false;
        List<int[]> pendingAnchors = new ArrayList<>(); // {entry, offset in paragraph}
        for (String para : raw.replace("\r", "").split("\n\\s*\n")) {
            String p = para.trim().replaceAll("\\s*\n\\s*", " ");
            if (p.isEmpty()) continue;
            if (p.equals(String.valueOf(Epub.BREAK))) { pageBreak = true; continue; }
            boolean heading = p.startsWith("# ");
            if (heading) p = p.substring(2);

            // Strip markers into styles; remember anchors.
            StringBuilder visible = new StringBuilder();
            List<int[]> italics = new ArrayList<>(), bolds = new ArrayList<>();
            int italicFrom = -1, boldFrom = -1;
            String image = null;
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch == Epub.ITALIC_ON) italicFrom = visible.length();
                else if (ch == Epub.ITALIC_OFF && italicFrom >= 0) { italics.add(new int[]{italicFrom, visible.length()}); italicFrom = -1; }
                else if (ch == Epub.BOLD_ON) boldFrom = visible.length();
                else if (ch == Epub.BOLD_OFF && boldFrom >= 0) { bolds.add(new int[]{boldFrom, visible.length()}); boldFrom = -1; }
                else if (ch == Epub.IMAGE || ch == Epub.ANCHOR) {
                    int close = p.indexOf(ch, i + 1);
                    if (close < 0) break;
                    String value = p.substring(i + 1, close);
                    if (ch == Epub.IMAGE) image = value;
                    else if (value.matches("\\d+")) pendingAnchors.add(new int[]{Integer.parseInt(value), visible.length()});
                    i = close;
                } else visible.append(ch);
            }
            if (visible.toString().trim().isEmpty() && image == null) {
                for (int[] a : pendingAnchors) a[1] = 0; // anchors carry forward to the next paragraph
                continue;
            }

            if (sb.length() > 0) {
                int s = sb.length();
                sb.append("\n\n");
                // Shrink the blank line so paragraphs get a small gap, not a full line.
                sb.setSpan(new AbsoluteSizeSpan(gap), s + 1, s + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int start = sb.length();
            for (int[] a : pendingAnchors) anchors.put(a[0], start + Math.min(a[1], visible.length()));
            pendingAnchors.clear();
            // A heading starts a page, unless it follows another heading (title, then subtitle).
            if (pageBreak || (heading && !lastHeading)) breaks.add(start);
            pageBreak = false;
            lastHeading = heading;

            if (image != null) {
                sb.append('\uFFFC'); // one character either way, so offsets match the reader's
                if (!pictures) continue;
                sb.setSpan(new PageImage(new File(images, image)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            sb.append(visible);
            int end = sb.length();
            for (int[] r : italics) sb.setSpan(new StyleSpan(Typeface.ITALIC), start + r[0], start + r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (int[] r : bolds) sb.setSpan(new StyleSpan(Typeface.BOLD), start + r[0], start + r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (heading) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }
}
