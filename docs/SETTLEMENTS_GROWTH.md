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
  kapsách jednotlivců.
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

### Hradby kolem sídel a ohrady zvířat (základ / navazující krok)

Zbývá autonomní stavění: **hradby** kolem sídla (cíl `settlement-walls`
roads-style nad `Enclosure`, z běžných stavebních bloků – ty boti mají) a
**ohrady** kolem stád (výběr místa a velikosti, sehnání zvířat). Primitivy
(`Enclosure`, `BarrierStyle`) i vypínač `settlement.walls` jsou připravené.

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

## Vědomé meze

- Max 8 členů (`settlement.max-members`) drží sídla lidská; město je
  „plná vesnice s infrastrukturou", ne metropole. Zvětšování kapacity
  podle stupně je možné rozšíření (config), ne předpoklad.
- **Ploty kolem domů**: hotové (cíl `settlement-fences`, za `settlement.fences`,
  defaultně vypnuto). **Hradby** kolem sídel a **ohrady** kolem zvířat mají
  hotový základ (`Enclosure`/`BarrierStyle` + vypínač `settlement.walls`), ale
  autonomní stavění je navazující krok (viz „Ohradní bariéry"). Radnice a druhé
  prstence budov zůstávají mimo plán, dokud se neukáže, že B–D substance
  nestačí. (Účelné řemeslné dílny fáze E jsou výjimka odůvodněná specializací –
  nezvětšují sídlo, jen ho prohlubují.)
- Žádná „městská práva" mechanika pro hráče – sídla botů zůstávají
  jejich, hráč je host (vítání, trh).
