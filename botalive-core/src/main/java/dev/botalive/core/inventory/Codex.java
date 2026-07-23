package dev.botalive.core.inventory;

import dev.botalive.core.inventory.Items.ItemCategory;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Codex – botní „povědomí": dotazovatelná <b>databáze všech vanilla materiálů
 * a itemů</b> postavená nad katalogy {@link Materials} a {@link Items}.
 *
 * <p>Zatímco katalogy odpovídají na jednotlivé otázky („je tohle ruda?"),
 * Codex je nad nimi <b>agregační vrstva</b>: zná kategorii i strukturovaná
 * fakta libovolného materiálu, umí ho lidsky popsat (pro chat/intent) a umí
 * vyjmenovat vše v dané kategorii (index přes {@code Material.values()}). Je
 * statický a všudypřístupný, takže „ho mají" všichni boti napříč cíli.</p>
 *
 * <p>Klasifikace je registry-free (viz katalogy), takže i index se postaví
 * bez běžícího serveru – jediná výjimka je poživatelnost ({@link
 * InventoryHelper#isFood}), která bez serveru padá na konzervativní fallback.</p>
 */
public final class Codex {

    private Codex() {
    }

    /**
     * Strukturovaná fakta o materiálu odvozená z katalogů – jedno místo, kde
     * bot dostane „všechno, co o tom ví".
     *
     * @param material         materiál
     * @param category         hlavní kategorie ({@link Items#primaryCategory})
     * @param ore              je ruda
     * @param oreValue         ekonomická hodnota rudy (nebo {@code null})
     * @param requiredPickTier tier krumpáče na vytěžení (0 = nerelevantní)
     * @param valuable         cennost/měna
     * @param bankable         ukládá se do truhly nad rezervu
     * @param bankReserve      pracovní rezerva (kolik si nechat)
     * @param buildingBlock    plný stavební kvádr
     * @param fuel             palivo do pece
     * @param food             jídlo
     */
    public record Facts(Material material, ItemCategory category, boolean ore, Double oreValue,
                        int requiredPickTier, boolean valuable, boolean bankable, int bankReserve,
                        boolean buildingBlock, boolean fuel, boolean food) {
    }

    /** @param material materiál @return hlavní kategorie ({@link Items#primaryCategory}) */
    public static ItemCategory categoryOf(Material material) {
        return Items.primaryCategory(material);
    }

    /**
     * Všechna fakta o materiálu (kompletní karta z katalogů).
     *
     * @param material materiál
     * @return strukturovaná fakta
     */
    public static Facts facts(Material material) {
        boolean ore = Materials.isOre(material);
        return new Facts(material, categoryOf(material), ore,
                ore ? Materials.oreValue(material) : null,
                ore ? Materials.requiredPickTier(material) : 0,
                Items.isValuable(material), Materials.isBankable(material),
                Materials.bankReserve(material), Materials.isBuildingBlock(material),
                Items.isFuel(material), Items.isFood(material));
    }

    /**
     * Lidsky čitelný popis materiálu (pro botní intent/chat a diagnostiku) –
     * kategorie a nejvýraznější fakta.
     *
     * @param material materiál ({@code null} = „nic")
     * @return český popis, např. „iron_ore: ruda (tier 3, hodnota 5)"
     */
    public static String describe(Material material) {
        if (material == null) {
            return "nic";
        }
        Set<String> tags = new LinkedHashSet<>();
        tags.add(categoryLabel(categoryOf(material)));
        if (Materials.isOre(material)) {
            Double value = Materials.oreValue(material);
            String ore = "ruda (tier " + Materials.requiredPickTier(material);
            if (value != null) {
                ore += ", hodnota " + trim(value);
            }
            tags.add(ore + ")");
        }
        if (Materials.isBankable(material)) {
            tags.add("bankuje se nad " + Materials.bankReserve(material) + " ks");
        } else if (Items.isValuable(material)) {
            tags.add("cennost");
        }
        if (Materials.isBuildingBlock(material)) {
            tags.add("stavební blok");
        }
        if (Items.isFuel(material)) {
            tags.add("palivo");
        }
        if (Items.isFood(material)) {
            tags.add("jídlo");
        }
        if (Materials.isGravityBlock(material)) {
            tags.add("padavý");
        }
        return material.name().toLowerCase(Locale.ROOT) + ": " + String.join(", ", tags);
    }

    // ==================================================================
    // Index přes celou vanillu (líně postavený, neměnný)
    // ==================================================================

    private static volatile Map<ItemCategory, List<Material>> index;

    private static Map<ItemCategory, List<Material>> index() {
        Map<ItemCategory, List<Material>> local = index;
        if (local != null) {
            return local;
        }
        Map<ItemCategory, List<Material>> built = new EnumMap<>(ItemCategory.class);
        for (ItemCategory category : ItemCategory.values()) {
            built.put(category, new ArrayList<>());
        }
        for (Material material : Material.values()) {
            if (material.name().startsWith("LEGACY_")) {
                continue; // legacy 1.12 mapování se do databáze nepočítá
            }
            built.get(categoryOf(material)).add(material);
        }
        built.replaceAll((category, list) -> List.copyOf(list));
        index = built;
        return built;
    }

    /**
     * Všechny (moderní) materiály v dané kategorii.
     *
     * @param category kategorie
     * @return neměnný seznam materiálů
     */
    public static List<Material> inCategory(ItemCategory category) {
        return index().getOrDefault(category, List.of());
    }

    /** @param category kategorie @return počet materiálů v kategorii */
    public static int count(ItemCategory category) {
        return inCategory(category).size();
    }

    /** @return histogram kategorie → počet materiálů (celá vanilla). */
    public static Map<ItemCategory, Integer> histogram() {
        Map<ItemCategory, Integer> histogram = new EnumMap<>(ItemCategory.class);
        index().forEach((category, list) -> histogram.put(category, list.size()));
        return histogram;
    }

    // ==================================================================

    /** Český název kategorie pro popis. */
    private static String categoryLabel(ItemCategory category) {
        return switch (category) {
            case TOOL -> "nástroj";
            case WEAPON -> "zbraň";
            case AMMO -> "střelivo";
            case ARMOR -> "brnění";
            case FOOD -> "jídlo";
            case POTION -> "lektvar";
            case BREWING -> "surovina vaření";
            case SEED -> "osivo";
            case TRANSPORT -> "doprava";
            case UTILITY -> "utility";
            case DYE -> "barvivo";
            case FUEL -> "palivo";
            case VALUABLE -> "cennost";
            case MOB_DROP -> "drop moba";
            case BLOCK -> "blok";
            case OTHER -> "ostatní";
        };
    }

    /** Hodnota bez zbytečného „.0". */
    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value) : String.valueOf(value);
    }
}
