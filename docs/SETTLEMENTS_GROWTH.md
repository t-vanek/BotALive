# Organický růst sídel: osada → vesnice → město

Analýza a fázový plán. Navazuje na fáze 13–15 (vesnice, trh, drby,
usmíření) a drží jejich DNA: **stupeň sídla se odvozuje ze substance,
nikdy nedekretuje** (jako „vesnice vzniká až s hotovým domem zakladatele"),
společenství zraje emergentně z paměti a povah (žádné pevné týmy) a každé
jádro rozhodování je čistá, jednotkově testovatelná funkce.

## Model stupňů

| Stupeň | Podmínka (odvozená, živě přepočítávaná) |
|---|---|
| **Osada** | výchozí – 1+ dostavěných domů |
| **Vesnice** | ≥ 4 dostavěné domy členů *(od fáze B navíc studna/ohniště na návsi)* |
| **Město** | ≥ 8 dostavěných domů **a** městská infrastruktura (sýpka + tržiště, fáze B/D) – samotný počet chalup město nedělá |

Substance = `house_done` na členství (píše `BuildHouseGoal.finishHouse`
přes `SettlementService.houseFinished`). Stupeň klesá tiše (zánik se
neslaví), roste s chatovou hláškou – jednou; poslední ohlášený stupeň se
persistuje (`ba_settlements.announced_tier`), aby restart hlášky
neopakoval. Čisté jádro: `SettlementTier.of(houses, townInfra)`.

## Fáze A – substance a stupně (hotovo tímto commitem)

- Migrace **v4**: `ba_settlement_members.house_done`,
  `ba_settlements.announced_tier`.
- `SettlementService.houseFinished(botId)` – idempotentní zápis substance,
  vrací nově dosažený stupeň k ohlášení; `SettlementInfo` nese
  `houses` + `tier`; `releasePlot` substanci vrací.
- Hláška `settlement-village-up` (cs/en), `/botalive settlements`
  ukazuje stupeň a počet domů.

## Fáze B – společné stavby (motor růstu; studna hotová, sýpka = B2)

První ne-soukromé budovy. Vzor `MarketBoard`: sdílená nástěnka
**projektů sídla** (`SettlementProjects`) – projekt má druh, parcelu
(vybírá se jako `suggestPlots`, prstenec 1), rozpočet materiálu a stav
(`SITE/BUILD/DONE`). Nový cíl `communal-build`: člen s materiálem
(ochota × role stavitel) si projekt zamluví (první bere, jako trh)
a postaví ho stejnou mašinérií jako dům (blueprint → `PlaceBlockTask`).

- **Studna/ohniště návsi** (podmínka vesnice od B): kamenný kruh 3×3
  s pochodní – čistě běžné materiály, `WellBlueprint` se stejným
  kontraktem jako `HouseBlueprint` (`placements/clearVolume/standPoint`).
- **Sýpka** (podmínka města): domek 3×3 se dvěma truhlami a dveřmi.
- Dokončený projekt = trvalý záznam sídla (persistuje se; fyzická stavba
  ve světě je autorita, záznam jen cache) → vstupuje do
  `SettlementTier.of(..., townInfra)`.
- Hlášky `settlement-project-*`, stupeň Město se ohlašuje odsud.

## Fáze C – zrání společenství

Normy rostou s infrastrukturou, ne dekretem:

- **Sýpka mění nouzi** *(hotovo)*: cíl `granary` (`GranaryGoal`) ze sýpky
  udělal instituci – hladový člen si z ní vezme jídlo s vyšší užitečností
  než `StealGoal`, takže sáhne do vlastní společné špajzky **dřív, než jde
  krást** do cizí truhly; člen s přebytkem jídla ji naopak doplní (nechá si
  zásobu na cestu). Pozici truhly si cíl dopočítá z geometrie sýpky
  (`ContainerService.depositFood` / `withdrawSupplies`).
- **Společný sklad na materiál** *(hotovo)*: čtvrtá společná stavba
  `WAREHOUSE` (zásobárna s dvojtruhlou, od vesnice, po dílnách) – člen s
  přebytkem materiálu ho přes `StashGoal` uloží do společného skladu místo
  soukromé truhly (je-li rozumně blízko). Přebytky přestávají hnít po
  kapsách jednotlivců. `StashGoal` už neukládá jen odpadní kámen: přes
  `ContainerService.depositSurplus` bankuje i **vytěžené cennosti nad
  pracovní rezervu** (rudy, ingoty, uhlí, drahé kameny – `InventoryHelper`),
  takže se do společného skladu poolují i rudy, ne jen stavební bloky, a
  výtěžek těžby přežije smrt bota. Rezerva je štědrá (bot si nechá dost na
  tavení/výrobu), takže se nepřetrhne řetěz ruda → ingot → nástroj;
  netheritový řetězec se schválně nebankuje. Zpětný odběr rud kovářem ze
  skladu je zatím mimo (odběr bloků řeší `CommunalBuildGoal` PROVISION) –
  přirozené rozšíření, ne předpoklad.
- **Starosta** – odvozený, ne volený: člen s nejsilnějším součtem
  FRIEND vazeb na ostatní členy (lazy přepočet ze `SocialView`).
  Přednostně vítá hráče (`tickVillageWelcome`), přednostně staví
  společné projekty, jeho jméno ukazuje `/botalive settlements`.
- **Profese podle potřeb**: při vstupu nového člena bez vyhraněné role
  do vesnice+ se spočte deficit profesí (bez kováře? bez farmáře?)
  a `RolePicker` dostane kontextový bias – vesnice si řemeslníky
  vychovává, nevnucuje.

## Fáze E – účelné (řemeslné) stavby

Po infrastruktuře přichází **specializace**: vesnice (od hotové studny) staví
malé **dílny** pro řemesla, která její členové skutečně dělají – ne dekretem,
ale poptávkově (stejná DNA jako „vesnice si řemeslníky vychovává"). Dílna je
bouda půdorysu `HouseBlueprint` s dveřmi a pochodní, uvnitř **pracovní stanice**
dané profese; řemeslník ji pak najde stejným skenem/pamětí jako jakoukoli
stanici a skutečně v ní pracuje.

| Dílna | Profese | Hlavní stanice | Vedlejší | Cíl, který ji používá |
|---|---|---|---|---|
| Kovárna | kovář | pec | kovářský stůl | `SmeltGoal`, `SmithGoal` |
| Kuchyně | kuchař | udírna | – | `SmeltGoal` (peče jídlo) |
| Dílna | stavitel/univerzál | ponk | řezák | `CraftGoal` |
| Kompostárna | farmář | composter | – | `CompostGoal` |
| Enchantovna | enchanter | enchantovací stůl | – | `EnchantGoal` |
| Alchymistická dílna | alchymista | varný stojan | – | `BrewGoal` |

- Dílny jsou další druhy `ProjectKind` (nesou profesi ve `workshopRole`);
  staví je tatáž mašinérie jako studnu/sýpku (`CommunalBuildGoal` → `Blueprint`
  → `BuildSession`). Katalog stanic žije na jednom místě (`Workshops`).
- **Poptávka, ne dekret**: `SettlementService.nextProjectKind` nabídne dílnu jen
  tehdy, když dané řemeslo v sídle někdo dělá; infrastruktura (studna/sýpka/
  tržiště, tj. stupeň sídla) má vždy přednost.
- **Zahájí ji jen ten, kdo má stanici** (jako sýpka truhly) – prázdná kůlna
  nevznikne. Vedlejší stanice je bonus (osadí se, když ji stavitel má).
- Dílna **nemění stupeň sídla** – ten dál stojí na domech + městské
  infrastruktuře; dílny jsou kvalita života, ne meta.
- Nový generický druh vybavení `FurnishKind.STATION` (materiál nese buňka),
  dvě hlášky `settlement-workshop-*` (název dílny jako `{name}`).
- **Rozšířená sada** pokrývá i další vanilla vesnická řemesla (šípařská
  dílna, knihovna, nástrojárna, zbrojírna, zbrojnice, kartografie,
  kamenictví, koželužna, tkalcovna). Jejich stanice (fletching table,
  lectern, grindstone, cartography table, cauldron, loom…) bot aktivně
  nepoužívá – dílna je pak **landmark** profese a zahájí ji jen člen, který
  stanici má; katalog i tak drží jedno místo pravdy (`Workshops`) a
  poptávka je stejná (staví se jen pro řemeslo, které v sídle někdo dělá).

## Vnitřní cesty sídla (hotovo)

Od stupně **VESNICE** boti neudržují jen cestičku od svých dveří k návsi
(`VillageDecor`), ale propojí celé sídlo souvislou **silniční sítí**:
hlavní ulice vedou od návsi k obsazeným parcelám (po hlavní ose ven, pak
kolmé žebro k domu; sdílené páteře se nedusají dvakrát), **město** navíc
dostane obvodový **okruh** po vnějším prstenci. Čistý plánovač
`SettlementRoads` počítá síť ze živé geometrie parcel (`PlotLayout`);
vykonává ji cíl `settlement-roads` toutéž mašinérií jako cestičky
(`DecorWorker` – lopata, chůze, pauzy).

Stejně jako u cestiček se **dusá jen po trávě**, takže plán je idempotentní:
hotová cesta se přeskočí, nové parcely (růst sídla) přibydou k síti v další
seanci a nikdy se nepřepíše podlaha domu, políčko ani voda. Síť vlastní
`SettlementService` (jeden stavitel naráz – claim s TTL jako u společných
projektů); stav se nepersistuje, protože fyzická cesta ve světě je autorita.

## Ohradní bariéry: ploty a hradby

Uzavřené obvodové stavby – **plot** kolem parcel domů a stád, **hradby** kolem
sídel – patří ke stejnému modelu jako cesty: čistý plán nad `WorldView` po
terénu, idempotentní, se stropem kroků na seanci. Základ tvoří dva primitivy:

- `Enclosure` (čistá geometrie) naplánuje obvod obdélníku po terénu: sloupce
  bariéry po prstenci (jako `SettlementRoads` obvodový okruh města), branky ve
  středu zvolených hran a rozvinutí sloupce do výšky (`column` – branka dole,
  sloupky nad ní). Idempotence je jako u cest: sloupec, kde už bariéra stojí,
  se pozná podle materiálu a přeskočí, takže plán roste se sídlem a samoopravuje
  se; terén se sleduje přes `VillageDecor.groundAt`, bariéra kopíruje svah.
- `BarrierStyle` oddělí „co bariéra je" od „z čeho" (stejně jako `SettlementRoads`
  nechává materiál na vykonavateli): **plot** se staví z **místního dřeva**
  (`*_FENCE`/`*_FENCE_GATE`, jako domy), **hradba** je kamenná (`COBBLESTONE_WALL`)
  s dřevěnou brankou.

Fyzicky ploty už fungují (kolize `TALL_BOXES` v `BlockTraits`, otevírání branek
přes `DoorOpener`), takže postavená bariéra zvířata udrží a boti brankou projdou.

### Plot kolem domu (hotovo, za `settlement.fences`)

Cíl `settlement-fences` (`SettlementFenceGoal`) obežene parcelu domu plotem
6×6 (dvorek s odsazením 1) s **brankou na straně dveří**; funguje pro člena
vesnice i samotáře (parcelu zná z `SettlementService.claimedPlot`, jinak z HOME
dat – jako `MaintainHomeGoal`). DNA jako u cest/domů: staví se ve dne, poblíž
domova, nízká priorita (řeší se, když je klid), a rozdělaný plot se dodělá
napříč seancemi (plán je idempotentní).

- **Materiál z prken**: plot v batohu bot skoro nikdy nemá, tak si plaňky a
  branku vyrobí z prken (`CraftingService.craftFencing`, klacky se domáčknou
  z prken – stejný vzor jako stanice dílen); potřebuje ponk.
- **Vykonavatel** `BarrierWorker` (sestra `DecorWorker`) klade po obvodu; branku
  staví z **vnitřní strany** ohrady, aby yaw mířil ven a branka se napojila na
  plot (ne napříč) – `PlaceBlockTask` + stanoviště, ne jen pohled.
- Váhy rolí: farmář/pastýř (drží zvířata) a stavitel k plotu tíhnou; hlášky
  `settlement-fence-start/done`.

### Hradby kolem sídel (hotovo, za `settlement.walls`)

Od stupně **VESNICE** obežene sídlo kamenné **hradby**: cíl `settlement-walls`
(`SettlementWallGoal`) je sourozenec `settlement-roads` – hradby vlastní
`SettlementService` (jeden stavitel naráz, claim s TTL jako cesty), stavbu vede
`BarrierWorker` nad `Enclosure`. Obvod se počítá po **vnějším obsazeném prstenci**
parcel (`wallBounds` – stejné odvození prstence jako `SettlementRoads`), takže
hradba roste se sídlem; **brány** jsou na čtyřech osách, kudy vyjíždějí hlavní
ulice.

- **Materiál z běžných bloků**: hradby se staví z toho, co bot sbírá jako na domy
  (`equipBuildingBlock` – `BarrierWorker` s `post = null`), takže se reálně
  stavějí bez zvláštního zásobování; výška je `settlement.wall-height`.
- **Brány jako průchody** (`Enclosure.gateway`): branka dole, volné nadpraží a
  překlad nahoře, takže se projde i vysokou hradbou. Branku si bot dorobí z prken
  (best-effort, jako plot) – když prkna/ponk nemá, zůstane otevřený **oblouk**
  (branka je bonus, ne podmínka: `BarrierWorker` chybějící branku jen přeskočí,
  hradba nezůstane torzem).
- Idempotentní jako cesty: hotové sloupce se přeskočí, velká hradba se dostaví
  napříč seancemi (strop sloupců na seanci). Váhy rolí: kameník a stavitel;
  hlášky `settlement-walls-start/done`.

### Ohrady kolem zvířat (hotovo, za `settlement.fences`)

Cíl `pen` (`PenGoal`) obežene shluk hospodářských zvířat plotem s brankou –
chov (`BreedGoal`) stádo rozmnoží, ohrada mu dá výběh (a přestane utíkat).
Chová se u chovatelských profesí (farmář, pastýř, krotitel).

Otevřený problém „kam a jak velkou ohradu kolem **pohyblivého** stáda" řeší
**pevná ohrada přichycená na mřížku**: spočítá se těžiště stáda, zaokrouhlí na
mřížku (7×7) a ohradí se ta buňka. Obdélník je tím **deterministický** –
opakovaný běh dá tentýž, takže se ohrady nepřekrývají a plán je idempotentní
(`penRect`, čistá funkce). Branka je otevíratelná (`DoorOpener`), takže se bot
nezavře. Materiál i vykonavatel jsou stejné jako u plotu domu
(`craftFencing` z prken, `BarrierWorker`); hlášky `pen-start/done`.

Tím je „obehnat zvířata i domy plotem, sídla hradbami" kompletní – ploty, hradby
i ohrady stojí na jednom primitivu (`Enclosure`) a jednom vykonavateli
(`BarrierWorker`).

### Oprava bariér s prioritizací (hotovo)

Bariéry se nejen staví, ale i **opravují** – a to **podle naléhavosti**. Díru
(po creeperovi apod.) pozná `Enclosure.assess` (kolik sloupců po obvodu stojí a
kolik chybí); „pár děr v jinak stojící bariéře" je oprava (`BarrierRepair.isDamaged`,
strop `MAX_GAPS` odliší opravu od nové/rozbořené stavby). Ohodnocení je
throttlované (`RepairAssessor`, přepočet ~5 s), aby utility zůstala levná.

**Priorita = raw utility** (Brain vybírá max; jako `SurviveGoal` škáluje podle
nouze). Uspořádání (`BarrierRepair`, ověřené `BarrierRepairTest`):

| Situace | raw utility |
|---|---|
| **hradby, díra, za soumraku/v noci** | 34–42 (obrana – nejnaléhavější) |
| **ohrada zvířat, díra** | 24–34 (ať neutečou – nad plotem domu) |
| hradby, díra, ve dne ≈ plot domu, díra | 10–18 |
| nová stavba (bez díry) | 4–10 |

Tedy „opravit ohradu dřív než plot domu" a „blíží se noc → nejdřív hradby".
Urgentní oprava (poškozené hradby v noci, poškozená ohrada) **obejde denní bránu**
(jinak se nestaví po 11500) i 5min throttle hradeb; drží se ale pod „opravdovým
přežitím" (jídlo při hladu, útěk před creeperem), i po zesílení rolí.

**Chybí materiál → bot si ho dojde sehnat** (`BarrierGather`): najde v okolí
nejbližší odkrytý zdroj a vytěží ho – **kámen/hlínu na hradby**, **dřevo na
ploty** (klády → prkna přes `CraftingService.craftPlanks`, pak plaňky). Bez
zdroje v dosahu opravu odloží. Fázový automat cílů: `PROVISION` (sehnat/vyrobit
materiál) → `WORK` (idempotentní stavba/oprava přes `BarrierWorker`).

## Fáze D – město a krajina

- **Tržiště** (třetí společná stavba): zastřešený pult u návsi; nabídky
  `SellGoal` se kotví k tržišti (trh dostává místo, vyvolávání na
  tržišti, kupci chodí tam).
- **Cesty mezi sídly**: `FarPlanner` koridor mezi návsemi dvou sídel
  do ~200 bloků + položení `DIRT_PATH` po trase (etapově, jako
  `VillageDecor`). Preference cestiček (fáze 25) pak zajistí, že se
  po silnici skutečně chodí – síť se používáním posiluje.
- Město ohlašuje `settlement-town-up`, `/botalive settlements` ukazuje
  infrastrukturu.

## Dynamická velikost staveb (hotovo)

Stavby nemají pevný rozměr – **velikost roste s prosperitou sídla**. Jeden zdroj
pravdy [`StructureSizer`](../botalive-core/src/main/java/dev/botalive/core/build/plan/StructureSizer.java)
mapuje „stupeň sídla + povaha stavitele → rozměr" (osada útulně, město honosně;
strop z configu). Většina navázaných subsystémů si velikost už bere z geometrie
`Blueprint`u, takže se přizpůsobí sama: staveniště (`SiteFinder.footprintDims`),
rozpočet terénu, rozmístění na parcele (`PlotLayout.centerFootprint`, vícparcelová
rezervace), materiál (`blocksNeeded`), vícestanovištní stavba (`BuildPlanner`).

- **Domy** škálují šířku i výšku (osada 5×5 → vesnice 7×7 → město 9×9) a s configem
  `build.grow` **dorůstají v čase** (viz `BUILD_AS_PROCESS.md`, fáze 6).
- **Prestižní sály a městské sklady** (radnice, kostel, sýpka, zásobárna) se sizují
  z prosperity **při vzniku projektu** a rozměr se **persistuje** (`ba_settlement_projects`
  `width/depth/wall_height`, migrace v11; 0 = legacy pevná velikost). Díky tomu
  `CommunalBuildGoal.blueprintFor` zrekonstruuje týž tvar napříč sezeními
  (idempotentní resume). Sdílený parametrický `Hall` (`Blueprints`) mění jen
  vybavení (pochodeň / dvojtruhla / stanice), geometrie je společná.
- **Landmarky** (studna, tržiště, zvonice) a účelné dílny drží pevnou velikost –
  jejich tvar je součástí identity.
- **Ochranné pásmo** proti poddolování se škáluje s rozestupem parcel, takže pokryje
  i širší domy a sály; pozice truhly sýpky/skladu se dopočítá z geometrie
  (`Blueprints.storageChest`), ne napevno z 4×4.

## Vědomé meze

- Max 8 členů (`settlement.max-members`) drží sídla lidská; město je
  „plná vesnice s infrastrukturou", ne metropole. Zvětšování kapacity
  podle stupně je možné rozšíření (config), ne předpoklad.
- **Ohradní bariéry hotové** (defaultně vypnuté): ploty kolem domů
  (`settlement-fences`) a ohrady kolem zvířat (`pen`) za `settlement.fences`,
  hradby kolem sídel (`settlement-walls`) za `settlement.walls` – vše na jednom
  primitivu `Enclosure` a vykonavateli `BarrierWorker`. Radnice a druhé prstence
  budov zůstávají mimo plán, dokud se neukáže, že B–D substance
  nestačí. (Účelné řemeslné dílny fáze E jsou výjimka odůvodněná specializací –
  nezvětšují sídlo, jen ho prohlubují.)
- Žádná „městská práva" mechanika pro hráče – sídla botů zůstávají
  jejich, hráč je host (vítání, trh).
