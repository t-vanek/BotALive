package dev.botalive.core.chat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Skloňování jmen a herních nicků v češtině.
 *
 * <p>Cíl není jazykovědná dokonalost, ale chat, který zní jako od českých
 * hráčů: „ahoj Karle", „jdu za Luckou", „díky Mateji". Pracuje se bez
 * diakritiky – nicky ji nemají a skloněný tvar má vypadat stejně
 * ({@code Tomas → Tomasi}, {@code Lucka → Lucce}).</p>
 *
 * <p>Postup:</p>
 * <ol>
 *   <li><b>Jádro nicku:</b> nejdelší souvislý úsek písmen – dekorace se
 *       zahazují, jak to hráči dělají ({@code Xx_Ninja_xX → Ninja},
 *       {@code Lucka360 → Lucka}). Leet nicky bez čitelného jádra
 *       ({@code N00bV1per}) se neskloňují.</li>
 *   <li><b>Slovník výjimek:</b> mužská jména na -a (Honza, Ondra),
 *       nesklonná ženská (Nikol, Elis), elize (Karel → Karl-).</li>
 *   <li><b>Koncovková pravidla:</b> velární/měkké/tvrdé mužské vzory,
 *       -ek s elizí (Radek → Radku), ženská na -a a -ie, mužská na -o.</li>
 * </ol>
 *
 * <p>Když si engine není jistý (neznámá koncovka, nesklonné jméno, cizí
 * tvar), vrací jméno beze změny – špatný pád je horší než žádný.</p>
 */
public final class CzechNames {

    /** Minimální délka jádra, aby mělo skloňování smysl. */
    private static final int MIN_CORE = 3;

    /** Mužská domácká jména na -a (skloňují se „Honzovi", ne „Honze"). */
    private static final Set<String> MASCULINE_A = Set.of(
            "honza", "ondra", "standa", "vojta", "jirka", "mira", "kuba", "pepa",
            "jarda", "franta", "tonda", "lada", "slava", "vasa", "misa");

    /** Nesklonná ženská jména (končí souhláskou, pád se nemění). */
    private static final Set<String> FEMININE_INDECLINABLE = Set.of(
            "nikol", "elis", "ines", "dagmar", "ester", "karin", "ingrid", "ruth");

    /** Elize -e- v poslední slabice (Karel → Karl-ovi, ne Karel-ovi). */
    private static final Map<String, String> ELIDED_STEMS = Map.of(
            "karel", "karl",
            "pavel", "pavl",
            "havel", "havl");

    private CzechNames() {
    }

    /**
     * Vyskloňuje jméno/nick do zadaného pádu.
     *
     * <p>U zdobených nicků se skloňuje (a vrací) jen jádro – oslovení
     * „čau Ninjo" místo „čau Xx_Ninjo_xX", přesně jak to hráči píší.</p>
     *
     * @param nick jméno nebo herní nick
     * @param grammaticalCase pád 1–7 (české školní číslování; 1 a neznámé
     *                        hodnoty vrací jméno beze změny)
     * @return skloněný tvar, nebo vstup beze změny když si engine není jistý
     */
    public static String decline(String nick, int grammaticalCase) {
        if (nick == null || nick.isEmpty() || grammaticalCase <= 1 || grammaticalCase > 7) {
            return nick == null ? "" : nick;
        }
        String core = core(nick);
        // Leet nicky (N00bV1per) mají místo jádra jen fragmenty – skloňování
        // dává smysl, jen když jádro tvoří většinu písmen nicku.
        int letters = 0;
        for (int i = 0; i < nick.length(); i++) {
            if (Character.isLetter(nick.charAt(i))) {
                letters++;
            }
        }
        if (core.length() < MIN_CORE || core.length() * 2 <= letters) {
            return nick; // krátké/leet – radši neskloňovat
        }
        String declined = declineCore(core, grammaticalCase);
        // Jádro se nezměnilo → vrátit celý původní nick (žádné trhání dekorací
        // bez důvodu); změnilo se → skloněné jádro nahrazuje celý nick.
        return declined.equals(core) ? nick : declined;
    }

    /** Nejdelší souvislý úsek písmen (sdílené jádro nicku; ASCII – skloňovací
     *  tabulky pracují bez diakritiky). */
    private static String core(String nick) {
        return dev.botalive.core.util.NickCore.core(nick, false);
    }

    /** Vyskloňuje čisté jméno (jen písmena). */
    private static String declineCore(String name, int gcase) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (FEMININE_INDECLINABLE.contains(lower)) {
            return name;
        }
        if (MASCULINE_A.contains(lower)) {
            return masculineA(name, gcase);
        }
        String elided = ELIDED_STEMS.get(lower);
        if (elided != null) {
            return masculineConsonant(matchCase(name, elided), gcase);
        }

        char last = lastChar(lower);
        if (last == 'a') {
            return feminineA(name, gcase);
        }
        if (lower.endsWith("ie")) {
            return feminineIe(name, gcase);
        }
        if (last == 'o') {
            return masculineO(name, gcase);
        }
        if (isVowel(last)) {
            return name; // -e, -i, -u, -y: nesklonné bez slovníku (Leo řeší -o)
        }
        // Souhláska → mužský rod. Jména na -ek mají elizi (Radek → Radk-).
        String stem = name;
        if (lower.endsWith("ek") && name.length() >= 4) {
            stem = name.substring(0, name.length() - 2) + name.charAt(name.length() - 1);
        }
        return masculineConsonant(stem, gcase);
    }

    /**
     * Mužská jména končící souhláskou (vzory pán/muž podle zakončení).
     * Koncovky: velára (k, g, h, ch) má 5. pád -u, měkké souhlásky
     * (c/j/x/s/z) -i, ostatní (včetně cizích w) -e.
     */
    private static String masculineConsonant(String stem, int gcase) {
        String lower = stem.toLowerCase(Locale.ROOT);
        char last = lastChar(lower);
        boolean velar = last == 'k' || last == 'g' || last == 'h' || lower.endsWith("ch");
        boolean soft = !velar && (last == 's' || last == 'z' || last == 'c'
                || last == 'j' || last == 'x');
        return switch (gcase) {
            case 2, 4 -> stem + (soft ? "e" : "a");
            case 3, 6 -> stem + "ovi";
            case 5 -> stem + (velar ? "u" : soft ? "i" : "e");
            case 7 -> stem + "em";
            default -> stem;
        };
    }

    /** Mužská domácká jména na -a (Honza): 2. Honzy, 3. Honzovi, 5. Honzo. */
    private static String masculineA(String name, int gcase) {
        String stem = name.substring(0, name.length() - 1);
        return switch (gcase) {
            case 2 -> stem + "y";
            case 3, 6 -> stem + "ovi";
            case 4 -> stem + "u";
            case 5 -> stem + "o";
            case 7 -> stem + "ou";
            default -> name;
        };
    }

    /**
     * Ženská jména na -a (vzor žena): 2. Lucky, 4. Lucku, 5. Lucko,
     * 7. Luckou. 3./6. pád jen s jistou alternací -ka → -ce (Lucce);
     * jiná zakončení se v dativu nechávají beze změny (Báře bez diakritiky
     * nezapíšeme slušně).
     */
    private static String feminineA(String name, int gcase) {
        String stem = name.substring(0, name.length() - 1);
        return switch (gcase) {
            case 2 -> stem + "y";
            case 3, 6 -> name.toLowerCase(Locale.ROOT).endsWith("ka")
                    ? name.substring(0, name.length() - 2) + "ce"
                    : name;
            case 4 -> stem + "u";
            case 5 -> stem + "o";
            case 7 -> stem + "ou";
            default -> name;
        };
    }

    /** Ženská jména na -ie (Sofie): 3./4./6./7. Sofii, 2. a 5. beze změny. */
    private static String feminineIe(String name, int gcase) {
        String stem = name.substring(0, name.length() - 1);
        return switch (gcase) {
            case 3, 4, 6, 7 -> stem + "i";
            default -> name;
        };
    }

    /** Mužská jména na -o (Leo, Ivo): 2./4. Lea, 3./6. Leovi, 7. Leem. */
    private static String masculineO(String name, int gcase) {
        String stem = name.substring(0, name.length() - 1);
        return switch (gcase) {
            case 2, 4 -> stem + "a";
            case 3, 6 -> stem + "ovi";
            case 7 -> stem + "em";
            default -> name; // 5. pád = 1. pád (Leo!)
        };
    }

    private static char lastChar(String s) {
        return s.charAt(s.length() - 1);
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }

    /** Přenese velikost prvního písmena vzoru na náhradní kmen. */
    private static String matchCase(String original, String stem) {
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
        }
        return stem;
    }
}
