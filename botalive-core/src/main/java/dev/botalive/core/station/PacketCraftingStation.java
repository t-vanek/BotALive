package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.crafting.CraftPlanner;
import dev.botalive.core.crafting.GridPlacer;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Paketový crafting – skutečné kliky do crafting mřížky.
 *
 * <p>Recepty 2×2 (prkna, tyčky, ponk) jdou v mřížce vlastního inventáře
 * (okno 0, sloty 1–4); recepty 3×3 vyžadují <b>položený</b> ponk v dosahu –
 * stanice ho otevře interact paketem a kliká v okně CRAFTING. Není-li ponk
 * v okolí, ale bot ho má v inventáři, vrací {@link #NEED_TABLE} a cíl ho
 * položí ({@code PlaceBlockTask}) – přesně jako hráč.</p>
 *
 * <p>Progresi plánuje sdílený {@link CraftPlanner}, rozmístění kliků čistý
 * {@link GridPlacer} (levý klik zvedne stack, pravé kliky pokládají po
 * kusu, levý klik vrací zbytek), výsledek se vybírá shift-klikem. Úspěch
 * ověřujeme poklesem surovin v klientském modelu – server je autorita.</p>
 */
public final class PacketCraftingStation implements CraftingStation {

    /** Poloměr hledání položeného ponku (bloky). */
    private static final int TABLE_RADIUS = 4;

    @Override
    public CompletableFuture<String> craftNext(BotContext ctx) {
        return StationFlow.run("craft-" + ctx.bot().name(), null, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return null;
            }
            Windows.SlotMaps inv = Windows.inventorySection(ctx.clientInventory(), mapper);
            CraftPlanner.Plan plan = CraftPlanner.next(plannerState(inv));
            if (plan == null) {
                return null;
            }
            if (!plan.needsTable()) {
                return craftInWindow(ctx, mapper, plan, 0, GridPlacer.PLAYER_GRID, inv);
            }
            BlockPos table = findNearbyTable(ctx.worldView(), ctx.position().toBlockPos());
            if (table == null) {
                return inv.count(m -> m == Material.CRAFTING_TABLE) > 0 ? NEED_TABLE : null;
            }
            ctx.actions().useItemOn(table, Direction.UP);
            ContainerView view = StationFlow.awaitWindow(ctx,
                    v -> v.type() == ContainerType.CRAFTING, 2_500);
            if (view == null) {
                return null;
            }
            // Sloty hráčovy sekce okna ponku ↔ okna 0: ponk 10–45 ≡ inventář 9–44.
            Windows.SlotMaps windowInv = Windows.playerSection(view, mapper);
            String crafted = craftInWindow(ctx, mapper, plan, view.containerId(),
                    GridPlacer.TABLE_GRID, windowInv);
            StationFlow.humanPause();
            ctx.actions().closeContainer();
            return crafted;
        });
    }

    /** Provede kliky receptu v daném okně a ověří spotřebu surovin. */
    private static String craftInWindow(BotContext ctx, ItemMapper mapper,
                                        CraftPlanner.Plan plan, int containerId,
                                        int[] gridMapping, Windows.SlotMaps playerSection)
            throws InterruptedException {
        List<GridPlacer.Step> steps = GridPlacer.plan(plan.matrix(), gridMapping,
                playerSection.materials(), playerSection.counts());
        if (steps == null) {
            return null;
        }
        // Referenční surovina pro ověření úspěchu (server je autorita).
        Map.Entry<Material, Integer> reference =
                plan.ingredients().entrySet().iterator().next();
        int beforeCount = countInInventory(ctx, mapper, reference.getKey());

        for (GridPlacer.Step step : steps) {
            if (step.kind() == GridPlacer.Kind.LEFT) {
                ctx.clicker().leftClick(containerId, step.slot());
            } else {
                ctx.clicker().rightClick(containerId, step.slot());
            }
            StationFlow.humanPause();
        }
        // Výsledek shift-klikem (slot 0 mřížky) – server recept validuje.
        ctx.clicker().shiftClick(containerId, 0);
        boolean consumed = StationFlow.await(
                () -> countInInventory(ctx, mapper, reference.getKey()) < beforeCount, 1_200);

        // Úklid mřížky – vrátit případné zbytky (i po neúspěchu).
        for (int cell = 0; cell < plan.matrix().length; cell++) {
            if (plan.matrix()[cell] != null && gridMapping[cell] >= 0) {
                ctx.clicker().shiftClick(containerId, gridMapping[cell]);
            }
        }
        return consumed ? plan.id() : null;
    }

    /** Počet kusů materiálu v klientském modelu inventáře (sloty 9–44). */
    private static int countInInventory(BotContext ctx, ItemMapper mapper, Material material) {
        return Windows.inventorySection(ctx.clientInventory(), mapper)
                .count(m -> m == material);
    }

    /** Souhrn inventáře pro plánovač progrese. */
    private static CraftPlanner.State plannerState(Windows.SlotMaps inv) {
        java.util.Map<Material, Integer> items = new java.util.HashMap<>();
        inv.materials().forEach((slot, material) -> {
            if (material != null) {
                items.merge(material, Math.max(inv.counts().getOrDefault(slot, 1), 1),
                        Integer::sum);
            }
        });
        int cobbleStd = items.getOrDefault(Material.COBBLESTONE, 0);
        return new CraftPlanner.State(items,
                inv.findMaterial(PacketCraftingStation::isLog),
                inv.findMaterial(PacketCraftingStation::isPlanks),
                cobbleStd >= 3 ? Material.COBBLESTONE : Material.COBBLED_DEEPSLATE,
                inv.findMaterial(m -> m.name().endsWith("_WOOL")));
    }

    private static boolean isLog(Material material) {
        return material.name().endsWith("_LOG") || material.name().endsWith("_STEM");
    }

    private static boolean isPlanks(Material material) {
        return material.name().endsWith("_PLANKS");
    }

    /** Najde položený ponk v okolí bota. */
    private static BlockPos findNearbyTable(WorldView world, BlockPos center) {
        if (world == null) {
            return null;
        }
        for (int dx = -TABLE_RADIUS; dx <= TABLE_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -TABLE_RADIUS; dz <= TABLE_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (world.materialAt(pos) == Material.CRAFTING_TABLE) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
