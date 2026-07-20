package dev.botalive.core.bot;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.Bot;
import dev.botalive.api.bot.BotManager;
import dev.botalive.api.bot.BotSpawnSpec;
import dev.botalive.api.event.BotRemovedEvent;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.economy.BotWalletImpl;
import dev.botalive.core.economy.EconomyGateway;
import dev.botalive.core.economy.VaultBotWallet;
import dev.botalive.core.economy.VaultEconomy;
import dev.botalive.core.memory.BotMemoryImpl;
import dev.botalive.core.persistence.BotRepository;
import dev.botalive.core.via.ViaCompat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Správce životního cyklu botů.
 *
 * <p>Vytvoření bota: rezervace jména → načtení/založení identity v databázi
 * (osobnost, paměť, peněženka) → konstrukce {@link BotImpl} → připojení
 * klienta → volitelný teleport na spawn pozici. Vše asynchronně, server
 * nikdy nečeká.</p>
 */
public final class BotManagerImpl implements BotManager {

    private static final Logger LOG = LoggerFactory.getLogger(BotManagerImpl.class);

    private final BotAliveConfig config;
    private final BotRepository repository;
    private final GoalRegistry goalRegistry;
    private final BotImpl.SharedServices services;
    private final BotNameGenerator nameGenerator;
    private final dev.botalive.core.inventory.StarterKitService starterKit;

    private final Map<UUID, BotImpl> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    /** Serverová ekonomika (Vault); resolví se líně – poskytovatelé se registrují
     *  až ve svém onEnable, které může běžet po našem. Boti spawnují později. */
    private volatile EconomyGateway economyGateway;
    private volatile boolean economyResolved;

    /**
     * @param config       konfigurace
     * @param repository   repozitář
     * @param goalRegistry registr AI cílů
     * @param services     sdílené služby pro boty
     */
    public BotManagerImpl(BotAliveConfig config, BotRepository repository,
                          GoalRegistry goalRegistry, BotImpl.SharedServices services) {
        this.config = config;
        this.repository = repository;
        this.goalRegistry = goalRegistry;
        this.services = services;
        this.starterKit = new dev.botalive.core.inventory.StarterKitService(services.bridge());
        this.nameGenerator = new BotNameGenerator(config.bots().namePool(), config.bots().nameStyle());
    }

    /**
     * Vygeneruje volné jméno pro nového bota.
     *
     * @return jméno neobsazené botem ani online hráčem
     */
    public String generateName() {
        return nameGenerator.next(name ->
                byName.containsKey(name.toLowerCase(Locale.ROOT))
                        || Bukkit.getPlayerExact(name) != null);
    }

    /**
     * Jednorázová (líná) detekce serverové ekonomiky přes Vault.
     *
     * @return brána na ekonomiku, nebo {@code null} (interní peněženka)
     */
    private EconomyGateway resolveEconomy() {
        if (!economyResolved) {
            synchronized (this) {
                if (!economyResolved) {
                    if (config.economy().vault()) {
                        economyGateway = VaultEconomy.detect(services.bridge()).orElse(null);
                        if (economyGateway != null) {
                            LOG.info("Ekonomika botů poběží přes Vault (poskytovatel: {})",
                                    economyGateway.providerName());
                        } else {
                            LOG.info("Vault ekonomika není k dispozici – boti použijí "
                                    + "interní peněženku");
                        }
                    }
                    economyResolved = true;
                }
            }
        }
        return economyGateway;
    }

    @Override
    public CompletableFuture<Bot> create(BotSpawnSpec spec) {
        if (byId.size() >= config.bots().maxCount()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Dosažen limit botů (" + config.bots().maxCount() + ")"));
        }
        String nameKey = spec.name().toLowerCase(Locale.ROOT);
        if (byName.containsKey(nameKey)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Bot se jménem '" + spec.name() + "' už existuje"));
        }
        // Online-mode kontrola dává smysl jen při připojování na tento server;
        // u cizího serveru o jeho režimu nic nevíme (rozhodne login). Výjimka:
        // s gateway.client-auth se boti umí ověřit i proti online-mode serveru
        // nasměrovanému na vlastní gateway – pak refusal neplatí (opt-in admina).
        if (isLocalTarget() && Bukkit.getOnlineMode() && !config.gateway().clientAuth()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Server běží v online-mode – boti se připojují jako offline klienti. "
                            + "Použijte offline-mode server, proxy (Velocity) s offline backendem, "
                            + "nebo zapněte gateway.client-auth a nasměrujte session host serveru "
                            + "na vlastní gateway BotAlive."));
        }
        // Kompatibilita verzí: běží-li server na jiném protokolu než klient botů,
        // musí překládat ViaVersion/ViaBackwards – bez nich by login stejně selhal,
        // takže selžeme hned a s návodem. Vypnutelné přes network.version-check.
        if (isLocalTarget() && config.network().versionCheck()) {
            ViaCompat.Assessment via = ViaCompat.assessLocalServer();
            if (!via.connectable()) {
                return CompletableFuture.failedFuture(new IllegalStateException(via.message()));
            }
        }

        UUID botId = offlineUuid(spec.name());
        byName.put(nameKey, botId); // rezervace jména

        long seed = spec.personalitySeed() != null
                ? spec.personalitySeed()
                : ThreadLocalRandom.current().nextLong();

        // Identita z databáze (osobnost, paměť, peněženka, role) → konstrukce → připojení.
        return repository.loadOrCreatePersonality(botId, seed)
                .thenCombine(repository.loadMemories(botId), IdentityData::new)
                .thenCombine(repository.loadOrCreateWallet(botId, config.economy().startingBalance()),
                        IdentityData::withBalance)
                .thenCombine(repository.loadRole(botId), IdentityData::withRole)
                .thenCompose(identity -> {
                    BotMemoryImpl memory = new BotMemoryImpl(botId, repository, identity.memories(),
                            dev.botalive.core.memory.RelationDecay.fromConfig(config.memory()));
                    EconomyGateway gateway = config.economy().enabled() ? resolveEconomy() : null;
                    dev.botalive.api.economy.BotWallet wallet = gateway != null
                            ? new VaultBotWallet(botId, gateway, repository::saveWallet,
                                    config.economy().startingBalance())
                            : new BotWalletImpl(botId, repository, identity.balance(),
                                    config.economy().enabled());
                    BotStats stats = new BotStats(botId, repository);
                    Function<Bot, List<Goal>> goalFactory = goalRegistry::instantiateAll;

                    BotImpl bot = new BotImpl(botId, spec.name(), identity.personality(),
                            memory, wallet, stats, goalFactory, services);
                    byId.put(botId, bot);

                    repository.upsertBot(botId, spec.name(), null, 0, 0, 0);

                    // Role: uložená z DB, jinak výběr podle osobnosti (lze vypnout).
                    dev.botalive.api.role.BotRole role = dev.botalive.api.role.BotRole
                            .parse(identity.role()).orElse(null);
                    if (role == null) {
                        role = config.bots().randomRoles()
                                ? dev.botalive.core.role.RolePicker.pick(identity.personality(),
                                        new dev.botalive.core.util.BotRandom(
                                                identity.personality().seed()))
                                : dev.botalive.api.role.BotRole.NONE;
                    }
                    bot.role(role);
                    LOG.info("Vytvářím bota '{}' ({}, archetyp {}, role {})", spec.name(), botId,
                            identity.personality().archetype(), role.displayName());

                    String host = config.network().host();
                    int port = config.network().port() > 0 ? config.network().port() : Bukkit.getPort();
                    return bot.connect(host, port);
                })
                .thenCompose(bot -> applySpawnLocation(bot, spec))
                .thenCompose(bot -> maybeGiveStarterKit(bot).thenApply(ignored -> bot))
                .whenComplete((bot, error) -> {
                    if (error != null) {
                        LOG.error("Vytvoření bota '{}' selhalo: {}", spec.name(), error.getMessage());
                        byName.remove(nameKey);
                        BotImpl failed = byId.remove(botId);
                        if (failed != null) {
                            failed.markRemoved();
                        }
                    }
                });
    }

    /**
     * Předá startovní kit, pokud ho bot ještě nedostal.
     *
     * <p>Pořadí vůči {@code upsertBot} je zaručené: oba dotazy jdou přes tentýž
     * jednovláknový DB executor, takže řádek už v době čtení existuje.</p>
     *
     * @param bot právě naspawnovaný bot
     * @return future dokončení (kit je fire-and-forget na hlavním vlákně)
     */
    private CompletableFuture<Void> maybeGiveStarterKit(Bot bot) {
        if (!config.bots().starterKit()) {
            return CompletableFuture.completedFuture(null);
        }
        return repository.kitGiven(bot.id()).thenCompose(given -> {
            if (given) {
                return CompletableFuture.completedFuture(null);
            }
            starterKit.give(bot.id(), bot.role());
            return repository.markKitGiven(bot.id());
        }).exceptionally(error -> {
            LOG.warn("Startovní kit pro '{}' selhal: {}", bot.name(), error.toString());
            return null;
        });
    }

    /** Přenos identity mezi fázemi async pipeline. */
    private record IdentityData(dev.botalive.api.personality.Personality personality,
                                List<dev.botalive.api.memory.MemoryRecord> memories,
                                double balance,
                                String role) {
        IdentityData(dev.botalive.api.personality.Personality personality,
                     List<dev.botalive.api.memory.MemoryRecord> memories) {
            this(personality, memories, 0, null);
        }

        IdentityData withBalance(double newBalance) {
            return new IdentityData(personality, memories, newBalance, role);
        }

        IdentityData withRole(String newRole) {
            return new IdentityData(personality, memories, balance, newRole);
        }
    }

    /** Po spawnu přemístí serverového hráče-bota podle konfigurace/specu. */
    private CompletableFuture<Bot> applySpawnLocation(Bot bot, BotSpawnSpec spec) {
        if (spec.spawnLocation() != null) {
            return teleportTo(bot, spec.spawnLocation());
        }
        // Výpočet čte svět (výškové mapy, bloky) → MUSÍ běžet na hlavním vlákně.
        // Dřív se volal rovnou z tick vlákna a getHighestBlockYAt si tam
        // vynucoval synchronní načtení chunku mimo hlavní vlákno.
        World world = spawnWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(bot);
        }
        return services.bridge()
                .callAt(new Location(world, config.spawn().x(), 64, config.spawn().z()),
                        this::configuredSpawn)
                .thenCompose(target -> target == null
                        ? CompletableFuture.completedFuture(bot)
                        : teleportTo(bot, target));
    }

    /** Svět pro spawn podle configu (prázdné = první svět serveru). */
    private World spawnWorld() {
        String name = config.spawn().world();
        return name.isBlank() ? Bukkit.getWorlds().getFirst() : Bukkit.getWorld(name);
    }

    /** Přemístí bota na danou lokaci (přes vlákno entity, Folia-safe). */
    private CompletableFuture<Bot> teleportTo(Bot bot, Location target) {
        if (target == null) {
            return CompletableFuture.completedFuture(bot);
        }
        if (!config.worlds().allowed(target.getWorld().getName())) {
            LOG.warn("Spawn svět '{}' není povolen (worlds whitelist/blacklist)",
                    target.getWorld().getName());
            return CompletableFuture.completedFuture(bot);
        }
        Player player = Bukkit.getPlayer(bot.id());
        if (player == null) {
            return CompletableFuture.completedFuture(bot);
        }
        // Teleport přes vlákno entity (Folia-safe; voláme z async vlákna).
        return services.bridge()
                .callForEntity(player, () -> {
                    player.teleportAsync(target);
                    return bot;
                })
                .thenApply(result -> bot);
    }

    /**
     * Spawn lokace podle config.yml (world-spawn | fixed | random-around).
     *
     * <p>Čte výškovou mapu světa – volat jen z hlavního vlákna.</p>
     *
     * @return cílová lokace, nebo {@code null} když se má nechat na serveru
     */
    private Location configuredSpawn() {
        BotAliveConfig.Spawn spawn = config.spawn();
        World world = spawnWorld();
        if (world == null) {
            return null;
        }
        return switch (spawn.mode().toLowerCase(Locale.ROOT)) {
            case "fixed" -> new Location(world, spawn.x(), spawn.y(), spawn.z());
            case "random-around" -> scatter(world, spawn);
            default -> null; // world-spawn: nechat na serveru
        };
    }

    /**
     * Rozptýlí bota do mezikruží kolem středu.
     *
     * <p>Tři věci, které dřív chyběly a kvůli kterým se boti kupili na jednom
     * místě:</p>
     * <ul>
     *   <li>střed je standardně <b>spawn světa</b>, ne pevná nula z configu;</li>
     *   <li>vzdálenost jde přes {@code sqrt} – rovnoměrné losování poloměru
     *       sype dvě třetiny botů do vnitřní třetiny plochy (hustota ~1/r);</li>
     *   <li>{@code min-radius} drží boty z bezprostředního okolí spawnu.</li>
     * </ul>
     *
     * <p>Několik pokusů hledá souš: přistání v oceánu nebo v lávě by bota
     * rovnou zabilo a shluklo zpátky k spawnu po respawnu.</p>
     *
     * @param world svět
     * @param spawn konfigurace spawnu
     * @return bezpečná lokace na povrchu
     */
    private Location scatter(World world, BotAliveConfig.Spawn spawn) {
        Location center = spawn.aroundWorldSpawn()
                ? world.getSpawnLocation()
                : new Location(world, spawn.x(), spawn.y(), spawn.z());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int min = Math.max(0, spawn.minRadius());
        int max = Math.max(min + 1, spawn.radius());
        Location fallback = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble(Math.PI * 2);
            // sqrt → rovnoměrně po PLOŠE mezikruží, ne po poloměru
            double t = random.nextDouble();
            double distance = Math.sqrt(min * min + t * (max * max - min * min));
            double x = center.getX() + Math.cos(angle) * distance;
            double z = center.getZ() + Math.sin(angle) * distance;
            Location candidate = new Location(world,
                    x, world.getHighestBlockYAt((int) x, (int) z) + 1, z);
            if (fallback == null) {
                fallback = candidate;
            }
            if (isSafeGround(world, candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    /** @return {@code true} když je pod nohama souš (ne voda, láva ani void) */
    private boolean isSafeGround(World world, Location location) {
        var below = world.getBlockAt(location.getBlockX(),
                location.getBlockY() - 1, location.getBlockZ()).getType();
        if (below.isAir() || !below.isSolid()) {
            return false;
        }
        return below != org.bukkit.Material.LAVA && below != org.bukkit.Material.WATER
                && below != org.bukkit.Material.MAGMA_BLOCK
                && location.getBlockY() > world.getMinHeight() + 1;
    }

    @Override
    public CompletableFuture<Boolean> remove(UUID botId, boolean purge) {
        BotImpl bot = byId.remove(botId);
        if (bot == null) {
            return CompletableFuture.completedFuture(false);
        }
        byName.remove(bot.name().toLowerCase(Locale.ROOT));
        bot.markRemoved();
        new BotRemovedEvent(bot, purge).callEvent();

        if (purge) {
            if (services.settlements() != null) {
                services.settlements().removeBot(botId);
            }
            return repository.purgeBot(botId).thenApply(v -> true);
        }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public Optional<Bot> byId(UUID botId) {
        return Optional.ofNullable(byId.get(botId));
    }

    @Override
    public Optional<Bot> byName(String name) {
        UUID botId = byName.get(name.toLowerCase(Locale.ROOT));
        return botId == null ? Optional.empty() : byId(botId);
    }

    @Override
    public Collection<Bot> all() {
        return List.copyOf(byId.values());
    }

    @Override
    public int onlineCount() {
        return (int) byId.values().stream()
                .filter(bot -> bot.snapshot().online())
                .count();
    }

    /**
     * Odpojí všechny boty (vypnutí pluginu) a počká, až síťová vlákna zhasnou.
     *
     * <p>Odpojení dobíhá asynchronně; Paper po {@code onDisable} zavře plugin
     * classloader a líné donačítání (relokovaných) tříd ze síťových vláken by
     * pak házelo „zip file error" – a umí utnout i poslední write-behind
     * flush. Dvě fáze: nejdřív se odpojí všichni (běží souběžně), pak se čeká
     * se společným deadline – na nejpomalejšího, ne na součet.</p>
     */
    public void shutdownAll() {
        java.util.List<BotImpl> bots = new java.util.ArrayList<>(byId.values());
        for (BotImpl bot : bots) {
            bot.markRemoved();
        }
        long deadline = System.currentTimeMillis() + 3000;
        for (BotImpl bot : bots) {
            bot.awaitNetworkQuiesce(Math.max(50, deadline - System.currentTimeMillis()));
        }
        // Krátká milost pro teardown sdílené netty event-loop skupiny knihovny.
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byId.clear();
        byName.clear();
    }

    /** Míří boti na tento server (loopback + stejný port)? */
    private boolean isLocalTarget() {
        return config.network().targetsLocalServer(Bukkit.getPort());
    }

    /** Offline-mode UUID – stejný algoritmus jako server v offline režimu. */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
