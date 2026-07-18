package dev.botalive.core.bot;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side pohled na bota – periodický snapshot jeho Bukkit {@link Player}a.
 *
 * <p>Bot je skutečný klient, takže na serveru existuje jeho plnohodnotná
 * hráčská entita. Toho využíváme jako <b>autoritativní zdroj</b> pro data,
 * která by se z paketů rekonstruovala složitě a křehce (mapování síťových
 * item ID na materiály, přesné doby těžby bloků, efekty). Snapshot se pořizuje
 * na vlákně entity (Folia-safe) a čte se z AI vlákna bota.</p>
 *
 * <p>Interakce (kopání, útok, jídlo) naopak vždy jdou přes pakety – server je
 * validuje jako u člověka. Tato třída je jen „oči", ne „ruce".</p>
 */
public final class ServerSideView {

    /**
     * Nemutabilní snapshot server-side stavu bota.
     *
     * @param location    poloha serverové entity
     * @param hotbar      materiály v hotbaru (9 slotů; null = prázdný)
     * @param hotbarCounts počty kusů v hotbaru
     * @param mainInventory materiály hlavního inventáře (sloty 9–35)
     * @param mainCounts  počty kusů hlavního inventáře (index odpovídá
     *                    {@code mainInventory}; null u ručně sestavených
     *                    snapshotů = konzervativní odhad)
     * @param itemVariants varianty itemů podle metadat (slot 0–35 → varianta:
     *                    typ lektvaru, enchant knihy; viz
     *                    {@code ItemVariants}); null = bez informace
     * @param offhand     materiál v druhé ruce (null = prázdná)
     * @param health      zdraví
     * @param foodLevel   najedenost
     * @param expLevel    úroveň zkušeností
     * @param onFire      bot hoří
     * @param inLava      bot je v lávě
     * @param sleeping    bot spí v posteli
     * @param worldTime   herní čas světa (0–23999)
     * @param takenAtMs   čas pořízení snapshotu
     */
    public record Snapshot(
            Location location,
            Material[] hotbar,
            int[] hotbarCounts,
            Material[] mainInventory,
            int[] mainCounts,
            java.util.Map<Integer, String> itemVariants,
            Material offhand,
            Material[] armor,
            Material damagedTool,
            int damagedToolPercent,
            double health,
            int foodLevel,
            int expLevel,
            boolean onFire,
            boolean inLava,
            boolean sleeping,
            long worldTime,
            long takenAtMs
    ) {

        /**
         * Najde hotbar slot podle predikátu materiálu.
         *
         * @param predicate podmínka na materiál
         * @return index 0–8, nebo -1
         */
        public int findHotbarSlot(java.util.function.Predicate<Material> predicate) {
            for (int i = 0; i < hotbar.length; i++) {
                if (hotbar[i] != null && predicate.test(hotbar[i])) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * @param predicate podmínka na materiál
         * @return {@code true} pokud bot má vyhovující item kdekoli v inventáři
         */
        public boolean hasItem(java.util.function.Predicate<Material> predicate) {
            if (findHotbarSlot(predicate) >= 0) {
                return true;
            }
            for (Material material : mainInventory) {
                if (material != null && predicate.test(material)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final UUID botId;
    private final MainThreadBridge bridge;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();

    /**
     * @param botId  UUID bota (= UUID serverového hráče)
     * @param bridge most na vlákno entity
     */
    public ServerSideView(UUID botId, MainThreadBridge bridge) {
        this.botId = botId;
        this.bridge = bridge;
    }

    /**
     * @return poslední snapshot, nebo {@code null} pokud ještě žádný není
     */
    public Snapshot latest() {
        return latest.get();
    }

    /**
     * Vloží snapshot sestavený jinde – paketový režim (cizí server) sem
     * odevzdává klientský snapshot ({@code PacketPlayerView}), takže
     * konzumenti čtou vždy stejným rozhraním.
     *
     * @param snapshot snapshot z klientských dat
     */
    public void offer(Snapshot snapshot) {
        if (snapshot != null) {
            latest.set(snapshot);
        }
    }

    /**
     * Asynchronně obnoví snapshot (max jedna obnova současně).
     */
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            refreshing.set(false);
            return;
        }
        bridge.callForEntity(player, () -> capture(player))
                .whenComplete((snapshot, error) -> {
                    if (snapshot != null) {
                        latest.set(snapshot);
                    }
                    refreshing.set(false);
                });
    }

    /**
     * Varianta itemu z metadat: typ lektvaru (lektvary, obalené šípy) nebo
     * první uložený enchant knihy ({@code klíč:úroveň}). Ostatní itemy
     * variantu nemají.
     */
    private static void putVariant(java.util.Map<Integer, String> variants, int slot,
                                   ItemStack item) {
        var meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.PotionMeta potion) {
            var type = potion.getBasePotionType();
            if (type != null) {
                variants.put(slot, type.name().toLowerCase(java.util.Locale.ROOT));
            }
            return;
        }
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta book
                && !book.getStoredEnchants().isEmpty()) {
            var entry = book.getStoredEnchants().entrySet().iterator().next();
            variants.put(slot, entry.getKey().getKey().getKey() + ":" + entry.getValue());
        }
    }

    /** Pořídí snapshot – běží na vlákně entity. */
    private Snapshot capture(Player player) {
        var inventory = player.getInventory();
        java.util.Map<Integer, String> variants = new java.util.HashMap<>();
        Material[] hotbar = new Material[9];
        int[] hotbarCounts = new int[9];
        for (int i = 0; i < 9; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                hotbar[i] = item.getType();
                hotbarCounts[i] = item.getAmount();
                putVariant(variants, i, item);
            }
        }
        Material[] main = new Material[27];
        int[] mainCounts = new int[27];
        for (int i = 0; i < 27; i++) {
            ItemStack item = inventory.getItem(9 + i);
            if (item != null && !item.getType().isAir()) {
                main[i] = item.getType();
                mainCounts[i] = item.getAmount();
                putVariant(variants, 9 + i, item);
            }
        }
        Material[] armor = new Material[4];
        ItemStack[] worn = inventory.getArmorContents(); // boty, kalhoty, prsník, helma
        for (int i = 0; i < 4 && i < worn.length; i++) {
            if (worn[i] != null && !worn[i].getType().isAir()) {
                armor[i] = worn[i].getType();
            }
        }
        // Nejopotřebenější nástroj (pro rozhodování o opravě u kovadliny).
        Material damagedTool = null;
        int damagedPercent = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null) {
                continue;
            }
            int max = item.getType().getMaxDurability();
            if (max <= 0
                    || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d)) {
                continue;
            }
            int percent = d.getDamage() * 100 / max;
            if (percent > damagedPercent) {
                damagedPercent = percent;
                damagedTool = item.getType();
            }
        }
        Material feet = player.getLocation().getBlock().getType();
        ItemStack offhandItem = inventory.getItemInOffHand();
        Material offhand = offhandItem.getType().isAir() ? null : offhandItem.getType();
        return new Snapshot(
                player.getLocation().clone(),
                hotbar, hotbarCounts, main, mainCounts, variants, offhand, armor,
                damagedTool, damagedPercent,
                player.getHealth(),
                player.getFoodLevel(),
                player.getLevel(),
                player.getFireTicks() > 0,
                feet == Material.LAVA,
                player.isSleeping(),
                player.getWorld().getTime(),
                System.currentTimeMillis());
    }
}
