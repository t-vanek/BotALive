package dev.botalive.core.build.plan;

/**
 * Druh vybavení stavby – co se osadí po dostavění hrubé stavby. Vybavení je
 * bonus, ne podmínka: co bot nemá v batohu, {@code BuildSession} přeskočí
 * (dnešní chování obou stavebních cílů). Konkrétní predikát itemu drží
 * vykonavatel, aby predikáty (např. „jakékoli {@code _DOOR}") žily na jednom
 * místě.
 */
public enum FurnishKind {

    /** Dveře do dveřního otvoru. */
    DOOR,
    /** Pochodeň (světlo). */
    TORCH,
    /** Postel. */
    BED,
    /** Truhla (sýpka – dvojtruhla dvěma buňkami). */
    CHEST
}
