package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/**
 * Položení bloku na cílovou pozici.
 *
 * <p>Bot musí držet stavební blok (zajistí volající). Task najde pevného
 * souseda cílové pozice, zaměří se na jeho přivrácenou stěnu a použije
 * UseItemOn – přesně jako hráč klikající pravým tlačítkem.</p>
 */
public final class PlaceBlockTask implements BotTask {

    private enum Phase { AIM, PLACE, VERIFY, DONE }

    private final BlockPos target;

    private Phase phase = Phase.AIM;
    private int aimTicks;
    private int verifyTicks;
    private BlockPos against;
    private Direction face;

    /**
     * @param target pozice, kde má blok vzniknout
     */
    public PlaceBlockTask(BlockPos target) {
        this.target = target;
    }

    @Override
    public boolean tick(BotContext ctx) {
        if (phase == Phase.AIM && against == null && !findSupport(ctx)) {
            phase = Phase.DONE; // není o co se opřít
            return true;
        }
        Vec3 eyes = ctx.position().add(0, 1.62, 0);

        switch (phase) {
            case AIM -> {
                Vec3 normal = faceNormal(face);
                Vec3 clickPoint = against.center().add(0, 0.5, 0).add(normal.mul(0.5));
                ctx.humanizer().lookAt(eyes, clickPoint);
                if (++aimTicks >= ctx.rng().rangeInt(3, 6)) {
                    phase = Phase.PLACE;
                }
            }
            case PLACE -> {
                ctx.actions().useItemOn(against, face);
                phase = Phase.VERIFY;
            }
            case VERIFY -> {
                // Ověření přes traits (kolize se objevila), ne Material.isAir –
                // materiálové API sahá na server Registry a vrstva tasků má
                // zůstat nad WorldView abstrakcí.
                if (ctx.worldView() != null
                        && !ctx.worldView().traitsAt(target).noCollision()) {
                    ctx.stats().addPlaced();
                    phase = Phase.DONE;
                } else if (++verifyTicks > 20) {
                    phase = Phase.DONE; // nepodařilo se (kolize s entitou apod.)
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
        phase = Phase.DONE;
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

    /** Najde pevného souseda a stranu, přes kterou lze blok položit. */
    private boolean findSupport(BotContext ctx) {
        if (ctx.worldView() == null) {
            return false;
        }
        // Preferovat pokládání na blok pod cílem (nejpřirozenější).
        record Support(BlockPos neighbor, Direction face) {
        }
        Support[] candidates = {
                new Support(target.down(), Direction.UP),
                new Support(target.offset(1, 0, 0), Direction.WEST),
                new Support(target.offset(-1, 0, 0), Direction.EAST),
                new Support(target.offset(0, 0, 1), Direction.NORTH),
                new Support(target.offset(0, 0, -1), Direction.SOUTH),
                new Support(target.up(), Direction.DOWN),
        };
        for (Support candidate : candidates) {
            if (ctx.worldView().traitsAt(candidate.neighbor()).solid()) {
                this.against = candidate.neighbor();
                this.face = candidate.face();
                return true;
            }
        }
        return false;
    }
}
