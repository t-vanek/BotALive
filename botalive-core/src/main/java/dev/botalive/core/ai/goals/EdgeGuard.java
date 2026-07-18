package dev.botalive.core.ai.goals;

import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Ochrana hran pro přímý (nenaplánovaný) pohyb – útěk, strafing, úhyby.
 *
 * <p>Pathfinding se srázům vyhýbá sám, ale panika ({@code SurviveGoal}),
 * bojový strafing a úhyby před dračím dechem posílají pohyb napřímo – a na
 * ostrovech Endu vede každý špatný krok do voidu. Guard směr před použitím
 * prověří: chybí-li kousek před botem do {@link #SCAN_DEPTH} bloků pod nohama
 * pevná podlaha (nebo bezpečná voda), otočí pohyb do nejbližšího bezpečného
 * směru; když bezpečný směr není (osamělý pilíř), bot se zastaví. Otočený
 * pohyb je opatrný – bez sprintu a skoku.</p>
 *
 * <p>Čistá třída bez stavů – jednotkově testovatelná nad {@code FakeWorldView}.
 * Užitečná ve všech dimenzích (útesy zabíjejí i v overworldu), kritická
 * v Endu.</p>
 */
public final class EdgeGuard {

    /** Do jaké hloubky pod hranou se hledá podlaha, než je krok „přes okraj". */
    static final int SCAN_DEPTH = 5;

    /** Jak daleko před botem se sloupec prověřuje (bloky). */
    private static final double LOOKAHEAD = 0.9;

    /** Zkoušené odklony od nebezpečného směru (stupně, od nejmenšího). */
    private static final double[] TURNS = {45, -45, 90, -90, 135, -135, 180};

    private EdgeGuard() {
    }

    /**
     * Prověří pohybový vstup proti hranám a případně ho otočí či zastaví.
     *
     * @param world    svět bota ({@code null} = bez kontroly)
     * @param position pozice bota (nohy)
     * @param input    zamýšlený pohyb
     * @return bezpečný pohyb (původní, otočený, nebo {@link MoveInput#IDLE})
     */
    public static MoveInput apply(WorldView world, Vec3 position, MoveInput input) {
        if (world == null || input == null || input.direction().horizontalLength() < 1.0E-4) {
            return input;
        }
        Vec3 direction = input.direction().horizontal().normalized();
        if (safeAhead(world, position, direction)) {
            return input;
        }
        for (double degrees : TURNS) {
            Vec3 turned = rotate(direction, Math.toRadians(degrees));
            if (safeAhead(world, position, turned)) {
                // Odklon je opatrný krok podél hrany – bez sprintu a skoku.
                return new MoveInput(turned, false, false, input.sneak());
            }
        }
        return MoveInput.IDLE; // kolem dokola hrana – stát je bezpečnější než krok
    }

    /**
     * Má sloupec kousek před botem v daném směru dohlednou podlahu?
     *
     * @param world     svět
     * @param position  pozice bota (nohy)
     * @param direction jednotkový vodorovný směr
     * @return {@code true} pokud tam lze šlápnout bez pádu přes {@link #SCAN_DEPTH}
     */
    static boolean safeAhead(WorldView world, Vec3 position, Vec3 direction) {
        BlockPos ahead = position.add(direction.mul(LOOKAHEAD)).toBlockPos();
        BlockTraits cell = world.traitsAt(ahead);
        if (cell.solid()) {
            return true; // stěna/schod – krok skončí step-upem, ne pádem
        }
        for (int depth = 1; depth <= SCAN_DEPTH; depth++) {
            BlockTraits below = world.traitsAt(ahead.offset(0, -depth, 0));
            if (below.solid()) {
                return true;
            }
            // Voda pád jistí; láva je liquid i hazard – ta nejistí nic.
            if (below.liquid() && !below.hazard()) {
                return true;
            }
        }
        return false;
    }

    /** Otočí vodorovný vektor o daný úhel (radiány). */
    private static Vec3 rotate(Vec3 direction, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(direction.x() * cos - direction.z() * sin, 0,
                direction.x() * sin + direction.z() * cos).normalized();
    }
}
