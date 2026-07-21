package dev.botalive.core.ai;

import dev.botalive.core.ai.BotDrives.Drive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje pudovou arbitráž: neutralitu při uspokojených potřebách, supresi
 * vyšších potřeb naléhavými základními (Maslow), nedotknutelnost bezpečí,
 * odolnost seberealizace a čtení dominantní potřeby.
 */
class BotDrivesTest {

    @Test
    void satisfiedBotDoesNotModulate() {
        BotDrives drives = new BotDrives();
        drives.update(20, 20, 0, 0.0, 0.0, 0.3);
        assertEquals(1.0, drives.modulate("explore"), 1e-9);
        assertEquals(1.0, drives.modulate("socialize"), 1e-9);
        assertEquals(1.0, drives.modulate("survive"), 1e-9);
        assertNull(drives.dominant());
    }

    @Test
    void hungerSuppressesHigherPursuits() {
        BotDrives drives = new BotDrives();
        drives.update(20, 2, 0, 0.0, 0.0, 0.0); // vyhladovělý, jinak v pohodě
        assertTrue(drives.modulate("explore") < 1.0, "hlad tlumí průzkum");
        assertTrue(drives.modulate("socialize") < 1.0, "hlad tlumí družení");
        // Jídlo je fundamentálnější než seberealizace – shánět jídlo se netlumí.
        assertEquals(1.0, drives.modulate("eat"), 1e-9);
        assertEquals(Drive.SUSTENANCE, drives.dominant());
    }

    @Test
    void safetyGoalsAreNeverSuppressed() {
        BotDrives drives = new BotDrives();
        drives.update(3, 0, 4, 1.0, 1.0, 1.0); // vše naléhavé
        assertEquals(1.0, drives.modulate("survive"), 1e-9);
        assertEquals(1.0, drives.modulate("escape"), 1e-9);
        assertEquals(Drive.SAFETY, drives.dominant());
    }

    @Test
    void higherNeedsSuppressedMoreThanLowerOnes() {
        BotDrives drives = new BotDrives();
        drives.update(4, 6, 2, 0.0, 0.0, 0.0); // ohrožený a hladový
        double safetyGoal = drives.modulate("survive");    // nikdy netlumeno
        double sustenanceGoal = drives.modulate("hunt");   // tlumí jen bezpečí
        double esteemGoal = drives.modulate("explore");    // tlumí bezpečí i jídlo
        assertEquals(1.0, safetyGoal, 1e-9);
        assertTrue(sustenanceGoal < 1.0, "ohrožení tlumí i lov");
        assertTrue(esteemGoal < sustenanceGoal, "vyšší potřeba je tlumena víc než nižší");
    }

    @Test
    void esteemResolveResistsSuppression() {
        BotDrives timid = new BotDrives();
        timid.update(20, 4, 0, 0.0, 0.0, 0.0); // hlad, žádná odolnost
        BotDrives bold = new BotDrives();
        bold.update(20, 4, 0, 0.0, 0.0, 0.9); // hlad, vysoká odolnost

        assertTrue(bold.modulate("explore") > timid.modulate("explore"),
                "odvážný/zvídavý bot na výpravy tlačí i o hladu");
        // Odolnost se týká jen seberealizace, ne družení.
        assertEquals(timid.modulate("socialize"), bold.modulate("socialize"), 1e-9);
    }
}
