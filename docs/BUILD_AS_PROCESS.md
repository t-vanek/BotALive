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
| **2** | Tiery palety | `PaletteResolver.resolve(wood, seed, tier)`; Tier 0 = dřevo + otvory; `tier` do HOME dat | návrh |
| **3** | Upgrade smyčka | údržba diffuje proti *vyššímu* tieru a nahrazuje po rolích, atomicky | návrh |
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

### Fáze 2 – Tiery palety

Dům dostane **stavební stupeň** a každý stupeň je jen jiná `Palette` nad
**stejnou geometrií**:

| Role | Tier 0 „srub" | Tier 1 „solidní" | Tier 2 „reprezentativní" |
|---|---|---|---|
| FOUNDATION | hlína / dřevo | dlažba / kámen | tesaný kámen |
| WALL | prkna | prkna | cihly / kámen |
| WALL_ACCENT | kmen | kmen | kmen + detaily |
| WINDOW | otvor (`LEAVE_EMPTY`) | sklo | skleněné tabule |
| ROOF | prkna | schody / dlažba | cihly |

- `PaletteResolver.resolve(woodHint, seed, tier)` – nová dimenze `tier`.
  Tier 0 dává nosným rolím tier-0 materiál (dřevo/hlína), okna zůstávají
  otvorem. `TIER0_MATERIAL` politika u vyšších tierů řeší „chybí cihla → polož
  prkno + dluh".
- Nový dům se postaví na tieru podle prosperity sídla (viz rozhodnutí 2);
  samotář začíná na Tier 0.
- **Persistence:** HOME paměť už nese `bwood/bseed/bw/bh` (viz
  `BuildHouseGoal.finishHouse`); přidá se `tier`.
  `MaintainHomeGoal.resolveDesign` z něj složí paletu správného stupně.
  Migrace je aditivní – starý dům bez `tier` = Tier 1 (dnešní vzhled).

### Fáze 3 – Upgrade smyčka

Rozšíření údržby (nebo nový `UpgradeHomeGoal`): bot občas projde dům, najde
bloky nižšího tieru a **nahradí je** za vyšší (vytěžit starý → hned položit
nový). `AcceptancePolicy` se rozšíří z „co je přijatelné" na „co je přijatelné,
ale existuje lepší tier".

- **Trigger:** stupeň sídla (hlavní), osobnost (modulace), přebytek materiálu.
  Utilita nízká, pod přežitím.
- **Bezpečnost (rozhodnutí 4):** náhrada v ruce před těžbou; po celých rolích;
  dojde-li materiál, zbytek příště.
- **Vizuál:** upgrade po rolích, ať dům nevypadá půl-na-půl.

Hranice: **materiálový** upgrade (stejná geometrie – levné, bezpečné) vs.
**strukturální** (větší dům, patro – drahé, bourá střechu). Začínáme
materiálovým; strukturální je samostatná, pozdější kapitola.

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
