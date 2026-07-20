package dev.botalive.core.build.plan;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * Rozpis stavebního materiálu: kolik kterého zamýšleného bloku stavba
 * spotřebuje (podle {@link Palette}). Živí cílené shánění (build wishlist) –
 * bot si na dům dojde pro prkna, sklo a kámen místo aby čekal, co se namane.
 * Vybavení (dveře, postel, truhla) řeší {@code CraftGoal}, proto v rozpisu
 * nefiguruje. Prázdný pro {@link Palette#GENERIC} (legacy staví oportunisticky).
 */
public final class BillOfMaterials {

    private BillOfMaterials() {
    }

    /**
     * @param plan    rozvinutý plán stavby
     * @param palette paleta
     * @return materiál → počet (zamýšlené bloky konstrukce)
     */
    public static Map<Material, Integer> of(BuildPlan plan, Palette palette) {
        Map<Material, Integer> bom = new EnumMap<>(Material.class);
        for (PlacementCell cell : plan.cells()) {
            palette.intended(cell.spec().role())
                    .ifPresent(material -> bom.merge(material, 1, Integer::sum));
        }
        return bom;
    }
}
