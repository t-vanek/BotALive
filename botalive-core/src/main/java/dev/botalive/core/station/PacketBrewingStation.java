package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.crafting.BrewPlanner;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;

import java.util.concurrent.CompletableFuture;

/**
 * Paketová stanice varných stojanů – nakládání a vybírání container kliky.
 *
 * <p>Rozvržení okna stojanu: 0–2 = lahve, 3 = přísada, 4 = palivo, dále
 * hráčova sekce; vlastnost 0 = zbývající čas vaření, 1 = palivoměr.
 * Nakládá se explicitními dvojicemi kliků (zvednout stack → položit do
 * slotu), stejně jako {@code PacketSmithingStation} – shift-klik má ve
 * stojanu nejednoznačné směrování (prach je palivo i přísada).</p>
 *
 * <p>Vědomé zjednodušení: varianty lahví se z okna nečtou (komponenty
 * container itemů se neparsují), nakládá se libovolná láhev {@code POTION}.
 * Nevalidní kombinaci server prostě neuvaří – timeout cíle ji přes
 * {@code collect(force)} vrátí do batohu a nic se neztratí.</p>
 */
public final class PacketBrewingStation implements BrewingStation {

    private static final int SLOT_BOTTLE_FIRST = 0;
    private static final int SLOT_BOTTLE_LAST = 2;
    private static final int SLOT_INGREDIENT = 3;
    private static final int SLOT_FUEL = 4;

    /** Vlastnost okna: zbývající ticky vaření (400 → 0). */
    private static final int PROPERTY_BREW_TIME = 0;

    /** Vlastnost okna: palivoměr (0–20 vsázek). */
    private static final int PROPERTY_FUEL = 1;

    @Override
    public CompletableFuture<LoadReport> load(BotContext ctx, String worldName, BlockPos pos,
                                              Material ingredient, BrewPlanner.Base base) {
        return StationFlow.run("brew-in-" + ctx.bot().name(), LoadReport.EMPTY, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return LoadReport.EMPTY;
            }
            ContainerView view = awaitStand(ctx);
            if (view == null) {
                return LoadReport.EMPTY;
            }
            // Palivo: prázdný palivoměr dostane blaze prach.
            if (view.property(PROPERTY_FUEL) < 1
                    && Windows.amountAt(view, SLOT_FUEL) == 0) {
                movePlayerStack(ctx, view, mapper, m -> m == Material.BLAZE_POWDER,
                        SLOT_FUEL);
            }
            // Lahve do volných slotů (varianty se z okna nečtou – viz doc).
            int bottles = 0;
            for (int slot = SLOT_BOTTLE_FIRST; slot <= SLOT_BOTTLE_LAST; slot++) {
                if (Windows.amountAt(view, slot) > 0) {
                    bottles++;
                    continue;
                }
                if (movePlayerStack(ctx, view, mapper, m -> m == Material.POTION, slot)) {
                    bottles++;
                }
            }
            // Přísada – jen do prázdného slotu a jen s lahvemi ve stojanu.
            boolean ingredientLoaded = Windows.amountAt(view, SLOT_INGREDIENT) > 0;
            if (!ingredientLoaded && bottles > 0) {
                ingredientLoaded = movePlayerStack(ctx, view, mapper,
                        m -> m == ingredient, SLOT_INGREDIENT);
            }
            return new LoadReport(bottles, ingredientLoaded);
        });
    }

    @Override
    public CompletableFuture<Integer> collect(BotContext ctx, String worldName, BlockPos pos,
                                              boolean force) {
        return StationFlow.run("brew-out-" + ctx.bot().name(), 0, () -> {
            ContainerView view = awaitStand(ctx);
            if (view == null) {
                return 0;
            }
            boolean brewing = view.property(PROPERTY_BREW_TIME) > 0
                    || Windows.amountAt(view, SLOT_INGREDIENT) > 0;
            if (!force && brewing) {
                return 0;
            }
            int taken = 0;
            for (int slot = SLOT_BOTTLE_FIRST; slot <= SLOT_BOTTLE_LAST; slot++) {
                int amount = Windows.amountAt(view, slot);
                if (amount == 0) {
                    continue;
                }
                ctx.clicker().shiftClick(view.containerId(), slot);
                final int slotIndex = slot;
                StationFlow.await(() -> Windows.amountAt(view, slotIndex) == 0, 800);
                if (Windows.amountAt(view, slot) == 0) {
                    taken++;
                }
                StationFlow.humanPause();
            }
            if (force && Windows.amountAt(view, SLOT_INGREDIENT) > 0) {
                ctx.clicker().shiftClick(view.containerId(), SLOT_INGREDIENT);
                StationFlow.await(() -> Windows.amountAt(view, SLOT_INGREDIENT) == 0, 800);
            }
            return taken;
        });
    }

    /** Počká na otevřené okno varného stojanu. */
    private static ContainerView awaitStand(BotContext ctx) throws InterruptedException {
        return StationFlow.awaitWindow(ctx,
                v -> v.type() == ContainerType.BREWING_STAND, 3_000);
    }

    /**
     * Přenese jeden vyhovující stack hráčovy sekce do cílového slotu okna
     * dvojicí kliků (zvednout → položit); zbytek stacku se vrací zpět.
     *
     * @return {@code true} pokud v cílovém slotu něco přistálo
     */
    private static boolean movePlayerStack(BotContext ctx, ContainerView view,
                                           ItemMapper mapper,
                                           java.util.function.Predicate<Material> filter,
                                           int targetSlot) throws InterruptedException {
        for (int slot = Windows.playerStart(view); slot < view.totalSlots(); slot++) {
            Material material = Windows.materialAt(view, mapper, slot);
            if (material == null || !filter.test(material)) {
                continue;
            }
            ctx.clicker().leftClick(view.containerId(), slot);
            StationFlow.humanPause();
            ctx.clicker().leftClick(view.containerId(), targetSlot);
            StationFlow.humanPause();
            // Zbytek zvednutého stacku (přísada/palivo jde po slotech) zpět.
            ctx.clicker().leftClick(view.containerId(), slot);
            final int target = targetSlot;
            StationFlow.await(() -> Windows.amountAt(view, target) > 0, 800);
            return Windows.amountAt(view, targetSlot) > 0;
        }
        return false;
    }
}
