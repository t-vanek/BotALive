package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

/**
 * Jedna položka stavby: světová pozice a {@link BlockSpec předpis} bloku.
 * Pořadí pokládky určuje {@link BuildPlanner}, ne toto pole.
 *
 * @param pos  světová pozice bloku
 * @param spec předpis (role + orientace)
 */
public record PlacementCell(BlockPos pos, BlockSpec spec) {
}
