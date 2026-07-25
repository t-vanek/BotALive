# BotAlive — audit mezer v cílech 2026-07-25

Statická analýza všech 65 registrovaných cílů + průřez registrů (DayRhythm, RoleProfiles,
Ambition, DimensionPolicy, GoalResumption, BotDrives, GoalMomentum). Šest paralelních auditů
(výpravy, stavění, ekonomika+sociální, produkce, přežití+pohyb, průřez), P0 nálezy ověřeny
druhým čtením. Navazuje na NALEZY-TESTOVANI.md (2026-07-20) — již opravené a tam popsané
nálezy se zde neopakují.

## Shrnutí

**~49 nálezů: 4× P0, 25× P1, 20× P2.** Kořen většiny z nich není v jednotlivých cílech, ale
v sedmi opakujících se vzorcích:

1. **Binární brána zabíjí běžící cíl** — stejný vzorec, který minule zabíjel stavby domů,
   žije dál ve ~13 dalších cílech (portál, vaření, ohrada, prodej, krádež, spánek…).
   `pause/resume` mašinerie nepomáhá: pobídka je multiplikátor utility, a 0 × pobídka = 0.
2. **Závody dokončení** — `finished()` sepne dřív, než tick stihne závěrečný zápis
   (zazděný bot, nezapsaná krádež, neoslavený drak), nebo `stop()`/`start()` nechá/smaže
   stav, který neměl (stale session latch, smazaný blacklist).
3. **Dvouaktérové protokoly bez párování** — kupec nevidí vlastní claim, prodejce pozná
   platbu podle delty zůstatku, Restock↔Supply pumpují tytéž bloky a nafukují BOM.
4. **Reflexy nejsou vyňaty ze stacku multiplikátorů** — nabuzená práce (role × rytmus ×
   ambice × zaměstnání ≈ až 10×) přebije jídlo při hladu 0; pudy jídlo dál tlumí ×0,4.
5. **utility() se side-efekty** — dekrementy cooldownů v utility() rozbíjí
   `/botalive goal` (zrychluje je) i vynucený cíl (zmrazuje je).
6. **Hotbar vs. batoh** — tři cíle hledají item jen v hotbaru, zatímco sourozenci umí
   `equipItem` pull (krotitel, dosev polí, vozík).
7. **Mrtvé mechaniky** — hradby se nikdy nepovažují za postavené, enchant stůl se nikdy
   nepoloží, městský sklad má truhlu na špatné souřadnici, na chleba neexistuje recept,
   cesty (`settlement-roads`) nikdo neboostuje.

---

## Opraveno 2026-07-25 (tatáž session)

Všech **1232 testů prochází**. Opravené nálezy jsou v textu níže ponechány pro kontext.

| Nález | Oprava | Soubory |
|---|---|---|
| P0-1 Survive zamrznutí ≤ 6 HP | kritická větev jen s hrozbou/hořením; hořící bot bez hrozby aktivně utíká z ohně (navigace `awayFrom` + krok k bezpečnému sloupci) | [SurviveGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/SurviveGoal.java) |
| P0-2 hráč-útočník neviditelný | `freshAggressor`: hráči s čerstvou ENEMY vzpomínkou + střelci do 32 bloků (v utility i ticku) | SurviveGoal.java |
| P0-3 portál se nedostaví | utility drží fáze GO/BUILD/LIGHT/ENTER (26); start() naváže na rozdělanou fázi ≤ 48 bloků od staveniště | [NetherGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/NetherGoal.java) |
| P0-4 mrtvý trh | `MarketBoard.dealOfBuyer` + `releaseClaim`; BuyGoal drží utilitu 22 nad vlastním dealem, start() na něj naváže, stop() vrací nabídku na nástěnku | [MarketBoard.java](botalive-core/src/main/java/dev/botalive/core/economy/MarketBoard.java), [BuyGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuyGoal.java) |
| Sell publikum zabíjí běžící prodej | brány (publikum, přebytek) jen pro vstup; běžící prodej drží `active` příznak do finished() | [SellGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/SellGoal.java) |
| Steal bez následků | transakce OPEN/LOOT drží utilitu 30 → `onLooted` (kniha zločinů, cooldown) doběhne; stop() uprostřed = cooldown 2400 | [StealGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/StealGoal.java) |
| Brew vsázka osiří | LOAD/WAIT drží utilitu 10; resume() naváže místo přeplánování | [BrewGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/BrewGoal.java) |
| Pen stádo vs. rozdělaná stavba | běžící stavba měří zmrazený obdélník ze start() | [PenGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/PenGoal.java) |
| Combat blacklist cykly | TTL 30 s místo mazání ve start(); bossové (drak, wither) vyloučeni z agresorské větve | [CombatGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CombatGoal.java) |
| Socialize socha | ztráta cíle ukončuje okamžitě (lingerTicks = MAX/2) | [SocializeGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/SocializeGoal.java) |
| MaterialDepot mimo truhlu | `Blueprints.storageChest(granary(size))` jako StashGoal + null-guard settlements | [MaterialDepot.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/MaterialDepot.java) |
| rezerva 176 < 177/322 | bezdomovcova rezerva = `blocksNeeded(tier, config) + 24` (stejná geometrie jako brána stavby) | [ContainerService.java](botalive-core/src/main/java/dev/botalive/core/inventory/ContainerService.java) |
| reflexy vs. stack vah | eat/survive/creeper-dodge/drink vyňaty z vah rolí/rytmu/ambicí/pudů… (dimenze a hystereze platí dál) | [Brain.java](botalive-core/src/main/java/dev/botalive/core/ai/Brain.java) |
| Shelter zazdění + brány | finishShelter/finishDemolish ve stejném ticku jako poslední task; brány jen pro zahájení (běžící stavba drží) | [BuildShelterGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuildShelterGoal.java) |
| Communal stale session | `session = null` ve finishProject i giveUp (stop dál drží pro návrat) | [CommunalBuildGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CommunalBuildGoal.java) |
| Sleep rozbitá postel + „posedlé" tělo | validace vzpomínky proti světu (+ forget); nový `BotActions.leaveBed()` ve stop(); SLEEPING drží utilitu přes okno 23000 | [SleepGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/SleepGoal.java), [BotActions.java](botalive-core/src/main/java/dev/botalive/core/network/BotActions.java) |
| Recover stání u lávy | stall detekce (vzor CollectItems): bez pokroku 200 ticků → finishSweep | [RecoverItemsGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/RecoverItemsGoal.java) |
| WarRaid věčný pochod + NPE | rozpočet MARCH 1200 ticků → vzdát + clearRaidCall; null-guard worldView v utility | [WarRaidGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/WarRaidGoal.java) |
| Smelt mikro-seance + ucpaná pec | cooldown platí i pro výběr; pending TTL 10 min; plný batoh marker nemaže (retry za 60 s); vsázka v jiné peci marker nepřepíše | [SmeltGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/SmeltGoal.java) |
| Granary pořadí a prázdné pochůzky | výběr 40 (nad celým pásmem steal); prázdný výběr → backoff 4000–6000; null-guard settlements | [GranaryGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/GranaryGoal.java) |
| hotbar-only trio | Tame (equipMatching + hasItem kandidát), Farm REPLANT (equipItem pull), Minecart (equipItem pull) | TameGoal.java, FarmGoal.java, MinecartRideGoal.java |
| dračí trofej jen v celebrate() | `lastFightMs` přežívá start() i smrt; utility se po boji vrátí dozapsat trofej (okno 30 min, latch = existující trofej) | [DragonFightGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/DragonFightGoal.java) |
| EndOuter zombie fáze | smrt/odchod z Endu uprostřed výpravy → `resetTrip()` (fáze i souřadnice); HOME_GO detekuje průchod gatewayí (`!onOuterIslands` → finish) | [EndOuterGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/EndOuterGoal.java) |
| EndOuter perlová smyčka | HOME_THROW bez perel čeká u gateway (perla může přibýt z lovu), po minutě výpravu uspí s cooldownem – konec 2tikové smyčky | EndOuterGoal.java |
| WitherFight slepá pokládka lebek | fronta se posouvá podle SVĚTA (materialAt na opoře), wither entita = okamžitý ústup, 12 pokusů → finish | [WitherFightGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/WitherFightGoal.java) |
| DrinkPotion fire-res mrtvá větev | 95 → 300 (nad survive-burning ≤ 292, pod creeper 400) | [DrinkPotionGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/DrinkPotionGoal.java) |
| CreeperDodge bez EdgeGuard | útěkový vektor obalen applyLethal/apply jako u sourozenců | [CreeperDodgeGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/CreeperDodgeGoal.java) |
| TradeGoal mint | `TradeReport.emeraldsSpent` + symetrický `wallet().withdraw` při nákupu jídla za smaragdy | [TradeGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/TradeGoal.java), [TradeService.java](botalive-core/src/main/java/dev/botalive/core/trade/TradeService.java) |
| Nether RETURN cyklí k nedosažitelnému portálu | per-trip blacklist `failedReturnPortals` (GO timeout s afterGo=ENTER) – kroky 2–4 dostanou šanci | NetherGoal.java |
| Stronghold studený sken + repath thrash | prefetch okolí při vstupu do SEARCH + druhý průchod skenu; TRAVEL cíl se počítá jednou | [StrongholdSeekGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/StrongholdSeekGoal.java) |
| Reconcile given==0 | křivda zůstává otevřená (settleAmends jen s darem) | ReconcileGoal.java |
| Mine stop() nechává stale cíle | targetBlock/targetCandidates/travelTarget/digTaskInPlan se ve stop() čistí (pause je drží dál) | MineGoal.java |
| DimensionPolicy NETHER neúplná | dorovnáno s END: minecart, rob, reconcile, mine, smith, shelter, guard | DimensionPolicy.java |
| CombatController vs. goaly prahy ústupu | koeficient 8 → 6 – konec pásma „bojového limba" | CombatController.java |
| Guard prázdné waypointy | 16 po sobě jdoucích null stanovišť → cooldown 1200 | GuardGoal.java |
| Farm *_PLANT equip smyčka | 20 marných pokusů → zpět do FIND (záhon i pole) | FarmGoal.java |
| kapacitní brány Farm/Fish/Hunt | freeSlots ≤ 1 → vstup 0 (rozdělaná práce dojede; vzor MineGoal) | FarmGoal.java, FishGoal.java, HuntGoal.java |
| Hunt bez inProgress | živý cíl drží utilitu – nahnaná kořist se dotáhne (ztrátu řeší lostTargetTicks) | HuntGoal.java |
| Craft brána přísnější než planner | + kovová větev (ingoty/diamant/netherit) – brnění a nůžky i bez dřeva | CraftGoal.java |
| Compost žere krmivo a osivo | WHEAT vyňata z kompostu; rezerva 9 pšeničných semínek na založení pole | CompostGoal.java |
| EndTravel brány u portálu | hold `active` (start→stop) – výbavové prahy jen pro zahájení; pause() příznak drží | EndTravelGoal.java |
| MaintainHome díra ve zdi přes noc | rozdělaná výměna drží utilitu; blocksRelocation při růstu/výměně; stop čistí příznaky | MaintainHomeGoal.java |
| settlement-roads sirotek | boost BUILDER 1.8, MASON 2.0, SCOUT 1.6 + doplněno zrcadlo RoleCoverageTest | RoleProfiles.java, RoleCoverageTest.java |
| Supply vysává bezdomovce | bez HOME type=house se stavební bloky nedarují (rezerva na vlastní dům) | SupplyGoal.java |
| BuildGuard stráž uvnitř sálu + cizí svět | odstup 3 → 7 (mimo 7×9 kostel), guardedSite porovnává svět sídla | BuildGuardGoal.java + test |

**Zbývá z tohoto auditu (neopraveno):** Guard↔Home noční oscilace (návrh — hlídkám se nikdy neobnoví energie); Eat vestoje; SellGoal refund zaplacené-zaniklé nabídky; detekce platby deltou zůstatku (potřebuje transakční event); Restock↔Supply BOM inflace (návrh; Supply-bezdomovec větev už spadla); Steal sken vlastního sídla; BuildHouse sólo resume + stale latch (návrh); SettlementWall isBarrier + PROVISION žere hradbu (návrh — mění sémantiku Enclosure); BarrierWorker DONE vs. došel materiál; pšeničný řetěz – recept na chleba; utilitySnapshot side-efekty + forced-goal zmrazení (návrh — cooldowny na wall-clock deadline); NETHERITE poslední míle; walls pause politika; resumption decay vs. noc.

---

## Živý test 2026-07-25 (test-server/, 10 botů, 40 min + restart)

Paper 26.1.2-74, čistý svět, `bots.auto-spawn: 10`, vzorky à 5 min přes RCON
(`test-server/rcon.py`), SQL nad `plugins/BotAlive/botalive.db`.

**Potvrzené opravy naživo:**

- **Trh žije (P0-4):** 5 dokončených bot↔bot obchodů za 30 min (5× chleba;
  minulý test: 2 transakce za 40 min se 46 boty). Vidět kamarádská sleva
  (Matej 10,0 místo 12,0) i spálený 10% poplatek – ekonomika má poprvé propad.
  Hladová Lucka **koupila** jídlo místo krádeže – zamýšlené pořadí funguje.
- **Survive nefrizuje (P0-1):** boti v `survive` se mezi vzorky HÝBOU a po
  odeznění hrozby cíl pouští řízení – Zdenek na ❤1 🍗0 se s úsvitem vrátil
  k práci (dřív by stál na místě navěky). Reflexní vlny druhé noci
  (survive/recover/eat napříč 10 boty) se do rána srovnaly na ❤20.
- **Stabilita:** 0 výjimek, 0 ERROR, 0 `selhal` za celý běh včetně restartu;
  TPS 20,0 nepřetržitě. Restart obnovil všech 10 botů z DB na uložených
  pozicích. Vesnice Ondrovice: 4 členové, 1 dům, střídání starosty.
- Živý histogram: mine/house/home/sell/buy/collect/craft/smelt/hunt/explore/
  fish/stash/guard/eat/recover/steal/creeper-dodge – žádný mrtvý pilíř.

**Nové nálezy ze živého běhu (opraveno v tomtéž buildu):**

- **Pudová suprese dusila nákup jídla:** `buy` byl v GOAL_DRIVE jako SOCIAL
  tier → hlad ho tlumil ×0,66 přesně ve chvíli, kdy měl vyhrát. Vyňat z mapy
  + vyhladovělá větev (hunger ≤ 6) dostala tlak +8/bod
  ([BotDrives.java](botalive-core/src/main/java/dev/botalive/core/ai/BotDrives.java),
  [BuyGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuyGoal.java)).

**Otevřené (návrh – kalibrace zoufalství):** role-boostnutá práce (kopáč
mine ×2,5 ≈ 71 vážené) pořád přebíjí VŠECHNY cesty k jídlu vyhladovělého
bota (steal 37, buy 0 mimo 48bl rádius nabídek, hunt bez zvěře) – bot pak
dře na ❤1, dokud ho něco nezabije (smrt = jediný reset hladu). Chce to
zoufalé akvizici jídla dát reflexní pásmo (výjimka z vah při hunger ≤ 4),
ne další číselné záplaty.

---

## P0 — zamrznutí a trvale mrtvé pilíře

### P0-1 Survive: bot s ≤ 6 HP bez viditelné hrozby zamrzne navěky

[SurviveGoal.java:64](botalive-core/src/main/java/dev/botalive/core/ai/goals/SurviveGoal.java) —
`if (burning || health <= CRITICAL_HEALTH) return panic;` je **bezpodmínečné** (192–232 dle
chybějícího zdraví). Oprava „bez hrozby uvolni řízení“ (komentář :67–74) pokryla jen pásmo
(6; 12] HP. Mechanismus:

1. health ≤ 6 → utility 192+ přebije vše (eat max 118, healing lektvar 80).
2. `tick()` bez hrozby jen `safeTicks++` — **žádný pohyb, žádné jídlo** (:153–155).
3. `finished()` chce `health >= 8` (:191) — bez food ≥ 18 regenerace neběží → nikdy.
4. Watchdog je slepý: počítá nehybnost jen při `navigator.navigating()`
   ([BotImpl.java:1591](botalive-core/src/main/java/dev/botalive/core/bot/BotImpl.java)).

Bot s chlebem v batohu stojí na místě, dokud ho něco nezabije; hořící bot bez moba uhoří
vestoje (tick bez hrozby z ohně nevyleze). **Oprava:** kritickou větev podmínit
`threatNear || burning`, hoření řešit útěkem z ohně v tick(), finished() uvolnit i při
`safeTicks > 60 && !threatNear` (zotavení předat eat/drink).

### P0-2 Survive: hráč-útočník je pro útěk neviditelný

[SurviveGoal.java:88](botalive-core/src/main/java/dev/botalive/core/ai/goals/SurviveGoal.java)
`nearest(pos, 24, TrackedEntity::isHostile)` (hráč není hostile) + :92 filtr `!e.isPlayer()`
+ [AbstractGoal.java:263](botalive-core/src/main/java/dev/botalive/core/ai/goals/AbstractGoal.java)
`recentAggressor` hráče vylučuje. Hráč srazí bota pod 6 HP → survive 192+ vytlačí PvP obranu
(max 56), ale hrozbu nevidí → bot se nebrání, neutíká, stojí (P0-1) — **exploit: sochu stačí
dobít**. Skeleton střílející z 25–32 bloků je tatáž díra (sken 24 < dohled 32).
**Oprava:** do threatNear/tick zahrnout `pvp.threat(bot.id())` a střelce do 32 bloků.

### P0-3 NetherGoal: bot vlastní portál nikdy nedostaví

[NetherGoal.java:200–203](botalive-core/src/main/java/dev/botalive/core/ai/goals/NetherGoal.java)
— brána `knownPortalNearby ∨ (buildPortals ∧ canBuildPortal)` běží v utility() bez ohledu na
fázi; žádná větev „phase ∈ {GO, BUILD, LIGHT, ENTER} → drž“ neexistuje (jediná výjimka je
`dimension == NETHER → 30`). Mechanismus:

1. BotNeeds sbírá obsidián jen do 14 → BUILD startuje s přesně 14 kusy.
2. Po položení 1. bloku je v batohu 13 → `canBuildPortal` (≥ 14) padne; PORTAL vzpomínka
   se zapisuje **až po zapálení** (:427–431) → `knownPortalNearby` taky false.
3. utility 0 → Brain cíl opustí před hysterezí; pause/resume z commitu N1 nepomůže
   (pobídka násobí nulu), torzo není rám (`PortalScanner.isFrame` chce kompletní rám)
   → utility se už nikdy nezvedne. Kolaps při LIGHT zabije i hotový nezapálený rám:
   14 položeno, 0 v batohu, bot s křesadlem 3 bloky od rámu má utilitu 0 navždy.

Bot bez zděděné PORTAL vzpomínky se do Netheru vlastními silami nedostane; ambice NETHERITE
i DRAGON_SLAYER stojí. **Oprava:** utility větev pro rozjeté fáze (obdoba IN_NETHER_UTILITY),
vstupní brány vyhodnocovat jen v PREPARE/FIND_PORTAL.

### P0-4 BuyGoal: kupec nevidí vlastní zamluvený obchod — trh nedokončí žádný obchod

[BuyGoal.java:59](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuyGoal.java)
`findWantedOffer(...).isEmpty() → return 0` +
[MarketBoard.java:152](botalive-core/src/main/java/dev/botalive/core/economy/MarketBoard.java)
`claim()` nabídku **odstraní z nástěnky** (deal jde do `dealsBySeller`). Žádné
`dealOfBuyer()` v celém kódu neexistuje (grep ověřen). Mechanismus:

1. decide T: nabídka viditelná → start() → claim ji sundá z nástěnky.
2. decide T+5 ticků: `findWantedOffer` prochází jen volné nabídky → bez druhé kompatibilní
   nabídky utility 0 → cíl opuštěn; „buy“ není v RESUMABLE, stop() claim neuvolní →
   claim visí do TTL 90 s.
3. Prodejce vidí `pendingDeal` → utility 22 → jde k pultu → čeká `BOT_HANDOVER_TICKS`
   1000 (50 s) na kupce, který nepřijde → withdraw + cooldown 2400.

Trh s jedinou aktivní nabídkou (běžný stav) nedokončí žádný bot↔bot obchod; funguje jen
náhodou, když po celou dobu visí druhá nabídka. To vysvětluje „2 transakce trhu“ z minulého
měření i po rebalancu prahů. **Oprava:** `MarketBoard.dealOfBuyer(UUID)` a v utility ho
kontrolovat jako první (zrcadlo SellGoal:102); ve stop() uvolnit claim při odchodu bez zboží.

---

## P1 — vzorec „brána zabíjí běžící cíl“ (pokračování známého P0 vzorce)

| # | Cíl | Brána, která zhasne za běhu | Důsledek | Klíčové řádky |
|---|---|---|---|---|
| 1 | **BrewGoal** | `BrewPlanner.next(state)` čte jen inventář — naložené lahve ve stojanu nevidí; během WAIT (~400 t) padne na null | vsázka (lektvary + wart + blaze) osiří ve stojanu, recovery jen náhodná | BrewGoal.java:84–90, :322–341 |
| 2 | **PenGoal** | `resolvePen()` (≥ 3 zvířata v buňce) se testuje PŘED `inProgress` | zvířata se během stavby toulají → napůl postavené ohrady, spotřebované plaňky; „pen“ navíc není v RESUMABLE | PenGoal.java:94–99, :341 |
| 3 | **SellGoal** | publikum ≤ 16 bloků od AKTUÁLNÍ pozice, každý decide | cestou k pultu bot vyjde z hloučku → withdraw nabídky (bez cooldownu) → ping-pong; nabídka žije sekundy, kupec ji vidí až do 48 bl | SellGoal.java:113–117, :158–159 |
| 4 | **StealGoal** | `starving ∨ destitute` — ukradené jídlo bránu zhasne během `waitTicks` čekací kosmetiky | `reportTheft`/`remember`/cooldown se nezavolá → **krádež se v běžném průběhu nikdy neodhalí**, celý řetěz zločin→vztek→diplomacie je mrtvý | StealGoal.java:60–64, :144–147, :196–197 |
| 5 | **RobGoal** | `findVictim()` — mrtvá oběť zmizí z trackingu dřív, než uplyne 40t okno přechodu do LOOT | loot, ROB_SUCCESS i cooldown 6000 nastanou jen když opodál stojí další oběť; jinak okamžitý restart loupeže | RobGoal.java:64–66, :114–127 |
| 6 | **BuildShelterGoal** | noc/bouřka, bloky > 0, katastr — vše běží i během stavby | torza nouzových budek, bot polouzavřený venku (kombinuje se s P1 „zazdění“ níže) | BuildShelterGoal.java:93–116 |
| 7 | **CampGoal** | `hasFireSource` i ve fázi CAMP — položení posledního ohně/pochodně bránu zhasne | bot přijde o item i o tábor vteřiny po zapálení; SETUP navíc není krytý `camping` oknem | CampGoal.java:76–78, :61–63, :178–181 |
| 8 | **MaintainHomeGoal** | časové okno (≥ 11500 → 0) i během výměny bloku; „maintain“ není v RESUMABLE | tickUpgrade nejdřív těží, pokládá další tick → soumrak/boj mezi nimi = **díra ve zdi přes noc** | MaintainHomeGoal.java:139–141, :647–672 |
| 9 | **HuntGoal** | zvíře v dohledu — kořist v panice uteče za viewDistance | stop() uprostřed honu, zvíře s ½ HP, bez dropu, bez cooldownu → hned nový lov | HuntGoal.java:62–69 |
| 10 | **EndTravelGoal** | výbavové prahy (16 šípů, 32 bloků, 5 jídel) i ve fázi FILL/ENTER u portálu | bot s okama v ruce odejde od portálu, protože „má málo bloků na mosty“, které už nepotřebuje | EndTravelGoal.java:102–104, :136–139 |
| 11 | **SleepGoal** | okno utility končí ve 23000, buzení až < 12000 | v 23001+ cíl vypadne a **tělo zůstane serverově v posteli** — viz P1 „leave-bed“ níže | SleepGoal.java:55, :145 |

**Společná oprava vzorce:** každý cíl s vícetickovou prací potřebuje v utility() větev
`inProgress → drž kladnou utilitu do finished()` a vstupní brány vyhodnocovat jen pro
zahájení (vzor: oprava BuildHouseGoal/CommunalBuildGoal z 2026-07-20).

---

## P1 — závody dokončení a stale stav

### Stavění

- **BuildShelterGoal — bot se zazdí bez záznamu a bez cesty ven.**
  `finished()` = `plan.isEmpty() && current == null` sepne hned po posledním
  `PlaceBlockTask`; `finishShelter()` (zápis HOME type=shelter + cooldown) se volá jen když
  je fronta prázdná už NA VSTUPU ticku → nikdy. Bez HOME záznamu nefunguje `trappedInShelter`
  ani `canDemolishShelter` → bot, kterému šel poslední blok do stropu, je **trvale zapečetěný
  v budce bez dveří** (utility 0, bloky došly). Demolice má stejný závod.
  [BuildShelterGoal.java:183](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuildShelterGoal.java), :151–156, :339, :197–230.
- **CommunalBuildGoal — stale `session` latch obchází brány navždy.** stop(), finishProject()
  ani giveUp() `session` nenulují (nuluje až start(), ale utilita se čte dřív) → od druhého
  projektu latch `inProgress` přeskakuje den/materiál/`hasRequiredItems` → sýpka se postaví
  a zaeviduje **bez povinných truhel**. [CommunalBuildGoal.java:123](botalive-core/src/main/java/dev/botalive/core/ai/goals/CommunalBuildGoal.java), :656–708.
- **BuildHouseGoal — sólo stavba nemá resume identitu.** Zakladatel/samotář: start() vše
  smaže, `localScan` počítá vlastní rozestavěné zdi jako překážky (`SiteFinder.cost` s
  prázdným setem) → po každém přerušení se dům zakládá **na novém místě**; krajina torz.
  [BuildHouseGoal.java:148–163](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuildHouseGoal.java), :394–395.
- **SettlementWallGoal/CommunalBuildGoal — PROVISION žere vlastní hradbu.** `BarrierGather`
  filtruje jen `isStructureProtected` (hranoly ±9 kolem parcel/projektů) — obvod hradby
  chráněný není → opravář těží 2 sloupce hradby, aby 1 postavil.
  [BarrierGather.java:79–83](botalive-core/src/main/java/dev/botalive/core/ai/goals/BarrierGather.java),
  [SettlementService.java:1197–1203](botalive-core/src/main/java/dev/botalive/core/settlement/SettlementService.java).

### Boj a přežití

- **CombatGoal — start() maže blacklist nedosažitelných cílů, který utility právě použila.**
  Mob A za zdí + mob B dál: A po 200 t → blacklist → finished; utility vybere B → start()
  **blacklist smaže** → findTarget zase najde A → nekonečné 10s cykly; B se nikdy nebojuje.
  Set navíc nemá TTL — dřív nedosažitelný agresor je neatakovatelný do příštího start().
  [CombatGoal.java:65](botalive-core/src/main/java/dev/botalive/core/ai/goals/CombatGoal.java), :103–107, :222–235.
- **DrinkPotionGoal — fire-res větev (95) je mrtvý kód.** Při hoření je survive vždy ≥ 140
  → jediný scénář, pro který větev existuje, ji stoprocentně přebije. Bot s lektvarem
  odolnosti uhoří. [DrinkPotionGoal.java:52–54](botalive-core/src/main/java/dev/botalive/core/ai/goals/DrinkPotionGoal.java).
- **SleepGoal — chybí „vstát z postele“.** stop()/pause() neposílá leave-bed paket (v celém
  core neexistuje) → cíl přebitý/vypršelý nechá bota serverově spát; nový cíl hýbe botem,
  kterého server drží v posteli. Každé ráno ~1 min „posedlého“ bota; noční přepad = boj
  z peřin. [SleepGoal.java](botalive-core/src/main/java/dev/botalive/core/ai/goals/SleepGoal.java) + AbstractGoal.java:93–96.
- **SleepGoal — zapamatovaná postel se nikdy nevaliduje.** Paměťová větev zkratuje sken
  náhradních postelí; zničená/obsazená postel → 5 kliků → cooldown 1200 → znovu tatáž
  vzpomínka, celou noc, každou noc. [SleepGoal.java:181–183](botalive-core/src/main/java/dev/botalive/core/ai/goals/SleepGoal.java), :135–137.
- **RecoverItemsGoal — SWEEP bez detekce nedosažitelného itemu** (přesně vzorec opravený
  v CollectItems): drop v lávě/na římse → navigator v backoffu tiše odmítá, item je vidět →
  `sweepEmptyTicks` neroste → bot stojí u lávy až 6 minut despawn okna.
  [RecoverItemsGoal.java:143–153](botalive-core/src/main/java/dev/botalive/core/ai/goals/RecoverItemsGoal.java).
- **GuardGoal ↔ ReturnHomeGoal — noční přetahovaná.** Hlídkový prstenec (28 bl) leží za
  nočním prahem návratu domů (12 bl); home s únavou (×1,4) a nocí (×1,6) přebíjí guard →
  bot celou noc pendluje prstenec↔postel. A protože guard vytlačuje spánek, energie se nikdy
  neobnoví → únavový bonus platí trvale. [GuardGoal.java:73](botalive-core/src/main/java/dev/botalive/core/ai/goals/GuardGoal.java),
  [ReturnHomeGoal.java:72](botalive-core/src/main/java/dev/botalive/core/ai/goals/ReturnHomeGoal.java).
- **WarRaidGoal — MARCH bez rozpočtu.** Nedosažitelné shromaždiště → navigateTo tiše odmítá
  (backoff) → bot stojí s utilitou 24–40 po celé TTL rozkazu (5 min), opakovaně.
  [WarRaidGoal.java:134–146](botalive-core/src/main/java/dev/botalive/core/ai/goals/WarRaidGoal.java).
- **SocializeGoal — družný bot zamrzne jako socha.** Ztráta cíle nastavuje `lingerTicks = 200`,
  ale limit pro SOCIABILITY ≥ 0,5 je ≥ 200 → finished() nikdy; retarget neexistuje; utilita
  drží, dokud KDOKOLI stojí do 24 bl — tedy uprostřed vesnice trvale.
  [SocializeGoal.java:66](botalive-core/src/main/java/dev/botalive/core/ai/goals/SocializeGoal.java), :108–109.

### Výpravy

- **Dračí trofej se zapisuje jen v celebrate() — propásnuté okno = End navždy uzavřený.**
  Zátah vyprší / draka dorazí jiný bot / smrt těsně po smrtící ráně → `fightStarted` je
  false (start() ho maže) → celebrate nedosažitelný → `EndKnowledge.dragonSlain` navždy
  false → **EndOuterGoal (elytra) se nikdy nespustí** a DRAGON_SLAYER frontier žene bota na
  věčně marné výpravy. [DragonFightGoal.java:191–203](botalive-core/src/main/java/dev/botalive/core/ai/goals/DragonFightGoal.java), :108;
  [EndOuterGoal.java:154](botalive-core/src/main/java/dev/botalive/core/ai/goals/EndOuterGoal.java).
- **EndOuterGoal — zombie fáze přežije smrt a odchod z Endu.** Fáze se nemaže ve stop() ani
  po smrti; větev `phase != IDLE → return 30` je před všemi branami → při další návštěvě
  Endu (o hodiny později) tick pokračuje se zatuchlými souřadnicemi z vnějších ostrovů →
  void-mosty z hlavního ostrova, HOME_GO bez stropu. [EndOuterGoal.java:148–150](botalive-core/src/main/java/dev/botalive/core/ai/goals/EndOuterGoal.java), :881–897.
- **EndOuterGoal — 0 perel na vnějších ostrovech = 2tiková smyčka HOME_GO↔HOME_THROW** bez
  východiska (počítadlo vzdání se nestihne inkrementovat; tripDeadline HOME fáze vynechává).
  [EndOuterGoal.java:327–332](botalive-core/src/main/java/dev/botalive/core/ai/goals/EndOuterGoal.java), :795–848.
- **WitherFightGoal — pokládka lebek se neověřuje světem** (klik týž tick jako lookAt, server
  ho zahazuje — přesně důvod, proč PlaceBlockTask má AIM fázi) a prázdná fronta spouští boj
  bez ohledu na to, kolik lebek reálně stojí → ztráta lebek (2,5% drop) na opuštěném oltáři,
  boj s nikým, 40min cooldown. [WitherFightGoal.java:254–274](botalive-core/src/main/java/dev/botalive/core/ai/goals/WitherFightGoal.java).
- **Boss fighty přebírá generický CombatGoal.** Každý zásah bosse zapisuje ENEMY →
  `recentAggressor` větev CombatGoalu (obchází isHostile výluku bossů) dává u bosse na
  5–15 bl ~65–95 > dragon-fight 55×1,15 i wither 60×1,15 → většinu souboje vede cíl bez
  úhybu před dechem, priority krystalů a wither fází.
  [CombatGoal.java:230–235](botalive-core/src/main/java/dev/botalive/core/ai/goals/CombatGoal.java),
  [ServerEventListener.java:124](botalive-core/src/main/java/dev/botalive/core/network/ServerEventListener.java).

### Ekonomika a produkce

- **SellGoal — hráč zaplatí, deal vyprší TTL, peníze zůstanou botovi.**
  `PLAYER_HANDOVER_TICKS` 1800 (90 s) == `DEAL_TTL_MS` 90 000: přerušený prodejce (boj přes
  zbytek TTL) → prune() deal smaže → `paidOfferId` míří na mrtvé id, refund neexistuje.
  „Bot mě okradl“ pro reálného hráče. [SellGoal.java:56](botalive-core/src/main/java/dev/botalive/core/ai/goals/SellGoal.java), :386;
  [MarketBoard.java:226](botalive-core/src/main/java/dev/botalive/core/economy/MarketBoard.java).
- **Platba se detekuje deltou zůstatku** (`balance >= baseline + cena`) u SellGoal i
  EmploymentService (baseline drží 3 min) — jakýkoli souběžný příjem (villager trade, jiný
  prodej, /pay) „zaplatí“ cizí obchod/smlouvu. [SellGoal.java:289](botalive-core/src/main/java/dev/botalive/core/ai/goals/SellGoal.java), :383;
  [EmploymentService.java:314](botalive-core/src/main/java/dev/botalive/core/employment/EmploymentService.java), :380.
- **TradeGoal — perpetuum mobile.** Prodej vesničanovi mintuje peníze (`emeraldsGained ×
  10`), ale smaragdy zůstávají v batohu; nákup jídla za smaragdy peníze nepálí → každý
  smaragd protočený přes vesničana = +10 vyražených peněz. Hlavní pumpa známé inflace.
  [TradeGoal.java:142–144](botalive-core/src/main/java/dev/botalive/core/ai/goals/TradeGoal.java),
  [TradeService.java:91–96](botalive-core/src/main/java/dev/botalive/core/trade/TradeService.java).
- **MaterialDepot — u zvětšeného (městského) skladu míří na blok, kde truhla není.**
  `chest()` počítá legacy `bedSpot` (rotace 4×4) místo `Blueprints.storageChest(granary(size))`;
  pro facing SOUTH = vzduch uvnitř sálu (EAST funguje jen náhodou přes druhou půlku
  dvojtruhly). Horníci sklad plní (StashGoal počítá správně), ale **celý odběr (Restock,
  Supply, PROVISION stavitele) je tiše mrtvý.** Nezávisle potvrzeno dvěma audity.
  [MaterialDepot.java:50](botalive-core/src/main/java/dev/botalive/core/ai/goals/MaterialDepot.java),
  [Blueprints.java:491](botalive-core/src/main/java/dev/botalive/core/build/plan/Blueprints.java).
- **Restock↔Supply — tytéž bloky pendlují a nafukují BOM.** Bezdomovec bez podmínky na
  aktivní stavbu vybere 48–64 bloků; Supply je nad prahem 32 vrátí do téže truhly a
  **připíše jako příspěvek** (`contribute`) → pár cyklů „papírově dozásobuje“ projekt,
  sběrači přestanou nosit. [RestockGoal.java:138–139](botalive-core/src/main/java/dev/botalive/core/ai/goals/RestockGoal.java),
  [SupplyGoal.java:106](botalive-core/src/main/java/dev/botalive/core/ai/goals/SupplyGoal.java), :172–174.
- **Rezerva stavebních bloků 176 < spotřeba domu VESNICE (177) i MĚSTA (322).** Komentář
  „~153 bloků“ platí pro 7×7×3; VESNICE má wallHeight 4, MĚSTO 9×9×5. Každá návštěva truhly
  ořeže bezdomovce na 176 → brána zahájení domu je od tieru VESNICE nedosažitelná — přesně
  deadlock, který oprava 32→176 řešila, jen o tier výš.
  [ContainerService.java:33–34](botalive-core/src/main/java/dev/botalive/core/inventory/ContainerService.java),
  [StructureSizer](botalive-core/src/main/java/dev/botalive/core/build/plan/StructureSizer.java).
- **SmeltGoal — collectReady() obchází cooldown a nedosažitelná pec cyklí.** GO fail →
  `finish(1800)`, ale `pendingFurnace` se nečistí a collect větev cooldown ignoruje → u
  kováře (×2,5) utility ~40–55 každé rozhodnutí → mikro-seance smelt→fail→smelt ovládnou
  bota, dokud cesta nezačne existovat. [SmeltGoal.java:58–61](botalive-core/src/main/java/dev/botalive/core/ai/goals/SmeltGoal.java), :127–133, :210–213.
- **EnchantGoal — jediný stanicový cíl bez StationPlacement.** Stůl se craftí
  (kniha + 3 diamanty + 4 obsidián), ale nikdy nepoloží (Smelt/Smith/Brew/Compost placement
  mají) → samotář/enchanter mimo sídelní projekt cykluje FIND→finish(2400) navěky se stolem
  v batohu. [EnchantGoal.java:73–79](botalive-core/src/main/java/dev/botalive/core/ai/goals/EnchantGoal.java).
- **SettlementWallGoal — hradba z běžných bloků se nikdy nepočítá jako postavená.**
  `Enclosure.isBarrier` zná jen `*_WALL/_FENCE/_FENCE_GATE`, ale `BarrierWorker` staví
  z plných kvádrů → assess hlásí `missing` na hotové hradbě → detekce hotovosti nikdy
  nesepne, noční `BarrierRepair.isDamaged` je trvale mrtvý kód, plan naplánuje **druhé patro
  na hotovou hradbu** (dvojnásobná spotřeba, roztřepená koruna), pak věčný 5min claim cyklus
  naprázdno. [Enclosure.java:279–286](botalive-core/src/main/java/dev/botalive/core/settlement/Enclosure.java),
  [SettlementWallGoal.java:134](botalive-core/src/main/java/dev/botalive/core/ai/goals/SettlementWallGoal.java),
  [BarrierRepair.java:34](botalive-core/src/main/java/dev/botalive/core/ai/goals/BarrierRepair.java).

### Průřez

- **Reflex eat není vyňat ze stacku multiplikátorů.** Práce dokáže stack ~10× (role 2,5 ×
  rytmus 1,4 × frontier 1,6 × worker 1,6 × momentum × hystereze); MINER s FULL_IRON ≈ 148 >
  eat 118 při hladu 0 → bot kope, dokud ho starvation nesrazí ke kritickému zdraví, pak
  dokola. Mine navíc nemá žádnou health/food bránu.
  [Brain.java:237–260](botalive-core/src/main/java/dev/botalive/core/ai/Brain.java),
  [EatGoal.java:45](botalive-core/src/main/java/dev/botalive/core/ai/goals/EatGoal.java).
- **Pudy (drives) tlumí eat ×0,4 z pouhého počtu mobů do 12 bl** (bez ohledu na
  dosažitelnost), zatímco 38 z 65 cílů mapovaných není → arbitráž se obrací (hladový GUARDIAN
  v noci: guard 52 > eat 47). [BotDrives.java:41](botalive-core/src/main/java/dev/botalive/core/ai/BotDrives.java), :86, :108–111.

---

## P2 — neefektivity a menší díry

### utility() se side-efekty (průřez)

- `Brain.utilitySnapshot()` (`/botalive goal`) volá utility všech cílů mimo rytmus →
  **každé zobrazení diagnostiky zkrátí všechny cooldowny o decisionInterval**, spouští
  mutace (`consumeRebuild`, `memory().forget`) a závodí s tick vláknem nad ne-volatile poli.
  [Brain.java:122–130](botalive-core/src/main/java/dev/botalive/core/ai/Brain.java).
- **Vynucený cíl zmrazí zbytek AI**: forced větev returnuje před decay momenta/pobídek i
  před utility smyčkou → cooldowny ostatních cílů stojí. [Brain.java:182–200](botalive-core/src/main/java/dev/botalive/core/ai/Brain.java).
- Oprava obojího: cooldowny jako absolutní deadline (vzor `EscapeGoal.failUntilMs` — jediný
  cíl s wall-clock), dekrement/decay v pre-passu decide().

### Hotbar vs. batoh (pull chybí)

- **TameGoal** hledá kosti/rybu jen `findHotbarSlot` → krotitel přestane ochočovat po prvním
  úklidu hotbaru (Breed/Shear/Fish umí pull). [TameGoal.java:329–334](botalive-core/src/main/java/dev/botalive/core/ai/goals/TameGoal.java).
- **FarmGoal REPLANT** jen z hotbaru (setí záhonu/pole pull umí) → tichá eroze polí.
  [FarmGoal.java:208–217](botalive-core/src/main/java/dev/botalive/core/ai/goals/FarmGoal.java).
- **MinecartRideGoal** utility vidí vozík kdekoli, tick jen v hotbaru → věčný cyklus
  aktivace→fail(1200) (BoatRide má pull). [MinecartRideGoal.java:187–190](botalive-core/src/main/java/dev/botalive/core/ai/goals/MinecartRideGoal.java), :253.

### Řetězy zdrojů

- **Chleba neexistuje** — žádný recept pšenice→jídlo; jediná využití pšenice jsou chov,
  prodej a kompost. Komentář ve FarmGoal („ať mám z čeho péct“) slibuje, co kód neumí.
- **CompostGoal žere osivo i krmivo bez rezervy** — práh SURPLUS 12 je součet všech druhů
  dohromady; semele i 9 semínek potřebných na založení pole a pšenici pro BreedGoal.
  [CompostGoal.java:40–46](botalive-core/src/main/java/dev/botalive/core/ai/goals/CompostGoal.java), :121–122.
- **Glistering melon nikdo nevyrábí** → healing větev BrewGoalu mrtvá.
- **CraftGoal brána „dřevo ∨ cobble“** blokuje recepty, které dřevo nepotřebují (brnění,
  netherit, nůžky) — po vybankování cobble (BULK_JUNK) utilita 0 s ingoty v batohu.
  [CraftGoal.java:59–61](botalive-core/src/main/java/dev/botalive/core/ai/goals/CraftGoal.java).
- **Farm/Fish/Hunt nemají bránu plného batohu** (Mine má) → produkce padá na zem a despawnuje.

### Výpravy a boj

- **NetherGoal RETURN bez fallback pořadí** — nedosažitelný zapamatovaný portál cyklí
  GO(3600)→RETURN→týž portál až do vyhladovění. [NetherGoal.java:1425–1434](botalive-core/src/main/java/dev/botalive/core/ai/goals/NetherGoal.java).
- **EndOuterGoal HOME_GO nedetekuje průchod gatewayí** — po úspěšném návratu bot šlape na
  místě na kraji hlavního ostrova. [EndOuterGoal.java:833–839](botalive-core/src/main/java/dev/botalive/core/ai/goals/EndOuterGoal.java).
- **StrongholdSeekGoal** — SEARCH bez prefetch (první průchod čte samé null → giveUp a celý
  cyklus znovu) a TRAVEL cíl přepočítávaný každý tick z y bota (repath thrash).
  [StrongholdSeekGoal.java:153–177](botalive-core/src/main/java/dev/botalive/core/ai/goals/StrongholdSeekGoal.java).
- **CombatController vs. CombatGoal/Survive prahy ústupu nesouhlasí** (6+(1−c)·8 vs.
  6+(1−c)·6) → pásmo „bojového limba“, bot bojuje couváním. [CombatController.java:232](botalive-core/src/main/java/dev/botalive/core/combat/CombatController.java).
- **CreeperDodgeGoal bez EdgeGuard** — jediný reflex, který smí sprintovat do lávy/z útesu.
  [CreeperDodgeGoal.java:76–78](botalive-core/src/main/java/dev/botalive/core/ai/goals/CreeperDodgeGoal.java).
- **EatGoal jí vestoje** (`navigator().stop()`, žádný pohyb) i vedle nepřátel → 2–3 zásahy
  zdarma; při sražení pod 6 HP naváže P0-1. [EatGoal.java:60](botalive-core/src/main/java/dev/botalive/core/ai/goals/EatGoal.java).
- **GuardGoal — všech 8 stanovišť bez schůdné buňky = celonoční prázdné točení waypointů**
  bez timeoutu. [GuardGoal.java:94–98](botalive-core/src/main/java/dev/botalive/core/ai/goals/GuardGoal.java).
- **WarRaidGoal.utility — `worldView().worldName()` bez null-guardu** (sourozenci ho mají) →
  NPE spam při přepnutí světa. [WarRaidGoal.java:84](botalive-core/src/main/java/dev/botalive/core/ai/goals/WarRaidGoal.java).

### Stavění a osady

- **BuildHouseGoal stale latch** — po BLOCKED_MATERIAL noční poutě na parcelu s prázdnýma
  rukama (latch obchází den/materiál). [BuildHouseGoal.java:129](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuildHouseGoal.java), :186–195.
- **„Došel materiál“ je nerozeznatelné od „hotovo“** u wall/fence — falešné DONE hlášky,
  XP za nedostavěné bariéry. [BarrierWorker.java:116](botalive-core/src/main/java/dev/botalive/core/ai/goals/BarrierWorker.java).
- **BuildGuardGoal** — stanoviště stráže u 7×7/7×9 sálů padne dovnitř stavby; guardedSite
  neporovnává svět. [BuildGuardGoal.java:183–186](botalive-core/src/main/java/dev/botalive/core/ai/goals/BuildGuardGoal.java), :131–145.
- **SupplyGoal — bezdomovec-šetřílek** odevzdá úspory na komunální stavbu (KEEP 16) a na
  vlastní dům (177+) nikdy nenašetří. [SupplyGoal.java:41–43](botalive-core/src/main/java/dev/botalive/core/ai/goals/SupplyGoal.java).
- **MaintainHomeGoal/`blocksRelocation`** — růst domu stěhování nebrání → hybridní torza
  při přestěhování. [MaintainHomeGoal](botalive-core/src/main/java/dev/botalive/core/ai/goals/MaintainHomeGoal.java).
- **MineGoal stop() neuklízí targetBlock** → stale `miningInProgress()` obchází bránu plného
  batohu. [MineGoal.java:229–243](botalive-core/src/main/java/dev/botalive/core/ai/goals/MineGoal.java), :276–279.
- **SmeltGoal onWorkDone** maže pendingFurnace i při `taken == 0` (plný batoh) → ingoty
  uvíznou v peci, pec se ucpe; druhá vsázka marker tiše přepíše. [SmeltGoal.java:180–192](botalive-core/src/main/java/dev/botalive/core/ai/goals/SmeltGoal.java).
- **FarmGoal *_PLANT fáze bez únikové větve** — ztráta sadby = nekonečná equip smyčka
  (TILL/PLACE bail umí). [FarmGoal.java:360–369](botalive-core/src/main/java/dev/botalive/core/ai/goals/FarmGoal.java), :582–590.

### Ekonomika a sociální

- **StealGoal vs GranaryGoal** — greed > 0,5 obrací pořadí „vlastní sýpka před krádeží“
  (38 vs 34). [GranaryGoal.java:89](botalive-core/src/main/java/dev/botalive/core/ai/goals/GranaryGoal.java).
- **StealGoal sken ignoruje vlastnictví** — vykrade vlastní truhlu i sklad vlastního sídla
  → vnitřní feud (jiný člen krádež odhalí). [StealGoal.java:234–245](botalive-core/src/main/java/dev/botalive/core/ai/goals/StealGoal.java).
- **ReconcileGoal — `settleAmends` i při `given == 0`** → křivda „vyřízena“ bez daru, feud
  navždy bez cesty ven. [ReconcileGoal.java:176](botalive-core/src/main/java/dev/botalive/core/ai/goals/ReconcileGoal.java).
- **GranaryGoal — prázdná sýpka bez backoffu** → hladový bot k ní chodí přednostně dokola
  (34 > steal 30). [GranaryGoal.java:88–90](botalive-core/src/main/java/dev/botalive/core/ai/goals/GranaryGoal.java).
- **Granary/Restock/Supply start() bez null-guardu na settlements** → vynucený cíl = NPE
  smyčka. [GranaryGoal.java:197–198](botalive-core/src/main/java/dev/botalive/core/ai/goals/GranaryGoal.java).

### Registry a váhy (průřez)

- **`settlement-roads` je sirotek** — žádná role, rytmus, ambice, momentum; max utilita 10 <
  explore 24 → cesty prakticky nevznikají. A pozor: **RoleCoverageTest má ruční kopii
  registru, kde roads chybí** — oprava by test falešně shodila.
  [RoleProfiles.java](botalive-core/src/main/java/dev/botalive/core/role/RoleProfiles.java),
  [RoleCoverageTest.java:26–36](botalive-core/src/test/java/dev/botalive/core/role/RoleCoverageTest.java).
- **NETHERITE ambice — poslední míle**: s trosky v batohu frontier pořád táhne `nether`
  (43) místo smelt/craft/smith (40) → smyčka výprav místo dokončení; stejná třída chyby
  jako opravený shear/COZY_HOME. [Ambition.java:280–284](botalive-core/src/main/java/dev/botalive/core/ai/Ambition.java).
- **DimensionPolicy — NETHER tabulka nezrcadlí pojistku** (END ji má): reconcile/rob/minecart
  v Netheru kryje jen stín IN_NETHER_UTILITY 30 — a repair (29) je 1 bod pod ním.
  [DimensionPolicy.java:65–88](botalive-core/src/main/java/dev/botalive/core/ai/DimensionPolicy.java).
- **Resumable bez pause() overridu**: settlement-walls při „pauze“ (default = stop) uvolní
  claim — opačná politika než communal-build → claim ping-pong; fence/pen při resume znovu
  hlásí startovní frázi. [GoalResumption.java:63](botalive-core/src/main/java/dev/botalive/core/ai/GoalResumption.java),
  [SettlementWallGoal.java:372–374](botalive-core/src/main/java/dev/botalive/core/ai/goals/SettlementWallGoal.java).
- **Návratová pobídka nepřežije noc** — DECAY 0.995 → pending zmizí za ~150 s, noc trvá
  ~600 s → pečlivě pauznutý mine/farm stav se ráno vždy zahodí přes start(). Mašinerie
  reálně slouží jen krátkým reflexům. [GoalResumption.java:38–48](botalive-core/src/main/java/dev/botalive/core/ai/GoalResumption.java).
- **escape (26 pevně) nepřebije role-boostnutou práci**, ačkoli komentář tvrdí opak (MINER
  mine ≈ 108). [EscapeGoal.java:63](botalive-core/src/main/java/dev/botalive/core/ai/goals/EscapeGoal.java).
- **EndOuter cooldown se dekrementuje před dimenzní branou** (běží i doma), zatímco
  dragon-fight/wither až za ní (mimo dimenzi zamrzlé) — nekonzistence; totéž sell
  (za overworld branou → v Netheru zamrzlý).

---

## Ověřená pozitiva

- **Jednotky cooldownů jsou po opravě z 2026-07-20 stoprocentně jednotné** (65 cílů, dekrement
  o decisionIntervalTicks v utility(); jediná odchylka escape = wall-clock ms, což je
  robustnější vzor, ne chyba).
- **Žádný překlep v registrech**: všechna id v DayRhythm, RoleProfiles, Ambition,
  DimensionPolicy, RESUMABLE, PRODUCTIVE, GOAL_DRIVE, MODULATION odpovídají registrovaným
  cílům; všech 65 XxxGoal souborů je registrováno (žádný mrtvý kód).
- **Fallback je bezpečný**: idle (1) a wander (≥ 1) nemají žádný nulující násobič — stav
  „všechny cíle 0 a bot stojí“ nenastane.
- **Řetěz očí Enderu je uzavřený** (perly+blaze → craft 12 → stronghold ≥ 8 → FILL) a
  dragon resume / eyes=missing retry z posledních commitů fungují.
- **Null-guardy materialAt()/snapshot** drží ve výpravách (EndTravel FILL, EndOuter, Dragon,
  NetherGoal:1039) i v MineGoal; FarmGoal vzorec CROP_SEEDS.get(null) se jinde neopakuje.
- Reflex-přebije-reflex je bezpečný (ne-resumable cíle dostávají čistý stop, ne pause).

## Doporučené pořadí oprav

1. **P0-1/P0-2 Survive** (zamrznutí + neviditelný hráč) — nejčastější příčina „mrtvých“ botů.
2. **P0-3 NetherGoal** brána BUILD/LIGHT — odemyká Nether progresi bez zděděné paměti.
3. **P0-4 BuyGoal** dealOfBuyer — oživuje trh (společně se SellGoal publikem, P1 #3).
4. **Vzorec „brána vs. běžící cíl“ plošně** (tabulka P1) — jeden mechanický vzor oprav.
5. **SettlementWall isBarrier + MaterialDepot truhla + rezerva 176** — odemyká tier MĚSTO.
6. **Eat/reflexy vs. multiplikátory** — zastaví hladovění „pilných“ botů.
