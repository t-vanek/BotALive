package dev.botalive.core.ai.goals;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.bot.Bot;
import dev.botalive.core.ai.BotContext;

/**
 * Společný základ vestavěných cílů.
 *
 * <p>Poskytuje pohodlný přístup k internímu {@link BotContext} a prázdné
 * implementace lifecycle metod – konkrétní cíle přepisují jen to, co potřebují.</p>
 */
public abstract class AbstractGoal implements Goal {

    private final String id;

    /**
     * @param id stabilní identifikátor cíle
     */
    protected AbstractGoal(String id) {
        this.id = id;
    }

    @Override
    public final String id() {
        return id;
    }

    /**
     * @param bot API bot
     * @return interní kontext bota
     */
    protected static BotContext ctx(Bot bot) {
        return BotContext.of(bot);
    }

    @Override
    public void start(Bot bot) {
        // výchozí: nic
    }

    @Override
    public void stop(Bot bot) {
        // výchozí: zrušit navigaci, ať po cíli nezůstane rozjetá cesta
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return false;
    }

    /**
     * Položí vlastní vyrobenou stanici (pec, truhlu, ponk) na zem vedle bota.
     *
     * <p>Najde volný sousední sloupec s pevnou podlahou, vezme stanici do ruky
     * a klikne na podlahu – jako hráč. Ověření nechává na world view (GO fáze
     * cíle k pozici stejně dojde a klikne na ni).</p>
     *
     * @param ctx     kontext bota
     * @param station materiál stanice v inventáři
     * @return pozice, kde stanice vznikla, nebo {@code null}
     */
    protected static dev.botalive.core.util.BlockPos placeOwnStation(
            BotContext ctx, org.bukkit.Material station) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || ctx.worldView() == null
                || !snapshot.hasItem(m -> m == station)) {
            return null;
        }
        dev.botalive.core.util.BlockPos feet = ctx.position().toBlockPos();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            var target = feet.offset(d[0], 0, d[1]);
            var below = target.down();
            if (ctx.worldView().traitsAt(target).passable()
                    && ctx.worldView().traitsAt(target.up()).passable()
                    && ctx.worldView().traitsAt(below).solid()) {
                if (!ctx.inventory().equipItem(snapshot, station)) {
                    return null;
                }
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        below.center().add(0, 0.5, 0));
                ctx.actions().useItemOn(below,
                        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.UP);
                return target;
            }
        }
        return null;
    }

    /**
     * Lidsky čitelný popis toho, co bot v rámci cíle právě dělá a proč.
     *
     * <p>Slouží intent vrstvě: {@code /botalive goal} ho zobrazuje, chat s ním
     * odpovídá na otázku „co děláš?" a boti jím občas komentují své záměry.
     * Vrací {@code null}, když cíl nemá co říct (použije se obecná odpověď).</p>
     *
     * @param bot bot vykonávající cíl
     * @return krátká věta v první osobě, nebo {@code null}
     */
    public String explain(Bot bot) {
        return null;
    }
}
