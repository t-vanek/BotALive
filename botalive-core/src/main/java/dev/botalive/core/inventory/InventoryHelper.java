package dev.botalive.core.inventory;

import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.network.BotActions;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.Locale;

/**
 * Výběr správného nástroje a práce s hotbarem.
 *
 * <p>Rozhoduje nad server-side snapshotem inventáře (autoritativní materiály)
 * a vykonává přes klientské pakety (přepnutí hotbar slotu). Nástroj se vybírá
 * podle kategorie cílového bloku a tieru nástroje (netherite &gt; diamant &gt; ...).</p>
 */
public final class InventoryHelper {

    private final BotActions actions;

    /**
     * @param actions akční primitivy (přepínání slotů)
     */
    public InventoryHelper(BotActions actions) {
        this.actions = actions;
    }

    /** Kategorie nástroje. */
    public enum ToolType {
        /** Krumpáč – kámen, rudy. */
        PICKAXE,
        /** Sekera – dřevo. */
        AXE,
        /** Lopata – hlína, písek, štěrk. */
        SHOVEL,
        /** Motyka – listí, sena. */
        HOE,
        /** Meč – pavučiny, boj. */
        SWORD,
        /** Ruka stačí. */
        NONE
    }

    /**
     * Určí správný typ nástroje pro blok.
     *
     * @param block materiál bloku
     * @return typ nástroje
     */
    public static ToolType toolFor(Material block) {
        if (Tag.MINEABLE_PICKAXE.isTagged(block)) {
            return ToolType.PICKAXE;
        }
        if (Tag.MINEABLE_AXE.isTagged(block)) {
            return ToolType.AXE;
        }
        if (Tag.MINEABLE_SHOVEL.isTagged(block)) {
            return ToolType.SHOVEL;
        }
        if (Tag.MINEABLE_HOE.isTagged(block)) {
            return ToolType.HOE;
        }
        if (block == Material.COBWEB) {
            return ToolType.SWORD;
        }
        return ToolType.NONE;
    }

    /**
     * @param material materiál itemu
     * @param type     hledaný typ nástroje
     * @return {@code true} pokud item je nástrojem daného typu
     */
    public static boolean isTool(Material material, ToolType type) {
        String name = material.name();
        return switch (type) {
            case PICKAXE -> name.endsWith("_PICKAXE");
            case AXE -> name.endsWith("_AXE") && !name.endsWith("_PICKAXE");
            case SHOVEL -> name.endsWith("_SHOVEL");
            case HOE -> name.endsWith("_HOE");
            case SWORD -> name.endsWith("_SWORD");
            case NONE -> false;
        };
    }

    /**
     * Tier nástroje pro porovnání (vyšší = lepší).
     *
     * @param material materiál nástroje
     * @return tier 0–6
     */
    public static int toolTier(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT);
        if (name.startsWith("netherite_")) {
            return 6;
        }
        if (name.startsWith("diamond_")) {
            return 5;
        }
        if (name.startsWith("iron_")) {
            return 4;
        }
        if (name.startsWith("stone_")) {
            return 3;
        }
        if (name.startsWith("golden_")) {
            return 2;
        }
        if (name.startsWith("wooden_")) {
            return 1;
        }
        return 0;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud je item k snědku (bez nežádoucích efektů)
     */
    public static boolean isFood(Material material) {
        return material.isEdible()
                && material != Material.ROTTEN_FLESH
                && material != Material.SPIDER_EYE
                && material != Material.POISONOUS_POTATO
                && material != Material.PUFFERFISH
                && material != Material.CHORUS_FRUIT;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o blok vhodný ke stavění úkrytu
     */
    public static boolean isBuildingBlock(Material material) {
        return material == Material.DIRT || material == Material.COBBLESTONE
                || material == Material.COBBLED_DEEPSLATE || material == Material.NETHERRACK
                || material == Material.STONE || material == Material.OAK_PLANKS
                || material == Material.SPRUCE_PLANKS || material == Material.BIRCH_PLANKS;
    }

    /**
     * Najde a vybere nejlepší nástroj pro blok v hotbaru.
     *
     * @param snapshot server-side snapshot inventáře
     * @param block    cílový blok
     * @return {@code true} pokud byl nalezen a vybrán vhodný nástroj (jinak
     *         zůstává aktuální slot – kopat lze i rukou, jen pomalu)
     */
    public boolean equipBestTool(ServerSideView.Snapshot snapshot, Material block) {
        ToolType type = toolFor(block);
        if (type == ToolType.NONE || snapshot == null) {
            return false;
        }
        int bestSlot = -1;
        int bestTier = -1;
        Material[] hotbar = snapshot.hotbar();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null && isTool(hotbar[i], type) && toolTier(hotbar[i]) > bestTier) {
                bestTier = toolTier(hotbar[i]);
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) {
            actions.selectHotbar(bestSlot);
            return true;
        }
        return false;
    }

    /**
     * Najde a vybere zbraň (meč, případně sekeru).
     *
     * @param snapshot server-side snapshot
     * @return {@code true} pokud má bot zbraň v ruce
     */
    public boolean equipWeapon(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        int slot = snapshot.findHotbarSlot(m -> isTool(m, ToolType.SWORD));
        if (slot < 0) {
            slot = snapshot.findHotbarSlot(m -> isTool(m, ToolType.AXE));
        }
        if (slot >= 0) {
            actions.selectHotbar(slot);
            return true;
        }
        return false;
    }

    /**
     * Najde a vybere jídlo.
     *
     * @param snapshot server-side snapshot
     * @return {@code true} pokud bot drží jídlo
     */
    public boolean equipFood(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        int slot = snapshot.findHotbarSlot(InventoryHelper::isFood);
        if (slot >= 0) {
            actions.selectHotbar(slot);
            return true;
        }
        return false;
    }

    /**
     * Najde a vybere stavební blok.
     *
     * @param snapshot server-side snapshot
     * @return {@code true} pokud bot drží stavební blok
     */
    public boolean equipBuildingBlock(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        int slot = snapshot.findHotbarSlot(InventoryHelper::isBuildingBlock);
        if (slot >= 0) {
            actions.selectHotbar(slot);
            return true;
        }
        return false;
    }
}
