# Stavba jako proces

Dům není jednorázová událost, ale **proces**: rodí se jako provizorní skica
(dřevo, otvory místo oken, srovnaný jen nutný terén) a v čase dozrává –
materiály se vylepšují, okna se zasklí, okolí se upraví. Tenhle dokument
popisuje rámec, jeho odfázování a rozhodnutí, na kterých stojí.

Navazuje na `COMPLEX_BUILDS.md` (paleta, generátor domu, `BuildSession`) a
`SETTLEMENTS_GROWTH.md` (stupně sídla). Klíč, na kterém celý rámec drží:
architektura už **odděluje geometrii od materiálu** –
[`Blueprint`](../botalive-core/src/main/java/dev/botalive/core/build/plan/Blueprint.java)
říká *kde* blok je a *jakou má roli*
([`PaletteRole`](../botalive-core/src/main/java/dev/botalive/core/build/plan/PaletteRole.java)),
[`Palette`](../botalive-core/src/main/java/dev/botalive/core/build/plan/Palette.java)
říká *z čeho*. „Udělej stejný dům z lepšího materiálu" je tedy **stejný
Blueprint, jiná Palette** – levné a bezpečné, protože geometrie se nemění.

## Rozhodnutí (zafixovaná)

1. **Substituce se přiřazuje po rolích.** Každá role má vlastní
   [`SubstitutionPolicy`](../botalive-core/src/main/java/dev/botalive/core/build/plan/SubstitutionPolicy.java):
   - `FILL_GENERIC` – nosné a plné role (zeď, základ, střecha): chybí-li
     materiál, dozdít náhradou. Radši odstín než díra.
   - `LEAVE_EMPTY` – okno: chybí-li sklo, **nechat otvor**, ne zazdít.
   - `TIER0_MATERIAL` – u vyšších tierů: chybí-li cílový materiál, položit
     materiál nižšího stupně a zapsat *dluh na upgrade* (fáze 2+).
2. **Upgrade žene prosperita, moduluje osobnost, podmiňuje přežití.** Hlavní
   spouštěč je stupeň sídla (dům roste s osadou → vesnicí → městem).
   Pracovitý/hrdý bot zvelebuje víc, líný míň. Utilita upgrade je vždy nízká –
   **nikdy nepřebije jídlo, obranu ani spánek.**
3. **Terén: kopat a iterativně se snažit o sokl a terasu.** Cut&fill srovná
   podlahu, obvodový prsten (apron) a podezdívka (sokl/terasa) dům usadí do
   svahu místo „na stole".
4. **Bezpečnost upgrade.** Nikdy neodstranit blok bez okamžité náhrady v ruce;
   upgradovat po celých rolích (celá střecha, pak celá fasáda), ne náhodně.
   Důsledek pravidla „náhrada v ruce": dojde-li materiál v půlce role,
   **nezačne se těžit další blok** – hotové zůstanou povýšené, zbytek na starém
   tieru do příště. Žádné vracení, žádná díra.

## Odfázování

Pořadí drží **závislosti a riziko**, ne atraktivnost. Fáze 0–1 nemají migraci
a jsou hotové; 2–5 čekají na dopracování.

| Fáze | Co | Jádro změny | Stav |
|---|---|---|---|
| **0** | Gate zásypu | terraform bez zásypového bloku vrátí `BLOCKED_MATERIAL`, ne díru | **hotovo** |
| **1** | Okna jako otvor | `SubstitutionPolicy` po rolích; `WINDOW`=`LEAVE_EMPTY`; oprava pasti ve verifikaci a údržbě | **hotovo** |
| **2** | Tiery palety | `PaletteResolver.resolve(wood, seed, tier)`; Tier 0 = dřevo + otvory; `tier` do HOME dat | **hotovo** |
| **3** | Upgrade smyčka | údržba diffuje proti *vyššímu* tieru a nahrazuje po rolích, atomicky | **hotovo** |
| **4** | Build wishlist | cílené shánění: **sklo-smyčka** (písek→sklo) i **cihlová smyčka** (hlína→cihla→blok) hotové; tesaný kámen volitelný | **hotovo** |
| **5** | Zarovnání okolí | apron dig (srovnat prstenec) hotový; sokl/terasa (zásyp svahu) zbývá | **z části** |
| **6** | Strukturální růst | dům se s prosperitou zvětšuje (5×5 → 7×7 → 9×9, vyšší zdi); aditivně, pak se odklidí starý vnitřek | **hotovo** (opt-in) |

### Fáze 0 – Gate zásypu (hotovo)

[`BuildSession.tickTerraform`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BuildSession.java)
dřív ignoroval návrat `equipBuildingBlock`: došel-li zásypový blok, pokládka
tiše selhala a v podlaze zůstala díra – zeď pak neměla oporu a dům stál nad
prázdnem. Nově se bez čeho zasypat vrátí `BLOCKED_MATERIAL` (dostaví se jindy,
resume world-diffem). Čistý bugfix, bez dat.

### Fáze 1 – Okna jako otvor (hotovo)

Okno už mělo roli `WINDOW` s materiálem `[GLASS, GLASS_PANE]`, ale při chybějícím
skle `equipFor` spadl na zaměnitelný blok a okno **zazdil**. Nově:

- Role nese `SubstitutionPolicy` (viz rozhodnutí 1). `equip()` v `BuildSession`
  vrací `READY / SKIP / BLOCKED`; `LEAVE_EMPTY` bez materiálu → `SKIP` (otvor).
- **Past ve verifikaci:** `finishUnits`/`missingUnits` kontrolovaly
  `traitsAt(pos).solid()` – prázdné okno by hlásily věčně jako nepoložený blok
  → torzo (`INCOMPLETE`). Cely s `LEAVE_EMPTY` se z povinné verifikace vyřazují.
- **Past v údržbě:**
  [`MaintainHomeGoal.planRepairs`](../botalive-core/src/main/java/dev/botalive/core/ai/goals/MaintainHomeGoal.java)
  by prázdné okno vidělo jako díru a zazdilo ho. Nově `leaveEmpty()` okno doplní
  **jen když máme sklo**; jinak zůstane otvorem. `equipFor` u `LEAVE_EMPTY`
  nikdy nezazdí generikem.

Blast radius je úzký: legacy domek 4×4 okna nemá, `CivicHall` je řeší
vynecháním buňky (žádná role) – `LEAVE_EMPTY` se týká jen `HouseGenerator`.
Test `leavesWindowOpenWhenGlassMissing` hlídá, že otvor není torzo.

### Fáze 2 – Tiery palety (hotovo)

Dům dostane **stavební stupeň**
([`BuildTier`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BuildTier.java):
`PROVISIONAL` / `SOLID` / `REFINED`) a každý stupeň je jen jiná `Palette` nad
**stejnou geometrií** – povýšení domu je záměna palety, ne přestavba tvaru.

| Role | PROVISIONAL „srub" | SOLID „solidní" | REFINED „reprezentativní" |
|---|---|---|---|
| FOUNDATION | prkna / hlína | dlažba / kámen (dnešek) | cihly / tesaný kámen* |
| WALL | prkna | prkna | cihly / tesaný kámen* |
| WALL_ACCENT | kmen | kmen | kmen |
| WINDOW | otvor (prázdná role) | sklo | skleněné tabule |
| ROOF | prkna / kmen | dlažba / prkna | cihly / tesaný kámen* |

REFINED = cihly a tesaný kámen, oba si bot vyrobí sám: `BRICKS` z hlíny
(hlína → cihla → blok), `STONE_BRICKS` z kamene (cobble → kámen → tesané cihly).
`*` **Variace podle seedu:** zeď je jeden materiál, základ a střecha druhý –
dva reprezentativní domy nejsou stejné. Okna jsou **skleněné tabule** (sklo →
tabule, masonry gate); plné sklo i přírodní kámen zůstávají přijatelné.

**Honosnější geometrie:** `tier` se protahuje i do `HouseGenerator` (ne jen do
palety), takže REFINED dům dostane **komín** – sloupec u nároží nad střechu
(role ROOF, materiál z palety). Komín má oporu při pokládce (`BuildPlanner.order`
projde) a vyšší bloky stavitel dosáhne z pilířového stanoviště. Nižší stupně
mají prostou geometrii beze změny.

**Dekorace a osvětlení:** REFINED dům svítí **lucernami** místo pochodně (a
jednou navíc) a má u bočních oken **květináče** – `HouseGenerator.furnishing`
je teď tier-aware (`FurnishKind.LANTERN`, `FurnishKind.FLOWER_POT`). Obojí bot
vyrobí sám, masonry-gated v `CraftPlanner`: lucerna z přebytku železa
(*ingot → 9 nugetů*, *8 nugetů + pochodeň → lucerna*, s rezervou ingotů),
květináč z přebytku cihel (*3 cihly → květináč*, až když má zásobu bloků na
zdi – dekorace neujídá zdivo). Vybavení je bonus: co bot nemá, `BuildSession`
i údržba přeskočí.

**Plot s brankou:** existující [`SettlementFenceGoal`](../botalive-core/src/main/java/dev/botalive/core/ai/goals/SettlementFenceGoal.java)
kolem domu plot **už staví** (plaňky z prken přes `craftFencing`, klade
`BarrierWorker` po obvodu `Enclosure`, branka u dveří, idempotentně, i s
opravou) – jen měl napevno legacy velikost 4×4, takže pro širší generovaný dům
byl plot uvnitř zdí. Nově cíl **čte skutečnou šířku domu** z HOME dat (`bw`),
takže plot správně obkrouží i REFINED dům (a odhad plaňků `fenceEstimate(width)`
škáluje s obvodem). Zapíná se configem `settlement.fences` (default vyp.);
funguje pro člena vesnice i samotáře. Materiál plotu se ladí na dřevo domu
(`BarrierStyle`).

- `PaletteResolver.resolve(woodHint, seed, tier)` – nová dimenze `tier`;
  2-arg varianta zůstává a míří na `SOLID` (dnešní vzhled beze změny).
  U `PROVISIONAL` je role `WINDOW` prázdná → `intended` je prázdné →
  `LEAVE_EMPTY` nechá otvor vždy (sklo přijde až upgradem).
- Nový dům dostane tier z prosperity a osobnosti:
  `HouseDesigner.tierFor(settlementTier, laziness)` – osada→`PROVISIONAL`,
  vesnice→`SOLID`, město→`REFINED`; pracovitý posune o stupeň nahoru, líný
  dolů (přichyceno k mezím). Samotář (bez sídla) začíná srubem.
- **Persistence:** HOME paměť už nese `bwood/bseed/bw/bh`; přidalo se `btier`
  (ordinal). `MaintainHomeGoal.resolveDesign` z něj složí paletu správného
  stupně. Migrace je aditivní – **starý dům bez `btier` = `SOLID`** (žádná
  regrese vzhledu), díky sekundárnímu 4-arg konstruktoru `HouseDesign`.
- `TIER0_MATERIAL` politika (chybí cihla → polož prkno + dluh) je zavedená
  jako enum, ale sešlape se až s upgrade smyčkou (fáze 3).

### Fáze 3 – Upgrade smyčka (hotovo)

Rozšíření [`MaintainHomeGoal`](../botalive-core/src/main/java/dev/botalive/core/ai/goals/MaintainHomeGoal.java)
(rozhodnutí: rozšířit údržbu, ne nový cíl): `resolveDesign` spočítá **cílový
tier** z aktuální prosperity a osobnosti (`targetTier`). Když stoupl nad
uložený, dům se povyšuje – jinak čistá oprava na uloženém stupni. `max()`
nikdy nesnižuje: dům povýšený za rozkvětu se nebourá, když sídlo splaskne.

- **Výběr (čistá funkce
  [`HomeUpgrade.next`](../botalive-core/src/main/java/dev/botalive/core/build/plan/HomeUpgrade.java)):**
  najde nejnižší roli (základ → zeď → …), jejíž stojící bloky nejsou z cílového
  materiálu, a vrátí je (nejvýš `MAX_UPGRADES = 6` za seanci). Dům dozrává po
  rolích, konzistentně, ne půl na půl.
- **Bezpečnost (rozhodnutí 4):** `tickUpgrade` vytěží starý blok **jen s cílovým
  materiálem v ruce** (`hasItem` před `MineBlockTask`), pak na totéž místo
  položí nový. Došel-li materiál, zbytek povýšení příště – žádná otevřená díra,
  žádné vracení.
- **Okna (otvor → sklo):** neřeší upgrade cesta, ale **oprava proti vyšší
  paletě** – prázdné okno je „díra", kterou `planRepairs`/`leaveEmpty` zasklí,
  jakmile má bot sklo a cílový tier okno očekává.
- **Střídmost & přežití:** utilita `MaintainHomeGoal` je nízká a běží jen
  v klidném ranním okně u domova – upgrade tak nikdy nepřebije jídlo, obranu
  ani spánek. Prosperita je hlavní hnací síla, osobnost moduluje (`tierFor`).

Hranice: tato fáze je **materiálový** upgrade (stejná geometrie – levné,
bezpečné). **Strukturální** růst (větší dům) je fáze 6. Když dům plně dosáhne
cílového tieru, `MaintainHomeGoal`
**zapíše dosažený `btier`** do HOME dat (jen zvýšení, nikdy nesnížení) – pak se
už zbytečně nediffuje a údržba míří na správný materiál i kdyby prosperita
později splaskla.

### Fáze 4 – Build wishlist (hotovo)

Bez cíleného shánění by tiery uvázly – bot nikdy záměrně nesežene sklo ani
cihly a vyšší domy by navždy zůstaly ze dřeva s otvory místo oken. Zapojeny
jsou **dva kompletní autonomní řetězce**, oba **gate-ované samotným sběrem**
(surovinu si natěží jen stavitel, jehož cílový tier ji vyžaduje):

- [`BuildMaterials.gatherWishlist`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BuildMaterials.java)
  (čistá funkce): solidnímu+ domu bez skla/písku vrátí **písek**, reprezentativnímu
  (REFINED) bez zásoby cihel navíc **hlínu**. Srub (PROVISIONAL) nic nechce.
- [`MineGoal`](../botalive-core/src/main/java/dev/botalive/core/ai/goals/MineGoal.java)
  ho bere jako **krok 1b** – nižší priorita než rudy: surovina se sbírá, jen
  když poblíž není žádaná ruda, ať těžba nástrojů má přednost. Cílový tier počítá
  `MineGoal.buildTarget` z prosperity a osobnosti (`HouseDesigner.tierFor`).
- **Sklo-smyčka:** písek → `SmeltGoal`/`FurnaceService` roztaví na **sklo** →
  okna zasklí oprava (`MaintainHomeGoal`, fáze 3). *natěžit → roztavit → zasklít*.
- **Cihlová smyčka** (zdi): hlína → těžba dá `CLAY_BALL` → pec roztaví na
  **cihlu** (`FurnaceService.SMELTABLE`) → `CraftPlanner` složí *4 cihly → blok*
  → REFINED zdi. Gate je **čistě ve sběru** (hlínu sbírá jen REFINED stavitel),
  bez příznaku v tavicí/craft vrstvě.
- **Tesaný kámen** (základ, střecha): cobble mají všichni boti, tak tenhle
  řetězec nese **explicitní `masonry` gate** = cílový tier je REFINED
  ([`AbstractGoal.wantsMasonry`](../botalive-core/src/main/java/dev/botalive/core/ai/goals/AbstractGoal.java)).
  Spočítá se v goal vrstvě (`SmeltGoal`, `CraftGoal`) a protáhne jako boolean
  do `FurnaceStation.insert` / `CraftingStation.craftNext` → `CraftPlanner.State`
  (tavicí a craft vrstva neznají sídlo). Řetěz: cobble → pec: **kámen**
  (masonry gate + strop zásoby) → `CraftPlanner`: *4 kameny → 4 tesané cihly*
  → REFINED základ a střecha. Cizí boti tak cobble nemelou.

Oba materiálové stropy (`BRICK_BLOCK_STOCK`, `STONE_BRICK_STOCK`, `MASONRY_STOCK`)
drží tempo střídmé – bot nevyrábí donekonečna.

**Volitelné dál:** per-materiálový
[`BillOfMaterials`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BillOfMaterials.java)
jako přesný rozpis (dnes gate na skalárním počtu bloků) a
`build.substitute-after-minutes`.

### Fáze 5 – Zarovnání okolí (z části hotovo)

Nad rámec dnešního (srovná se jen sloupce přesně pod půdorysem) je hotový
**apron dig** – v duchu rozhodnutí „kopat a snažit se o sokl a terasu iterativně"
se začíná kopáním:

- [`BuildPlanner.schedule(plan, world, gradeApron)`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BuildPlanner.java)
  srovná **1-blokový prstenec kolem půdorysu** na úroveň podlahy: v každém
  sloupci prstence vytěží, co trčí na úrovni podlahy a hlavy (`origin.y()` a
  `+1`), aby dům netrčel do svahu a šlo k němu dojít.
- **Jen výkopy** – žádný materiál, takže zarovnání nikdy nezablokuje stavbu
  (na rozdíl od zásypu). Hazard (láva/magma) a nenačtené bloky přeskočí; na
  rovině nedělá nic (self-limiting terénem).
- Gate-uje `ai.terraforming` (`BuildHouseGoal` předá příznak). Civilní stavby
  ho zatím neberou (bezpečnější default).

**Zbývá — sokl / terasa (iterativní krok):** svažitou stranu **podezdít**
místo věčného kopání – vypadá líp a míň jizví krajinu. Chce to zásyp
s materiálem (nesmí zablokovat stavbu → best-effort, ne přes hlavní `fill`
frontu) a hezčí geometrii podezdívky. Nezávislé na tierech.

### Fáze 6 – Strukturální růst (hotovo, opt-in)

Nad rámec materiálového zrání dům **fyzicky roste**: jak sídlo bohatne (osada →
vesnice → město), zvětšuje se i **půdorys a výška** (5×5 → 7×7 → 9×9, vyšší zdi).
Velikost volí jediný zdroj pravdy
([`StructureSizer`](../botalive-core/src/main/java/dev/botalive/core/build/plan/StructureSizer.java):
`houseWidth`/`houseWallHeight` – prosperita žene, osobnost moduluje, config dává
strop), stejně jako materiálový tier.

Klíč je **bezpečné pořadí** (rozhodnutí 4 zobecněné z materiálu na geometrii):
*aditivně napřed, demolice vnitřku naposled.* Čistá funkce
[`StructureGrowth`](../botalive-core/src/main/java/dev/botalive/core/build/plan/StructureGrowth.java)
(sourozenec `HomeUpgrade`) vrátí **přídavky** (buňky většího pláště, které ještě
nestojí) a **demolice** (staré strukturální bloky uvnitř nového pláště). Demolice
jsou prázdné, **dokud plášť nestojí** – dům je tak celou dobu zakrytý (starý nižší
krov drží, než se postaví nový vyšší; teprve pak se odklidí). Odklízí se přesně
staré buňky mimo nový plášť, takže se nikdy neubourá nový plášť ani vybavení.

[`MaintainHomeGoal.maybeGrow`](../botalive-core/src/main/java/dev/botalive/core/ai/goals/MaintainHomeGoal.java):
- **Center-fixed origin.** Nový roh se spočítá tak, aby **střed** domu zůstal na
  místě (dům je čtvercový). Střed = stanoviště stavitele = klíč HOME paměti, ten se
  tedy nehýbe – žádná migrace klíče. Parcela v evidenci sídla se posune na nový roh.
- **Viability gate.** Roste jen na **vhodném rovném terénu** (nová podlaha pod celým
  půdorysem je pevná → obvod má oporu). Jinak růst počká – radši malý dům než torzo
  na svahu.
- **Determinismus a monotónnost.** Cíl je funkce prosperity a **uložené** velikosti
  (`bw/bh`); přerušený růst se dostaví na tentýž tvar (world-diff resume). Po
  dokončení se nová velikost i roh zapíšou (`max()`, nikdy nezmenší).
- **Střídmost.** Materiál se povyšuje až po velikosti; růst běží po částech v klidném
  ranním okně u domova, s nízkou utilitou – nikdy nepřebije jídlo/obranu/spánek.

Za configem `build.grow` (default **vyp.**) – opt-in, protože i aditivní přestavba
může dům přechodně otevřít jako každá rozestavěná stavba. Testy: `StructureGrowthTest`
(invariant „žádná demolice, dokud plášť nestojí", přesnost demolic, idempotence,
center-fixed origin).

**Společné stavby** (radnice, kostel, městská sýpka/sklad) se **sizují z prosperity
při vzniku** a rozměr se persistuje (viz `SETTLEMENTS_GROWTH.md`) – nová stavba
v prosperujícím městě je větší. Plné *re-growth* už dokončené společné stavby (jako
u domů) je přirozený další krok nad touž persistencí.

**Rezervované staveniště.** Aby růst nemusel dorovnávat terén, staveniště se rovnou
srovná pro **maximální dorostlý dům + okraj** (`build.reserve`) – ve všech osách
(rovná plocha v X/Z, výškové srovnání). Srovnání rezervy je **jen výkopové** (jako
apron), takže nikdy nespotřebuje bloky určené domu; díry dorovná až růst
([`BuildPlanner.schedule(..., reserveGround)`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BuildPlanner.java)).
Rezerva se přichytí pod rozestup parcel, takže si stavby nechají odstup.

**Nesmí se stavět na nesrovnaném terénu.** Pokládka je tvrdě gate-ovaná
srovnáním: `BuildSession` projde **celý** `mine` (výkopy) + `fill` (zásyp podlahy;
bez materiálu vrátí `BLOCKED_MATERIAL`, nikdy nepoloží zeď nad díru) a teprve pak
klade stavbu. Platí to **i pro malé přístřešky**: `BuildShelterGoal` nově
**nejdřív srovná/uzavře podlahu** (zaplní díry pod celým půdorysem 3×3) a teprve
pak staví zdi – přístřešek tak stojí na pevné zemi, zeď má oporu a zespodu se
nikdo nedostane (na rovině je podlaha už pevná → beze změny).

**Nouzový přístřešek je dočasný.** Panik-budka z první noci (`BuildShelterGoal`,
`type=shelter`) není domov navždy: jakmile má bot **reálný dům**, ve dne budku
poblíž **zbourá** (vytěží zdi a strop, zapomene záznam) a bydlí v opravdové stavbě,
která pak roste. Nízká priorita – úklid se řeší, jen když není nic naléhavějšího.

## Config a data (průřezově)

- Apron dig je gate-ovaný stávajícím `ai.terraforming` (žádný nový config).
- HOME paměť: přidané pole `btier` (ordinal; aditivní migrace, chybí = `SOLID`).
- Výška domu: `build.wall-height` je nově **spodní mez** (osada), `build.max-wall-height`
  strop; skutečnou výšku volí bot z prosperity a povahy (`StructureSizer`).
- Strukturální růst: `build.grow` (default vyp.). Strop půdorysu (`build.width`) se
  při načtení configu zkrátí pod rozestup parcel (`plot-spacing − 3`), aby dům
  nelezl k sousedovi a nevyčníval z ochranného pásma.
- Testy: `PaletteBuildTest`, `StructureSizerTest`, `StructureGrowthTest`.

## Otevřené otázky pro další fáze

- Přesná materiálová tabulka tierů (co je „reprezentativní" pro které dřevo/biom).
- Sokl vs. kopání: kde je hranice, za kterou se radši podezdí.
- Patro / přístavba (nečtvercová geometrie) – dnešní růst drží čtvercový půdorys
  (rotační invariance, reach test); patro je další samostatná kapitola.
- Re-growth už dokončených společných staveb (dnes se sizují při vzniku).
