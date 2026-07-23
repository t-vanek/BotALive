package dev.botalive.core.inventory;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Znalostní vrstva nad katalogy – <b>afordance</b> materiálu: k čemu slouží,
 * co s ním bot smí/má a nesmí/nemá dělat, jak ho vytvořit, vylepšit a opravit.
 *
 * <p>Zatímco {@link Materials}/{@link Items} odpovídají „co to je" a {@link
 * Codex} agreguje fakta, tady je „co si s tím počít". Znalost je odvozená
 * <b>pravidly</b> z kategorií a katalogových faktů (ne ruční tabulka přes celou
 * vanillu), takže pokryje i materiály, na které nikdo nemyslel, a zůstane
 * konzistentní. Výstup je lidsky čitelný (česky) – bot ho může vyslovit
 * (intent/chat) a člověk vidět přes {@code /botalive codex}.</p>
 *
 * <p>Registry-free (stojí na katalozích), takže se testuje bez serveru.</p>
 */
public final class MaterialGuide {

    private MaterialGuide() {
    }

    /**
     * Návod k materiálu.
     *
     * @param material materiál
     * @param purpose  k čemu slouží (hlavní účel)
     * @param can      co s ním bot smí / má dělat
     * @param cannot   co nesmí / nemá dělat (pozor)
     * @param craft    jak ho vytvořit (nebo {@code null})
     * @param upgrade  jak ho vylepšit (nebo {@code null})
     * @param repair   jak ho opravit (nebo {@code null})
     */
    public record Guidance(Material material, String purpose, List<String> can,
                           List<String> cannot, String craft, String upgrade, String repair) {
    }

    /**
     * Sestaví návod k materiálu z katalogových pravidel.
     *
     * @param material materiál ({@code null} = prázdný návod)
     * @return návod
     */
    public static Guidance of(Material material) {
        if (material == null) {
            return new Guidance(null, "nic", List.of(), List.of(), null, null, null);
        }
        return new Guidance(material, purpose(material), can(material), cannot(material),
                craft(material), upgrade(material), repair(material));
    }

    /**
     * Návod jako řádky „štítek: text" pro zobrazení ({@code /botalive codex}) i
     * botní intent.
     *
     * @param material materiál
     * @return neprázdné řádky návodu
     */
    public static List<String> lines(Material material) {
        Guidance g = of(material);
        List<String> out = new ArrayList<>();
        out.add("k čemu: " + g.purpose());
        if (!g.can().isEmpty()) {
            out.add("může: " + String.join("; ", g.can()));
        }
        if (!g.cannot().isEmpty()) {
            out.add("pozor: " + String.join("; ", g.cannot()));
        }
        if (g.craft() != null) {
            out.add("tvorba: " + g.craft());
        }
        if (g.upgrade() != null) {
            out.add("vylepšení: " + g.upgrade());
        }
        if (g.repair() != null) {
            out.add("oprava: " + g.repair());
        }
        return out;
    }

    // ==================================================================
    // K čemu slouží
    // ==================================================================

    private static String purpose(Material m) {
        if (Items.isPickaxe(m)) {
            return "těžba kamene, rud a tvrdých bloků";
        }
        if (Items.isAxe(m)) {
            return "kácení dřeva (a nouzová zbraň)";
        }
        if (Items.isShovel(m)) {
            return "kopání hlíny, písku, štěrku a sněhu";
        }
        if (Items.isHoe(m)) {
            return "orání půdy na políčko";
        }
        if (Items.isSword(m)) {
            return "boj a zabíjení mobů";
        }
        if (m == Material.BOW || m == Material.CROSSBOW) {
            return "střelba šípy na dálku";
        }
        if (m == Material.TRIDENT) {
            return "boj zblízka i vrhání na dálku";
        }
        if (m == Material.SHEARS) {
            return "stříhání vlny, listí a lián";
        }
        if (m == Material.FISHING_ROD) {
            return "rybaření";
        }
        if (m == Material.FLINT_AND_STEEL) {
            return "zapalování (portál do Netheru, oheň)";
        }
        if (m == Material.SHIELD) {
            return "blokování útoků";
        }
        if (m == Material.ELYTRA) {
            return "létání (klouzání vzduchem)";
        }
        if (Items.isArmor(m)) {
            return "ochrana v boji (" + armorSlotLabel(m) + ")";
        }
        if (Items.isAmmo(m)) {
            return "střelivo do luku a kuše";
        }
        if (Items.isPotion(m)) {
            return "lektvarový efekt (vypít, nebo splash hodit)";
        }
        if (Items.isBrewingIngredient(m)) {
            return "surovina na vaření lektvarů";
        }
        if (Items.isSeed(m) || Items.isSapling(m)) {
            return "zasadit a vypěstovat";
        }
        if (Materials.isOre(m)) {
            Double value = Materials.oreValue(m);
            return "vytěžit na surovinu"
                    + (value != null ? " (hodnota " + trim(value) + ")" : "");
        }
        if (Items.isRawMetal(m)) {
            return "přetavit v peci na ingot";
        }
        if (m == Material.IRON_INGOT) {
            return "železné nástroje, brnění a opravy";
        }
        if (m == Material.NETHERITE_INGOT) {
            return "vylepšení diamantové výbavy na netherit";
        }
        if (Items.isIngot(m)) {
            return "výroba a oprava kovové výbavy";
        }
        if (m == Material.DIAMOND) {
            return "diamantová výbava a enchantovací stůl";
        }
        if (m == Material.EMERALD) {
            return "měna pro obchod s vesničany";
        }
        if (m == Material.LAPIS_LAZULI) {
            return "enchantování u enchant stolu";
        }
        if (m == Material.COAL || m == Material.CHARCOAL) {
            return "palivo do pece a výroba pochodní";
        }
        if (Items.isCrop(m)) {
            return "jídlo, krmivo nebo obchod";
        }
        if (Materials.isMineralBlock(m)) {
            return "kompaktní sklad suroviny (9 kusů)";
        }
        if (Items.isBoat(m)) {
            return "plavba po vodě";
        }
        if (Items.isMinecart(m)) {
            return "kolejová doprava (jezdí po kolejích)";
        }
        if (Items.isRail(m)) {
            return "stavba kolejové dráhy";
        }
        if (Items.isBucket(m)) {
            return "nabírání a přenášení tekutin";
        }
        if (Items.isDye(m)) {
            return "barvení (vlna, sklo, kůže…)";
        }
        if (Items.isBed(m)) {
            return "spánek a nastavení respawnu";
        }
        if (Items.isBook(m)) {
            return "enchantování a zápis";
        }
        if (Items.isMusicDisc(m)) {
            return "hudba v jukeboxu";
        }
        if (Items.isSmithingTemplate(m)) {
            return "vzor pro vylepšení výbavy (smithing stůl)";
        }
        if (m == Material.TORCH) {
            return "osvětlení (brání spawnu mobů)";
        }
        if (Materials.isBuildingBlock(m)) {
            return "stavba (zdi, podlahy, pilíře, mosty)";
        }
        if (Items.isFuel(m)) {
            return "palivo do pece";
        }
        if (InventoryHelper.isReserveFood(m)) {
            return "nouzové léčení a sytost (šetřit na boj, ne na běžný hlad)";
        }
        if (Items.isFood(m)) {
            return "obnova hladu a sytosti";
        }
        return Codex.describe(m);
    }

    // ==================================================================
    // Co může / má dělat
    // ==================================================================

    private static List<String> can(Material m) {
        List<String> can = new ArrayList<>();
        if (hasDurability(m)) {
            can.add("nosit/používat");
        }
        if (Items.isFood(m)) {
            can.add("sníst");
        }
        if (Items.isSeed(m) || Items.isSapling(m)) {
            can.add("zasadit");
        }
        if (FurnaceService.isSmeltable(m)) {
            can.add("přetavit v peci");
        }
        if (Items.isFuel(m)) {
            can.add("spálit jako palivo");
        }
        if (Materials.isBuildingBlock(m)) {
            can.add("stavět a pilířovat");
        }
        if (Materials.isBankable(m)) {
            can.add("uložit přebytek do truhly (rezerva ~" + Materials.bankReserve(m) + ")");
        }
        return can;
    }

    // ==================================================================
    // Co nesmí / nemá dělat (pozor)
    // ==================================================================

    private static List<String> cannot(Material m) {
        List<String> cannot = new ArrayList<>();
        if (Materials.isOre(m)) {
            int tier = Materials.requiredPickTier(m);
            if (tier > 1) {
                cannot.add("nevytěžíš krumpáčem pod tier " + tier + " (nic nepadne)");
            }
        }
        if (Materials.isNether(m) && Materials.isWood(m)) {
            cannot.add("netherové dřevo NEHOŘÍ – není palivo");
        }
        if (m == Material.ROTTEN_FLESH) {
            cannot.add("může způsobit hlad – jíst až v nouzi");
        } else if (Items.isRawFood(m)) {
            cannot.add("syrové – radši nejdřív upéct (víc sytosti)");
        }
        if (InventoryHelper.isReserveFood(m)) {
            cannot.add("neplýtvat na běžný hlad – schovat na nouzi a boj");
        }
        if (m == Material.SPIDER_EYE || m == Material.POISONOUS_POTATO
                || m == Material.PUFFERFISH) {
            cannot.add("jedovaté – nejíst normálně");
        }
        if (Materials.isGravityBlock(m)) {
            cannot.add("padá dolů – nestavět bez podpory, nekopat zespodu");
        }
        if (isNetheriteGear(m) || m == Material.NETHERITE_INGOT
                || m == Material.NETHERITE_BLOCK || Items.isValuable(m) && Items.isFuel(m)) {
            cannot.add("neplýtvat (nepálit, nevhazovat do lávy)");
        }
        return cannot;
    }

    // ==================================================================
    // Jak vytvořit / vylepšit / opravit
    // ==================================================================

    private static String craft(Material m) {
        if (Items.isPickaxe(m)) {
            return "3× materiál v řadě nahoře + 2 klacky pod středem (verpánek)";
        }
        if (Items.isSword(m)) {
            return "2× materiál nad sebou + 1 klacek (verpánek)";
        }
        if (Items.isAxe(m)) {
            return "3× materiál do L + 2 klacky (verpánek)";
        }
        if (Items.isShovel(m)) {
            return "1× materiál + 2 klacky pod ním (verpánek)";
        }
        if (Items.isHoe(m)) {
            return "2× materiál nahoře vedle sebe + 2 klacky (verpánek)";
        }
        if (Materials.isPlanks(m)) {
            return "1 kláda → 4 prkna";
        }
        if (m == Material.STICK) {
            return "2 prkna nad sebou → 4 klacky";
        }
        if (m == Material.TORCH) {
            return "uhlí / dřevěné uhlí + klacek → 4 pochodně";
        }
        if (m == Material.CHARCOAL) {
            return "přetavit kládu v peci";
        }
        if (Items.isIngot(m) && !isNetherite(m)) {
            return "přetavit rudu / surovinu v peci";
        }
        if (m == Material.CRAFTING_TABLE) {
            return "4 prkna do čtverce";
        }
        if (m == Material.FURNACE) {
            return "8 dlažebních kostek do rámečku";
        }
        if (m == Material.CHEST) {
            return "8 prken do rámečku";
        }
        return null;
    }

    private static String upgrade(Material m) {
        if (isDiamondGear(m)) {
            return "na netherit: smithing stůl + šablona Netherite Upgrade + netheritový ingot";
        }
        if (hasDurability(m) || m == Material.BOOK) {
            return "očarovat (enchant stůl s lapisem, nebo kovadlina s očarovanou knihou)";
        }
        return null;
    }

    private static String repair(Material m) {
        if (!hasDurability(m)) {
            return null;
        }
        return "kovadlina se stejným materiálem, spojení dvou kusů u brusného kamene, "
                + "nebo enchant Mending";
    }

    // ==================================================================

    /** Má předmět trvanlivost (jde opravit/očarovat)? */
    private static boolean hasDurability(Material m) {
        return Items.isTool(m) || Items.isWeapon(m) || Items.isArmor(m)
                || m == Material.ELYTRA || m == Material.SHIELD;
    }

    private static boolean isDiamondGear(Material m) {
        return m != null && m.name().startsWith("DIAMOND_") && hasDurability(m);
    }

    private static boolean isNetheriteGear(Material m) {
        return m != null && m.name().startsWith("NETHERITE_") && hasDurability(m);
    }

    private static boolean isNetherite(Material m) {
        return m != null && m.name().startsWith("NETHERITE_");
    }

    private static String armorSlotLabel(Material m) {
        return switch (InventoryHelper.armorSlot(m)) {
            case 3 -> "helma";
            case 2 -> "prsní plát";
            case 1 -> "kalhoty";
            case 0 -> "boty";
            default -> "brnění";
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value) : String.valueOf(value);
    }
}
