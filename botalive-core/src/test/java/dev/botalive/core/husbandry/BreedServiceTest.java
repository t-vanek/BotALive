package dev.botalive.core.husbandry;

import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Čistá logika chovu: mapa krmiva na druh a rozpoznání hospodářského zvířete.
 * Server-side {@code check} potřebuje běžící server (testuje se in-world).
 */
class BreedServiceTest {

    @Test
    void hospodarskaZvirataMajiSveKrmivo() {
        assertTrue(BreedService.breedingFood(EntityType.COW).test(Material.WHEAT));
        assertTrue(BreedService.breedingFood(EntityType.SHEEP).test(Material.WHEAT));
        assertTrue(BreedService.breedingFood(EntityType.PIG).test(Material.CARROT));
        assertTrue(BreedService.breedingFood(EntityType.CHICKEN).test(Material.WHEAT_SEEDS));
        assertTrue(BreedService.breedingFood(EntityType.RABBIT).test(Material.CARROT));
    }

    @Test
    void spatneKrmivoNepasuje() {
        // Kráva se nerozmnožuje na mrkev ani na semínka.
        assertFalse(BreedService.breedingFood(EntityType.COW).test(Material.CARROT));
        assertFalse(BreedService.breedingFood(EntityType.COW).test(Material.WHEAT_SEEDS));
        // Prase se nerozmnožuje na pšenici.
        assertFalse(BreedService.breedingFood(EntityType.PIG).test(Material.WHEAT));
    }

    @Test
    void nehospodarskaZviretaSeNechovaji() {
        assertNull(BreedService.breedingFood(EntityType.WOLF));
        assertNull(BreedService.breedingFood(EntityType.ZOMBIE));
        assertFalse(BreedService.isLivestock(EntityType.WOLF));
        assertFalse(BreedService.isLivestock(EntityType.VILLAGER));
    }

    @Test
    void isLivestockOdpovidaKrmivu() {
        for (EntityType type : new EntityType[]{EntityType.COW, EntityType.MOOSHROOM,
                EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN,
                EntityType.GOAT, EntityType.RABBIT}) {
            assertTrue(BreedService.isLivestock(type), type.name());
            assertNotNull(BreedService.breedingFood(type), type.name());
        }
    }
}
