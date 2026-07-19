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

## Fáze B – společné stavby (motor růstu)

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
  neukáže, že B–D substance nestačí.
- Žádná „městská práva" mechanika pro hráče – sídla botů zůstávají
  jejich, hráč je host (vítání, trh).
