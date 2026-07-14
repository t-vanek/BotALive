package dev.botalive.core.combat;

import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;

/**
 * Střelba lukem a kuší.
 *
 * <p><b>Luk:</b> natažení (UseItem) → držení s mířením ~20–28 ticků (plné
 * nabití + lidská odchylka) → výstřel (ReleaseUseItem). <b>Kuše:</b> nabití
 * (UseItem + Release po nabití) → míření → výstřel (UseItem).
 * Míření předsazuje cíl podle odhadu jeho rychlosti ({@link TrackedEntity})
 * a kompenzuje balistický pokles šípu; trvalou nepřesnost dodává
 * {@link Humanizer} (chyba míření, šum otáčení).</p>
 */
public final class RangedAttack {

    /** Rychlost šípu z plně nataženého luku (bloky/tick). */
    private static final double ARROW_SPEED = 3.0;

    /** Gravitační pokles šípu (bloky/tick²). */
    private static final double ARROW_GRAVITY = 0.05;

    private enum State { IDLE, BOW_DRAWING, CROSSBOW_CHARGING, CROSSBOW_LOADED, COOLDOWN }

    private final BotActions actions;
    private final Humanizer humanizer;
    private final BotRandom rng;
    private final InventoryHelper inventory;

    private State state = State.IDLE;
    private int stateTicks;
    private int requiredTicks;
    private boolean usingCrossbow;

    /**
     * @param actions   akční primitivy
     * @param humanizer humanizace míření
     * @param rng       per-bot náhoda
     * @param inventory výběr zbraně v hotbaru
     */
    public RangedAttack(BotActions actions, Humanizer humanizer, BotRandom rng,
                        InventoryHelper inventory) {
        this.actions = actions;
        this.humanizer = humanizer;
        this.rng = rng;
        this.inventory = inventory;
    }

    /**
     * @param snapshot server-side snapshot
     * @return {@code true} pokud má bot v hotbaru luk/kuši a šípy
     */
    public boolean canUse(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        boolean weapon = snapshot.findHotbarSlot(m -> m == Material.BOW || m == Material.CROSSBOW) >= 0;
        boolean arrows = snapshot.hasItem(m -> m == Material.ARROW
                || m == Material.SPECTRAL_ARROW || m == Material.TIPPED_ARROW);
        return weapon && arrows;
    }

    /** @return {@code true} pokud právě probíhá natahování/nabíjení */
    public boolean busy() {
        return state != State.IDLE && state != State.COOLDOWN;
    }

    /**
     * Přeruší střelbu (cíl se přiblížil, přechod na melee).
     */
    public void reset() {
        if (state == State.BOW_DRAWING || state == State.CROSSBOW_CHARGING) {
            actions.releaseUseItem(); // pustit tětivu/kuši, i nedotaženou
        }
        state = State.IDLE;
        stateTicks = 0;
    }

    /**
     * Jeden tick střelby.
     *
     * @param position pozice bota (nohy)
     * @param target   cíl
     * @param snapshot server-side snapshot
     * @return pohybový vstup (při střelbě bot stojí/úkroky)
     */
    public MoveInput tick(Vec3 position, TrackedEntity target, ServerSideView.Snapshot snapshot) {
        aim(position, target);
        stateTicks++;

        switch (state) {
            case IDLE -> {
                int slot = snapshot.findHotbarSlot(m -> m == Material.BOW || m == Material.CROSSBOW);
                if (slot < 0) {
                    return MoveInput.IDLE;
                }
                usingCrossbow = snapshot.hotbar()[slot] == Material.CROSSBOW;
                actions.selectHotbar(slot);
                actions.useItem(humanizer.yaw(), humanizer.pitch());
                state = usingCrossbow ? State.CROSSBOW_CHARGING : State.BOW_DRAWING;
                stateTicks = 0;
                // plné natažení 20 ticků + lidské zaváhání
                requiredTicks = 20 + rng.rangeInt(0, 10);
            }
            case BOW_DRAWING -> {
                if (stateTicks >= requiredTicks) {
                    actions.releaseUseItem(); // výstřel
                    enterCooldown();
                }
            }
            case CROSSBOW_CHARGING -> {
                if (stateTicks >= 25 + rng.rangeInt(0, 8)) {
                    actions.releaseUseItem(); // kuše nabita
                    state = State.CROSSBOW_LOADED;
                    stateTicks = 0;
                    requiredTicks = rng.rangeInt(4, 14); // zamíření před výstřelem
                }
            }
            case CROSSBOW_LOADED -> {
                if (stateTicks >= requiredTicks) {
                    actions.useItem(humanizer.yaw(), humanizer.pitch()); // výstřel
                    enterCooldown();
                }
            }
            case COOLDOWN -> {
                if (stateTicks >= requiredTicks) {
                    state = State.IDLE;
                    stateTicks = 0;
                }
            }
        }

        // Při natahování stát/drobné úkroky do strany – jako hráč u střelby.
        if (busy() && rng.chance(0.15)) {
            Vec3 toTarget = target.position().sub(position).horizontal().normalized();
            Vec3 side = new Vec3(-toTarget.z(), 0, toTarget.x()).mul(rng.chance(0.5) ? 1 : -1);
            return MoveInput.of(side.mul(0.3), false, false);
        }
        return MoveInput.IDLE;
    }

    private void enterCooldown() {
        state = State.COOLDOWN;
        stateTicks = 0;
        requiredTicks = rng.rangeInt(10, 30);
    }

    /** Míření s predikcí pohybu cíle a kompenzací poklesu šípu. */
    private void aim(Vec3 position, TrackedEntity target) {
        Vec3 eyes = position.add(0, 1.62, 0);
        Vec3 targetPos = target.position().add(0, 1.2, 0);
        double distance = eyes.distance(targetPos);
        double flightTicks = distance / ARROW_SPEED;

        // Předsazení podle odhadu rychlosti + balistická kompenzace.
        Vec3 lead = target.velocityEstimate().mul(flightTicks);
        double drop = 0.5 * ARROW_GRAVITY * flightTicks * flightTicks;
        Vec3 aimPoint = targetPos.add(lead.x(), lead.y() + drop, lead.z());
        humanizer.lookAt(eyes, aimPoint);
    }
}
