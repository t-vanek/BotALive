package dev.botalive.core.pvp;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.bot.BotSnapshot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.config.BotAliveConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Koordinátor PvP a aliancí.
 *
 * <p>Aliance jsou <b>emergentní</b>: vznikají z přátelství v paměti botů
 * (kategorie {@link MemoryKind#FRIEND}, budovaná socializací a společným
 * bojem) – žádné pevné týmy. Koordinátor propojuje tři mechanismy:</p>
 * <ul>
 *   <li><b>Hrozby</b> – kdo bota právě napadl (obrana / útěk podle odvahy),</li>
 *   <li><b>Volání o pomoc</b> – napadený bot svolá spojence v okolí; ti
 *       dostanou žádost o asistenci proti útočníkovi,</li>
 *   <li><b>Férovost</b> – na jeden cíl smí současně útočit jen omezený počet
 *       botů (konfigurovatelné), aby se server nezvrhl v hon na jednoho hráče.</li>
 * </ul>
 *
 * <p>Konfigurační pojistky: hlavní vypínač, zvlášť útoky na skutečné hráče
 * (obrana po napadení je povolena vždy, když je PvP zapnuté) a na jiné boty.
 * Thread-safe – volá se z region vláken (damage eventy) i tick vláken botů.</p>
 */
public final class PvpCoordinator {

    /** Jak dlouho platí „právě mě někdo napadl". */
    private static final long THREAT_TTL_MS = 30_000;

    /** Jak dlouho platí žádost o asistenci. */
    private static final long ASSIST_TTL_MS = 45_000;

    /** Práh důležitosti FRIEND vzpomínky pro spojenectví. */
    /** Práh FRIEND důležitosti, od kterého se vztah počítá jako aliance
     *  (sdílí PvP asistence i zakládání vesnic – jedna definice kamarádství). */
    public static final double ALLY_THRESHOLD = 0.45;

    /**
     * Aktivní hrozba – bot byl napaden.
     *
     * @param attacker         UUID útočníka
     * @param attackerEntityId síťové id útočníka
     * @param atMs             čas útoku
     */
    public record Threat(UUID attacker, int attackerEntityId, long atMs) {
    }

    /**
     * Žádost o asistenci – spojenec volá o pomoc.
     *
     * @param target         UUID útočníka, proti kterému se pomáhá
     * @param targetEntityId síťové id útočníka
     * @param friend         UUID napadeného spojence
     * @param expiresAtMs    konec platnosti žádosti
     */
    public record Assist(UUID target, int targetEntityId, UUID friend, long expiresAtMs) {
    }

    private final BotAliveConfig.Pvp config;
    private final LongSupplier clock;

    private volatile BotManagerImpl botManager;

    private final Map<UUID, Threat> threats = new ConcurrentHashMap<>();
    private final Map<UUID, Assist> assists = new ConcurrentHashMap<>();

    /** Cíl → boti, kteří na něj právě útočí (férovostní strop). */
    private final Map<UUID, Set<UUID>> attackers = new ConcurrentHashMap<>();

    /**
     * @param config PvP konfigurace
     */
    public PvpCoordinator(BotAliveConfig.Pvp config) {
        this(config, System::currentTimeMillis);
    }

    /**
     * @param config PvP konfigurace
     * @param clock  zdroj času (pro testy)
     */
    public PvpCoordinator(BotAliveConfig.Pvp config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Připojí manager botů (volá se jednou při bootstrapu – manager vzniká
     * později než koordinátor).
     *
     * @param manager manager botů
     */
    public void attach(BotManagerImpl manager) {
        this.botManager = manager;
    }

    // ================================================================ vstupy

    /**
     * Bot byl napaden (volá se z damage eventu na region vlákně).
     * Zaznamená hrozbu a svolá spojence v okolí.
     *
     * @param victim           napadený bot
     * @param attacker         UUID útočníka
     * @param attackerEntityId síťové id útočníka
     */
    public void onBotAttacked(Bot victim, UUID attacker, int attackerEntityId) {
        if (!config.enabled() || attacker == null || attacker.equals(victim.id())) {
            return;
        }
        long now = recordThreat(victim.id(), attacker, attackerEntityId);

        if (!config.helpAllies()) {
            return;
        }
        BotManagerImpl manager = botManager;
        if (manager == null) {
            return;
        }
        BotSnapshot victimSnapshot = victim.snapshot();
        for (Bot helper : manager.all()) {
            if (helper.id().equals(victim.id()) || helper.paused()) {
                continue;
            }
            BotSnapshot helperSnapshot = helper.snapshot();
            if (!helperSnapshot.online()
                    || !Objects.equals(helperSnapshot.worldName(), victimSnapshot.worldName())
                    || distanceSquared(helperSnapshot, victimSnapshot)
                            > (double) config.helpRadius() * config.helpRadius()) {
                continue;
            }
            // Pomáhá jen spojenec oběti, který sám není spojencem útočníka.
            if (!isAllyOf(helper, victim.id()) || isAllyOf(helper, attacker)) {
                continue;
            }
            if (attackerCount(attacker) >= config.maxAttackersPerTarget()) {
                break; // stačí – na cíl už jde dost botů
            }
            assists.put(helper.id(), new Assist(attacker, attackerEntityId,
                    victim.id(), now + ASSIST_TTL_MS));
        }
    }

    /**
     * Zaznamená hrozbu (oddělené kvůli testovatelnosti).
     *
     * @param victimId         UUID napadeného bota
     * @param attacker         UUID útočníka
     * @param attackerEntityId síťové id útočníka
     * @return aktuální čas záznamu
     */
    long recordThreat(UUID victimId, UUID attacker, int attackerEntityId) {
        long now = clock.getAsLong();
        threats.put(victimId, new Threat(attacker, attackerEntityId, now));
        return now;
    }

    // ================================================================ dotazy

    /**
     * @param botId UUID bota
     * @return čerstvá hrozba (napadení v posledních ~30 s)
     */
    public Optional<Threat> threat(UUID botId) {
        Threat threat = threats.get(botId);
        if (threat == null || clock.getAsLong() - threat.atMs() > THREAT_TTL_MS) {
            threats.remove(botId);
            return Optional.empty();
        }
        return Optional.of(threat);
    }

    /**
     * @param botId UUID bota
     * @return platná žádost o asistenci spojenci
     */
    public Optional<Assist> assist(UUID botId) {
        Assist assist = assists.get(botId);
        if (assist == null || clock.getAsLong() > assist.expiresAtMs()) {
            assists.remove(botId);
            return Optional.empty();
        }
        return Optional.of(assist);
    }

    /**
     * Zruší žádost o asistenci (splněna/nesplnitelná).
     *
     * @param botId UUID bota
     */
    public void clearAssist(UUID botId) {
        assists.remove(botId);
    }

    /**
     * Subjektivní spojenectví: bot považuje daného za přítele
     * (FRIEND vzpomínka s dostatečnou důležitostí).
     *
     * @param bot   bot
     * @param other UUID posuzovaného
     * @return {@code true} pokud jde o spojence
     */
    public boolean isAllyOf(Bot bot, UUID other) {
        if (other == null || other.equals(bot.id())) {
            return true;
        }
        return bot.memory().recallAbout(other).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND
                        && r.importance() >= ALLY_THRESHOLD);
    }

    /**
     * @param uuid UUID entity
     * @return {@code true} pokud jde o jiného bota tohoto pluginu
     */
    public boolean isBot(UUID uuid) {
        BotManagerImpl manager = botManager;
        return manager != null && uuid != null && manager.byId(uuid).isPresent();
    }

    /**
     * Smí bot na daný cíl zaútočit? Kontroluje vypínače, typ cíle
     * a spojenectví.
     *
     * @param bot        útočící bot
     * @param targetUuid UUID cíle
     * @param defensive  jde o obranu/asistenci po napadení (obrana proti
     *                   hráčům je povolena i bez {@code attack-players})
     * @return {@code true} pokud je útok povolen
     */
    public boolean mayEngage(Bot bot, UUID targetUuid, boolean defensive) {
        if (!config.enabled() || targetUuid == null || isAllyOf(bot, targetUuid)) {
            return false;
        }
        if (isBot(targetUuid)) {
            return config.attackBots();
        }
        return defensive || config.attackPlayers();
    }

    // ============================================================== férovost

    /**
     * Přihlásí bota jako útočníka na cíl (respektuje strop; sebeobrana
     * napadeného má vždy přednost).
     *
     * @param target UUID cíle
     * @param botId  UUID útočícího bota
     * @param forced {@code true} = sebeobrana, strop se ignoruje
     * @return {@code true} pokud se bot smí zapojit
     */
    public boolean registerAttacker(UUID target, UUID botId, boolean forced) {
        Set<UUID> set = attackers.computeIfAbsent(target,
                k -> ConcurrentHashMap.newKeySet());
        if (set.contains(botId)) {
            return true;
        }
        if (!forced && set.size() >= config.maxAttackersPerTarget()) {
            return false;
        }
        set.add(botId);
        return true;
    }

    /**
     * Odhlásí bota z útoku na cíl.
     *
     * @param target UUID cíle
     * @param botId  UUID bota
     */
    public void unregisterAttacker(UUID target, UUID botId) {
        Set<UUID> set = attackers.get(target);
        if (set != null) {
            set.remove(botId);
            if (set.isEmpty()) {
                attackers.remove(target);
            }
        }
    }

    /**
     * @param target UUID cíle
     * @return kolik botů na cíl právě útočí
     */
    public int attackerCount(UUID target) {
        Set<UUID> set = attackers.get(target);
        return set == null ? 0 : set.size();
    }

    private static double distanceSquared(BotSnapshot a, BotSnapshot b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
