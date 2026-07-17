package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;

/**
 * Přemostění tekutiny (láva, hluboká voda) nebo propasti jedním směrem.
 *
 * <p>Klasická hráčská technika: stoupnout na hranu, položit blok před sebe
 * (do hladiny tekutiny nebo do vzduchu), přejít na něj a opakovat, dokud se
 * nedosáhne pevného břehu. Task pokládá segmenty přes {@link PlaceBlockTask}
 * (míření → klik → ověření) a mezi segmenty bota sám posouvá – proto jako
 * jediný task využívá {@link #move()}.</p>
 *
 * <p>Dvě geometrie hladiny: tekutina o úroveň níž (deck v úrovni hladiny,
 * chůze rovně) a tekutina v úrovni nohou (deck v úrovni nohou, výstup o blok
 * výš). Task končí úspěchem na pevném břehu, neúspěchem při vyčerpání bloků,
 * segmentů nebo časového rozpočtu.</p>
 */
public final class BridgeTask implements BotTask {

    /** Nejvýš tolik položených segmentů (šířka překonatelné překážky). */
    private static final int MAX_SEGMENTS = 12;
    /** Tvrdý časový strop celého mostu (ticky) – pojistka proti zacyklení. */
    private static final int BUDGET_TICKS = 1200;
    /** Tolerance dosažení středu segmentu při přechodu (bloky, vodorovně). */
    private static final double ADVANCE_TOLERANCE = 0.35;

    private enum Phase { PLAN, PLACE, ADVANCE, DONE }

    private final int sx;
    private final int sz;

    private Phase phase = Phase.PLAN;
    private PlaceBlockTask placing;
    private BlockPos deck;
    private MoveInput move = MoveInput.IDLE;
    private int segments;
    private int ticks;
    private boolean succeeded;

    /**
     * @param sx krok mostu po ose X (-1/0/1); právě jedna osa nenulová
     * @param sz krok mostu po ose Z (-1/0/1)
     */
    public BridgeTask(int sx, int sz) {
        this.sx = sx;
        this.sz = sz;
    }

    /** @return {@code true} pokud most došel na pevný břeh */
    public boolean succeeded() {
        return succeeded;
    }

    @Override
    public MoveInput move() {
        return move;
    }

    @Override
    public boolean tick(BotContext ctx) {
        move = MoveInput.IDLE;
        if (ctx.worldView() == null || ++ticks > BUDGET_TICKS) {
            phase = Phase.DONE;
            return true;
        }
        BlockPos feet = ctx.position().toBlockPos();

        switch (phase) {
            case PLAN -> {
                BlockPos front = feet.offset(sx, 0, sz);
                BlockTraits frontTraits = ctx.worldView().traitsAt(front);
                BlockTraits frontDown = ctx.worldView().traitsAt(front.down());

                // Pevný břeh před nosem → hotovo (navigace si cestu přepočítá).
                if (frontDown.solid() && frontTraits.passable()) {
                    succeeded = segments > 0;
                    phase = Phase.DONE;
                    return true;
                }
                if (segments >= MAX_SEGMENTS) {
                    phase = Phase.DONE;
                    return true;
                }
                // Deck: tekutina v úrovni nohou → pokládat do ní (výstup +1);
                // jinak o úroveň níž (hladina/propast pod hranou).
                deck = frontTraits.liquid() ? front : front.down();
                if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
                    phase = Phase.DONE; // došly bloky – most končí v půlce
                    return true;
                }
                placing = new PlaceBlockTask(deck);
                phase = Phase.PLACE;
            }
            case PLACE -> {
                if (!placing.tick(ctx)) {
                    return false; // míří/klika/ověřuje
                }
                placing = null;
                if (!ctx.worldView().traitsAt(deck).solid()) {
                    phase = Phase.DONE; // položení nevyšlo (entita, dosah…)
                    return true;
                }
                segments++;
                phase = Phase.ADVANCE;
            }
            case ADVANCE -> {
                Vec3 center = deck.up().center();
                Vec3 delta = center.sub(ctx.position());
                if (delta.horizontalLength() < ADVANCE_TOLERANCE) {
                    phase = Phase.PLAN; // stojíme na novém segmentu → další
                    return false;
                }
                // Krok na čerstvý segment; deck v úrovni nohou chce výskok.
                boolean jump = deck.y() >= feet.y();
                move = new MoveInput(delta.horizontal().normalized(), false, jump, false);
                return false;
            }
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (placing != null) {
            placing.cancel(ctx);
            placing = null;
        }
        phase = Phase.DONE;
    }
}
