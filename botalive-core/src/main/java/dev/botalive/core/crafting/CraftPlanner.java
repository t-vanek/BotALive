package dev.botalive.core.crafting;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Čistý plánovač survival crafting progrese bota.
 *
 * <p>Jediný zdroj pravdy pro „co vyrobit dál" – sdílený server-side
 * implementací ({@link CraftingService}, čte živý Bukkit inventář)
 * i paketovou stanicí (čte klientský model inventáře). Vstupem je
 * {@link State} – mapa materiálů v inventáři – takže progrese jde
 * testovat jednotkově.</p>
 *
 * <p>Kompletní řetěz: suroviny → ponk → dřevěné → kamenné nástroje →
 * pec → pochodně → železné nástroje a sekera → štít → železné brnění →
 * diamantové nástroje a brnění → luk a šípy → výbava do Netheru
 * (křesadlo, zlaté boty) → truhla → loďka. Diamantová generace nahrazuje
 * železnou automaticky (kontroly „už má lepší" berou vyšší tier v potaz).
 * Netherit uzavírá progresi: starodávné trosky z Netheru se taví na
 * úlomky ({@code FurnaceService}), 4 úlomky + 4 zlato = ingot a povýšení
 * diamantové výbavy provádí kovářský stůl ({@code SmithingService},
 * s šablonou z bastionu). Z netherové kořisti se melou i oči Enderu
 * (blaze prach + perla) – klíč k zaplnění rámu portálu ve strongholdu
 * ({@code EndTravelGoal}).</p>
 *
 * <p>Netherová kořist živí i vedlejší řetězy: houba na prutu (rybářský
 * prut + warped fungus – řízení stridera), varný stojan s lahvemi
 * a palivem ({@code BrewGoal}), kotva respawnu (crying obsidián
 * + glowstone) a po elytrách rakety (papír + střelný prach) a shulker
 * box (2 ulity + truhla).</p>
 */
public final class CraftPlanner {

    /**
     * Cílová zásoba očí Enderu: rám portálu má 12 slotů, víc očí nemá
     * v inventáři co dělat (bez házení očí se strongholdy nehledají).
     */
    private static final int EYES_TARGET = 12;

    /**
     * Strop zásoby cihlových bloků, které bot vyrobí do zásoby na reprezentativní
     * dům – dost na zdi/základ/střechu jednoho domu, ať nemele cihly donekonečna.
     */
    private static final int BRICK_BLOCK_STOCK = 16;

    /** Strop zásoby tesaných cihel (jako {@link #BRICK_BLOCK_STOCK}). */
    private static final int STONE_BRICK_STOCK = 16;

    /** Strop zásoby skleněných tabulí do oken reprezentativního domu. */
    private static final int GLASS_PANE_STOCK = 16;

    /** Strop zásoby luceren (osvětlení reprezentativního domu). */
    private static final int LANTERN_STOCK = 4;

    /** Ingoty železa ponechané na nástroje/brnění – nugety až z přebytku. */
    private static final int IRON_RESERVE = 3;

    /** Strop zásoby květináčů (dekorace reprezentativního domu). */
    private static final int FLOWER_POT_STOCK = 2;

    /**
     * Jeden plán receptu: co vyrobit a z čeho (3×3 matice, row-major).
     *
     * @param id         české označení výrobku (pro chat/log)
     * @param matrix     rozložení surovin v mřížce 3×3 (row-major, null = prázdné)
     * @param needsTable {@code true} pokud recept přesahuje 2×2 (vyžaduje ponk)
     */
    public record Plan(String id, Material[] matrix, boolean needsTable) {

        /** @return spotřeba materiálů (materiál → počet kusů) */
        public Map<Material, Integer> ingredients() {
            Map<Material, Integer> counts = new HashMap<>();
            for (Material material : matrix) {
                if (material != null) {
                    counts.merge(material, 1, Integer::sum);
                }
            }
            return counts;
        }
    }

    /**
     * Souhrn inventáře pro plánování – nezávislý na zdroji dat.
     *
     * @param items     materiál → počet kusů (jen nenulové položky)
     * @param logType   konkrétní druh klády (null = žádná)
     * @param plankType konkrétní druh prken (null = žádná)
     * @param stoneType druh kamene pro nástroje (cobblestone/deepslate)
     * @param woolType  konkrétní druh vlny (null = žádná)
     * @param masonry   míří bot na reprezentativní dům? (odemyká tesané cihly –
     *                  jinak by je mlel každý a plýtval kamenem)
     */
    public record State(Map<Material, Integer> items, Material logType,
                        Material plankType, Material stoneType, Material woolType,
                        boolean masonry) {

        /** @return počet kusů materiálu */
        public int count(Material material) {
            return items.getOrDefault(material, 0);
        }

        /** @return {@code true} pokud má aspoň kus */
        public boolean has(Material material) {
            return count(material) > 0;
        }

        /** @return {@code true} pokud má materiál vyhovující predikátu */
        public boolean hasMatching(Predicate<Material> predicate) {
            for (Map.Entry<Material, Integer> entry : items.entrySet()) {
                if (entry.getValue() > 0 && predicate.test(entry.getKey())) {
                    return true;
                }
            }
            return false;
        }

        /** @return součet kusů materiálů vyhovujících predikátu */
        public int countMatching(Predicate<Material> predicate) {
            int total = 0;
            for (Map.Entry<Material, Integer> entry : items.entrySet()) {
                if (predicate.test(entry.getKey())) {
                    total += entry.getValue();
                }
            }
            return total;
        }

        // ------------------------------------------------ odvozené pohledy

        /** @return počet klád */
        public int logs() {
            return countMatching(m -> m.name().endsWith("_LOG") || m.name().endsWith("_STEM"));
        }

        /** @return počet prken */
        public int planks() {
            return countMatching(m -> m.name().endsWith("_PLANKS"));
        }

        /** @return počet tyček */
        public int sticks() {
            return count(Material.STICK);
        }

        /** @return počet cobble (včetně deepslate) */
        public int cobble() {
            return count(Material.COBBLESTONE) + count(Material.COBBLED_DEEPSLATE);
        }

        /** @return počet vlny (libovolná barva) */
        public int wool() {
            return countMatching(m -> m.name().endsWith("_WOOL"));
        }

        /** @return má ponk */
        public boolean hasTable() {
            return has(Material.CRAFTING_TABLE);
        }

        /** @return má nástroj daného typu v tieru {@code tier} nebo lepší */
        public boolean hasToolAtLeast(String suffix, int tier) {
            return hasMatching(m -> m.name().endsWith(suffix)
                    && dev.botalive.core.inventory.InventoryHelper.toolTier(m) >= tier);
        }
    }

    private CraftPlanner() {
    }

    /**
     * Rozhodne další krok survival progrese.
     *
     * @param s souhrn inventáře
     * @return plán, nebo {@code null} když nic nedává smysl
     */
    public static Plan next(State s) {
        Material plank = s.plankType();
        Material stone = s.stoneType();

        // ---- suroviny a základ (zásoba prken kryje i pozdější recepty: štít,
        // truhlu, dveře; 1 kláda = 4 prkna)
        if (s.planks() < 8 && s.logs() >= 1 && s.logType() != null) {
            return new Plan("prkna", matrix(s.logType(), 0), false);
        }
        if (s.sticks() < 4 && s.planks() >= 2 && plank != null) {
            return new Plan("tyčky", matrix(plank, 0, plank, 3), false);
        }
        if (!s.hasTable() && s.planks() >= 4 && plank != null) {
            return new Plan("ponk", matrix(plank, 0, plank, 1, plank, 3, plank, 4), false);
        }

        // ---- dřevěná generace
        boolean pickaxe1 = s.hasToolAtLeast("_PICKAXE", 1);
        if (s.hasTable() && plank != null && s.sticks() >= 2 && s.planks() >= 3 && !pickaxe1) {
            return new Plan("dřevěný krumpáč", matrix(
                    plank, 0, plank, 1, plank, 2, Material.STICK, 4, Material.STICK, 7), true);
        }
        boolean anySword = s.hasMatching(m -> m.name().endsWith("_SWORD"));
        if (s.hasTable() && plank != null && s.sticks() >= 1 && s.planks() >= 2 && !anySword) {
            return new Plan("dřevěný meč", matrix(
                    plank, 1, plank, 4, Material.STICK, 7), true);
        }
        boolean anyAxe = s.hasMatching(m -> m.name().endsWith("_AXE")
                && !m.name().endsWith("_PICKAXE"));
        if (s.hasTable() && plank != null && s.sticks() >= 2 && s.planks() >= 3 && !anyAxe) {
            return new Plan("dřevěná sekera", matrix(
                    plank, 0, plank, 1, plank, 3, Material.STICK, 4, Material.STICK, 7), true);
        }

        // ---- kamenná generace
        if (s.hasTable() && s.cobble() >= 3 && s.sticks() >= 2
                && !s.hasToolAtLeast("_PICKAXE", 3)) {
            return new Plan("kamenný krumpáč", matrix(
                    stone, 0, stone, 1, stone, 2, Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 2 && s.sticks() >= 1
                && anySword && !s.hasToolAtLeast("_SWORD", 3)) {
            return new Plan("kamenný meč", matrix(
                    stone, 1, stone, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 3 && s.sticks() >= 2
                && anyAxe && !hasAxeAtLeast(s, 3)) {
            return new Plan("kamenná sekera", matrix(
                    stone, 0, stone, 1, stone, 3, Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 1 && s.sticks() >= 2
                && !s.hasMatching(m -> m.name().endsWith("_SHOVEL"))) {
            return new Plan("kamenná lopata", matrix(
                    stone, 1, Material.STICK, 4, Material.STICK, 7), true);
        }
        // ---- motyka: jen když má bot osivo (chce zorat pole) – jinak by si ji
        // dělal každý zbytečně. Zpřístupní zakládání polí i mimo roli farmáře
        // a nahradí rozbitou motyku.
        if (s.hasTable() && s.cobble() >= 2 && s.sticks() >= 2
                && s.count(Material.WHEAT_SEEDS) >= 1
                && !s.hasMatching(m -> m.name().endsWith("_HOE"))) {
            return new Plan("motyka", matrix(
                    stone, 0, stone, 1, Material.STICK, 4, Material.STICK, 7), true);
        }

        // ---- pec (klíč k železu; rezerva cobble na nástroje)
        if (s.hasTable() && s.cobble() >= 11 && !s.has(Material.FURNACE)) {
            return new Plan("pec", matrix(
                    stone, 0, stone, 1, stone, 2, stone, 3, stone, 5,
                    stone, 6, stone, 7, stone, 8), true);
        }

        // ---- pochodně
        if (s.count(Material.COAL) >= 1 && s.sticks() >= 1
                && s.count(Material.TORCH) < 8) {
            return new Plan("pochodně", matrix(
                    Material.COAL, 1, Material.STICK, 4), false);
        }

        // ---- železná generace
        int iron = s.count(Material.IRON_INGOT);
        if (s.hasTable() && iron >= 3 && s.sticks() >= 2
                && !s.hasToolAtLeast("_PICKAXE", 4)) {
            return new Plan("železný krumpáč", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && iron >= 2 && s.sticks() >= 1
                && s.hasToolAtLeast("_SWORD", 3) && !s.hasToolAtLeast("_SWORD", 4)) {
            return new Plan("železný meč", matrix(
                    Material.IRON_INGOT, 1, Material.IRON_INGOT, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && iron >= 3 && s.sticks() >= 2
                && hasAxeAtLeast(s, 3) && !hasAxeAtLeast(s, 4)) {
            return new Plan("železná sekera", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 3,
                    Material.STICK, 4, Material.STICK, 7), true);
        }

        // ---- štít (combat ho blokuje z offhandu)
        if (s.hasTable() && iron >= 1 && s.planks() >= 6 && plank != null
                && !s.has(Material.SHIELD)) {
            return new Plan("štít", matrix(
                    plank, 0, Material.IRON_INGOT, 1, plank, 2,
                    plank, 3, plank, 4, plank, 5, plank, 7), true);
        }

        // ---- lepší pece: udírna (jídlo 2× rychleji) a blastová pec (rudy).
        // Spotřebují pec – další si bot vyrobí (krok „pec" se vrátí sám).
        if (s.hasTable() && s.has(Material.FURNACE) && s.logs() >= 4
                && s.logType() != null && !s.has(Material.SMOKER)) {
            return new Plan("udírna", matrix(
                    s.logType(), 1, s.logType(), 3, Material.FURNACE, 4,
                    s.logType(), 5, s.logType(), 7), true);
        }
        if (s.hasTable() && s.has(Material.FURNACE) && iron >= 5
                && s.count(Material.SMOOTH_STONE) >= 3 && !s.has(Material.BLAST_FURNACE)) {
            return new Plan("blastová pec", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 2,
                    Material.IRON_INGOT, 3, Material.FURNACE, 4, Material.IRON_INGOT, 5,
                    Material.SMOOTH_STONE, 6, Material.SMOOTH_STONE, 7,
                    Material.SMOOTH_STONE, 8), true);
        }

        // ---- železné brnění (nejcennější kusy dřív)
        Plan ironArmor = armorPlan(s, Material.IRON_INGOT, iron, "IRON",
                new String[]{"železný prsní plát", "železné kalhoty",
                        "železná helma", "železné boty"});
        if (ironArmor != null) {
            return ironArmor;
        }

        // ---- diamantová generace
        int diamonds = s.count(Material.DIAMOND);
        if (s.hasTable() && diamonds >= 3 && s.sticks() >= 2
                && !s.hasToolAtLeast("_PICKAXE", 5)) {
            return new Plan("diamantový krumpáč", matrix(
                    Material.DIAMOND, 0, Material.DIAMOND, 1, Material.DIAMOND, 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && diamonds >= 2 && s.sticks() >= 1
                && !s.hasToolAtLeast("_SWORD", 5)) {
            return new Plan("diamantový meč", matrix(
                    Material.DIAMOND, 1, Material.DIAMOND, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && diamonds >= 3 && s.sticks() >= 2 && !hasAxeAtLeast(s, 5)) {
            return new Plan("diamantová sekera", matrix(
                    Material.DIAMOND, 0, Material.DIAMOND, 1, Material.DIAMOND, 3,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        Plan diamondArmor = armorPlan(s, Material.DIAMOND, diamonds, "DIAMOND",
                new String[]{"diamantový prsní plát", "diamantové kalhoty",
                        "diamantová helma", "diamantové boty"});
        if (diamondArmor != null) {
            return diamondArmor;
        }

        // ---- enchantovací stůl (samoenchantování; 4 obsidián + 2 diamanty +
        // kniha). Bez něj se bot nikdy sám neočaruje (dřív jen když našel cizí).
        // Podmínka knihy drží recept hlavně u enchantera/knihovníka (mají ji
        // v kitu), takže ostatní role si obsidián na portál neukrojí.
        if (s.hasTable() && !s.has(Material.ENCHANTING_TABLE) && s.has(Material.BOOK)
                && s.count(Material.DIAMOND) >= 2 && s.count(Material.OBSIDIAN) >= 4) {
            return new Plan("enchantovací stůl", matrix(
                    Material.BOOK, 1, Material.DIAMOND, 3, Material.OBSIDIAN, 4,
                    Material.DIAMOND, 5, Material.OBSIDIAN, 6, Material.OBSIDIAN, 7,
                    Material.OBSIDIAN, 8), true);
        }

        // ---- křesadlo (klíč do Netheru; PŘED šípy – jinak by opakovaná
        // výroba šípů spolykala každý pazourek a křesadlo by nikdy nevzniklo)
        boolean netherPrep = s.hasToolAtLeast("_PICKAXE", 5);
        if (netherPrep && iron >= 1 && s.has(Material.FLINT)
                && !s.has(Material.FLINT_AND_STEEL)) {
            return new Plan("křesadlo", matrix(
                    Material.IRON_INGOT, 0, Material.FLINT, 1), false);
        }

        // ---- luk a šípy (dálkový boj)
        if (s.hasTable() && s.count(Material.STRING) >= 3 && s.sticks() >= 3
                && !s.has(Material.BOW) && !s.has(Material.CROSSBOW)) {
            return new Plan("luk", matrix(
                    Material.STICK, 1, Material.STRING, 2,
                    Material.STICK, 3, Material.STRING, 5,
                    Material.STICK, 7, Material.STRING, 8), true);
        }
        if (s.hasTable() && (s.has(Material.BOW) || s.has(Material.CROSSBOW))
                && s.has(Material.FLINT) && s.has(Material.FEATHER) && s.sticks() >= 1
                && s.count(Material.ARROW) < 16) {
            return new Plan("šípy", matrix(
                    Material.FLINT, 1, Material.STICK, 4, Material.FEATHER, 7), true);
        }

        // ---- rybářský prut (jídlo z vody). Dřív se prut vyráběl jen v larvě
        // "houba na prutu" pod podmínkou sedlo+houba, takže FishGoal (a s ním
        // celá role RYBÁŘ) se nikdy nespustil – prut nebylo kde vzít.
        // Provázek se dělí s lukem: dokud luk není, nechá se mu jeho porce.
        if (s.hasTable() && !s.has(Material.FISHING_ROD) && s.sticks() >= 3
                && s.count(Material.STRING) >= ((s.has(Material.BOW)
                        || s.has(Material.CROSSBOW)) ? 2 : 5)) {
            return new Plan("rybářský prut", matrix(
                    Material.STICK, 2, Material.STICK, 4, Material.STRING, 5,
                    Material.STICK, 6, Material.STRING, 8), true);
        }

        // ---- výbava do Netheru (má smysl až s diamantovým krumpáčem –
        // obsidián se jiným nevytěží; křesadlo viz výše před šípy)
        if (netherPrep && s.hasTable() && s.count(Material.GOLD_INGOT) >= 6
                && !s.has(Material.GOLDEN_BOOTS)) {
            // Zlaté boty piglini respektují; 2 ingoty navíc zůstávají na barter.
            return new Plan("zlaté boty", matrix(
                    Material.GOLD_INGOT, 0, Material.GOLD_INGOT, 2,
                    Material.GOLD_INGOT, 6, Material.GOLD_INGOT, 8), true);
        }

        // ---- jízda na striderovi: houba na prutu. Sedlo je kořist (pevnosti,
        // bastiony), houba se trhá v pokřiveném lese – prut se vyrábí, jen když
        // obojí čeká (string jinak patří luku, který je v řetězu dřív).
        if (s.has(Material.SADDLE) && s.has(Material.WARPED_FUNGUS)
                && !s.has(Material.WARPED_FUNGUS_ON_A_STICK)) {
            if (s.has(Material.FISHING_ROD)) {
                return new Plan("houba na prutu", matrix(
                        Material.FISHING_ROD, 0, Material.WARPED_FUNGUS, 4), false);
            }
            if (s.hasTable() && s.sticks() >= 3 && s.count(Material.STRING) >= 2) {
                return new Plan("rybářský prut", matrix(
                        Material.STICK, 2, Material.STICK, 4, Material.STRING, 5,
                        Material.STICK, 6, Material.STRING, 8), true);
            }
        }

        // ---- vaření lektvarů: stojan (1 blaze rod + 3 kámen), lahve ze skla,
        // palivo. Stojan má smysl až s bradavicí (bez ní není co vařit);
        // rod na palivo se mele, jen když je stojan i bradavice po ruce –
        // jinak rody patří očím Enderu (viz níže).
        if (s.hasTable() && s.has(Material.NETHER_WART) && !s.has(Material.BREWING_STAND)
                && s.count(Material.BLAZE_ROD) >= 1 && s.cobble() >= 3) {
            return new Plan("varný stojan", matrix(
                    Material.BLAZE_ROD, 1, stone, 3, stone, 4, stone, 5), true);
        }
        // Lahve i palivo se doplňují podle „kapitoly vaření" (stojan V BATOHU
        // nebo bradavice) – položený stojan z inventáře zmizí a čistě
        // stand-gate by doplňování navždy vypnul (splash lektvary lahve
        // spotřebovávají).
        boolean brewingChapter = s.has(Material.BREWING_STAND)
                || s.has(Material.NETHER_WART);
        if (s.hasTable() && brewingChapter
                && s.count(Material.GLASS) >= 3
                && s.count(Material.GLASS_BOTTLE) + s.count(Material.POTION) < 3) {
            return new Plan("skleněné lahve", matrix(
                    Material.GLASS, 0, Material.GLASS, 2, Material.GLASS, 4), true);
        }
        if (brewingChapter && s.has(Material.NETHER_WART)
                && !s.has(Material.BLAZE_POWDER) && s.count(Material.BLAZE_ROD) >= 1) {
            return new Plan("blaze prach (palivo)", matrix(Material.BLAZE_ROD, 0), false);
        }

        // ---- kotva respawnu: crying obsidián z barteru/bastionů, glowstone
        // z těžby (4 prachy = blok; 3 do kotvy, další bloky na nabíjení).
        if (s.count(Material.CRYING_OBSIDIAN) >= 6 && s.count(Material.GLOWSTONE_DUST) >= 4
                && s.count(Material.GLOWSTONE) < 4) {
            return new Plan("glowstone", matrix(
                    Material.GLOWSTONE_DUST, 0, Material.GLOWSTONE_DUST, 1,
                    Material.GLOWSTONE_DUST, 3, Material.GLOWSTONE_DUST, 4), false);
        }
        if (s.hasTable() && !s.has(Material.RESPAWN_ANCHOR)
                && s.count(Material.CRYING_OBSIDIAN) >= 6
                && s.count(Material.GLOWSTONE) >= 3) {
            return new Plan("kotva respawnu", matrix(
                    Material.CRYING_OBSIDIAN, 0, Material.CRYING_OBSIDIAN, 1,
                    Material.CRYING_OBSIDIAN, 2, Material.GLOWSTONE, 3,
                    Material.GLOWSTONE, 4, Material.GLOWSTONE, 5,
                    Material.CRYING_OBSIDIAN, 6, Material.CRYING_OBSIDIAN, 7,
                    Material.CRYING_OBSIDIAN, 8), true);
        }

        // ---- netherit (kořist z Netheru: trosky → úlomky → ingot; povýšení
        // výbavy dělá kovářský stůl přes SmithingService)
        if (s.hasTable() && s.count(Material.NETHERITE_SCRAP) >= 4
                && s.count(Material.GOLD_INGOT) >= 4 && !s.has(Material.NETHERITE_INGOT)) {
            return new Plan("netheritový ingot", matrix(
                    Material.NETHERITE_SCRAP, 0, Material.NETHERITE_SCRAP, 1,
                    Material.NETHERITE_SCRAP, 2, Material.NETHERITE_SCRAP, 3,
                    Material.GOLD_INGOT, 4, Material.GOLD_INGOT, 5,
                    Material.GOLD_INGOT, 6, Material.GOLD_INGOT, 7), true);
        }
        if (s.hasTable() && !s.has(Material.SMITHING_TABLE) && iron >= 2
                && s.planks() >= 4 && plank != null
                && (s.has(Material.NETHERITE_INGOT) || s.has(Material.NETHERITE_SCRAP)
                        || s.has(Material.ANCIENT_DEBRIS))) {
            return new Plan("kovářský stůl", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1,
                    plank, 3, plank, 4, plank, 6, plank, 7), true);
        }
        // Kopie šablony (7 diamantů + netherrack): šablona z bastionu je
        // jedna, ale povýšit chce bot celou výbavu – duplikace se vyplatí,
        // dokud zbývá víc diamantových kusů a je z čeho ji zaplatit.
        if (s.hasTable() && s.count(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE) == 1
                && s.count(Material.DIAMOND) >= 7 && s.has(Material.NETHERRACK)
                && diamondGearPieces(s) >= 2
                && (s.has(Material.NETHERITE_INGOT) || s.count(Material.NETHERITE_SCRAP) >= 4
                        || s.has(Material.ANCIENT_DEBRIS))) {
            return new Plan("kopie kovářské šablony", matrix(
                    Material.DIAMOND, 0, Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1,
                    Material.DIAMOND, 2, Material.DIAMOND, 3, Material.NETHERRACK, 4,
                    Material.DIAMOND, 5, Material.DIAMOND, 6, Material.DIAMOND, 7,
                    Material.DIAMOND, 8), true);
        }

        // ---- rakety na elytry (papír + střelný prach; nejmenší náboj = 3 ks)
        // a papír z třtiny. Jen s křídly – bez elytr rakety nemají co pohánět.
        boolean hasElytra = s.has(Material.ELYTRA);
        if (hasElytra && s.count(Material.FIREWORK_ROCKET) < 16
                && s.has(Material.PAPER) && s.has(Material.GUNPOWDER)) {
            return new Plan("rakety", matrix(
                    Material.PAPER, 0, Material.GUNPOWDER, 1), false);
        }
        if (hasElytra && s.count(Material.FIREWORK_ROCKET) < 16
                && s.count(Material.PAPER) < 3 && s.count(Material.SUGAR_CANE) >= 3
                && s.hasTable()) {
            return new Plan("papír", matrix(
                    Material.SUGAR_CANE, 0, Material.SUGAR_CANE, 1,
                    Material.SUGAR_CANE, 2), true);
        }

        // ---- shulker box (2 ulity + truhla): přenosná truhla výprav – vykope
        // se i s obsahem. Truhlu si bot dorobí (krok „truhla" se vrátí sám).
        if (s.hasTable() && s.count(Material.SHULKER_SHELL) >= 2
                && s.has(Material.CHEST)
                && !s.hasMatching(m -> m.name().endsWith("SHULKER_BOX"))) {
            return new Plan("shulker box", matrix(
                    Material.SHULKER_SHELL, 1, Material.CHEST, 4,
                    Material.SHULKER_SHELL, 7), true);
        }

        // ---- oči Enderu (perla + blaze prach, 2×2) – vstupenka k rámu
        // portálu ve strongholdu. Prach se mele, jen když na něj čeká perla:
        // 1 rod = 2 prachy a přebytek rodů zůstává vcelku (vaření si bere
        // palivo vlastní větví výš).
        int eyes = s.count(Material.ENDER_EYE);
        if (eyes < EYES_TARGET && s.has(Material.ENDER_PEARL)
                && s.has(Material.BLAZE_POWDER)) {
            return new Plan("oko Enderu", matrix(
                    Material.ENDER_PEARL, 0, Material.BLAZE_POWDER, 1), false);
        }
        if (eyes < EYES_TARGET && s.has(Material.ENDER_PEARL)
                && !s.has(Material.BLAZE_POWDER) && s.has(Material.BLAZE_ROD)) {
            return new Plan("blaze prach", matrix(Material.BLAZE_ROD, 0), false);
        }

        // ---- truhla a loďka (zázemí a cestování; rezerva prken)
        if (s.hasTable() && s.planks() >= 12 && plank != null
                && !s.has(Material.CHEST)) {
            return new Plan("truhla", matrix(
                    plank, 0, plank, 1, plank, 2, plank, 3, plank, 5,
                    plank, 6, plank, 7, plank, 8), true);
        }
        if (s.hasTable() && s.planks() >= 9 && plank != null
                && !s.hasMatching(m -> m.name().endsWith("_BOAT")
                        && !m.name().endsWith("_CHEST_BOAT"))) {
            return new Plan("loďka", matrix(
                    plank, 3, plank, 5, plank, 6, plank, 7, plank, 8), true);
        }

        // ---- kovadlina (opravy nástrojů; drahá – až při přebytku železa)
        if (s.hasTable() && !s.has(Material.ANVIL)
                && s.count(Material.IRON_BLOCK) < 3 && iron >= 13) {
            return new Plan("blok železa", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 2,
                    Material.IRON_INGOT, 3, Material.IRON_INGOT, 4, Material.IRON_INGOT, 5,
                    Material.IRON_INGOT, 6, Material.IRON_INGOT, 7,
                    Material.IRON_INGOT, 8), true);
        }
        if (s.hasTable() && !s.has(Material.ANVIL)
                && s.count(Material.IRON_BLOCK) >= 3 && iron >= 4) {
            return new Plan("kovadlina", matrix(
                    Material.IRON_BLOCK, 0, Material.IRON_BLOCK, 1, Material.IRON_BLOCK, 2,
                    Material.IRON_INGOT, 4, Material.IRON_INGOT, 6,
                    Material.IRON_INGOT, 7, Material.IRON_INGOT, 8), true);
        }

        // ---- composter (hnojivo ze semínek; farmářský okruh)
        if (s.hasTable() && plank != null && !s.has(Material.COMPOSTER)
                && s.countMatching(m -> m.name().endsWith("_SLAB")) < 7 && s.planks() >= 11) {
            return new Plan("dřevěné půlky", matrix(
                    plank, 0, plank, 1, plank, 2), true);
        }
        if (s.hasTable() && !s.has(Material.COMPOSTER)
                && s.countMatching(m -> m.name().endsWith("_SLAB")) >= 7) {
            Material slab = firstSlab(s);
            return new Plan("composter", matrix(
                    slab, 0, slab, 2, slab, 3, slab, 5,
                    slab, 6, slab, 7, slab, 8), true);
        }

        // ---- nůžky na vlnu: bez postele a bez vlny si bot udělá nůžky
        // (2 železa), aby mohl ostříhat ovce – dřív vlna byla jen ze zabíjení
        // ovcí, takže postel v biomu bez lovu nikdy nevznikla.
        if (s.hasTable() && iron >= 2 && !s.has(Material.SHEARS)
                && s.wool() < 3 && !s.hasMatching(m -> m.name().endsWith("_BED"))) {
            return new Plan("nůžky", matrix(Material.IRON_INGOT, 1, Material.IRON_INGOT, 3), true);
        }

        // ---- vybavení domova
        if (s.hasTable() && s.planks() >= 6 && plank != null
                && !s.hasMatching(m -> m.name().endsWith("_DOOR"))) {
            return new Plan("dveře", matrix(
                    plank, 0, plank, 1, plank, 3, plank, 4, plank, 6, plank, 7), true);
        }
        if (s.hasTable() && s.wool() >= 3 && s.woolType() != null
                && s.planks() >= 3 && plank != null
                && !s.hasMatching(m -> m.name().endsWith("_BED"))) {
            return new Plan("postel", matrix(
                    s.woolType(), 0, s.woolType(), 1, s.woolType(), 2,
                    plank, 3, plank, 4, plank, 5), true);
        }

        // ---- druhá truhla, až je vybavení hotové (nesmí předběhnout kovadlinu,
        // composter ani loďku). Sýpka jich chce 2 a StashGoal jednu spotřebuje
        // položením – s jedinou se tier MĚSTO nedal odemknout vůbec.
        if (s.hasTable() && s.planks() >= 20 && plank != null
                && s.count(Material.CHEST) < 2) {
            return new Plan("truhla do zásoby", matrix(
                    plank, 0, plank, 1, plank, 2, plank, 3, plank, 5,
                    plank, 6, plank, 7, plank, 8), true);
        }

        // ---- cihlový blok pro reprezentativní dům (REFINED): 4 cihly → blok.
        // Nejnižší priorita (za výbavou). Přirozeně gate-ované sběrem hlíny –
        // jen stavitel mířící na REFINED sbírá hlínu, taví ji na cihly a má
        // z čeho blok složit; zásoba se drží na rozumné mezi.
        if (s.count(Material.BRICK) >= 4 && s.count(Material.BRICKS) < BRICK_BLOCK_STOCK) {
            return new Plan("cihlový blok", matrix(
                    Material.BRICK, 0, Material.BRICK, 1, Material.BRICK, 3, Material.BRICK, 4),
                    false);
        }
        // ---- tesané cihly na základ a střechu REFINED domu: 4 kameny → 4 tesané.
        // Gate na masonry: kámen (z cobble) mají i cizí boti, tak by je jinak
        // mleli zbytečně. Kámen dodá tavba cobble (FurnaceService, masonry gate).
        if (s.masonry() && s.count(Material.STONE) >= 4
                && s.count(Material.STONE_BRICKS) < STONE_BRICK_STOCK) {
            return new Plan("tesané cihly", matrix(
                    Material.STONE, 0, Material.STONE, 1, Material.STONE, 3, Material.STONE, 4),
                    false);
        }
        // ---- skleněné tabule do oken REFINED domu: 6 skla → 16 tabulí. Jen
        // masonry (REFINED chce tabule); solidní dům skleněná okna z plného
        // skla by si jinak proměnil na tabule a přestal je umět zasklít.
        if (s.masonry() && s.count(Material.GLASS) >= 6
                && s.count(Material.GLASS_PANE) < GLASS_PANE_STOCK) {
            return new Plan("skleněné tabule", matrix(
                    Material.GLASS, 0, Material.GLASS, 1, Material.GLASS, 2,
                    Material.GLASS, 3, Material.GLASS, 4, Material.GLASS, 5), true);
        }
        // ---- lucerny do REFINED domu: z přebytku železa nugety, z nich lucerna
        // (8 nugetů + pochodeň). Úplně nejnižší priorita a rezerva ingotů –
        // nástroje a brnění mají vždy přednost před osvětlením.
        if (s.masonry() && s.count(Material.LANTERN) < LANTERN_STOCK
                && s.count(Material.IRON_NUGGET) < 8
                && s.count(Material.IRON_INGOT) > IRON_RESERVE) {
            return new Plan("železné nugety", matrix(Material.IRON_INGOT, 0), false);
        }
        if (s.masonry() && s.hasTable() && s.count(Material.IRON_NUGGET) >= 8
                && s.count(Material.TORCH) >= 1 && s.count(Material.LANTERN) < LANTERN_STOCK) {
            return new Plan("lucerna", matrix(
                    Material.IRON_NUGGET, 0, Material.IRON_NUGGET, 1, Material.IRON_NUGGET, 2,
                    Material.IRON_NUGGET, 3, Material.TORCH, 4, Material.IRON_NUGGET, 5,
                    Material.IRON_NUGGET, 6, Material.IRON_NUGGET, 7, Material.IRON_NUGGET, 8),
                    true);
        }
        // ---- květináče do REFINED domu: 3 cihly do V. Až z přebytku cihel
        // (dům už má zásobu bloků na zdi) – dekorace nesmí ujídat zdivo.
        if (s.masonry() && s.hasTable() && s.count(Material.BRICK) >= 3
                && s.count(Material.BRICKS) >= 8
                && s.count(Material.FLOWER_POT) < FLOWER_POT_STOCK) {
            return new Plan("květináč", matrix(
                    Material.BRICK, 0, Material.BRICK, 2, Material.BRICK, 4), true);
        }
        return null;
    }

    /** První druh dřevěné půlky v inventáři. */
    private static Material firstSlab(State s) {
        for (Material material : s.items().keySet()) {
            if (material.name().endsWith("_SLAB") && s.count(material) >= 7) {
                return material;
            }
        }
        for (Material material : s.items().keySet()) {
            if (material.name().endsWith("_SLAB")) {
                return material;
            }
        }
        return Material.OAK_SLAB;
    }

    /** Počet diamantových kusů výbavy (kandidátů na povýšení na netherit). */
    private static int diamondGearPieces(State s) {
        return s.countMatching(m -> {
            String n = m.name();
            return n.startsWith("DIAMOND_") && (n.endsWith("_PICKAXE") || n.endsWith("_SWORD")
                    || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                    || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                    || n.endsWith("_HELMET") || n.endsWith("_BOOTS"));
        });
    }

    /** Má sekeru v tieru {@code tier} nebo lepší (bez krumpáčů). */
    private static boolean hasAxeAtLeast(State s, int tier) {
        return s.hasMatching(m -> m.name().endsWith("_AXE") && !m.name().endsWith("_PICKAXE")
                && dev.botalive.core.inventory.InventoryHelper.toolTier(m) >= tier);
    }

    /** Další chybějící kus brnění dané suroviny (prsník → kalhoty → helma → boty). */
    private static Plan armorPlan(State s, Material ingot, int available,
                                  String tierPrefix, String[] labels) {
        if (!s.hasTable()) {
            return null;
        }
        if (available >= 8 && !hasArmor(s, tierPrefix, "_CHESTPLATE")) {
            return new Plan(labels[0], matrix(
                    ingot, 0, ingot, 2, ingot, 3, ingot, 4, ingot, 5,
                    ingot, 6, ingot, 7, ingot, 8), true);
        }
        if (available >= 7 && !hasArmor(s, tierPrefix, "_LEGGINGS")) {
            return new Plan(labels[1], matrix(
                    ingot, 0, ingot, 1, ingot, 2, ingot, 3, ingot, 5,
                    ingot, 6, ingot, 8), true);
        }
        if (available >= 5 && !hasArmor(s, tierPrefix, "_HELMET")) {
            return new Plan(labels[2], matrix(
                    ingot, 0, ingot, 1, ingot, 2, ingot, 3, ingot, 5), true);
        }
        if (available >= 4 && !hasArmor(s, tierPrefix, "_BOOTS")) {
            return new Plan(labels[3], matrix(
                    ingot, 0, ingot, 2, ingot, 6, ingot, 8), true);
        }
        return null;
    }

    /** Má kus brnění daného typu v tomto tieru nebo lepším. */
    private static boolean hasArmor(State s, String tierPrefix, String suffix) {
        int wanted = tierPrefix.equals("DIAMOND") ? 5 : 4;
        return s.hasMatching(m -> m.name().endsWith(suffix)
                && dev.botalive.core.inventory.InventoryHelper.armorTier(m) >= wanted);
    }

    /** Sestaví 3×3 matici z dvojic (materiál, index). */
    private static Material[] matrix(Object... pairs) {
        Material[] matrix = new Material[9];
        for (int i = 0; i < pairs.length; i += 2) {
            matrix[(Integer) pairs[i + 1]] = (Material) pairs[i];
        }
        return matrix;
    }
}
