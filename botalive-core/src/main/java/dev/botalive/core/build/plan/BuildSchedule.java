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
 * <p>Vyvýšená stanoviště jednotek stavitel dosáhne pilířováním (skok + blok
 * pod nohy, {@code PillarUpTask}); dočasný pilíř zaznamenává {@link #scaffold}
 * a {@code BuildSession} ho po dostavbě odklidí (vytěží), aby zůstala jen
 * stavba. Nízká stavba na dosah ze země má {@code scaffold} prázdné.</p>
 *
 * @param mine         pozice k vytěžení (překážky v objemu stavby)
 * @param fill         pozice podlahy k zasypání (zaměnitelný blok)
 * @param units        dávky pokládky po stanovištích (pořadí drží oporu)
 * @param furnishStand odkud se osazuje vybavení (vnitřek stavby)
 * @param standExact   musí stavitel dokročit přesně na stanoviště jednotky?
 * @param furnishing   kroky vybavení (bonus, přeskočí se, co chybí)
 * @param scaffold     dočasné bloky lešení (pilíře pod vyvýšenými stanovišti)
 *                     k úklidu po dostavbě; prázdné u staveb stavěných ze země
 */
public record BuildSchedule(
        List<BlockPos> mine,
        List<BlockPos> fill,
        List<WorkUnit> units,
        BlockPos furnishStand,
        boolean standExact,
        List<FurnishCell> furnishing,
        List<BlockPos> scaffold) {
}
