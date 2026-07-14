package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.crafting.CraftingService;
import dev.botalive.core.inventory.InventoryHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Výroba nástrojů a surovin – survival progrese bota.
 *
 * <p>Bot se zastaví, chvíli „přemýšlí" (delay podle inteligence), zamává rukou
 * a vyrobí další krok progrese (prkna → tyčky → ponk → dřevěné → kamenné
 * nástroje). Vyrábí po jednom kroku s pauzami – žádné strojové dávky.
 * Vlastní výrobu provádí {@link CraftingService} (server-side simulace,
 * zdůvodnění tamtéž).</p>
 */
public final class CraftGoal extends AbstractGoal {

    private final CraftingService crafting;

    private CompletableFuture<String> pending;
    private int thinkTicks;
    private int craftedCount;
    private int idleRounds;
    private int cooldownTicks;

    /**
     * @param crafting sdílená crafting služba
     */
    public CraftGoal(CraftingService crafting) {
        super("craft");
        this.crafting = crafting;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        // Levná heuristika bez sahání na živý inventář: má suroviny a chybí
        // mu nástroje? Přesné rozhodnutí udělá CraftingService na vlákně entity.
        boolean hasWood = snapshot.hasItem(m -> m.name().endsWith("_LOG")
                || m.name().endsWith("_PLANKS"));
        boolean missingTools = !snapshot.hasItem(
                m -> InventoryHelper.isTool(m, InventoryHelper.ToolType.PICKAXE))
                || !snapshot.hasItem(m -> InventoryHelper.isTool(m, InventoryHelper.ToolType.SWORD));
        if (!hasWood && !snapshot.hasItem(m -> m == org.bukkit.Material.COBBLESTONE)) {
            return 0;
        }
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        return (missingTools ? 14 : 4) + intelligence * 10;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        ctx.navigator().stop();
        pending = null;
        craftedCount = 0;
        idleRounds = 0;
        // Přemýšlení před výrobou – chytří boti méně.
        thinkTicks = (int) (ctx.humanizer().reactionDelayMs(900, 500) / 50);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (thinkTicks > 0) {
            thinkTicks--;
            return;
        }
        if (pending == null) {
            ctx.actions().swing(); // "sahá do inventáře"
            pending = crafting.craftNextInProgression(bot.id());
            return;
        }
        if (!pending.isDone()) {
            return;
        }
        String craftedId = pending.getNow(null);
        pending = null;
        if (craftedId == null) {
            idleRounds++;
            return;
        }
        craftedCount++;
        idleRounds = 0;
        // Pauza mezi kroky výroby + občas komentář.
        thinkTicks = ctx.rng().rangeInt(10, 40);
        if (ctx.rng().chance(0.15)) {
            ctx.chat().say("mám " + craftedId);
        }
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        // Konec: není co vyrábět (2 prázdná kola) nebo vyrobeno dost kroků.
        if (idleRounds >= 2 || craftedCount >= 6) {
            cooldownTicks = ctx.rng().rangeInt(400, 1200);
            return true;
        }
        return false;
    }
}
