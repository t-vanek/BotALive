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
import java.util.function.Supplier;

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

    /** Minimální odstup JAKÝCHKOLI dvou odeslaných zpráv (i odpovědí, ms). */
    private static final long MIN_OUTGOING_GAP_MS = 4_000;

    /** Základní odstup spontánních hlášek (ms); škáluje se povahou. */
    private static final long SPONTANEOUS_GAP_MS = 35_000;

    /** Odstup dvou hlášek téže kategorie (ms) – ať bot neomílá totéž téma. */
    private static final long CATEGORY_GAP_MS = 120_000;

    /** Odstup urgentních hlášek (nebezpečí) – jediné, co smí „skákat do řeči". */
    private static final long URGENT_GAP_MS = 2_000;

    /** Odstup urgentních hlášek téže kategorie (další creeper za chvíli). */
    private static final long URGENT_CATEGORY_GAP_MS = 15_000;

    /** Kolik posledních zpráv si bot pamatuje proti opakování. */
    private static final int RECENT_MEMORY = 8;

    private final String botName;
    private final Personality personality;
    private final BotRandom rng;
    private final ChatStyle style;
    private final TypoEngine typos;
    private final BotAliveConfig.Chat config;
    private final PhraseBank phrases;
    private final Consumer<String> sender;
    /** Napojení na stav bota (intent, poloha, inventář, prosby); může být null. */
    private final ChatContext botContext;

    /** Tvary jména, na které bot slyší (včetně skloněných pádů), malými písmeny. */
    private final java.util.Set<String> mentionForms;

    /** Příchozí zprávy ze síťového vlákna. */
    private final Queue<Inbound> inbox = new ConcurrentLinkedQueue<>();

    /** Naplánované odchozí zprávy (odpočet v ticích); plní se i z jiných vláken. */
    private final Deque<Outbound> outbox = new ConcurrentLinkedDeque<>();

    private final Map<UUID, Long> senderCooldowns = new ConcurrentHashMap<>();
    /** Zdroj času (nahraditelný v testech – guvernér stojí na rozestupech). */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private long lastSentAtMs;
    private long lastSpontaneousAtMs;
    /** Poslední spontánní hláška dané kategorie (ms epoch). */
    private final Map<PhraseCategory, Long> categoryLastMs = new ConcurrentHashMap<>();
    /** Poslední odeslané texty (normalizované) – ochrana proti papouškování. */
    private final Deque<String> recentSent = new ConcurrentLinkedDeque<>();

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
     * @param phrases     banka frází zvoleného jazyka (sdílená všemi boty)
     * @param sender      callback pro fyzické odeslání zprávy (přes BotChatEvent)
     * @param botContext  napojení na stav bota (intent, poloha, prosby; může být null)
     */
    public ChatEngine(String botName, Personality personality, BotRandom rng,
                      BotAliveConfig.Chat config, PhraseBank phrases,
                      Consumer<String> sender, ChatContext botContext) {
        this.botName = botName;
        this.personality = personality;
        this.rng = rng;
        this.config = config;
        this.phrases = phrases;
        this.sender = sender;
        this.botContext = botContext;
        this.style = ChatStyle.derive(personality, rng, config.wordsPerMinute());
        this.typos = new TypoEngine(rng);
        this.mentionForms = phrases.mentionForms(botName);
    }

    /** Nahradí zdroj času (jen testy). */
    void clock(java.util.function.LongSupplier newClock) {
        this.clock = newClock;
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
     * Přímé promluvení (API {@code Bot.say} / spontánní hlášky cílů) –
     * prochází humanizací a spontánním guvernérem (rozestupy, opakování,
     * tlumení v davu), bez reakční latence.
     *
     * @param message text
     */
    public void say(String message) {
        if (!config.enabled() || !spontaneousAllowed(null, false)) {
            return;
        }
        if (recentlySaid(message)) {
            return; // stejnou hlášku nedávno říkal – mlčet je lidštější
        }
        markSpontaneous(null);
        enqueue(message, 5 + rng.rangeInt(0, 15));
    }

    /**
     * Spontánní hláška z banky frází – guvernér drží rozestupy (globální,
     * per kategorie), tlumí mluvení v davu botů a nepapouškuje nedávné fráze.
     *
     * @param category kategorie
     * @param name     jméno protistrany (může být null)
     */
    public void sayFrom(PhraseCategory category, String name) {
        if (!config.enabled() || !spontaneousAllowed(category, false)) {
            return;
        }
        String message = pickFresh(category, name);
        if (message == null) {
            return;
        }
        markSpontaneous(category);
        enqueue(message, 5 + rng.rangeInt(0, 15));
    }

    /**
     * Urgentní hláška (creeper, přepadení) – přeskakuje spontánní rozestupy,
     * drží jen krátký odstup a deduplikaci; kategorie má vlastní cooldown,
     * ať bot nehuláká „creeper" každý tick.
     *
     * @param category kategorie
     * @param name     jméno protistrany (může být null)
     */
    public void sayUrgent(PhraseCategory category, String name) {
        if (!config.enabled() || !spontaneousAllowed(category, true)) {
            return;
        }
        String message = pickFresh(category, name);
        if (message == null) {
            return;
        }
        markSpontaneous(category);
        enqueue(message, 2 + rng.rangeInt(0, 6));
    }

    /** Smí teď bot spustit spontánní/urgentní hlášku? */
    private boolean spontaneousAllowed(PhraseCategory category, boolean urgent) {
        long now = clock.getAsLong();
        if (outbox.size() >= config.maxQueuedReplies()) {
            return false;
        }
        // Kategorie se neomílá (platí i pro urgentní – jedno varování stačí).
        if (category != null) {
            Long last = categoryLastMs.get(category);
            long gap = urgent ? URGENT_CATEGORY_GAP_MS : CATEGORY_GAP_MS;
            if (last != null && now - last < gap) {
                return false;
            }
        }
        if (urgent) {
            return now - lastSentAtMs >= URGENT_GAP_MS;
        }
        // Mluvnost podle povahy: společenští boti mluví častěji, ale nikdy
        // pod základní rozestup.
        double sociability = personality.trait(Trait.SOCIABILITY);
        long gap = (long) (SPONTANEOUS_GAP_MS * (1.5 - sociability * 0.8));
        if (now - lastSpontaneousAtMs < gap) {
            return false;
        }
        // Tlumení v davu: čím víc lidí/botů okolo, tím menší šance mluvit –
        // ať se 30 botů nepřekřikuje (v průměru mluví pořád stejně „hlasitě"
        // celá skupina, ne každý zvlášť).
        int nearby = botContext != null ? botContext.nearbyPlayerCount() : 0;
        return rng.next() < 1.0 / (1.0 + nearby * 0.5);
    }

    /** Zaznamená spontánní hlášku pro guvernér. */
    private void markSpontaneous(PhraseCategory category) {
        long now = clock.getAsLong();
        lastSpontaneousAtMs = now;
        if (category != null) {
            categoryLastMs.put(category, now);
        }
    }

    /** Vybere frázi kategorie, kterou nedávno neřekl on ANI nikdo jiný. */
    private String pickFresh(PhraseCategory category, String name) {
        for (int attempt = 0; attempt < 4; attempt++) {
            String candidate = phrases.pick(category, rng, name);
            if (!recentlySaid(candidate)
                    && !phrases.saidRecentlyByAnyone(normalize(candidate), clock.getAsLong())) {
                return candidate;
            }
        }
        return null; // celá kategorie ohraná – radši mlčet
    }

    /** Odpověď z kategorie – vyhne se opakováním, ale odpoví vždy. */
    private String pickReply(PhraseCategory category, String name) {
        for (int attempt = 0; attempt < 4; attempt++) {
            String candidate = phrases.pick(category, rng, name);
            if (!recentlySaid(candidate)
                    && !phrases.saidRecentlyByAnyone(normalize(candidate), clock.getAsLong())) {
                return candidate;
            }
        }
        return phrases.pick(category, rng, name); // odpovědět je víc než neopakovat
    }

    /** Řekl bot tohle (normalizovaně) nedávno? */
    private boolean recentlySaid(String message) {
        String normalized = normalize(message);
        return recentSent.contains(normalized);
    }

    /** Zapamatuje odeslaný text proti papouškování (vlastnímu i sborovému). */
    private void rememberSent(String message) {
        String normalized = normalize(message);
        recentSent.addFirst(normalized);
        while (recentSent.size() > RECENT_MEMORY) {
            recentSent.pollLast();
        }
        phrases.markSaid(normalized, clock.getAsLong());
    }

    private static String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").strip();
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
        // Odchozí odpočet; mezi zprávami se drží lidský minimální rozestup.
        Outbound head = outbox.peek();
        if (head != null && --head.ticksRemaining <= 0) {
            if (clock.getAsLong() - lastSentAtMs < MIN_OUTGOING_GAP_MS) {
                head.ticksRemaining = rng.rangeInt(10, 30); // počkat a zkusit znovu
                return;
            }
            outbox.poll();
            lastSentAtMs = clock.getAsLong();
            sender.accept(head.text);
        }
    }

    /** Rozhodne, zda a jak odpovědět na příchozí zprávu. */
    private void considerReply(Inbound inbound) {
        long now = clock.getAsLong();
        if (outbox.size() >= config.maxQueuedReplies()) {
            return;
        }
        // Nákup hráče („beru!") na vyvolávanou nabídku: zamluvení je
        // první-bere, takže se řeší hned a mimo pravděpodobnostní brány –
        // trhovec na zákazníka reaguje vždy. Bez vlastní aktivní nabídky
        // spadne zpráva do běžné konverzace.
        if (botContext != null && phrases.matches("market-buy", inbound.content)
                && botContext.marketBuyRequest(inbound.sender, inbound.senderName)) {
            senderCooldowns.put(inbound.sender, now);
            String confirmation = pickReply(PhraseCategory.MARKET_DEAL, inbound.senderName);
            enqueue(confirmation, replyDelayTicks(confirmation));
            return;
        }
        boolean mentioned = isMentioned(inbound.content);
        double sociability = personality.trait(Trait.SOCIABILITY);
        boolean request = isRequest(inbound.content);

        if (mentioned && request) {
            // Přímá prosba se jménem – bot vždy zareaguje (vyhovět/odmítnout
            // rozhodne povaha uvnitř), cooldowny se neaplikují.
            senderCooldowns.put(inbound.sender, now);
        } else {
            // Nezmíněný bot reaguje jen výjimečně a jen když je společenský;
            // na volání o pomoc bez adresáta slyší ochotní. V davu se šance
            // dělí počtem posluchačů – konverzace 30 botů se jinak sama živí
            // (každá zpráva zplodí ~1 odpověď a vlákno nikdy neuhasne).
            double crowd = 1.0 / (1.0
                    + (botContext != null ? botContext.nearbyPlayerCount() : 0) * 0.4);
            double chance = mentioned
                    ? config.replyChance() * (0.6 + sociability * 0.6)
                    : request && phrases.matches("help", inbound.content)
                            ? (0.25 + personality.trait(Trait.HELPFULNESS) * 0.35) * crowd
                            : config.replyChance() * sociability * 0.05 * crowd;
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
        }

        String reply = composeReply(inbound);
        enqueue(reply, replyDelayTicks(reply));
    }

    /** Reakční latence: přemýšlení (0.5–3 s dle povahy) + psaní podle délky a WPM. */
    private int replyDelayTicks(String reply) {
        double thinkMs = Math.abs(rng.gaussian(1200, 700))
                * (1.0 + personality.trait(Trait.LAZINESS) * 0.5)
                * (1.2 - personality.trait(Trait.INTELLIGENCE) * 0.4);
        double typingMs = reply.length() / (style.wpm() * 5.0 / 60_000.0);
        return (int) Math.max(10, (thinkMs + typingMs) / 50);
    }

    /** Sestaví odpověď podle jednoduché klasifikace zprávy (vzory z jazyka banky). */
    private String composeReply(Inbound inbound) {
        String content = inbound.content;
        String name = inbound.senderName;

        // Věcné otázky a prosby → odpovědi ze skutečného stavu bota.
        if (botContext != null) {
            String informed = informedReply(inbound, content, name);
            if (informed != null) {
                return informed;
            }
        }

        PhraseCategory category;
        if (phrases.isGreeting(content)) {
            category = PhraseCategory.GREETINGS;
        } else if (phrases.isThanks(content)) {
            category = PhraseCategory.YOURE_WELCOME;
        } else if (content.strip().endsWith("?")) {
            // Otázky: souhlas/nesouhlas/nechápání podle povahy.
            double roll = rng.next();
            if (roll < 0.4) {
                category = PhraseCategory.CONFUSED;
            } else if (roll < 0.4 + personality.trait(Trait.HELPFULNESS) * 0.4) {
                category = PhraseCategory.AGREEMENT;
            } else {
                category = PhraseCategory.DISAGREEMENT;
            }
        } else {
            category = rng.chance(0.5) ? PhraseCategory.AGREEMENT : PhraseCategory.CONFUSED;
        }
        return pickReply(category, name);
    }

    /** Odpověď na věcnou otázku/prosbu ze stavu bota, nebo {@code null}. */
    private String informedReply(Inbound inbound, String content, String name) {
        if (phrases.matches("what-doing", content)) {
            String activity = botContext.describeActivity();
            return activity != null && !activity.isBlank()
                    ? activity : pickReply(PhraseCategory.IDLE_CHATTER, name);
        }
        if (phrases.matches("where-village", content)) {
            String village = botContext.describeVillage();
            return village != null ? village
                    : pickReply(PhraseCategory.NO_VILLAGE_KNOWN, name);
        }
        if (phrases.matches("where-are-you", content)) {
            return botContext.describeLocation();
        }
        if (phrases.matches("what-have", content)) {
            return botContext.describeInventory();
        }
        // Volání o pomoc má přednost před obecným „pojď sem".
        if (phrases.matches("help", content)) {
            return botContext.helpRequest(inbound.sender)
                    ? pickReply(PhraseCategory.PVP_ASSIST, name)
                    : pickReply(PhraseCategory.REQUEST_DECLINE, name);
        }
        if (phrases.matches("come-here", content)) {
            return botContext.followRequest(inbound.sender)
                    ? pickReply(PhraseCategory.REQUEST_ACCEPT, name)
                    : pickReply(PhraseCategory.REQUEST_DECLINE, name);
        }
        if (phrases.matches("give-food", content)) {
            return botContext.giveFoodRequest(inbound.sender)
                    ? pickReply(PhraseCategory.GIVE_ACCEPT, name)
                    : pickReply(PhraseCategory.GIVE_DECLINE, name);
        }
        // Prosba o konkrétní item: „dej mi dřevo", „máš uhlí?".
        if (phrases.matches("give-item", content)) {
            var wanted = phrases.requestedItems(content);
            if (!wanted.isEmpty()) {
                return botContext.giveItemRequest(inbound.sender, wanted)
                        ? phrases.pick(PhraseCategory.GIVE_ACCEPT, rng, name)
                        : phrases.pick(PhraseCategory.GIVE_DECLINE, rng, name);
            }
        }
        return null;
    }

    /** Je zpráva prosba, kterou umí bot vykonat (pomoc, pojď sem, dej mi…)? */
    private boolean isRequest(String content) {
        return phrases.matches("help", content)
                || phrases.matches("come-here", content)
                || phrases.matches("give-food", content)
                || phrases.matches("give-item", content);
    }

    /** Zmiňuje zpráva bota? Slyší i na skloněné tvary a jádro nicku. */
    private boolean isMentioned(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        for (String form : mentionForms) {
            if (lower.contains(form)) {
                return true;
            }
        }
        return false;
    }

    /** Zařadí zprávu do odchozí fronty: aplikuje styl, překlepy a případnou opravu. */
    private void enqueue(String message, int delayTicks) {
        // Paměť proti papouškování drží ZÁMĚR (před překlepy a dekorací),
        // aby se stejná fráze poznala i příště.
        rememberSent(message);
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
            result = result + " " + rng.pick(phrases.list(PhraseCategory.EMOJIS));
        }
        return result;
    }
}
