package dev.botalive.core.ai.goals;

import dev.botalive.core.build.plan.Blueprints;
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
 * patří z definice. Truhla se dopočítá z geometrie skladu podle jeho
 * persistované velikosti ({@link Blueprints#storageChest} nad blueprintem
 * sýpky, parita se {@code StashGoal.warehouseChest}) – legacy bedSpot
 * rotoval v rámci 4×4 a u širšího městského skladu mířil do vzduchu
 * uvnitř sálu (odběr byl pak tiše mrtvý). Dvojtruhla sdílí inventář,
 * takže stačí adresovat jednu půlku.</p>
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
        // Guard i pro start() cílů: vynucený cíl obchází utility a bez služby
        // sídel by NPE ze startu roztočila error-smyčku mozku.
        if (settlements == null) {
            return Optional.empty();
        }
        OptionalLong id = settlements.settlementIdOf(botId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return settlements.doneProject(id.getAsLong(), SettlementService.ProjectKind.WAREHOUSE)
                .flatMap(p -> Blueprints.storageChest(
                        Blueprints.granary(p.size()), p.origin(), p.facing()));
    }
}
