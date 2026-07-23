package dev.botalive.core.ai;

import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.inventory.Items;
import org.bukkit.Material;

/**
 * Připravenost bota na výpravu do Endu – čistá funkce nad snapshotem.
 *
 * <p>Do Endu se nechodí v tričku: minimum je železný meč, většina brnění,
 * jídlo a zásoba bloků na mosty přes void. Luk se šípy není podmínka, ale
 * bez něj se nedají sestřelit dračí krystaly – „dobře vyzbrojený" bot má
 * výrazně lepší vyhlídky a utility výpravy to zohledňuje.</p>
 *
 * @param swordTier      nejlepší meč (0 = žádný, 3 = kamenný, 4 = železný, 5+ = diamant)
 * @param armorPieces    počet oblečených kusů brnění (0–4)
 * @param hasBow         má luk nebo kuši
 * @param arrows         odhad počtu šípů
 * @param foodCount      odhad počtu kusů jídla
 * @param buildingBlocks odhad počtu stavebních bloků (mosty!)
 * @param hasPickaxe     má krumpáč (end stone, průkopy)
 */
public record EndReadiness(int swordTier, int armorPieces, boolean hasBow, int arrows,
                           int foodCount, int buildingBlocks, boolean hasPickaxe) {

    /** Minimální tier meče pro výpravu (železo). */
    private static final int MIN_SWORD_TIER = 4;

    /**
     * Vyhodnotí připravenost ze server-side snapshotu.
     *
     * @param snapshot snapshot inventáře ({@code null} = nepřipraven)
     * @return připravenost
     */
    public static EndReadiness assess(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return new EndReadiness(0, 0, false, 0, 0, 0, false);
        }
        int swordTier = 0;
        boolean pickaxe = false;
        for (Material material : snapshot.hotbar()) {
            if (material == null) {
                continue;
            }
            swordTier = Math.max(swordTier, swordTier(material));
            pickaxe = pickaxe || InventoryHelper.isTool(material, InventoryHelper.ToolType.PICKAXE);
        }
        for (Material material : snapshot.mainInventory()) {
            if (material == null) {
                continue;
            }
            swordTier = Math.max(swordTier, swordTier(material));
            pickaxe = pickaxe || InventoryHelper.isTool(material, InventoryHelper.ToolType.PICKAXE);
        }
        int armor = 0;
        for (Material material : snapshot.armor()) {
            // Porovnání konstant, ne isAir() – to potřebuje Bukkit Registry
            // (za běhu serveru), a připravenost musí jít spočítat i v testech.
            if (material != null && material != Material.AIR) {
                armor++;
            }
        }
        boolean bow = snapshot.hasItem(Items::isBow);
        int arrows = InventoryHelper.countEstimate(snapshot, m -> m == Material.ARROW
                || m == Material.SPECTRAL_ARROW || m == Material.TIPPED_ARROW);
        int food = InventoryHelper.countEstimate(snapshot, InventoryHelper::isFood);
        int blocks = InventoryHelper.countEstimate(snapshot, InventoryHelper::isBuildingBlock);
        return new EndReadiness(swordTier, armor, bow, arrows, food, blocks, pickaxe);
    }

    /** @return {@code true} pokud výbava stačí na výpravu do Endu */
    public boolean expeditionReady() {
        // Krumpáč je nutný: end stone na mosty se bez něj netěží a bot by
        // po vyčerpání bloků uvízl na ostrůvku.
        return swordTier >= MIN_SWORD_TIER && armorPieces >= 3
                && foodCount >= 5 && buildingBlocks >= 32 && hasPickaxe;
    }

    /** @return {@code true} pokud má bot luk a rozumnou zásobu šípů (krystaly) */
    public boolean wellArmed() {
        return hasBow && arrows >= 16;
    }

    private static int swordTier(Material material) {
        if (material == null || !InventoryHelper.isTool(material, InventoryHelper.ToolType.SWORD)) {
            return 0;
        }
        return InventoryHelper.toolTier(material);
    }
}
