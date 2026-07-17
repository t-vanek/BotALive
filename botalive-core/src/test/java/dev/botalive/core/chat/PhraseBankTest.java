package dev.botalive.core.chat;

import dev.botalive.core.util.BotRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy vícejazyčné banky frází a jejího vrstveného načítání.
 */
class PhraseBankTest {

    @TempDir
    Path tempDir;

    @Test
    void vestavenaCestinaJeKompletni() {
        PhraseBank bank = PhraseBankLoader.builtIn();
        for (PhraseCategory category : PhraseCategory.values()) {
            assertFalse(bank.list(category).isEmpty(), category.key() + " nesmí být prázdná");
        }
    }

    @Test
    void vestavenaAnglictinaJeKompletni() {
        // en.yml v jaru musí pokrýt všechny kategorie sám (bez fallbacku na cs).
        PhraseBank cs = PhraseBankLoader.builtIn();
        PhraseBank en = PhraseBankLoader.load(tempDir.toFile(), "en");
        for (PhraseCategory category : PhraseCategory.values()) {
            if (category == PhraseCategory.EMOJIS) {
                continue; // smajlíky jsou záměrně společné
            }
            assertFalse(en.list(category).equals(cs.list(category)),
                    category.key() + " má mít vlastní anglický překlad");
        }
        assertTrue(en.isGreeting("hello there"));
        assertTrue(en.isThanks("thanks a lot"));
    }

    @Test
    void rozpoznavaniPozdravuFungujeNaDiakritiku() {
        // \b bez UNICODE_CHARACTER_CLASS na „čau" nefunguje – tohle hlídá fix.
        PhraseBank bank = PhraseBankLoader.builtIn();
        assertTrue(bank.isGreeting("čau lidi"));
        assertTrue(bank.isGreeting("Ahoj vespolek"));
        assertTrue(bank.isThanks("Díky moc!"));
        assertFalse(bank.isGreeting("dnes je hezky"));
    }

    @Test
    void rozpoznavaniOtazkyCoDelas() {
        PhraseBank bank = PhraseBankLoader.builtIn();
        assertTrue(bank.isAskingActivity("co děláš?"));
        assertTrue(bank.isAskingActivity("co delas"));
        assertTrue(bank.isAskingActivity("Pepo co teď děláš?"));
        assertTrue(bank.isAskingActivity("čím se zabýváš"));
        assertFalse(bank.isAskingActivity("co je to za blok"));
        assertFalse(bank.isAskingActivity("ahoj"));
    }

    @Test
    void rozpoznavaniVecnychOtazek() {
        PhraseBank bank = PhraseBankLoader.builtIn();
        assertTrue(bank.matches("where-are-you", "Pepo kde jsi?"));
        assertTrue(bank.matches("what-have", "co máš u sebe"));
        assertTrue(bank.matches("where-village", "kde je nejbližší vesnice?"));
        assertTrue(bank.matches("come-here", "pojď za mnou"));
        assertTrue(bank.matches("give-food", "dej mi něco k jídlu"));
        assertFalse(bank.matches("give-food", "kde jsi"));
        assertFalse(bank.matches("come-here", "co děláš"));
    }

    @Test
    void uzivatelskySouborPrepisujeJenDefinovaneKategorie() throws Exception {
        writeLang("cs", """
                phrases:
                  greetings:
                    - "nazdárek {name}"
                """);
        PhraseBank bank = PhraseBankLoader.load(tempDir.toFile(), "cs");
        assertEquals(1, bank.list(PhraseCategory.GREETINGS).size());
        assertEquals("nazdárek {name}", bank.list(PhraseCategory.GREETINGS).getFirst());
        // Nedefinované kategorie spadly na vestavěnou vrstvu.
        assertFalse(bank.list(PhraseCategory.DEATH_REACTIONS).isEmpty());
        // Nedefinované vzory zůstaly vestavěné.
        assertTrue(bank.isGreeting("ahoj"));
    }

    @Test
    void neznamyJazykSpadneNaCestinu() {
        PhraseBank bank = PhraseBankLoader.load(tempDir.toFile(), "de");
        assertEquals(PhraseBankLoader.builtIn().list(PhraseCategory.GREETINGS),
                bank.list(PhraseCategory.GREETINGS));
        assertTrue(bank.isGreeting("ahoj"));
    }

    @Test
    void novyJazykLzeDodatSouborem() throws Exception {
        writeLang("de", """
                patterns:
                  greeting: '\\b(hallo|moin|servus)\\b'
                phrases:
                  greetings:
                    - "hallo {name}"
                    - "moin"
                """);
        PhraseBank bank = PhraseBankLoader.load(tempDir.toFile(), "de");
        assertEquals(2, bank.list(PhraseCategory.GREETINGS).size());
        assertTrue(bank.isGreeting("Moin moin"));
        assertFalse(bank.isGreeting("ahoj")); // vzor přepsán německým
        assertTrue(bank.isThanks("díky"));    // thanks nedefinován → fallback cs
    }

    @Test
    void rozbityVzorSpadneNaPredchoziVrstvu() throws Exception {
        writeLang("cs", """
                patterns:
                  greeting: '[neuzavřená'
                """);
        PhraseBank bank = PhraseBankLoader.load(tempDir.toFile(), "cs");
        assertTrue(bank.isGreeting("ahoj"), "po chybě vzoru má platit vestavěný");
    }

    @Test
    void rozbityYamlSePreskoci() throws Exception {
        writeLang("cs", "phrases: [ tohle: neni validni yaml }");
        PhraseBank bank = PhraseBankLoader.load(tempDir.toFile(), "cs");
        assertFalse(bank.list(PhraseCategory.GREETINGS).isEmpty());
    }

    @Test
    void pickDoplniJmenoAOrizne() {
        PhraseBank bank = PhraseBankLoader.builtIn();
        BotRandom rng = new BotRandom(42);
        for (int i = 0; i < 50; i++) {
            String phrase = bank.pick(PhraseCategory.GREETINGS, rng, "Pepa");
            assertFalse(phrase.contains("{name}"), "placeholder musí být nahrazen");
            assertEquals(phrase, phrase.trim());
        }
        // null jméno nesmí nechat díru ani mezery na krajích.
        for (int i = 0; i < 50; i++) {
            String phrase = bank.pick(PhraseCategory.MEET_PLAYER, rng, null);
            assertFalse(phrase.contains("{name}"));
            assertEquals(phrase, phrase.trim());
        }
    }

    @Test
    void exportVytvoriSablonyJenJednou() throws Exception {
        File dataFolder = tempDir.toFile();
        PhraseBankLoader.exportDefaults(dataFolder);
        File cs = new File(dataFolder, "lang/cs.yml");
        File en = new File(dataFolder, "lang/en.yml");
        assertTrue(cs.isFile() && en.isFile());

        // Úprava přežije druhý export (neexportuje se přes existující).
        Files.writeString(cs.toPath(), "phrases:\n  greetings: [\"vlastní\"]\n",
                StandardCharsets.UTF_8);
        PhraseBankLoader.exportDefaults(dataFolder);
        assertTrue(Files.readString(cs.toPath(), StandardCharsets.UTF_8).contains("vlastní"));
    }

    private void writeLang(String code, String content) throws Exception {
        Path lang = tempDir.resolve("lang");
        Files.createDirectories(lang);
        Files.writeString(lang.resolve(code + ".yml"), content, StandardCharsets.UTF_8);
    }
}
