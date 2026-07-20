package dev.botalive.api.role;

import java.util.Locale;
import java.util.Optional;

/**
 * Profese (role) bota.
 *
 * <p>Role je <b>zaměření, ne klec</b>: násobí užitečnost souvisejících AI cílů,
 * takže se bot dané činnosti věnuje výrazně častěji, ale pořád jí, spí, bojuje
 * a socializuje se jako každý jiný. Všechny role stojí na vanilla mechanikách
 * (těžba, kácení, lov, tavení v peci, enchantování, obchod s vesničany,
 * rybaření, farmaření, stavění).</p>
 */
public enum BotRole {

    /** Univerzál bez zaměření – čistě osobnostní chování. */
    NONE("univerzál"),

    /** Stavitel – přístřešky, opevnění, práce s bloky. */
    BUILDER("stavitel"),

    /** Kopáč/horník – rudy, kámen, doly. */
    MINER("kopáč"),

    /** Dřevorubec – kácení stromů, práce se dřevem. */
    LUMBERJACK("dřevorubec"),

    /** Lovec – lov zvěře pro jídlo a suroviny. */
    HUNTER("lovec"),

    /** Kovář – tavení rud a vaření jídla v peci. */
    BLACKSMITH("kovář"),

    /** Enchanter – očarovávání výbavy u enchantovacího stolu. */
    ENCHANTER("enchanter"),

    /** Obchodník – směna komodit s vesničany. */
    TRADER("obchodník"),

    /** Rybář – rybaření prutem u vody. */
    FISHERMAN("rybář"),

    /** Farmář – pěstování a sklizeň plodin. */
    FARMER("farmář"),

    /** Alchymista – vaření lektvarů u stojanu, výpravy pro bradavici. */
    ALCHEMIST("alchymista"),

    /** Strážce – hlídání návsi, ochrana ostatních, boj v čele. */
    GUARDIAN("strážce"),

    /** Průzkumník – dálkové výpravy, lodě, vozíky, tábory v terénu. */
    SCOUT("průzkumník"),

    /** Krotitel – ochočování zvířat a péče o ně. */
    BEASTMASTER("krotitel"),

    /** Zloděj – krádeže z truhel a loupeže; strůjce konfliktů. */
    THIEF("zloděj"),

    /** Vyjednavač – družení, sdílení, urovnávání sporů mezi osadami. */
    DIPLOMAT("vyjednavač"),

    /** Dobrodruh – Nether, End, souboje s bossy. */
    ADVENTURER("dobrodruh"),

    /** Poslíček – rozvoz zakázek, doplňování truhel, sběr. */
    COURIER("poslíček"),

    /** Kuchař – vaření jídla v peci a jeho rozdělování. */
    COOK("kuchař");

    private final String displayName;

    BotRole(String displayName) {
        this.displayName = displayName;
    }

    /**
     * @return český název role pro výpisy
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Tolerantní parsování z příkazu/DB (anglický název enumu i český název).
     *
     * @param input vstupní text
     * @return role, pokud odpovídá
     */
    public static Optional<BotRole> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (BotRole role : values()) {
            if (role.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || role.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
