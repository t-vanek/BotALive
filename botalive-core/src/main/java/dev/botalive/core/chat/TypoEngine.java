package dev.botalive.core.chat;

import dev.botalive.core.util.BotRandom;

import java.text.Normalizer;
import java.util.Map;

/**
 * Generátor lidských překlepů.
 *
 * <p>Simuluje reálné typy chyb: záměnu sousedních kláves (QWERTZ/QWERTY),
 * prohození dvou písmen, vypadlé písmeno a zdvojené písmeno. Umí také
 * odstranit diakritiku – typický projev rychlého psaní na českém serveru.</p>
 */
public final class TypoEngine {

    /** Sousední klávesy pro záměnu (zjednodušená QWERTZ mapa). */
    private static final Map<Character, String> NEIGHBORS = Map.ofEntries(
            Map.entry('a', "qsy"), Map.entry('b', "vgn"), Map.entry('c', "xdv"),
            Map.entry('d', "sfce"), Map.entry('e', "wrd"), Map.entry('f', "dgv"),
            Map.entry('g', "fhb"), Map.entry('h', "gjn"), Map.entry('i', "uok"),
            Map.entry('j', "hkm"), Map.entry('k', "jli"), Map.entry('l', "ko"),
            Map.entry('m', "nj"), Map.entry('n', "bmh"), Map.entry('o', "ipl"),
            Map.entry('p', "o"), Map.entry('q', "wa"), Map.entry('r', "etf"),
            Map.entry('s', "adw"), Map.entry('t', "rzg"), Map.entry('u', "zij"),
            Map.entry('v', "cfb"), Map.entry('w', "qes"), Map.entry('x', "ycs"),
            Map.entry('y', "xa"), Map.entry('z', "tu")
    );

    private final BotRandom rng;

    /**
     * @param rng per-bot náhoda
     */
    public TypoEngine(BotRandom rng) {
        this.rng = rng;
    }

    /**
     * Aplikuje překlepy na zprávu podle stylu.
     *
     * @param message zpráva
     * @param style   styl psaní bota
     * @return dvojice (text s překlepy, první zkomolené slovo pro případnou opravu)
     */
    public Result apply(String message, ChatStyle style) {
        String text = message;
        if (style.dropDiacritics()) {
            text = stripDiacritics(text);
        }
        if (style.lowercase()) {
            text = text.toLowerCase();
        }
        if (!style.punctuation()) {
            text = text.replace(",", "").replaceAll("\\.$", "");
        }

        String[] words = text.split(" ");
        String correction = null;
        for (int i = 0; i < words.length; i++) {
            if (words[i].length() >= 3 && rng.chance(style.typoRate())) {
                String original = words[i];
                words[i] = mangle(words[i]);
                if (correction == null && !words[i].equals(original)) {
                    correction = original;
                }
            }
        }
        return new Result(String.join(" ", words), correction);
    }

    /** Jedna náhodná chyba ve slově. */
    private String mangle(String word) {
        int type = rng.rangeInt(0, 3);
        int i = rng.rangeInt(0, word.length() - 2);
        char c = Character.toLowerCase(word.charAt(i));
        return switch (type) {
            // záměna za sousední klávesu
            case 0 -> {
                String neighbors = NEIGHBORS.get(c);
                if (neighbors == null) {
                    yield word;
                }
                char replacement = neighbors.charAt(rng.rangeInt(0, neighbors.length() - 1));
                yield word.substring(0, i) + replacement + word.substring(i + 1);
            }
            // prohození dvou sousedních písmen
            case 1 -> word.substring(0, i) + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2);
            // vypadlé písmeno
            case 2 -> word.substring(0, i) + word.substring(i + 1);
            // zdvojené písmeno
            default -> word.substring(0, i + 1) + word.charAt(i) + word.substring(i + 1);
        };
    }

    private static String stripDiacritics(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /**
     * Výsledek aplikace překlepů.
     *
     * @param text       finální text
     * @param correction slovo k opravě follow-up zprávou („*slovo"), nebo {@code null}
     */
    public record Result(String text, String correction) {
    }
}
