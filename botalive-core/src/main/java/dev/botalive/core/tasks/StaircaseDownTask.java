package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

/**
 * Kontrolovaný sestup vyhloubením schodiště dolů – protějšek {@link PillarUpTask}
 * a {@link LadderTask} pro cestu <b>dolů</b>.
 *
 * <p>Když cíl leží níž za srázem vyšším než bezpečný seskok a svah je z pevného
 * materiálu, bot si vydělá schodiště: v každém kroku uvolní blok před sebou
 * (prostor na hlavu) a blok o patro níž (kam vkročí), ověří, že pod ním je
 * pevná a bezpečná podlaha, a vkročí o blok níž (bezpečný seskok o 1). Opakuje,
 * dokud nedosáhne cílové výšky nebo bezpečného seskoku. Klasika hráče místo
 * skoku do hloubky.</p>
 *
 * <p>Bezpečnost je zásadní: nikdy nekope do tekutiny ani nad prázdno/neznámo
 * (do jeskyní a lávy se schodiště nevede) – narazí-li na nebezpečnou podlahu,
 * task skončí a navigace zvolí jinudy. Kopání dělá vnořený {@link MineBlockTask}
 * (věrné načasování); mezi kopáním bot stojí, při vkročení se posouvá –
 * proto využívá {@link #move()}.</p>
 */
public final class StaircaseDownTask implements BotTask {

    /** Nejhlubší sestup na jeden task (níž se plánuje nadvakrát přes replan). */
    public static final int MAX_DEPTH = 8;
    /** Tvrdý časový strop celého sestupu (ticky). */
    private static final int BUDGET_TICKS = 1200;
    /** Ticky na jedno vkročení o patro níž, než se krok vzdá. */
    private static final int STEP_TICKS = 20;

    private enum Phase { ASSESS, CLEAR_HEAD, CLEAR_STEP, STEP, DONE }

    private final int sx;
    private final int sz;
    private final int targetY;

    private Phase phase = Phase.ASSESS;
    private MoveInput move = MoveInput.IDLE;
    private MineBlockTask digger;
    private int ticks;
    private int stepTicks;
    private int depth;
    private int startY = Integer.MIN_VALUE;
    private int cycleStartY;
    private boolean succeeded;

    /**
     * @param sx      krok k cíli po ose X (-1/0/1); právě jedna vodorovná osa nenulová
     * @param sz      krok k cíli po ose Z (-1/0/1)
     * @param targetY cílová výška nohou (sestup se zastaví na ní nebo výš)
     */
    public StaircaseDownTask(int sx, int sz, int targetY) {
        this.sx = sx;
        this.sz = sz;
        this.targetY = targetY;
    }

    /** @return {@code true} pokud bot sestoupil do cílové výšky */
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
        WorldView world = ctx.worldView();
        if (world == null || ++ticks > BUDGET_TICKS) {
            phase = Phase.DONE;
            return true;
        }
        BlockPos feet = ctx.position().toBlockPos();
        if (startY == Integer.MIN_VALUE) {
            startY = feet.y();
        }

        switch (phase) {
            case ASSESS -> assess(ctx, world, feet);
            case CLEAR_HEAD -> clear(ctx, world, feet.offset(sx, 0, sz), Phase.CLEAR_STEP);
            case CLEAR_STEP -> clear(ctx, world, feet.offset(sx, -1, sz), Phase.STEP);
            case STEP -> step(ctx, feet);
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    /** Rozhodne: hotovo / bezpečný další stupeň / nebezpečno → konec. */
    private void assess(BotContext ctx, WorldView world, BlockPos feet) {
        if (!ctx.onGround()) {
            return; // dosednout, než se rozhoduje (jako pilíř)
        }
        if (feet.y() <= targetY || depth >= MAX_DEPTH || feet.y() <= startY - MAX_DEPTH) {
            succeeded = feet.y() <= targetY;
            phase = Phase.DONE;
            return;
        }
        if (!canDescendStep(world, feet, sx, sz)) {
            phase = Phase.DONE; // pod schodem tekutina/prázdno/neznámo → nechat být
            return;
        }
        cycleStartY = feet.y();
        phase = Phase.CLEAR_HEAD;
    }

    /** Uvolní zadaný blok (je-li pevný) vnořeným MineBlockTaskem, pak přejde dál. */
    private void clear(BotContext ctx, WorldView world, BlockPos block, Phase next) {
        if (digger == null) {
            if (!world.traitsAt(block).solid()) {
                phase = next;
                return;
            }
            Material material = world.materialAt(block);
            ctx.inventory().equipBestTool(ctx.serverView().latest(),
                    material != null ? material : Material.STONE);
            digger = new MineBlockTask(block);
        }
        if (digger.tick(ctx)) {
            digger = null;
            phase = next;
        }
    }

    /** Vkročí o patro níž (bezpečný seskok o 1 na uvolněný stupeň). */
    private void step(BotContext ctx, BlockPos feet) {
        move = new MoveInput(new Vec3(sx, 0, sz), false, false, false);
        if (feet.y() < cycleStartY) {
            depth++;
            stepTicks = 0;
            phase = Phase.ASSESS;
        } else if (++stepTicks > STEP_TICKS) {
            phase = Phase.DONE; // nevkročil (zeď, entita) – vzdát to
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        if (digger != null) {
            digger.cancel(ctx);
            digger = null;
        }
        phase = Phase.DONE;
    }

    /**
     * Je další stupeň sestupu bezpečný? Podlaha o patro níž
     * ({@code feet + (sx,-2,sz)}) musí být pevná, známá a bez hazardu, a ani
     * uvolňované buňky (hlava, stupeň) nesmí sousedit s tekutinou – do jeskyní,
     * lávy a nad prázdno se schodiště nevede.
     *
     * @param world pohled na svět
     * @param feet  aktuální pozice nohou
     * @param sx    krok po X
     * @param sz    krok po Z
     * @return {@code true} pokud lze bezpečně sestoupit o jeden stupeň
     */
    public static boolean canDescendStep(WorldView world, BlockPos feet, int sx, int sz) {
        BlockPos head = feet.offset(sx, 0, sz);
        BlockPos stepCell = feet.offset(sx, -1, sz);
        BlockPos floor = feet.offset(sx, -2, sz);
        BlockTraits floorTraits = world.traitsAt(floor);
        if (floorTraits == BlockTraits.UNKNOWN || !floorTraits.solid()
                || floorTraits.hazard()) {
            return false; // není bezpečná pevná podlaha, na kterou dosednout
        }
        // Ani hlava, ani stupeň nesmí být tekutina nebo těsně u tekutiny/hazardu.
        return safeToClear(world, head) && safeToClear(world, stepCell);
    }

    /** Buňka je průchozí nebo pevně vylámatelná, a nesousedí s tekutinou/hazardem. */
    private static boolean safeToClear(WorldView world, BlockPos block) {
        BlockTraits traits = world.traitsAt(block);
        if (traits == BlockTraits.UNKNOWN || traits.liquid() || traits.hazard()) {
            return false;
        }
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}}) {
            BlockTraits neighbor = world.traitsAt(block.offset(d[0], d[1], d[2]));
            if (neighbor.liquid() || neighbor.hazard()) {
                return false; // za stěnou stupně číhá voda/láva – nekopat
            }
        }
        return true;
    }
}
