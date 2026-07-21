package dev.botalive.core.bot;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.bot.BotControl;
import dev.botalive.api.bot.NearbyEntity;
import dev.botalive.api.bot.Position;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.List;

/**
 * Implementace veřejného {@link BotControl} nad interním {@link BotContext}.
 *
 * <p>Je to tenká, bezstavová fasáda: každá metoda deleguje na existující
 * subsystém bota ({@code navigator}, {@code actions}, {@code entities},
 * {@code worldView}, {@code inventory}, {@code humanizer}) a překládá interní
 * typy ({@link Vec3}, {@link BlockPos}, {@link TrackedEntity}) na hodnotové
 * typy veřejného API ({@link Position}, {@link NearbyEntity}). Žádnou vlastní
 * logiku nepřidává – jen zveřejňuje bezpečnou podmnožinu schopností bota
 * cizím pluginům.</p>
 *
 * <p>Volá se z tick vlákna bota (stejně jako vestavěné cíle) – proto bez
 * zámků. Očekává výška očí bota 1,62 bloku (vanilla) pro {@link #lookAt}.</p>
 */
public final class BotControlImpl implements BotControl {

    /** Výška očí hráče nad nohama (vanilla). */
    private static final double EYE_HEIGHT = 1.62;

    private final BotContext ctx;

    /**
     * @param ctx interní kontext bota (typicky {@code BotImpl})
     */
    public BotControlImpl(BotContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Bot bot() {
        return ctx.bot();
    }

    // ------------------------------------------------------------------ vnímání

    @Override
    public Position position() {
        Vec3 p = ctx.position();
        return new Position(p.x(), p.y(), p.z());
    }

    @Override
    public double health() {
        return ctx.clientState().health();
    }

    @Override
    public int food() {
        return ctx.clientState().food();
    }

    @Override
    public boolean onGround() {
        return ctx.onGround();
    }

    @Override
    public long worldTime() {
        return ctx.worldTime();
    }

    @Override
    public boolean raining() {
        return ctx.raining();
    }

    @Override
    public boolean thundering() {
        return ctx.thundering();
    }

    @Override
    public String worldName() {
        WorldView world = ctx.worldView();
        return world != null ? world.worldName() : null;
    }

    @Override
    public String blockAt(int x, int y, int z) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        Material material = world.materialAt(new BlockPos(x, y, z));
        return material != null ? material.name() : null;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return traits(x, y, z).solid();
    }

    @Override
    public boolean isLiquid(int x, int y, int z) {
        return traits(x, y, z).liquid();
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        return traits(x, y, z).passable();
    }

    private BlockTraits traits(int x, int y, int z) {
        WorldView world = ctx.worldView();
        return world != null ? world.traitsAt(new BlockPos(x, y, z)) : BlockTraits.UNKNOWN;
    }

    @Override
    public List<NearbyEntity> nearbyEntities(double radius) {
        List<TrackedEntity> tracked = ctx.entities().nearby(ctx.position(), radius, e -> true);
        return tracked.stream().map(BotControlImpl::toNearby).toList();
    }

    private static NearbyEntity toNearby(TrackedEntity e) {
        Vec3 p = e.position();
        return new NearbyEntity(e.entityId(), e.uuid(), e.type().name(),
                new Position(p.x(), p.y(), p.z()), e.isPlayer(), e.isHostile());
    }

    // ---------------------------------------------------------------- navigace

    @Override
    public void navigateTo(int x, int y, int z) {
        ctx.navigator().navigateTo(ctx.position(), new BlockPos(x, y, z));
    }

    @Override
    public boolean navigating() {
        return ctx.navigator().navigating();
    }

    @Override
    public void stopNavigation() {
        ctx.navigator().stop();
    }

    // ------------------------------------------------------ taktická primitiva

    @Override
    public dev.botalive.api.task.BotTask mineBlock(int x, int y, int z) {
        return new dev.botalive.core.tasks.ApiTaskAdapter(ctx,
                new dev.botalive.core.tasks.MineBlockTask(new BlockPos(x, y, z)));
    }

    @Override
    public dev.botalive.api.task.BotTask placeBlock(int x, int y, int z, String material) {
        Material mat = material == null ? null : Material.matchMaterial(material);
        return new dev.botalive.core.tasks.ApiTaskAdapter(ctx,
                new dev.botalive.core.tasks.EquipAndPlaceTask(new BlockPos(x, y, z), mat));
    }

    @Override
    public dev.botalive.api.task.BotTask walkTo(int x, int y, int z) {
        return new dev.botalive.core.tasks.WalkToTask(x, y, z);
    }

    // ------------------------------------------------------------ pohled a akce

    @Override
    public void lookAt(double x, double y, double z) {
        Vec3 eye = ctx.position().add(0, EYE_HEIGHT, 0);
        ctx.humanizer().lookAt(eye, new Vec3(x, y, z));
    }

    @Override
    public void attack(int entityId) {
        ctx.actions().attack(entityId);
    }

    @Override
    public void swingArm() {
        ctx.actions().swing();
    }

    @Override
    public void selectHotbarSlot(int index) {
        ctx.actions().selectHotbar(index);
    }

    @Override
    public void useItem() {
        ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
    }

    // -------------------------------------------------------------- inventář

    @Override
    public boolean hasItem(String materialName, int count) {
        if (count <= 0) {
            return true;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            return false;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return false;
        }
        int found = countMatching(snapshot.hotbar(), snapshot.hotbarCounts(), material)
                + countMatching(snapshot.mainInventory(), snapshot.mainCounts(), material);
        return found >= count;
    }

    private static int countMatching(Material[] slots, int[] counts, Material target) {
        int total = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == target) {
                total += counts[i];
            }
        }
        return total;
    }

    @Override
    public boolean selectBestTool(int x, int y, int z) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        WorldView world = ctx.worldView();
        if (snapshot == null || world == null) {
            return false;
        }
        Material block = world.materialAt(new BlockPos(x, y, z));
        return block != null && ctx.inventory().equipBestTool(snapshot, block);
    }

    // ----------------------------------------------------------------- řeč

    @Override
    public void say(String message) {
        ctx.bot().say(message);
    }
}
