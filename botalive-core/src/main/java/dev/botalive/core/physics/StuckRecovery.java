package dev.botalive.core.physics;

import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;

/**
 * Vyproštění bota z tvrdého zaseknutí – repertoár lidských mikro-manévrů, které
 * bot zkusí <b>dřív</b>, než watchdog sáhne k nouzovému teleportu.
 *
 * <p>Watchdog {@code BotImpl}u pozná bota, který se dlouho nepohnul (kolizní
 * desync, uvíznutí na entitě, geometrie, kterou navigace neobejde). Místo aby
 * hned „blikl" na sousední buňku (nepřirozené), spustí <b>epizodu vyproštění</b>:
 * bot postupně zkusí <em>couvnout → uhnout do strany → couvnout s poskokem →
 * zavrtět se náhodně</em>. Když se tím uvolní, pokračuje normálně; když ne,
 * eskalace k teleportu zůstává jako poslední instance.</p>
 *
 * <p>Čistá, jednotkově testovaná třída: vlastní jen fázi epizody a z okolní
 * průchodnosti (které směry jsou volné) skládá {@link MoveInput}. Bezpečnostní
 * reflexy (láva, hrana) mají nad výsledkem dál poslední slovo.</p>
 */
public final class StuckRecovery {

    /** Kolik ticků trvá jeden manévr (~0,4 s). */
    private static final int MANEUVER_TICKS = 8;

    /** Repertoár manévrů v pořadí eskalace. */
    private enum Maneuver { BACK_UP, STRAFE, BACK_HOP, WIGGLE }

    private static final Maneuver[] SEQUENCE = {
            Maneuver.BACK_UP, Maneuver.STRAFE, Maneuver.BACK_HOP, Maneuver.WIGGLE};

    private boolean active;
    private int index;
    private int ticksLeft;
    private MoveInput currentInput = MoveInput.IDLE;

    /** @return probíhá epizoda vyproštění? */
    public boolean active() {
        return active;
    }

    /** Spustí novou epizodu vyproštění (od prvního manévru). */
    public void begin() {
        active = true;
        index = 0;
        ticksLeft = 0;
    }

    /** Ukončí epizodu (uvolnění, teleport, pauza). */
    public void stop() {
        active = false;
        index = 0;
        ticksLeft = 0;
        currentInput = MoveInput.IDLE;
    }

    /**
     * Pohybový vstup pro tento tick. Po vyčerpání repertoáru epizoda skončí
     * a vrací {@link MoveInput#IDLE}.
     *
     * @param forward   směr, kterým se bot marně snažil jít (kam „tlačí")
     * @param backOpen  je buňka za botem průchozí?
     * @param leftOpen  je buňka vlevo od bota průchozí?
     * @param rightOpen je buňka vpravo od bota průchozí?
     * @param onGround  stojí bot na zemi (poskoky mají smysl jen tam)
     * @param rng       per-bot náhoda (nepředvídatelné zavrtění)
     * @return vstup manévru
     */
    public MoveInput tick(Vec3 forward, boolean backOpen, boolean leftOpen, boolean rightOpen,
                          boolean onGround, BotRandom rng) {
        if (!active) {
            return MoveInput.IDLE;
        }
        if (ticksLeft <= 0) {
            if (index >= SEQUENCE.length) {
                stop();
                return MoveInput.IDLE;
            }
            currentInput = build(SEQUENCE[index], forward, backOpen, leftOpen, rightOpen,
                    onGround, rng);
            ticksLeft = MANEUVER_TICKS;
            index++;
        }
        ticksLeft--;
        return currentInput;
    }

    private static MoveInput build(Maneuver maneuver, Vec3 forward, boolean backOpen,
                                   boolean leftOpen, boolean rightOpen, boolean onGround,
                                   BotRandom rng) {
        Vec3 fwd = forward.horizontalLength() > 0.01
                ? forward.horizontal().normalized() : new Vec3(0, 0, 1);
        Vec3 back = fwd.mul(-1);
        Vec3 left = new Vec3(-fwd.z(), 0, fwd.x());
        Vec3 right = new Vec3(fwd.z(), 0, -fwd.x());
        return switch (maneuver) {
            case BACK_UP -> new MoveInput(
                    firstOpen(back, backOpen, left, leftOpen, right, rightOpen, back),
                    false, false, false);
            case STRAFE -> {
                Vec3 dir = leftOpen ? left : rightOpen ? right : rng.next() < 0.5 ? left : right;
                yield new MoveInput(dir, false, false, false);
            }
            // Couvnutí s poskokem – přehoupnutí přes nízkou překážku (deska, schod).
            case BACK_HOP -> new MoveInput(back, false, onGround, false);
            // Poslední pokus: náhodné zavrtění s poskokem.
            case WIGGLE -> {
                double angle = rng.next() * Math.PI * 2;
                Vec3 dir = new Vec3(Math.cos(angle), 0, Math.sin(angle));
                yield new MoveInput(dir, false, onGround, false);
            }
        };
    }

    /** První průchozí směr z nabídky, jinak fallback. */
    private static Vec3 firstOpen(Vec3 a, boolean aOpen, Vec3 b, boolean bOpen,
                                  Vec3 c, boolean cOpen, Vec3 fallback) {
        if (aOpen) {
            return a;
        }
        if (bOpen) {
            return b;
        }
        if (cOpen) {
            return c;
        }
        return fallback;
    }
}
