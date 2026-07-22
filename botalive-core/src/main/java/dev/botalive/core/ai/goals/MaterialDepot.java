package dev.botalive.core.ai.goals;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.util.BlockPos;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Sklad materiálu sídla – sdílená zásobovací truhla pro stavební bloky.
 *
 * <p>Vícebotí stavba velkých staveb (radnice, kostel) potřebuje dělbu
 * práce: sběrač nosí kámen do společné truhly, stavitel si z ní odebírá.
 * Tou truhlou je <b>dvojtruhla skladu</b> ({@code WAREHOUSE}) – sídlo si ho
 * staví jako „společný sklad na materiál", takže stavební bloky do něj
 * patří z definice. Sklad používá blueprint sýpky, tedy jeho truhla sedí
 * na {@link HouseBlueprint#bedSpot} (parita s {@code GranaryGoal}). Dvojtruhla
 * sdílí inventář, takže stačí adresovat jednu půlku.</p>
 *
 * <p>Zásobovací řetězec je <b>bonus, ne podmínka</b>: dokud sídlo nemá
 * postavený sklad, metoda vrací prázdno a stavitel se zásobuje sám (jako
 * dosud). Sklad je poslední kus zázemí (po studni/sýpce/tržišti/dílnách),
 * takže řetězec naskočí právě u velkých prestižních staveb, které dělbu
 * práce nejvíc potřebují.</p>
 */
final class MaterialDepot {

    private MaterialDepot() {
    }

    /**
     * Truhla skladu materiálu v sídle bota – kam sběrač ukládá stavební
     * bloky a odkud si je stavitel bere. Čistá geometrie z evidence sídla
     * (bez živého světa); volající si přidá kontrolu vzdálenosti a načtení
     * chunku.
     *
     * @param settlements služba sídel
     * @param botId       člen sídla (sběrač nebo stavitel)
     * @return pozice zásobovací truhly, nebo prázdné (bot není v sídle /
     *         sídlo ještě nemá postavený sklad)
     */
    static Optional<BlockPos> chest(SettlementService settlements, UUID botId) {
        OptionalLong id = settlements.settlementIdOf(botId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return settlements.doneProject(id.getAsLong(), SettlementService.ProjectKind.WAREHOUSE)
                .map(p -> HouseBlueprint.bedSpot(p.origin(), p.facing()));
    }
}
