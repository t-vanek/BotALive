package dev.botalive.core.physics;

import dev.botalive.core.util.Vec3;

/**
 * Pohybový záměr bota pro jeden tick – „co by hráč držel na klávesnici".
 *
 * <p>AI cíle a navigace neskládají pozice napřímo; nastavují záměr a fyzika
 * ({@link BotPhysics}) ho převede na reálný pohyb se setrvačností, gravitací
 * a kolizemi. Díky tomu pohyb působí přirozeně a nedá se „teleportovat".</p>
 *
 * @param direction jednotkový směr pohybu ve vodorovné rovině (nebo {@link Vec3#ZERO})
 * @param sprint    sprintovat
 * @param jump      chce skočit (uplatní se jen na zemi / ve vodě)
 * @param sneak     plížit se
 */
public record MoveInput(Vec3 direction, boolean sprint, boolean jump, boolean sneak) {

    /** Klidový vstup – bot stojí. */
    public static final MoveInput IDLE = new MoveInput(Vec3.ZERO, false, false, false);

    /**
     * @param direction směr pohybu
     * @return vstup „jdi daným směrem"
     */
    public static MoveInput walk(Vec3 direction) {
        return new MoveInput(direction.horizontal().normalized(), false, false, false);
    }

    /**
     * @param direction směr pohybu
     * @param sprint    sprintovat
     * @param jump      skočit
     * @return plně určený vstup
     */
    public static MoveInput of(Vec3 direction, boolean sprint, boolean jump) {
        return new MoveInput(direction.horizontal().normalized(), sprint, jump, false);
    }
}
