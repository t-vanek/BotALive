package dev.botalive.core.entity;

import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy parsování entity metadat – stav krunýře shulkera (peek).
 */
class TrackedEntityMetadataTest {

    private static TrackedEntity entity(EntityType type) {
        return new TrackedEntity(1, UUID.randomUUID(), type, new Vec3(0, 64, 0));
    }

    @Test
    void bezMetadatSeShulkerBereJakoOtevreny() {
        // Stará data (nebo výpadek paketu) nesmí boty zablokovat – bez
        // známého peeku se bojuje postaru.
        assertFalse(entity(EntityType.SHULKER).shulkerClosed());
    }

    @Test
    void peekNulaZnamenaZavrenyKrunyr() {
        TrackedEntity shulker = entity(EntityType.SHULKER);
        shulker.applyMetadata(17, (byte) 0);
        assertTrue(shulker.shulkerClosed(), "peek 0 = zavřený krunýř");

        shulker.applyMetadata(17, (byte) 30);
        assertFalse(shulker.shulkerClosed(), "peek 30 = vykouklý (zranitelný)");

        shulker.applyMetadata(17, (byte) 100);
        assertFalse(shulker.shulkerClosed(), "peek 100 = plně otevřený");

        shulker.applyMetadata(17, (byte) 0);
        assertTrue(shulker.shulkerClosed(), "zavření se sleduje průběžně");
    }

    @Test
    void cizaMetadataSeIgnoruji() {
        TrackedEntity shulker = entity(EntityType.SHULKER);
        shulker.applyMetadata(16, (byte) 0);  // strana přichycení
        shulker.applyMetadata(18, (byte) 0);  // barva
        shulker.applyMetadata(17, "nesmysl"); // špatný typ hodnoty
        assertFalse(shulker.shulkerClosed(), "jen byte na indexu 17 je peek");
    }

    @Test
    void jineEntityKrunyrNemaji() {
        TrackedEntity zombie = entity(EntityType.ZOMBIE);
        zombie.applyMetadata(17, (byte) 0);
        assertFalse(zombie.shulkerClosed(), "zombie žádný krunýř nemá");
    }
}
