package dev.botalive.core.crafting;

import org.bukkit.Material;

/**
 * Čistý plánovač vaření lektvarů – lektvarová obdoba {@link CraftPlanner}.
 *
 * <p>Jediný zdroj pravdy pro „co uvařit dál", sdílený server-side službou
 * i paketovou stanicí. Vstupem je {@link State} – souhrn lahví (podle
 * variant z metadat) a přísad – takže progrese jde testovat jednotkově.</p>
 *
 * <p>Řetěz: voda + bradavice → awkward základ; z něj podle přísad efekty
 * v pořadí užitečnosti – <b>odolnost ohni</b> (magma krém; klíč k lávě
 * a hlubinám Netheru), <b>léčení</b> (třpytivý meloun), <b>síla</b> (blaze
 * prach; wither a nájezdy) a <b>jed</b> (pavoučí oko), který se střelným
 * prachem převrací na <b>splash</b> – útočný vrh pro boj. Palivo stojanu
 * (blaze prach) a lahve řeší {@link CraftPlanner}.</p>
 */
public final class BrewPlanner {

    /** Základ vsázky – které lahve se do stojanu nakládají. */
    public enum Base {
        /** Lahve s vodou (vaří se awkward základ). */
        WATER,
        /** Awkward základy (vaří se efekt). */
        AWKWARD,
        /** Hotové pitelné jedy (konverze na splash střelným prachem). */
        POISON
    }

    /**
     * Jedna vsázka: přísada do stojanu + jaké lahve do něj patří.
     *
     * @param id         české označení vsázky (pro chat/log)
     * @param ingredient přísada (bradavice, magma krém, prach…)
     * @param base       lahve, které se nakládají
     */
    public record Batch(String id, Material ingredient, Base base) {
    }

    /**
     * Souhrn lektvarového inventáře. Varianty lahví čte volající – server
     * režim z {@code PotionMeta} (snapshot {@code itemVariants}), paketový
     * z komponent itemů.
     *
     * @param waterBottles    lahve s vodou
     * @param awkward         awkward základy
     * @param poisonDrinkable pitelné jedy (kandidáti splash konverze)
     * @param fireResistance  nese odolnost ohni (pitelnou či splash)
     * @param healing         nese léčení
     * @param strength        nese sílu
     * @param offensiveSplash nese útočný splash (zranění/jed)
     * @param wart            netherové bradavice
     * @param magmaCream      magma krémy
     * @param glisteringMelon třpytivé melouny
     * @param spiderEye       pavoučí oka
     * @param gunpowder       střelný prach
     * @param blazePowder     blaze prach
     */
    public record State(int waterBottles, int awkward, int poisonDrinkable,
                        boolean fireResistance, boolean healing, boolean strength,
                        boolean offensiveSplash,
                        int wart, int magmaCream, int glisteringMelon,
                        int spiderEye, int gunpowder, int blazePowder) {

        /** @return {@code true} pokud botovi nějaký cílový lektvar chybí */
        public boolean anythingMissing() {
            return !fireResistance || !healing || !strength || !offensiveSplash;
        }
    }

    private BrewPlanner() {
    }

    /**
     * Rozhodne další vsázku.
     *
     * @param s souhrn lektvarového inventáře
     * @return vsázka, nebo {@code null} když není co (nebo z čeho) vařit
     */
    public static Batch next(State s) {
        // Základ: bez awkward lahví se žádný efekt neuvaří.
        if (s.anythingMissing() && s.awkward() == 0
                && s.wart() > 0 && s.waterBottles() > 0) {
            return new Batch("awkward základ", Material.NETHER_WART, Base.WATER);
        }
        // Efekty v pořadí užitečnosti (awkward lahví je omezeně).
        if (!s.fireResistance() && s.awkward() > 0 && s.magmaCream() > 0) {
            return new Batch("odolnost ohni", Material.MAGMA_CREAM, Base.AWKWARD);
        }
        if (!s.healing() && s.awkward() > 0 && s.glisteringMelon() > 0) {
            return new Batch("léčení", Material.GLISTERING_MELON_SLICE, Base.AWKWARD);
        }
        // Blaze prach: aspoň jeden zůstává (palivo stojanu, oči Enderu).
        if (!s.strength() && s.awkward() > 0 && s.blazePowder() > 1) {
            return new Batch("síla", Material.BLAZE_POWDER, Base.AWKWARD);
        }
        if (!s.offensiveSplash() && s.poisonDrinkable() == 0
                && s.awkward() > 0 && s.spiderEye() > 0) {
            return new Batch("jed", Material.SPIDER_EYE, Base.AWKWARD);
        }
        // Splash konverze: pitelný jed + střelný prach = útočný vrh.
        if (!s.offensiveSplash() && s.poisonDrinkable() > 0 && s.gunpowder() > 0) {
            return new Batch("splash jed", Material.GUNPOWDER, Base.POISON);
        }
        return null;
    }
}
