package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Pádový reflex – instinkty kolem pádů.
 *
 * <ul>
 *   <li><b>Na zemi:</b> „nešlápnout do prázdna". Když bot jde po zemi mimo
 *       řízený pohyb (posun davem, postrčení) a přímo před ním je nebezpečný
 *       sráz, reflexivně se přikrčí – plížení pak přes {@link BotPhysics}
 *       ochranu hrany bota zadrží u okraje.</li>
 *   <li><b>Ve vzduchu:</b> „clutch". Když už bot padá do nebezpečné hloubky,
 *       kormidluje vzdušným řízením k nejbližší vodě nebo měkkému bloku
 *       v dosahu – z pádu, který by bolel, udělá pád do bezpečí.</li>
 * </ul>
 *
 * <p>Řízený pohyb reflex na zemi <b>nepřebíjí</b>: navigace povoluje jen
 * bezpečné seskoky (do {@link #SAFE_DROP} bloků nebo do vody) a zdolávání
 * překážek (most, žebřík, loď) i cíle s vlastním pohybem vědí, co dělají –
 * jinak by se bot u každého schodu zbytečně zaseknul. Vzdušný clutch se ale
 * uplatní vždy: pád hlubší než {@link #SAFE_DROP} mimo vodu není nikdy
 * v plánu, takže zásah nikomu neškodí.</p>
 */
public final class FallReflex {

    /** Nejvyšší seskok bez poškození (shodně s pathfinderem). */
    private static final int SAFE_DROP = 3;
    /** Vodorovný dosah hledání záchrany při pádu (vzdušné řízení je slabé). */
    private static final int CLUTCH_RADIUS = 3;
    /** Jak hluboko pod botem hledat dopadovou plochu při pádu. */
    private static final int CLUTCH_SCAN_DEPTH = 24;
    private static final double EPS = 1.0E-4;

    private FallReflex() {
    }

    /**
     * Aplikuje pádový reflex na pohybový vstup.
     *
     * @param input        vstup od navigace/cíle (po vyhýbání davu)
     * @param managed      pohyb řídí navigace/úloha/cíl – seskoky jsou zamýšlené,
     *                     pozemní část reflexu mlčí (clutch zasahuje vždy)
     * @param onGround     stojí bot na zemi
     * @param fallDistance kumulovaná výška probíhajícího pádu (bloky)
     * @param position     pozice nohou bota
     * @param world        pohled na svět
     * @return upravený vstup (beze změny, když reflex nezasahuje)
     */
    public static MoveInput apply(MoveInput input, boolean managed, boolean onGround,
                                  double fallDistance, Vec3 position, WorldView world) {
        if (world == null) {
            return input;
        }
        // Ve vzduchu: pád už je hlubší, než je zdrávo → kormidlovat k záchraně.
        if (!onGround && fallDistance > SAFE_DROP) {
            return steerToRescue(input, position, world);
        }
        if (managed || !onGround || input.sneak()) {
            return input;
        }
        Vec3 dir = input.direction();
        if (dir.horizontalLength() < EPS) {
            return input;
        }
        if (dangerousDropAhead(world, position.toBlockPos(), dir)) {
            // Přikrčit, nesprintovat a neskákat – jen opatrně stát u hrany.
            return new MoveInput(dir, false, false, true);
        }
        return input;
    }

    /**
     * Kormidlování při pádu: míří-li bot do tvrdého dopadu, stočí ho vzdušné
     * řízení k nejbližšímu sloupci s vodou nebo měkkým blokem v dosahu
     * {@link #CLUTCH_RADIUS}. Když padá do bezpečí (nebo žádné v dosahu není),
     * vstup se nemění.
     */
    private static MoveInput steerToRescue(MoveInput input, Vec3 position, WorldView world) {
        BlockPos feet = position.toBlockPos();
        if (isRescueColumn(world, feet)) {
            return input; // padáme do vody/na měkké – nerušit
        }
        BlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;
        for (int dx = -CLUTCH_RADIUS; dx <= CLUTCH_RADIUS; dx++) {
            for (int dz = -CLUTCH_RADIUS; dz <= CLUTCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int distSq = dx * dx + dz * dz;
                if (distSq >= bestDistSq) {
                    continue;
                }
                BlockPos candidate = feet.offset(dx, 0, dz);
                if (isRescueColumn(world, candidate)) {
                    bestDistSq = distSq;
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return input; // žádná záchrana v dosahu – aspoň nezhoršovat
        }
        // Mířit na střed záchranného sloupce (přesně, ne po blocích).
        Vec3 steer = new Vec3(best.x() + 0.5 - position.x(), 0, best.z() + 0.5 - position.z())
                .horizontal().normalized();
        return new MoveInput(steer, false, false, false);
    }

    /**
     * Skončí pád v tomto sloupci bezpečně? Sken dolů až {@link #CLUTCH_SCAN_DEPTH}
     * bloků: první neprůchozí blok rozhoduje – voda (bez lávy) nebo měkký blok
     * je záchrana, cokoli jiného (tvrdý blok, láva, neznámo, bezedno) ne.
     */
    private static boolean isRescueColumn(WorldView world, BlockPos top) {
        BlockPos pos = top;
        for (int depth = 0; depth <= CLUTCH_SCAN_DEPTH; depth++) {
            BlockTraits t = world.traitsAt(pos);
            if (t.powderSnow()) {
                return true; // prašan pád utlumí (a reflex pak bota vyhrabe)
            }
            if (t.liquid()) {
                return !t.hazard();
            }
            if (t.solid()) {
                return t.softLanding();
            }
            if (!t.passable()) {
                return false; // neznámý chunk – nespoléhat
            }
            pos = pos.down();
        }
        return false;
    }

    /**
     * Je v následujícím kroku (podle směru pohybu) nebezpečný sráz? Zeď ani
     * bezpečný seskok (≤ {@link #SAFE_DROP}, případně do vody) se nepočítá.
     */
    private static boolean dangerousDropAhead(WorldView world, BlockPos feet, Vec3 dir) {
        int sx = (int) Math.round(dir.x());
        int sz = (int) Math.round(dir.z());
        if (sx == 0 && sz == 0) {
            return false;
        }
        BlockPos ahead = feet.offset(sx, 0, sz);
        // Zeď/schod ve směru chůze není pád – to řeší kolize a step-up.
        if (world.traitsAt(ahead).solid() || world.traitsAt(ahead.up()).solid()) {
            return false;
        }
        // Sken dolů od podlahy sousední buňky: kolik bloků by bot spadl?
        BlockPos support = ahead.down();
        for (int drop = 0; drop <= SAFE_DROP + 1; drop++) {
            BlockTraits t = world.traitsAt(support);
            if (t.powderSnow()) {
                return false; // prašan pád utlumí – bezpečný seskok
            }
            if (t.liquid()) {
                // Voda dopad tlumí; láva je jistá smrt.
                return t.hazard();
            }
            if (t.solid()) {
                return drop > SAFE_DROP && !t.softLanding();
            }
            support = support.down();
        }
        // Do hloubky SAFE_DROP+1 nic pevného ani voda → bezedno, radši opatrně.
        return true;
    }
}
