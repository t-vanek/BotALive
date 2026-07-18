package dev.botalive.core.chat;

import dev.botalive.api.personality.Personality;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.personality.PersonalityGenerator;
import dev.botalive.core.util.BotRandom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy spontánního guvernéru chatu – rozestupy, neopakování, urgentní kanál.
 *
 * <p>Guvernér je hlavní ochrana proti spamu: 30 botů nesmí překřikovat
 * jeden druhého ani papouškovat stejné fráze v dávkách. Čas se v testech
 * posouvá ručně (injektované hodiny) – tick zpracuje frontu, hodiny řídí
 * rozestupy.</p>
 */
class ChatEngineTest {

    private static final BotAliveConfig.Chat CONFIG =
            new BotAliveConfig.Chat(true, "cs", 0.75, 200, 3);

    /** Testovací stojan: engine, zachycené zprávy a ručně řízené hodiny. */
    private static final class Rig {
        final List<String> sent = new ArrayList<>();
        final long[] nowMs = {1_000_000};
        final ChatEngine engine;

        Rig(long seed) {
            Personality personality = PersonalityGenerator.generate(seed);
            engine = new ChatEngine("Karel13", personality, new BotRandom(seed),
                    CONFIG, PhraseBankLoader.builtIn(), sent::add, null);
            engine.clock(() -> nowMs[0]);
        }

        /** Odtiká engine a posune hodiny (50 ms na tick jako herní server). */
        void run(int ticks) {
            for (int i = 0; i < ticks; i++) {
                nowMs[0] += 50;
                engine.tick();
            }
        }
    }

    @Test
    void spontanniHlaskySeNespamuji() {
        Rig rig = new Rig(42);
        // Minuta bombardování spontánními hláškami (pokus každou sekundu) –
        // projít smí nanejvýš pár, guvernér drží rozestupy.
        for (int i = 0; i < 60; i++) {
            rig.engine.sayFrom(PhraseCategory.IDLE_CHATTER, null);
            rig.run(20); // 1 s
        }
        rig.run(200);
        assertTrue(rig.sent.size() <= 3,
                "guvernér má pustit max pár hlášek za minutu, prošlo: " + rig.sent);
        assertFalse(rig.sent.isEmpty(), "úplně umlčet bota guvernér nesmí");
    }

    @Test
    void stejnaHlaskaSeNeopakuje() {
        Rig rig = new Rig(7);
        rig.engine.say("jdu kopat");
        rig.run(40);
        assertEquals(1, rig.sent.size(), "první hláška má projít");

        // Po uplynutí rozestupu tentýž text znovu – dedupe ho zadrží.
        rig.run(2400); // 2 minuty
        rig.engine.say("jdu kopat");
        rig.run(40);
        assertEquals(1, rig.sent.size(), "opakovaná hláška nesmí projít");

        // Jiný text po rozestupu projde.
        rig.run(2400);
        rig.engine.say("jdu stavet");
        rig.run(40);
        assertEquals(2, rig.sent.size(), "nová hláška po rozestupu má projít");
    }

    @Test
    void urgentniHlaskaPrerusiTicho() {
        Rig rig = new Rig(99);
        rig.engine.say("jdu kopat");
        rig.run(40);
        int before = rig.sent.size();

        // Spontánní rozestup zdaleka neuplynul – běžná hláška neprojde…
        rig.run(60); // 3 s
        rig.engine.sayFrom(PhraseCategory.IDLE_CHATTER, null);
        rig.run(40);
        assertEquals(before, rig.sent.size(), "běžná hláška v tichu neprojde");

        // …ale urgentní varování ano.
        rig.engine.sayUrgent(PhraseCategory.MOB_WARNING, null);
        rig.run(40);
        assertTrue(rig.sent.size() > before, "urgentní varování má projít hned");
    }

    @Test
    void urgentniKategorieMaVlastniCooldown() {
        Rig rig = new Rig(5);
        rig.engine.sayUrgent(PhraseCategory.MOB_WARNING, null);
        rig.run(40);
        assertEquals(1, rig.sent.size(), "první varování má projít");

        rig.run(60); // 3 s – urgentní odstup uplynul, kategorie ještě ne
        rig.engine.sayUrgent(PhraseCategory.MOB_WARNING, null);
        rig.run(40);
        assertEquals(1, rig.sent.size(),
                "druhé varování hned po prvním nesmí projít (cooldown kategorie)");
    }

    @Test
    void primaProsbaSeJmenemVzdyOdpovi() {
        // Prosba se jménem bota musí projít bez ohledu na náhodu a povahu.
        for (long seed = 1; seed <= 8; seed++) {
            Rig rig = new Rig(seed);
            rig.engine.onMessage(UUID.randomUUID(), "Tester", "Karel13 pojď sem");
            rig.run(600);
            assertFalse(rig.sent.isEmpty(),
                    "bot (seed " + seed + ") má na přímou prosbu odpovědět");
        }
    }

    @Test
    void sborovyDedupeZastaviPapouskovani() {
        // Dva boti sdílejí banku (jako na serveru) – druhý nesmí zopakovat
        // frázi, kterou první právě řekl, i když mu ji RNG nabídne.
        PhraseBank shared = PhraseBankLoader.builtIn();
        long[] now = {1_000_000};
        List<String> sentA = new ArrayList<>();
        List<String> sentB = new ArrayList<>();
        ChatEngine botA = new ChatEngine("Karel13", PersonalityGenerator.generate(1),
                new BotRandom(1), CONFIG, shared, sentA::add, null);
        ChatEngine botB = new ChatEngine("Lucka99", PersonalityGenerator.generate(2),
                new BotRandom(1), CONFIG, shared, sentB::add, null);
        botA.clock(() -> now[0]);
        botB.clock(() -> now[0]);

        botA.sayFrom(PhraseCategory.NIGHTFALL, null);
        for (int i = 0; i < 40; i++) {
            now[0] += 50;
            botA.tick();
        }
        // Stejný RNG seed → bez sborové paměti by B vybral stejnou frázi.
        botB.sayFrom(PhraseCategory.NIGHTFALL, null);
        for (int i = 0; i < 40; i++) {
            now[0] += 50;
            botB.tick();
        }
        assertEquals(1, sentA.size());
        if (!sentB.isEmpty()) {
            assertFalse(sentB.get(0).equals(sentA.get(0)),
                    "druhý bot nesmí papouškovat: " + sentA + " vs " + sentB);
        }
    }

    @Test
    void slysiNaSklonenyTvarJmena() {
        Rig rig = new Rig(3);
        // „Karle" je 5. pád jádra nicku Karel13 – bot to má brát jako zmínku.
        rig.engine.onMessage(UUID.randomUUID(), "Tester", "Karle, dej mi drevo");
        rig.run(600);
        assertFalse(rig.sent.isEmpty(), "bot má slyšet na skloněné jméno");
    }
}
