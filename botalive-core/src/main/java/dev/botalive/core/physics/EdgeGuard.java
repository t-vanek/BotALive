package dev.botalive.core.physics;

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
 * pohyb je opatrný – bez sprintu a skoku. Buňka ani přistání s lávou/ohněm
 * se za bezpečné nepovažují nikdy.</p>
 *
 * <p>Sdílená sonda podlahy ({@link #safeAhead}) slouží i navigátoru
 * (brzdění u hran při následování cesty). Čistá třída bez stavů –
 * jednotkově testovatelná nad {@code FakeWorldView}.</p>
 */
public final class EdgeGuard {

    /** Do jaké hloubky pod hranou se hledá podlaha, než je krok „přes okraj". */
    public static final int SCAN_DEPTH = 5;

    /**
     * Hloubka smrtícího pádu – práh mírné (overworldové) varianty
     * {@link #applyLethal}. Drží se nad prahem volby povahy v plánovači
     * (rokle ~12 je legitimní seskok odvážných).
     */
    public static final int LETHAL_DEPTH = 20;

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
        return apply(world, position, input, SCAN_DEPTH);
    }

    /**
     * Mírná varianta pro overworld: zasahuje jen před pádem přes
     * {@link #LETHAL_DEPTH} bloků (nebo lávou ve sloupci) – běžné seskoky
     * nechává být, takže zavedené chování (přiblížení přes seskok, přímočarý
     * útěk) se nemění. Provozní nález: dva pády na smrt při přímém pohybu
     * u podzemních srázů, kde End-only ochrana nebyla.
     *
     * @param world    svět bota ({@code null} = bez kontroly)
     * @param position pozice bota (nohy)
     * @param input    zamýšlený pohyb
     * @return bezpečný pohyb (původní, otočený, nebo {@link MoveInput#IDLE})
     */
    public static MoveInput applyLethal(WorldView world, Vec3 position, MoveInput input) {
        return apply(world, position, input, LETHAL_DEPTH);
    }

    private static MoveInput apply(WorldView world, Vec3 position, MoveInput input,
                                   int scanDepth) {
        if (world == null || input == null || input.direction().horizontalLength() < 1.0E-4) {
            return input;
        }
        Vec3 direction = input.direction().horizontal().normalized();
        if (safeAhead(world, position, direction, scanDepth)) {
            return input;
        }
        for (double degrees : TURNS) {
            Vec3 turned = rotate(direction, Math.toRadians(degrees));
            if (safeAhead(world, position, turned, scanDepth)) {
                // Odklon je opatrný krok podél hrany – bez sprintu a skoku.
                return new MoveInput(turned, false, false, input.sneak());
            }
        }
        return MoveInput.IDLE; // kolem dokola hrana – stát je bezpečnější než krok
    }

    /**
     * Má sloupec kousek před botem v daném směru dohlednou bezpečnou podlahu?
     * Láva/oheň kdekoli ve sloupci znamená „ne" – i mělká láva nad pevným
     * dnem je rozsudek, žádné přistání.
     *
     * @param world     svět
     * @param position  pozice bota (nohy)
     * @param direction jednotkový vodorovný směr
     * @param scanDepth do jaké hloubky pod hranou se hledá podlaha
     * @return {@code true} pokud tam lze šlápnout bez pádu přes {@code scanDepth}
     */
    public static boolean safeAhead(WorldView world, Vec3 position, Vec3 direction,
                                    int scanDepth) {
        BlockPos ahead = position.add(direction.mul(LOOKAHEAD)).toBlockPos();
        BlockTraits cell = world.traitsAt(ahead);
        if (cell.hazard()) {
            return false; // krok rovnou do lávy/ohně
        }
        if (cell.solid()) {
            return true; // stěna/schod – krok skončí step-upem, ne pádem
        }
        for (int depth = 1; depth <= scanDepth; depth++) {
            BlockTraits below = world.traitsAt(ahead.offset(0, -depth, 0));
            if (below.hazard()) {
                return false; // láva (i mělká nad dnem) – tudy ne
            }
            if (below.solid() || below.liquid()) {
                return true; // pevná podlaha nebo voda jistí pád
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
