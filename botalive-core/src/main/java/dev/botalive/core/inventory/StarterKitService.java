package dev.botalive.core.inventory;

import dev.botalive.api.role.BotRole;
import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Startovní výbava bota podle jeho profese.
 *
 * <p>Bez ní se boti na čerstvém světě zaseknou ve smyčce přežití: rodí se
 * s prázdným inventářem, {@code BuildShelterGoal} vyžaduje materiál, který
 * nemají, a v noci je mobové zabíjejí dokola. V měření to vypadalo tak, že
 * 85 % botů mělo cíl {@code combat}/{@code survive}, za 25 minut padlo přes
 * 70 smrtí a nevznikla jediná osada.</p>
 *
 * <p>Kit se dává <b>jen při prvním spawnu</b> (příznak {@code ba_bots.kit_given}).
 * Při respawnu po smrti se neopakuje – jinak by smrt nic nestála a survival
 * vrstva by ztratila smysl.</p>
 *
 * <p>Role dostává navíc přesně to, co jí dnes blokuje vlastní cíl – rybář prut
 * (bez něj {@code FishGoal} nikdy neprošel vstupní branou), obchodník smaragdy,
 * farmář osivo.</p>
 */
public final class StarterKitService {

    private static final Logger LOG = LoggerFactory.getLogger(StarterKitService.class);

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na hlavní vlákno (inventář se smí měnit jen tam)
     */
    public StarterKitService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Předá botovi startovní výbavu.
     *
     * @param botId UUID bota (musí být online)
     * @param role  profese bota
     */
    public void give(UUID botId, BotRole role) {
        Map<Material, Integer> kit = contents(role);
        bridge.runGlobal(() -> {
            Player player = Bukkit.getPlayer(botId);
            if (player == null) {
                return;
            }
            kit.forEach((material, count) ->
                    player.getInventory().addItem(new ItemStack(material, count)));
            LOG.info("[{}] Startovní kit ({}) předán: {} druhů předmětů",
                    player.getName(), role.displayName(), kit.size());
        });
    }

    /**
     * Obsah kitu pro danou roli (základ + profesní přídavek).
     *
     * <p>Vrací materiály a počty, ne {@code ItemStack} – ty potřebují
     * inicializovaný Bukkit registr, takže by nešly sestavit v unit testu.</p>
     *
     * @param role profese
     * @return materiál → počet kusů, v pořadí vkládání
     */
    static Map<Material, Integer> contents(BotRole role) {
        Map<Material, Integer> kit = new LinkedHashMap<>();
        kit.put(Material.STONE_SWORD, 1);
        kit.put(Material.STONE_PICKAXE, 1);
        kit.put(Material.STONE_AXE, 1);
        kit.put(Material.BREAD, 16);
        // 64 dlažebek: nouzový úkryt na první noc a základ na dům
        kit.put(Material.COBBLESTONE, 64);
        kit.put(Material.TORCH, 16);
        kit.put(Material.CRAFTING_TABLE, 1);
        switch (role) {
            case BUILDER -> {
                // Dům chce 80–153 bloků; stavitel má začít hned, ne až po těžbě.
                kit.merge(Material.COBBLESTONE, 64, Integer::sum);
                kit.put(Material.OAK_PLANKS, 32);
            }
            case MINER -> {
                kit.put(Material.IRON_PICKAXE, 1);
                kit.merge(Material.TORCH, 32, Integer::sum);
            }
            case LUMBERJACK -> {
                kit.put(Material.IRON_AXE, 1);
                kit.put(Material.OAK_LOG, 16);
            }
            case HUNTER -> {
                kit.put(Material.BOW, 1);
                kit.put(Material.ARROW, 32);
            }
            case BLACKSMITH -> {
                kit.put(Material.FURNACE, 1);
                kit.put(Material.COAL, 16);
                kit.put(Material.IRON_INGOT, 8);
            }
            case ENCHANTER -> {
                kit.put(Material.LAPIS_LAZULI, 16);
                kit.put(Material.BOOK, 4);
            }
            case TRADER -> {
                kit.put(Material.EMERALD, 16);
                kit.put(Material.CHEST, 1);
            }
            case FISHERMAN -> kit.put(Material.FISHING_ROD, 1);
            case FARMER -> {
                kit.put(Material.STONE_HOE, 1);
                kit.put(Material.WHEAT_SEEDS, 16);
                kit.put(Material.CARROT, 4);
                kit.put(Material.POTATO, 4);
            }
            case ALCHEMIST -> {
                // Stojan i bradavici si musí obstarat sám (Nether) – lahve
                // a sklo jsou jen startovní kapitál, ne přeskočení řetězu.
                kit.put(Material.GLASS_BOTTLE, 3);
                kit.put(Material.GLASS, 8);
                kit.put(Material.SUGAR, 8);
            }
            case GUARDIAN -> {
                kit.put(Material.SHIELD, 1);
                kit.put(Material.LEATHER_CHESTPLATE, 1);
                kit.put(Material.LEATHER_HELMET, 1);
            }
            case SCOUT -> {
                kit.put(Material.OAK_BOAT, 1);
                kit.merge(Material.BREAD, 16, Integer::sum);
                kit.merge(Material.TORCH, 16, Integer::sum);
            }
            case BEASTMASTER -> {
                kit.put(Material.WHEAT, 16);
                kit.put(Material.BONE, 8);
                kit.put(Material.LEAD, 2);
            }
            case THIEF -> {
                // Krádeže potřebují rychlý únik a otevírání truhel, ne sílu.
                kit.put(Material.IRON_PICKAXE, 1);
                kit.merge(Material.BREAD, 8, Integer::sum);
            }
            case DIPLOMAT -> {
                // Dary otevírají dveře: vyjednavač rozdává.
                kit.merge(Material.BREAD, 16, Integer::sum);
                kit.put(Material.EMERALD, 8);
                kit.put(Material.COOKED_BEEF, 8);
            }
            case ADVENTURER -> {
                kit.put(Material.FLINT_AND_STEEL, 1);
                kit.put(Material.BOW, 1);
                kit.put(Material.ARROW, 16);
                kit.merge(Material.BREAD, 16, Integer::sum);
            }
            case COURIER -> {
                kit.put(Material.CHEST, 1);
                kit.merge(Material.BREAD, 8, Integer::sum);
            }
            case COOK -> {
                kit.put(Material.FURNACE, 1);
                kit.merge(Material.COAL, 16, Integer::sum);
                kit.put(Material.BEEF, 8);
            }
            case NONE -> {
                // univerzál si vystačí se základem
            }
            default -> {
                // nová role bez vlastního kitu – základ stačí
            }
        }
        return kit;
    }
}
