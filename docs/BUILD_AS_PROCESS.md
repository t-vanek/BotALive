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
| **4** | BOM wishlist | zapojit `BillOfMaterials` → cílené shánění/craft (sklo, cihly) | návrh |
| **5** | Zarovnání okolí | apron + sokl/terasa kolem půdorysu | návrh |

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
| FOUNDATION | prkna / hlína | dlažba / kámen (dnešek) | tesaný kámen |
| WALL | prkna | prkna | cihly / kámen |
| WALL_ACCENT | kmen | kmen | kmen |
| WINDOW | otvor (prázdná role) | sklo | tabule |
| ROOF | prkna / kmen | dlažba / prkna | cihly / kámen |

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

Hranice: implementován **materiálový** upgrade (stejná geometrie – levné,
bezpečné). **Strukturální** (větší dům, patro – bourá střechu) je samostatná,
pozdější kapitola. Uložený `btier` se dnes při konvergenci nepřepisuje (dům se
levně re-diffuje každé ráno) – volitelná optimalizace do budoucna.

### Fáze 4 – BOM wishlist

Dosud mrtvý
[`BillOfMaterials`](../botalive-core/src/main/java/dev/botalive/core/build/plan/BillOfMaterials.java)
se zapojí jako *build wishlist*: rozpis materiálu tieru → bot cíleně shání a
craftuje (sklo z písku, cihly z hlíny, tesaný kámen). Bez toho tiery uváznou –
bot nikdy záměrně nesežene sklo. Řetězec „rozpis → wishlist → náhrada" už
předjímá `COMPLEX_BUILDS.md`, jen nebyl postavený. Sem patří i dosud
neexistující `build.substitute-after-minutes`.

### Fáze 5 – Zarovnání okolí

Nad rámec dnešního (srovná se jen sloupce přesně pod půdorysem):
- **Apron:** prsten 1 blok kolem půdorysu srovnat na úroveň podlahy
  (odkopat vršky, zasypat prohlubně) – přístup ke dveřím, čistý sokl.
- **Sokl / terasa:** svažitou stranu podezdít místo věčného kopání –
  vypadá líp a míň jizví krajinu. Iterativně: nejdřív kopat do rozumné míry,
  pak dorovnat podezdívkou.
Vše pod `ai.terraforming` a novým `build.grade-apron`. Nezávislé na tierech –
dá se vsunout kdykoli.

## Config a data (průřezově)

- `Build` record (`config/BotAliveConfig.java`): `build.max-tier`,
  `build.grade-apron`, `build.substitute-after-minutes`.
- HOME paměť: nové pole `tier` (aditivní migrace).
- Testy: `PaletteBuildTest` (hotový nový případ), plus budoucí testy na
  tier-diff a atomicitu upgrade.

## Otevřené otázky pro fáze 2+

- Přesná materiálová tabulka tierů (co je „reprezentativní" pro které dřevo/biom).
- Jak přesně škáluje tier s prosperitou a osobností (křivka utility).
- Sokl vs. kopání: kde je hranice, za kterou se radši podezdí.
- Strukturální upgrade (patro, přístavba) – vlastní kapitola, zatím mimo rámec.
