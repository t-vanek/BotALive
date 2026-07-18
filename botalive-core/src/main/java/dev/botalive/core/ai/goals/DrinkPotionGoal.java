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
                && carries(snapshot, ItemVariants.FIRE_RESISTANCE)) {
            return 95;
        }
        float health = ctx.clientState().health();
        if (health <= 10 && carries(snapshot, ItemVariants.HEALING)) {
            return 80;
        }
        if (health <= 10 && !ctx.clientState().effectActive(Effect.REGENERATION)
                && carries(snapshot, ItemVariants.REGENERATION)) {
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
                && carries(snapshot, ItemVariants.FIRE_RESISTANCE)) {
            effect = ItemVariants.FIRE_RESISTANCE;
            trackedEffect = Effect.FIRE_RESISTANCE;
        } else if (carries(snapshot, ItemVariants.HEALING)) {
            effect = ItemVariants.HEALING;
            trackedEffect = null; // okamžité léčení – pozná se z nárůstu zdraví
        } else {
            effect = ItemVariants.REGENERATION;
            trackedEffect = Effect.REGENERATION;
        }
    }

    /** Nese bot efekt v pitelné, nebo vrhací podobě? */
    private static boolean carries(dev.botalive.core.bot.ServerSideView.Snapshot snapshot,
                                   String effect) {
        return ItemVariants.findPotionSlot(snapshot, effect) >= 0
                || ItemVariants.findSplashSlot(snapshot, effect) >= 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        var snapshot = ctx.serverView().latest();
        switch (phase) {
            case EQUIP -> {
                // Hoří-li bot, je rychlejší splash pod nohy (okamžitý) než
                // 1,6 s pití; jinak se preferuje pitelná láhev (nic nevyprchá).
                boolean emergency = ItemVariants.FIRE_RESISTANCE.equals(effect);
                int drink = ItemVariants.findPotionSlot(snapshot, effect);
                int splash = ItemVariants.findSplashSlot(snapshot, effect);
                boolean throwIt = emergency ? splash >= 0 : drink < 0;
                int slot = throwIt ? splash : drink;
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
                // Splash se hází kolmo pod nohy (pitch 90°), pití drží use.
                ctx.actions().useItem(ctx.humanizer().yaw(),
                        throwIt ? 90f : ctx.humanizer().pitch());
                ticks = throwIt ? 30 : 0; // vrh je okamžitý – kratší ověření
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
