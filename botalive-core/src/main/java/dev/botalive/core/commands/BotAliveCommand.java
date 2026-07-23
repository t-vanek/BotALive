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
            "goal", "stats", "role", "settlements", "diplomacy", "end", "path",
            "overview");
    private static final List<String> SUBCOMMANDS = List.of(
            "create", "remove", "tp", "list", "pause", "resume", "personality",
            "memory", "goal", "stats", "role", "settlements", "diplomacy", "end",
            "path", "overview", "hire", "dismiss", "codex");

    /**
     * @return jména všech vestavěných podpříkazů – vyhrazená pro registr
     *         cizích podpříkazů ({@link SubcommandRegistryImpl})
     */
    public static java.util.Set<String> builtInSubcommands() {
        java.util.Set<String> all = new java.util.HashSet<>(SUBCOMMANDS);
        all.addAll(ADMIN_SUBCOMMANDS);
        return all;
    }

    private final BotManagerImpl botManager;
    private final GoalRegistryImpl goalRegistry;
    private final BotRepository repository;
    private final dev.botalive.core.config.BotAliveConfig config;
    private final dev.botalive.core.settlement.SettlementService settlements;
    private final dev.botalive.core.settlement.DiplomacyService diplomacy;
    private final dev.botalive.core.economy.EmploymentService employment;
    private final dev.botalive.core.pathfinding.NavigationService navigation;
    private final SubcommandRegistryImpl subcommands;
    private final dev.botalive.core.role.RoleRegistryImpl roles;
    private final dev.botalive.core.teleport.TeleportCooldowns cooldowns;

    /**
     * @param botManager   manager botů
     * @param goalRegistry registr cílů (pro /botalive goal)
     * @param repository   repozitář (pro /botalive stats)
     * @param config       konfigurace (teleport sekce)
     * @param settlements  služba vesnic (pro /botalive settlements)
     * @param diplomacy    diplomacie sídel (pro /botalive diplomacy)
     * @param employment   najímání botů (pro /botalive hire a dismiss)
     * @param navigation   pathfinding (metriky pro /botalive path)
     * @param subcommands  registr cizích podpříkazů
     * @param roles        registr profesí (pro /botalive role)
     */
    public BotAliveCommand(BotManagerImpl botManager, GoalRegistryImpl goalRegistry,
                           BotRepository repository,
                           dev.botalive.core.config.BotAliveConfig config,
                           dev.botalive.core.settlement.SettlementService settlements,
                           dev.botalive.core.settlement.DiplomacyService diplomacy,
                           dev.botalive.core.economy.EmploymentService employment,
                           dev.botalive.core.pathfinding.NavigationService navigation,
                           SubcommandRegistryImpl subcommands,
                           dev.botalive.core.role.RoleRegistryImpl roles) {
        this.botManager = botManager;
        this.goalRegistry = goalRegistry;
        this.repository = repository;
        this.config = config;
        this.settlements = settlements;
        this.diplomacy = diplomacy;
        this.employment = employment;
        this.navigation = navigation;
        this.subcommands = subcommands;
        this.roles = roles;
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
            case "settlements" -> settlements(sender);
            case "diplomacy" -> diplomacy(sender);
            case "hire" -> hire(sender, args);
            case "dismiss" -> dismiss(sender, args);
            case "end" -> endPortal(sender, args);
            case "path" -> path(sender, args);
            case "overview" -> overview(sender);
            case "codex" -> codex(sender, args);
            default -> dispatchCustom(sender, sub, args);
        }
        return true;
    }

    /**
     * Neznámé jméno předá cizímu podpříkazu z registru (izolovaně – výjimka
     * pluginu nesmí shodit příkaz). Argumenty se předávají bez jména podpříkazu.
     */
    private void dispatchCustom(CommandSender sender, String sub, String[] args) {
        dev.botalive.api.command.BotSubcommand custom = subcommands.byName(sub);
        if (custom == null) {
            help(sender);
            return;
        }
        String permission = custom.permission();
        if (permission != null && !sender.hasPermission(permission)) {
            error(sender, "K tomu nemáš oprávnění");
            return;
        }
        try {
            custom.execute(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
        } catch (Exception e) {
            error(sender, "Podpříkaz '" + sub + "' selhal: " + rootMessage(e));
        }
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

    /**
     * {@code /botalive overview} – flotilový přehled na jednu obrazovku:
     * histogram aktivních cílů, podezřele nehybní boti (okno včasného varování
     * před 30s zásahem watchdogu) a souhrn A* metrik.
     */
    private void overview(CommandSender sender) {
        var bots = botManager.all();
        long online = bots.stream().filter(b -> b.snapshot().online()).count();
        info(sender, "Přehled (" + bots.size() + " botů, " + online + " online):");

        java.util.Map<String, Integer> goals = new java.util.HashMap<>();
        for (Bot bot : bots) {
            String goal = bot.snapshot().currentGoal();
            goals.merge(goal == null ? "-" : goal, 1, Integer::sum);
        }
        String histogram = goals.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(java.util.Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(java.util.stream.Collectors.joining(" · "));
        sender.sendMessage(Component.text(" cíle: " + histogram, NamedTextColor.YELLOW));

        List<String> still = new ArrayList<>();
        for (Bot bot : bots) {
            if (bot instanceof BotImpl impl && impl.ticksWithoutMovement() >= 300) {
                var snapshot = bot.snapshot();
                still.add("%s (%s, %.0f/%.0f/%.0f, %d s)".formatted(bot.name(),
                        snapshot.currentGoal() == null ? "-" : snapshot.currentGoal(),
                        snapshot.x(), snapshot.y(), snapshot.z(),
                        impl.ticksWithoutMovement() / 20));
            }
        }
        sender.sendMessage(still.isEmpty()
                ? Component.text(" bez pohybu 15+ s: nikdo", NamedTextColor.GREEN)
                : Component.text(" bez pohybu 15+ s: " + String.join(", ", still),
                        NamedTextColor.RED));

        var s = navigation.stats().snapshot();
        long requests = Math.max(1, s.requests());
        sender.sendMessage(Component.text(
                " A*: %d výpočtů, %d %% prázdných, %d %% timeout".formatted(
                        s.requests(), s.empty() * 100 / requests,
                        s.timedOut() * 100 / requests),
                NamedTextColor.GRAY));
    }

    /** {@code /botalive settlements} – přehled vesnic botů. */
    private void settlements(CommandSender sender) {
        var all = settlements.all();
        if (all.isEmpty()) {
            info(sender, "Žádná vesnice zatím nestojí – boti se teprve seznamují.");
            return;
        }
        info(sender, "Vesnice botů (" + all.size() + "):");
        for (var settlement : all) {
            String founderName = settlement.founder() == null ? "?"
                    : botManager.byId(settlement.founder()).map(Bot::name).orElse("?");
            List<String> memberNames = new ArrayList<>();
            int houses = 0;
            for (var member : settlement.members()) {
                memberNames.add(botManager.byId(member.botId())
                        .map(Bot::name).orElse("…"));
                if (member.plotOrigin() != null) {
                    houses++;
                }
            }
            sender.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(settlement.name(), NamedTextColor.AQUA))
                    .append(Component.text(" [" + settlement.tier().displayName() + "]",
                            NamedTextColor.GOLD))
                    .append(Component.text("  " + settlement.world() + " "
                                    + settlement.center().x() + "/" + settlement.center().y()
                                    + "/" + settlement.center().z(),
                            NamedTextColor.WHITE))
                    .append(Component.text("  zakladatel: " + founderName,
                            NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text("  starosta: " + (settlement.mayor() == null
                                    ? "?" : botManager.byId(settlement.mayor())
                                            .map(Bot::name).orElse("?")),
                            NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text("  členů: " + settlement.members().size()
                            + " (parcel: " + houses + ", domů: " + settlement.houses() + ")",
                            NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("   " + String.join(", ", memberNames),
                    NamedTextColor.GRAY));
            for (var project : settlement.projects()) {
                sender.sendMessage(Component.text("   ⚒ " + project.kind() + " "
                                + project.origin().x() + "/" + project.origin().y() + "/"
                                + project.origin().z()
                                + (project.done() ? " (hotovo)" : " (staví se)"),
                        NamedTextColor.DARK_AQUA));
            }
            List<String> enemies = diplomacy.atWarWith(settlement.id());
            if (!enemies.isEmpty()) {
                sender.sendMessage(Component.text("   ⚔ ve válce s: "
                        + String.join(", ", enemies), NamedTextColor.RED));
            }
        }
    }

    /** Rozjednané najmutí čekající na potvrzení hráčem. */
    private record PendingHire(String botName, dev.botalive.core.economy.EmploymentService.Kind kind,
                               int days, double price, long expiresAtMs) {
    }

    private final java.util.Map<java.util.UUID, PendingHire> pendingHires =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@code /botalive hire <bot> <worker|guard> [dny]} – nabídka mzdy;
     * {@code /botalive hire <bot> confirm} – potvrzení (pak bot čeká na /pay).
     */
    private void hire(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            error(sender, "Najímat boty může jen hráč ve hře");
            return;
        }
        if (!config.economy().employment().enabled()) {
            error(sender, "Najímání botů je na tomto serveru vypnuté");
            return;
        }
        if (args.length < 3) {
            error(sender, "Použití: /botalive hire <bot> <worker|guard> [dny] "
                    + "a pak /botalive hire <bot> confirm");
            return;
        }
        Optional<Bot> found = botManager.byName(args[1]);
        if (found.isEmpty()) {
            error(sender, "Bot '" + args[1] + "' neexistuje");
            return;
        }
        Bot bot = found.get();
        var snapshot = bot.snapshot();
        boolean nearby = snapshot.online()
                && player.getWorld().getName().equals(snapshot.worldName())
                && player.getLocation().distanceSquared(new org.bukkit.Location(
                        player.getWorld(), snapshot.x(), snapshot.y(), snapshot.z())) <= 16 * 16;
        if (!nearby) {
            error(sender, "Dojdi za botem – najímá se osobně (do 16 bloků)");
            return;
        }
        var employmentService = this.employment;
        if (args[2].equalsIgnoreCase("confirm")) {
            PendingHire pending = pendingHires.get(player.getUniqueId());
            if (pending == null || !pending.botName().equalsIgnoreCase(bot.name())
                    || System.currentTimeMillis() > pending.expiresAtMs()) {
                error(sender, "Není co potvrzovat – nejdřív si řekni o nabídku: "
                        + "/botalive hire " + bot.name() + " <worker|guard> [dny]");
                return;
            }
            pendingHires.remove(player.getUniqueId());
            var quote = employmentService.beginHire(bot, player.getUniqueId(),
                    player.getName(), pending.kind(), pending.days());
            if (quote.decline() != dev.botalive.core.economy.EmploymentService.Decline.NONE) {
                error(sender, declineMessage(bot.name(), quote.decline()));
                return;
            }
            if (config.economy().employment().requirePayment()) {
                success(sender, bot.name() + " kývnul – pošli mzdu: /pay "
                        + bot.name() + " " + dev.botalive.core.economy.EmploymentService
                                .priceLabel(quote.price())
                        + " (do 3 minut, jinak nabídka padá)");
            } else {
                success(sender, bot.name() + " nastupuje do služby ("
                        + kindLabel(pending.kind()) + ", " + quote.days() + " d)");
            }
            return;
        }
        dev.botalive.core.economy.EmploymentService.Kind kind;
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "worker" -> kind = dev.botalive.core.economy.EmploymentService.Kind.WORKER;
            case "guard" -> kind = dev.botalive.core.economy.EmploymentService.Kind.GUARD;
            default -> {
                error(sender, "Druh práce je worker (dělník) nebo guard (bodyguard)");
                return;
            }
        }
        int days = 1;
        if (args.length >= 4) {
            try {
                days = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                error(sender, "Počet dní musí být číslo");
                return;
            }
        }
        var quote = employmentService.quote(bot, player.getUniqueId(), kind, days);
        if (quote.decline() != dev.botalive.core.economy.EmploymentService.Decline.NONE) {
            error(sender, declineMessage(bot.name(), quote.decline()));
            return;
        }
        pendingHires.put(player.getUniqueId(), new PendingHire(bot.name(), kind,
                quote.days(), quote.price(), System.currentTimeMillis() + 60_000));
        info(sender, bot.name() + " si za " + kindLabel(kind) + " na "
                + quote.days() + " d řekne "
                + dev.botalive.core.economy.EmploymentService.priceLabel(quote.price())
                + ". Potvrď: /botalive hire " + bot.name() + " confirm");
    }

    /** {@code /botalive dismiss <bot>} – výpověď najatému botovi. */
    private void dismiss(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            error(sender, "Propouštět může jen hráč ve hře");
            return;
        }
        if (args.length < 2) {
            error(sender, "Použití: /botalive dismiss <bot>");
            return;
        }
        Optional<Bot> found = botManager.byName(args[1]);
        if (found.isEmpty()) {
            error(sender, "Bot '" + args[1] + "' neexistuje");
            return;
        }
        if (employment.dismiss(found.get().id(), player.getUniqueId())) {
            success(sender, found.get().name() + " propuštěn ze služby "
                    + "(mzda se nevrací)");
        } else {
            error(sender, found.get().name() + " pro tebe nepracuje");
        }
    }

    private static String kindLabel(dev.botalive.core.economy.EmploymentService.Kind kind) {
        return kind == dev.botalive.core.economy.EmploymentService.Kind.WORKER
                ? "dělníka" : "bodyguarda";
    }

    private static String declineMessage(String botName,
                                         dev.botalive.core.economy.EmploymentService.Decline decline) {
        return switch (decline) {
            case DISABLED -> "Najímání botů je vypnuté";
            case ALREADY_EMPLOYED -> botName + " už práci má (nebo čeká na platbu)";
            case EMPLOYER_LIMIT -> "Víc botů už najato mít nemůžeš";
            case ENEMY -> botName + " s tebou po tom všem nechce nic mít";
            case UNWILLING -> botName + " se do práce nehrne – zkus jiného bota";
            case AWAY -> botName + " je na výpravě, teď se nenajímá";
            case NONE -> "";
        };
    }

    /** {@code /botalive diplomacy} – napětí, války a příměří mezi vesnicemi. */
    private void diplomacy(CommandSender sender) {
        var relations = diplomacy.allRelations();
        if (relations.isEmpty()) {
            info(sender, "Mezi vesnicemi panuje klid – žádné napětí, žádné války.");
            return;
        }
        info(sender, "Diplomacie vesnic (" + relations.size() + "):");
        long now = System.currentTimeMillis();
        for (var relation : relations) {
            String pair = relation.aName() + " ↔ " + relation.bName();
            switch (relation.state()) {
                case WAR -> {
                    long hours = Math.max(0, (now - relation.stateSince()) / 3_600_000);
                    sender.sendMessage(Component.text(" ⚔ ", NamedTextColor.RED)
                            .append(Component.text(pair, NamedTextColor.AQUA))
                            .append(Component.text("  VÁLKA (" + hours + " h)",
                                    NamedTextColor.RED))
                            .append(Component.text("  padlí " + relation.deathsA()
                                            + ":" + relation.deathsB(),
                                    NamedTextColor.GRAY)));
                }
                case TRUCE -> {
                    long hours = Math.max(0, (relation.truceUntil() - now) / 3_600_000);
                    sender.sendMessage(Component.text(" ✋ ", NamedTextColor.GOLD)
                            .append(Component.text(pair, NamedTextColor.AQUA))
                            .append(Component.text("  příměří (ještě " + hours + " h)",
                                    NamedTextColor.GOLD))
                            .append(Component.text("  napětí "
                                            + String.format(Locale.ROOT, "%.1f",
                                                    relation.tension()),
                                    NamedTextColor.GRAY)));
                }
                case NEUTRAL -> sender.sendMessage(
                        Component.text(" • ", NamedTextColor.DARK_GRAY)
                                .append(Component.text(pair, NamedTextColor.AQUA))
                                .append(Component.text("  napětí "
                                                + String.format(Locale.ROOT, "%.1f",
                                                        relation.tension()),
                                        NamedTextColor.GRAY)));
            }
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
            String mood = impl.moodLine();
            if (mood != null) {
                sender.sendMessage(Component.text(" nálada: " + mood, NamedTextColor.LIGHT_PURPLE));
            }
            String vitals = impl.vitalsLine();
            if (vitals != null) {
                sender.sendMessage(Component.text(" energie: " + vitals, NamedTextColor.LIGHT_PURPLE));
            }
            String drives = impl.drivesLine();
            if (drives != null) {
                sender.sendMessage(Component.text(" pudy: " + drives, NamedTextColor.LIGHT_PURPLE));
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

    /** {@code /botalive role <jméno> [role|random]} – zobrazí/nastaví profesi (i cizí). */
    private void role(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        if (args.length < 3) {
            String roleId = bot.get().roleId();
            String display = roles.byId(roleId)
                    .map(dev.botalive.api.role.RoleDefinition::displayName)
                    .orElse(roleId);
            info(sender, "Bot '" + bot.get().name() + "' má roli: " + display + " (" + roleId + ")");
            sender.sendMessage(Component.text(" Dostupné: " + availableRoleIds(),
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
        // Vestavěná role (enum), pak cizí role z registru podle id.
        var parsed = dev.botalive.api.role.BotRole.parse(args[2]);
        if (parsed.isPresent()) {
            bot.get().role(parsed.get());
            success(sender, "Bot '" + bot.get().name() + "' je nyní " + parsed.get().displayName());
            return;
        }
        if (bot.get().assignRole(args[2])) {
            String display = roles.byId(args[2])
                    .map(dev.botalive.api.role.RoleDefinition::displayName).orElse(args[2]);
            success(sender, "Bot '" + bot.get().name() + "' je nyní " + display);
            return;
        }
        error(sender, "Neznámá role. Dostupné: " + availableRoleIds());
    }

    /** Seznam id všech dostupných rolí (univerzál + vestavěné + cizí). */
    private List<String> availableRoleIds() {
        List<String> ids = new ArrayList<>();
        ids.add("none");
        roles.all().stream()
                .map(dev.botalive.api.role.RoleDefinition::id)
                .sorted()
                .forEach(ids::add);
        return ids;
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

    /**
     * {@code /botalive path <jméno>} – diagnostika navigace: cíl, segment,
     * postup po waypointech, běžící výpočet a agregované metriky A*.
     */
    private void path(CommandSender sender, String[] args) {
        Optional<Bot> bot = requireBot(sender, args);
        if (bot.isEmpty()) {
            return;
        }
        info(sender, "Navigace bota '" + bot.get().name() + "':");
        if (bot.get() instanceof BotImpl impl) {
            var snap = impl.navigator().debugSnapshot();
            if (snap.destination() == null) {
                sender.sendMessage(Component.text(" nenaviguje", NamedTextColor.GRAY));
            } else {
                dev.botalive.core.util.BlockPos feet = impl.position().toBlockPos();
                sender.sendMessage(Component.text(
                        " cíl: %s (vzdálenost %.1f m)".formatted(format(snap.destination()),
                                Math.sqrt(feet.distanceSquared(snap.destination()))),
                        NamedTextColor.GRAY));
                if (snap.segmentGoal() != null) {
                    sender.sendMessage(Component.text(
                            " mezicíl segmentu: " + format(snap.segmentGoal()),
                            NamedTextColor.GRAY));
                }
                if (snap.corridorCount() > 0) {
                    sender.sendMessage(Component.text(
                            " koridor: bod %d/%d".formatted(
                                    Math.min(snap.corridorIndex() + 1, snap.corridorCount()),
                                    snap.corridorCount()),
                            NamedTextColor.GRAY));
                }
                String progress = snap.waypointCount() == 0 ? "bez cesty"
                        : "waypoint %d/%d (%s)".formatted(
                                Math.min(snap.waypointIndex() + 1, snap.waypointCount()),
                                snap.waypointCount(),
                                snap.pathComplete() ? "kompletní" : "částečná");
                String state = snap.assistNeeded()
                        ? "čeká na zásah do terénu (prokopání/most)"
                        : snap.computing() ? progress + ", výpočet běží" : progress;
                sender.sendMessage(Component.text(" stav: " + state, NamedTextColor.GRAY));
                if (!snap.upcoming().isEmpty()) {
                    sender.sendMessage(Component.text(" dál: " + snap.upcoming().stream()
                            .map(BotAliveCommand::format)
                            .collect(java.util.stream.Collectors.joining(" → ")),
                            NamedTextColor.DARK_GRAY));
                }
            }
        }
        var s = navigation.stats().snapshot();
        sender.sendMessage(Component.text(
                (" A* celkem: %d výpočtů (%d úplných, %d částečných, %d prázdných), "
                        + "%d timeout, %d zrušeno")
                        .formatted(s.requests(), s.complete(), s.partial(), s.empty(),
                                s.timedOut(), s.cancelled()),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                " průměr %.1f ms / %.0f uzlů na výpočet, max %.1f ms / %d uzlů"
                        .formatted(s.avgMillis(), s.avgNodes(), s.maxMillis(), s.maxNodes()),
                NamedTextColor.GRAY));
    }

    private static String format(dev.botalive.core.util.BlockPos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
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

    /**
     * {@code /botalive end portal <x> <y> <z> [svět]} – prozradí všem botům
     * polohu portálu do Endu (PORTAL vzpomínka {@code type=end}). Odtud se
     * znalost šíří dál drby; boti s výbavou a odvahou pak plánují výpravy.
     */
    private void endPortal(CommandSender sender, String[] args) {
        if (args.length < 5 || !args[1].equalsIgnoreCase("portal")) {
            error(sender, "Použití: /botalive end portal <x> <y> <z> [svět]");
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[2]);
            y = Integer.parseInt(args[3]);
            z = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            error(sender, "Souřadnice musí být celá čísla");
            return;
        }
        org.bukkit.World bukkitWorld;
        if (args.length >= 6) {
            bukkitWorld = Bukkit.getWorld(args[5]);
            if (bukkitWorld == null) {
                error(sender, "Svět '" + args[5] + "' neexistuje");
                return;
            }
        } else if (sender instanceof Player player) {
            bukkitWorld = player.getWorld();
        } else if (!Bukkit.getWorlds().isEmpty()) {
            bukkitWorld = Bukkit.getWorlds().getFirst();
        } else {
            error(sender, "Není z čeho odvodit svět – zadej ho parametrem");
            return;
        }
        // Paměť se páruje na worldName() pohledu bota (Bukkit název světa).
        String world = bukkitWorld.getName();
        var bots = botManager.all();
        if (bots.isEmpty()) {
            error(sender, "Žádní boti nejsou připojení");
            return;
        }
        for (Bot bot : bots) {
            bot.memory().remember(MemoryKind.PORTAL, world, x, y, z, null,
                    Map.of("type", "end", "via", "admin"), 0.95);
        }
        success(sender, "Portál do Endu na " + x + " " + y + " " + z + " (" + world
                + ") teď zná " + bots.size() + " botů");
    }

    private void help(CommandSender sender) {
        info(sender, "BotAlive – autonomní AI hráči");
        boolean admin = sender.hasPermission(PERM_ADMIN);
        boolean canTeleport = admin || sender.hasPermission(PERM_TELEPORT)
                || sender.hasPermission(PERM_SUMMON);
        for (String sub : SUBCOMMANDS) {
            boolean visible = switch (sub) {
                case "tp", "list" -> canTeleport;
                case "hire", "dismiss", "codex" -> true;
                default -> admin;
            };
            if (visible) {
                sender.sendMessage(Component.text(" /botalive " + sub, NamedTextColor.GRAY));
            }
        }
        // Cizí podpříkazy z registru (viditelné dle svého oprávnění).
        for (String name : subcommands.registeredNames()) {
            dev.botalive.api.command.BotSubcommand custom = subcommands.byName(name);
            if (custom == null) {
                continue;
            }
            String permission = custom.permission();
            if (permission == null || sender.hasPermission(permission)) {
                String description = custom.description();
                sender.sendMessage(Component.text(" /botalive " + name
                        + (description == null || description.isBlank() ? "" : " – " + description),
                        NamedTextColor.GRAY));
            }
        }
        if (!canTeleport) {
            sender.sendMessage(Component.text(" (žádná dostupná akce – chybí oprávnění)",
                    NamedTextColor.DARK_GRAY));
        }
    }

    /**
     * {@code /botalive codex [materiál]} – nahlédnutí do botní databáze materiálů
     * ({@link dev.botalive.core.inventory.Codex}). Bez argumentu vypíše histogram
     * kategorií celé vanilly, s materiálem jeho „kartu" (kategorie a fakta).
     */
    private void codex(CommandSender sender, String[] args) {
        if (args.length < 2) {
            var histogram = dev.botalive.core.inventory.Codex.histogram();
            int total = histogram.values().stream().mapToInt(Integer::intValue).sum();
            info(sender, "Codex – databáze materiálů (" + total + " celkem):");
            histogram.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(java.util.Map.Entry.<dev.botalive.core.inventory.Items.ItemCategory,
                            Integer>comparingByValue().reversed())
                    .forEach(e -> sender.sendMessage(Component.text(
                            " " + e.getKey().name().toLowerCase(Locale.ROOT) + ": " + e.getValue(),
                            NamedTextColor.GRAY)));
            return;
        }
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(args[1]);
        if (material == null) {
            info(sender, "Neznámý materiál: " + args[1]);
            return;
        }
        info(sender, dev.botalive.core.inventory.Codex.describe(material));
    }

    /** Názvy (moderních) materiálů malými písmeny – pro tab-complete /botalive codex. */
    private static List<String> materialNames() {
        return Stream.of(org.bukkit.Material.values())
                .filter(m -> !m.name().startsWith("LEGACY_"))
                .map(m -> m.name().toLowerCase(Locale.ROOT))
                .toList();
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
            List<String> visible = new ArrayList<>(SUBCOMMANDS.stream()
                    .filter(sub -> switch (sub) {
                        case "tp", "list" -> canTeleport;
                        case "hire", "dismiss", "codex" -> true;
                        default -> admin;
                    })
                    .toList());
            for (String name : subcommands.registeredNames()) {
                dev.botalive.api.command.BotSubcommand custom = subcommands.byName(name);
                if (custom != null
                        && (custom.permission() == null || sender.hasPermission(custom.permission()))) {
                    visible.add(name);
                }
            }
            return filter(visible, args[0]);
        }
        // Cizí podpříkaz si tab-complete řídí sám (izolovaně).
        dev.botalive.api.command.BotSubcommand customSub =
                subcommands.byName(args[0].toLowerCase(Locale.ROOT));
        if (customSub != null) {
            if (customSub.permission() != null && !sender.hasPermission(customSub.permission())) {
                return List.of();
            }
            try {
                List<String> suggestions = customSub.tabComplete(sender,
                        java.util.Arrays.copyOfRange(args, 1, args.length));
                return suggestions != null ? suggestions : List.of();
            } catch (Exception e) {
                return List.of();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("codex")) {
            return filter(materialNames(), args[1]);
        }
        boolean playerFacing = args[0].equalsIgnoreCase("hire")
                || args[0].equalsIgnoreCase("dismiss");
        if (!admin && !canTeleport && !playerFacing) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>(botManager.all().stream().map(Bot::name).toList());
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "remove", "pause", "resume" -> names.add("all");
                case "create" -> {
                    return List.of();
                }
                case "end" -> {
                    return filter(List.of("portal"), args[1]);
                }
                default -> {
                }
            }
            return filter(names, args[1]);
        }
        if (args.length == 3) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "goal" -> filter(List.of("set", "clear"), args[2]);
                case "hire" -> filter(List.of("worker", "guard", "confirm"), args[2]);
                case "memory" -> filter(Stream.of(MemoryKind.values())
                        .map(k -> k.name().toLowerCase(Locale.ROOT)).toList(), args[2]);
                case "tp" -> filter(List.of("here"), args[2]);
                case "remove" -> filter(List.of("purge"), args[2]);
                case "role" -> {
                    List<String> options = new ArrayList<>(availableRoleIds());
                    options.add("random");
                    yield filter(options, args[2]);
                }
                default -> List.of();
            };
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("goal") && args[2].equalsIgnoreCase("set")) {
            return filter(goalRegistry.registeredIds(), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("hire")
                && !args[2].equalsIgnoreCase("confirm")) {
            return filter(List.of("1", "3", "7"), args[3]);
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("end")) {
            return filter(Bukkit.getWorlds().stream()
                    .map(org.bukkit.World::getName).toList(), args[5]);
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
