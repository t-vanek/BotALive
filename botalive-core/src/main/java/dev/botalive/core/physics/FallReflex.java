package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Pádový reflex – instinkt „nešlápnout do prázdna".
 *
 * <p>Když bot jde po zemi mimo naplánovanou cestu (posun davem, ruční cíl,
 * postrčení) a přímo před ním je nebezpečný sráz, reflexivně se přikrčí.
 * Plížení pak přes {@link BotPhysics} ochranu hrany bota zadrží u okraje,
 * takže nespadne a nezraní se.</p>
 *
 * <p>Řízený pohyb reflex <b>nepřebíjí</b>: navigace povoluje jen bezpečné
 * seskoky (do {@link #SAFE_DROP} bloků nebo do vody) a zdolávání překážek
 * (most, žebřík, loď) i cíle s vlastním pohybem vědí, co dělají – jinak by se
 * bot u každého schodu zbytečně zaseknul.</p>
 */
public final class FallReflex {

    /** Nejvyšší seskok bez poškození (shodně s pathfinderem). */
    private static final int SAFE_DROP = 3;
    private static final double EPS = 1.0E-4;

    private FallReflex() {
    }

    /**
     * Aplikuje pádový reflex na pohybový vstup.
     *
     * @param input    vstup od navigace/cíle (po vyhýbání davu)
     * @param managed  pohyb řídí navigace/úloha/cíl – seskoky jsou zamýšlené, reflex mlčí
     * @param onGround stojí bot na zemi (ve vzduchu/vodě reflex mlčí)
     * @param position pozice nohou bota
     * @param world    pohled na svět
     * @return vstup s vynuceným plížením u nebezpečné hrany, jinak beze změny
     */
    public static MoveInput apply(MoveInput input, boolean managed, boolean onGround,
                                  Vec3 position, WorldView world) {
        if (world == null || managed || !onGround || input.sneak()) {
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
            if (t.liquid()) {
                // Voda dopad tlumí; láva je jistá smrt.
                return t.hazard();
            }
            if (t.solid()) {
                return drop > SAFE_DROP;
            }
            support = support.down();
        }
        // Do hloubky SAFE_DROP+1 nic pevného ani voda → bezedno, radši opatrně.
        return true;
    }
}
