package dev.botalive.core.tasks;

import dev.botalive.api.bot.BotControl;
import dev.botalive.core.ai.BotContext;

/**
 * Adaptér vnitřního {@link BotTask} (nad {@link BotContext}) na veřejný
 * {@link dev.botalive.api.task.BotTask} (nad {@link BotControl}).
 *
 * <p>Vestavěná taktická primitiva jsou napsaná nad plným interním kontextem;
 * cizí pluginy je řídí přes bezpečné {@link BotControl}. Adaptér drží kontext
 * zachycený při vytvoření (pro daného bota) a předává mu volání – parametr
 * {@code control} v {@link #tick} se ignoruje (jde o téhož bota).</p>
 */
public final class ApiTaskAdapter implements dev.botalive.api.task.BotTask {

    private final BotContext ctx;
    private final BotTask delegate;

    /**
     * @param ctx      interní kontext bota
     * @param delegate vnitřní task, který se má vykonat
     */
    public ApiTaskAdapter(BotContext ctx, BotTask delegate) {
        this.ctx = ctx;
        this.delegate = delegate;
    }

    @Override
    public boolean tick(BotControl control) {
        return delegate.tick(ctx);
    }

    @Override
    public void cancel(BotControl control) {
        delegate.cancel(ctx);
    }
}
