package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Rozhodovací jádro STRUKTURÁLNÍHO růstu domu – čistá funkce (sourozenec
 * {@link HomeUpgrade}). Vlastní tick smyčku (dojít, položit, vytěžit) vede
 * {@code MaintainHomeGoal}; tady se rozhoduje jen <b>co přidat</b> a <b>co
 * odklidit</b>.
 *
 * <p><b>Aditivní napřed, demolice vnitřku naposled</b> (bezpečnostní DNA rámce
 * „stavba jako proces"): nejdřív se dostaví buňky <b>většího pláště</b>, které
 * ještě nestojí (nové vyšší/širší zdi a střecha se postaví, zatímco starý nižší
 * krov pořád drží – dům je celou dobu zakrytý). Teprve až <b>celý nový plášť
 * stojí</b> ({@link Plan#shellComplete()}), vrátí funkce k odklizení zbylé
 * <b>staré strukturální bloky</b>, které se staly vnitřními (starý krov, staré
 * zdi) – přesně staré buňky, které nejsou v novém plášti, takže se nikdy
 * neodklidí vybavení (postel, truhla) ani nový plášť. Odklízení vnitřku obálku
 * nikdy neprolomí.</p>
 */
public final class StructureGrowth {

    private StructureGrowth() {
    }

    /**
     * Přídavky (buňky nového pláště, které ještě nestojí) a demolice (staré
     * strukturální bloky uvnitř nového pláště k odklizení – prázdné, dokud
     * plášť nestojí).
     */
    public record Plan(List<PlacementCell> additions, List<BlockPos> demolitions) {

        /** @return stojí celý nový plášť (nezbývá co přidat)? */
        public boolean shellComplete() {
            return additions.isEmpty();
        }

        /** @return je růst hotový (plášť stojí a starý vnitřek je odklizený)? */
        public boolean done() {
            return additions.isEmpty() && demolitions.isEmpty();
        }
    }

    /**
     * @param oldGeometry starý (menší) blueprint na svém původním originu
     * @param oldOrigin   roh starého půdorysu
     * @param newGeometry nový (větší) blueprint na přepočítaném center-fixed originu
     * @param newOrigin   roh nového půdorysu
     * @param facing      orientace (společná – dům je čtvercový, střed se nehýbe)
     * @param solid       je pozice ve světě pevná?
     * @param addLimit    strop přídavků na seanci
     * @param demoLimit   strop demolic na seanci
     * @return plán růstu (přídavky, případně demolice až po dostavbě pláště)
     */
    public static Plan plan(Blueprint oldGeometry, BlockPos oldOrigin,
                            Blueprint newGeometry, BlockPos newOrigin, Cardinal facing,
                            Predicate<BlockPos> solid, int addLimit, int demoLimit) {
        List<PlacementCell> newCells = newGeometry.cells(newOrigin, facing);
        Set<Long> newCellSet = new HashSet<>();
        for (PlacementCell c : newCells) {
            newCellSet.add(c.pos().asLong());
        }

        // Přídavky: buňky nového pláště, které ještě nestojí (bottom-up pořadí
        // z cells() drží oporu při pokládce – jako běžná oprava).
        List<PlacementCell> additions = new ArrayList<>();
        for (PlacementCell c : newCells) {
            if (additions.size() >= addLimit) {
                break;
            }
            if (!solid.test(c.pos())) {
                additions.add(c);
            }
        }

        // Demolice až po dostavbě pláště (nepromokavost): staré strukturální
        // bloky, které se staly vnitřními (nejsou v novém plášti). Přesně staré
        // buňky – vybavení (postel, truhla, pochodeň) není buňka, takže zůstane.
        List<BlockPos> demolitions = new ArrayList<>();
        if (additions.isEmpty()) {
            for (PlacementCell oldCell : oldGeometry.cells(oldOrigin, facing)) {
                if (demolitions.size() >= demoLimit) {
                    break;
                }
                BlockPos pos = oldCell.pos();
                if (!newCellSet.contains(pos.asLong()) && solid.test(pos)) {
                    demolitions.add(pos);
                }
            }
        }
        return new Plan(additions, demolitions);
    }
}
