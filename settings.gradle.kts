/*
 * BotAlive – kořenový settings skript.
 *
 * Projekt je rozdělen na dva Gradle moduly:
 *  - botalive-api  : veřejné, stabilní API (rozhraní, eventy, datové typy) bez implementačních závislostí
 *  - botalive-core : implementace pluginu (síť, AI, paměť, persistence, příkazy, ...)
 *
 * Logické subsystémy (AI, Network, Memory, Personality, Pathfinding, Combat, Inventory,
 * Economy, Tasks, Chat, Persistence, Commands, Config, Scheduler) jsou uvnitř core modulu
 * odděleny do samostatných balíčků s jasnými hranicemi – viz docs/ARCHITECTURE.md,
 * kde je vysvětleno, proč nejsou samostatnými Gradle moduly.
 */
rootProject.name = "BotAlive"

include("botalive-api")
include("botalive-core")
