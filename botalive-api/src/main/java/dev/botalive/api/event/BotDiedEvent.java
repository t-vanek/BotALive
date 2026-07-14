package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán, když bot zemře. Bot si smrt zapamatuje (kategorie DEATH/LOST_ITEMS)
 * a po humanizovaném zpoždění provede respawn.
 */
public class BotDiedEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    /**
     * @param bot       zemřelý bot
     * @param worldName svět úmrtí
     * @param x         blok X
     * @param y         blok Y
     * @param z         blok Z
     */
    public BotDiedEvent(Bot bot, String worldName, int x, int y, int z) {
        super(bot);
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @return svět úmrtí */
    public String worldName() {
        return worldName;
    }

    /** @return blok X místa úmrtí */
    public int x() {
        return x;
    }

    /** @return blok Y místa úmrtí */
    public int y() {
        return y;
    }

    /** @return blok Z místa úmrtí */
    public int z() {
        return z;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return handler list (Bukkit konvence)
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
