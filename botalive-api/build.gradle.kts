/*
 * botalive-api – veřejné API pluginu BotAlive.
 *
 * Modul obsahuje pouze rozhraní, eventy a datové typy. Nesmí záviset na žádné
 * implementační knihovně (MCProtocolLib, Hikari, ...), aby na něm mohly bezpečně
 * stavět cizí pluginy, aniž by si zatáhly naše interní závislosti.
 */
plugins {
    `java-library`
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.slf4j.api)
}
