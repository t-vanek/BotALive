package dev.botalive.core.crafting;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side simulace craftingu botů.
 *
 * <p><b>Volba implementace:</b> paketová cesta (otevření ponku + container
 * kliky) v protokolu 26.1 vyžaduje „hashed item stacky" a stavový automat nad
 * okny – je extrémně křehká vůči změnám protokolu. Server-side simulace přes
 * {@link org.bukkit.Server#craftItemResult} používá skutečné receptury serveru
 * (včetně custom receptů z jiných pluginů a craft eventů), je atomická a
 * validovaná. Trade-off: ostatní hráči nevidí otevřené okno ponku – to je
 * jediný pozorovatelný rozdíl; lidskost dodává {@code CraftGoal} (bot se
 * zastaví, „přemýšlí", mává rukou).</p>
 *
 * <p>Pravidla realističnosti: recepty s přesahem 2×2 vyžadují, aby bot měl
 * v inventáři ponk ({@code CRAFTING_TABLE}) – stejné omezení jako u hráče,
 * jen bez nutnosti ho pokládat.</p>
 */
public final class CraftingService implements dev.botalive.core.station.CraftingStation {

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na vlákno entity
     */
    public CraftingService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletableFuture<String> craftNext(dev.botalive.core.ai.BotContext ctx,
                                               boolean wantsMasonry) {
        return craftNextInProgression(ctx.bot().id(), wantsMasonry);
    }

    /**
     * Zkusí vyrobit další recept z progrese bota. Vše (kontrola surovin,
     * spotřeba, vložení výsledku) proběhne atomicky na vlákně entity.
     *
     * @param botId        UUID bota
     * @param wantsMasonry míří bot na reprezentativní dům (odemyká tesané cihly)?
     * @return future s id vyrobeného receptu, nebo {@code null} když není co vyrábět
     */
    public CompletableFuture<String> craftNextInProgression(UUID botId, boolean wantsMasonry) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            return CompletableFuture.completedFuture(null);
        }
        return bridge.callForEntity(player, () -> {
            CraftPlanner.Plan plan = nextPlan(player.getInventory(), wantsMasonry);
            if (plan == null) {
                return null;
            }
            return executePlan(player, plan) ? plan.id() : null;
        });
    }

    /**
     * Rozhodne, co by měl bot vyrobit jako další krok survival progrese –
     * sestaví souhrn z živého inventáře a deleguje na sdílený
     * {@link CraftPlanner}. Běží na vlákně entity.
     *
     * @param inventory    inventář bota
     * @param wantsMasonry míří bot na reprezentativní dům (odemyká tesané cihly)?
     * @return plán, nebo {@code null} když nic nedává smysl
     */
    public static CraftPlanner.Plan nextPlan(PlayerInventory inventory, boolean wantsMasonry) {
        Map<Material, Integer> items = new java.util.HashMap<>();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && !item.getType().isAir() && !nearlyBroken(item)) {
                items.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        int cobbleStd = items.getOrDefault(Material.COBBLESTONE, 0);
        return CraftPlanner.next(new CraftPlanner.State(items,
                firstMatching(inventory, m -> Tag.LOGS.isTagged(m)),
                firstMatching(inventory, m -> Tag.PLANKS.isTagged(m)),
                cobbleStd >= 3 ? Material.COBBLESTONE : Material.COBBLED_DEEPSLATE,
                firstMatching(inventory, m -> m.name().endsWith("_WOOL")),
                wantsMasonry));
    }

    private static boolean containsMatching(PlayerInventory inventory,
                                            java.util.function.Predicate<Material> predicate) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && predicate.test(item.getType())) {
                return true;
            }
        }
        return false;
    }

    /** Provede plán: ověří suroviny (+ ponk), spotřebuje a vloží výsledek. */
    private static boolean executePlan(Player player, CraftPlanner.Plan plan) {
        PlayerInventory inventory = player.getInventory();
        if (plan.needsTable() && !inventory.contains(Material.CRAFTING_TABLE)) {
            return false;
        }
        for (Map.Entry<Material, Integer> entry : plan.ingredients().entrySet()) {
            if (count(inventory, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            if (plan.matrix()[i] != null) {
                matrix[i] = new ItemStack(plan.matrix()[i], 1);
            }
        }
        ItemCraftResult result = Bukkit.craftItemResult(matrix, player.getWorld(), player);
        ItemStack crafted = result.getResult();
        if (crafted == null || crafted.getType().isAir()) {
            return false; // recept na tomto serveru neexistuje/změněn
        }

        // Atomická spotřeba + vložení výsledku (jsme na vlákně entity).
        for (Map.Entry<Material, Integer> entry : plan.ingredients().entrySet()) {
            inventory.removeItem(new ItemStack(entry.getKey(), entry.getValue()));
        }
        var overflow = inventory.addItem(crafted);
        result.getOverflowItems().forEach(item -> inventory.addItem(item)
                .values().forEach(rest -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), rest)));
        overflow.values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
        return true;
    }

    // ---------------------------------------------- výroba stanice dílny na míru

    private static final java.util.function.Predicate<Material> PLANKS =
            m -> m.name().endsWith("_PLANKS");
    private static final java.util.function.Predicate<Material> WOOD_SLAB =
            m -> m.name().endsWith("_SLAB") && !m.name().contains("STONE")
                    && !m.name().contains("BRICK") && !m.name().contains("COBBLE")
                    && !m.name().contains("QUARTZ") && !m.name().contains("SANDSTONE");
    private static final java.util.function.Predicate<Material> STONE_SLAB =
            m -> m == Material.STONE_SLAB || m == Material.SMOOTH_STONE_SLAB;
    /**
     * Surový kámen, který kameník/zbrojíř opracuje (dlaždice, řezaný kámen).
     * Zahrnuje i dlažební kámen: ten boti z těžby kupí, kdežto hladký kámen se
     * v progresi netvoří do zásoby (řetěz ho rovnou spálí na blastovou pec).
     */
    private static final java.util.function.Predicate<Material> STONE_SOURCE =
            m -> m == Material.COBBLESTONE || m == Material.STONE || m == Material.SMOOTH_STONE;

    /**
     * Surovina receptu stanice: predikát materiálu + počet. Volitelně
     * meziprodukt, který si stavitel domáčkne z běžných surovin ({@code from},
     * {@code yield} kusů na jedno spuštění, výsledek {@code produces}), když ho
     * v batohu nemá – řezák chce kamennou dlaždici, pult knihovnu; ty bot běžně
     * nedrží, ale suroviny na ně (kámen, prkna, knihy) ano. {@code from}
     * prázdné = surovina se neodvozuje.
     */
    private record StationIngredient(java.util.function.Predicate<Material> match, int count,
                                     java.util.List<BaseCost> from, int yield, Material produces) {
        StationIngredient(java.util.function.Predicate<Material> match, int count) {
            this(match, count, java.util.List.of(), 0, null);
        }

        boolean derivable() {
            return !from.isEmpty();
        }
    }

    /** Náklad na jedno spuštění odvození meziproduktu: predikát suroviny + počet. */
    private record BaseCost(java.util.function.Predicate<Material> match, int perBatch) {
    }

    /**
     * Recepty stanic účelných dílen, které nejsou v běžné progresi (šípařská
     * deska, řezák, kotlík…). Umožní staviteli dílny vyrobit stanici na míru
     * (jen on, když staví právě tuhle dílnu) – bez globálního plýtvání
     * v {@code CraftPlanner}. Ostatní stanice (pec, ponk, pece…) si bot dělá
     * progresí, takže tu nejsou. Kamenná dlaždice / knihovna se domáčkne
     * z běžných surovin, jinak by řezák a pult byly nedosažitelné.
     */
    private static final Map<Material, java.util.List<StationIngredient>> STATION_RECIPES =
            Map.of(
                    Material.FLETCHING_TABLE, java.util.List.of(
                            new StationIngredient(PLANKS, 4),
                            new StationIngredient(m -> m == Material.FLINT, 2)),
                    Material.CARTOGRAPHY_TABLE, java.util.List.of(
                            new StationIngredient(PLANKS, 4),
                            new StationIngredient(m -> m == Material.PAPER, 2)),
                    Material.LOOM, java.util.List.of(
                            new StationIngredient(PLANKS, 2),
                            new StationIngredient(m -> m == Material.STRING, 2)),
                    Material.CAULDRON, java.util.List.of(
                            new StationIngredient(m -> m == Material.IRON_INGOT, 7)),
                    Material.GRINDSTONE, java.util.List.of(
                            new StationIngredient(m -> m == Material.STICK, 2),
                            new StationIngredient(PLANKS, 2),
                            new StationIngredient(STONE_SLAB, 1,
                                    java.util.List.of(new BaseCost(STONE_SOURCE, 3)),
                                    6, Material.STONE_SLAB)),
                    Material.STONECUTTER, java.util.List.of(
                            // Kameník opracuje surový kámen (hlavně dlažbu z těžby)
                            // na řezaný přímo na staveništi, jinak by kamenictví
                            // nikdy nevzniklo – kámen se v progresi nekupí.
                            new StationIngredient(m -> m == Material.STONE, 3,
                                    java.util.List.of(new BaseCost(STONE_SOURCE, 3)),
                                    3, Material.STONE),
                            new StationIngredient(m -> m == Material.IRON_INGOT, 1)),
                    Material.LECTERN, java.util.List.of(
                            new StationIngredient(WOOD_SLAB, 4,
                                    java.util.List.of(new BaseCost(PLANKS, 3)),
                                    6, Material.OAK_SLAB),
                            new StationIngredient(m -> m == Material.BOOKSHELF, 1,
                                    java.util.List.of(new BaseCost(PLANKS, 6),
                                            new BaseCost(m -> m == Material.BOOK, 3)),
                                    1, Material.BOOKSHELF)));

    /**
     * Má bot v batohu suroviny na výrobu stanice dílny na míru? (rychlá brána
     * pro {@code CommunalBuildGoal.utility} – běží na tick vlákně bota).
     * Meziprodukty (dlaždice, knihovna) se počítají i jako odvoditelné z běžných
     * surovin; sdílená surovina (prkna na dlaždice i na knihovnu) se odečítá,
     * aby brána nelhala, když prken stačí jen na jedno.
     *
     * @param snapshot inventářový snímek bota
     * @param station  materiál stanice
     * @return {@code true} pokud stanici lze vyrobit ze současného inventáře
     */
    public static boolean canCraftStation(dev.botalive.core.bot.ServerSideView.Snapshot snapshot,
                                          Material station) {
        var recipe = STATION_RECIPES.get(station);
        if (recipe == null || snapshot == null) {
            return false;
        }
        // Rozpočet po identitě predikátu – sdílená surovina se čerpá napříč recepty.
        Map<java.util.function.Predicate<Material>, Integer> budget = new java.util.IdentityHashMap<>();
        for (StationIngredient ingredient : recipe) {
            int have = budget.computeIfAbsent(ingredient.match(),
                    p -> countSnapshot(snapshot, p));
            int use = Math.min(ingredient.count(), have);
            budget.put(ingredient.match(), have - use);
            int missing = ingredient.count() - use;
            if (missing == 0) {
                continue;
            }
            if (!ingredient.derivable()) {
                return false;
            }
            int batches = (missing + ingredient.yield() - 1) / ingredient.yield();
            for (BaseCost base : ingredient.from()) {
                int need = batches * base.perBatch();
                int stock = budget.computeIfAbsent(base.match(),
                        p -> countSnapshot(snapshot, p));
                if (stock < need) {
                    return false;
                }
                budget.put(base.match(), stock - need);
            }
        }
        return true;
    }

    // ---------------------------------------------------------- výroba plotu

    /** Klacky se domáčknou z prken (2 prkna → 4 klacky), jako meziprodukt stanic. */
    private static final int STICKS_PER_BATCH = 4;
    private static final int PLANKS_PER_STICK_BATCH = 2;
    /** Recept plaňky: 4 prkna + 2 klacky → 3 plaňky; branka: 2 prkna + 4 klacky → 1. */
    private static final int PLANKS_PER_FENCE_BATCH = 4;
    private static final int STICKS_PER_FENCE_BATCH = 2;
    private static final int FENCES_PER_BATCH = 3;
    private static final int PLANKS_PER_GATE = 2;
    private static final int STICKS_PER_GATE = 4;

    /**
     * Má bot prkna (a klacky, případně domáčknutelné z prken) na výrobu daného
     * počtu plaňků a branek? Čistá brána pro utility cíle plotu (běží na tick
     * vlákně bota), stejný vzor jako {@link #canCraftStation}.
     *
     * @param snapshot inventářový snímek bota
     * @param planks   druh prken (musí ho být dost jednoho druhu)
     * @param fences   kolik plaňků
     * @param gates    kolik branek
     * @return {@code true} když na to prkna stačí
     */
    public static boolean canCraftFencing(dev.botalive.core.bot.ServerSideView.Snapshot snapshot,
                                          Material planks, int fences, int gates) {
        if (snapshot == null || planks == null) {
            return false;
        }
        int fenceBatches = (Math.max(0, fences) + FENCES_PER_BATCH - 1) / FENCES_PER_BATCH;
        int planksNeeded = fenceBatches * PLANKS_PER_FENCE_BATCH + Math.max(0, gates) * PLANKS_PER_GATE;
        int sticksNeeded = fenceBatches * STICKS_PER_FENCE_BATCH + Math.max(0, gates) * STICKS_PER_GATE;
        int haveSticks = countSnapshot(snapshot, m -> m == Material.STICK);
        int stickBatches = (Math.max(0, sticksNeeded - haveSticks) + STICKS_PER_BATCH - 1) / STICKS_PER_BATCH;
        int planksForSticks = stickBatches * PLANKS_PER_STICK_BATCH;
        Material p = planks;
        return countSnapshot(snapshot, m -> m == p) >= planksNeeded + planksForSticks;
    }

    /**
     * Druh prken, kterých má bot v batohu nejvíc (plot se staví z jednoho druhu,
     * aby recept sedl) – jinak {@code null}.
     *
     * @param snapshot inventářový snímek bota
     * @return převažující {@code *_PLANKS}, nebo {@code null} když žádné nemá
     */
    public static Material dominantPlanks(dev.botalive.core.bot.ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<Material, Integer> tally = new java.util.HashMap<>();
        tallyPlanks(tally, snapshot.hotbar(), snapshot.hotbarCounts());
        tallyPlanks(tally, snapshot.mainInventory(), snapshot.mainCounts());
        Material best = null;
        int bestCount = 0;
        for (Map.Entry<Material, Integer> entry : tally.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private static void tallyPlanks(Map<Material, Integer> tally, Material[] items, int[] counts) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && PLANKS.test(items[i])) {
                int count = counts != null && i < counts.length ? counts[i] : 1;
                tally.merge(items[i], count, Integer::sum);
            }
        }
    }

    /**
     * Vyrobí stanici dílny na míru (spotřebuje suroviny, přidá blok) – jen
     * pro stanice mimo běžnou progresi. Chybějící meziprodukty (kamenná
     * dlaždice, knihovna) nejdřív domáčkne z běžných surovin. Autoritativně na
     * vlákně entity, jako {@code AnvilService}. Bez receptu / bez surovin vrací
     * {@code false}.
     *
     * @param botId   UUID bota
     * @param station materiál stanice
     * @return future s výsledkem (true = stanice v inventáři)
     */
    @Override
    public CompletableFuture<Boolean> craftStation(UUID botId, Material station) {
        var recipe = STATION_RECIPES.get(station);
        Player player = Bukkit.getPlayer(botId);
        if (recipe == null || player == null) {
            return CompletableFuture.completedFuture(false);
        }
        return bridge.callForEntity(player, () -> {
            PlayerInventory inventory = player.getInventory();
            // Nejdřív dorob meziprodukty, které stavitel nemá (dlaždice, knihovna).
            for (StationIngredient ingredient : recipe) {
                deriveIfNeeded(player, inventory, ingredient);
            }
            for (StationIngredient ingredient : recipe) {
                if (countMatching(inventory, ingredient.match()) < ingredient.count()) {
                    return false; // suroviny mezitím ubyly / meziprodukt nešel dorobit
                }
            }
            for (StationIngredient ingredient : recipe) {
                removeMatching(inventory, ingredient.match(), ingredient.count());
            }
            addOrDrop(player, new ItemStack(station, 1));
            return true;
        }).exceptionally(t -> false);
    }

    /**
     * Vyrobí plaňky plotu a branky z prken (klacky domáčkne z prken) –
     * spotřeba surovin + vložení výsledku, jako {@link #craftStation}, jen s
     * pevným receptem plotu. Vyžaduje ponk (3-široký recept). Neúspěch (málo
     * prken, chybí ponk) vrací {@code false}; cíl to zkusí menší dávkou příště.
     *
     * @param botId  UUID bota
     * @param planks druh prken
     * @param fence  materiál plaňky
     * @param gate   materiál branky
     * @param fences kolik plaňků vyrobit
     * @param gates  kolik branek vyrobit
     * @return future s výsledkem (true = materiál v inventáři)
     */
    @Override
    public CompletableFuture<Boolean> craftFencing(UUID botId, Material planks, Material fence,
                                                   Material gate, int fences, int gates) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null || planks == null || fence == null || gate == null) {
            return CompletableFuture.completedFuture(false);
        }
        int fenceCount = Math.max(0, fences);
        int gateCount = Math.max(0, gates);
        return bridge.callForEntity(player, () -> {
            PlayerInventory inventory = player.getInventory();
            if (!inventory.contains(Material.CRAFTING_TABLE)) {
                return false; // plaňka i branka jsou 3-široké recepty (ponk)
            }
            int fenceBatches = (fenceCount + FENCES_PER_BATCH - 1) / FENCES_PER_BATCH;
            int planksNeeded = fenceBatches * PLANKS_PER_FENCE_BATCH + gateCount * PLANKS_PER_GATE;
            int sticksNeeded = fenceBatches * STICKS_PER_FENCE_BATCH + gateCount * STICKS_PER_GATE;
            int missingSticks = Math.max(0, sticksNeeded - count(inventory, Material.STICK));
            int stickBatches = (missingSticks + STICKS_PER_BATCH - 1) / STICKS_PER_BATCH;
            int planksForSticks = stickBatches * PLANKS_PER_STICK_BATCH;
            if (count(inventory, planks) < planksNeeded + planksForSticks) {
                return false; // prkna mezitím ubyla
            }
            if (stickBatches > 0) {
                removeMatching(inventory, m -> m == planks, planksForSticks);
                addOrDrop(player, new ItemStack(Material.STICK, stickBatches * STICKS_PER_BATCH));
            }
            removeMatching(inventory, m -> m == planks, planksNeeded);
            removeMatching(inventory, m -> m == Material.STICK, sticksNeeded);
            if (fenceBatches > 0) {
                addOrDrop(player, new ItemStack(fence, fenceBatches * FENCES_PER_BATCH));
            }
            if (gateCount > 0) {
                addOrDrop(player, new ItemStack(gate, gateCount));
            }
            return true;
        }).exceptionally(t -> false);
    }

    /**
     * Zpracuje klády na prkna (1 kláda → 4 prkna dle dřeva). Mezikrok, když si
     * bot na opravu plotu nasekal dřevo. Spotřeba + vložení, jako {@code craftStation}.
     *
     * @param botId   UUID bota
     * @param maxLogs kolik klád nejvýš zpracovat
     * @return future s výsledkem (true = aspoň jedna kláda zpracována)
     */
    @Override
    public CompletableFuture<Boolean> craftPlanks(UUID botId, int maxLogs) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null || maxLogs <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return bridge.callForEntity(player, () -> {
            PlayerInventory inventory = player.getInventory();
            int done = 0;
            ItemStack[] contents = inventory.getStorageContents();
            for (int i = 0; i < contents.length && done < maxLogs; i++) {
                ItemStack item = contents[i];
                if (item == null || !Tag.LOGS.isTagged(item.getType())) {
                    continue;
                }
                int take = Math.min(item.getAmount(), maxLogs - done);
                if (take >= item.getAmount()) {
                    inventory.setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - take);
                    inventory.setItem(i, item);
                }
                addOrDrop(player, new ItemStack(planksFromLog(item.getType()), take * 4));
                done += take;
            }
            return done > 0;
        }).exceptionally(t -> false);
    }

    /** Prkna odpovídající kládě ({@code OAK_LOG→OAK_PLANKS}); neznámé → dub. */
    static Material planksFromLog(Material log) {
        String name = log.name().replace("STRIPPED_", "")
                .replace("_LOG", "_PLANKS").replace("_WOOD", "_PLANKS")
                .replace("_STEM", "_PLANKS").replace("_HYPHAE", "_PLANKS");
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Material.OAK_PLANKS;
        }
    }

    /** Domáčkne chybějící meziprodukt z běžných surovin (dokud jsou a je ho třeba). */
    private static void deriveIfNeeded(Player player, PlayerInventory inventory,
                                       StationIngredient ingredient) {
        if (!ingredient.derivable()) {
            return;
        }
        while (countMatching(inventory, ingredient.match()) < ingredient.count()) {
            for (BaseCost base : ingredient.from()) {
                if (countMatching(inventory, base.match()) < base.perBatch()) {
                    return; // došla surovina – stanice se nedorobí, craftStation vrátí false
                }
            }
            for (BaseCost base : ingredient.from()) {
                removeMatching(inventory, base.match(), base.perBatch());
            }
            addOrDrop(player, new ItemStack(ingredient.produces(), ingredient.yield()));
        }
    }

    /** Vloží do inventáře, přebytek upustí (jsme na vlákně entity). */
    private static void addOrDrop(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    private static int countSnapshot(dev.botalive.core.bot.ServerSideView.Snapshot snapshot,
                                     java.util.function.Predicate<Material> match) {
        int total = 0;
        for (int i = 0; i < snapshot.hotbar().length; i++) {
            if (snapshot.hotbar()[i] != null && match.test(snapshot.hotbar()[i])) {
                total += snapshot.hotbarCounts()[i];
            }
        }
        for (int i = 0; i < snapshot.mainInventory().length; i++) {
            if (snapshot.mainInventory()[i] != null && match.test(snapshot.mainInventory()[i])) {
                total += snapshot.mainCounts()[i];
            }
        }
        return total;
    }

    /** Odebere {@code count} kusů vyhovujícího materiálu z inventáře. */
    private static void removeMatching(PlayerInventory inventory,
                                       java.util.function.Predicate<Material> match, int count) {
        int remaining = count;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !match.test(item.getType())) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            remaining -= take;
            if (take >= item.getAmount()) {
                inventory.setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
                inventory.setItem(i, item);
            }
        }
    }

    // ------------------------------------------------------------- pomocníci

    private static int count(PlayerInventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static int countMatching(PlayerInventory inventory,
                                     java.util.function.Predicate<Material> predicate) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && predicate.test(item.getType())) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /**
     * Nástroj/zbroj těsně před rozbitím (&gt;85 % opotřebení) se pro plánování
     * nepočítá – bot si tak přirozeně vyrobí náhradu, než mu praskne v ruce.
     */
    private static boolean nearlyBroken(ItemStack item) {
        int max = item.getType().getMaxDurability();
        if (max <= 0) {
            return false;
        }
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable)) {
            return false;
        }
        return damageable.getDamage() >= max * 0.85;
    }

    private static Material firstMatching(PlayerInventory inventory,
                                          java.util.function.Predicate<Material> predicate) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && predicate.test(item.getType())) {
                return item.getType();
            }
        }
        return null;
    }

    private static boolean containsTool(PlayerInventory inventory, String suffix) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType().name().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAxe(PlayerInventory inventory) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType().name().endsWith("_AXE")
                    && !item.getType().name().endsWith("_PICKAXE")) {
                return true;
            }
        }
        return false;
    }

    private static boolean betterPickaxe(PlayerInventory inventory) {
        return inventory.contains(Material.IRON_PICKAXE)
                || inventory.contains(Material.DIAMOND_PICKAXE)
                || inventory.contains(Material.NETHERITE_PICKAXE);
    }
}
