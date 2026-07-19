package dev.botalive.core.testutil;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Personality;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.ShareRequest;
import dev.botalive.core.bot.BotStats;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.chat.ChatEngine;
import dev.botalive.core.combat.CombatController;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.EntityTracker;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.network.BotClientState;
import dev.botalive.core.pathfinding.Navigator;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Testovací dvojník {@link BotContext} pro fyzickou simulaci reaktivních
 * tasků (pilíř, most, žebřík) proti {@code BotPhysics}.
 *
 * <p>Svět je {@link FakeWorldView}; {@code actions().useItemOn} pokládá
 * držený materiál rovnou do světa (jako server, který klik přijal) – žebřík
 * jako šplhatelný blok, stavební blok jako pevný. Pevný blok se odmítne,
 * když by protínal tělo bota (vanilla pravidlo, na kterém stojí načasování
 * pilíře). Inventář je počítadlo kusů ({@link #give}); equip drží právě
 * jeden „materiál v ruce". Nepoužívané subsystémy vyhazují
 * {@link UnsupportedOperationException} – task, který na ně sáhne, si o
 * rozšíření dvojníka řekne spadlým testem.</p>
 */
public final class FakeBotContext implements BotContext {

    private final FakeWorldView world;
    private final BotRandom rng = new BotRandom(7);
    private final Humanizer humanizer;
    private final ServerSideView serverView = new ServerSideView(UUID.randomUUID(), null);
    private final BotStats stats = new BotStats(UUID.randomUUID(), null);
    private final Map<Material, Integer> items = new HashMap<>();
    private final FakeActions actions;
    private final FakeInventory inventory;

    private Material equipped;
    private Vec3 position = new Vec3(0, 0, 0);
    private boolean onGround = true;
    private int placed;

    /**
     * @param world       syntetický svět simulace
     * @param personality osobnost (humanizer pohledu)
     */
    public FakeBotContext(FakeWorldView world, Personality personality) {
        this.world = world;
        this.humanizer = new Humanizer(rng, personality);
        this.actions = new FakeActions();
        this.inventory = new FakeInventory(actions);
    }

    /** Aktualizuje polohu a stav země z fyziky (volat každý tick před task.tick). */
    public void update(Vec3 position, boolean onGround) {
        this.position = position;
        this.onGround = onGround;
    }

    /** Přidá kusy materiálu do inventářového počítadla. */
    public FakeBotContext give(Material material, int count) {
        items.merge(material, count, Integer::sum);
        return this;
    }

    /** @return počet bloků/příček skutečně položených do světa */
    public int placed() {
        return placed;
    }

    // ------------------------------------------------------- použité subsystémy

    @Override
    public WorldView worldView() {
        return world;
    }

    @Override
    public Vec3 position() {
        return position;
    }

    @Override
    public boolean onGround() {
        return onGround;
    }

    @Override
    public BotActions actions() {
        return actions;
    }

    @Override
    public InventoryHelper inventory() {
        return inventory;
    }

    @Override
    public Humanizer humanizer() {
        return humanizer;
    }

    @Override
    public ServerSideView serverView() {
        return serverView; // latest() je null – equip dvojníka snapshot nečte
    }

    @Override
    public BotStats stats() {
        return stats;
    }

    @Override
    public BotRandom rng() {
        return rng;
    }

    @Override
    public long worldTime() {
        return 6000; // poledne
    }

    // --------------------------------------------------- nepoužívané subsystémy

    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException(
                "simulace reaktivních tasků tento subsystém nepoužívá");
    }

    @Override
    public Bot bot() {
        throw unused();
    }

    @Override
    public BotClientState clientState() {
        throw unused();
    }

    @Override
    public EntityTracker entities() {
        throw unused();
    }

    @Override
    public Navigator navigator() {
        throw unused();
    }

    @Override
    public dev.botalive.core.inventory.ClientInventory clientInventory() {
        throw unused();
    }

    @Override
    public dev.botalive.core.container.ContainerTracker containers() {
        throw unused();
    }

    @Override
    public dev.botalive.core.container.ContainerClicker clicker() {
        throw unused();
    }

    @Override
    public dev.botalive.core.world.state.ItemMapper itemMapper() {
        return null; // volitelná tabulka – null je legální „bez tabulky"
    }

    @Override
    public ChatEngine chat() {
        throw unused();
    }

    @Override
    public CombatController combat() {
        throw unused();
    }

    @Override
    public BotAliveConfig config() {
        throw unused();
    }

    @Override
    public dev.botalive.core.scheduler.MainThreadBridge bridge() {
        throw unused();
    }

    @Override
    public dev.botalive.core.vehicle.VehicleController vehicle() {
        throw unused();
    }

    @Override
    public void requestMove(MoveInput input) {
        throw unused();
    }

    @Override
    public void gainExperience(
            dev.botalive.core.personality.PersonalityEvolution.BotExperience experience) {
        // tasky hlásí zážitky mimochodem – v simulaci se zahazují
    }

    @Override
    public dev.botalive.core.social.CrimeLog crimeLog() {
        throw unused();
    }

    @Override
    public dev.botalive.core.social.SocialGraph socialGraph() {
        throw unused();
    }

    @Override
    public boolean raining() {
        return false;
    }

    @Override
    public boolean thundering() {
        return false;
    }

    @Override
    public dev.botalive.core.settlement.SettlementService settlements() {
        throw unused();
    }

    @Override
    public dev.botalive.core.settlement.SocialView settlementView() {
        throw unused();
    }

    @Override
    public ShareRequest takeShareRequest() {
        return null;
    }

    @Override
    public java.util.concurrent.CompletableFuture<Integer> estimateBreakTicks(BlockPos pos) {
        return java.util.concurrent.CompletableFuture.completedFuture(20);
    }

    // ------------------------------------------------------------------ dvojníci

    /** Akce: klik pravým položí držený materiál do světa (server ho přijal). */
    private final class FakeActions extends BotActions {
        FakeActions() {
            super(null, null);
        }

        @Override
        public void useItemOn(BlockPos pos, Direction face) {
            if (equipped == null || items.getOrDefault(equipped, 0) <= 0) {
                return;
            }
            BlockPos target = pos.offset(
                    face == Direction.EAST ? 1 : face == Direction.WEST ? -1 : 0,
                    face == Direction.UP ? 1 : face == Direction.DOWN ? -1 : 0,
                    face == Direction.SOUTH ? 1 : face == Direction.NORTH ? -1 : 0);
            if (!world.traitsAt(target).noCollision()) {
                return; // buňka obsazená – klik propadne
            }
            boolean ladder = equipped == Material.LADDER;
            if (!ladder && intersectsBot(target)) {
                return; // pevný blok do vlastního těla server odmítne
            }
            items.merge(equipped, -1, Integer::sum);
            world.set(target.x(), target.y(), target.z(),
                    ladder ? FakeWorldView.CLIMBABLE : FakeWorldView.SOLID);
            placed++;
        }

        /** Protíná buňka AABB bota (šířka 0,6, výška 1,8)? */
        private boolean intersectsBot(BlockPos cell) {
            double half = 0.3;
            boolean overlapX = position.x() + half > cell.x() && position.x() - half < cell.x() + 1;
            boolean overlapZ = position.z() + half > cell.z() && position.z() - half < cell.z() + 1;
            boolean overlapY = position.y() + 1.8 > cell.y() && position.y() < cell.y() + 1;
            return overlapX && overlapZ && overlapY;
        }
    }

    /** Inventář: equip drží materiál v ruce podle počítadla kusů. */
    private final class FakeInventory extends InventoryHelper {
        FakeInventory(BotActions actions) {
            super(actions);
        }

        @Override
        public boolean equipItem(ServerSideView.Snapshot snapshot, Material material) {
            if (items.getOrDefault(material, 0) <= 0) {
                return false;
            }
            equipped = material;
            return true;
        }

        @Override
        public boolean equipBuildingBlock(ServerSideView.Snapshot snapshot) {
            for (Map.Entry<Material, Integer> entry : items.entrySet()) {
                if (entry.getKey() != Material.LADDER && entry.getValue() > 0) {
                    equipped = entry.getKey();
                    return true;
                }
            }
            return false;
        }
    }
}
