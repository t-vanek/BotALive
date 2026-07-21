package dev.botalive.core.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje energii/únavu: svěžest v klidu, úbytek bděním a námahou, obnovu
 * spánkem, ořez 0–1, modulaci priorit při únavě a reset po respawnu.
 */
class VitalsTest {

    @Test
    void freshBotIsRestedAndDoesNotModulate() {
        Vitals vitals = new Vitals();
        assertEquals(1.0, vitals.energy(), 1e-9);
        assertFalse(vitals.tired());
        assertEquals(1.0, vitals.modulate("sleep"), 1e-9);
        assertEquals(1.0, vitals.modulate("nether"), 1e-9);
    }

    @Test
    void activityDrainsUntilTired() {
        Vitals vitals = new Vitals();
        for (int i = 0; i < 600 && !vitals.tired(); i++) {
            vitals.tick(false, 1.0);
        }
        assertTrue(vitals.tired(), "den plné námahy bota unaví");
        assertTrue(vitals.energy() < 0.35);
    }

    @Test
    void sleepRecoversEnergy() {
        Vitals vitals = new Vitals();
        for (int i = 0; i < 600; i++) {
            vitals.tick(false, 1.0);
        }
        double tired = vitals.energy();
        for (int i = 0; i < 200; i++) {
            vitals.tick(true, 0.0);
        }
        assertTrue(vitals.energy() > tired, "spánek energii obnoví");
    }

    @Test
    void energyStaysWithinBounds() {
        Vitals vitals = new Vitals();
        for (int i = 0; i < 5000; i++) {
            vitals.tick(false, 1.0);
        }
        assertEquals(0.0, vitals.energy(), 1e-9); // nikdy pod 0
        for (int i = 0; i < 5000; i++) {
            vitals.tick(true, 0.0);
        }
        assertEquals(1.0, vitals.energy(), 1e-9); // nikdy nad 1
    }

    @Test
    void tirednessShiftsPrioritiesTowardRest() {
        Vitals vitals = new Vitals();
        for (int i = 0; i < 600; i++) {
            vitals.tick(false, 1.0);
        }
        assertTrue(vitals.tired());
        assertTrue(vitals.modulate("sleep") > 1.2, "únava táhne ke spánku");
        assertTrue(vitals.modulate("nether") < 1.0, "únava odkládá výpravy");
        assertEquals(1.0, vitals.modulate("some-neutral-goal"), 1e-9);
    }

    @Test
    void refreshRestores() {
        Vitals vitals = new Vitals();
        for (int i = 0; i < 600; i++) {
            vitals.tick(false, 1.0);
        }
        assertTrue(vitals.tired());
        vitals.refresh();
        assertEquals(1.0, vitals.energy(), 1e-9);
        assertFalse(vitals.tired());
    }
}
