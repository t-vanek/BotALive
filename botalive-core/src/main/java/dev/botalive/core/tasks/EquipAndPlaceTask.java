package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;

/**
 * Vezme materiál do ruky a položí ho na cílovou pozici – vnitřní task pro
 * veřejné {@code BotControl.placeBlock}. {@link PlaceBlockTask} sám pokládá jen
 * to, co bot drží; tenhle wrapper nejdřív materiál nasadí.
 */
public final class EquipAndPlaceTask implements BotTask {

    private final Material material;
    private final PlaceBlockTask place;
    private boolean equipped;

    /**
     * @param target   pozice, kde má blok vzniknout
     * @param material materiál k položení ({@code null} = položit, co bot drží)
     */
    public EquipAndPlaceTask(BlockPos target, Material material) {
        this.material = material;
        this.place = new PlaceBlockTask(target);
    }

    @Override
    public boolean tick(BotContext ctx) {
        if (!equipped) {
            if (material != null) {
                ServerSideView.Snapshot snapshot = ctx.serverView().latest();
                if (snapshot == null || !ctx.inventory().equipItem(snapshot, material)) {
                    return true; // materiál není k dispozici – task končí neúspěchem
                }
            }
            equipped = true;
        }
        return place.tick(ctx);
    }

    @Override
    public void cancel(BotContext ctx) {
        place.cancel(ctx);
    }
}
