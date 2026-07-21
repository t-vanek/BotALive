package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import org.bukkit.Material;

/**
 * Jeden krok vybavení: {@link FurnishKind druh}, světová pozice a – jen pro
 * {@link FurnishKind#STATION} – konkrétní materiál stanice.
 *
 * @param kind     druh vybavení
 * @param pos      světová pozice
 * @param material materiál pracovní stanice pro {@link FurnishKind#STATION};
 *                 {@code null} pro ostatní druhy (item řeší {@code Blueprints.itemFor})
 */
public record FurnishCell(FurnishKind kind, BlockPos pos, Material material) {

    /**
     * Vybavení bez konkrétního materiálu (dveře, pochodeň, postel, truhla) –
     * item určuje {@link Blueprints#itemFor(FurnishKind)}.
     *
     * @param kind druh vybavení
     * @param pos  světová pozice
     */
    public FurnishCell(FurnishKind kind, BlockPos pos) {
        this(kind, pos, null);
    }
}
