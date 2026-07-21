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

- **Sýpka mění nouzi**: hladový člen si PŘED krádeží (desperation)
  a před prosbou dojde do sýpky; přebytky jídla ze `StashGoal` končí
  v sýpce místo soukromé truhly. Sdílení jídla přestává být osobní
  laskavost a stává se institucí.
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
- Hradby, radnice a druhé prstence budov jsou mimo plán, dokud se
  neukáže, že B–D substance nestačí. (Účelné řemeslné dílny fáze E jsou
  výjimka odůvodněná specializací – nezvětšují sídlo, jen ho prohlubují.)
- Žádná „městská práva" mechanika pro hráče – sídla botů zůstávají
  jejich, hráč je host (vítání, trh).
