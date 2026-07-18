package dev.botalive.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy skloňování jmen a nicků.
 *
 * <p>Reference = jak by jméno napsal český hráč v chatu bez diakritiky.
 * U nejistých tvarů je správná odpověď „beze změny" – špatný pád je horší
 * než žádný.</p>
 */
class CzechNamesTest {

    private static void voc(String expected, String nick) {
        assertEquals(expected, CzechNames.decline(nick, 5), "5. pád: " + nick);
    }

    @Test
    void oslovenieMuzskaTvrda() {
        voc("Martine", "Martin");
        voc("Petre", "Petr");
        voc("Davide", "David");
        voc("Adame", "Adam");
        voc("Milane", "Milan");
        voc("Michale", "Michal");
        voc("Filipe", "Filip");
        voc("Jakube", "Jakub");
        voc("Romane", "Roman");
        voc("Same", "Sam");
    }

    @Test
    void osloveniVelary() {
        voc("Radku", "Radek");
        voc("Zdenku", "Zdenek");
        voc("Marku", "Marek");
        voc("Vojtechu", "Vojtech");
    }

    @Test
    void osloveniMekka() {
        voc("Tomasi", "Tomas");
        voc("Lukasi", "Lukas");
        voc("Mateji", "Matej");
        voc("Alexi", "Alex");
        voc("Maxi", "Max");
    }

    @Test
    void osloveniElize() {
        voc("Karle", "Karel");
        voc("Pavle", "Pavel");
    }

    @Test
    void osloveniMuzskaNaA() {
        voc("Honzo", "Honza");
        voc("Ondro", "Ondra");
        voc("Stando", "Standa");
        voc("Vojto", "Vojta");
    }

    @Test
    void osloveniZenska() {
        voc("Lucko", "Lucka");
        voc("Katko", "Katka");
        voc("Anicko", "Anicka");
        voc("Terko", "Terka");
        voc("Baro", "Bara");
        voc("Sofie", "Sofie");   // 5. pád = 1. pád
        voc("Nikol", "Nikol");   // nesklonné
        voc("Elis", "Elis");     // nesklonné
    }

    @Test
    void osloveniZdobenychNicku() {
        voc("Lucko", "Lucka360");        // číselný ocas pryč, jádro skloněné
        voc("Karle", "Karel13");
        voc("Ninjo", "Xx_Ninja_xX");     // dekorace pryč
        voc("sofie_13", "sofie_13");     // 5. pád Sofie = 1. pád → nick vcelku
        voc("milane", "milan_69");
        voc("CrazyWolfe", "CrazyWolf13");
        voc("N00bV1per", "N00bV1per");   // leet bez čitelného jádra – beze změny
        voc("Leo", "Leo");               // 5. pád = 1. pád
    }

    @Test
    void dalsiPadyMuzske() {
        assertEquals("Karla", CzechNames.decline("Karel", 2), "2. pád");
        assertEquals("Karlovi", CzechNames.decline("Karel", 3), "3. pád");
        assertEquals("Karla", CzechNames.decline("Karel", 4), "4. pád");
        assertEquals("Karlem", CzechNames.decline("Karel", 7), "7. pád");
        assertEquals("Tomase", CzechNames.decline("Tomas", 2));
        assertEquals("Tomasem", CzechNames.decline("Tomas", 7));
        assertEquals("Honzy", CzechNames.decline("Honza", 2));
        assertEquals("Honzovi", CzechNames.decline("Honza", 3));
        assertEquals("Honzou", CzechNames.decline("Honza", 7));
        assertEquals("Radka", CzechNames.decline("Radek", 2));
        assertEquals("Lea", CzechNames.decline("Leo", 2));
        assertEquals("Leovi", CzechNames.decline("Leo", 3));
        assertEquals("Leem", CzechNames.decline("Leo", 7));
    }

    @Test
    void dalsiPadyZenske() {
        assertEquals("Lucky", CzechNames.decline("Lucka", 2));
        assertEquals("Lucce", CzechNames.decline("Lucka", 3));
        assertEquals("Lucku", CzechNames.decline("Lucka", 4));
        assertEquals("Luckou", CzechNames.decline("Lucka", 7));
        assertEquals("Sofii", CzechNames.decline("Sofie", 3));
        assertEquals("Sofii", CzechNames.decline("Sofie", 7));
        // Dativ bez jisté alternace se neriskuje – beze změny.
        assertEquals("Bara", CzechNames.decline("Bara", 3));
    }

    @Test
    void okrajoveVstupy() {
        assertEquals("Karel", CzechNames.decline("Karel", 1), "1. pád beze změny");
        assertEquals("Karel", CzechNames.decline("Karel", 0), "neplatný pád beze změny");
        assertEquals("Karel", CzechNames.decline("Karel", 8), "neplatný pád beze změny");
        assertEquals("", CzechNames.decline(null, 5), "null → prázdný řetězec");
        assertEquals("xX", CzechNames.decline("xX", 5), "krátké jádro beze změny");
    }
}
