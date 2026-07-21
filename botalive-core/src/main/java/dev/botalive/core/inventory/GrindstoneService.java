package dev.botalive.core.inventory;

import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Broušení u brusného kamene – oprava nářadí <b>bez materiálu</b>.
 *
 * <p>Vanilla mechanika: dva opotřebené kusy téhož druhu se u grindstonu spojí
 * do jednoho s vyšší trvanlivostí (součet zbytků + 5 % bonus). Na rozdíl od
 * kovadliny ({@link AnvilService}) nestojí surovinu ani XP – proto je to
 * záchrana pro bota, kterému dochází nářadí a nemá čím opravovat: dvě
 * polorozbité kamenné krumpáče udělají jeden funkční, a bot může dál kopat,
 * místo aby uvázl na cíli.</p>
 *
 * <p>Grindstone v vanille <b>maže očarování</b>, takže služba záměrně spojuje
 * jen <b>neočarované</b> kusy – enchantovanou výbavu (cennou) nikdy nezničí;
 * ta se opravuje u kovadliny. Server-side úprava inventáře na vlákně regionu
 * (stejný precedent jako {@code AnvilService}).</p>
 */
public final class GrindstoneService {

    /** O kolik navíc (podíl max trvanlivosti) grindstone při spojení přidá. */
    private static final double COMBINE_BONUS = 0.05;

    /** Výsledek broušení. */
    public record RepairReport(Material repaired) {

        /** Nic se neopravilo. */
        public static final RepairReport NONE = new RepairReport(null);

        /** @return {@code true} pokud broušení proběhlo */
        public boolean succeeded() {
            return repaired != null;
        }
    }

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na vlákna regionů
     */
    public GrindstoneService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Spojí dva neočarované opotřebené kusy téhož druhu do jednoho opraveného.
     *
     * @param botId          UUID bota (musí stát u grindstonu)
     * @param worldName      svět grindstonu
     * @param grindstonePos  pozice grindstonu
     * @return future s výsledkem (NONE = nebyl vhodný pár / nedostupné)
     */
    public CompletableFuture<RepairReport> repair(UUID botId, String worldName,
                                                  BlockPos grindstonePos) {
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(botId);
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(RepairReport.NONE);
        }
        Location location = new Location(world, grindstonePos.x(), grindstonePos.y(),
                grindstonePos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6) {
                return RepairReport.NONE;
            }
            if (world.getBlockAt(grindstonePos.x(), grindstonePos.y(), grindstonePos.z())
                    .getType() != Material.GRINDSTONE) {
                return RepairReport.NONE;
            }
            Inventory inventory = player.getInventory();
            // Najít druh s dvěma neočarovanými opotřebenými kusy; z párů vzít
            // ten s největším celkovým poškozením (nejvíc se opravou získá).
            int slotA = -1;
            int slotB = -1;
            int bestDamage = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack first = repairableUnenchanted(inventory.getItem(i));
                if (first == null) {
                    continue;
                }
                for (int j = i + 1; j < 36; j++) {
                    ItemStack second = inventory.getItem(j);
                    if (second == null || second.getType() != first.getType()
                            || repairableUnenchanted(second) == null) {
                        continue;
                    }
                    int totalDamage = damage(first) + damage(second);
                    if (totalDamage > bestDamage) {
                        bestDamage = totalDamage;
                        slotA = i;
                        slotB = j;
                    }
                    break; // pár k tomuhle kusu nalezen
                }
            }
            if (slotA < 0) {
                return RepairReport.NONE;
            }
            ItemStack a = inventory.getItem(slotA);
            ItemStack b = inventory.getItem(slotB);
            int max = a.getType().getMaxDurability();
            int remaining = (max - damage(a)) + (max - damage(b))
                    + (int) Math.round(max * COMBINE_BONUS);
            int newDamage = Math.max(0, max - Math.min(max, remaining));
            ItemStack result = new ItemStack(a.getType(), 1);
            if (result.getItemMeta() instanceof Damageable meta) {
                meta.setDamage(newDamage);
                result.setItemMeta(meta);
            }
            inventory.setItem(slotA, null);
            inventory.setItem(slotB, null);
            var leftover = inventory.addItem(result);
            if (!leftover.isEmpty()) {
                // Nemělo by nastat (uvolnili jsme dva sloty) – vrátit původní.
                inventory.setItem(slotA, a);
                inventory.setItem(slotB, b);
                return RepairReport.NONE;
            }
            world.playSound(location, Sound.BLOCK_GRINDSTONE_USE, 1.0f, 1.0f);
            return new RepairReport(a.getType());
        }).exceptionally(t -> RepairReport.NONE);
    }

    /** Kus vrátí, jen když je to opotřebený neočarovaný nástroj/zbraň/brnění. */
    private static ItemStack repairableUnenchanted(ItemStack item) {
        if (item == null || item.getType().getMaxDurability() <= 0
                || !item.getEnchantments().isEmpty()
                || !(item.getItemMeta() instanceof Damageable d) || d.getDamage() <= 0) {
            return null;
        }
        return item;
    }

    private static int damage(ItemStack item) {
        return item.getItemMeta() instanceof Damageable d ? d.getDamage() : 0;
    }
}
