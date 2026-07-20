package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import java.util.List;
import java.util.Optional;

/**
 * Geometrie stavby – formalizace dosud duck-typovaného kontraktu
 * ({@code placements/clearVolume/groundColumns/standPoint}), který dnes sdílí
 * {@code HouseBlueprint} i {@code WellBlueprint}. Jeden {@code BuildSession}
 * pak postaví libovolný blueprint místo čtyř opsaných smyček.
 *
 * <p>Metody vrací rovnou <b>světové</b> pozice pro daný {@code origin}
 * a {@code facing} – rotaci si řeší každý blueprint sám (legacy adaptéry
 * delegují na statické metody, takže geometrie je bitově shodná; generátory
 * a šablony fáze V2b/V2c rotují vlastním kódem). Pořadí pokládky {@code cells}
 * je nezávazné – závazné pořadí se zárukou opory počítá {@link BuildPlanner}.</p>
 */
public interface Blueprint {

    /**
     * @param origin roh půdorysu (minimální souřadnice, úroveň podlahy)
     * @param facing orientace stavby
     * @return bloky stavby (světové pozice), pořadí nezávazné
     */
    List<PlacementCell> cells(BlockPos origin, Cardinal facing);

    /**
     * @param origin roh půdorysu
     * @param facing orientace stavby
     * @return objem, který musí být před stavbou volný (světové pozice)
     */
    List<BlockPos> clearVolume(BlockPos origin, Cardinal facing);

    /**
     * @param origin roh půdorysu
     * @param facing orientace stavby
     * @return sloupce pod stavbou, které musí být pevné (světové pozice)
     */
    List<BlockPos> groundColumns(BlockPos origin, Cardinal facing);

    /**
     * @param origin roh půdorysu
     * @param facing orientace stavby
     * @return kroky vybavení (dveře, světlo, postel, truhla) – bonus, ne podmínka
     */
    List<FurnishCell> furnishing(BlockPos origin, Cardinal facing);

    /**
     * @param origin roh půdorysu
     * @param facing orientace stavby
     * @return místo, odkud stavitel klade (dosah na celou stavbu)
     */
    BlockPos standPoint(BlockPos origin, Cardinal facing);

    /**
     * @param origin roh půdorysu
     * @param facing orientace stavby
     * @return spodní buňka dveřního otvoru (exit), nebo prázdné (stavba bez dveří)
     */
    Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing);

    /** @return kolik stavebních bloků stavba spotřebuje (bez vybavení) */
    int blocksNeeded();

    /**
     * Musí stavitel stát <b>přesně</b> na stanovišti (studna – šachta, sýpka –
     * vnitřek), nebo stačí okolí (dům se staví, kam bot dojde)? Rozhoduje, jak
     * přísně {@code BuildSession} dokročí před pokládkou.
     *
     * @return {@code true} když je nutné stát přesně na {@code standPoint}
     */
    default boolean standExact() {
        return false;
    }
}
