package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Dávka pokládky z jednoho stanoviště: bloky, které stavitel položí, aniž by
 * se musel hnout. Velká stavba se rozpadne na víc jednotek (bot přechází po
 * vnitřní podlaze mezi nimi), aby na každý blok dosáhl s rezervou; malá stavba
 * je jediná jednotka (jako dřív). Pořadí bloků napříč jednotkami drží oporu.
 *
 * @param stand      stanoviště stavitele
 * @param placements bloky k položení z tohoto stanoviště (v pořadí s oporou)
 */
public record WorkUnit(BlockPos stand, List<PlacementCell> placements) {
}
