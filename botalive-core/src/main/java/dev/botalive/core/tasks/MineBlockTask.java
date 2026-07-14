package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.concurrent.CompletableFuture;

/**
 * Vytěžení jednoho bloku přesně po vzoru vanilla klienta.
 *
 * <p>Fáze: zaměření pohledem → START_DIGGING → máchání rukou po dobu těžby
 * (odhad přes server-side {@code Block.getBreakSpeed(Player)} – autoritativní
 * hodnota zohledňující nástroj, enchanty i efekty) → FINISH_DIGGING → ověření,
 * že blok zmizel. Server kopání validuje jako u hráče; příliš rychlé dokončení
 * by odmítl.</p>
 */
public final class MineBlockTask implements BotTask {

    private enum Phase { AIM, DIGGING, VERIFY, DONE }

    private final BlockPos block;

    private Phase phase = Phase.AIM;
    private int aimTicks;
    private int digTicks;
    private int estimatedTicks = -1;
    private int verifyTicks;
    private int swingCooldown;
    private CompletableFuture<Integer> estimate;
    private Direction face = Direction.UP;

    /**
     * @param block blok k vytěžení
     */
    public MineBlockTask(BlockPos block) {
        this.block = block;
    }

    @Override
    public boolean tick(BotContext ctx) {
        Vec3 eyes = ctx.position().add(0, 1.62, 0);
        Vec3 blockCenter = block.center().add(0, 0.5, 0);
        ctx.humanizer().lookAt(eyes, blockCenter);

        switch (phase) {
            case AIM -> {
                if (++aimTicks >= ctx.rng().rangeInt(3, 7)) {
                    face = dominantFace(eyes, blockCenter);
                    estimate = ctx.estimateBreakTicks(block);
                    ctx.actions().startDigging(block, face);
                    phase = Phase.DIGGING;
                }
            }
            case DIGGING -> {
                digTicks++;
                if (--swingCooldown <= 0) {
                    ctx.actions().swing();
                    swingCooldown = ctx.rng().rangeInt(4, 6);
                }
                if (estimatedTicks < 0 && estimate != null && estimate.isDone()) {
                    Integer ticks = estimate.getNow(40);
                    estimatedTicks = ticks == null ? 40 : ticks;
                }
                // +2 ticky rezerva – radši o chlup déle než rychleji než server čeká.
                if (estimatedTicks >= 0 && digTicks >= estimatedTicks + 2) {
                    ctx.actions().finishDigging(block, face);
                    phase = Phase.VERIFY;
                }
                if (digTicks > 600) {
                    cancel(ctx); // bedrock apod. – vzdát to
                    phase = Phase.DONE;
                }
            }
            case VERIFY -> {
                var material = ctx.worldView() == null ? null : ctx.worldView().materialAt(block);
                if (material != null && material.isAir()) {
                    phase = Phase.DONE;
                } else if (++verifyTicks > 40) {
                    phase = Phase.DONE; // server neodpověděl změnou – nepokoušet dál
                }
            }
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (phase == Phase.DIGGING) {
            ctx.actions().cancelDigging(block, face);
        }
        phase = Phase.DONE;
    }

    /** Strana bloku přivrácená k očím bota. */
    private static Direction dominantFace(Vec3 eyes, Vec3 blockCenter) {
        Vec3 d = eyes.sub(blockCenter);
        double ax = Math.abs(d.x());
        double ay = Math.abs(d.y());
        double az = Math.abs(d.z());
        if (ay >= ax && ay >= az) {
            return d.y() > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return d.x() > 0 ? Direction.EAST : Direction.WEST;
        }
        return d.z() > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
