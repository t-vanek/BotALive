package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.crafting.BrewPlanner;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;

/**
 * Stanice varných stojanů – kontrakt mezi {@code BrewGoal} a implementací
 * (server-side {@code BrewingService}).
 *
 * <p>Vaření trvá ~20 s vanilla mechanikou, proto je rozhraní dvoufázové jako
 * u pece: {@link #load} naloží lahve, přísadu a palivo, {@link #collect}
 * vyzvedne dovařené lahve (0, dokud vaření běží). Čekání vlastní cíl.</p>
 */
public interface BrewingStation {

    /**
     * Výsledek naložení stojanu.
     *
     * @param bottles          naložené lahve (0–3)
     * @param ingredientLoaded přísada je ve slotu
     */
    record LoadReport(int bottles, boolean ingredientLoaded) {

        /** Prázdný výsledek. */
        public static final LoadReport EMPTY = new LoadReport(0, false);
    }

    /**
     * Naloží do stojanu lahve odpovídající základu vsázky, přísadu a – je-li
     * palivoměr prázdný – blaze prach.
     *
     * @param ctx        kontext bota (stojí u otevřeného stojanu)
     * @param worldName  svět stojanu
     * @param pos        pozice stojanu
     * @param ingredient přísada vsázky
     * @param base       jaké lahve se nakládají
     * @return future s výsledkem naložení
     */
    CompletableFuture<LoadReport> load(BotContext ctx, String worldName, BlockPos pos,
                                       Material ingredient, BrewPlanner.Base base);

    /**
     * Vyzvedne dovařené lahve. Dokud vaření běží (nebo přísada čeká ve
     * slotu), vrací 0 – s výjimkou {@code force}, které vybere stojan celý
     * (timeout/nevalidní kombinace: lahve i přísada se vrací botovi).
     *
     * @param ctx       kontext bota (stojí u otevřeného stojanu)
     * @param worldName svět stojanu
     * @param pos       pozice stojanu
     * @param force     vybrat i rozvařený/nevalidní obsah
     * @return future s počtem vyzvednutých lahví
     */
    CompletableFuture<Integer> collect(BotContext ctx, String worldName, BlockPos pos,
                                       boolean force);
}
