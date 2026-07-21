package dev.botalive.core.build.plan;

import dev.botalive.api.role.BotRole;

import org.bukkit.Material;

import java.util.Map;

/**
 * Katalog účelných řemeslných dílen – jedno místo pravdy o tom, jaká
 * pracovní stanice a jaký blueprint patří které profesi, plus český název
 * pro chat a intent.
 *
 * <p>Do sídla se dílna staví jen tehdy, když dané řemeslo někdo z členů
 * dělá (poptávka řeší {@code SettlementService}); tady žije jen <b>data</b>:
 * hlavní stanice (nutná k zahájení, jinak by vznikla prázdná kůlna), vedlejší
 * stanice (bonus – osadí se, když ji stavitel má) a název. Stanice jsou přesně
 * ty, které příslušný cíl reálně používá ({@code SmeltGoal}, {@code SmithGoal},
 * {@code CraftGoal}, {@code CompostGoal}, {@code EnchantGoal}, {@code BrewGoal}),
 * takže řemeslník dílnu skutečně obývá, ne jen míjí.</p>
 */
public final class Workshops {

    /**
     * Předpis dílny.
     *
     * @param station   hlavní stanice (nutná k zahájení stavby)
     * @param secondary vedlejší stanice (bonus), nebo {@code null}
     * @param csName     český název v prvním pádě pro chat/intent (např. „kovárna")
     */
    public record Spec(Material station, Material secondary, String csName) {
    }

    private static final Map<BotRole, Spec> SPECS = Map.of(
            BotRole.BLACKSMITH, new Spec(Material.FURNACE, Material.SMITHING_TABLE, "kovárna"),
            BotRole.COOK, new Spec(Material.SMOKER, null, "kuchyně"),
            BotRole.BUILDER, new Spec(Material.CRAFTING_TABLE, Material.STONECUTTER, "dílna"),
            BotRole.FARMER, new Spec(Material.COMPOSTER, null, "kompostárna"),
            BotRole.ENCHANTER, new Spec(Material.ENCHANTING_TABLE, null, "enchantovna"),
            BotRole.ALCHEMIST, new Spec(Material.BREWING_STAND, null, "alchymistická dílna"));

    private Workshops() {
    }

    /**
     * @param role profese
     * @return předpis dílny pro profesi, nebo {@code null} (profese bez dílny)
     */
    public static Spec spec(BotRole role) {
        return SPECS.get(role);
    }

    /**
     * @param role profese (musí mít dílnu, viz {@link #spec})
     * @return blueprint dílny dané profese
     */
    public static Blueprint blueprint(BotRole role) {
        Spec spec = SPECS.get(role);
        if (spec == null) {
            throw new IllegalArgumentException("profese bez dílny: " + role);
        }
        return Blueprints.workshop(spec.station(), spec.secondary());
    }
}
