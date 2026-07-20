package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Proveditelný rozvrh stavby, který {@code BuildSession} odbaví po krocích:
 * nejdřív úprava terénu ({@link #mine výkopy} a {@link #fill zásypy podlahy}),
 * pak {@link #units pokládka} po jednotkách (každá z jednoho stanoviště, mezi
 * nimi se přechází – velké stavby na dosah), nakonec {@link #furnishing
 * vybavení} z {@link #furnishStand vnitřního stanoviště}.
 *
 * @param mine         pozice k vytěžení (překážky v objemu stavby)
 * @param fill         pozice podlahy k zasypání (zaměnitelný blok)
 * @param units        dávky pokládky po stanovištích (pořadí drží oporu)
 * @param furnishStand odkud se osazuje vybavení (vnitřek stavby)
 * @param standExact   musí stavitel dokročit přesně na stanoviště jednotky?
 * @param furnishing   kroky vybavení (bonus, přeskočí se, co chybí)
 */
public record BuildSchedule(
        List<BlockPos> mine,
        List<BlockPos> fill,
        List<WorkUnit> units,
        BlockPos furnishStand,
        boolean standExact,
        List<FurnishCell> furnishing) {
}
