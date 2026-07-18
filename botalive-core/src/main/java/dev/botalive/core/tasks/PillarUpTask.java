package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/**
 * Pilířování vzhůru – skok a blok pod vlastní nohy, dokola.
 *
 * <p>Klasická hráčská technika výstupu do otevřeného prostoru (převis, útes,
 * plošina bez stěny na žebřík): bot se dívá pod sebe, vyskočí, a když jsou
 * nohy dost vysoko nad opěrným blokem (nový blok se do prostoru vejde),
 * klikne na jeho horní stranu. Dopadne na čerstvě položený blok a opakuje,
 * dokud nedosáhne cílové výšky. Svislá analogie {@link BridgeTask}: task
 * pokládá bloky vlastní mířenou akcí a bota mezi pokládkami sám řídí –
 * proto využívá {@link #move()}.</p>
 *
 * <p>Bezpečnost: sloupec se před startem kontroluje na průchodnost (strop,
 * tekutiny, hazardy, nenačtené chunky ukončují výstup předem), během výstupu
 * se drží střed sloupce jako u šplhání – tlak do stěny by server vyhodnotil
 * jako neplatný pohyb. Task končí úspěchem v cílové výšce, neúspěchem při
 * vyčerpání bloků, pokusů nebo časového rozpočtu.</p>
 */
public final class PillarUpTask implements BotTask {

    /** Nejvyšší pilíř na jeden task (výš se plánuje nadvakrát přes replan). */
    public static final int MAX_HEIGHT = 12;
    /** Tvrdý časový strop celého výstupu (ticky) – pojistka proti zacyklení. */
    private static final int BUDGET_TICKS = 900;
    /** Kolik ticků čekat na potvrzení položeného bloku, než se skok zopakuje. */
    private static final int VERIFY_TICKS = 15;
    /** Kolik nepovedených pokusů o pokládku task snese, než to vzdá. */
    private static final int MAX_FAILED_PLACEMENTS = 4;
    /** Od jaké výšky nohou nad opěrným blokem se nový blok vejde pod nohy. */
    private static final double PLACE_CLEARANCE = 1.0;

    private enum Phase { PLAN, ASCEND, VERIFY, DONE }

    private final int requestedY;

    private Phase phase = Phase.PLAN;
    private MoveInput move = MoveInput.IDLE;
    private BlockPos col;
    private int targetY;
    private int ticks;
    private int verifyTicks;
    private int failedPlacements;
    /** Blok, na jehož horní stranu se kliká (aktuální vrchol pilíře). */
    private BlockPos support;
    /** Buňka, kde má vyrůst nový blok (nad supportem, pod nohama bota). */
    private BlockPos placeTarget;
    private boolean succeeded;

    /**
     * @param targetY cílová výška nohou; přesah nad {@link #MAX_HEIGHT} od
     *                startu se ořízne (zbytek dořeší replanning navigace)
     */
    public PillarUpTask(int targetY) {
        this.requestedY = targetY;
    }

    /** @return {@code true} pokud bot vystoupal do cílové výšky */
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
        if (col == null) {
            col = feet; // pilíř roste ve sloupci, kde bot stojí na startu
            targetY = Math.min(requestedY, feet.y() + MAX_HEIGHT);
            if (!columnClear(ctx.worldView(), feet, targetY)) {
                phase = Phase.DONE; // strop/tekutina/neznámo ve sloupci
                return true;
            }
        }

        switch (phase) {
            case PLAN -> plan(ctx, feet);
            case ASCEND -> ascend(ctx);
            case VERIFY -> verify(ctx);
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    /** Rozhodne další krok: hotovo / další skok s pokládkou. */
    private void plan(BotContext ctx, BlockPos feet) {
        if (ctx.onGround() && feet.y() >= targetY) {
            succeeded = true;
            phase = Phase.DONE;
            return;
        }
        if (failedPlacements >= MAX_FAILED_PLACEMENTS) {
            phase = Phase.DONE;
            return;
        }
        // Strop dalšího skoku (nohy +2 při výskoku o blok) musí zůstat volný –
        // svět se mohl od startu změnit.
        if (!bodyClear(ctx.worldView().traitsAt(feet.offset(0, 2, 0)))) {
            phase = Phase.DONE;
            return;
        }
        if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
            phase = Phase.DONE; // došly bloky – výstup končí (i v půlce)
            return;
        }
        support = feet.down();
        placeTarget = feet;
        verifyTicks = 0;
        phase = Phase.ASCEND;
    }

    /**
     * Výskok s pohledem dolů; v okně u vrcholu skoku (nohy ≥ 1 blok nad
     * opěrou – nový blok se pod ně vejde) klik na horní stranu opěry.
     */
    private void ascend(BotContext ctx) {
        Vec3 pos = ctx.position();
        lookDown(ctx, pos);
        // Držet střed sloupce, jinak by bot z pilíře sklouzl.
        move = new MoveInput(centering(pos), false, true, false);

        double risen = pos.y() - (support.y() + 1);
        if (risen >= PLACE_CLEARANCE) {
            ctx.actions().useItemOn(support, Direction.UP);
            phase = Phase.VERIFY;
        }
    }

    /** Čeká na potvrzení bloku pod nohama; timeout vrací do dalšího skoku. */
    private void verify(BotContext ctx) {
        Vec3 pos = ctx.position();
        lookDown(ctx, pos);
        // Bez skoku – bot dosedá na (snad) položený blok; střed držet dál.
        move = new MoveInput(centering(pos), false, false, false);

        if (ctx.worldView().traitsAt(placeTarget).solid()) {
            ctx.stats().addPlaced();
            failedPlacements = 0;
            phase = Phase.PLAN;
        } else if (++verifyTicks > VERIFY_TICKS) {
            failedPlacements++;
            phase = Phase.PLAN; // pokládka nevyšla (okno skoku, entita) – znovu
        }
    }

    /** Pohled kolmo dolů na opěrný blok (věrohodnost + přesný klik). */
    private void lookDown(BotContext ctx, Vec3 pos) {
        Vec3 eyes = pos.add(0, 1.62, 0);
        ctx.humanizer().lookAt(eyes, new Vec3(col.x() + 0.5, pos.y() - 1, col.z() + 0.5));
    }

    /** Jemná korekce směru do středu sloupce (viz šplhání po žebříku). */
    private Vec3 centering(Vec3 pos) {
        double dx = clampUnit(col.x() + 0.5 - pos.x());
        double dz = clampUnit(col.z() + 0.5 - pos.z());
        return new Vec3(dx, 0, dz);
    }

    /** Ořízne hodnotu do intervalu [-1, 1] (jednotkový směr korekce). */
    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    @Override
    public void cancel(BotContext ctx) {
        phase = Phase.DONE;
    }

    /**
     * Je sloupec pro pilíř průchozí? Od nohou po cílovou výšku (+1 na hlavu)
     * nesmí být kolize, tekutina, hazard ani nenačtený chunk – do neznáma se
     * nepilířuje.
     *
     * @param world   pohled na svět
     * @param feet    startovní pozice nohou (sloupec pilíře)
     * @param targetY cílová výška nohou
     * @return {@code true} pokud výstup dává smysl
     */
    public static boolean columnClear(WorldView world, BlockPos feet, int targetY) {
        if (targetY <= feet.y()) {
            return false;
        }
        for (int y = feet.y(); y <= targetY + 1; y++) {
            if (!bodyClear(world.traitsAt(new BlockPos(feet.x(), y, feet.z())))) {
                return false;
            }
        }
        return true;
    }

    /** Prostor bez kolize, tekutiny, hazardu a pavučin (a známý). */
    private static boolean bodyClear(BlockTraits t) {
        return t != BlockTraits.UNKNOWN && !t.hazard() && !t.web()
                && !t.liquid() && t.noCollision();
    }
}
