package dev.botalive.core.nether;

import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.inventory.InventoryHelper;
import org.bukkit.Material;

/**
 * Připravenost bota na výpravu do Netheru – čistá funkce nad snapshotem
 * inventáře (jednotkově testovatelná, stejný vzor jako {@code BotNeeds}).
 *
 * <p>Do pekla se nechodí nalehko: výprava chce slušnou zbraň a zbroj
 * (konfigurovatelný tier), zásobu jídla, krumpáč aspoň na quartz – a buď
 * známý portál, nebo materiál na stavbu vlastního (14 obsidiánu
 * + křesadlo).</p>
 *
 * @param pickaxeTier    nejlepší krumpáč (4 = železo, 5 = diamant)
 * @param weaponTier     nejlepší meč (0 = žádný)
 * @param hasRangedKit   luk/kuše + aspoň pár šípů (na ghasty)
 * @param armorPieces    počet nasazených kusů zbroje v požadovaném tieru+
 * @param foodCount      odhad kusů jídla
 * @param hasFlintSteel  má křesadlo
 * @param obsidian       odhad kusů obsidiánu
 * @param goldIngots     odhad zlatých ingotů (barter s pigliny)
 * @param hasGoldenBoots zlaté boty (piglini nechají bota na pokoji)
 */
public record NetherReadiness(int pickaxeTier, int weaponTier, boolean hasRangedKit,
                              int armorPieces, int foodCount, boolean hasFlintSteel,
                              int obsidian, int goldIngots, boolean hasGoldenBoots) {

    /** Minimální zásoba jídla na výpravu. */
    public static final int MIN_FOOD = 5;

    /**
     * Vyhodnotí připravenost ze snapshotu.
     *
     * @param snapshot    server-side snapshot (může být {@code null})
     * @param minGearTier minimální tier zbraně/zbroje z konfigurace
     * @return připravenost
     */
    public static NetherReadiness assess(ServerSideView.Snapshot snapshot, int minGearTier) {
        if (snapshot == null) {
            return new NetherReadiness(0, 0, false, 0, 0, false, 0, 0, false);
        }
        int pickaxe = 0;
        int weapon = 0;
        for (Material material : snapshot.hotbar()) {
            pickaxe = Math.max(pickaxe, pickTier(material));
            weapon = Math.max(weapon, swordTier(material));
        }
        for (Material material : snapshot.mainInventory()) {
            pickaxe = Math.max(pickaxe, pickTier(material));
            weapon = Math.max(weapon, swordTier(material));
        }
        int armor = 0;
        boolean goldenBoots = false;
        for (Material piece : snapshot.armor()) {
            if (piece == Material.GOLDEN_BOOTS) {
                goldenBoots = true;
                armor++; // zlaté boty se počítají – jsou tam schválně
            } else if (piece != null && InventoryHelper.armorTier(piece) >= minGearTier) {
                armor++;
            }
        }
        boolean bow = snapshot.hasItem(m -> m == Material.BOW || m == Material.CROSSBOW)
                && InventoryHelper.countEstimate(snapshot, m -> m == Material.ARROW
                        || m == Material.TIPPED_ARROW || m == Material.SPECTRAL_ARROW) >= 4;
        boolean goldenBootsAnywhere = goldenBoots
                || snapshot.hasItem(m -> m == Material.GOLDEN_BOOTS);
        return new NetherReadiness(
                pickaxe, weapon, bow, armor,
                InventoryHelper.countEstimate(snapshot, InventoryHelper::isFood),
                snapshot.hasItem(m -> m == Material.FLINT_AND_STEEL),
                InventoryHelper.countEstimate(snapshot, m -> m == Material.OBSIDIAN),
                InventoryHelper.countEstimate(snapshot, m -> m == Material.GOLD_INGOT),
                goldenBootsAnywhere);
    }

    /**
     * @param minGearTier minimální tier zbraně/zbroje
     * @return {@code true} pokud výbava na výpravu stačí
     */
    public boolean gearReady(int minGearTier) {
        boolean weaponReady = weaponTier >= minGearTier || hasRangedKit;
        return pickaxeTier >= 4 && weaponReady && armorPieces >= 3 && foodCount >= MIN_FOOD;
    }

    /** @return {@code true} pokud má materiál na stavbu vlastního portálu */
    public boolean canBuildPortal() {
        return hasFlintSteel && obsidian >= PortalBlueprint.OBSIDIAN_NEEDED;
    }

    private static int pickTier(Material material) {
        return material != null
                && InventoryHelper.isTool(material, InventoryHelper.ToolType.PICKAXE)
                ? InventoryHelper.toolTier(material) : 0;
    }

    private static int swordTier(Material material) {
        return material != null
                && InventoryHelper.isTool(material, InventoryHelper.ToolType.SWORD)
                ? InventoryHelper.toolTier(material) : 0;
    }
}
