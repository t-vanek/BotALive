package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kovářský stůl – povýšení diamantové výbavy na netheritovou (vanilla:
 * šablona z bastionu + diamantový kus + netheritový ingot).
 *
 * <p>Dvě implementace stejného kontraktu: server-side
 * {@code SmithingService} (autoritativní úprava inventáře na vlákně regionu,
 * viz precedent CraftingService/AnvilService) a paketová
 * {@code PacketSmithingStation} (kliky v okně kovářského stolu pro boty na
 * cizích serverech).</p>
 */
public interface SmithingStation {

    /** Pořadí povyšování: nejužitečnější kusy dřív. */
    List<String> UPGRADE_ORDER = List.of(
            "DIAMOND_PICKAXE", "DIAMOND_SWORD", "DIAMOND_CHESTPLATE",
            "DIAMOND_LEGGINGS", "DIAMOND_HELMET", "DIAMOND_BOOTS",
            "DIAMOND_AXE", "DIAMOND_SHOVEL", "DIAMOND_HOE");

    /**
     * Výsledek povýšení.
     *
     * @param upgraded povýšený diamantový kus ({@code null} = nic se nestalo)
     * @param result   výsledný netheritový materiál
     */
    record UpgradeReport(Material upgraded, Material result) {

        /** Nic se nepovýšilo. */
        public static final UpgradeReport NONE = new UpgradeReport(null, null);

        /** @return {@code true} pokud povýšení proběhlo */
        public boolean succeeded() {
            return result != null;
        }
    }

    /**
     * Povýší jeden diamantový kus (dle {@link #UPGRADE_ORDER}) na netherit.
     * Bot musí mít netheritový ingot a kovářskou šablonu a stát u stolu.
     *
     * @param ctx       kontext bota
     * @param worldName svět kovářského stolu
     * @param pos       pozice kovářského stolu
     * @return future s výsledkem (nikdy nedokončená výjimkou)
     */
    CompletableFuture<UpgradeReport> upgrade(BotContext ctx, String worldName, BlockPos pos);

    /**
     * @param material materiál
     * @return netheritová podoba diamantového kusu, nebo {@code null}
     */
    static Material netheriteOf(Material material) {
        String name = material == null ? "" : material.name();
        if (!name.startsWith("DIAMOND_") || !UPGRADE_ORDER.contains(name)) {
            return null;
        }
        try {
            return Material.valueOf("NETHERITE_" + name.substring("DIAMOND_".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
