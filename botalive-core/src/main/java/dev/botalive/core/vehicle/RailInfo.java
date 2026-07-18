package dev.botalive.core.vehicle;

import dev.botalive.core.util.Cardinal;

/**
 * Popis jednoho kolejového bloku pro simulaci minecartu.
 *
 * <p>Vlastní typ (místo Bukkit {@code Rail.Shape}) drží simulaci nezávislou na
 * Bukkit API – testy si koleje staví přímo, produkce je čte přes
 * {@link WorldRailReader} z chunk snapshotů.</p>
 *
 * @param shape       tvar koleje
 * @param powered     blok je napájený redstonem (má smysl jen pro powered rail)
 * @param poweredRail jde o napájecí kolej (POWERED_RAIL)
 */
public record RailInfo(Shape shape, boolean powered, boolean poweredRail) {

    /** Tvary kolejí (zrcadlí vanilla rail shapes). */
    public enum Shape {
        /** Rovně sever–jih. */
        NORTH_SOUTH,
        /** Rovně východ–západ. */
        EAST_WEST,
        /** Stoupání směrem na východ. */
        ASCENDING_EAST,
        /** Stoupání směrem na západ. */
        ASCENDING_WEST,
        /** Stoupání směrem na sever. */
        ASCENDING_NORTH,
        /** Stoupání směrem na jih. */
        ASCENDING_SOUTH,
        /** Zatáčka jih–východ. */
        SOUTH_EAST,
        /** Zatáčka jih–západ. */
        SOUTH_WEST,
        /** Zatáčka sever–západ. */
        NORTH_WEST,
        /** Zatáčka sever–východ. */
        NORTH_EAST
    }


    /**
     * @return dvojice stran, které tvar koleje propojuje
     */
    public Cardinal[] connections() {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH ->
                    new Cardinal[]{Cardinal.NORTH, Cardinal.SOUTH};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST ->
                    new Cardinal[]{Cardinal.EAST, Cardinal.WEST};
            case SOUTH_EAST -> new Cardinal[]{Cardinal.SOUTH, Cardinal.EAST};
            case SOUTH_WEST -> new Cardinal[]{Cardinal.SOUTH, Cardinal.WEST};
            case NORTH_WEST -> new Cardinal[]{Cardinal.NORTH, Cardinal.WEST};
            case NORTH_EAST -> new Cardinal[]{Cardinal.NORTH, Cardinal.EAST};
        };
    }

    /**
     * Kudy kolej pokračuje, vjede-li do ní vozík jedoucí směrem {@code moving}.
     *
     * @param moving směr jízdy vozíku
     * @return výstupní směr, nebo {@code null} pokud kolej z této strany nenavazuje
     */
    public Cardinal exitFor(Cardinal moving) {
        Cardinal entrySide = moving.opposite();
        Cardinal[] connections = connections();
        if (connections[0] == entrySide) {
            return connections[1];
        }
        if (connections[1] == entrySide) {
            return connections[0];
        }
        return null;
    }

    /**
     * @return směr stoupání, nebo {@code null} pro vodorovné koleje
     */
    public Cardinal ascendingToward() {
        return switch (shape) {
            case ASCENDING_EAST -> Cardinal.EAST;
            case ASCENDING_WEST -> Cardinal.WEST;
            case ASCENDING_NORTH -> Cardinal.NORTH;
            case ASCENDING_SOUTH -> Cardinal.SOUTH;
            default -> null;
        };
    }
}
