package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

/**
 * Jeden krok vybavení: {@link FurnishKind druh} a světová pozice.
 *
 * @param kind druh vybavení
 * @param pos  světová pozice
 */
public record FurnishCell(FurnishKind kind, BlockPos pos) {
}
