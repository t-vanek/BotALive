package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Proveditelný rozvrh stavby, který {@code BuildSession} odbaví po krocích:
 * nejdřív úprava terénu ({@link #mine výkopy} a {@link #fill zásypy podlahy}),
 * pak {@link #placements pokládka} v pořadí se zaručenou oporou, nakonec
 * {@link #furnishing vybavení}. Vše ze {@link #stand jednoho stanoviště}
 * (fáze V2a; vícero stanovišť přijde s většími stavbami ve V2b).
 *
 * @param mine        pozice k vytěžení (překážky v objemu stavby)
 * @param fill        pozice podlahy k zasypání (zaměnitelný blok)
 * @param placements  bloky stavby v pořadí pokládky (každý má při pokládce oporu)
 * @param stand       stanoviště stavitele
 * @param furnishing  kroky vybavení (bonus, přeskočí se, co chybí)
 */
public record BuildSchedule(
        List<BlockPos> mine,
        List<BlockPos> fill,
        List<PlacementCell> placements,
        BlockPos stand,
        List<FurnishCell> furnishing) {
}
