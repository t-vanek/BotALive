package dev.botalive.core.build.plan;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometrie účelné dílny: půdorys domku, uvnitř pracovní stanice ve vybavení.
 * Stanice se nesmí krýt navzájem, s pochodní ani se stanovištěm stavitele –
 * jinak by je nešlo osadit.
 */
class WorkshopBlueprintTest {

    private static final BlockPos ORIGIN = new BlockPos(10, 64, 20);

    @Test
    void dilnaMaObeStaniceVeVybaveni() {
        Blueprint workshop = Blueprints.workshop(Material.FURNACE, Material.SMITHING_TABLE);
        List<FurnishCell> furnish = workshop.furnishing(ORIGIN, Cardinal.NORTH);

        List<FurnishCell> stations = furnish.stream()
                .filter(f -> f.kind() == FurnishKind.STATION).toList();
        assertEquals(2, stations.size(), "hlavní i vedlejší stanice");
        Set<Material> materials = stations.stream()
                .map(FurnishCell::material).collect(Collectors.toSet());
        assertEquals(Set.of(Material.FURNACE, Material.SMITHING_TABLE), materials);

        // Dveře jsou (kvůli vstupu), sdílí půdorys domku (stejný počet bloků).
        assertTrue(workshop.doorCell(ORIGIN, Cardinal.NORTH).isPresent());
        assertEquals(HouseBlueprint.blocksNeeded(), workshop.blocksNeeded());
        assertTrue(workshop.standExact(), "dílna se osazuje přesně z vnitřku");
    }

    @Test
    void dilnaBezVedlejsiStaniceMaJednu() {
        Blueprint workshop = Blueprints.workshop(Material.SMOKER, null);
        long stations = workshop.furnishing(ORIGIN, Cardinal.NORTH).stream()
                .filter(f -> f.kind() == FurnishKind.STATION).count();
        assertEquals(1, stations);
    }

    @Test
    void staniceSeVAdneOrientaciNekryji() {
        Blueprint workshop = Blueprints.workshop(Material.CRAFTING_TABLE, Material.STONECUTTER);
        for (Cardinal facing : Cardinal.values()) {
            List<BlockPos> inner = workshop.furnishing(ORIGIN, facing).stream()
                    .filter(f -> f.kind() == FurnishKind.STATION || f.kind() == FurnishKind.TORCH)
                    .map(FurnishCell::pos).toList();
            assertEquals(inner.size(), Set.copyOf(inner).size(),
                    "stanice a pochodeň se nesmí krýt (" + facing + ")");
            assertFalse(inner.contains(workshop.standPoint(ORIGIN, facing)),
                    "stanice nesmí být na stanovišti stavitele (" + facing + ")");
        }
    }
}
