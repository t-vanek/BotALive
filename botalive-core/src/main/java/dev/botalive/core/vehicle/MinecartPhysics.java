package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.vehicle.RailInfo.Cardinal;

/**
 * Klientská simulace minecartu na kolejích.
 *
 * <p>Stejně jako u lodi je jízda klientsky autoritativní – řidič posílá
 * MoveVehicle pakety. Simulace replikuje vanilla chování v rozsahu potřebném
 * pro věrohodnou jízdu: pohyb vázaný na osu koleje, zatáčky (přesměrování
 * v bloku zatáčky), stoupání/klesání s gravitačním zrychlením, napájecí
 * koleje (boost/brzda), tření a zastavení na konci trati. Rychlost je
 * omezená vanilla stropem 0.4 bloku/tick (8 m/s).</p>
 *
 * <p>Čistá třída bez Bukkit závislostí – koleje čte přes {@link RailReader},
 * takže je plně testovatelná.</p>
 */
public final class MinecartPhysics {

    /** Vanilla strop rychlosti vozíku (bloky/tick). */
    private static final double MAX_SPEED = 0.4;

    /** Zrychlení na napájené powered rail (bloky/tick²). */
    private static final double POWERED_ACCEL = 0.06;

    /** Gravitační zrychlení na svahu (bloky/tick²). */
    private static final double SLOPE_ACCEL = 0.0078;

    /** Tření na rovné koleji za tick. */
    private static final double FRICTION = 0.997;

    /** Výška vozíku nad kolejí. */
    private static final double CART_Y_OFFSET = 0.1;

    private final RailReader rails;

    private BlockPos railBlock;
    private Vec3 position;
    private Cardinal direction;
    private double speed;
    private boolean endOfTrack;
    private int stoppedTicks;

    /**
     * Postaví simulaci nad vozíkem stojícím na koleji.
     *
     * @param rails zdroj kolejí
     * @param start pozice vozíku (od serveru)
     * @throws IllegalStateException pokud pod vozíkem není kolej
     */
    public MinecartPhysics(RailReader rails, Vec3 start) {
        this.rails = rails;
        BlockPos block = start.toBlockPos();
        RailInfo rail = rails.railAt(block);
        if (rail == null) {
            block = block.down();
            rail = rails.railAt(block);
        }
        if (rail == null) {
            throw new IllegalStateException("Pod vozíkem není kolej: " + start.toBlockPos());
        }
        this.railBlock = block;
        // Výchozí směr: strana, za kterou trať pokračuje dál (delší jízda).
        Cardinal[] connections = rail.connections();
        this.direction = railContinues(block, connections[0]) ? connections[0]
                : railContinues(block, connections[1]) ? connections[1]
                : connections[0];
        this.position = centered(block, rail, start);
        // Jemné rozjetí („zhoupnutí vozíku") – na mrtvé trati se rychlost
        // ztratí třením a jízda skončí; powered rail ji naopak zesílí.
        this.speed = 0.08;
    }

    /** @return aktuální pozice vozíku */
    public Vec3 position() {
        return position;
    }

    /** @return yaw vozíku podle směru jízdy */
    public float yaw() {
        return direction.yaw();
    }

    /** @return aktuální rychlost (bloky/tick) */
    public double speed() {
        return speed;
    }

    /** @return aktuální směr jízdy */
    public Cardinal direction() {
        return direction;
    }

    /** @return {@code true} pokud jízda skončila (konec trati / vozík stojí) */
    public boolean finishedRiding() {
        return endOfTrack || stoppedTicks > 40;
    }

    /**
     * Tvrdá korekce od serveru.
     *
     * @param position pozice od serveru
     * @param yaw      natočení (ignoruje se – směr určuje kolej)
     */
    public void correct(Vec3 position, float yaw) {
        this.position = position;
        BlockPos block = position.toBlockPos();
        if (rails.railAt(block) != null) {
            this.railBlock = block;
        } else if (rails.railAt(block.down()) != null) {
            this.railBlock = block.down();
        }
    }

    /**
     * Jeden tick jízdy.
     */
    public void step() {
        if (endOfTrack) {
            return;
        }
        RailInfo rail = rails.railAt(railBlock);
        if (rail == null) {
            endOfTrack = true;
            speed = 0;
            return;
        }

        // --- Rychlost: powered rail, svah, tření --------------------------------
        if (rail.poweredRail()) {
            if (rail.powered()) {
                speed = Math.min(MAX_SPEED, speed + POWERED_ACCEL);
            } else {
                speed *= 0.5; // brzdicí (nenapájená) napájecí kolej
                if (speed < 0.02) {
                    speed = 0;
                }
            }
        }
        Cardinal ascending = rail.ascendingToward();
        if (ascending != null) {
            speed += direction == ascending ? -SLOPE_ACCEL : SLOPE_ACCEL;
            if (speed < 0) {
                // Nevyjel kopec – otočit a sjíždět zpátky.
                speed = -speed;
                direction = direction.opposite();
            }
        }
        speed = Math.min(MAX_SPEED, speed * FRICTION);

        stoppedTicks = speed < 0.005 ? stoppedTicks + 1 : 0;

        // --- Posun po kolejích (po hranicích bloků) ------------------------------
        double remaining = speed;
        int guard = 0;
        while (remaining > 1.0E-4 && !endOfTrack && guard++ < 8) {
            remaining = advance(remaining, rail);
            rail = rails.railAt(railBlock);
            if (rail == null) {
                endOfTrack = true;
                speed = 0;
            }
        }
    }

    /**
     * Posune vozík v aktuálním bloku, případně přejde do dalšího.
     *
     * @return nespotřebovaná vzdálenost
     */
    private double advance(double remaining, RailInfo rail) {
        boolean alongX = direction.dx() != 0;
        double axisPos = alongX ? position.x() : position.z();
        double blockBase = alongX ? railBlock.x() : railBlock.z();
        boolean positive = (alongX ? direction.dx() : direction.dz()) > 0;

        double edge = positive ? blockBase + 1 : blockBase;
        double distToEdge = positive ? edge - axisPos : axisPos - edge;

        double stepDist = Math.min(remaining, Math.max(0, distToEdge));
        axisPos += positive ? stepDist : -stepDist;
        applyAxisPosition(alongX, axisPos, rail);
        remaining -= stepDist;

        if (remaining <= 1.0E-4 || distToEdge > stepDist) {
            return 0;
        }

        // --- Přechod do sousedního bloku -----------------------------------------
        BlockPos next = railBlock.offset(direction.dx(), 0, direction.dz());
        RailInfo nextRail = rails.railAt(next);
        if (nextRail == null) {
            // Sjezd ze stoupání dolů, nebo nájezd na stoupání nahoru.
            RailInfo below = rails.railAt(next.down());
            RailInfo above = rails.railAt(next.up());
            if (below != null) {
                next = next.down();
                nextRail = below;
            } else if (above != null && rail.ascendingToward() == direction) {
                next = next.up();
                nextRail = above;
            } else {
                // Konec trati – zastavit těsně před hranou.
                endOfTrack = true;
                speed = 0;
                return 0;
            }
        }
        Cardinal exit = nextRail.exitFor(direction);
        if (exit == null) {
            endOfTrack = true; // kolej nenavazuje (boční nájezd do cizí trati)
            speed = 0;
            return 0;
        }
        railBlock = next;
        direction = exit;
        // Vycentrovat na osu nové koleje (kolmá souřadnice do středu bloku).
        position = centered(railBlock, nextRail, position);
        return remaining;
    }

    /** Zapíše posun po ose jízdy + dopočítá výšku (svahy). */
    private void applyAxisPosition(boolean alongX, double axisPos, RailInfo rail) {
        double x = alongX ? axisPos : railBlock.x() + 0.5;
        double z = alongX ? railBlock.z() + 0.5 : axisPos;
        position = new Vec3(x, heightAt(rail, x, z), z);
    }

    /** Výška vozíku: rovina, nebo interpolace na svahu. */
    private double heightAt(RailInfo rail, double x, double z) {
        Cardinal ascending = rail.ascendingToward();
        if (ascending == null) {
            return railBlock.y() + CART_Y_OFFSET;
        }
        double frac = switch (ascending) {
            case EAST -> x - railBlock.x();
            case WEST -> 1 - (x - railBlock.x());
            case SOUTH -> z - railBlock.z();
            case NORTH -> 1 - (z - railBlock.z());
        };
        return railBlock.y() + CART_Y_OFFSET + Math.max(0, Math.min(1, frac));
    }

    /** Pozice vycentrovaná na osu koleje. */
    private Vec3 centered(BlockPos block, RailInfo rail, Vec3 reference) {
        boolean alongX = direction.dx() != 0;
        double x = alongX ? clampInBlock(reference.x(), block.x()) : block.x() + 0.5;
        double z = alongX ? block.z() + 0.5 : clampInBlock(reference.z(), block.z());
        return new Vec3(x, heightAt(rail, x, z), z);
    }

    private static double clampInBlock(double value, int blockCoord) {
        return Math.max(blockCoord + 0.01, Math.min(blockCoord + 0.99, value));
    }

    /** Pokračuje trať za daným směrem? (pro volbu výchozího směru jízdy) */
    private boolean railContinues(BlockPos from, Cardinal side) {
        BlockPos next = from.offset(side.dx(), 0, side.dz());
        return rails.railAt(next) != null
                || rails.railAt(next.down()) != null
                || rails.railAt(next.up()) != null;
    }
}
