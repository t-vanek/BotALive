package dev.botalive.core.build.plan;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Suroviny, které má smysl cíleně natěžit pro daný stavební stupeň domu –
 * „build wishlist" vedle {@code BotNeeds.miningWishlist}. Čistá funkce; napojuje
 * ji {@code MineGoal} do výběru cíle (nižší priorita než rudy, ať těžba
 * nástrojů má přednost).
 *
 * <p>Pokrývá kompletní autonomní řetězce: <b>písek → sklo</b> (okna solidního+
 * domu; pec taví, {@code SmeltGoal}) a <b>hlína → cihla → blok cihel</b> (zdi,
 * základ i střecha reprezentativního domu; pec taví, {@code CraftPlanner}
 * skládá). Oba jsou gate-ované samotným sběrem: hlínu/písek si natěží jen
 * stavitel, jehož cílový tier je materiál vyžaduje.</p>
 */
public final class BuildMaterials {

    private BuildMaterials() {
    }

    /**
     * @param target        cílový stavební stupeň domu ({@code null} = žádný)
     * @param hasGlass      má bot sklo nebo skleněné tabule?
     * @param hasSand       má bot písek (už se taví na sklo)?
     * @param hasRawClay    má bot hlínu nebo hliněné kuličky (už se zpracovává)?
     * @param hasBrickBlock má bot hotové cihlové bloky (zásoba pro tuto etapu)?
     * @return suroviny k natěžení pro tier, které bot ještě nemá; prázdné pro
     *         srub (okna jsou otvory, zdi ze dřeva) i když bot potřebné už má
     */
    public static List<Material> gatherWishlist(BuildTier target, boolean hasGlass,
                                                boolean hasSand, boolean hasRawClay,
                                                boolean hasBrickBlock) {
        if (target == null) {
            return List.of();
        }
        List<Material> wishlist = new ArrayList<>();
        // Sklo do oken (SOLID+): natěžit písek, pec ho roztaví na sklo.
        if (target.ordinal() >= BuildTier.SOLID.ordinal() && !hasGlass && !hasSand) {
            wishlist.add(Material.SAND);
            wishlist.add(Material.RED_SAND);
        }
        // Cihly do reprezentativního domu (REFINED): natěžit hlínu (→ cihly).
        // Nesbírat, když už hlínu zpracovává nebo má hotové cihlové bloky – tempo
        // je střídmé, řetězec se sám dotočí (těžba → tavba → craft → stavba).
        if (target == BuildTier.REFINED && !hasRawClay && !hasBrickBlock) {
            wishlist.add(Material.CLAY);
        }
        return wishlist;
    }
}
