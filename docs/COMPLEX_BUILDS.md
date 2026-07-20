# Složitější stavby: stavební engine v2

Návrh a fázový plán; prováděcí rozpad je v `COMPLEX_BUILDS_IMPL.md`.
Navazuje na fáze 13–15 (vesnice, parcely, údržba
domu) a na `SETTLEMENTS_GROWTH.md`, jehož „Vědomé meze" (hradby, radnice,
větší budovy) tímto přestávají být mezemi. Drží DNA projektu: **každé
jádro rozhodování je čistá, jednotkově testovatelná funkce**, **fyzický
svět je autorita** a **vše se děje protokolově věrně** – bot nemá
`setBlock`, jen pohled, kurzor a `UseItemOn` jako hráč.

## Proč nový engine (dnešní meze)

Stavění dnes stojí na dvou dobrých primitivech (`PlaceBlockTask`
s AIM→PLACE→VERIFY a blueprintech s ručně zaručeným pořadím opory),
ale nad nimi je pět stropů:

1. **Jeden standpoint** – celá stavba se pokládá z jednoho místa
   (`HouseBlueprint.standPoint`), dosah ruky ⇒ strop 4×4×3. Žádná
   patra, žádný pohyb po stavbě.
2. **Holé pozice, jeden materiál** – `placements()` vrací `BlockPos`
   bez významu; zeď = střecha = podlaha = cokoli
   z `InventoryHelper.isBuildingBlock` whitelistu. Žádné orientované
   bloky (schody, půlbloky), žádné palety.
3. **Build smyčka 4×** – `BuildHouseGoal`, `CommunalBuildGoal`,
   `NetherGoal` (portál) a `WitherFightGoal` (oltář) si tutéž smyčku
   terraform → pokládka → vybavení píší každý znovu; kontrakt
   blueprintů (`placements/clearVolume/groundColumns/standPoint`) je
   konvence, ne rozhraní (`CommunalBuildGoal` dispatchuje `if/else`
   podle `ProjectKind`).
4. **Materiál jen oportunisticky** – cíl se probudí, až když bloky
   náhodou v batohu jsou (`needs.buildingBlocks() ≥ blocksNeeded()`);
   neexistuje rozpis materiálu → cílené shánění.
5. **Rozestavěnost jen world-diffem** – „pevný blok = hotovo, přeskoč".
   Funguje jen díky jedinému designu a zaměnitelnému materiálu; u více
   designů se po restartu neví, *co* se stavělo.

Vedlejší důsledek: tier **MĚSTO je fakticky nedosažitelný**
(`townInfra` se předává natvrdo `false`, `MARKET_STALL` je TODO).

## Cíl

- **Rozmanité domy**: patra, okna, sedlové střechy ze schodů, palety
  podle biomu/profese/povahy – každý dům trochu jiný, deterministicky
  ze seedu.
- **Civilní stavby sídel**: tržiště (odemkne skutečné MĚSTO), radnice,
  zvonice, kostel – přes stejné jádro, designy jako data, velké stavby
  na vícparcelových staveništích.
- Jedno sdílené jádro místo čtyř kopií smyčky; stavby přežívají
  přerušení, noc i restart.

Reprezentace plánů: **generátory + šablony** (obojí ústí do jednotného
`BuildPlan`). Materiály: **cílené shánění s náhradami** (rozpis → wishlist
→ craft; co dlouho nejde sehnat, paleta nahradí – stavba se nezasekne).

## Model (`core/build/plan`)

### BuildPlan a Blueprint

```java
record BlockSpec(PaletteRole role, Orient orient)   // orient jen kde dává smysl
record PlacementCell(BlockPos local, BlockSpec spec)
interface Blueprint {                                // formalizace dnešní konvence
    Dims size();                                     // W×H×D, obdélník povolen
    List<PlacementCell> cells();                     // BEZ pořadí – to je věc planneru
    Set<BlockPos> clearVolume();
    Set<BlockPos> groundColumns();
    List<FurnishCell> furnishing();                  // dveře, postel, truhla, světlo
    BlockPos doorCell();                             // exit + orientace k návsi
}
```

`BuildPlan.instantiate(blueprint, origin, facing, palette)` převádí
lokální souřadnice na světové. Rotace obdélníkového půdorysu prohazuje
rozměry (origin zůstává minimální roh – vzor `HouseBlueprint.local()`)
a **rotuje i orientace** (`Orient` schodů/dveří se otáčí s půdorysem).

Zdroje blueprintů, engine mezi nimi nerozlišuje:

- **Generátory** (kód): `HouseGenerator.generate(HouseParams)` –
  parametrický, nekonečná variace, unit testy. Params (půdorys, patra,
  typ střechy, okna, seed) vybírá `HouseDesigner` z povahy, profese,
  biomu a kapacity parcely; persistují se, takže tentýž dům jde
  kdykoli regenerovat.
- **Šablony** (data): `resources/structures/*.yml` – vrstvy znaků +
  mapa znak → role/materiál/orientace. Snadné přidávání civilních
  staveb bez nového kódu; loader validuje (neznámý znak, díra
  v podpoře) při startu, ne až na staveništi.

### Palety a přijatelnost

`PaletteRole` (FOUNDATION, WALL, WALL_FRAME, FLOOR, ROOF, ROOF_EDGE,
WINDOW, DOOR, LIGHT, INTERIOR…) → seznam materiálů v pořadí preference.
Konkrétní paletu skládá `PaletteResolver` z biomu (druh dřeva), profese
a seedu. `AcceptancePolicy.accepts(spec, actualMaterial)` je jediné
místo pravdy pro world-diff: resume, opravy (`MaintainHomeGoal`)
i rozhodnutí „cizí blok v půdorysu vybourat, nebo přijmout jako zeď"
(přírodní kámen v kamenné roli projde, dirt ve srubu ne).

### BuildPlanner (čistá funkce)

`BuildPlanner.schedule(plan, worldSnapshot, caps)` → `BuildSchedule` =
seznam `WorkUnit(standPos, steps…)` + terraform prolog. Kroky:

1. **Etapy**: základy/podlaha → zdi po patrech → stropní deska →
   střecha. Stropní deska je zároveň podlaha stavitele pro další patro
   – **bot stojí na vlastní stavbě jako hráč**, žádná teleportace.
2. **Stanoviště**: pro každý blok kandidátní stání v dosahu
   (konzervativně 4,0 bloku od očí); greedy set-cover shlukne bloky
   k co nejmenšímu počtu stanovišť, mezi nimi se chodí navigátorem.
   Preference: vnitřek → terén venku → vlastní strop/zeď.
3. **Pořadí s oporou**: topologické setřídění, aby každý blok měl
   v okamžiku pokládky pevného souseda (zem, dřívější blok, svět) –
   zobecněný invariant `PortalBlueprint`, nově hlídaný pro *každý*
   design testem, ne ručním pořadím v kódu.
4. **Nezazdění**: interiér a vybavení před uzavřením objemu, poslední
   pokládky dosažitelné ode dveří/zvenku – bot vždy vyjde dveřmi.

Ve V2a/b **bez lešení**: archetypy se navrhují postavitelné ze země
a z vlastních stropů (planner to ověří testem). Lešení (dočasný blok +
úklid) je připravené v modelu jako `ScaffoldStep`, zapíná se až kdyby
V2c stavby potřebovaly (zvonice).

### BuildSession (sdílený vykonavatel)

Jedna smyčka pro všechny cíle, konzumuje `BuildSchedule`:
GOTO_STAND → TERRAFORM → PLACE → FURNISH; per krok `equipForSpec`
(nahrazuje `equipBuildingBlock` tam, kde krok žádá roli), world-diff
skip přes `AcceptancePolicy`, stavy `BLOCKED_MATERIAL(request)`
a `UNREACHABLE` vrací řízení cíli, `progress()` živí `explain()`
(„stavím dům … zbývá N bloků"). Session je v paměti cíle; po restartu
se plán regeneruje z persistence a diffne proti světu – **svět zůstává
autoritou položených bloků**.

### PlacementHints (protokolová věrnost)

`PlaceBlockTask` dnes bere jen cílovou pozici a oporu si najde sám.
Orientované bloky potřebují hinty: **stranu a kurzor** (půlblok/schod
horní vs. spodní půlkou kliknuté stěny), **vyžadovaný yaw** (schody
a postel se natáčejí podle pohledu hráče, dveře pant podle kurzoru).
Rozšíření: `PlaceBlockTask(target, PlacementHints)` – AIM fáze zamíří
na přesný bod a natočí tělo; VERIFY zůstává přes traits, materiál se
doověří přes `WorldView.materialAt`, orientace se garantuje mechanikou
kliku (přesně jako u hráče, žádná magie s block state).

## Materiály: rozpis → wishlist → náhrada

- `BillOfMaterials.of(plan)` → mapa rolí a počtů; utility gate cíle se
  mění z „mám dost bloků" na „mám dost NA PRVNÍ ETAPU" – stavět se
  začíná dřív, shání se průběžně.
- Nová fáze **SUPPLY** v cíli: chybí-li materiál, bot jde cíleně –
  vlastní truhla → těžba/kácení (rozšíření `BotNeeds` o *build
  wishlist* vedle `miningWishlist`) → craft (`CraftPlanner` nově
  schody 6 prken → 4 ks; sklo tavbou písku už umí, půlbloky už umí).
- Po `build.substitute-after-minutes` bez pokroku povolí paleta
  náhradu (další materiál v pořadí role, nakonec generický blok).
  Stavba má charakter, ale nikdy se nezasekne navždy.
- Civilní stavby: materiál řeší **zásobovací truhla na staveništi**
  a stav SUPPLY projektu (viz životní cyklus níže) – nosí všichni,
  staví držitel claimu. Předstupeň spolupráce bez dělení stavební
  práce.

## Persistence (migrace v8)

```sql
ba_builds(id, owner_bot NULL, settlement_id NULL, kind,
          design, params_json, world, x, y, z, facing,
          created_at, updated_at)
```

- Řádek existuje jen pro **rozestavěnou** stavbu: říká *co* se staví
  (design + params + origin/facing); pokrok se dál odvozuje world-diffem
  (žádné počítadlo, které by se rozešlo se světem). Dokončení řádek maže.
- Hotový dům ukládá design+params do dat HOME vzpomínky (vzor
  `ox/oy/oz/facing`) → `MaintainHomeGoal` opravuje proti skutečnému
  plánu domu. Staré HOME bez designu = legacy 4×4 (plná zpětná
  kompatibilita, nic se nebourá).
- Projekty sídla: `ba_settlement_projects` + sloupce `design`,
  `params_json`. Builder claim zůstává nepersistentní (restart uvolní
  projekt dalšímu – osvědčené).

## Integrace cílů

- **`BuildHouseGoal`**: FIND_SITE/RELOCATE/GOTO_SITE (výběr místa,
  katastr, posouzení na místě) zůstávají beze změny; TERRAFORM/BUILD/
  FURNISH nahrazuje `BuildSession`, přibývá SUPPLY. `siteCost` škáluje
  `MAX_FILLS/MAX_DIGS` plochou půdorysu.
- **Kapacita parcely (domy)**: max půdorys domu = `plot-spacing − 5`
  (u výchozích 12 ⇒ 7×7; to je zároveň default `build.max-footprint`,
  konfigurací lze zvednout spolu se spacingem) – rozestupy a cestičky
  zůstávají, **existující vesnice se nemění** (spacing přepisovat
  nelze, odvozují se z něj origins parcel).
- **Vícparcelová staveniště (civilní stavby)**: velikost civilní
  stavby určuje šablona, ne strop domů. Projekt si podle půdorysu
  rezervuje **obdélník sousedních parcel** – 1×2 podél prstence
  (~7×19 využitelné plochy), 2×2 přes prstence (~19×19). Rezervace je
  čistá geometrie nad `PlotLayout`; rezervované parcely se nenabízejí
  domům. Kostel 9×13 se tak vejde bez zásahu do spacingu existujících
  vesnic.
- **`HouseDesigner`**: archetyp váží povaha (pracovitý/chamtivý větší,
  líný menší), profese (stavitel výstavnější), ambice „útulný domov",
  biom a **stupeň sídla** (žebřík níže – honosnější archetypy se
  odemykají s tierem); deterministicky z persistovaného seedu.
- **`CommunalBuildGoal`**: `if/else` dispatch nahradí registr
  blueprintů podle `ProjectKind`; rozšíření o `MARKET_STALL` (V2c) –
  `SettlementTier.of(houses, well, townInfra)` konečně dostane
  `townInfra = sýpka && tržiště` ⇒ MĚSTO dosažitelné. Radnice jako
  prestižní projekt města (není podmínkou tieru – substance, ne dekret).
- **`MaintainHomeGoal`**: diff proti plánu z HOME (design/params),
  legacy rekonstrukce 4×4 zůstává.

## Žebřík stupňů: tvrdá brána, pořád substance

Postup sídla je stupňovitý program: **do dalšího stupně se jde až se
splněnými podmínkami toho současného – dokončené stavby stupně
a zajištěný materiál na projekt**. Podmínky tieru zůstávají odvozené
ze substance (nic se nedekretuje), nové je, že stupeň zpětně odemyká,
co se smí stavět:

| Stupeň | Podmínka (jako dnes) | Co stupeň odemyká |
|---|---|---|
| **Osada** | 1+ dokončený dům | základní archetypy domů (chaloupka 5×5), projekt **studna** |
| **Vesnice** | ≥ 4 domy + studna | větší archetypy (6×6 s podkrovím, patrový 5×7), projekty **sýpka** a **tržiště** |
| **Město** | ≥ 8 domů + sýpka + tržiště | honosné archetypy, prestižní projekty **radnice, zvonice, kostel** (vícparcelové; nejsou podmínkou tieru) |

„Zajištěný materiál" není utility gate v batohu jednoho bota, ale stav
projektu – viz životní cyklus níže. Důsledek pro domy: nový člen
v mladé osadě staví skromně; honosný dům je vidět až tam, kde sídlo
skutečně vyrostlo.

## Životní cyklus projektu: SITE → SUPPLY → BUILD → DONE

Rozšíření stavů z `SETTLEMENTS_GROWTH.md` (SITE/BUILD/DONE) o **SUPPLY**
– odpověď na „stavba stojí, došel materiál":

1. **SITE** – projekt vznikne na nástěnce sídla, zarezervuje parcely
   a první stavitel na staveniště položí **zásobovací truhlu**
   (jediný blok – staví se jako dnes stanice přes `placeOwnStation`).
2. **SUPPLY** – nástěnka zveřejní rozpis materiálu (BOM po etapách).
   Členové nosí příspěvky do truhly: přebytky (rozšíření `StashGoal`)
   i cílené shánění (build wishlist), ochota váží HELPFULNESS a roli.
   Truhla je fyzická autorita – co v ní je, to je zajištěno; restart
   nic neztratí.
3. **BUILD** – jakmile truhla kryje aktuální etapu, držitel claimu
   staví; materiál si bere z truhly (ne z vlastního batohu), při
   vyčerpání se projekt vrací do SUPPLY, claim drží. Etapové krytí
   znamená, že se začíná dřív, než je sneseno úplně všechno.
4. **DONE** – jako dnes (fyzická stavba autorita, záznam cache);
   truhla zůstává sídlu (u tržiště se hodí, jinde ji stavitel vyklidí).

Sólo domy truhlu nepotřebují – tam zůstává osobní fáze SUPPLY
(vlastní truhla → těžba/kácení → craft → náhrady).

## Fázový plán

- **V2a – jádro bez změny chování**: model (`Blueprint`, `BuildPlan`,
  palety), `BuildPlanner`, `BuildSession`; dům/studna/sýpka na engine
  s dnešním designem jako generátorem (parita chování). Simulační test
  (fake svět + inventář → session doběhne → bloky == plán) po vzoru
  `PathExecutionSimulationTest`. Portál a oltář se zatím nemigrují
  (verify obsidiánu, lebka naposled – specifika, viz V2d).
- **V2b – rozmanité domy**: `PlacementHints` (schody, půlbloky, dveře,
  postel), palety + BOM + osobní SUPPLY, migrace v8, `HouseDesigner`
  s 3–4 archetypy (chaloupka 5×5, dům 6×6 s podkrovím a sedlovou
  střechou, patrový 5×7) odemykanými stupněm sídla, okna, interiér
  podle profese; `MaintainHomeGoal` na nové plány; hlášky.
- **V2c – civilní stavby**: šablonový loader (`structures/*.yml`),
  životní cyklus SITE→SUPPLY→BUILD→DONE se zásobovací truhlou,
  `MARKET_STALL` + ukotvení `SellGoal` k tržišti (fáze D růstové
  roadmapy), vícparcelová rezervace a prestižní stavby města
  (radnice, zvonice, kostel).
- **V2d – konsolidace**: portál a wither oltář na engine (jejich
  invarianty přebírá planner), lešení, pokud ho V2c stavby ukázaly
  jako potřebné.

## Testy

- **Invarianty planneru property-style přes všechny designy** (a fuzz
  přes náhodné params/seedy): opora v okamžiku pokládky, dosah ze
  stanoviště, stoj na pevném, nezazdění, exit dveřmi. Tohle je jádro
  jistoty „co projde plannerem, to bot postaví".
- Determinismus generátorů (stejný seed ⇒ stejný dům), validace
  šablon při načtení, `AcceptancePolicy`, BOM aritmetika, migrace v8,
  simulace celé session.

## Config

```yaml
build:
  complex: true                 # vypnutí = dnešní chování (legacy 4×4)
  max-footprint: 7              # strop půdorysu DOMŮ (min. default; civilní
                                # stavby řídí šablona + rezervace parcel)
  max-floors: 2
  substitute-after-minutes: 20  # kdy paleta povolí náhradu materiálu
```

Terénní úpravy dál gatuje `ai.terraforming`; `build.complex: false`
musí zůstat plnohodnotný fallback (server bez zájmu o velké stavby
nepozná rozdíl).

## Vědomé meze

- **Dělení stavební práce mezi boty** – mimo plán, dokud V2b/c
  neukáže potřebu; zásobovací truhla je záměrný předstupeň.
- **Import .schem** – mimo plán; šablonový formát je vlastní, čitelný
  a validovatelný při startu.
- **Hradby, brány, druhé prstence** – až po V2c (lineární segmentová
  geometrie kolem obvodu sídla je jiný planner).
- **Lešení** – v modelu připravené, zapíná se až při skutečné potřebě.
