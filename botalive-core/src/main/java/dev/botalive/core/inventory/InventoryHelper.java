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

    /**
     * Přitažení itemu z hlavního inventáře do hotbaru (kliky ve vlastním okně).
     *
     * @see #puller(ItemPuller)
     */
    @FunctionalInterface
    public interface ItemPuller {
        /**
         * @param predicate co přitáhnout
         * @return hotbar index, kam item dorazil, nebo -1
         */
        int pullToHotbar(java.util.function.Predicate<Material> predicate);
    }

    private final BotActions actions;
    private ItemPuller puller;

    /**
     * @param actions akční primitivy (přepínání slotů)
     */
    public InventoryHelper(BotActions actions) {
        this.actions = actions;
    }

    /**
     * Napojí přitahování itemů z hlavního inventáře – equip metody pak umí
     * sáhnout i mimo hotbar (SWAP klik ve vlastním okně inventáře).
     *
     * @param puller implementace přitažení (typicky BotImpl přes ContainerClicker)
     */
    public void puller(ItemPuller puller) {
        this.puller = puller;
    }

    /** Vybere slot: hotbar → přitažení z hlavního inventáře → -1. */
    private int slotOrPull(ServerSideView.Snapshot snapshot,
                           java.util.function.Predicate<Material> predicate) {
        int slot = snapshot.findHotbarSlot(predicate);
        if (slot >= 0) {
            return slot;
        }
        return puller != null ? puller.pullToHotbar(predicate) : -1;
    }

    /**
     * Najde v hlavním inventáři nejlepší slot vyhovující predikátu
     * (řadí podle tieru nástroje/brnění; jinak první výskyt).
     *
     * @param snapshot  server-side snapshot
     * @param predicate podmínka na materiál
     * @return index v {@code mainInventory()} (0–26), nebo -1
     */
    public static int findBestInMain(ServerSideView.Snapshot snapshot,
                                     java.util.function.Predicate<Material> predicate) {
        if (snapshot == null) {
            return -1;
        }
        Material[] main = snapshot.mainInventory();
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < main.length; i++) {
            if (main[i] == null || !predicate.test(main[i])) {
                continue;
            }
            int score = Math.max(toolTier(main[i]), armorTier(main[i]));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * Vybere hotbar slot, kam lze bezpečně odložit přitažený item:
     * prázdný → obyčejný materiál (bloky) → nejpočetnější stack. Nikdy nástroj
     * ani jídlo, pokud existuje jiná možnost.
     *
     * @param snapshot server-side snapshot
     * @return hotbar index 0–8
     */
    public static int chooseHotbarDumpSlot(ServerSideView.Snapshot snapshot) {
        Material[] hotbar = snapshot.hotbar();
        for (int j = 0; j < hotbar.length; j++) {
            if (hotbar[j] == null) {
                return j;
            }
        }
        for (int j = 0; j < hotbar.length; j++) {
            if (isBuildingBlock(hotbar[j]) || hotbar[j] == Material.DIRT
                    || hotbar[j] == Material.GRAVEL || hotbar[j] == Material.SAND) {
                return j;
            }
        }
        int[] counts = snapshot.hotbarCounts();
        int best = 0;
        int bestCount = -1;
        for (int j = 0; j < hotbar.length; j++) {
            boolean precious = toolTier(hotbar[j]) > 0 || armorTier(hotbar[j]) > 0
                    || isFood(hotbar[j]);
            int count = counts != null ? counts[j] : 1;
            if (!precious && count > bestCount) {
                bestCount = count;
                best = j;
            }
        }
        return best;
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

    /** Záložní seznam jídel pro běh bez serveru (unit testy) – {@link Material#isEdible()}
     *  potřebuje Bukkit Registry, který je dostupný až za běhu serveru. */
    private static final java.util.Set<Material> FOOD_FALLBACK = java.util.Set.of(
            Material.BREAD, Material.APPLE, Material.GOLDEN_APPLE, Material.CARROT,
            Material.BAKED_POTATO, Material.COOKED_BEEF, Material.COOKED_PORKCHOP,
            Material.COOKED_CHICKEN, Material.COOKED_MUTTON, Material.COOKED_RABBIT,
            Material.COOKED_COD, Material.COOKED_SALMON, Material.MELON_SLICE,
            Material.SWEET_BERRIES, Material.COOKIE, Material.PUMPKIN_PIE);

    /**
     * @param material materiál
     * @return {@code true} pokud je item k snědku (bez nežádoucích efektů)
     */
    public static boolean isFood(Material material) {
        try {
            return material.isEdible()
                    && material != Material.ROTTEN_FLESH
                    && material != Material.SPIDER_EYE
                    && material != Material.POISONOUS_POTATO
                    && material != Material.PUFFERFISH
                    && material != Material.CHORUS_FRUIT;
        } catch (Throwable registryUnavailable) {
            return FOOD_FALLBACK.contains(material);
        }
    }

    /**
     * Nouzové jídlo – k snědku, ale s vedlejším efektem, který za normálních
     * okolností nestojí za to (chorus ovoce teleportuje). Sáhne se po něm,
     * až když pořádné jídlo došlo a hlad tlačí.
     *
     * @param material materiál
     * @return {@code true} pro jídlo „až když není zbytí"
     */
    public static boolean isEmergencyFood(Material material) {
        return material == Material.CHORUS_FRUIT;
    }

    /**
     * Odhad počtu kusů odpovídajících filtru: hotbar přesně, hlavní inventář
     * konzervativně 4 kusy na slot (snapshot počty hlavního inventáře nenese).
     *
     * @param snapshot snapshot inventáře ({@code null} = 0)
     * @param what     filtr materiálů
     * @return odhad počtu kusů
     */
    public static int countEstimate(ServerSideView.Snapshot snapshot,
                                    java.util.function.Predicate<Material> what) {
        if (snapshot == null) {
            return 0;
        }
        int count = 0;
        Material[] hotbar = snapshot.hotbar();
        int[] counts = snapshot.hotbarCounts();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null && what.test(hotbar[i])) {
                count += counts != null && i < counts.length ? Math.max(counts[i], 1) : 1;
            }
        }
        for (Material material : snapshot.mainInventory()) {
            if (material != null && what.test(material)) {
                count += 4;
            }
        }
        return count;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o blok vhodný ke stavění úkrytu
     */
    public static boolean isBuildingBlock(Material material) {
        return material == Material.DIRT || material == Material.COBBLESTONE
                || material == Material.COBBLED_DEEPSLATE || material == Material.NETHERRACK
                || material == Material.STONE || material == Material.OAK_PLANKS
                || material == Material.SPRUCE_PLANKS || material == Material.BIRCH_PLANKS
                || material == Material.END_STONE;
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
        // Lepší nástroj v hlavním inventáři? Přitáhnout (kliky vlastního okna).
        int mainBest = findBestInMain(snapshot, m -> isTool(m, type));
        if (puller != null && mainBest >= 0
                && toolTier(snapshot.mainInventory()[mainBest]) > bestTier) {
            int pulled = puller.pullToHotbar(m -> isTool(m, type));
            if (pulled >= 0) {
                actions.selectHotbar(pulled);
                return true;
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
        int slot = slotOrPull(snapshot, m -> isTool(m, ToolType.SWORD));
        if (slot < 0) {
            slot = slotOrPull(snapshot, m -> isTool(m, ToolType.AXE));
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
        int slot = slotOrPull(snapshot, InventoryHelper::isFood);
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
        int slot = slotOrPull(snapshot, InventoryHelper::isBuildingBlock);
        if (slot >= 0) {
            actions.selectHotbar(slot);
            return true;
        }
        return false;
    }

    /**
     * Index kusu brnění ve {@code snapshot.armor()} (Bukkit pořadí:
     * 0 boty, 1 kalhoty, 2 prsní plát, 3 helma).
     *
     * @param material materiál
     * @return index 0–3, nebo -1 pokud nejde o brnění
     */
    public static int armorSlot(Material material) {
        String name = material.name();
        if (name.endsWith("_BOOTS")) {
            return 0;
        }
        if (name.endsWith("_LEGGINGS")) {
            return 1;
        }
        if (name.endsWith("_CHESTPLATE")) {
            return 2;
        }
        if (name.endsWith("_HELMET")) {
            return 3;
        }
        return -1;
    }

    /**
     * Tier brnění pro porovnání (vyšší = lepší).
     *
     * @param material materiál brnění
     * @return tier 0–6
     */
    public static int armorTier(Material material) {
        String name = material.name();
        if (name.startsWith("NETHERITE_")) {
            return 6;
        }
        if (name.startsWith("DIAMOND_")) {
            return 5;
        }
        if (name.startsWith("IRON_")) {
            return 4;
        }
        if (name.startsWith("CHAINMAIL_")) {
            return 3;
        }
        if (name.startsWith("GOLDEN_") || name.startsWith("TURTLE_")) {
            return 2;
        }
        if (name.startsWith("LEATHER_")) {
            return 1;
        }
        return 0;
    }

    /**
     * Nasadí lepší kus brnění z hotbaru – klik pravým jako hráč (server
     * kus vymění s právě nošeným).
     *
     * @param snapshot server-side snapshot (včetně nošeného brnění)
     * @param yaw      aktuální yaw pohledu
     * @param pitch    aktuální pitch pohledu
     * @return {@code true} pokud se nasazoval nějaký kus
     */
    public boolean equipBetterArmor(ServerSideView.Snapshot snapshot, float yaw, float pitch) {
        if (snapshot == null || snapshot.armor() == null) {
            return false;
        }
        Material[] hotbar = snapshot.hotbar();
        for (int i = 0; i < hotbar.length; i++) {
            if (isArmorUpgrade(snapshot, hotbar[i])) {
                actions.selectHotbar(i);
                actions.useItem(yaw, pitch);
                return true;
            }
        }
        // Kus v hlavním inventáři? Přitáhnout klikem a rovnou nasadit.
        if (puller != null
                && findBestInMain(snapshot, m -> isArmorUpgrade(snapshot, m)) >= 0) {
            int pulled = puller.pullToHotbar(m -> isArmorUpgrade(snapshot, m));
            if (pulled >= 0) {
                actions.selectHotbar(pulled);
                actions.useItem(yaw, pitch);
                return true;
            }
        }
        return false;
    }

    /** Je item kus brnění lepší než právě nošený ve svém slotu? */
    private static boolean isArmorUpgrade(ServerSideView.Snapshot snapshot, Material item) {
        if (item == null) {
            return false;
        }
        int slot = armorSlot(item);
        if (slot < 0) {
            return false;
        }
        Material worn = snapshot.armor() != null && slot < snapshot.armor().length
                ? snapshot.armor()[slot] : null;
        return armorTier(item) > (worn == null ? 0 : armorTier(worn));
    }

    /**
     * Najde a vybere konkrétní item v hotbaru.
     *
     * @param snapshot server-side snapshot
     * @param material hledaný materiál
     * @return {@code true} pokud bot item drží
     */
    public boolean equipItem(ServerSideView.Snapshot snapshot, Material material) {
        if (snapshot == null) {
            return false;
        }
        int slot = slotOrPull(snapshot, m -> m == material);
        if (slot >= 0) {
            actions.selectHotbar(slot);
            return true;
        }
        return false;
    }

    /**
     * Najde a vybere item podle predikátu.
     *
     * @param snapshot  server-side snapshot
     * @param predicate podmínka na materiál
     * @return {@code true} pokud bot vyhovující item drží
     */
    public boolean equipMatching(ServerSideView.Snapshot snapshot,
                                 java.util.function.Predicate<Material> predicate) {
        if (snapshot == null) {
            return false;
        }
        int slot = slotOrPull(snapshot, predicate);
        if (slot >= 0) {
            actions.selectHotbar(slot);
            return true;
        }
        return false;
    }
}
