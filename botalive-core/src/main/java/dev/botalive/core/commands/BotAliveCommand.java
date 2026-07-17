package dev.botalive.core.commands;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.bot.BotSpawnSpec;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.GoalRegistryImpl;
import dev.botalive.core.bot.BotImpl;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.persistence.BotRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Kořenový příkaz {@code /botalive} s podpříkazy a tab-complete.
 *
 * <p>Práva: administrace (create, remove, pause, ...) vyžaduje
 * {@code botalive.admin}. Teleportace je přístupná i hráčům –
 * {@code botalive.teleport} (k botovi) a {@code botalive.teleport.summon}
 * (přivolání bota) – s konfigurovatelným cooldownem; admin cooldown obchází.
 * Všechny odpovědi jdou přes Adventure API.</p>
 */
public final class BotAliveCommand implements TabExecutor {

    /** Oprávnění pro administraci botů. */
    public static final String PERM_ADMIN = "botalive.admin";

    /** Oprávnění: teleport hráče k botovi. */
    public static final String PERM_TELEPORT = "botalive.teleport";

    /** Oprávnění: přivolání bota k hráči. */
    public static final String PERM_SUMMON = "botalive.teleport.summon";

    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "create", "remove", "pause", "resume", "personality", "memory",
            "goal", "stats", "role");
    private static final List<String> SUBCOMMANDS = List.of(
            "create", "remove", "tp", "list", "pause", "resume",
            "personality", "memory", "goal", "stats", "role");

    private final BotManagerImpl botManager;
    private final GoalRegistryImpl goalRegistry;
    private final BotRepository repository;
    private final dev.botalive.core.config.BotAliveConfig config;
    private final dev.botalive.core.teleport.TeleportCooldowns cooldowns;

    /**
     * @param botManager   manager botů
     * @param goalRegistry registr cílů (pro /botalive goal)
     * @param repository   repozitář (pro /botalive stats)
     * @param config       konfigurace (teleport sekce)
     */
    public BotAliveCommand(BotManagerImpl botManager, GoalRegistryImpl goalRegistry,
                           BotRepository repository,
                           dev.botalive.core.config.BotAliveConfig config) {
        this.botManager = botManager;
        this.goalRegistry = goalRegistry;
        this.repository = repository;
        this.config = config;
        this.cooldowns = new dev.botalive.core.teleport.TeleportCooldowns(
                config.teleport().playerCooldownSeconds());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        // Administrace jen pro botalive.admin; tp a list mají vlastní práva.
        if (ADMIN_SUBCOMMANDS.contains(sub) && !sender.hasPermission(PERM_ADMIN)) {
            error(sender, "K tomu nemáš oprávnění");
            return true;
        }
        switch (sub) {
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "tp" -> teleport(sender, args);
            case "list" -> list(sender);
            case "pause" -> setPaused(sender, args, true);
            case "resume" -> setPaused(sender, args, false);
            case "personality" -> personality(sender, args);
            case "memory" -> memory(sender, args);
            case "goal" -> goal(sender, args);
            case "stats" -> stats(sender, args);
            case "role" -> role(sender, args);
            default -> help(sender);
        }
        return true;
    }

    // ------------------------------------------------------------ podpříkazy

    /** {@code /botalive create [jméno] [počet]} */
    private void create(CommandSender sender, String[] args) {
        int count = 1;
        String explicitName = null;
        if (args.length >= 2) {
            if (args[1].matches("\\d+")) {
                count = Math.min(50, Integer.parseInt(args[1]));
            } else {
                explicitName = args[1];
                if (args.length >= 3 && args[2].matches("\\d+")) {
                    count = Math.min(50, Integer.parseInt(args[2]));
                }
            }
        }
        for (int i = 0; i < count; i++) {
            String name = explicitName != null && count == 1
                    ? explicitName
                    : botManager.generateName();
            info(sender, "Vytvářím bota '" + name + "'...");
            botManager.create(BotSpawnSpec.named(name)).whenComplete((bot, error) -> {
                if (error != null) {
                    error(sender, "Bot '" + name + "': " + rootMessage(error));
                } else {
                    success(sender, "Bot '" + bot.name() + "' je ve hře ("
                            + bot.personality().archetype() + ")");
                }
            });
        }
    }

    /** {@code /botalive remove <jméno|all> [purge]} */
    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Použití: /botalive remove <jméno|all> [purge]");
            return;
        }
        boolean purge = args.length >= 3 && args[2].equalsIgnoreCase("purge");
        if (args[1].equalsIgnoreCase("all")) {
            List<Bot> bots = List.copyOf(botManager.all());
            bots.forEach(bot -> botManager.remove(bot.id(), purge));
            success(sender, "Odstraněno " + bots.size() + " botů" + (purge ? " včetně dat" : ""));
            return;
        }
        Optional<Bot> bot = botManager.byName(args[1]);
        if (bot.isEmpty()) {
            error(sender, "Bot '" + args[1] + "' neexistuje");
            return;
        }
        botManager.remove(bot.get().id(), purge).thenAccept(ok ->
                success(sender, "Bot '" + args[1] + "' odstraněn" + (purge ? " včetně dat" : "")));
    }

    /**
     * {@code /botalive tp <jméno>} – hráč k botovi ({@code botalive.teleport}),
     * {@code /botalive tp <jméno> here} – bot k hráči ({@code botalive.teleport.summon}),
     * {@code /botalive tp <jméno> <x> <y> <z> [svět]} – bot na souřadnice (admin).
     */
    private void teleport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Použití: /botalive tp <jméno> [here | x y z [svět]]");
            return;
        }
        Optional<Bot> bot = botManager.byName(args[1]);
        if (bot.isEmpty()) {
            error(sender, "Bot '" + args[1] + "' neexistuje");
            return;
        }
        boolean admin = sender.hasPermission(PERM_ADMIN);

        // Teleport na souřadnice (jen admin; funguje i z konzole).
        if (args.length >= 5) {
            if (!admin) {
                error(sender, "Teleport na souřadnice může jen administrátor");
                return;
            }
            teleportToCoordinates(sender, bot.get(), args);
            return;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Z konzole použij: /botalive tp <jméno> <x> <y> <z> [svět]");
            return;
        }
        boolean here = args.length >= 3 && args[2].equalsIgnoreCase("here");

        // Práva a limity pro běžné hráče (admin obchází vše).
        if (!admin) {
            String required = here ? PERM_SUMMON : PERM_TELEPORT;
            if (!player.hasPermission(required)) {
                error(sender, "K tomu nemáš oprávnění (" + required + ")");
                return;
            }
            if (!config.teleport().enabled()) {
                error(sender, "Teleportace k botům je vypnutá");
                return;
            }
            long remainingMs = cooldowns.remainingMs(player.getUniqueId());
            if (remainingMs > 0) {
                error(sender, "Počkej ještě " + (remainingMs / 1000 + 1) + " s");
                return;
            }
        }

        if (here) {
            bot.get().teleportToPlayer(player.getUniqueId()).thenAccept(ok ->
                    afterPlayerTeleport(sender, player, admin, ok,
                            "Bot '" + bot.get().name() + "' přenesen k tobě"));
        } else {
            bot.get().teleportPlayerToBot(player.getUniqueId()).thenAccept(ok ->
                    afterPlayerTeleport(sender, player, admin, ok,
                            "Přenesen k botovi '" + bot.get().name() + "'"));
        }
    }

    /** Společné vyhodnocení hráčského teleportu (cooldown + hláška). */
    private void afterPlayerTeleport(CommandSender sender, Player player, boolean admin,
                                     boolean ok, String successMessage) {
        if (ok) {
            if (!admin) {
                cooldowns.markUsed(player.getUniqueId());
            }
            success(sender, successMessage);
        } else {
            error(sender, "Teleport se nezdařil (bot není online)");
        }
    }

    /** Teleport bota na souřadnice přes API {@code Bot.teleport} (plný resync klienta). */
    private void teleportToCoordinates(CommandSender sender, Bot bot, String[] args) {
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            error(sender, "Neplatné souřadnice");
            return;
        }
        org.bukkit.World world;
        if (args.length >= 6) {
            world = Bukkit.getWorld(args[5]);
            if (world == null) {
                error(sender, "Svět '" + args[5] + "' neexistuje");
                return;
            }
        } else {
            String currentWorld = bot.snapshot().worldName();
            world = currentWorld != null ? Bukkit.getWorld(currentWorld)
                    : Bukkit.getWorlds().getFirst();
        }
        bot.teleport(new org.bukkit.Location(world, x, y, z)).thenAccept(ok -> {
            if (ok) {
                success(sender, "Bot '%s' teleportován na %.0f %.0f %.0f (%s)"
                        .formatted(bot.name(), x, y, z, world.getName()));
            } else {
                error(sender, "Teleport se nezdařil (bot offline, nebo svět není povolen)");
            }
        });
    }

    /**
     * {@code /botalive list} – plný výpis pro adminy; hráči s teleport právem
     * vidí jen jména a online stav (bez souřadnic).
     */
    private void list(CommandSender sender) {
        boolean admin = sender.hasPermission(PERM_ADMIN);
        if (!admin && !sender.hasPermission(PERM_TELEPORT) && !sender.hasPermission(PERM_SUMMON)) {
            error(sender, "K tomu nemáš oprávnění");
            return;
        }
        var bots = botManager.all();
        info(sender, "Boti (" + bots.size() + "):");
        for (Bot bot : bots) {
            var snapshot = bot.snapshot();
            if (!admin) {
                sender.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(bot.name(), NamedTextColor.AQUA))
                        .append(Component.text(snapshot.online() ? " (online)" : " (offline)",
                                snapshot.online() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                continue;
            }
            String where = snapshot.worldName() == null ? "?"
                    : "%s %.0f/%.0f/%.0f".formatted(snapshot.worldName(),
                    snapshot.x(), snapshot.y(), snapshot.z());
            sender.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(bot.name(), NamedTextColor.AQUA))
                    .append(Component.text(" (" + snapshot.role().displayName() + ")",
                            NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text(" [" + snapshot.state() + "] ", NamedTextColor.GRAY))
                    .append(Component.text(where, NamedTextColor.WHITE))
                    .append(Component.text("  ❤" + Math.round(snapshot.health())
                            + " 🍗" + snapshot.food(), NamedTextColor.GRAY))
                    .append(Component.text("  cíl: " + (snapshot.currentGoal() == null
                            ? "-" : snapshot.currentGoal()), NamedTextColor.YELLOW)));
        }
    }

    /** {@code /botalive pause|resume <jméno|all>} */
    private void setPaused(CommandSender sender, String[] args, boolean pause) {
        if (args.length < 2) {
            error(sender, "Použití: /botalive " + (pause ? "pause" : "resume") + " <jméno|all>");
            return;
        }
        List<Bot> targets;
        if (args[1].equalsIgnoreCase("all")) {
            targets = List.copyOf(botManager.all());
        } else {
            Optional<Bot> bot = botManager.byName(args[1]);
            if (bot.isEmpty()) {
                error(sender, "Bot '" + args[1] + "' neexistuje");
                return;
            }
            targets = List.of(bot.get());
        }
        targets.forEach(bot -> {
            if (pause) {
                bot.pause();
            } else {
                bot.resume();
            }
        });
        success(sender, (pause ? "Pozastaveno " : "Obnoveno ") + targets.size() + " botů");
    }

    /** {@code /botalive personality <jméno>} */
    private void personality(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        var personality = bot.get().personality();
        info(sender, "Osobnost bota '" + bot.get().name() + "' – " + personality.archetype()
                + " (seed " + personality.seed() + "):");
        for (Trait trait : Trait.values()) {
            double value = personality.trait(trait);
            int bars = (int) Math.round(value * 10);
            var line = Component.text(" " + trait.name().toLowerCase() + " ",
                            NamedTextColor.GRAY)
                    .append(Component.text("█".repeat(bars), NamedTextColor.GREEN))
                    .append(Component.text("░".repeat(10 - bars), NamedTextColor.DARK_GRAY))
                    .append(Component.text(" %.2f".formatted(value), NamedTextColor.WHITE));
            // Vývoj osobnosti: ukázat drift vůči základu ze seedu.
            if (personality instanceof dev.botalive.core.personality.PersonalityImpl impl) {
                double drift = value - impl.baseTrait(trait);
                if (Math.abs(drift) >= 0.03) {
                    line = line.append(Component.text(
                            " %s%.2f".formatted(drift > 0 ? "↗ +" : "↘ −", Math.abs(drift)),
                            NamedTextColor.AQUA));
                }
            }
            sender.sendMessage(line);
        }
    }

    /** {@code /botalive memory <jméno> [kategorie]} */
    private void memory(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        if (args.length >= 3) {
            MemoryKind kind;
            try {
                kind = MemoryKind.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                error(sender, "Neznámá kategorie. Dostupné: "
                        + Stream.of(MemoryKind.values()).map(Enum::name).toList());
                return;
            }
            List<MemoryRecord> records = bot.get().memory().recall(kind);
            info(sender, kind + " (" + records.size() + "):");
            records.stream().limit(15).forEach(record ->
                    sender.sendMessage(Component.text(" • %s %d/%d/%d (důležitost %.2f)"
                                    .formatted(record.world(), record.x(), record.y(), record.z(),
                                            record.importance()),
                            NamedTextColor.GRAY)));
            return;
        }
        info(sender, "Paměť bota '" + bot.get().name() + "' ("
                + bot.get().memory().size() + " vzpomínek):");
        for (MemoryKind kind : MemoryKind.values()) {
            int count = bot.get().memory().recall(kind).size();
            if (count > 0) {
                sender.sendMessage(Component.text(" " + kind.name().toLowerCase() + ": " + count,
                        NamedTextColor.GRAY));
            }
        }
    }

    /** {@code /botalive goal <jméno> [set <cíl>|clear]} */
    private void goal(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        if (args.length >= 4 && args[2].equalsIgnoreCase("set")) {
            if (bot.get().forceGoal(args[3])) {
                success(sender, "Cíl '" + args[3] + "' vynucen");
            } else {
                error(sender, "Neznámý cíl. Dostupné: " + goalRegistry.registeredIds());
            }
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            bot.get().forceGoal(null);
            success(sender, "Vynucený cíl zrušen");
            return;
        }
        // Přehled utility hodnot + intent (co bot dělá vlastními slovy).
        Map<String, Double> utilities = bot.get() instanceof BotImpl impl
                ? impl.utilitySnapshot() : Map.of();
        info(sender, "Cíle bota '" + bot.get().name() + "' (aktivní: "
                + bot.get().snapshot().currentGoal() + "):");
        if (bot.get() instanceof BotImpl impl) {
            String intent = impl.explainCurrentGoal();
            if (intent != null) {
                sender.sendMessage(Component.text(" záměr: „" + intent + "“",
                        NamedTextColor.AQUA));
            }
            String ambition = impl.ambitionLine();
            if (ambition != null) {
                sender.sendMessage(Component.text(" životní cíl: " + ambition,
                        NamedTextColor.GOLD));
            }
        }
        utilities.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> sender.sendMessage(Component.text(
                        " %s: %.1f".formatted(entry.getKey(), entry.getValue()),
                        NamedTextColor.GRAY)));
    }

    /** {@code /botalive role <jméno> [role|random]} – zobrazí/nastaví profesi. */
    private void role(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        if (args.length < 3) {
            info(sender, "Bot '" + bot.get().name() + "' má roli: "
                    + bot.get().role().displayName() + " (" + bot.get().role().name() + ")");
            sender.sendMessage(Component.text(" Dostupné: " + Stream
                            .of(dev.botalive.api.role.BotRole.values())
                            .map(r -> r.name().toLowerCase(Locale.ROOT)).toList(),
                    NamedTextColor.GRAY));
            return;
        }
        if (args[2].equalsIgnoreCase("random")) {
            var picked = dev.botalive.core.role.RolePicker.pick(bot.get().personality(),
                    new dev.botalive.core.util.BotRandom(System.nanoTime()));
            bot.get().role(picked);
            success(sender, "Bot '" + bot.get().name() + "' je nyní " + picked.displayName());
            return;
        }
        var parsed = dev.botalive.api.role.BotRole.parse(args[2]);
        if (parsed.isEmpty()) {
            error(sender, "Neznámá role. Dostupné: " + Stream
                    .of(dev.botalive.api.role.BotRole.values())
                    .map(r -> r.name().toLowerCase(Locale.ROOT)).toList());
            return;
        }
        bot.get().role(parsed.get());
        success(sender, "Bot '" + bot.get().name() + "' je nyní " + parsed.get().displayName());
    }

    /** {@code /botalive stats <jméno>} */
    private void stats(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        Bot target = bot.get();
        repository.loadStats(target.id()).thenAccept(stats -> {
            info(sender, "Statistiky bota '" + target.name() + "':");
            sender.sendMessage(Component.text(
                    " vytěženo: %d | postaveno: %d | smrti: %d | zabití: %d"
                            .formatted(stats.getOrDefault("blocks_mined", 0L),
                                    stats.getOrDefault("blocks_placed", 0L),
                                    stats.getOrDefault("deaths", 0L),
                                    stats.getOrDefault("kills", 0L)),
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    " zpráv: %d | nachozeno: %.1f km | odehráno: %d min | peníze: %.1f"
                            .formatted(stats.getOrDefault("messages_sent", 0L),
                                    stats.getOrDefault("distance_cm", 0L) / 100_000.0,
                                    stats.getOrDefault("playtime_seconds", 0L) / 60,
                                    target.wallet().balance()),
                    NamedTextColor.GRAY));
        });
    }

    // ------------------------------------------------------------- pomocníci

    private Optional<Bot> requireBot(CommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "Chybí jméno bota");
            return Optional.empty();
        }
        Optional<Bot> bot = botManager.byName(args[1]);
        if (bot.isEmpty()) {
            error(sender, "Bot '" + args[1] + "' neexistuje");
        }
        return bot;
    }

    private void help(CommandSender sender) {
        info(sender, "BotAlive – autonomní AI hráči");
        boolean admin = sender.hasPermission(PERM_ADMIN);
        boolean canTeleport = admin || sender.hasPermission(PERM_TELEPORT)
                || sender.hasPermission(PERM_SUMMON);
        for (String sub : SUBCOMMANDS) {
            boolean visible = switch (sub) {
                case "tp", "list" -> canTeleport;
                default -> admin;
            };
            if (visible) {
                sender.sendMessage(Component.text(" /botalive " + sub, NamedTextColor.GRAY));
            }
        }
        if (!canTeleport) {
            sender.sendMessage(Component.text(" (žádná dostupná akce – chybí oprávnění)",
                    NamedTextColor.DARK_GRAY));
        }
    }

    private void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BotAlive] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private void success(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BotAlive] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.GREEN)));
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[BotAlive] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.RED)));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    // ---------------------------------------------------------- tab-complete

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        boolean admin = sender.hasPermission(PERM_ADMIN);
        boolean canTeleport = admin || sender.hasPermission(PERM_TELEPORT)
                || sender.hasPermission(PERM_SUMMON);
        if (args.length == 1) {
            List<String> visible = SUBCOMMANDS.stream()
                    .filter(sub -> switch (sub) {
                        case "tp", "list" -> canTeleport;
                        default -> admin;
                    })
                    .toList();
            return filter(visible, args[0]);
        }
        if (!admin && !canTeleport) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>(botManager.all().stream().map(Bot::name).toList());
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "remove", "pause", "resume" -> names.add("all");
                case "create" -> {
                    return List.of();
                }
                default -> {
                }
            }
            return filter(names, args[1]);
        }
        if (args.length == 3) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "goal" -> filter(List.of("set", "clear"), args[2]);
                case "memory" -> filter(Stream.of(MemoryKind.values())
                        .map(k -> k.name().toLowerCase(Locale.ROOT)).toList(), args[2]);
                case "tp" -> filter(List.of("here"), args[2]);
                case "remove" -> filter(List.of("purge"), args[2]);
                case "role" -> {
                    List<String> options = new ArrayList<>(Stream
                            .of(dev.botalive.api.role.BotRole.values())
                            .map(r -> r.name().toLowerCase(Locale.ROOT)).toList());
                    options.add("random");
                    yield filter(options, args[2]);
                }
                default -> List.of();
            };
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("goal") && args[2].equalsIgnoreCase("set")) {
            return filter(goalRegistry.registeredIds(), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
