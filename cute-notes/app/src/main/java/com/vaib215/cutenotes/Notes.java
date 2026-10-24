package com.vaib215.cutenotes;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Each note is a plain text file in the app's files dir, named by when it was started. */
final class Notes {
    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "notes");
        d.mkdirs();
        return d;
    }

    /** Most recently changed first. */
    static List<File> all(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".txt"));
        List<File> list = new ArrayList<>(files == null ? List.of() : Arrays.asList(files));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    static File create(Context c) {
        return new File(dir(c), System.currentTimeMillis() + ".txt");
    }

    static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** An empty note isn't kept. */
    static void write(File f, String text) {
        if (text.trim().isEmpty()) {
            f.delete();
            return;
        }
        if (text.equals(read(f))) return; // unchanged: keep its place in the list
        try {
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** The first line, and what follows it, for the list. */
    static String[] titleAndPreview(String text) {
        String t = text.trim();
        int nl = t.indexOf('\n');
        if (nl < 0) return new String[]{t, ""};
        return new String[]{t.substring(0, nl).trim(), t.substring(nl + 1).trim().replaceAll("\\s+", " ")};
    }
}
