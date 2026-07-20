# BotAlive — hloubkové testování 2026-07-20

Paper 26.1.2, 46 botů, ~40 min soak. Zdroje dat: runtime telemetrie
(`botalive overview/goal/settlements`), přímé SQL nad `botalive.db`, čtení kódu ve čtyřech
paralelních auditech (navigace, mozek/cíle, ekonomika/osady, persistence/souběžnost).

---

## Shrnutí

Plugin je **stabilní a výkonný** (0 výjimek, 0 zaseknutých botů, TPS 20,0 se 46 boty), ale
**funkčně mělký**: z ~60 implementovaných cílů je reálně živých ~12. Zbytek vrací utilitu
přesně 0.

Kořenová příčina není 20 nezávislých chyb, ale **dva systémové vzorce**:

1. **Binární brány se vyhodnocují i pro běžící cíl.** `Brain.decide()` zahodí cíl s utilitou 0
   ještě před hysterezí, takže jakmile podmínka na okamžik zhasne (ubyly bloky, padla noc,
   item vypadl z poloměru), cíl se opustí a `start()` smaže postup.
2. **Cíle vyžadují prostředek, který žádný jiný cíl nevyrábí** — a řetěz se přetrhne hned
   v prvním článku.

---

## Ověřený řetěz příčin: proč 80 % botů dělá jen `home` + `collect`

**Kalibrace:** naměřený histogram je z NOCI (dokazuje ho `guard`/`shelter`, oba mají tvrdou
bránu `isNight()`). Část nul je tedy očekávaná. Zbytek je defekt:

```
StashGoal uloží bloky nad 32 kusů   →  bot nikdy nemá 80–153 bloků na dům
        ↓
BuildHouseGoal.utility = 0 navždy   →  žádný dům
        ↓
žádná vesnice                       →  padá guard, maintain, communal-build, camp, decor
        ↓
sýpka se nepostaví                  →  tržiště se nepostaví  →  tier MĚSTO nedosažitelný
```

Zároveň `home` osciluje na 12blokovém prstenci (zapínací i vypínací poloměr byl stejný,
`restTicks` se inkrementoval až od 3 bloků → `finished()` byl **nedosažitelný kód**), takže
`home 19–31` v histogramu nebylo normální chování, ale zaseknutá smyčka.

---

## Opraveno v této session

| # | Oprava | Soubor | Dopad |
|---|---|---|---|
| 1 | Rezerva stavebních bloků 32 → 176, dokud bot nemá dům | [ContainerService.java](botalive-core/src/main/java/dev/botalive/core/inventory/ContainerService.java) | odemyká celý stavební pilíř (P0) |
| 2 | Vstupní brány (materiál, denní doba) jen pro ZAHÁJENÍ, ne pro běžící stavbu | [BuildHouseGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuildHouseGoal.java), [CommunalBuildGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CommunalBuildGoal.java) | konec torz domů (P0) |
| 3 | Hystereze u `home` – běžící cíl se drží do `finished()` | [ReturnHomeGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/ReturnHomeGoal.java) | konec oscilace na prstenci (P1) |
| 4 | `collect`: cooldown + detekce nedosažitelného itemu, základ 12+greed·20 → 6+greed·12, sjednocený poloměr | [CollectItemsGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CollectItemsGoal.java) | přestane přebíjet produktivní práci (P1) |
| 5 | Cooldown se odečítal v jiné jednotce než u ostatních ~28 cílů (5× delší) | [CampGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CampGoal.java), [CommunalBuildGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CommunalBuildGoal.java) | 25 min → 5 min mezi společnými stavbami (P2) |
| 6 | Samostatný recept na udici + `equipItem` místo hledání jen v hotbaru | [CraftPlanner.java](botalive-core/src/main/java/dev/botalive/core/crafting/CraftPlanner.java), [FishGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/FishGoal.java) | oživuje roli RYBÁŘ (P1) |
| 7 | Druhá truhla (na konci craft řetězu, ať nepředbíhá kovadlinu/composter) | [CraftPlanner.java](botalive-core/src/main/java/dev/botalive/core/crafting/CraftPlanner.java) | odemyká sýpku → tier MĚSTO (P1) |
| 8 | `assistFailed()` kotví backoff k živé pozici místo `lastProgressPos` | [Navigator.java](botalive-core/src/main/java/dev/botalive/core/pathfinding/Navigator.java), [BotImpl.java](botalive-core/src/main/java/dev/botalive/core/bot/BotImpl.java) | brzdí replán bouři (P1) |
| 9 | Přesun bota už nemaže historii selhání → backoff může eskalovat | [Navigator.java](botalive-core/src/main/java/dev/botalive/core/pathfinding/Navigator.java) | tamtéž (P2) |
| 10 | `stats.flush()` + `persistPosition()` i při odchodu/vypnutí | [BotImpl.java](botalive-core/src/main/java/dev/botalive/core/bot/BotImpl.java) | konec ztráty až 60 s statistik při každém restartu (P1) |
| 11 | `purgeBot` maže i `ba_employment` | [BotRepository.java](botalive-core/src/main/java/dev/botalive/core/persistence/BotRepository.java) | bot se stejným jménem nezdědí starou smlouvu (P1) |
| 12 | Log při ukončení vynuceného cíle | [Brain.java](botalive-core/src/main/java/dev/botalive/core/ai/Brain.java) | rozliší „nespustil se" od „vzdal se" (P2) |
| 13 | `BotEventLoop` už nenuluje statické pole → konec NPE v shutdown hooku | [BotEventLoop.java](botalive-core/src/main/java/dev/botalive/core/network/BotEventLoop.java) | čistý shutdown (P2) |

Všech **705 testů prochází**. Nález 13 se objevil až při restartu serveru s opravami — regrese
po dřívější opravě „zip file error".

---

## Zbývá — potvrzené, neopravené

### Ekonomika: čistě inflační, žádný propad (P1)

Z 11 335 transakcí v DB je **11 333 mint z těžby** (+33 378) a 2 transakce trhu (±10).
Zdroje peněz: `MineGoal:491`, `NetherGoal:1141`, `TradeGoal:143`, hráčské `/pay`.
Propady: **žádné**. Zůstatek každého těžícího bota roste monotónně, cena přestává být
signálem a `Ambition.RICH` táhne `mine/farm/fish/stash`, ale **neobsahuje `sell`**.

### Obchod se nespustí ani po opravě stavby (P1)

- `sell` (4+greed·9 = max 13) systematicky prohrává se `stash` (max 29,5) a `collect` na
  **stejné vstupní podmínce** (přebytek v inventáři). Bot přebytek vždy uloží.
- `buy` závisí na nabídkách, které vytváří jen `sell` → nástěnka je trvale prázdná.
- `sell`/`buy` vůbec nefigurují v `DayRhythm` (vždy ×1.0).
- `ShareGoal` (práh 8) rozdá jídlo dřív, než `SellGoal` (práh 12) dosáhne prodejního prahu.
- Timeouty se rozcházejí: prodejce se vzdá po 35 s, kupec má na cestu 45 s → **každý obchod
  na vzdálenost > 35 s cesty systematicky selže**.

### Atomicita peněz (P1)

`VaultBotWallet.withdraw()` vrací `true` podle lokálního zrcadla, skutečný výběr je async.
`MarketBoard.settle()` mezitím nepodmíněně provede `deposit` prodejci → při odmítnutí výběru
**mint do serverové ekonomiky**; `deposit` vrací `void`, takže opačným směrem peníze mizí.
`SellGoal` navíc účtuje **před** ověřením, že zboží pořád existuje.

### Persistence a souběžnost (P0/P1)

- `BotManagerImpl:270` volá `world.getHighestBlockYAt()` **z tick vlákna** → vynucený
  synchronní chunk load mimo hlavní vlákno (jen při `spawn.mode: random-around`).
- `SchemaMigrator:42` — DELETE+INSERT verze bez transakce a migrace v2/v4 nejsou idempotentní
  (`ALTER TABLE ADD COLUMN` bez `IF NOT EXISTS`); pád v milisekundovém okně = **trvale
  nestartující plugin**.
- `BotMemoryImpl:108` — `thenAccept` běží na jednovláknovém DB executoru a čeká na monitor
  tick vlákna → jeden bot s velkou pamětí zastaví persistenci všech.
- `ba_memories` roste bez limitu: `ExploreGoal` píše `VISITED_PLACE` à 10 s, merge do 8 bloků
  skoro nikdy nesedne → **~415 tis. řádků denně při 48 botech**. Naměřeno 21 183 řádků
  (max 525 na bota), `VISITED_PLACE` 9 835.
- `ba_transactions` nemá index na `bot_id` → `purgeBot` dělá full scan účetní knihy.
- `remove <jméno> purge` funguje jen na načtené boty → v DB je **228 řádků** při limitu 50.

### Mrtvé mechaniky (P2)

`granaryOf()` nemá v celém repu volajícího; postavené tržiště nemá na obchod žádný vliv
(`MarketBoard` je in-memory, rádius se počítá od bota). Tier MĚSTO je tak navázaný na stavby,
které nic nedělají.

---

## Korekce vlastních chybných závěrů

Zaznamenávám, protože obojí vypadalo přesvědčivě a bylo špatně:

1. **„Postel a vlna v kódu vůbec nejsou."** Grep na `Material.WHITE_BED`/`WOOL` nic nenašel,
   ale kód pracuje s materiály dynamicky: recept existuje (`CraftPlanner:524`, 3× vlna +
   3× prkna), dům postel pokládá (`HouseGenerator:150`) a `SleepGoal` ji hledá. Ambice
   COZY_HOME je nesplnitelná kvůli **upstream** blokádě (bot nikdy nepostaví dům), ne kvůli
   chybějící posteli.
2. **„Udice se nedá vyrobit."** Recept existuje, ale je zanořený pod podmínkou
   `SADDLE + WARPED_FUNGUS` (`CraftPlanner:341`) — vyrobí se jen pro jízdu na striderovi.
   Funkčně to na rybaření nestačilo, ale mechanismus byl jiný, než jsem tvrdil.

---

## Pozitivní zjištění

- **0 výjimek, 0 NPE, 0 „zip file error"** za 40 min běhu.
- **Navigace:** ve 12 po sobě jdoucích vzorcích „bez pohybu 15+ s: **nikdo**", 2× „moved
  wrongly" za 40 min. Historická oprava NPE v `Navigator.tick` je podle auditu **úplná**.
- **Výkon:** TPS 20,0/20,0/20,0 se 46 boty.
- **Osady fungují** po tier VESNICE včetně projektů (WELL hotovo, GRANARY se staví).
- **Sociální vrstva žije:** 398 zpráv, boti si navzájem dávají jídlo.
- Audit persistence označil dřívější podezření na off-thread `callEvent()` za **falešný
  poplach** — `BotEvent` nastavuje async příznak dynamicky, je to legální.

---

## Test na čistém světě (48 nových botů, prázdná DB)

Starý svět všechno podstatné **maskoval** — boti tam měli domy, vybavení a peníze zděděné
z minulých sessions, takže se stavební ani ekonomické opravy neměly jak projevit. Po smazání
světa a DB (zálohy v `test-server/_zaloha-*`) se ukázalo tohle:

| | starý svět | čistý svět |
|---|---|---|
| `combat` + `survive` | 0 z 46 | **41 z 48 (85 %)** |
| smrti | 0 za 40 min | **72 za 25 min** |
| osady | 5 | **0** |

### P1 — na čerstvém světě boti uvíznou ve smyčce přežití

Bot se narodí bez nástrojů, jídla a bloků. Přijde noc, `BuildShelterGoal` vyžaduje materiál,
který nemá, takže nemá kam zalézt a mobové ho zabíjejí dokola. Za 25 minut nevznikla jediná
osada. Chybí startovací (bootstrap) fáze: bot potřebuje nejdřív dřevo → nástroje → úkryt,
než ho může zaměstnat cokoli jiného.

### P1 — A\* timeouty 57 % (potvrzeno jako PŮVODNÍ, ne regrese)

Na čerstvém světě skončí přes polovinu A\* výpočtů na časovém stropu (25 ms / 8000 uzlů,
2 vlákna na 48 botů). Prázdných cest je přitom jen 6–9 %, takže se vrací **částečné** cesty —
boti chodí, ale hůř a s víc přepočty.

**A/B test** (worktree na HEAD vs. opravená verze, čistý svět) při shodném počtu výpočtů:

| výpočtů | původní kód | s opravami |
|---|---|---|
| ~200 | 19 % | 12 % |
| ~1 600 | 50 % | 47 % |
| ~3 300 | 57 % | 56 % |

Křivky jsou prakticky totožné a obě konvergují k ~57 % → **timeouty nezpůsobily mé změny**.
Je to nezávislý, dřív neviditelný problém: na starém (prozkoumaném) světě byly timeouty 5–7 %.

### P1 — NPE ve `FarmGoal` (opraveno)

`materialAt()` vrací `null` u nenacachovaného chunku (TTL 3 s) → `cropType = null` →
`CROP_SEEDS.get(null)`, jenže `CROP_SEEDS` je `Map.of()`, které na null klíči **hodí NPE**
(na rozdíl od `HashMap`, které vrátí null). Mozek pak celý cíl deaktivoval.
Stejný vzorec prověřen jinde: `MineGoal:487` i `NetherGoal:1039` null hlídají — `FarmGoal`
byl jediný nechráněný.

---

## P0 — nedosažitelný cíl v boji zamrzne bota (opraveno)

Nejčastější příčina nehybných botů vůbec: **208 z 211** hlášení watchdogu v běhu
s původním kódem mělo cíl `combat`.

V [CombatController.updateApproach](botalive-core/src/main/java/dev/botalive/core/combat/CombatController.java)
se jednou zahájené obcházení drželo „až na dosah úderu" **bez časového stropu**.
Když byl mob nedosažitelný (přes vodu, za zdí, na římse) a přitom pořád v dosahu trackeru
(32 bloků), zůstal `currentValid == true`, `lostTargetTicks` nerostlo a
`CombatGoal.finished()` nikdy nenastalo. Bot u něj stál, dokud ho něco nezabilo.

Tohle je podle všeho i motor „smyčky přežití" na čerstvém světě: bot se zamkne na
nedosažitelném mobovi, nemůže se odpoutat, přijde noc a zemře.

**Oprava:** strop 200 ticků (10 s) na obcházení + blacklist cílů, ke kterým se bot
nedokázal dostat (jinak si tentýž mob vybere hned příští tick).

---

## Startovní kit podle profese

Boti se rodili s prázdným inventářem, takže neměli z čeho postavit ani nouzový úkryt.
`StarterKitService` dává výbavu **jen při prvním spawnu** (`ba_bots.kit_given`, migrace v8);
po smrti se neopakuje, aby smrt něco stála. Migrace zároveň označí stávající boty jako
„už dostali", takže na existujícím serveru kit zpětně nedostanou.

Základ pro všechny: kamenné nástroje, 16 chleba, 64 dlažebek, 16 pochodní, verpánek.
Podle role navíc to, co jí **odemyká vlastní cíl** – rybář prut (bez něj `FishGoal` nikdy
neprošel branou), farmář osivo a motyku, obchodník smaragdy, lovec luk se šípy.

Testy hlídají i to, co v kitu **být nesmí** (diamant, netherit, elytra) – kit má první noc
přežít, ne přeskočit ranou hru.

## Profese: 10 → 19

Plugin měl 55 registrovaných cílů, ale jen 9 profesí – `brew`, `tame`, `reconcile`,
`deliver-work`, `camp`, `minecart`, `rob` neměly „majitele" a v histogramu se neobjevovaly.
Doplněno: `ALCHEMIST`, `GUARDIAN`, `SCOUT`, `BEASTMASTER`, `THIEF`, `DIPLOMAT`,
`ADVENTURER`, `COURIER`, `COOK`.

`RolePicker` dostal **vzácnost**: páteř osady (stavitel, kopáč, farmář, dřevorubec) ×1.15,
specializace ×0.85, zloděj a dobrodruh ×0.6. Bez toho by se 19 profesí rozdrobilo na
~2 boty na roli a vesnice by neměla kdo stavět.

`RoleCoverageTest` hlídá, že každá role má aspoň jeden zesílený cíl, že profil neukazuje
na neexistující id cíle (překlep `deliver_work` by roli tiše proměnil v popisek) a že
každou profesi někdo napříč povahami dostane.

**Pozor:** `RoleProfiles` i `DayRhythm` používaly `Map.of()`, který má strop 10 dvojic –
při rozšiřování je nutné `Map.ofEntries()`.

---

## Poznámka k metodice

Dvě věci, které mě málem svedly:

- **`ps -W | grep paper.jar` je nespolehlivý test, jestli server běží** — proces se zobrazuje
  bez argumentů, takže grep nikdy nic nenašel a smyčka „počkej na ukončení" skončila okamžitě.
  Server pak běžel dál, držel zámek světa a dva následné starty padly na
  „jiný proces uzamkl soubor". Spolehlivé je čekat na konkrétní **PID**.
- **Baseline se musí měřit při shodném počtu výpočtů**, ne po shodném čase — zátěž A\* se mezi
  běhy liší řádově, takže časové srovnání říká nesmysly.
