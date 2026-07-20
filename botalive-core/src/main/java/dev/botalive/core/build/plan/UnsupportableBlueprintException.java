package dev.botalive.core.build.plan;

import java.util.List;

/**
 * Blueprint nelze postavit tak, aby každý blok měl při pokládce oporu jen
 * z podlahy a už položených bloků (visící blok bez souseda). Signalizuje
 * chybu návrhu stavby, ne běhový stav – generátory a šablony fáze V2b/V2c
 * musí projít {@link BuildPlanner#order}, jinak spadne test (u šablon už
 * při načtení).
 */
public final class UnsupportableBlueprintException extends IllegalStateException {

    private final transient List<PlacementCell> unsupported;

    /**
     * @param unsupported bloky, které zůstaly bez opory
     */
    public UnsupportableBlueprintException(List<PlacementCell> unsupported) {
        super("blueprint není postavitelný – " + unsupported.size()
                + " bloků nemá oporu (první: "
                + (unsupported.isEmpty() ? "-" : unsupported.get(0).pos()) + ")");
        this.unsupported = List.copyOf(unsupported);
    }

    /** @return bloky bez opory (diagnostika) */
    public List<PlacementCell> unsupported() {
        return unsupported;
    }
}
