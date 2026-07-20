package dev.botalive.core.build.plan;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Konkrétní materiály stavby podle {@link PaletteRole rolí}. Každá role nese
 * seřazený seznam přijatelných materiálů: první je zamýšlený (equip a rozpis),
 * ostatní projdou při opravě/resume ({@link AcceptancePolicy}). Skládá ji
 * {@link PaletteResolver} z místního dřeva a seedu.
 *
 * <p>{@link #GENERIC} je prázdná paleta – {@code BuildSession} pak klade
 * zaměnitelný stavební blok jako dnes (legacy stavby).</p>
 *
 * @param byRole role → materiály (první = zamýšlený)
 */
public record Palette(Map<PaletteRole, List<Material>> byRole) {

    /** Prázdná paleta: vše přes {@code equipBuildingBlock} (legacy). */
    public static final Palette GENERIC = new Palette(Map.of());

    public Palette {
        byRole = Map.copyOf(byRole);
    }

    /**
     * @param role role bloku
     * @return zamýšlený materiál role, nebo prázdné (→ zaměnitelný blok)
     */
    public Optional<Material> intended(PaletteRole role) {
        List<Material> options = byRole.get(role);
        return options == null || options.isEmpty()
                ? Optional.empty() : Optional.of(options.get(0));
    }

    /**
     * @param role role bloku
     * @return všechny přijatelné materiály role (prázdné = jakýkoli stavební blok)
     */
    public List<Material> accepted(PaletteRole role) {
        return byRole.getOrDefault(role, List.of());
    }
}
