package dev.botalive.core.chat;

import dev.botalive.core.util.BotRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy generátoru překlepů a stylu psaní.
 */
class TypoEngineTest {

    private static ChatStyle style(double typoRate, boolean lowercase, boolean dropDiacritics,
                                   boolean punctuation) {
        return new ChatStyle(typoRate, true, lowercase, dropDiacritics, punctuation,
                0, false, 160, 0);
    }

    @Test
    void jeDeterministickyProStejnySeed() {
        TypoEngine first = new TypoEngine(new BotRandom(42));
        TypoEngine second = new TypoEngine(new BotRandom(42));
        ChatStyle style = style(0.5, true, true, false);

        assertEquals(first.apply("tohle je testovaci zprava", style).text(),
                second.apply("tohle je testovaci zprava", style).text(),
                "stejný seed musí dát stejné překlepy");
    }

    @Test
    void plnaChybovostZkomoliAVratiOpravu() {
        TypoEngine engine = new TypoEngine(new BotRandom(7));
        ChatStyle style = style(1.0, false, false, true);
        String original = "tohle je docela dlouha zprava bez diakritiky";

        TypoEngine.Result result = engine.apply(original, style);

        assertNotEquals(original, result.text(), "při 100% chybovosti musí vzniknout překlep");
        assertNotNull(result.correction(), "první zkomolené slovo se vrací k opravě");
        assertTrue(original.contains(result.correction()), "oprava je slovo z originálu");
    }

    @Test
    void odstraniDiakritikuAVelkaPismena() {
        TypoEngine engine = new TypoEngine(new BotRandom(1));
        ChatStyle style = style(0.0, true, true, true);

        TypoEngine.Result result = engine.apply("Žluťoučký Kůň", style);

        assertEquals("zlutoucky kun", result.text());
    }

    @Test
    void nulovaChybovostNechaZpravuBytKromeStylu() {
        TypoEngine engine = new TypoEngine(new BotRandom(1));
        ChatStyle style = style(0.0, false, false, true);

        TypoEngine.Result result = engine.apply("ahoj jak se mas", style);

        assertEquals("ahoj jak se mas", result.text());
        assertFalse(result.correction() != null, "bez překlepu není co opravovat");
    }
}
