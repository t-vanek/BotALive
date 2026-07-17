package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/**
 * Přelezení svislé stěny vyšší než jeden blok pomocí žebříku.
 *
 * <p>Klasická hráčská technika: k pevné stěně před botem se zdola nahoru lepí
 * žebříky (do vlastního sloupce bota, přichycené na přivrácenou stěnu), po
 * kterých bot leze vzhůru, dokud se nedostane nad hranu stěny a nevstoupí na
 * ni. Vertikální analogie {@link BridgeTask}: task pokládá žebříky vlastní
 * mířenou akcí (míření → klik → ověření) a mezi pokládkami bota sám posouvá –
 * proto jako {@link BridgeTask} využívá {@link #move()}.</p>
 *
 * <p>Žebřík ({@link Material#LADDER}) se přichytává na svislou stěnu, ne na
 * podlahu – proto task hledá oporu jen v přivráceném vodorovném sousedovi
 * (na rozdíl od {@link PlaceBlockTask}, který preferuje blok pod cílem).
 * Celý sloupec žebříků se položí naráz vstoje z footholdu (bota drží podlaha,
 * takže při míření na vyšší příčky nesklouzne) a teprve pak bot vyleze jedním
 * plynulým tahem – nikdy nešplhá do prázdna a nezastaví se v půli.</p>
 *
 * <p>Task končí úspěchem, jakmile bot stojí na zemi výš než začal (přelezl),
 * neúspěchem při vyčerpání žebříků, výškového nebo časového rozpočtu.</p>
 */
public final class LadderTask implements BotTask {

    /** Nejvyšší přelezitelná stěna (počet žebříkových segmentů). */
    private static final int MAX_HEIGHT = 8;
    /** Tvrdý časový strop celého výstupu (ticky) – pojistka proti zacyklení. */
    private static final int BUDGET_TICKS = 800;
    /** Kolik ticků čekat na potvrzení položeného žebříku, než to vzdáme. */
    private static final int VERIFY_TICKS = 20;

    private enum Phase { PLAN, PLACE, VERIFY, CLIMB, DONE }

    private final int sx;
    private final int sz;
    private final Direction wallFace;

    private Phase phase = Phase.PLAN;
    private MoveInput move = MoveInput.IDLE;
    private int startY = Integer.MIN_VALUE;
    private BlockPos col;
    private int ticks;
    private int aimTicks;
    private int verifyTicks;
    private BlockPos placeTarget;
    private BlockPos placeAgainst;
    private boolean succeeded;

    /**
     * @param sx krok ke stěně po ose X (-1/0/1); právě jedna osa nenulová
     * @param sz krok ke stěně po ose Z (-1/0/1)
     */
    public LadderTask(int sx, int sz) {
        this.sx = sx;
        this.sz = sz;
        this.wallFace = faceTowardBot(sx, sz);
    }

    /** @return {@code true} pokud bot stěnu přelezl */
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
            startY = feet.y();
            col = feet; // sloupec žebříků má pevnou vodorovnou pozici (foothold)
        }

        switch (phase) {
            case PLAN -> plan(ctx, feet);
            case PLACE -> place(ctx);
            case VERIFY -> verify(ctx);
            case CLIMB -> climb(ctx, feet);
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    /**
     * Rozhodne další krok: přelezeno / položit další příčku / vylézt.
     *
     * <p>Celý sloupec žebříků klademe zdola nahoru <b>vstoje z footholdu</b>
     * (bota drží podlaha pod žebříkem, takže při míření na vyšší příčku
     * nesklouzne). Teprve až sloupec sahá k hraně stěny, přejdeme do jednoho
     * plynulého výstupu – při šplhání se nesmí zastavit, jinak fyzika žebříku
     * nechá bota sklouznout zpět.</p>
     */
    private void plan(BotContext ctx, BlockPos feet) {
        // Přelezeno: dosedli jsme na zem výš než na startu (a už nejsme na žebříku).
        if (ctx.onGround() && feet.y() > startY && !ctx.worldView().traitsAt(feet).climbable()) {
            succeeded = true;
            phase = Phase.DONE;
            return;
        }
        for (int k = 0; k < MAX_HEIGHT; k++) {
            BlockPos cell = col.offset(0, k, 0);
            BlockPos wall = col.offset(sx, k, sz);
            if (!ctx.worldView().traitsAt(wall).solid()) {
                break; // nad hranou stěny – žebřík už není o co opřít
            }
            if (!ctx.worldView().traitsAt(cell).climbable()) {
                if (!ctx.inventory().equipItem(ctx.serverView().latest(), Material.LADDER)) {
                    phase = Phase.DONE; // došly žebříky – výstup končí
                    return;
                }
                beginPlace(cell, wall);
                return;
            }
        }
        // Sloupec stojí → vylézt nahoru jedním tahem.
        phase = Phase.CLIMB;
    }

    private void beginPlace(BlockPos target, BlockPos against) {
        placeTarget = target;
        placeAgainst = against;
        aimTicks = 0;
        verifyTicks = 0;
        phase = Phase.PLACE;
    }

    /** Zamíří na přivrácenou stěnu a klikne (UseItemOn) – jako hráč pravým. */
    private void place(BotContext ctx) {
        Vec3 eyes = ctx.position().add(0, 1.62, 0);
        Vec3 normal = faceNormal(wallFace);
        Vec3 clickPoint = placeAgainst.center().add(0, 0.5, 0).add(normal.mul(0.5));
        ctx.humanizer().lookAt(eyes, clickPoint);
        if (++aimTicks >= ctx.rng().rangeInt(3, 6)) {
            ctx.actions().useItemOn(placeAgainst, wallFace);
            phase = Phase.VERIFY;
        }
    }

    /** Počká, až se žebřík ve světě objeví (climbable), nebo to po timeoutu vzdá. */
    private void verify(BotContext ctx) {
        if (ctx.worldView().traitsAt(placeTarget).climbable()) {
            ctx.stats().addPlaced();
            phase = Phase.PLAN;
        } else if (++verifyTicks > VERIFY_TICKS) {
            phase = Phase.DONE; // položení nevyšlo (dosah, entita, chybějící stěna)
        }
    }

    /**
     * Plynulý výstup: fyzika (onClimbable + jump) šplhá svisle. Během výstupu se
     * bot drží STŘEDU žebříkového sloupce a netlačí do stěny – tlak do zdi by
     * server vyhodnotil jako neplatný pohyb a bota trhaně vracel. Až nad hranou
     * stěny bot vykročí vpřed a dosedne na vršek.
     */
    private void climb(BotContext ctx, BlockPos feet) {
        if (ctx.onGround() && feet.y() > startY) {
            succeeded = true; // dosedli jsme na vršek stěny – hotovo
            phase = Phase.DONE;
            return;
        }
        Vec3 pos = ctx.position();
        double dirX;
        double dirZ;
        if (ctx.worldView().traitsAt(feet.offset(sx, 0, sz)).solid()) {
            // Ještě podél stěny → držet se STŘEDU sloupce (netlačit do zdi, jinak
            // server vyhodnotí pohyb jako „moved wrongly" a vrátí bota zpět).
            dirX = clampUnit(col.x() + 0.5 - pos.x());
            dirZ = clampUnit(col.z() + 0.5 - pos.z());
        } else {
            // Nad hranou stěny → vykročit vpřed na vršek.
            dirX = sx != 0 ? sx : clampUnit(col.x() + 0.5 - pos.x());
            dirZ = sz != 0 ? sz : clampUnit(col.z() + 0.5 - pos.z());
        }
        move = new MoveInput(new Vec3(dirX, 0, dirZ), false, true, false);
    }

    /** Ořízne hodnotu do intervalu [-1, 1] (jednotkový směr korekce). */
    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    @Override
    public void cancel(BotContext ctx) {
        phase = Phase.DONE;
    }

    /** Strana stěny přivrácená k botovi (kam se žebřík přilepí a kudy se leze). */
    static Direction faceTowardBot(int sx, int sz) {
        if (sx == 1) {
            return Direction.WEST;
        }
        if (sx == -1) {
            return Direction.EAST;
        }
        if (sz == 1) {
            return Direction.NORTH;
        }
        return Direction.SOUTH; // sz == -1
    }

    /** Jednotková normála strany bloku. */
    private static Vec3 faceNormal(Direction direction) {
        return switch (direction) {
            case UP -> new Vec3(0, 1, 0);
            case DOWN -> new Vec3(0, -1, 0);
            case NORTH -> new Vec3(0, 0, -1);
            case SOUTH -> new Vec3(0, 0, 1);
            case EAST -> new Vec3(1, 0, 0);
            case WEST -> new Vec3(-1, 0, 0);
        };
    }
}
