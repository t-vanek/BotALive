package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paleta, přijatelnost a rozpis materiálu – čisté funkce, deterministické. */
class PaletteModelTest {

    @Test
    void resolverIsDeterministicPerSeed() {
        assertEquals(PaletteResolver.resolve(Material.OAK_LOG, 42),
                PaletteResolver.resolve(Material.OAK_LOG, 42),
                "stejné dřevo i seed → stejná paleta");
    }

    @Test
    void wallFollowsLocalWood() {
        assertEquals(Optional.of(Material.SPRUCE_PLANKS),
                PaletteResolver.resolve(Material.SPRUCE_LOG, 1).intended(PaletteRole.WALL));
        assertEquals(Optional.of(Material.BIRCH_PLANKS),
                PaletteResolver.resolve(Material.BIRCH_PLANKS, 1).intended(PaletteRole.WALL));
        // Neznámé/žádné dřevo → dub.
        assertEquals(Optional.of(Material.OAK_PLANKS),
                PaletteResolver.resolve(null, 1).intended(PaletteRole.WALL));
        assertEquals(Optional.of(Material.OAK_PLANKS),
                PaletteResolver.resolve(Material.DIAMOND, 1).intended(PaletteRole.WALL));
    }

    @Test
    void windowIsGlass() {
        assertEquals(Optional.of(Material.GLASS),
                PaletteResolver.resolve(Material.OAK_LOG, 7).intended(PaletteRole.WINDOW));
    }

    @Test
    void acceptanceMatchesPaletteAndFallsBackToBuildingBlock() {
        Palette palette = PaletteResolver.resolve(Material.SPRUCE_LOG, 3);
        assertTrue(AcceptancePolicy.accepts(PaletteRole.WALL, Material.SPRUCE_PLANKS, palette));
        assertFalse(AcceptancePolicy.accepts(PaletteRole.WALL, Material.DIRT, palette),
                "cizí materiál ve zdi neprojde");
        assertTrue(AcceptancePolicy.accepts(PaletteRole.WINDOW, Material.GLASS_PANE, palette),
                "sklo i tabule projde jako okno");
        // GENERIC / prázdná paleta – jakýkoli stavební blok.
        assertTrue(AcceptancePolicy.accepts(PaletteRole.GENERIC, Material.COBBLESTONE,
                Palette.GENERIC));
        assertFalse(AcceptancePolicy.accepts(PaletteRole.GENERIC, Material.DIAMOND_BLOCK,
                Palette.GENERIC), "nestavební blok neprojde");
        assertFalse(AcceptancePolicy.accepts(PaletteRole.WALL, Material.AIR, palette),
                "díra (vzduch) není přijatelná");
    }

    @Test
    void billOfMaterialsSumsIntendedByRole() {
        Palette palette = PaletteResolver.resolve(Material.OAK_LOG, 1);
        BlockPos o = new BlockPos(0, 64, 0);
        BuildPlan plan = new BuildPlan(
                List.of(
                        new PlacementCell(o, BlockSpec.of(PaletteRole.WALL)),
                        new PlacementCell(o.offset(1, 0, 0), BlockSpec.of(PaletteRole.WALL)),
                        new PlacementCell(o.offset(2, 0, 0), BlockSpec.of(PaletteRole.WINDOW))),
                List.of(), List.of(), List.of(), o, Optional.empty(), false);
        Map<Material, Integer> bom = BillOfMaterials.of(plan, palette);
        assertEquals(2, bom.get(Material.OAK_PLANKS), "dvě prkna do zdí");
        assertEquals(1, bom.get(Material.GLASS), "jedno sklo do okna");
    }

    @Test
    void genericPaletteHasNoBillOfMaterials() {
        BlockPos o = new BlockPos(0, 64, 0);
        BuildPlan plan = new BuildPlan(
                List.of(new PlacementCell(o, BlockSpec.GENERIC)),
                List.of(), List.of(), List.of(), o, Optional.empty(), false);
        assertTrue(BillOfMaterials.of(plan, Palette.GENERIC).isEmpty(),
                "legacy staví oportunisticky – žádný rozpis");
    }
}
