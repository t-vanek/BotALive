package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;

/**
 * Průchod nether portálem: bot dojde do vstupní buňky, zůstane stát
 * v portálových blocích a čeká, až ho server přenese (vanilla ~4 s;
 * přenos poznáme podle změny světa ve {@code worldView}).
 *
 * <p>Task nepoužívá navigátor – cíl bota nejdřív dovede k portálu na pár
 * bloků a task dojde zbytek vlastním {@link #move()} (do portálu se vchází
 * krokem, případně výskokem na spodní řadu rámu). Po přenosu server bota
 * teleportuje a {@code BotImpl} přepne world view; task to ohlásí přes
 * {@link #transited()}.</p>
 */
public final class UsePortalTask implements BotTask {

    /** Tvrdý strop celého průchodu (ticky) – vanilla přenos je ~80 ticků. */
    private static final int BUDGET_TICKS = 400;

    /** Jak daleko od vstupní buňky se task ještě obtěžuje dojít sám. */
    private static final double MAX_WALK_DISTANCE_SQ = 5 * 5;

    private final BlockPos entry;

    private String fromWorld;
    private MoveInput move = MoveInput.IDLE;
    private int ticks;
    private boolean transited;
    private boolean done;

    /**
     * @param entry vstupní buňka portálu (spodní patro vnitřku, kde stojí nohy)
     */
    public UsePortalTask(BlockPos entry) {
        this.entry = entry;
    }

    /** @return {@code true} pokud bot prošel do jiného světa */
    public boolean transited() {
        return transited;
    }

    @Override
    public MoveInput move() {
        return move;
    }

    @Override
    public boolean tick(BotContext ctx) {
        move = MoveInput.IDLE;
        if (done || ctx.worldView() == null) {
            return true;
        }
        if (fromWorld == null) {
            fromWorld = ctx.worldView().worldName();
        }
        if (++ticks > BUDGET_TICKS) {
            done = true;
            return true;
        }

        // Přenos proběhl – world view už kouká do jiného světa.
        if (!ctx.worldView().worldName().equals(fromWorld)) {
            transited = true;
            done = true;
            return true;
        }

        Vec3 position = ctx.position();
        Vec3 target = entry.center();
        double distSq = position.distanceSquared(target);
        BlockPos feet = position.toBlockPos();

        // Uvnitř portálu: stát a čekat (nether ~4 s nehybnosti, end hned).
        if (feet.equals(entry) || ctx.worldView().traitsAt(feet).portal()) {
            return false;
        }

        // Moc daleko – sem měl bota dovést cíl navigací, task to nezachrání.
        if (distSq > MAX_WALK_DISTANCE_SQ) {
            done = true;
            return true;
        }

        // Dojít (případně vyskočit) do vstupní buňky.
        Vec3 dir = target.sub(position);
        boolean jump = entry.y() > feet.y() && dir.horizontalLength() < 1.2;
        move = new MoveInput(dir.horizontal().normalized(), false, jump, false);
        return false;
    }

    @Override
    public void cancel(BotContext ctx) {
        done = true;
    }
}
