# Stavební engine v2 – implementační plán

Prováděcí plán k návrhu v `COMPLEX_BUILDS.md`. Detailně rozpracovaná je
fáze **V2a** (jádro s paritou chování), fáze V2b–V2d jsou rozepsané do
konkrétních pracovních položek s known-facts z kódu. Čísla řádků platí
k `3604c6f`.

> **Stav: V2a hotová** (K1–K6) + **V2b částečně** (palety, generátor,
> zapojení jako opt-in). Package `core/build/plan` (`Blueprint`,
> `BuildPlan`, `BuildPlanner`, `BuildSession`, `Blueprints`) s paritními,
> invariantními a simulačními testy; `CommunalBuildGoal` i `BuildHouseGoal`
> migrované na sdílený engine.
>
> V2b hotovo: `Palette`/`PaletteResolver` (dřevo + seed), `AcceptancePolicy`,
> `BillOfMaterials`; `BuildSession` klade podle role (zeď prkna, okno sklo)
> s náhradou; `HouseGenerator` (okna, valbová střecha); `HouseDesigner`;
> **multi-standpoint** – `BuildPlanner` rozdělí velký dům na `WorkUnit`
> dávky po stanovištích (vnitřní podlaha, dosah s rezervou), `BuildSession`
> mezi nimi přechází; **design-aware údržba** (`MaintainHomeGoal` je
> blueprint-driven, opraví legacy 4×4 i generovaný dům proti jeho plánu).
> Zapojeno do `BuildHouseGoal`, **`build.complex` výchozí `true`, `width`
> výchozí 7** (uživatelské minimum, přes multi-standpoint). Celá sada
> 714 testů zelená.
>
> V2b navíc: **velikost domu podle stupně sídla a povahy**
> (`HouseDesigner.widthFor` – osada 5×5, vesnice/město až ke stropu
> `build.width`, líný staví malý; gate i design podle skutečné velikosti)
> a **variace tvaru střechy** ze seedu (plná jehla / valba s plochým
> vrcholem).
>
> **V2c zahájeno**: **tržiště** (`MARKET_STALL`) odemyká tier **MĚSTO** –
> `townInfra = sýpka && tržiště`, `nextProjectKind` ho nabízí po sýpce,
> `CommunalBuildGoal` ho staví (Blueprints.marketStall). Progrese
> studna→sýpka→tržiště→MĚSTO ověřena. Celá sada 717 testů zelená.
>
> **Zbývá**: ukotvení nabídek `SellGoal` k tržišti (fáze D); radnice/
> zvonice/kostel na vícparcelových staveništích; cílené shánění materiálu
> (build wishlist z `BillOfMaterials` – zatím náhrada); orientované bloky
> (schody/dveře – kurzorový `useItemOn`, chce ověření na živém serveru).
> **Doporučeno: smoke test na serveru** (`build.complex: true` je default).

## Inventura dotčeného kódu

| Soubor | Dnes | Změna |
|---|---|---|
| `core/build/HouseBlueprint.java` | statická geometrie 4×4, duck-typed kontrakt | **zůstává** (zdroj legacy geometrie); obalí ho adaptér `Blueprint` |
| `core/build/WellBlueprint.java` | statická geometrie 3×3 | zůstává, obalí ho adaptér |
| `core/build/VillageDecor.java` + `DecorWorker` | plán+vykonavatel cestiček | beze změny (precedent pro `BuildSession`) |
| `core/tasks/PlaceBlockTask.java` | AIM→PLACE→VERIFY, kurzor natvrdo střed stěny | V2a beze změny; V2b `PlacementHints` |
| `core/network/BotActions.java:122` | `useItemOn(pos, face)` – kurzor `(0.5,0.5,0.5)` natvrdo | V2b: overload s kurzorem (paket parametry už má) |
| `core/ai/goals/CommunalBuildGoal.java` | vlastní build smyčka, `if/else` na `ProjectKind` | V2a: fáze GOTO/TERRAFORM/STEP_IN/BUILD/FINISH nahradí `BuildSession` |
| `core/ai/goals/BuildHouseGoal.java` | 844 ř., vlastní smyčka | V2a: TERRAFORM/BUILD/FURNISH nahradí `BuildSession`; FIND_SITE/RELOCATE/GOTO_SITE/`siteCost`/`prepareSite` **beze změny** |
| `core/ai/goals/MaintainHomeGoal.java` | diff proti `HouseBlueprint` | V2a beze změny; V2b diff přes `AcceptancePolicy` + plán z HOME dat |
| `core/ai/goals/BuildShelterGoal.java` | inline nouzový kruh | beze změny (V2d volitelně) |
| `core/settlement/SettlementService.java` | parcely, projekty WELL/GRANARY, `tierOf` s `townInfra=false` (L690) | V2c: `MARKET_STALL`, stavy projektů, vícparcelová rezervace, zapojení `townInfra` |
| `core/inventory/ContainerService.java` (+`PacketChestStation`) | withdraw jen kategorie (`withdrawSupplies`) | V2c: nová metoda „vydej konkrétní materiály" (obě implementace!) |
| `core/persistence/SchemaMigrator.java` | verze v7 | V2b: **v8** `ba_builds`; V2c: **v9** sloupce projektů |
| `core/config/BotAliveConfig.java` + `ConfigLoader` + `config.yml` | 18 sekcí | V2b: nová sekce `build` |
| `core/bootstrap/CompositionRoot.java:258` | registrace cílů | V2a beze změny (cíle si engine berou staticky) |
| `core/chat/PhraseCategory.java` + `lang/cs.yml`,`en.yml` | `SETTLEMENT_WELL_*`, `SETTLEMENT_GRANARY_*` | V2b/c: nové fráze (completeness test hlídá cs) |
| `testutil/FakeBotContext.java`, `FakeWorldView.java` | fake kontext bez navigátoru (`unused()`) | V2a: doplnit `FakeNavigator` (teleport za N ticků) |

## Nové jádro: `core/build/plan`

```java
enum PaletteRole { GENERIC, /* V2b: FOUNDATION, WALL, WALL_FRAME, FLOOR,
                              ROOF, ROOF_EDGE, WINDOW, LIGHT, INTERIOR */ }
enum Orient { NONE /* V2b: N/S/E/W × half – rotuje se s facingem */ }
record BlockSpec(PaletteRole role, Orient orient)
record PlacementCell(BlockPos local, BlockSpec spec)
enum FurnishKind { DOOR, TORCH, BED, CHEST, CHEST_PAIR }   // predikáty itemů centrálně
record FurnishCell(FurnishKind kind, BlockPos local)

interface Blueprint {
    Dims size();                       // W×H×D, obdélník povolen
    List<PlacementCell> cells();       // BEZ pořadí – pořadí počítá planner
    List<BlockPos> clearVolume();
    List<BlockPos> groundColumns();
    List<FurnishCell> furnishing();
    BlockPos standHint();              // parita: dnešní stanoviště
    Optional<BlockPos> doorCell();     // exit (studna nemá)
}

final class BuildPlan {                // instantiace do světa
    static BuildPlan instantiate(Blueprint bp, BlockPos origin, Cardinal facing);
    // rotace zobecňuje HouseBlueprint.local() na obdélník: NORTH identita,
    // EAST/WEST prohazuje W×D, origin zůstává minimální roh; V2b navíc
    // rotuje Orient. Zpřístupňuje světové cells/clearVolume/ground/furnish.
}

record BuildCaps(boolean terraforming, double reach)       // reach 4.0 konzervativně
sealed interface Op { record Mine(BlockPos pos) {} record Place(BlockPos pos, BlockSpec spec) {} }
record WorkUnit(BlockPos stand, boolean standExact, List<Op.Place> steps)
record BuildSchedule(List<Op> terraform, List<WorkUnit> units)

final class BuildPlanner {             // čistá funkce, počítá se JEDNOU a cachuje
    static BuildSchedule schedule(BuildPlan plan, WorldView world, BuildCaps caps);
}

final class BuildSession {             // sdílený vykonavatel (vzor DecorWorker)
    enum State { RUNNING, DONE, BLOCKED_MATERIAL, UNREACHABLE }
    BuildSession(BuildPlan plan, BuildSchedule schedule);
    State tick(BotContext ctx);        // uvnitř: terraform → jednotky → furnish
    PaletteRole missing();             // co chybí při BLOCKED_MATERIAL
    int remaining();                   // pro explain()
    void cancel(BotContext ctx);
}

final class BlueprintRegistry {        // V2a: HOUSE_LEGACY, WELL, GRANARY
    static Blueprint of(SettlementService.ProjectKind kind);
    static Blueprint legacyHouse();
}
```

Sémantika `BuildSession` (parita s dnešními smyčkami):

- TERRAFORM = dnešní `planTerraform`/`planWork`: `Op.Mine` pro pevné
  bloky v `clearVolume` mimo strukturu, `Op.Place` pro díry
  v `groundColumns`; před `Place` `equipBuildingBlock` (GENERIC).
- GOTO_STAND: `navigator().navigateTo(pos, PathGoal.near(stand, 2))`,
  u `standExact` (studna – šachta) dokrok na přesnou buňku (dnešní
  STEP_IN). Selhání navigace → `UNREACHABLE` (politiku – cooldown,
  release claimu – řeší cíl).
- PLACE: pop kroku; `traitsAt(pos).solid()` → skip (resume world-diffem
  jako dnes); equip selže → `BLOCKED_MATERIAL` (cíl rozhodne: dům
  cooldown 2400 + hláška, projekt release + cooldown); jinak
  `PlaceBlockTask`. `stats().addPlaced()` volá jen task (dnes se
  počítá dvakrát – task i cíl; vědomá mikrooprava).
- FURNISH: skip pokud cíl pevný (resume) nebo item chybí (vybavení je
  bonus – dnešní chování obou cílů).
- Plánování jedno-rázově při vzniku session (tick pool je 20 Hz sdílený,
  čistá geometrie do ~2k buněk je v pohodě inline; nic na Bukkit –
  jen `WorldView`/`Snapshot`, funguje i v packet režimu).

## V2a krok za krokem

Každý krok kompiluje a má zelené testy; pořadí minimalizuje riziko.

**K1 – model + legacy adaptéry + paritní testy.**
Package `core/build/plan` (typy výše), `LegacyBlueprints`: adaptér domu
nad `HouseBlueprint` (standHint = `standPoint`), studny nad
`WellBlueprint` (standHint = **střed šachty** – parita s
`CommunalBuildGoal.wellCenter()`, ne se statickým `standPoint`)
a sýpky (dům + `CHEST_PAIR` vybavení dle `chestNeighbor`).
`BlueprintParityTest`: množiny `cells`/`clearVolume`/`groundColumns`
== legacy výstupy pro všechny 4 `Cardinal`.

**K2 – BuildPlanner + invarianty.**
Vrstvení zdola nahoru + topologické setřídění podle opory; jednotky
greedy set-coverem nad kandidátními stanovišti (V2a stačí: legacy
designy MUSÍ vyjít jako jediná jednotka na `standHint` – assert).
`PlanInvariantsTest` po vzoru `PortalBlueprintTest.poradiStavbyMaVzdyOporu`
(nether/PortalBlueprintTest.java:45): každý blok má při pokládce oporu
(zem / dříve položený / existující svět), každý krok v dosahu svého
stanoviště, stanoviště stojatelné v čase svého užití, buňky dveří
nejsou v pokládkách (exit). Terraform na `FakeWorldView` terénech
(díra v podlaze, balvan v objemu) == dnešní fronty.

**K3 – BuildSession + FakeNavigator.**
Session dle sémantiky výše. `testutil.FakeBotContext` dnes na
`navigator()` hází `UnsupportedOperationException` – doplnit
`FakeNavigator` (dojde k cíli za N ticků, umí „nedosažitelno"),
chainable jako `give()`/`update()`.

**K4 – simulační test.**
`BuildSessionSimulationTest` (vzor `ReactiveTaskSimulationTest`):
plná stavba domu/studny/sýpky na `FakeWorldView` → svět obsahuje
všechny buňky; vybavení položené, když boty itemy mají; vyčerpání
bloků uprostřed → `BLOCKED_MATERIAL`, po `give()` dostaví; **resume**:
nová session nad polorozestavěným světem pokládá jen zbytek.

**K5 – migrace `CommunalBuildGoal`.**
Fáze `CLAIM → SESSION → FINISH → DONE`; per-druh `if/else`
(placementsFor/clearVolumeFor/groundColumnsFor/standFor, L144–167)
nahradí `BlueprintRegistry.of(kind)`. Zachovat: utility gates (bloky
+ `BLOCK_RESERVE`, sýpka 2 truhly), dedupe hlášek `lastAnnouncedProject`,
držení claimu přes `stop()`, release při `BLOCKED_MATERIAL`/`UNREACHABLE`
(cooldowny sjednotit na 2400 – vědomá mikrozměna z 1200/2400).

**K6 – migrace `BuildHouseGoal` + regrese.**
FIND_SITE/RELOCATE/GOTO_SITE/`prepareSite`/`siteCost` **nedotčené**
(zabírání parcel, COST_UNKNOWN, DY_ORDER, solo fallback – hotové
lekce). Po `prepareSite` sestavit plán+schedule → fáze `SESSION`
(bot už na standu stojí, GOTO_STAND je no-op) → `DECORATE` →
`finishHouse` beze změny. Smazat `planTerraform/tickTasks/tickBuild/
planFurnish/tickFurnish`; `explain()` z `session.remaining()`.
Regrese: celá stávající sada testů zelená.

**Definition of done V2a:** žádná změna configu, persistence ani
viditelného chování (stejné bloky, stanoviště, gates a hlášky) kromě
dvou dokumentovaných mikrozměn (dvojité `addPlaced`, cooldown 1200→2400);
nové testy K1–K4 zelené; `CommunalBuildGoal` bez per-druh větvení.

## V2b – rozmanité domy (pracovní položky)

1. **Kurzor pro orientované bloky**: `BotActions.useItemOn(pos, face,
   cx, cy, cz, insideBlock)` – `ServerboundUseItemOnPacket` parametry
   už má (L122–127, dnes natvrdo `0.5f`). `PlacementHints(face, cursor,
   requiredYaw)` do `PlaceBlockTask`: AIM čeká, až se `humanizer.yaw()`
   srovná (tolerance ±15° stačí na kvadrant; aim error je σ≈1,2°).
   Ověření orientace `WorldView.blockDataAt` (L37) – v packet režimu
   při `null` datech shovívavě (jen materiál).
2. **Palety**: `PaletteResolver` (biom → druh dřeva, profese, seed),
   `AcceptancePolicy` (jediné místo world-diff pravdy; přírodní kámen
   v kamenné roli projde). Role z `PaletteRole` výše.
3. **BOM + SUPPLY**: `BillOfMaterials.of(plan)` po etapách; publikace
   potřeb přes kontext (vzor `takeShareRequest`) → `MineGoal`/dřevorubec/
   `CraftGoal` boostují související cíle (utility dělá plánovač, žádné
   podcíle). `CraftPlanner` + recept schody (6 prken → 4 ks; půlbloky
   a sklo už umí). Náhrady po `build.substitute-after-minutes`.
4. **Generátor**: `HouseGenerator(params)` + `HouseDesigner` (povaha,
   profese, ambice, biom, **tier sídla**, kapacita parcely =
   `plot-spacing − 5`); determinismus testem (stejný seed ⇒ stejný dům).
   Archetypy: chaloupka 5×5, dům 6×6 s podkrovím a sedlovou střechou
   ze schodů, patrový 5×7; okna, interiér dle profese.
5. **Migrace v8** (`SchemaMigrator.migrations`, append za v7, vzor v5
   L181–195): `ba_builds(id [autoIncrementPk], owner_bot, settlement_id,
   kind, design, params_json, world, x, y, z, facing, created_at,
   updated_at)`; `BotRepository` řádek + load/upsert/delete přes
   `db.async` (single-thread write-behind).
6. **HOME data**: `design`/`params` klíče vedle `ox/oy/oz/facing`
   (finishHouse L800–812). `MaintainHomeGoal`: zachovat trojstupňové
   `resolveOriginAndFacing` (parcela → HOME data → rekonstrukce sever,
   L163–197), diff nově přes plán + `AcceptancePolicy`; bez `design`
   klíče = legacy 4×4 větev beze změny.
7. **Config sekce `build`** (`BotAliveConfig` record + `ConfigLoader`
   dle šablony settlement L165–179 + `config.yml` s českými komentáři):
   `complex`, `max-footprint` (min. 7), `max-floors`,
   `substitute-after-minutes`. `complex: false` = čistý legacy fallback.
8. **Fráze**: `HOUSE_UPGRADE_START/DONE` apod. – enum + cs/en listy
   (completeness test vynucuje cs).

## V2c – civilní stavby (pracovní položky)

1. **Šablonový loader**: `resources/structures/*.yml` (vrstvy znaků +
   mapa znak→role/orient), validace při startu (neznámý znak, díra
   v opoře přes `BuildPlanner` dry-run) – fail fast v logu, ne na
   staveništi.
2. **Stavy projektu SITE→SUPPLY→BUILD→DONE**: migrace v9 – sloupce
   `state`, `design`, `params_json`, `chest_x/y/z` do
   `ba_settlement_projects`; `SettlementService` API: `projectState`,
   `advanceProject`, `contributionNeeds(settlementId)` (BOM minus
   obsah truhly). Builder claim zůstává nepersistentní (TTL 10 min,
   `activeBuilder` L775).
3. **Zásobovací truhla**: postaví se přes `StationPlacement(CHEST)`
   (AbstractGoal L113–185); **nové metody `ChestStation`** –
   `depositMaterials(ctx, world, pos, Map<Material,Integer>)`
   a `withdrawMaterials(...)` (dnešní API je jen kategorie!) –
   implementovat v `ContainerService` (`bridge.callAt` + limit 6 bloků)
   **i** `PacketChestStation`. Donášku dělá rozšířený `StashGoal` vzor
   + nový drobný cíl `contribute` (ochota × HELPFULNESS × role).
4. **`ProjectKind` rozšíření**: `MARKET_STALL`, `TOWN_HALL`,
   `BELL_TOWER`, `CHURCH`; `nextProjectKind` (L748) podle žebříku
   z `COMPLEX_BUILDS.md`; **`tierOf` (L690): `townInfra =
   projectDone(GRANARY) && projectDone(MARKET_STALL)`** – tím se
   poprvé odemkne MĚSTO.
5. **Vícparcelová rezervace**: `SettlementService.reserveSite(id, w, d)`
   nad sousedností `PlotLayout.cellFor` (přiléhající indexy podél/přes
   prstence); blokace parcel vzorem `unusablePlots.put(index,
   Long.MAX_VALUE)` (přesně tak už dnes blokuje projektové parcely
   `neededProject`, L713).
6. **Trh**: ukotvení `SellGoal` nabídek k tržišti (fáze D růstové
   roadmapy) + fráze `SETTLEMENT_MARKET_*`, `SETTLEMENT_TOWNHALL_*`…

> **HOTOVO (dělba práce u velkých staveb) – lehčí cestou než bod 3.**
> Zásobovací řetězec „sběrač → truhla → stavitel" i stráž staveniště jsou
> hotové, ale **bez migrace a bez per-staveništní truhly**. Materiálovým
> skladem je **dvojtruhla `WAREHOUSE`** (sídlo si ho staví jako „společný
> sklad na materiál") – je perzistentní, postavený a jeho truhla se
> dopočítá z geometrie (`HouseBlueprint.bedSpot`, parita s `GranaryGoal`),
> takže odpadá `chest_x/y/z` i pokládka truhly. Řetězec je **bonus, ne
> podmínka**: naskočí až po dostavbě skladu (tj. právě u velkých
> prestižních staveb – radnice, kostel), jinak se stavitel zásobuje sám
> jako dřív. Hotové kusy:
> - `ChestStation.deposit/withdrawBuildingBlocks` (obě v `ContainerService`,
>   `bridge.callAt` + limit 6 bloků) – materiálové primitivum místo
>   `deposit/withdrawMaterials(Map)`; kategorie stavební blok stačí, protože
>   civilní stavby jedou na `GENERIC` (blok se počítá kusově, ne paletou).
> - `SettlementService.activeProject(botId)` – rozestavěná stavba pro
>   sběrače i stráž (nejčerstvější zamluvení, mizí po dostavbě/expiraci).
> - `MaterialDepot.chest(settlements, botId)` – sdílený lokátor truhly skladu.
> - `SupplyGoal` (nový cíl `supply`, HELPFULNESS, boost MASON/MINER) – nosí
>   přebytek bloků do skladu, když se staví.
> - `CommunalBuildGoal` fáze `DRAW` – stavitel si v PROVISION dobere ze
>   skladu, než začne dolovat (jednou za pokus, guard `drewFromDepot`).
> - `BuildGuardGoal` (nový cíl `build-guard`, GUARDIAN) – drží stráž
>   u staveniště; boj přebírá `CombatGoal`/`PvpGoal`.
>
> **Zbývá z bodu 3 jako budoucí V2c**: per-etapová BOM (`contributionNeeds`),
> stavy projektu `SITE→SUPPLY→BUILD→DONE` v DB (migrace v10) a přesné
> materiálové palety – teprve až je vynutí šablonové stavby.

## V2d – konsolidace

- Portál a wither oltář na engine: planner dostane material-exact
  režim (`AcceptancePolicy.strict` – obsidian se ověřuje `materialAt`,
  lebka naposled jako poslední `WorkUnit`); jejich invariantní testy
  se stanou testy planneru.
- `ScaffoldStep` (dočasný blok + úklidové `Op.Mine`), jen ukáže-li
  V2c potřebu (zvonice/kostel); gate `ai.terraforming`.
- Volitelně `BuildShelterGoal` na mini-blueprint.

## Rizika a mitigace

- **`BuildHouseGoal` nemá dnes žádný test** – největší riziko refaktoru.
  Mitigace: K1–K4 pokryjí novou mašinérii dřív, než se jí cíl dotkne;
  výběr staveniště (nejchoulostivější část) se nemění vůbec; K6 je
  čistě náhrada tří fází za session.
- **Packet režim**: session drží dnešní abstrakce (`WorldView`,
  `Snapshot`, `InventoryHelper`) – nic z Bukkitu krom `Material`;
  `blockDataAt` shovívavost v V2b.
- **Výkon**: schedule jednou per session, čistá geometrie; žádné
  `.join()` na tick vlákně (persistence fire-and-forget přes
  `db.async`, vzor sídel).
- **Kompatibilita**: staré HOME bez `design` = legacy větev; config
  bez sekce `build` = defaulty; migrace v8/v9 jen přidávají.
- **Souběh na sdíleném PostgreSQL**: id `ba_builds` řešit jako
  u sídel (náhodný long, L649 precedent), ne čítačem.

## Odhad rozsahu

| Krok | Nové soubory | Dotčené | Odhad LOC (vč. testů) |
|---|---|---|---|
| K1 | 8 (typy, adaptéry, testy) | – | ~500 |
| K2 | 2 (+testy) | – | ~450 |
| K3 | 2 + testutil | FakeBotContext | ~350 |
| K4 | 1 test | – | ~250 |
| K5 | – | CommunalBuildGoal | −150/+90 |
| K6 | – | BuildHouseGoal | −220/+100 |
| V2b | ~12 | ~10 | ~2000 |
| V2c | ~8 | ~8 | ~1600 |
