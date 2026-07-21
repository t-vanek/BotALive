package dev.botalive.core.via;

import dev.botalive.core.via.ViaCompat.Assessment;
import dev.botalive.core.via.ViaCompat.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy posouzení kompatibility verzí (podpora ViaVersion/ViaBackwards).
 */
class ViaCompatTest {

    private static final int BOT = 780;

    @Test
    void shodaProtokoluNepotrebujeViu() {
        Assessment a = ViaCompat.assess(BOT, "26.1", BOT, "26.1", false, false);
        assertEquals(Status.NATIVE_MATCH, a.status());
        assertTrue(a.connectable());
        assertFalse(a.translated());
    }

    @Test
    void patchVerzeSeStejnymProtokolemJeNativniShoda() {
        // 26.1 vs. 26.1.2 – jiný řetězec verze, stejný protokol → žádný překlad.
        Assessment a = ViaCompat.assess(BOT, "26.1.2", BOT, "26.1", false, false);
        assertEquals(Status.NATIVE_MATCH, a.status());
        assertTrue(a.connectable());
    }

    @Test
    void novejsiBotPotrebujeViaVersion() {
        Assessment chybi = ViaCompat.assess(BOT - 10, "25.4", BOT, "26.1", false, false);
        assertEquals(Status.MISSING_VIAVERSION, chybi.status());
        assertFalse(chybi.connectable());
        assertTrue(chybi.message().contains("ViaVersion"));

        Assessment ok = ViaCompat.assess(BOT - 10, "25.4", BOT, "26.1", true, false);
        assertEquals(Status.TRANSLATED_BY_VIAVERSION, ok.status());
        assertTrue(ok.connectable());
        assertTrue(ok.translated());
    }

    @Test
    void starsiBotPotrebujeObaViaPluginy() {
        Assessment ok = ViaCompat.assess(BOT + 10, "26.2", BOT, "26.1", true, true);
        assertEquals(Status.TRANSLATED_BY_VIABACKWARDS, ok.status());
        assertTrue(ok.connectable());
        assertTrue(ok.translated());
    }

    @Test
    void starsiBotBezViaBackwardsSelze() {
        Assessment jenVia = ViaCompat.assess(BOT + 10, "26.2", BOT, "26.1", true, false);
        assertEquals(Status.MISSING_VIABACKWARDS, jenVia.status());
        assertFalse(jenVia.connectable());
        assertTrue(jenVia.message().contains("ViaBackwards"));
        assertFalse(jenVia.message().contains("ViaVersion a ViaBackwards"),
                "ViaVersion už je – zpráva má chtít jen ViaBackwards");

        Assessment nicNema = ViaCompat.assess(BOT + 10, "26.2", BOT, "26.1", false, false);
        assertEquals(Status.MISSING_VIABACKWARDS, nicNema.status());
        assertTrue(nicNema.message().contains("ViaVersion a ViaBackwards"),
                "bez obou pluginů má zpráva chtít oba");
    }

    @Test
    void samotnyViaBackwardsBezViaVersionNestaci() {
        // ViaBackwards je addon ViaVersion – bez něj nefunguje.
        Assessment a = ViaCompat.assess(BOT + 10, "26.2", BOT, "26.1", false, true);
        assertEquals(Status.MISSING_VIABACKWARDS, a.status());
        assertFalse(a.connectable());
        assertTrue(a.message().contains("ViaVersion"));
    }

    @Test
    void ciziServerJeNeznamyAleNechaSePripojit() {
        Assessment a = ViaCompat.remoteTarget("mc.example.com");
        assertEquals(Status.UNKNOWN_TARGET, a.status());
        assertTrue(a.connectable());
        assertFalse(a.translated());
        assertTrue(a.message().contains("mc.example.com"));
    }

    @Test
    void botVersionOdpovidaKodekuMcProtocolLib() {
        // Sanita: verze zapečená v MCProtocolLib je čitelná a neprázdná.
        assertFalse(ViaCompat.botVersion().isBlank());
        assertTrue(ViaCompat.botProtocol() > 0);
    }

    @Test
    void supportContractZminiBaselineIObaViaSmery() {
        // Kontrakt podporovaných verzí – jediný zdroj věty pro log/diagnostiku.
        String contract = ViaCompat.supportContract();
        assertTrue(contract.contains(ViaCompat.botVersion()),
                "kontrakt má jmenovat nativní baseline (verzi botů)");
        assertTrue(contract.contains("ViaVersion"), "kontrakt má zmínit ViaVersion");
        assertTrue(contract.contains("ViaBackwards"),
                "kontrakt má zmínit ViaBackwards pro novější servery");
    }
}
