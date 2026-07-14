package dev.botalive.core;

import dev.botalive.api.bot.BotSpawnSpec;
import dev.botalive.core.bootstrap.CompositionRoot;
import dev.botalive.core.bootstrap.ServerEventListener;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.commands.BotAliveCommand;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.config.ConfigLoader;
import dev.botalive.core.via.ViaCompat;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/**
 * Vstupní bod pluginu BotAlive.
 *
 * <p>Drží pouze lifecycle (enable/disable) – veškeré zapojení služeb dělá
 * {@link CompositionRoot}. Podporuje Paper i Folia (používá výhradně
 * region-aware scheduler API).</p>
 */
public final class BotAlivePlugin extends JavaPlugin {

    private CompositionRoot root;

    @Override
    public void onEnable() {
        BotAliveConfig config = ConfigLoader.load(this);

        if (Bukkit.getOnlineMode()) {
            getLogger().warning("Server běží v online-mode! Boti jsou offline klienti a nepůjdou "
                    + "připojit. Použijte offline-mode server, nebo proxy (Velocity) s offline backendem.");
        }
        logVersionCompatibility(config);

        this.root = new CompositionRoot(this, config);

        // Bukkit integrace.
        Bukkit.getPluginManager().registerEvents(root.get(ServerEventListener.class), this);
        PluginCommand command = getCommand("botalive");
        if (command != null) {
            BotAliveCommand executor = root.get(BotAliveCommand.class);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Automatický spawn botů po startu serveru.
        if (config.bots().autoSpawnEnabled()) {
            scheduleAutoSpawn(config);
        }

        getLogger().info("BotAlive " + getPluginMeta().getVersion() + " zapnut. Limit botů: "
                + config.bots().maxCount() + ", databáze: " + config.persistence().type());
    }

    /**
     * Diagnostika kompatibility verzí při startu (podpora ViaVersion).
     *
     * <p>Boti mluví pevnou verzí protokolu; při neshodě se serverem musí
     * překládat ViaVersion/ViaBackwards. Chybí-li, upozorníme adminy hned
     * při startu – vytvoření bota pak selže se stejnou zprávou.</p>
     */
    private void logVersionCompatibility(BotAliveConfig config) {
        if (!config.network().targetsLocalServer(Bukkit.getPort())) {
            getLogger().info(ViaCompat.remoteTarget(config.network().host()).message());
            return;
        }
        ViaCompat.Assessment via = ViaCompat.assessLocalServer();
        if (!via.connectable()) {
            getLogger().warning(via.message() + (config.network().versionCheck()
                    ? "" : " (version-check: false – boti se přesto pokusí připojit)"));
        } else if (via.translated()) {
            getLogger().info(via.message());
        }
    }

    /** Rozfázovaný auto-spawn – boti nenaskakují v jedné vlně. */
    private void scheduleAutoSpawn(BotAliveConfig config) {
        BotManagerImpl manager = root.get(BotManagerImpl.class);
        int count = Math.min(config.bots().autoSpawnCount(), config.bots().maxCount());
        long baseDelayTicks = config.bots().autoSpawnDelayS() * 20L;

        for (int i = 0; i < count; i++) {
            // Každý bot s náhradním rozestupem 2–8 s, ať připojení nepůsobí uměle.
            long delayTicks = baseDelayTicks + i * (40L + (long) (Math.random() * 120));
            Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
                String name = manager.generateName();
                manager.create(BotSpawnSpec.named(name)).whenComplete((bot, error) -> {
                    if (error != null) {
                        getLogger().warning("Auto-spawn bota '" + name + "' selhal: "
                                + error.getMessage());
                    }
                });
            }, delayTicks);
        }
        getLogger().info("Naplánován auto-spawn " + count + " botů");
    }

    @Override
    public void onDisable() {
        if (root != null) {
            try {
                root.shutdown();
            } catch (Exception e) {
                getLogger().severe("Chyba při vypínání: " + e);
            }
            root = null;
        }
        getLogger().info("BotAlive vypnut");
    }
}
