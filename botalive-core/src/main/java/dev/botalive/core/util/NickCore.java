package dev.botalive.core.util;

/**
 * Čitelné jádro herního nicku – nejdelší souvislý úsek písmen
 * („xX_Reaper_Xx" → „Reaper"), při shodě délek vyhrává první.
 *
 * <p>Jediná definice pro celý plugin: skloňování v chatu
 * ({@code CzechNames}) pracuje s ASCII písmeny (české skloňovací tabulky),
 * jména vesnic ({@code SettlementNames}) berou i diakritiku. Obě varianty
 * sdílejí tenhle algoritmus, aby „jádro nicku" znamenalo všude totéž.</p>
 */
public final class NickCore {

    private NickCore() {
    }

    /**
     * @param nick    nick hráče/bota
     * @param unicode {@code true} = písmenem je každé {@link Character#isLetter};
     *                {@code false} = jen ASCII A–Z/a–z
     * @return nejdelší úsek písmen (může být prázdný)
     */
    public static String core(String nick, boolean unicode) {
        String best = "";
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= nick.length(); i++) {
            char c = i < nick.length() ? nick.charAt(i) : ' ';
            boolean letter = unicode
                    ? Character.isLetter(c)
                    : (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (letter) {
                run.append(c);
            } else {
                if (run.length() > best.length()) {
                    best = run.toString();
                }
                run.setLength(0);
            }
        }
        return best;
    }
}
