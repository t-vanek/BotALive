package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Kompostování přebytků – ze semínek a sazenic je hnojivo.
 *
 * <p>Farmářský okruh: přebytečná semínka, sazenice a rostlinné zbytky bot
 * hází do composteru (klik pravým jako hráč); plný composter při dalším
 * kliku vydá bone meal, které si bot sebere. Vanilla mechanika bez oken –
 * funguje v server i packet režimu. Composter si bot vyrobí a postaví sám
 * (crafting progrese: půlky → composter).</p>
 */
public final class CompostGoal extends AbstractGoal {

    /** Od kolika kusů se přebytek vyplatí kompostovat. */
    private static final int SURPLUS = 12;

    private enum Phase { FIND, GO, COMPOST, DONE }

    private Phase phase = Phase.FIND;
    private BlockPos composter;
    private StationPlacement placement;
    private int uses;
    private int waitTicks;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public CompostGoal() {
        super("compost");
    }

    /** Kompostovatelný přebytek (semínka, sazenice, listí…). */
    private static boolean compostable(Material material) {
        return material.name().endsWith("_SEEDS")
                || dev.botalive.core.inventory.Items.isSapling(material)
                || dev.botalive.core.inventory.Materials.isLeaves(material)
                || material == Material.WHEAT
                || material == Material.KELP || material == Material.SUGAR_CANE;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Composter je farmářský okruh – overworld.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        int surplus = countCompostable(snapshot);
        boolean hasComposter = snapshot.hasItem(m -> m == Material.COMPOSTER)
                || findComposter(ctx) != null;
        if (surplus < SURPLUS || !hasComposter) {
            return 0;
        }
        double patience = bot.personality().trait(Trait.PATIENCE);
        return 4 + Math.min(surplus, 32) * 0.2 + patience * 4;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        composter = null;
        placement = null;
        uses = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                composter = findComposter(ctx);
                if (composter != null) {
                    placement = null;
                    phase = Phase.GO;
                    return;
                }
                if (placement == null) {
                    placement = new StationPlacement(Material.COMPOSTER);
                }
                if (!placement.tick(ctx)) {
                    placement = null;
                    cooldownTicks = 2400;
                    phase = Phase.DONE;
                }
            }
            case GO -> {
                if (composter.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), PathGoal.near(composter, 2));
                    if (!ctx.navigator().navigating()) {
                        cooldownTicks = 1200;
                        phase = Phase.DONE;
                    }
                    return;
                }
                ctx.navigator().stop();
                waitTicks = ctx.rng().rangeInt(4, 8);
                phase = Phase.COMPOST;
            }
            case COMPOST -> {
                if (--waitTicks > 0) {
                    return;
                }
                var snapshot = ctx.serverView().latest();
                // Vyprázdnění plného composteru řeší tentýž klik – po naplnění
                // vypadne bone meal a bot ho sebere (server ho přitáhne sám).
                if (uses >= 24 || snapshot == null
                        || !ctx.inventory().equipMatching(snapshot, CompostGoal::compostable)) {
                    // Poslední klik naprázdno vybere případný hotový bone meal.
                    ctx.actions().useItemOn(composter, Direction.UP);
                    cooldownTicks = 2400;
                    phase = Phase.DONE;
                    if (uses > 0 && ctx.rng().chance(0.4)) {
                        ctx.chat().say("kompost hotovy, hnojivo se bude hodit");
                    }
                    return;
                }
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        composter.center().add(0, 0.5, 0));
                ctx.actions().useItemOn(composter, Direction.UP);
                uses++;
                waitTicks = ctx.rng().rangeInt(6, 12);
            }
            case DONE -> {
            }
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case FIND, GO -> "nesu přebytky do composteru";
            case COMPOST -> "kompostuju semínka na hnojivo";
            case DONE -> null;
        };
    }

    private static int countCompostable(dev.botalive.core.bot.ServerSideView.Snapshot snapshot) {
        int count = 0;
        var hotbar = snapshot.hotbar();
        int[] counts = snapshot.hotbarCounts();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null && compostable(hotbar[i])) {
                count += counts != null ? Math.max(counts[i], 1) : 1;
            }
        }
        for (Material material : snapshot.mainInventory()) {
            if (material != null && compostable(material)) {
                count += 4;
            }
        }
        return count;
    }

    /** Composter v okolí (sken 8 bloků). */
    private BlockPos findComposter(BotContext ctx) {
        if (ctx.worldView() == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    Material material = ctx.worldView().materialAt(center.offset(dx, dy, dz));
                    if (material == Material.COMPOSTER) {
                        return center.offset(dx, dy, dz);
                    }
                }
            }
        }
        return null;
    }
}
