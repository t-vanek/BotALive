package dev.botalive.core.inventory;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Znalostní vrstva nad pracovními stanicemi – „karta" stanice: k čemu slouží,
 * co potřebuje (vstupy), co vyrobí (výstupy) a který botní cíl ji obsluhuje.
 *
 * <p>Zatímco {@link MaterialGuide} popisuje afordance jednotlivých materiálů
 * pravidlově přes celou vanillu, tady jde o funkci konečné množiny pracovních
 * bloků, u kterých bot „pracuje" (pec, kovadlina, kovářský/enchant/varný stůl,
 * kompostér, truhla…). Je to proto kurátorovaný katalog – ale drží se
 * skutečného chování botů (který cíl stanici obsluhuje), ne jen vanilla
 * teorie.</p>
 *
 * <p>Registry-free (jen enum konstanty a switch), takže se testuje bez serveru
 * a čte ho i {@code /botalive codex &lt;stanice&gt;} vedle {@link MaterialGuide}.</p>
 */
public final class StationGuide {

    private StationGuide() {
    }

    /**
     * Karta stanice.
     *
     * @param station  blok stanice
     * @param purpose  k čemu slouží (hlavní funkce)
     * @param needs    co potřebuje (vstupy)
     * @param produces co vyrobí (výstupy; prázdné = jen skladuje / nevyrábí)
     * @param usedBy   který botní cíl ji obsluhuje
     */
    public record Guidance(Material station, String purpose, List<String> needs,
                           List<String> produces, String usedBy) {
    }

    /**
     * Je materiál pracovní stanice (má kartu)?
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro známou stanici
     */
    public static boolean isStation(Material material) {
        return of(material) != null;
    }

    /**
     * Karta stanice.
     *
     * @param material blok stanice ({@code null} = žádná)
     * @return karta, nebo {@code null} když materiál není stanice
     */
    public static Guidance of(Material material) {
        if (material == null) {
            return null;
        }
        return switch (material) {
            case CRAFTING_TABLE -> new Guidance(material,
                    "výroba předmětů z receptů (3×3 mřížka)",
                    List.of("suroviny podle receptu – prkna, klacky, ingoty, kámen…"),
                    List.of("nástroje, zbraně, stanice, bloky, pomůcky"),
                    "CraftGoal – bot si u něj vyrábí, co zrovna potřebuje");
            case FURNACE -> new Guidance(material,
                    "tavení a pečení (ruda → ingot, syrové → pečené)",
                    List.of("surovinu (ruda, syrové jídlo, kulatý kámen…)",
                            "palivo (uhlí, prkna, klacky – netherové dřevo nehoří)"),
                    List.of("ingoty, pečené jídlo, kámen, dřevěné uhlí, sklo"),
                    "SmeltGoal");
            case BLAST_FURNACE -> new Guidance(material,
                    "rychlé tavení rud a kovů (2× rychleji než pec, jen suroviny)",
                    List.of("kovovou surovinu (ruda, raw metal)", "palivo"),
                    List.of("ingoty"),
                    "SmeltGoal (dílna zbrojíře)");
            case SMOKER -> new Guidance(material,
                    "rychlé pečení jídla (2× rychleji než pec, jen jídlo)",
                    List.of("syrové jídlo", "palivo"),
                    List.of("pečené jídlo"),
                    "SmeltGoal (kuchyně kuchaře)");
            case STONECUTTER -> new Guidance(material,
                    "řezání kamene na tvary (schody, desky, cihly) bez plýtvání",
                    List.of("kámen nebo kamenný blok"),
                    List.of("schody, desky, tesané a jiné kamenné bloky"),
                    "crafting/stavba (kameník)");
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> new Guidance(material,
                    "oprava a očarování výbavy se zachováním enchantů (i pojmenování)",
                    List.of("poškozený kus + surovinu téhož materiálu (nebo očarovanou knihu)",
                            "XP levely"),
                    List.of("opravený / očarovaný kus"),
                    "RepairGoal (sama je gravitační – opotřebením padá)");
            case GRINDSTONE -> new Guidance(material,
                    "oprava spojením dvou kusů bez suroviny (a sundání očarování za XP)",
                    List.of("dva opotřebené neočarované kusy téhož druhu"),
                    List.of("jeden opravený kus"),
                    "RepairGoal (záchrana, když není čím opravovat)");
            case SMITHING_TABLE -> new Guidance(material,
                    "povýšení diamantové výbavy na netherit (a zdobné vzory)",
                    List.of("diamantový kus", "netheritový ingot", "šablonu Netherite Upgrade"),
                    List.of("netheritová výbava"),
                    "SmithGoal");
            case ENCHANTING_TABLE -> new Guidance(material,
                    "očarování výbavy za XP (víc knihoven okolo = silnější kouzla)",
                    List.of("kus k očarování", "lapis lazuli", "XP levely"),
                    List.of("očarovaný kus"),
                    "EnchantGoal");
            case BREWING_STAND -> new Guidance(material,
                    "vaření lektvarů",
                    List.of("lahve s vodou", "ingredience (nether wart…)", "blaze prášek (palivo)"),
                    List.of("lektvary (léčení, rychlost, ohnivzdornost…)"),
                    "BrewGoal");
            case COMPOSTER -> new Guidance(material,
                    "kompostování rostlinných přebytků na hnojivo",
                    List.of("semínka, sazenice, listí a rostlinné zbytky"),
                    List.of("kostní moučka (hnojivo)"),
                    "CompostGoal");
            case CHEST, TRAPPED_CHEST, BARREL -> new Guidance(material,
                    "úložiště – bankování přebytků a surovin",
                    List.of("přebytek k uložení (odpad, komodity nad pracovní rezervu)"),
                    List.of(),
                    "StashGoal – bot si sem odkládá, co unese navíc");
            case SHULKER_BOX -> new Guidance(material,
                    "přenosné úložiště – drží obsah i po rozbití",
                    List.of("věci k přenesení"),
                    List.of(),
                    "StashGoal / výpravy do Endu");
            case RESPAWN_ANCHOR -> new Guidance(material,
                    "respawn point v Netheru (postele tam vybuchují)",
                    List.of("nabít glowstonem"),
                    List.of(),
                    "NetherGoal – kotva respawnu u základny");
            default -> null;
        };
    }

    /**
     * Karta jako řádky „štítek: text" pro zobrazení ({@code /botalive codex}) i
     * botní intent.
     *
     * @param material materiál
     * @return řádky karty; prázdný seznam, když materiál není stanice
     */
    public static List<String> lines(Material material) {
        Guidance g = of(material);
        if (g == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("stanice: " + g.purpose());
        if (!g.needs().isEmpty()) {
            out.add("potřebuje: " + String.join("; ", g.needs()));
        }
        if (!g.produces().isEmpty()) {
            out.add("vyrobí: " + String.join("; ", g.produces()));
        }
        if (g.usedBy() != null) {
            out.add("bot: " + g.usedBy());
        }
        return out;
    }
}
