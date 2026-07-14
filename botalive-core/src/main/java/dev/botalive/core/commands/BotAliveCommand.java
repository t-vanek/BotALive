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
 * <p>Podpříkazy: create, remove, tp, list, pause, resume, personality,
 * memory, goal, stats. Všechny odpovědi jdou přes Adventure API.</p>
 */
public final class BotAliveCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "remove", "tp", "list", "pause", "resume",
            "personality", "memory", "goal", "stats");

    private final BotManagerImpl botManager;
    private final GoalRegistryImpl goalRegistry;
    private final BotRepository repository;

    /**
     * @param botManager   manager botů
     * @param goalRegistry registr cílů (pro /botalive goal)
     * @param repository   repozitář (pro /botalive stats)
     */
    public BotAliveCommand(BotManagerImpl botManager, GoalRegistryImpl goalRegistry,
                           BotRepository repository) {
        this.botManager = botManager;
        this.goalRegistry = goalRegistry;
        this.repository = repository;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
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

    /** {@code /botalive tp <jméno> [here]} – k botovi, nebo bota k sobě. */
    private void teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "Jen pro hráče");
            return;
        }
        if (args.length < 2) {
            error(sender, "Použití: /botalive tp <jméno> [here]");
            return;
        }
        Optional<Bot> bot = botManager.byName(args[1]);
        if (bot.isEmpty()) {
            error(sender, "Bot '" + args[1] + "' neexistuje");
            return;
        }
        Player botPlayer = Bukkit.getPlayer(bot.get().id());
        if (botPlayer == null) {
            error(sender, "Bot není online");
            return;
        }
        boolean here = args.length >= 3 && args[2].equalsIgnoreCase("here");
        if (here) {
            botPlayer.teleportAsync(player.getLocation())
                    .thenRun(() -> success(sender, "Bot přenesen k tobě"));
        } else {
            player.teleportAsync(botPlayer.getLocation())
                    .thenRun(() -> success(sender, "Přenesen k botovi '" + args[1] + "'"));
        }
    }

    /** {@code /botalive list} */
    private void list(CommandSender sender) {
        var bots = botManager.all();
        info(sender, "Boti (" + bots.size() + "):");
        for (Bot bot : bots) {
            var snapshot = bot.snapshot();
            String where = snapshot.worldName() == null ? "?"
                    : "%s %.0f/%.0f/%.0f".formatted(snapshot.worldName(),
                    snapshot.x(), snapshot.y(), snapshot.z());
            sender.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(bot.name(), NamedTextColor.AQUA))
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
            sender.sendMessage(Component.text(" " + trait.name().toLowerCase() + " ",
                            NamedTextColor.GRAY)
                    .append(Component.text("█".repeat(bars), NamedTextColor.GREEN))
                    .append(Component.text("░".repeat(10 - bars), NamedTextColor.DARK_GRAY))
                    .append(Component.text(" %.2f".formatted(value), NamedTextColor.WHITE)));
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
        // Přehled utility hodnot.
        Map<String, Double> utilities = bot.get() instanceof BotImpl impl
                ? impl.utilitySnapshot() : Map.of();
        info(sender, "Cíle bota '" + bot.get().name() + "' (aktivní: "
                + bot.get().snapshot().currentGoal() + "):");
        utilities.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> sender.sendMessage(Component.text(
                        " %s: %.1f".formatted(entry.getKey(), entry.getValue()),
                        NamedTextColor.GRAY)));
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
        for (String sub : SUBCOMMANDS) {
            sender.sendMessage(Component.text(" /botalive " + sub, NamedTextColor.GRAY));
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
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
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
