package dev.botalive.core.ai;

import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.inventory.InventoryHelper;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Potřeby bota odvozené z inventáře – „proč" za jeho rozhodnutími.
 *
 * <p>Utility AI říká „jak moc chci těžit"; potřeby říkají <b>co</b> a <b>proč</b>:
 * bot bez kamenného krumpáče chce kámen, s kamenným chce železo (a uhlí na
 * pochodně), se železným chce diamanty. Z potřeb se skládá „wishlist" těžby,
 * priority craftu i lidsky čitelné vysvětlení záměru. Čistá funkce nad
 * {@link ServerSideView.Snapshot} – jednotkově testovatelná.</p>
 *
 * @param pickaxeTier      nejlepší krumpáč (0 = žádný, 1 = dřevo, 3 = kámen, 4 = železo, 5+ = diamant…)
 * @param hasAxe           má sekeru
 * @param hasSword         má meč
 * @param hasTorches       má pochodně
 * @param hasWood          má klády nebo prkna
 * @param hasCobble        má cobblestone (na kamenné nástroje)
 * @param hasIronMaterial  má železo (surové/ingoty) k dalšímu zpracování
 * @param buildingBlocks   odhad počtu stavebních bloků
 * @param hasFood          má něco k snědku
 * @param hasFlintKit      má pazourek nebo hotové křesadlo (příprava na Nether)
 * @param obsidian         odhad kusů obsidiánu (na rám portálu je třeba 14)
 */
public record BotNeeds(int pickaxeTier, boolean hasAxe, boolean hasSword,
                       boolean hasTorches, boolean hasWood, boolean hasCobble,
                       boolean hasIronMaterial, int buildingBlocks, boolean hasFood,
                       boolean hasFlintKit, int obsidian) {

    /** Konzervativní odhad kusů v jednom slotu hlavního inventáře (bez počtů). */
    private static final int MAIN_SLOT_ESTIMATE = 16;

    /**
     * Vyhodnotí potřeby ze server-side snapshotu.
     *
     * @param snapshot snapshot inventáře (může být {@code null} – prázdné potřeby)
     * @return potřeby bota
     */
    public static BotNeeds assess(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return new BotNeeds(0, false, false, false, false, false, false, 0, false, false, 0);
        }
        int pickTier = 0;
        boolean axe = false;
        boolean sword = false;
        for (Material material : allItems(snapshot)) {
            if (material == null) {
                continue;
            }
            if (InventoryHelper.isTool(material, InventoryHelper.ToolType.PICKAXE)) {
                pickTier = Math.max(pickTier, InventoryHelper.toolTier(material));
            }
            axe = axe || InventoryHelper.isTool(material, InventoryHelper.ToolType.AXE);
            sword = sword || InventoryHelper.isTool(material, InventoryHelper.ToolType.SWORD);
        }
        return new BotNeeds(
                pickTier, axe, sword,
                snapshot.hasItem(m -> m == Material.TORCH),
                snapshot.hasItem(m -> {
                    String n = m.name();
                    return n.endsWith("_LOG") || n.endsWith("_PLANKS");
                }),
                snapshot.hasItem(m -> m == Material.COBBLESTONE
                        || m == Material.COBBLED_DEEPSLATE),
                snapshot.hasItem(m -> m == Material.RAW_IRON || m == Material.IRON_INGOT
                        || m == Material.IRON_ORE || m == Material.DEEPSLATE_IRON_ORE),
                countBuildingBlocks(snapshot),
                snapshot.hasItem(InventoryHelper::isFood),
                snapshot.hasItem(m -> m == Material.FLINT
                        || m == Material.FLINT_AND_STEEL),
                InventoryHelper.countEstimate(snapshot, m -> m == Material.OBSIDIAN));
    }

    /**
     * Hladoví bot bez jídla? (spouštěč nouzového chování)
     *
     * @param foodLevel aktuální hlad 0–20
     * @return {@code true} pokud je zle: málo jídla v břiše a nic v batohu
     */
    public boolean starving(int foodLevel) {
        return foodLevel <= 8 && !hasFood;
    }

    /**
     * @return {@code true} pokud bot nemá vůbec nic, čím by se posunul
     *         (žádný nástroj, dřevo ani materiál) – druhý spouštěč nouze
     */
    public boolean destitute() {
        return pickaxeTier == 0 && !hasAxe && !hasWood && !hasCobble
                && buildingBlocks == 0;
    }

    /**
     * Seřazený seznam bloků, které má právě smysl těžit (nejdřív ten
     * nejpotřebnější). Zahrnuje jen cíle, které bot aktuálním krumpáčem
     * skutečně vytěží (tier gating – z železné rudy dřevěným krumpáčem
     * nic nepadá).
     *
     * @return wishlist materiálů k těžbě (rodiny včetně deepslate variant)
     */
    public List<Material> miningWishlist() {
        List<Material> wishlist = new ArrayList<>();
        // Kamenné nástroje: mít krumpáč a nemít kámen → kopat kámen.
        if (pickaxeTier >= 1 && pickaxeTier < 3 && !hasCobble) {
            wishlist.add(Material.STONE);
        }
        // Pochodně a tavení: uhlí dává smysl vždy, když chybí pochodně.
        if (pickaxeTier >= 1 && !hasTorches) {
            wishlist.add(Material.COAL_ORE);
            wishlist.add(Material.DEEPSLATE_COAL_ORE);
        }
        // Železo: kamenným krumpáčem, dokud nemá železný ani materiál na něj.
        if (pickaxeTier >= 3 && pickaxeTier < 4 && !hasIronMaterial) {
            wishlist.add(Material.IRON_ORE);
            wishlist.add(Material.DEEPSLATE_IRON_ORE);
        }
        // Diamanty: železným a lepším krumpáčem.
        if (pickaxeTier >= 4) {
            wishlist.add(Material.DIAMOND_ORE);
            wishlist.add(Material.DEEPSLATE_DIAMOND_ORE);
        }
        // Příprava na Nether: s diamantovým krumpáčem shánět pazourek
        // (gravel) na křesadlo a obsidián na rám portálu.
        if (pickaxeTier >= 5 && !hasFlintKit) {
            wishlist.add(Material.GRAVEL);
        }
        if (pickaxeTier >= 5 && obsidian < dev.botalive.core.nether.PortalBlueprint.OBSIDIAN_NEEDED) {
            wishlist.add(Material.OBSIDIAN);
        }
        return wishlist;
    }

    /**
     * Minimální tier krumpáče, aby z bloku něco padalo (vanilla pravidla).
     *
     * @param block těžený blok
     * @return tier dle {@link InventoryHelper#toolTier} (1 = dřevěný stačí)
     */
    public static int requiredPickTier(Material block) {
        String name = block.name();
        if (block == Material.ANCIENT_DEBRIS || block == Material.OBSIDIAN
                || block == Material.CRYING_OBSIDIAN) {
            return 5; // jen diamantový a lepší
        }
        if (block == Material.NETHER_GOLD_ORE || block == Material.NETHER_QUARTZ_ORE) {
            return 1; // netherové rudy padají z libovolného krumpáče
        }
        if (name.contains("DIAMOND_ORE") || name.contains("EMERALD_ORE")
                || name.contains("GOLD_ORE") || name.contains("REDSTONE_ORE")) {
            return 4; // železný a lepší
        }
        if (name.contains("IRON_ORE") || name.contains("LAPIS_ORE")) {
            return 3; // kamenný a lepší
        }
        return 1; // uhlí, měď, kámen – stačí dřevěný
    }

    /**
     * @param block blok k těžbě
     * @return {@code true} pokud aktuální krumpáč blok skutečně vytěží (padne drop)
     */
    public boolean canHarvest(Material block) {
        return pickaxeTier >= requiredPickTier(block);
    }

    /**
     * Lidsky čitelný důvod, proč bot daný blok těží (pro intent/chat).
     *
     * @param block těžený blok
     * @return krátké české zdůvodnění
     */
    public String miningReason(Material block) {
        String name = block.name();
        if (name.contains("COAL")) {
            return hasTorches ? "hodí se do pece" : "potřebuju uhlí na pochodně";
        }
        if (name.contains("IRON")) {
            return "chci si vyrobit železný krumpáč";
        }
        if (name.contains("DIAMOND")) {
            return "diamanty!";
        }
        if (name.equals("GRAVEL")) {
            return "sháním pazourek na křesadlo";
        }
        if (name.equals("OBSIDIAN")) {
            return "obsidián na portál do Netheru";
        }
        if (name.equals("ANCIENT_DEBRIS")) {
            return "starodávné trosky – netherit!";
        }
        if (name.contains("QUARTZ") || name.equals("GLOWSTONE")) {
            return "netherová kořist";
        }
        if (name.equals("STONE") || name.equals("DEEPSLATE")) {
            return "potřebuju kámen na nástroje";
        }
        if (name.endsWith("_LOG")) {
            return hasWood ? "dřevo se vždycky hodí" : "potřebuju dřevo";
        }
        return "za tohle jsou peníze";
    }

    /** @return normalizovaná rodina rudy ({@code DEEPSLATE_X_ORE} → {@code X_ORE}) */
    public static String oreFamily(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") ? name.substring("DEEPSLATE_".length()) : name;
    }

    /** Počet stavebních bloků (skutečné počty; bez nich konzervativní odhad). */
    private static int countBuildingBlocks(ServerSideView.Snapshot snapshot) {
        int count = 0;
        Material[] hotbar = snapshot.hotbar();
        int[] counts = snapshot.hotbarCounts();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null && InventoryHelper.isBuildingBlock(hotbar[i])) {
                count += counts != null && i < counts.length ? Math.max(counts[i], 1) : 1;
            }
        }
        Material[] main = snapshot.mainInventory();
        int[] mainCounts = snapshot.mainCounts();
        for (int i = 0; i < main.length; i++) {
            if (main[i] != null && InventoryHelper.isBuildingBlock(main[i])) {
                count += mainCounts != null && i < mainCounts.length
                        ? Math.max(mainCounts[i], 1) : MAIN_SLOT_ESTIMATE;
            }
        }
        return count;
    }

    private static List<Material> allItems(ServerSideView.Snapshot snapshot) {
        List<Material> items = new ArrayList<>();
        for (Material material : snapshot.hotbar()) {
            items.add(material);
        }
        for (Material material : snapshot.mainInventory()) {
            items.add(material);
        }
        return items;
    }

    /** @return jméno bloku malými písmeny pro chat („iron_ore" → „železnou rudu" neřešíme – stačí anglicky) */
    public static String blockLabel(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }
}
