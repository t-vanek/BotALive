package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Rozhodovací jádro povyšování domu na vyšší tier – čistá funkce. Vlastní tick
 * smyčku (dojít k domu, vytěžit, položit) vede {@code MaintainHomeGoal}; tady
 * se rozhoduje jen <b>co</b> povýšit: která role a které její bloky.
 *
 * <p>Povyšuje se <b>po celých rolích</b> (rozhodnutí rámce „stavba jako
 * proces"): najde se nejnižší role (základ → zeď → …), jejíž aspoň jeden
 * stojící blok není z cílového materiálu, a vrátí se její bloky k záměně
 * (nejvýš {@code limit} za seanci – tempo je střídmé). Dům tak dozrává
 * konzistentně, ne půl na půl.</p>
 *
 * <p>Okno (otvor → sklo) sem nepatří – prázdné místo řeší oprava proti vyšší
 * paletě ({@code MaintainHomeGoal.planRepairs}), tudy jdou jen <b>stojící</b>
 * bloky z nižšího materiálu.</p>
 */
public final class HomeUpgrade {

    private HomeUpgrade() {
    }

    /** Role k povýšení a její bloky k záměně (v pořadí buněk stavby). */
    public record Plan(PaletteRole role, List<PlacementCell> cells) {
    }

    /**
     * @param cells      bloky stavby (světové pozice + role)
     * @param palette    cílová paleta (tier, na který se povyšuje)
     * @param materialAt skutečný materiál ve světě na dané pozici ({@code null} = neznámo/vzduch)
     * @param limit      strop bloků k povýšení za jednu seanci
     * @return plán povýšení nejnižší nedokončené role, nebo prázdné (dům je
     *         celý z cílového materiálu)
     */
    public static Optional<Plan> next(List<PlacementCell> cells, Palette palette,
                                      Function<BlockPos, Material> materialAt, int limit) {
        PaletteRole role = lowestRole(cells, palette, materialAt);
        if (role == null) {
            return Optional.empty();
        }
        Material intended = palette.intended(role).orElseThrow();
        List<PlacementCell> pick = new ArrayList<>();
        for (PlacementCell cell : cells) {
            if (pick.size() >= limit) {
                break;
            }
            if (cell.spec().role() == role
                    && needsUpgrade(materialAt.apply(cell.pos()), intended)) {
                pick.add(cell);
            }
        }
        return Optional.of(new Plan(role, pick));
    }

    /**
     * Nejnižší role (podle {@link PaletteRole#ordinal()}), jejíž aspoň jeden
     * stojící blok není z cílového materiálu. Role bez cílového materiálu
     * (GENERIC, okno bez skla) se nepovyšují. {@code null} = není co povýšit.
     */
    private static PaletteRole lowestRole(List<PlacementCell> cells, Palette palette,
                                          Function<BlockPos, Material> materialAt) {
        PaletteRole lowest = null;
        for (PlacementCell cell : cells) {
            PaletteRole role = cell.spec().role();
            Material intended = palette.intended(role).orElse(null);
            if (intended == null || !needsUpgrade(materialAt.apply(cell.pos()), intended)) {
                continue;
            }
            if (lowest == null || role.ordinal() < lowest.ordinal()) {
                lowest = role;
            }
        }
        return lowest;
    }

    /** Stojící blok z jiného než cílového materiálu (díra ani vzduch se nepovyšují). */
    private static boolean needsUpgrade(Material actual, Material intended) {
        return actual != null && actual != Material.AIR && actual != intended;
    }
}
