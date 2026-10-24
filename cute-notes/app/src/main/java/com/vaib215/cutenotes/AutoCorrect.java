package com.vaib215.cutenotes;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fixes misread handwriting with an English word list (SCOWL, ranked from 10 = most common
 * to 70 = rare). Of the recogniser's guesses, a common word wins; failing that, a known
 * one; failing that, the most common word a letter or two away. Words you put back after
 * a correction are learnt and left alone from then on.
 */
final class AutoCorrect {
    private static final int COMMON = 60;
    private static volatile Map<String, Byte> words; // lower case → level
    private static boolean loading;

    /** Loads the word list in the background; safe to call often. */
    static synchronized void load(Context c) {
        if (words != null || loading) return;
        loading = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Map<String, Byte> m = new HashMap<>(250_000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    app.getAssets().open("words.txt"), StandardCharsets.UTF_8))) {
                byte level = 0;
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("#")) level = Byte.parseByte(line.substring(1));
                    else if (!line.isEmpty()) m.put(line, level);
                }
                words = m;
            } catch (Exception ignored) {
            }
        }, "words").start();
    }

    private final SharedPreferences prefs;
    private final Set<String> learnt;

    AutoCorrect(Context c) {
        load(c);
        prefs = Ui.prefs(c);
        learnt = new HashSet<>(prefs.getStringSet("learnt", new HashSet<>()));
    }

    /** Remembers a word, so it's never corrected again. */
    void learn(String word) {
        learnt.add(core(word).toLowerCase(Locale.ROOT));
        prefs.edit().putStringSet("learnt", new HashSet<>(learnt)).apply();
    }

    /** `guesses`, best first, reordered so the choice is first; a fix may be added in front. */
    List<String> correct(List<String> guesses) {
        if (words == null || guesses.isEmpty()) return guesses;
        String best = guesses.get(0);
        if (level(best) <= COMMON) return guesses;
        boolean capital = Character.isUpperCase(best.codePointAt(0));
        for (String g : guesses) {
            // A capitalised word is likely a name: don't trade it for a lower-case one.
            if (capital && !Character.isUpperCase(g.codePointAt(0))) continue;
            if (level(g) <= COMMON) return moveFirst(guesses, g);
        }
        if (level(best) < Integer.MAX_VALUE) return guesses;
        String fix = nearest(best);
        if (fix == null) return guesses;
        return moveFirst(guesses, fix);
    }

    /** The rarest level among the guess's words; MAX_VALUE if one is unknown. */
    private int level(String guess) {
        int worst = 0;
        for (String token : guess.split("\\s+")) {
            String w = core(token).toLowerCase(Locale.ROOT);
            if (w.isEmpty() || !w.matches("[a-z']+") || learnt.contains(w)) continue;
            Byte l = words.get(w);
            if (l == null) return Integer.MAX_VALUE;
            worst = Math.max(worst, l);
        }
        return worst;
    }

    /**
     * For a single unknown word written in lower case: the most common word one edit away,
     * or two for longer words. Null if there's none, or it's not a plain word.
     */
    private String nearest(String guess) {
        if (guess.contains(" ")) return null;
        String w = core(guess);
        if (w.length() < 3 || !w.matches("[a-z']+")) return null; // capitals: likely a name
        String best = null;
        int bestLevel = COMMON + 1;
        Set<String> once = edits(w);
        for (String e : once) {
            Byte l = words.get(e);
            // On a tie, swapped letters ("teh") beat a changed one ("tea").
            if (l != null && (l < bestLevel || l == bestLevel && swapped(w, e))) { best = e; bestLevel = l; }
        }
        if (best == null && w.length() >= 5) {
            for (String e : once) {
                for (String e2 : edits(e)) {
                    Byte l = words.get(e2);
                    if (l != null && l < bestLevel) { best = e2; bestLevel = l; }
                }
            }
        }
        if (best == null) return null;
        int at = guess.indexOf(w);
        return guess.substring(0, at) + best + guess.substring(at + w.length());
    }

    private static boolean swapped(String a, String b) {
        if (a.length() != b.length()) return false;
        int i = 0;
        while (i < a.length() && a.charAt(i) == b.charAt(i)) i++;
        return i < a.length() - 1 && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                && a.substring(i + 2).equals(b.substring(i + 2));
    }

    private static Set<String> edits(String w) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i <= w.length(); i++) {
            String a = w.substring(0, i), b = w.substring(i);
            if (!b.isEmpty()) out.add(a + b.substring(1));
            if (b.length() > 1) out.add(a + b.charAt(1) + b.charAt(0) + b.substring(2));
            for (char c = 'a'; c <= 'z'; c++) {
                if (!b.isEmpty()) out.add(a + c + b.substring(1));
                out.add(a + c + b);
            }
        }
        out.remove(w);
        return out;
    }

    /** The word without punctuation around it. */
    private static String core(String token) {
        int s = 0, e = token.length();
        while (s < e && !Character.isLetterOrDigit(token.charAt(s))) s++;
        while (e > s && !Character.isLetterOrDigit(token.charAt(e - 1))) e--;
        return token.substring(s, e);
    }

    private static List<String> moveFirst(List<String> list, String g) {
        List<String> out = new ArrayList<>(list);
        out.remove(g);
        out.add(0, g);
        return out;
    }
}
