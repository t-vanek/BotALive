package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.inventory.ItemVariants;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;

/**
 * Pití lektvarů – reflexivní záchrana, ne plánovaná alchymie.
 *
 * <p>Boti lektvary nevaří; nosí je z barteru s pigliny a z vyloupených
 * truhel. Cíl se probouzí jen v nouzi: <b>odolnost ohni</b>, když bot hoří
 * nebo stojí v lávě (a efekt ještě neběží – sleduje se z UpdateMobEffect
 * paketů v {@code BotClientState}), <b>léčení/regenerace</b> při nízkém
 * zdraví. Typ lektvaru se čte z variant snapshotu ({@link ItemVariants}) –
 * bot nikdy nevypije láhev vody v domnění, že je to medicína.</p>
 *
 * <p>Pití je vanilla mechanika jako jídlo: use item a ~32 ticků držení;
 * úspěch potvrdí aplikovaný efekt (paket), u okamžitého léčení nárůst
 * zdraví.</p>
 */
public final class DrinkPotionGoal extends AbstractGoal {

    private enum Phase { EQUIP, DRINK, DONE }

    private Phase phase = Phase.EQUIP;
    private String effect;
    private Effect trackedEffect;
    private int ticks;
    private float startHealth;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public DrinkPotionGoal() {
        super("drink");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || ctx.clientState().dead()) {
            return 0;
        }
        // Hoří/stojí v lávě: odolnost ohni zastaví poškození okamžitě –
        // priorita nad bojem i většinou přežívání (1,6 s pití se vyplatí).
        if ((snapshot.onFire() || snapshot.inLava())
                && !ctx.clientState().effectActive(Effect.FIRE_RESISTANCE)
                && ItemVariants.hasPotion(snapshot, ItemVariants.FIRE_RESISTANCE)) {
            return 95;
        }
        float health = ctx.clientState().health();
        if (health <= 10 && ItemVariants.hasPotion(snapshot, ItemVariants.HEALING)) {
            return 80;
        }
        if (health <= 10 && !ctx.clientState().effectActive(Effect.REGENERATION)
                && ItemVariants.hasPotion(snapshot, ItemVariants.REGENERATION)) {
            return 70;
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.EQUIP;
        ticks = 0;
        startHealth = ctx.clientState().health();
        var snapshot = ctx.serverView().latest();
        // Pořadí odpovídá utility – pije se to, kvůli čemu se cíl probral.
        if (snapshot != null && (snapshot.onFire() || snapshot.inLava())
                && ItemVariants.hasPotion(snapshot, ItemVariants.FIRE_RESISTANCE)) {
            effect = ItemVariants.FIRE_RESISTANCE;
            trackedEffect = Effect.FIRE_RESISTANCE;
        } else if (ItemVariants.hasPotion(snapshot, ItemVariants.HEALING)) {
            effect = ItemVariants.HEALING;
            trackedEffect = null; // okamžité léčení – pozná se z nárůstu zdraví
        } else {
            effect = ItemVariants.REGENERATION;
            trackedEffect = Effect.REGENERATION;
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        var snapshot = ctx.serverView().latest();
        switch (phase) {
            case EQUIP -> {
                int slot = ItemVariants.findPotionSlot(snapshot, effect);
                if (slot < 0) {
                    finish(200);
                    return;
                }
                if (slot >= 9) {
                    // Lektvar v hlavním inventáři: přetáhnout na hotbar
                    // (číslování okna 0 se pro hlavní inventář kryje s Bukkit).
                    int dump = InventoryHelper.chooseHotbarDumpSlot(snapshot);
                    ctx.clicker().moveToHotbar(0, slot, dump >= 0 ? dump : 8);
                    if (++ticks > 40) {
                        finish(400); // přesun se nepotvrdil – zkusit jindy
                    }
                    return; // další tick uvidí lektvar v hotbaru (snapshot)
                }
                ctx.navigator().stop();
                ctx.actions().selectHotbar(slot);
                ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
                ticks = 0;
                phase = Phase.DRINK;
            }
            case DRINK -> {
                // Pití trvá 32 ticků; úspěch = efekt z paketu / nárůst zdraví.
                boolean applied = trackedEffect != null
                        ? ctx.clientState().effectActive(trackedEffect)
                        : ctx.clientState().health() > startHealth;
                if (applied || ++ticks > 45) {
                    finish(applied ? 100 : 400);
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    private void finish(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return ItemVariants.FIRE_RESISTANCE.equals(effect)
                ? "hořím! rychle lektvar odolnosti ohni"
                : "dopřávám si léčivý lektvar";
    }
}
