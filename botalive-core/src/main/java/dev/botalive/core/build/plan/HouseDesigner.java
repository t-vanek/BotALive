package dev.botalive.core.build.plan;

import dev.botalive.api.bot.Bot;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.inventory.InventoryHelper;

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
     */
    public record HouseDesign(int width, int wallHeight, Material wood, long seed) {

        /** @return geometrie domu */
        public Blueprint blueprint() {
            return new HouseGenerator(width, wallHeight);
        }

        /** @return materiály podle rolí */
        public Palette palette() {
            return PaletteResolver.resolve(wood, seed);
        }

        /** @return klíč designu do paměti HOME (rekonstrukce při údržbě). */
        public String key() {
            return "house" + width + "x" + wallHeight;
        }
    }

    /**
     * @param bot      bot
     * @param snapshot snímek inventáře (pro volbu dřeva)
     * @param cfg      konfigurace staveb
     * @return návrh domu pro tohoto bota
     */
    public static HouseDesign design(Bot bot, ServerSideView.Snapshot snapshot,
                                     BotAliveConfig.Build cfg) {
        return new HouseDesign(cfg.width(), cfg.wallHeight(),
                dominantWood(snapshot), bot.personality().seed());
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
