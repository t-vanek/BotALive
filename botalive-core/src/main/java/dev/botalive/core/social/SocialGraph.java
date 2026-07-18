package dev.botalive.core.social;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.util.BotRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sociální adresář botů – kdo je bot (a ne hráč) a drby mezi boty.
 *
 * <p>Sdílená služba pro mechaniky, které potřebují vidět „přes plot" vlastní
 * paměti: rozlišení bot vs. skutečný hráč (vítání ve vesnici, prodej hráčům,
 * usmiřování) a výměnu drbů při socializaci. Vědění tak přestává být
 * ostrovní – bot ví jen to, co sám zažil, dokud si nepokecá se sousedem.</p>
 *
 * <p>Drb je přenesená vzpomínka s poloviční důležitostí (drb ≠ zážitek):
 * místa (vesnice, doly, nebezpečí) si boti předávají volně, pomluvy
 * („ten a ten krade") jen posluchači, kterému vypravěč aspoň trochu věří.
 * Opakované drby stejnou vzpomínku oživují, takže zloděj časem získá
 * reputaci v celé vesnici – sociální tlak bez centrální autority. Slabé
 * drby (pod {@link #MIN_WORTH_TELLING}) se dál nešíří, řetěz řečí tedy
 * přirozeně vyhasíná.</p>
 *
 * <p>Vlákna: volá se z tick vlákna jedné strany; paměti obou stran jsou
 * thread-safe a nikdy se nedrží dva zámky najednou (čtení a zápis jsou
 * oddělené kroky).</p>
 */
public final class SocialGraph {

    /** Důležitost drbu = důležitost zážitku × tento faktor. */
    private static final double GOSSIP_FACTOR = 0.5;
    /** Vzpomínky pod touto důležitostí nestojí za řeč. */
    private static final double MIN_WORTH_TELLING = 0.2;
    /** Pomluvy (ENEMY) chtějí silnější zážitek než zmínka o dole. */
    private static final double MIN_RUMOR_IMPORTANCE = 0.4;
    /** Pomluvy se sdílí jen s posluchačem, kterému vypravěč věří (FRIEND). */
    private static final double RUMOR_TRUST = 0.3;
    /** Kolik vzpomínek jedna strana předá za jednu konverzaci. */
    private static final int MAX_SHARED = 2;

    private volatile BotManagerImpl botManager;

    /** Připojí manager botů (posty konstrukce, jako u ostatních služeb). */
    public void attach(BotManagerImpl manager) {
        this.botManager = manager;
    }

    /**
     * @param id UUID entity
     * @return bot daného UUID, pokud existuje
     */
    public Optional<Bot> bot(UUID id) {
        BotManagerImpl manager = botManager;
        return manager == null || id == null ? Optional.empty() : manager.byId(id);
    }

    /**
     * @param id UUID entity
     * @return {@code true} pokud UUID patří botovi (jinak skutečný hráč)
     */
    public boolean isBot(UUID id) {
        return bot(id).isPresent();
    }

    /**
     * Oboustranná výměna drbů mezi botem a protistranou (je-li protistrana bot).
     *
     * @param self    bot, který konverzaci vede (jeho tick vlákno)
     * @param otherId protistrana
     * @param rng     náhoda vedoucího bota
     * @return {@code true} když si předali aspoň jeden drb
     */
    public boolean exchangeGossip(Bot self, UUID otherId, BotRandom rng) {
        Optional<Bot> other = bot(otherId);
        if (other.isEmpty() || self.id().equals(otherId)) {
            return false;
        }
        return tell(self, other.get(), rng) + tell(other.get(), self, rng) > 0;
    }

    /** Vypravěč předá posluchači pár vzpomínek; vrací počet předaných. */
    private int tell(Bot teller, Bot listener, BotRandom rng) {
        double trust = maxImportance(teller.memory().recallAbout(listener.id()),
                MemoryKind.FRIEND);
        List<MemoryRecord> places = new ArrayList<>();
        places.addAll(teller.memory().recall(MemoryKind.VILLAGE));
        places.addAll(teller.memory().recall(MemoryKind.MINE));
        places.addAll(teller.memory().recall(MemoryKind.DANGER));
        // Portály se sdílí taky – vesnice tak časem používá společný portál
        // do Netheru místo lesa rámů (výprava čte „to"/„built" z dat).
        places.addAll(teller.memory().recall(MemoryKind.PORTAL));
        List<MemoryRecord> shareable = shareable(places,
                teller.memory().recall(MemoryKind.ENEMY), trust, listener.id());
        int told = 0;
        while (told < MAX_SHARED && !shareable.isEmpty()) {
            MemoryRecord record = shareable.remove(rng.rangeInt(0, shareable.size() - 1));
            listener.memory().remember(record.kind(), record.world(), record.x(),
                    record.y(), record.z(), record.subject(), gossipStamp(teller, record),
                    record.importance() * GOSSIP_FACTOR);
            told++;
        }
        return told;
    }

    /**
     * Razítko drbu; klíče nesoucí význam („to"/„built" u portálů) se
     * zachovávají – bez nich by předaný portál nebyl použitelný.
     */
    private static Map<String, String> gossipStamp(Bot teller, MemoryRecord record) {
        Map<String, String> stamp = new java.util.HashMap<>();
        if (record.data() != null) {
            record.data().forEach((key, value) -> {
                if (key.equals("to") || key.equals("built")) {
                    stamp.put(key, value);
                }
            });
        }
        stamp.put("via", "gossip");
        stamp.put("from", teller.name());
        return stamp;
    }

    /**
     * Co z vypravěčovy paměti stojí za řeč (čistá logika, testovatelná).
     *
     * @param places     vzpomínky na místa (VILLAGE/MINE/DANGER)
     * @param grudges    vypravěčovy ENEMY vzpomínky
     * @param trust      vypravěčovo přátelství k posluchači (0–1)
     * @param listenerId posluchač (pomluvy o něm samém se neříkají jemu)
     * @return kandidáti na předání (mutabilní seznam)
     */
    static List<MemoryRecord> shareable(List<MemoryRecord> places,
                                        List<MemoryRecord> grudges,
                                        double trust, UUID listenerId) {
        List<MemoryRecord> result = new ArrayList<>();
        for (MemoryRecord record : places) {
            if (record.importance() >= MIN_WORTH_TELLING) {
                result.add(record);
            }
        }
        if (trust >= RUMOR_TRUST) {
            for (MemoryRecord record : grudges) {
                if (record.subject() != null && !record.subject().equals(listenerId)
                        && record.importance() >= MIN_RUMOR_IMPORTANCE) {
                    result.add(record);
                }
            }
        }
        return result;
    }

    /** Nejvyšší důležitost vzpomínek dané kategorie v seznamu. */
    private static double maxImportance(List<MemoryRecord> records, MemoryKind kind) {
        double max = 0;
        for (MemoryRecord record : records) {
            if (record.kind() == kind && record.importance() > max) {
                max = record.importance();
            }
        }
        return max;
    }
}
