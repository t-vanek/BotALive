package dev.botalive.core.economy;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.event.BotDismissedEvent;
import dev.botalive.api.event.BotHiredEvent;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.persistence.BotRepository;
import dev.botalive.core.pvp.PvpCoordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Najímání botů hráči ({@code /botalive hire}).
 *
 * <p>Hráč si najme bota jako <b>dělníka</b> (WORKER – bot se soustředí na
 * produktivní práci a výtěžek pravidelně nosí zaměstnavateli) nebo
 * <b>bodyguarda</b> (GUARD – chodí se zaměstnavatelem a brání ho). Mzdu si
 * bot řekne podle povahy ({@link EmploymentPrices}); platí se předem přes
 * Vault {@code /pay} a příchozí platba se ověřuje na botově účtu stejným
 * trikem jako u trhu ({@code SellGoal} – baseline zůstatku). Smlouva končí
 * vypršením, výpovědí hráče, nebo výpovědí bota – zaměstnavatel, který
 * svého bota napadne, o něj přijde bez náhrady (čerstvá ENEMY vzpomínka).</p>
 *
 * <p>Vliv na chování: dělníkova smlouva násobí utility produktivních cílů
 * v mozku ({@link #weight}), bodyguard má vlastní cíl {@code bodyguard}.
 * Nezaplacené nabídky žijí jen v paměti (restart je zahodí), aktivní
 * smlouvy se persistují (v7, {@code ba_employment}).</p>
 */
public final class EmploymentService {

    /** Jak dlouho čeká nabídka na zaplacení. */
    private static final long OFFER_TTL_MS = 3 * 60_000;

    /** Jak dlouho platí poplach bodyguarda po napadení zaměstnavatele. */
    private static final long GUARD_ALERT_TTL_MS = 30_000;

    /** Jak čerstvá ENEMY vzpomínka na zaměstnavatele znamená výpověď. */
    private static final long MISTREATMENT_WINDOW_MS = 2 * 60_000;

    /** Práh ENEMY důležitosti pro výpověď (vážné napadení, ne škrábnutí). */
    private static final double MISTREATMENT_SEVERITY = 0.4;

    /** Druh práce. */
    public enum Kind { WORKER, GUARD }

    /** Výsledek poptávky – proč bot odmítá, případně za kolik kývne. */
    public enum Decline {
        NONE, DISABLED, ALREADY_EMPLOYED, EMPLOYER_LIMIT, ENEMY, UNWILLING, AWAY
    }

    /**
     * Nabídka mzdy.
     *
     * @param decline důvod odmítnutí ({@code NONE} = bot kývne)
     * @param price   mzda za celou smlouvu (jen při {@code NONE})
     * @param days    délka smlouvy po oříznutí stropem
     */
    public record Quote(Decline decline, double price, int days) {
        static Quote declined(Decline reason) {
            return new Quote(reason, 0, 0);
        }
    }

    /**
     * Aktivní smlouva.
     *
     * @param botId          UUID bota
     * @param employer       UUID zaměstnavatele (hráče)
     * @param employerName   jméno zaměstnavatele
     * @param kind           druh práce
     * @param wage           zaplacená mzda
     * @param hiredAt        začátek smlouvy (epoch ms)
     * @param paidUntil      konec zaplaceného období (epoch ms)
     * @param lastDeliveryAt poslední donáška výtěžku (epoch ms; 0 = žádná)
     */
    public record Contract(UUID botId, UUID employer, String employerName,
                           Kind kind, double wage, long hiredAt, long paidUntil,
                           long lastDeliveryAt) {
    }

    /**
     * Poplach bodyguarda – zaměstnavatel byl napaden.
     *
     * @param attacker         UUID útočníka ({@code null} u mobů bez UUID smyslu)
     * @param attackerEntityId síťové id útočníka
     * @param expiresAtMs      konec platnosti poplachu
     */
    public record GuardAlert(UUID attacker, int attackerEntityId, long expiresAtMs) {
    }

    /** Nezaplacená nabídka (čeká na /pay). */
    private record Offer(UUID botId, UUID employer, String employerName, Kind kind,
                         int days, double price, double baselineBalance,
                         long expiresAtMs) {
    }

    private final BotAliveConfig.Employment config;
    private final BotRepository repository;
    private final LongSupplier clock;

    private volatile BotManagerImpl botManager;

    private final Map<UUID, Contract> contracts = new ConcurrentHashMap<>();
    private final Map<UUID, Offer> offers = new ConcurrentHashMap<>();
    private final Map<UUID, GuardAlert> guardAlerts = new ConcurrentHashMap<>();

    /**
     * @param config     konfigurace najímání ({@code economy.employment})
     * @param repository persistence; {@code null} = bez databáze (testy)
     */
    public EmploymentService(BotAliveConfig.Employment config, BotRepository repository) {
        this(config, repository, System::currentTimeMillis);
    }

    /**
     * @param clock zdroj času (pro testy)
     */
    EmploymentService(BotAliveConfig.Employment config, BotRepository repository,
                      LongSupplier clock) {
        this.config = config;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Připojí manager botů (bootstrap).
     *
     * @param manager manager botů
     */
    public void attach(BotManagerImpl manager) {
        this.botManager = manager;
    }

    /**
     * Synchronně načte smlouvy z databáze (bootstrap). Propadlé smlouvy se
     * rovnou uklidí.
     */
    public void load() {
        if (repository == null) {
            return;
        }
        long now = clock.getAsLong();
        for (BotRepository.EmploymentRow row : repository.loadEmployment().join()) {
            if (row.paidUntil() <= now) {
                repository.deleteEmployment(row.botId());
                continue;
            }
            Kind kind;
            try {
                kind = Kind.valueOf(row.kind());
            } catch (IllegalArgumentException e) {
                repository.deleteEmployment(row.botId());
                continue;
            }
            contracts.put(row.botId(), new Contract(row.botId(), row.employer(),
                    row.employerName(), kind, row.wage(), row.hiredAt(),
                    row.paidUntil(), row.lastDelivery()));
        }
    }

    // ================================================================ dotazy

    /**
     * @param botId UUID bota
     * @return aktivní smlouva bota
     */
    public Optional<Contract> contractOf(UUID botId) {
        return Optional.ofNullable(contracts.get(botId));
    }

    /**
     * @param employer UUID hráče
     * @return smlouvy, kde je hráč zaměstnavatelem
     */
    public List<Contract> contractsFor(UUID employer) {
        List<Contract> result = new ArrayList<>();
        for (Contract contract : contracts.values()) {
            if (contract.employer().equals(employer)) {
                result.add(contract);
            }
        }
        return result;
    }

    /**
     * @param botId UUID bota
     * @return platný poplach bodyguarda
     */
    public Optional<GuardAlert> guardAlert(UUID botId) {
        GuardAlert alert = guardAlerts.get(botId);
        if (alert == null || clock.getAsLong() > alert.expiresAtMs()) {
            guardAlerts.remove(botId);
            return Optional.empty();
        }
        return Optional.of(alert);
    }

    /**
     * Zruší poplach bodyguarda (vyřízen/nevyřiditelný).
     *
     * @param botId UUID bota
     */
    public void clearGuardAlert(UUID botId) {
        guardAlerts.remove(botId);
    }

    /**
     * Násobič utility cíle podle zaměstnání – dělník se soustředí na
     * produktivní práci a míň se fláká; bodyguard se drží zaměstnavatele,
     * a tak ho netáhne svět. Neutrální 1.0 pro nezaměstnané.
     *
     * @param botId  UUID bota
     * @param goalId id cíle
     * @return násobič utility (1.0 = bez vlivu)
     */
    public double weight(UUID botId, String goalId) {
        Contract contract = contracts.get(botId);
        if (contract == null) {
            return 1.0;
        }
        return switch (contract.kind()) {
            case WORKER -> switch (goalId) {
                case "mine", "farm" -> 1.6;
                case "hunt", "fish", "deliver-work" -> 1.5;
                case "smelt" -> 1.4;
                case "collect" -> 1.3;
                case "craft" -> 1.2;
                case "socialize", "wander", "explore" -> 0.6;
                case "idle", "boat", "minecart" -> 0.7;
                default -> 1.0;
            };
            case GUARD -> switch (goalId) {
                case "socialize", "wander", "explore" -> 0.6;
                case "mine", "farm", "hunt", "fish" -> 0.5;
                default -> 1.0;
            };
        };
    }

    // ============================================================== najímání

    /**
     * Poptávka mzdy – bez vedlejších účinků (jen výpočet a ochota).
     *
     * @param bot      poptávaný bot
     * @param employer UUID hráče
     * @param kind     druh práce
     * @param days     požadovaná délka (ořízne se stropem)
     * @return nabídka, nebo důvod odmítnutí
     */
    public Quote quote(Bot bot, UUID employer, Kind kind, int days) {
        if (!config.enabled()) {
            return Quote.declined(Decline.DISABLED);
        }
        if (contracts.containsKey(bot.id()) || offers.containsKey(bot.id())) {
            return Quote.declined(Decline.ALREADY_EMPLOYED);
        }
        if (contractsFor(employer).size() >= config.maxBotsPerPlayer()) {
            return Quote.declined(Decline.EMPLOYER_LIMIT);
        }
        BotContext ctx = (BotContext) bot;
        if (ctx.dimension() != dev.botalive.core.world.WorldDimension.OVERWORLD) {
            return Quote.declined(Decline.AWAY); // uprostřed výpravy se nenajímá
        }
        if (isEnemy(bot, employer)) {
            return Quote.declined(Decline.ENEMY);
        }
        double greed = bot.personality().trait(Trait.GREED);
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        boolean friend = isFriend(bot, employer);
        if (!EmploymentPrices.willing(greed, helpfulness, friend)) {
            return Quote.declined(Decline.UNWILLING);
        }
        int clampedDays = Math.max(1, Math.min(config.maxDays(), days));
        double perDay = kind == Kind.WORKER
                ? config.workerWagePerDay() : config.guardWagePerDay();
        double price = EmploymentPrices.wage(perDay, clampedDays, greed,
                bot.personality().trait(Trait.LAZINESS), helpfulness, friend);
        return new Quote(Decline.NONE, price, clampedDays);
    }

    /**
     * Potvrzené najmutí. Při vyžadované platbě vytvoří nabídku čekající na
     * {@code /pay} (bot si o peníze řekne); bez platby smlouvu rovnou
     * aktivuje.
     *
     * @param bot          najímaný bot
     * @param employer     UUID hráče
     * @param employerName jméno hráče
     * @param kind         druh práce
     * @param days         délka smlouvy
     * @return nabídka (cena, dny), nebo důvod odmítnutí
     */
    public Quote beginHire(Bot bot, UUID employer, String employerName,
                           Kind kind, int days) {
        Quote quoted = quote(bot, employer, kind, days);
        if (quoted.decline() != Decline.NONE) {
            return quoted;
        }
        long now = clock.getAsLong();
        if (config.requirePayment()) {
            offers.put(bot.id(), new Offer(bot.id(), employer, employerName, kind,
                    quoted.days(), quoted.price(), bot.wallet().balance(),
                    now + OFFER_TTL_MS));
            ((BotContext) bot).chat().sayFrom(PhraseCategory.HIRE_PAY_REQUEST,
                    priceLabel(quoted.price()));
        } else {
            activate(bot, employer, employerName, kind, quoted.days(), 0, now);
        }
        return quoted;
    }

    /**
     * Výpověď smlouvy zaměstnavatelem (bez náhrady mzdy).
     *
     * @param botId    UUID bota
     * @param employer UUID hráče (kontrola vlastnictví smlouvy)
     * @return {@code true}, pokud smlouva existovala a patřila hráči
     */
    public boolean dismiss(UUID botId, UUID employer) {
        Contract contract = contracts.get(botId);
        if (contract == null || !contract.employer().equals(employer)) {
            return false;
        }
        endContract(contract, BotDismissedEvent.Reason.DISMISSED, null);
        return true;
    }

    // ================================================================== tick

    /**
     * Pravidelná kontrola bota: příchozí platba nabídky, vypršení smlouvy,
     * výpověď po napadení zaměstnavatelem. Volá se z ticku bota (řídce).
     *
     * @param bot bot, jehož tick právě běží
     */
    public void tick(Bot bot) {
        if (!config.enabled()) {
            return;
        }
        long now = clock.getAsLong();
        Offer offer = offers.get(bot.id());
        if (offer != null) {
            tickOffer(bot, offer, now);
        }
        Contract contract = contracts.get(bot.id());
        if (contract == null) {
            return;
        }
        if (now >= contract.paidUntil()) {
            endContract(contract, BotDismissedEvent.Reason.EXPIRED, bot);
            return;
        }
        if (freshEmployerGrudge(bot, contract.employer(), now)) {
            endContract(contract, BotDismissedEvent.Reason.QUIT, bot);
        }
    }

    private void tickOffer(Bot bot, Offer offer, long now) {
        if (now > offer.expiresAtMs()) {
            offers.remove(bot.id());
            ((BotContext) bot).chat().sayFrom(PhraseCategory.HIRE_DECLINE, offer.employerName());
            return;
        }
        // Vault peněženka se musí občas synchronizovat, aby /pay dorazilo.
        if (bot.wallet() instanceof VaultBotWallet vault) {
            vault.refresh();
        }
        if (bot.wallet().balance() >= offer.baselineBalance() + offer.price() - 1e-6) {
            offers.remove(bot.id());
            activate(bot, offer.employer(), offer.employerName(), offer.kind(),
                    offer.days(), offer.price(), now);
        }
    }

    private void activate(Bot bot, UUID employer, String employerName, Kind kind,
                          int days, double wage, long now) {
        Contract contract = new Contract(bot.id(), employer, employerName, kind,
                wage, now, now + days * 86_400_000L, 0);
        contracts.put(bot.id(), contract);
        persist(contract);
        // Zaměstnavatel je od teď (trochu) kamarád – bot ho zdraví a chrání.
        BotContext ctx = (BotContext) bot;
        if (ctx.worldView() != null) {
            var pos = ctx.position();
            bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), employer,
                    Map.of("via", "employment"), PvpCoordinator.ALLY_THRESHOLD);
        }
        ctx.chat().sayFrom(PhraseCategory.HIRE_ACCEPT, employerName);
        new BotHiredEvent(bot, employer, employerName, kind.name(), wage, days)
                .callEvent();
    }

    private void endContract(Contract contract, BotDismissedEvent.Reason reason,
                             Bot bot) {
        contracts.remove(contract.botId());
        guardAlerts.remove(contract.botId());
        if (repository != null) {
            repository.deleteEmployment(contract.botId());
        }
        Bot resolved = bot != null ? bot : resolve(contract.botId());
        if (resolved != null) {
            PhraseCategory phrase = switch (reason) {
                case EXPIRED -> PhraseCategory.HIRE_EXPIRED;
                case QUIT -> PhraseCategory.HIRE_QUIT;
                case DISMISSED -> null; // výpověď oznamuje příkaz, ne bot
            };
            if (phrase != null) {
                ((BotContext) resolved).chat().sayFrom(phrase, contract.employerName());
            }
            new BotDismissedEvent(resolved, contract.employer(),
                    contract.employerName(), contract.kind().name(), reason)
                    .callEvent();
        }
    }

    /**
     * Zaznamená donášku výtěžku (volá {@code WorkDeliveryGoal}).
     *
     * @param botId UUID bota
     */
    public void markDelivered(UUID botId) {
        Contract contract = contracts.get(botId);
        if (contract == null) {
            return;
        }
        Contract updated = new Contract(contract.botId(), contract.employer(),
                contract.employerName(), contract.kind(), contract.wage(),
                contract.hiredAt(), contract.paidUntil(), clock.getAsLong());
        contracts.put(botId, updated);
        persist(updated);
    }

    // ========================================================= obrana hráče

    /**
     * Zaměstnavatel byl napaden – poplach všem jeho bodyguardům (volá se
     * z damage eventu na region vlákně).
     *
     * @param employer         UUID napadeného hráče
     * @param attacker         UUID útočníka (může být {@code null})
     * @param attackerEntityId síťové id útočníka
     */
    public void onEmployerAttacked(UUID employer, UUID attacker, int attackerEntityId) {
        if (!config.enabled() || contracts.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        for (Contract contract : contracts.values()) {
            if (contract.kind() == Kind.GUARD && contract.employer().equals(employer)) {
                guardAlerts.put(contract.botId(),
                        new GuardAlert(attacker, attackerEntityId,
                                now + GUARD_ALERT_TTL_MS));
            }
        }
    }

    // ================================================================ nitro

    private boolean isFriend(Bot bot, UUID other) {
        return bot.memory().recallAbout(other).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND
                        && r.importance() >= PvpCoordinator.ALLY_THRESHOLD);
    }

    private boolean isEnemy(Bot bot, UUID other) {
        return bot.memory().recallAbout(other).stream()
                .anyMatch(r -> r.kind() == MemoryKind.ENEMY
                        && r.importance() >= MISTREATMENT_SEVERITY);
    }

    /** Čerstvě zapsaná zášť vůči zaměstnavateli = napadl svého bota. */
    private boolean freshEmployerGrudge(Bot bot, UUID employer, long now) {
        return bot.memory().recallAbout(employer).stream()
                .anyMatch(r -> r.kind() == MemoryKind.ENEMY
                        && r.importance() >= MISTREATMENT_SEVERITY
                        && now - r.updatedAt() <= MISTREATMENT_WINDOW_MS);
    }

    private Bot resolve(UUID botId) {
        BotManagerImpl manager = botManager;
        return manager == null ? null : manager.byId(botId).orElse(null);
    }

    private void persist(Contract contract) {
        if (repository == null) {
            return;
        }
        repository.upsertEmployment(new BotRepository.EmploymentRow(
                contract.botId(), contract.employer(), contract.employerName(),
                contract.kind().name(), contract.wage(), contract.hiredAt(),
                contract.paidUntil(), contract.lastDeliveryAt()));
    }

    /**
     * Cena bez zbytečného „.0" (mzdy jsou v půlkách).
     *
     * @param price cena
     * @return textová podoba pro hlášky a příkazy
     */
    public static String priceLabel(double price) {
        return price == Math.floor(price)
                ? String.valueOf((long) price) : String.valueOf(price);
    }
}
