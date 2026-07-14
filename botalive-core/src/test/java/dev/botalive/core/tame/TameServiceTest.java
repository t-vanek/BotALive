package dev.botalive.core.tame;

import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy klasifikace ochočování (itemy a druhy).
 */
class TameServiceTest {

    @Test
    void vlkSeOchocujeKosti() {
        var item = TameService.tamingItem(EntityType.WOLF);
        assertTrue(item.test(Material.BONE));
        assertFalse(item.test(Material.COD));
    }

    @Test
    void kockaSeOchocujeRybou() {
        var item = TameService.tamingItem(EntityType.CAT);
        assertTrue(item.test(Material.COD));
        assertTrue(item.test(Material.SALMON));
        assertFalse(item.test(Material.BONE));
    }

    @Test
    void papousekSeOchocujeSeminky() {
        var item = TameService.tamingItem(EntityType.PARROT);
        assertTrue(item.test(Material.WHEAT_SEEDS));
        assertTrue(item.test(Material.BEETROOT_SEEDS));
        assertFalse(item.test(Material.WHEAT));
    }

    @Test
    void koneSeOchocujiNasedanim() {
        for (EntityType type : new EntityType[]{EntityType.HORSE, EntityType.DONKEY,
                EntityType.MULE, EntityType.LLAMA}) {
            assertNull(TameService.tamingItem(type), type + " nemá taming item");
            assertTrue(TameService.tamedByMounting(type), type + " se ochočuje nasedáním");
        }
        assertFalse(TameService.tamedByMounting(EntityType.WOLF));
    }

    @Test
    void trackedEntityZnaOchocitelneDruhy() {
        for (EntityType type : new EntityType[]{EntityType.WOLF, EntityType.CAT,
                EntityType.PARROT, EntityType.HORSE, EntityType.DONKEY,
                EntityType.MULE, EntityType.LLAMA}) {
            assertTrue(entity(type).isTameableType(), type + " je ochočitelný");
        }
        assertFalse(entity(EntityType.COW).isTameableType());
        assertFalse(entity(EntityType.ZOMBIE).isTameableType());
        assertFalse(entity(EntityType.SKELETON_HORSE).isTameableType(),
                "kostlivčí kůň se neochočuje");
    }

    private static TrackedEntity entity(EntityType type) {
        return new TrackedEntity(1, UUID.randomUUID(), type, Vec3.ZERO);
    }
}
