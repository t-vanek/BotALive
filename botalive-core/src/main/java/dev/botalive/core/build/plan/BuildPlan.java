package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import java.util.List;
import java.util.Optional;

/**
 * Rozvinutý plán stavby na konkrétním místě: {@link Blueprint} vyhodnocený
 * pro daný {@code origin} a {@code facing}. Spočítá se jednou (výběr
 * staveniště je věc cíle) a předává se {@link BuildPlanner} a
 * {@code BuildSession} – geometrie ve světových souřadnicích, žádné
 * přepočítávání za běhu.
 *
 * @param cells        bloky stavby (světové pozice), pořadí nezávazné
 * @param clearVolume  objem, který musí být volný
 * @param groundColumns sloupce podlahy (musí být pevné)
 * @param furnishing   kroky vybavení
 * @param stand        stanoviště stavitele
 * @param doorCell     spodní buňka dveří (exit), nebo prázdné
 * @param standExact   musí stavitel stát přesně na {@code stand}?
 */
public record BuildPlan(
        List<PlacementCell> cells,
        List<BlockPos> clearVolume,
        List<BlockPos> groundColumns,
        List<FurnishCell> furnishing,
        BlockPos stand,
        Optional<BlockPos> doorCell,
        boolean standExact) {

    /**
     * Vyhodnotí blueprint na daném místě.
     *
     * @param blueprint geometrie stavby
     * @param origin    roh půdorysu (úroveň podlahy)
     * @param facing    orientace stavby
     * @return rozvinutý plán ve světových souřadnicích
     */
    public static BuildPlan of(Blueprint blueprint, BlockPos origin, Cardinal facing) {
        return new BuildPlan(
                blueprint.cells(origin, facing),
                blueprint.clearVolume(origin, facing),
                blueprint.groundColumns(origin, facing),
                blueprint.furnishing(origin, facing),
                blueprint.standPoint(origin, facing),
                blueprint.doorCell(origin, facing),
                blueprint.standExact());
    }
}
