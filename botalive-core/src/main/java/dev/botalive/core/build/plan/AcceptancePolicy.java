package dev.botalive.core.build.plan;

import dev.botalive.core.inventory.InventoryHelper;

import org.bukkit.Material;

/**
 * Jediné místo pravdy „je tenhle blok na téhle pozici v pořádku?" pro
 * world-diff: resume rozestavěné stavby a opravu ({@code MaintainHomeGoal}).
 * Blok projde, když patří mezi přijatelné materiály role palety; u
 * {@link PaletteRole#GENERIC} a nevyřešených rolí stačí jakýkoli stavební
 * blok (přírodní kámen ve zdi ze zaměnitelného bloku je v pořádku, dřív
 * postavená stavba se nebourá kvůli odstínu).
 */
public final class AcceptancePolicy {

    private AcceptancePolicy() {
    }

    /**
     * @param role    role bloku na dané pozici
     * @param actual  skutečný materiál ve světě (může být {@code null})
     * @param palette paleta stavby
     * @return {@code true} když je materiál pro tuto roli přijatelný
     */
    public static boolean accepts(PaletteRole role, Material actual, Palette palette) {
        if (actual == null || actual == Material.AIR) {
            return false;
        }
        var accepted = palette.accepted(role);
        if (!accepted.isEmpty()) {
            return accepted.contains(actual);
        }
        // GENERIC / nevyřešená role – jakýkoli zaměnitelný stavební blok.
        return InventoryHelper.isBuildingBlock(actual);
    }
}
