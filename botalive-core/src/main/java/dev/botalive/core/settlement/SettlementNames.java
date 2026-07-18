package dev.botalive.core.settlement;

import java.util.Locale;
import java.util.Random;

/**
 * Generátor jmen vesnic – deterministický ze seedu.
 *
 * <p>Zhruba polovina jmen se odvozuje od zakladatele („Pepov", „Martinovice"),
 * zbytek z banky českých místních jmen („Nová Lhota", „Dolní Bystřice").
 * U herních nicků se pracuje s čitelným jádrem (nejdelší souvislý úsek
 * písmen), aby „xX_Reaper_Xx" založil „Reapov", ne „XxreaperxXov".</p>
 */
public final class SettlementNames {

    private static final String[] PREFIXES = {
            "Nová", "Stará", "Horní", "Dolní", "Velká", "Malá"};
    private static final String[] ROOTS = {
            "Lhota", "Ves", "Bystřice", "Doubrava", "Kamenice",
            "Olšina", "Vrbina", "Studánka"};
    private static final String[] SUFFIXES = {"ov", "ín", "ovice"};

    private SettlementNames() {
    }

    /**
     * Vygeneruje jméno vesnice.
     *
     * @param founderName jméno zakladatele (může být {@code null})
     * @param seed        seed (deterministické opakování)
     * @param attempt     pořadí pokusu – při kolizi jmen zkusit další
     * @return jméno vesnice
     */
    public static String generate(String founderName, long seed, int attempt) {
        Random rng = new Random(seed * 31L + attempt);
        if (founderName != null && rng.nextDouble() < 0.55) {
            String core = readableCore(founderName);
            if (core.length() >= 3) {
                String stem = trimTrailingVowels(core);
                if (stem.length() >= 2) {
                    return capitalize(stem) + SUFFIXES[rng.nextInt(SUFFIXES.length)];
                }
            }
        }
        return PREFIXES[rng.nextInt(PREFIXES.length)] + " " + ROOTS[rng.nextInt(ROOTS.length)];
    }

    /** Nejdelší souvislý úsek písmen ve jménu (sdílené jádro nicku, s diakritikou). */
    static String readableCore(String name) {
        return dev.botalive.core.util.NickCore.core(name, true);
    }

    /** Odstřihne koncové samohlásky, aby přípona zněla česky („Pepa" → „Pep"). */
    private static String trimTrailingVowels(String core) {
        int end = core.length();
        while (end > 2 && isVowel(core.charAt(end - 1))) {
            end--;
        }
        return core.substring(0, end);
    }

    private static boolean isVowel(char c) {
        return "aeiouyáéěíóúůý".indexOf(Character.toLowerCase(c)) >= 0;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
