package dev.botalive.core.chat;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.util.BotRandom;

import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Konverzační engine jednoho bota.
 *
 * <p>Bot nikdy neodpovídá okamžitě: nejdřív „přemýšlí" (reakční latence podle
 * povahy), pak „píše" (doba podle délky zprávy a WPM stylu) a teprve potom
 * zprávu odešle – včetně překlepů a případné follow-up opravy. Odpovídá na
 * zmínky svého jména, na pozdravy v okolí a občas spontánně komentuje dění.
 * Cooldowny brání spamu.</p>
 *
 * <p>Příchozí zprávy přicházejí ze síťového vlákna do fronty; zpracování běží
 * na tick vlákně bota.</p>
 */
public final class ChatEngine {

    /** Minimální odstup dvou odeslaných zpráv (ms). */
    private static final long GLOBAL_COOLDOWN_MS = 8_000;

    /** Cooldown odpovědí jednomu hráči (ms). */
    private static final long PER_SENDER_COOLDOWN_MS = 20_000;

    private final String botName;
    private final Personality personality;
    private final BotRandom rng;
    private final ChatStyle style;
    private final TypoEngine typos;
    private final BotAliveConfig.Chat config;
    private final Consumer<String> sender;

    /** Příchozí zprávy ze síťového vlákna. */
    private final Queue<Inbound> inbox = new ConcurrentLinkedQueue<>();

    /** Naplánované odchozí zprávy (odpočet v ticích); plní se i z jiných vláken. */
    private final Deque<Outbound> outbox = new ConcurrentLinkedDeque<>();

    private final Map<UUID, Long> senderCooldowns = new ConcurrentHashMap<>();
    private long lastSentAtMs;

    private record Inbound(UUID sender, String senderName, String content) {
    }

    private static final class Outbound {
        final String text;
        int ticksRemaining;

        Outbound(String text, int ticksRemaining) {
            this.text = text;
            this.ticksRemaining = ticksRemaining;
        }
    }

    /**
     * @param botName     jméno bota
     * @param personality osobnost
     * @param rng         per-bot náhoda
     * @param config      konfigurace chatu
     * @param sender      callback pro fyzické odeslání zprávy (přes BotChatEvent)
     */
    public ChatEngine(String botName, Personality personality, BotRandom rng,
                      BotAliveConfig.Chat config, Consumer<String> sender) {
        this.botName = botName;
        this.personality = personality;
        this.rng = rng;
        this.config = config;
        this.sender = sender;
        this.style = ChatStyle.derive(personality, rng, config.wordsPerMinute());
        this.typos = new TypoEngine(rng);
    }

    /** @return styl psaní bota (pro /botalive personality) */
    public ChatStyle style() {
        return style;
    }

    /**
     * Příjem zprávy ze síťového vlákna.
     *
     * @param sender     UUID odesílatele
     * @param senderName jméno odesílatele
     * @param content    text zprávy
     */
    public void onMessage(UUID sender, String senderName, String content) {
        if (!config.enabled() || senderName.equalsIgnoreCase(botName)) {
            return;
        }
        inbox.add(new Inbound(sender, senderName, content));
    }

    /**
     * Přímé promluvení (API {@code Bot.say} / spontánní hlášky) – prochází
     * humanizací, ale bez reakční latence.
     *
     * @param message text
     */
    public void say(String message) {
        if (!config.enabled()) {
            return;
        }
        enqueue(message, 5 + rng.rangeInt(0, 15));
    }

    /**
     * Spontánní hláška z banky frází.
     *
     * @param bank kategorie
     * @param name jméno protistrany (může být null)
     */
    public void sayFrom(java.util.List<String> bank, String name) {
        say(PhraseBank.pick(bank, rng, name));
    }

    /**
     * Jeden tick chatu – zpracuje příchozí zprávy a odpočítává odchozí.
     */
    public void tick() {
        // Příchozí.
        Inbound inbound;
        while ((inbound = inbox.poll()) != null) {
            considerReply(inbound);
        }
        // Odchozí odpočet.
        Outbound head = outbox.peek();
        if (head != null && --head.ticksRemaining <= 0) {
            outbox.poll();
            lastSentAtMs = System.currentTimeMillis();
            sender.accept(head.text);
        }
    }

    /** Rozhodne, zda a jak odpovědět na příchozí zprávu. */
    private void considerReply(Inbound inbound) {
        long now = System.currentTimeMillis();
        if (outbox.size() >= config.maxQueuedReplies()) {
            return;
        }
        boolean mentioned = inbound.content.toLowerCase(Locale.ROOT)
                .contains(botName.toLowerCase(Locale.ROOT));
        double sociability = personality.trait(Trait.SOCIABILITY);

        // Nezmíněný bot reaguje jen výjimečně a jen když je společenský.
        double chance = mentioned
                ? config.replyChance() * (0.6 + sociability * 0.6)
                : config.replyChance() * sociability * 0.08;
        if (!mentioned && now - lastSentAtMs < GLOBAL_COOLDOWN_MS) {
            return;
        }
        Long senderCooldown = senderCooldowns.get(inbound.sender);
        if (senderCooldown != null && now - senderCooldown < PER_SENDER_COOLDOWN_MS) {
            return;
        }
        if (!rng.chance(Math.min(0.95, chance))) {
            return;
        }
        senderCooldowns.put(inbound.sender, now);

        String reply = composeReply(inbound);
        // Reakční latence: přemýšlení (0.5–3 s) + psaní podle délky a WPM.
        double thinkMs = Math.abs(rng.gaussian(1200, 700))
                * (1.0 + personality.trait(Trait.LAZINESS) * 0.5)
                * (1.2 - personality.trait(Trait.INTELLIGENCE) * 0.4);
        double typingMs = reply.length() / (style.wpm() * 5.0 / 60_000.0);
        int delayTicks = (int) Math.max(10, (thinkMs + typingMs) / 50);
        enqueue(reply, delayTicks);
    }

    /** Sestaví odpověď podle jednoduché klasifikace zprávy. */
    private String composeReply(Inbound inbound) {
        String content = inbound.content.toLowerCase(Locale.ROOT);
        String name = inbound.senderName;

        java.util.List<String> bank;
        if (content.matches(".*\\b(ahoj|čau|cau|nazdar|zdar|hi|hello|hey)\\b.*")) {
            bank = PhraseBank.GREETINGS;
        } else if (content.matches(".*\\b(díky|dík|diky|thx|thanks|dekuju|děkuju)\\b.*")) {
            bank = PhraseBank.YOURE_WELCOME;
        } else if (content.endsWith("?")) {
            // Otázky: souhlas/nesouhlas/nechápání podle povahy.
            double roll = rng.next();
            if (roll < 0.4) {
                bank = PhraseBank.CONFUSED;
            } else if (roll < 0.4 + personality.trait(Trait.HELPFULNESS) * 0.4) {
                bank = PhraseBank.AGREEMENT;
            } else {
                bank = PhraseBank.DISAGREEMENT;
            }
        } else {
            bank = rng.chance(0.5) ? PhraseBank.AGREEMENT : PhraseBank.CONFUSED;
        }
        return PhraseBank.pick(bank, rng, name);
    }

    /** Zařadí zprávu do odchozí fronty: aplikuje styl, překlepy a případnou opravu. */
    private void enqueue(String message, int delayTicks) {
        TypoEngine.Result result = typos.apply(decorate(message), style);
        outbox.add(new Outbound(result.text(), delayTicks));

        // Oprava překlepu follow-up zprávou („*slovo") – jen pečliví boti.
        if (result.correction() != null && style.correctTypos() && rng.chance(0.6)) {
            int correctionDelay = rng.rangeInt(15, 45);
            outbox.add(new Outbound("*" + result.correction(), correctionDelay));
        }
    }

    /** Dozdobí zprávu podle stylu (vykřičníky, smajlíky). */
    private String decorate(String message) {
        String result = message;
        if (rng.chance(style.exclamations())) {
            result = result + "!";
        }
        if (rng.chance(style.emojiRate())) {
            result = result + " " + rng.pick(PhraseBank.EMOJIS);
        }
        return result;
    }
}
