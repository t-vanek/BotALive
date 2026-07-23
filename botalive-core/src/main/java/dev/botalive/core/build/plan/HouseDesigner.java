package dev.botalive.core.build.plan;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.settlement.SettlementTier;

import org.bukkit.Material;

/**
 * Vybere konkrétní podobu domu pro bota: velikost z konfigurace, dřevo podle
 * toho, co bot nasbíral (dub v listnatém lese, smrk v horách…), variaci ze
 * seedu osobnosti. Deterministické podle (seed, konfigurace, dřevo v batohu),
 * takže rozestavěný dům se po restartu dogeneruje stejně a dostaví
 * (resume přes world-diff).
 */
public final class HouseDesigner {

    /** Druhy dřeva, ze kterých bot staví (podle nasbíraných prken/kmenů). */
    private static final Material[] WOODS = {
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
            Material.JUNGLE_PLANKS, Material.ACACIA_PLANKS, Material.DARK_OAK_PLANKS,
            Material.MANGROVE_PLANKS, Material.CHERRY_PLANKS};

    private HouseDesigner() {
    }

    /**
     * Konkrétní návrh domu: geometrie ({@link #blueprint()}) + materiály
     * ({@link #palette()}).
     *
     * @param width      šířka půdorysu
     * @param wallHeight výška zdí
     * @param wood       druh dřeva (prkna/kmen)
     * @param seed       seed variace palety
     * @param tier       stavební stupeň (materiály; geometrie je na tieru nezávislá)
     */
    public record HouseDesign(int width, int wallHeight, Material wood, long seed,
                              BuildTier tier) {

        /**
         * Bez tieru = {@link BuildTier#SOLID} (dnešní vzhled). Drží zpětnou
         * kompatibilitu: starý dům bez uloženého stupně se opraví jako solidní.
         */
        public HouseDesign(int width, int wallHeight, Material wood, long seed) {
            this(width, wallHeight, wood, seed, BuildTier.SOLID);
        }

        /** @return geometrie domu (tvar střechy ze seedu, komín u REFINED) */
        public Blueprint blueprint() {
            return new HouseGenerator(width, wallHeight, Math.floorMod(seed, 3) != 0, tier);
        }

        /** @return materiály podle rolí pro daný {@link #tier} */
        public Palette palette() {
            return PaletteResolver.resolve(wood, seed, tier);
        }

        /** @return klíč designu do paměti HOME (rekonstrukce při údržbě). */
        public String key() {
            return "house" + width + "x" + wallHeight;
        }
    }

    /**
     * @param bot      bot
     * @param snapshot snímek inventáře (pro volbu dřeva)
     * @param cfg      konfigurace staveb ({@code width} je strop)
     * @param tier     stupeň sídla bota (osada staví útulně, město honosně)
     * @return návrh domu pro tohoto bota
     */
    public static HouseDesign design(Bot bot, ServerSideView.Snapshot snapshot,
                                     BotAliveConfig.Build cfg, SettlementTier tier) {
        double laziness = bot.personality().trait(Trait.LAZINESS);
        int width = widthFor(tier, laziness, cfg.width());
        return new HouseDesign(width, cfg.wallHeight(), dominantWood(snapshot),
                bot.personality().seed(), tierFor(tier, laziness));
    }

    /**
     * Stavební stupeň nového domu z prosperity sídla a osobnosti (čistá,
     * testovatelná): osada staví provizorně (srub), vesnice solidně, město
     * reprezentativně. Osobnost posune o stupeň – pracovitý zvelebuje, líný
     * bydlí skromně. Samotář (bez sídla) začíná srubem. Prosperita je hlavní
     * hnací síla, osobnost jen modulace (rozhodnutí rámce „stavba jako proces").
     *
     * @param tier     stupeň sídla ({@code null} = osada / bez sídla)
     * @param laziness lenost bota (0–1)
     * @return stavební stupeň domu
     */
    public static BuildTier tierFor(SettlementTier tier, double laziness) {
        int base = switch (tier == null ? SettlementTier.OSADA : tier) {
            case OSADA -> 0;   // srub
            case VESNICE -> 1; // solidní
            case MESTO -> 2;   // reprezentativní
        };
        if (laziness < 0.34) {
            base += 1; // pracovitý/hrdý zvelebuje
        } else if (laziness > 0.66) {
            base -= 1; // líný bydlí skromně
        }
        return BuildTier.fromOrdinal(base);
    }

    /**
     * Velikost domu odvozená ze stupně sídla a lenosti (čistá, testovatelná):
     * osada staví útulné 5×5, vesnice větší, město do plného stropu; líný bot
     * staví malý bez ohledu na sídlo. {@code cap} ({@code build.width}) je
     * horní mez daná konfigurací.
     *
     * @param tier     stupeň sídla ({@code null} = osada / bez sídla)
     * @param laziness lenost bota (0–1)
     * @param cap      strop půdorysu z konfigurace (lichý, ≥ 5)
     * @return šířka půdorysu (lichá, 5..cap)
     */
    public static int widthFor(SettlementTier tier, double laziness, int cap) {
        int base = switch (tier == null ? SettlementTier.OSADA : tier) {
            case OSADA -> 5;
            case VESNICE -> Math.min(cap, 7);
            case MESTO -> cap;
        };
        if (laziness > 0.66) {
            base = 5; // líný bydlí skromně
        }
        int width = Math.min(base, cap);
        if (width % 2 == 0) {
            width--; // lichý půdorys
        }
        return Math.max(5, width);
    }

    /** Nejčastější dřevo v batohu (prkna+kmeny); dub, když žádné. */
    private static Material dominantWood(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return Material.OAK_PLANKS;
        }
        Material best = Material.OAK_PLANKS;
        int bestCount = -1;
        for (Material planks : WOODS) {
            String prefix = planks.name().substring(0, planks.name().length() - "_PLANKS".length());
            int count = InventoryHelper.countEstimate(snapshot,
                    m -> m == planks || m.name().equals(prefix + "_LOG"));
            if (count > bestCount) {
                bestCount = count;
                best = planks;
            }
        }
        return best;
    }
}
