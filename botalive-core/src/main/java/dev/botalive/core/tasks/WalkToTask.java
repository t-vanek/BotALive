package dev.botalive.core.tasks;

import dev.botalive.api.bot.BotControl;

/**
 * Veřejný task „dojdi na pozici" – spustí navigaci a je hotový, jakmile bot
 * dorazí nebo se navigace vzdá. Stojí čistě na {@link BotControl} (žádný interní
 * kontext), takže se řídí přímo veřejným rozhraním.
 */
public final class WalkToTask implements dev.botalive.api.task.BotTask {

    private final int x;
    private final int y;
    private final int z;
    private boolean started;

    /**
     * @param x cílový blok X
     * @param y cílový blok Y
     * @param z cílový blok Z
     */
    public WalkToTask(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean tick(BotControl control) {
        if (!started) {
            control.navigateTo(x, y, z);
            started = true;
            return false; // cesta se teprve počítá
        }
        return !control.navigating(); // hotovo, když navigace skončí
    }

    @Override
    public void cancel(BotControl control) {
        control.stopNavigation();
    }
}
