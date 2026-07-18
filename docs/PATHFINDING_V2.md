# Pathfinding v2 – analýza možností vylepšení

Tento dokument analyzuje současný pathfinding (dále „v1") a navrhuje, co a v
jakém pořadí zlepšit v nové verzi. Cíl analýzy: pojmenovat slabá místa
s konkrétními důkazy v kódu, nabídnout varianty řešení s trade-offy a
doporučit fázování tak, aby každá fáze byla samostatně nasaditelná a krytá
testy.

## 1. Současný stav (v1)

```
Goal ──navigateTo──▶ Navigator ──findPath──▶ NavigationService ──▶ AStarPathfinder
                        │                        (pool ⅛ CPU)         (grid A*)
                        │◀─────────── Path (waypointy, complete?) ◀────┘
                        │
                        ├─ exekuce: waypointy → MoveInput (sprint, skoky, dveře)
                        ├─ PathSmoother (string pulling, LOOKAHEAD 6)
                        ├─ SegmentPlanner (dálkové trasy: segmenty 64 bloků)
                        ├─ detekce zaseknutí (50 ticků) → replán ×3
                        └─ needsAssist → BotImpl.planObstacleRecovery
                                         (MineBlock/Bridge/Pillar/Ladder/Place)
```

Silné stránky, které stojí za to zachovat:

- **Čistá vrstva nad `BlockTraits`/`WorldView`** – vnitřní smyčka nesahá na
  Bukkit, funguje v server i packet režimu, je Folia-safe a testovatelná
  (28 unit testů `AStarPathfinderTest` + `PathSmootherTest`,
  `SegmentPlannerTest`, `PoolExitTest`).
- **Bezpečnost** – tvrdý zákaz hazardů a UNKNOWN, drop scan s vodní výjimkou,
  void-safe odjakživa; brzda u hran a EdgeGuard při exekuci.
- **Bohatý repertoár** – desky/schody/sníh přes `floorHeight()`, dveře,
  žebříky, plavání s hospodařením s dechem, sprint-skoky mezer 1–2 bloky
  (i diagonálně, i s dopadem níž), penalizace portálů.
- **Učení z chyb** – ceny zdražují okolí DEATH/DANGER vzpomínek (max 24).
- **Eskalace jako hráč** – replán → prokopání → most/pilíř/žebřík
  (`ai.terraforming`, strop `MAX_ASSIST_CYCLES = 10`).
- **Oddělená humanizace** – plánování je deterministické, lidskost řeší
  exekuce (smoothing, sprint-hop dle povahy) a Humanizer; v2 to nesmí slepit.

## 2. Slabá místa (s důkazy)

### P1: Výkon vnitřní smyčky

- **Žádná memoizace traits per výpočet.** `feetHeight()` dělá 2–6 dotazů
  `traitsAt` a stejnou buňku se ptá opakovaně až 8 sousedních expanzí
  (`AStarPathfinder.feetHeight`, `transitClear`). Každý dotaz jde přes
  Caffeine (chunk) + `STATE_CACHE` (ConcurrentHashMap nad BlockData). Odhad:
  50–150 dotazů na expandovaný uzel, tedy až ~1M dotazů na jeden výpočet
  s plným rozpočtem 8 000 uzlů.
- **`terrainPenalty` skenuje 18 buněk** (3×3×2 hazard okolí) na každý
  přijatý uzel.
- **`dangerPenalty` je O(dangers)** na každý `tryAdd` – až 24 vzdáleností
  na uzel místo prostorové mřížky.
- **PriorityQueue bez tie-breaku** – při shodě f se neupřednostňuje vyšší g,
  na rovinách se zbytečně expandují plata (klasický A* trik zadarmo).
- **Rozpočet v uzlech, ne v čase** – vodní/hazardní oblasti mají dražší
  uzly a výpočet trvá násobně déle při stejném rozpočtu; latenci pro tick
  smyčku nic negarantuje.
- **Výpočty nejsou zrušitelné** – `Navigator.stop()` jen zahodí referenci na
  future (`pendingPath = null`), výpočet v poolu doběhne celý. Při rychlém
  střídání cílů (boj, útěk, nový segment) pool mele zbytečnou práci.

### P2: Replanning vždy od nuly

- Každý replán je plný A*. Zaseknutí → až 3 replány (`repathAttempts`),
  každý cyklus assistu → další plný replán (`assistResolved`).
- **Pohyblivý cíl = replán při každém bloku pohybu cíle.**
  `FollowPlayerGoal` volá `navigateTo(position, target.toBlockPos())` každý
  tick cíle; guard `to.equals(destination)` padne pokaždé, když se sledovaný
  posune o blok → plný A* několikrát za sekundu na bota. Totéž potenciálně
  u eskort/tame/pvp přibližování.
- Stará cesta se nevaliduje proti změně světa – replán se spouští slepě
  (zaseknutím), i když by stačilo cestu srovnat od aktuálního waypointu
  („splice"), a naopak se nespouští, když svět cestu rozbil, ale bot ještě
  fyzicky nenarazil.

### P3: Dálkové trasy jsou přímka s dvěma úhyby

- `SegmentPlanner` klade mezicíle po vzdušné čáře (64 bloků) s laterálními
  posuny jen `{0, +24, −24}`. Slepé rameno širší než 24 bloků (velké jezero,
  horský masiv, kaňon) → všechny tři posuny selžou → přímý částečný plán →
  často `assistNeeded` → terraforming nebo vzdání. Bot neumí „obejít to
  z druhé strany kopce".
- **UNKNOWN = zeď.** Prefetch je fire-and-forget (`NavigationService.findPath`
  žádá chunky a hned počítá) – studená cache znamená částečnou cestu končící
  na hranici chunků, stop-and-go chůzi a opakované výpočty. Nic nečeká na
  doručení snapshotů ani nenavazuje výpočet na jejich příchod.

### P4: Částečná cesta míří do slepé pasti

- Fallback „nejlepší uzel" je čistě `min h` (`if (current.h < best.h)`).
  V U-pasti (zátoka, ohrada, budova) je nejblíž cíli uzel **uvnitř pasti** –
  bot tam dojde, zasekne se a eskaluje terraformingem, místo aby si částečnou
  cestu vybral tak, aby vedla k obejití (vážit h i g, případně penalizovat
  uzly s malým „únikovým" potenciálem). Opakované pokusy nemají paměť
  („tudy už to nešlo") – tu má jen segmentový posun.

### P5: Reaktivní zásahy do terénu místo plánovaných

- Eskalace funguje, ale draze: každý zásah = zaseknutí (50 ticků čekání) →
  assist → 1–2 bloky kopání → plný replán. Dlouhý tunel navigací nejde
  (strop 10 cyklů); štoly řeší zvlášť `MineGoal` vlastní logikou.
- Přitom existuje `BreakTimeEstimator` (vanilla vzorec dob kopání) – cena
  hrany „prokopej se" jde spočítat přesně, a `PlaceBlockTask`/`BridgeTask`
  dávají cenu hrany „polož blok". Plánovač o těchto akcích neví.

### P6: Cílem je vždy jeden blok

- API je `navigateTo(from, BlockPos)`. Chybí cílové predikáty: „do
  vzdálenosti r od X" (interakce s truhlou/ponkem), „na Y hladinu" (těžba),
  „pryč od X" (plánovaný útěk – dnes panika řeší přímý pohyb s EdgeGuardem),
  „nejbližší z N kandidátů" (strom/ruda/truhla – dnes si cíl vybere kandidáta
  předem a teprve pak zjistí, že je nedosažitelný; multi-target A* najde
  dosažitelný nejlevnější sám).
- `normalizeGoal` řeší jen posun o buňku dolů nad deskou; cíle si dosažení
  „přibližně" hlídají každý po svém (`SEGMENT_REACHED_SQ`, tolerance v goalech).

### P7: Konfigurace a observabilita

- Jediný knob: `performance.pathfinding-threads`. Rozpočet uzlů, stropy
  seskoků, ceny, segmentové délky – vše zadrátované konstanty.
- Žádné metriky (doba výpočtu, expandované uzly, hit-rate částečných cest,
  fronta poolu) ani debug nástroj (`/botalive path <bot>` s výpisem trasy);
  ladění nové verze bez toho bude střelba naslepo. Jediná diagnostika je
  `logDeadStart` mapka 3×3.

### P8: Osobnost do plánování nemluví

- Styl cesty je pro všechny boty stejný; povaha ovlivňuje jen exekuci
  (sprint, bunny-hop). Odvážný bot by měl brát parkour a seskoky levněji,
  opatrný držet větší odstup od hazardů, líný preferovat rovinu – ceny jsou
  ale statické konstanty. (Háček: `Navigator` osobnost má, do
  `findPath` ji neposílá.)

## 3. Varianty řešení

### A. Evoluce jádra – výkon a robustnost (S–M, nízké riziko, vysoký přínos)

1. **Per-výpočet memo cache traits** (`Long2ObjectOpenHashMap<BlockTraits>`
   uvnitř jednoho `findPath`) – odhadem 5–10× méně dotazů do sdílených cache;
   navrch memo `feetHeight` (Long2DoubleMap). Čistě lokální, žádná
   invalidace (výpočet je snapshot v čase, jako dnes).
2. **Časový rozpočet vedle uzlového** – `budget = min(nodes, millis)`,
   kontrola po blocích expanzí (např. každých 256). Garantovaná latence.
3. **Kooperativní zrušení** – `AtomicBoolean cancelled` v handle, který
   `Navigator.stop()`/nový `navigateTo` nastaví; smyčka ho čte spolu
   s časem. Pool přestane mlít mrtvou práci.
4. **Tie-break PQ na vyšší g** (komparátor `f, pak −g`) a **danger mřížka**
   (hrubá spatial hash místo O(N) smyčky).
5. **Výběr částečné cesty**: `best` podle `h + g/K` (malé K) místo čistého
   `h` – preferuje přiblížení, které nezabíhá hluboko do pastí; plus krátká
   paměť neúspěšných regionů mezi pokusy jednoho `destination`.
6. **Konfig sekce `pathfinding.*`** (rozpočty, stropy, ceny-multiplikátory)
   + **metriky** (per výpočet: uzly, ms, výsledek; agregace do
   `/botalive stats` nebo logu) + **`/botalive path <bot>`** (výpis aktivní
   trasy, případně particle vizualizace pro admina).

Efekt: stejné chování, výrazně nižší CPU na bota, měřitelnost pro další
fáze. Bez migrace – API i testy beze změny (testy jen zrychlí).

### B. Chytřejší replanning (S–M, nízké riziko)

1. **Moving-target throttle + splice**: `navigateTo` s cílem do ~2 bloků od
   běžícího `destination` cestu nezahazuje – jen posune poslední waypoint(y),
   plný replán až při větší odchylce nebo periodicky (např. 1×/s). Řeší
   follow/escort/tame za pár řádek.
2. **Validace cesty proti světu**: levná kontrola nadcházejících ~8 waypointů
   (memo traits) jednou za pár ticků; rozbitá cesta → replán hned (dnes až
   po fyzickém zaseknutí = 2,5 s), nezměněná cesta → nikdy zbytečný replán.
3. **Replán ze zaseknutí navazuje od aktuální pozice na zbytek staré cesty**,
   pokud validace projde od k-tého waypointu (šetří plný A*).

D* Lite / LPA* (inkrementální přepočet) považuji za overkill: světové změny
jsou lokální a řídké, splice+validate pokryje 90 % užitku za zlomek
složitosti a bez zásahu do datových struktur A*.

### C. Hierarchie pro dálkové trasy – „HPA*-lite" (M–L, střední riziko)

Náhrada `SegmentPlanner` přímky grafem konektivity regionů:

- Svět rozdělit na regiony (např. 16×16×16 nebo chunk-sloupce s Y pásmy);
  pro načtené regiony předpočítat **průchodnost hran mezi sousedy**
  (existuje pochozí přechod?) – levný flood-fill nad traits, cache
  s invalidací navěšenou na stejné eventy jako chunk snapshoty.
- Dálková trasa = A* nad region grafem (tisíce uzlů i pro kilometrové
  vzdálenosti), výsledkem **koridor**, kterým vede série low-level plánů
  (dnešní A* s malým rozpočtem). Mezicíle = brány mezi regiony místo bodů
  na přímce.
- UNKNOWN regiony: volitelně **optimisticky průchozí s přirážkou**
  (povrchová úroveň), s povinným replánem při přiblížení – bot smí vyrazit
  „směrem tam" jako hráč s mapou, místo dnešního odmítnutí mezicíle.
  Prefetch se naváže na koridor (a výpočet na doručení chunků –
  `CompletableFuture` kompozice, ne fire-and-forget).

Řeší P3 i většinu P4 (past obejde hierarchie). Fallback na dnešní segmenty
zachovat za flagem pro případ regresí. Riziko: invalidace region cache a
paměť (držet LRU, počítat lazy per dotaz).

### D. Akčně rozšířený A* – plánované kopání/stavění (L, vyšší riziko, nejvyšší strop)

Do grafu přidat hrany-akce à la Baritone: „prokopej blok" (cena
z `BreakTimeEstimator` × nástroj v inventáři), „polož blok pod sebe /
před sebe" (cena + spotřeba), „postav pilíř/most segment". Gated přes
`ai.terraforming` + nové flagy, jen mimo cizí stavby (respektovat dnešní
pojistky assistů – kontrola tekutin, žádné kopání pod sebou).

- Sjednotí dnešní reaktivní eskalaci (P5) do plánu: tunel, schodiště,
  most vzniknou jako **jedna cesta s akcemi**, ne smyčka
  zaseknutí→assist→replán ×10.
- Exekuce už existuje: `MineBlockTask`, `PlaceBlockTask`, `BridgeTask`,
  `PillarUpTask` – Navigator by je spouštěl podle typu hrany waypointu
  (`Path` waypoint dostane volitelný `action` tag).
- Rizika: exploze větvení (řešit až po A a C – akční hrany jen když
  „pěší" soused neexistuje, a s výrazně vyšší cenou), víc testů, dražší
  výpočty. Chování zůstává lidské – tempo kopání i pokládání drží tasky
  a `BreakTimeEstimator`, pakety se nemění.

### E. Rozšíření repertoáru pohybů (S za kus, střední přínos)

- **Sprint-skok přes 3 bloky prázdna** (vanilla dá ~4 bloky vzdálenosti;
  dnes strop `MAX_GAP = 2`) – exekuce už sprint u širších mezer vynucuje.
- **Parkour s dopadem o blok výš** (skok na vyvýšenou plošinu přes mezeru)
  – dnes jen stejná výška / o blok níž.
- **Seskok do vody s odhozem** (ledge → voda dál od stěny) a **žebřík shora**
  (sestup na žebřík z hrany) – dnes šplh jen ze sousední buňky.
- Každý pohyb = jedna metoda v expanzi + unit testy podle vzoru stávajících
  (`preskociDvoublokovouMezeru`…), exekuci většinou netřeba měnit.

### F. Cílové predikáty (M, odemyká kvalitu v goalech)

`PathGoal` rozhraní: `GoalBlock` (dnešek), `GoalNear(pos, r)`,
`GoalY(level)`, `GoalAway(pos, minDist)`, `GoalAnyOf(kandidáti…)`.
Heuristika a test cíle se ptají predikátu; multi-target zadarmo najde
nejbližší **dosažitelný** strom/rudu/truhlu (P6). `GoalAway` dá plánovaný
útěk (dnes jen reflexní přímý úprk s EdgeGuardem) – v Endu/u lávy zásadní
bezpečnostní upgrade. Goalům se zjednoduší kód (zmizí ruční tolerance).

### G. Sdílené cesty a „silnice" (S, kosmetika/emergence)

- Mírná **sleva na `dirt_path`/deskové cesty** → boti viditelně chodí po
  vesnických cestičkách, které si sami staví (DECORATE fáze) – zpětná vazba
  vesnice ↔ pathfinding zadarmo.
- Volitelně per-vesnice cache častých tras (náves↔domy) sdílená mezi boty;
  jen jako optimalizace, ne zdroj pravdy (svět se mění).

### H. Osobnost v cenách (S, šmrnc)

Multiplikátory cen z povahy, předané do `findPath` (Navigator osobnost už
má): odvaha ↓ ceny mezer/seskoků, opatrnost ↑ hazard okolí a ↑ max drop
o −1, lenost ↑ ceny převýšení (radši rovina), zvědavost ↓ penalizaci
neznáma (s C). Deterministické per-bot (žádný šum do plánu – kmitání cest
by vypadalo stroze), dva boti volí různé trasy = viditelná individualita.

## 4. Doporučené fázování

| Fáze | Obsah | Náročnost | Riziko | Hlavní přínos |
|---|---|---|---|---|
| **v2.0 jádro** | A (memo, čas, cancel, tie-break, partial-best, konfig+metriky+debug) + B (throttle, splice, validace) | M | nízké | 5–10× levnější výpočty, konec replan bouří u followu, měřitelnost |
| **v2.1 dálka** | C (region graf, koridory, optimistické UNKNOWN, prefetch navázaný na výpočet) | M–L | střední | kilometrové trasy bez slepých ramen a stop-and-go |
| **v2.2 akce** | D (dig/place hrany za flagem, sjednocení s assist eskalací) + F (predikáty – lze i dřív, je nezávislé) | L | vyšší | tunely/mosty jako plán, plánovaný útěk, multi-target |
| **v2.3 šmrnc** | E (nové skoky) + G (silnice) + H (osobnost) | S | nízké | lidskost a individualita tras |

Pořadí není náhodné: **A je prerekvizita všeho** (bez metrik a levné smyčky
se C ani D nedá odladit ani zaplatit), B je nejlepší poměr cena/užitek pro
okamžitě viditelné chování (follow/escort), C řeší nejčastější reálné
selhání (dálkové výpravy – Nether, End, vesnice), D má nejvyšší strop, ale
až na stabilním základě. F lze předsunout kdykoli – nemá závislosti, jen
mění API goalů.

## 5. Kompatibilita a zábradlí

- **API držet**: `Navigator`, `NavigationService.findPath`, `Path` zůstávají;
  `Path` se rozšiřuje (action tag, predikát cíle) zpětně kompatibilně.
  Goaly se migrují postupně (F), `navigateTo(BlockPos)` zůstane jako
  `GoalBlock` zkratka.
- **Vrstvení nechat**: plánování čisté nad `WorldView`/`BlockTraits`
  (server i packet režim, Folia-safe, bez Bukkitu ve smyčce); lidskost jen
  v exekuci a Humanizeru – v2 nesmí přinést „strojově dokonalé" trasy do
  paketů (smoothing, tolerance a šum exekuce zůstávají).
- **Testy jako záchranná síť**: 28 stávajících scénářů drží repertoár;
  přidat regresní scénáře na P2–P4 (pohyblivý cíl, U-past, hranice chunků,
  časový rozpočet) a mikro-benchmark (uzly/ms na FakeWorldView bludišti)
  do CI, ať je zisk fáze A doložitelný a regrese C/D viditelné.
- **Feature flagy**: `pathfinding.hierarchical`, `pathfinding.planned-actions`
  – nové chování vypínatelné, výchozí zapnout až po ověření v provozu.

## 6. Shrnutí

v1 je bezpečný a repertoárově bohatý grid A* s dobrou architekturou vrstev,
ale plýtvá výpočty (P1, P2), na dálku je slepý (P3, P4), zásahy do terénu
dělá reaktivně a draze (P5), goalům nabízí jen „dojdi na blok" (P6) a chybí
mu měřitelnost (P7). Nová verze by měla nejdřív zlevnit a změřit jádro
(v2.0), pak vyřešit dálkové trasy hierarchií (v2.1), a teprve na tom
stavět plánované akce a bohatší cíle (v2.2) a osobitost tras (v2.3).
