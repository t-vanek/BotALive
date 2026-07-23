package dev.botalive.core.build.plan;

import org.bukkit.Material;

import java.util.List;

/**
 * Suroviny, které má smysl cíleně natěžit pro daný stavební stupeň domu –
 * „build wishlist" vedle {@code BotNeeds.miningWishlist}. Čistá funkce; napojuje
 * ji {@code MineGoal} do výběru cíle (nižší priorita než rudy, ať těžba
 * nástrojů má přednost).
 *
 * <p>Dnes pokrývá jen kompletní řetězec <b>písek → sklo</b> (pec ho roztaví,
 * {@code SmeltGoal}), takže solidnímu+ domu se zasklí okna. Cihly a tesaný
 * kámen (REFINED) čekají na craft recepty ({@code CraftPlanner}) – viz
 * {@code docs/BUILD_AS_PROCESS.md}, zbytek fáze 4.</p>
 */
public final class BuildMaterials {

    private BuildMaterials() {
    }

    /**
     * @param target   cílový stavební stupeň domu ({@code null} = žádný)
     * @param hasGlass má bot sklo nebo skleněné tabule?
     * @param hasSand  má bot písek (už se taví na sklo)?
     * @return suroviny k natěžení pro tier, které bot ještě nemá; prázdné pro
     *         srub (okna jsou otvory) i když bot potřebné už má/shání
     */
    public static List<Material> gatherWishlist(BuildTier target, boolean hasGlass,
                                                boolean hasSand) {
        // Srub (PROVISIONAL) okna nezasklívá; se sklem/pískem už není co shánět.
        if (target == null || target.ordinal() < BuildTier.SOLID.ordinal()
                || hasGlass || hasSand) {
            return List.of();
        }
        // Sklo do oken: natěžit písek, pec ho roztaví (SmeltGoal).
        return List.of(Material.SAND, Material.RED_SAND);
    }
}
