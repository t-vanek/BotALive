# Pathfinding v3 – analýza po dokončení roadmapy v2

Roadmapa v2 ([PATHFINDING_V2.md](PATHFINDING_V2.md)) je kompletní: levné
jádro s memo cache a rozpočty, simulační kontrakt plánovač ↔ fyzika,
hrubé koridory dálkových tras, cílové predikáty, akční hrany (kopání
i pokládání), osobnost v cenách, rozšířený parkour a preference cestiček.
Tahle analýza hledá, co zbývá: prošla znovu kód A*, akčních hran,
navigátoru a všech volajících goalů s čerstvýma očima a hledala rozpory
mezi plánem a exekucí, výkonnostní rezervy a nevyužité schopnosti.

## 1. Slabá místa (s důkazy)

### P1: Kopací hrany nevidí padavé bloky (písek, štěrk)

`digsFor` (AStarPathfinder) schvaluje výkop, když je blok pevný, mimo
deny-list a bez tekutiny v 6-okolí – **gravitaci nezná**. Tunel pod
pískovým/štěrkovým stropem (poušť, pláž, podvodní hrany už chrání
tekutinová pojistka, ale suchý štěrk ne) znamená: bot vykopne blok,
sloupec nad ním se sesype do štoly, waypoint je po `actionResolved` zase
zablokovaný, validace cesty selže a plán se přepočítá – potenciálně
dokola, dokud se sloupec nevysype celý. Exekuce přežije (reaktivní
`MineBlockTask` si s dosypaným štěrkem poradí, `Suffocation` bota
vytlačí), ale „jeden souvislý plán" se rozpadne na smyčku replánů
přesně toho druhu, který měl v2.2-D odstranit.

Náprava: při stavbě kopací hrany zkontrolovat materiál nad každým
kopaným blokem; padavý sloupec buď zakázat (konzervativní start), nebo
ocenit jako vícenásobný výkop (výška sloupce × `COST_DIG`) a do
`TerrainAction` přidat opakované kopání téže buňky. Exekuční část už
existuje (TaskSequence kroky se přeskakují/opakují podle stavu světa).

### P2: Bezedno je „bezpečné" – skoky a mosty nad voidem a hlubokou roklí

`hazardBelow` skenuje dno jen do `GAP_HAZARD_SCAN = 8` bloků a při
nenalezení dna vrací `false` – komentář výslovně říká „bezedno – pád je
riziko skoku, ne hazard dna". Důsledky:

- sprint-skok přes rokli s lávou v hloubce 9+ projde kontrolou (láva
  je pod dohledem skenu);
- skok přes mezeru nad **voidem** (End!) nemá žádnou přirážku – odvážný
  i bázlivý bot skáčou nad smrtelným pádem za stejnou cenu jako nad
  jámou hlubokou 4 bloky;
- stejný sken jistí opory mostů (v2.2-D2) – most nad roklí hlubší než
  8 bloků s lávou na dně se postaví.

Skoky jsou fyzikálně ověřené simulací, takže selhání v klidu je vzácné –
ale strčení davem, led nebo boj uprostřed rozskoku znamenají pád. Náprava:
hluboký sken jako u mostních opor + **příplatek škálovaný opatrností**
(`PathCosts.hazardMargin`) za skok nad pádem ≥ smrtící výška; nad voidem
násobek. Bázlivý bot obchází, odvážný skáče dál – individualita zůstane.

### P3: Zbylé blokové cíle pálí rozpočet (End, Nether, drak, průzkum)

Migrace na `near` (fáze 25) pokryla overworldové goaly. Stejný vzor
„navigateTo na neprůchozí blok → cíl se nikdy nesplní → každý plán spálí
celý uzlový rozpočet" zůstává v: `EndTravelGoal` (rám portálu – solidní
blok; vzpomínka na portál), `NetherGoal` (kořistěná truhla, těžený blok,
barter), `EndHarvestGoal` (end stone/chorus), `DragonFightGoal` a
`EndReturnGoal` (střed fontány – obsidián), `MinecartRideGoal`/
`BoatRideGoal` (vozidlo – entita), `GuardGoal`, `ReturnHomeGoal`,
`ExploreGoal` (bod expedice může padnout do stromu/vody – `near`
s větším poloměrem je robustnější). Záměrně blokové zůstávají konkrétní
pochozí buňky (rybaření, stanoviště kopání, sběr dropů, stavební
stanoviště, dekorace).

### P4: Kandidáti se vybírají vzdušnou čarou – `anyOf` leží ladem

Goaly, které volí mezi kandidáty (strom na dřevo, ruda, truhla,
postel, plodina), berou nejbližší podle vzdálenosti a teprve pak
zjišťují, že je nedosažitelný (marný výpočet, blacklist, další pokus).
`PathGoal.anyOf` umí „nejbližší **dosažitelný**" v jednom hledání –
ale žádný goal ho zatím nepoužívá. Chybí drobná infrastruktura: goal
potřebuje vědět, KTERÝ kandidát cesta vybrala (poslední waypoint →
nejbližší kandidát), aby mohl vést interakci.

### P5: Diagonála řeže roh přes zavřené dveře

`canCutCorner` používá `transitClear`, který zavřené dveře pouští
(bot si je umí otevřít). Pro průchod SKRZ je to správně, pro řezání
rohu ne: bot dveře v bočním sloupci neotvírá a diagonální krok ho vede
tělem přes roh jejich kolize – fyzika ho odře o hranu (vyhlazení to
zamaskuje, ale je to přesně druh nesouladu plán ↔ kolize, který jinde
odstranil simulační kontrakt). Náprava: roh vyžaduje `lowProfile`,
ne `door`.

### P6: Alokační tlak vnitřní smyčky (měřit, pak řešit)

Open set je `PriorityQueue<Node>` s objektovými uzly a `BlockPos`
se alokuje na ~64 místech smyčky (offsety, up/down). Ceny jsou celé
číslo s malým rozsahem – nabízí se bucket queue a průchod nad `long`
klíči místo záznamů. Memo cache už drží dotazy do světa na ~2/uzel,
takže úzké hrdlo je teď alokace/GC, ne svět. Bez měření neimplementovat:
nejdřív mikro-benchmark (uzly/ms) v efektivnostní stráži, práh, a teprve
při prokázané rezervě přepis (riziko čitelnosti jádra).

### P7: Tekoucí voda nemá směr

`BlockTraits` nerozlišuje zdrojový blok od tekoucí vody a proud nemá
směr – plán počítá jen cenu plavání, exekuce koriguje snos reaktivně
(drift → validace → replán). Řeky bota snášejí, proti proudu se plave
draze. Plnohodnotné řešení chce směr toku ve `state` vrstvě
(`ReflectionBlockStateMapper` úroveň) a cenu po/proti proudu – velký
zásah za malý viditelný zisk. Vědomě odložit, dokud nebude scénář,
kde to reálně bolí (splavování, přístavy).

### P8: Testovací dluh exekuce akcí

Simulační kontrakt kryje kopání i pokládání **podle plánu**, ale
reaktivní `PillarUpTask`/`BridgeTask`/`TaskSequence` exekuce
(BotContext vazba) fyzickou simulaci nemají. A pokládání žebříků
na stěny > 2 bloky zůstává čistě reaktivní – jako plánovaná hrana
(sloupec žebříků + rozpočet žebříků v inventáři) by dokončilo paritu
plán ↔ assist.

> **Stav: v3.0 implementováno.** (P1) `digsFor` odmítne výkop s padavým
> blokem (písek, štěrk, beton v prášku, kovadlina…) přímo nad kopanou
> buňkou – padavý blok smí být sám cílem výkopu, vadí jen nekopaný soused
> nad čerstvou dírou (`nekopeTunelPodPadavymStropem`,
> `prokopeSterkKdyzJeStropPevny`). (P2) `hazardBelow` nahrazen `dropDepth`
> s dohlednou hloubkou 24: láva na dně zakazuje skok i v hloubce 9+
> (`lavaVHlubokeRokliZakazeSkok`) a mostní opěry sdílí stejný sken;
> **smrtící pád** (≥ 20, vanilla poškození 17) a bezedno nesou příplatek
> škálovaný opatrností (`COST_DEEP_GAP`, nad voidem ×2) – bázlivý bot jde
> mezi ostrovy lávkou, odvážný skáče (`nadVoidemSkaceJenOdvazny`). Práh
> je záměrně „smrtící", ne „bolestivý": rokle hloubky 12 nechává volbu na
> odvaze jako dřív (původní návrh s prahem = bezpečný seskok převracel
> chování odvážných – odhalil to osobnostní test). Mostění nad bezednem
> zůstává povolené (ostrovy v Endu). (P5) nový `flightClear` (průchozí
> bez ruky na klice) kryje rohy diagonál i celou letovou dráhu skoku –
> zavřené dveře už neřežou roh (`diagonalaNerezeRohPresZavreneDvere`)
> a neskáče se skrz ně (`neskaceSkrzZavreneDvere` – nález nad rámec
> analýzy: letová dráha dveře „otvírala" taky).

## 2. Doporučené fázování

| Fáze | Obsah | Náročnost | Riziko | Přínos |
|---|---|---|---|---|
| **v3.0 korektnost** ✅ | P1 gravity guard kopání, P2 hluboký sken + příplatek za skok nad pádem/voidem (škálovaný opatrností), P5 roh bez dveří | S | nízké | plán a kolize se přestanou rozcházet v posledních známých místech |
| **v3.1 dokončení migrace** | P3 zbylé `near` cíle (End/Nether/drak/vozidla/průzkum) | S | nízké | konec pálení rozpočtu, drift throttle všude |
| **v3.2 kvalita výběru** | P4 `anyOf` v goalech s kandidáty (strom/ruda/truhla/postel) + zpětná vazba „který kandidát vyšel" | M | střední | méně marných výpočtů a blacklist smyček, přirozenější volby |
| **v3.3 parita akcí** | P8 žebříkové hrany + BotTask-level simulace | M | střední | poslední reaktivní eskalace pod kontraktem |
| **v3.4 výkon** | P6 bucket queue / long smyčka – jen po benchmarku | M | střední | až 2× rychlejší jádro, ale nejdřív důkaz |
| odloženo | P7 proudy vody | L | vyšší | malý viditelný zisk |

Pořadí drží zásadu celé série: nejdřív korektnost (P1/P2/P5 jsou
poslední známé rozpory plánu s fyzikou), pak dokončit rozdělanou
migraci (P3 je mechanická), pak kvalita chování (P4), parita akcí (P8)
a výkon až s důkazem (P6).

## 3. Shrnutí

v2 udělal z pathfindingu levný, měřitelný a fyzikou ověřený systém
s akčními hranami a osobností. Zbývající slabiny jsou úzké a dobře
ohraničené: dvě bezpečnostní (padavé bloky, bezedno/hluboká láva),
jedna kosmetická kolizní (roh přes dveře), jedna rozdělaná migrace
(`near` mimo overworld), jedna nevyužitá schopnost (`anyOf`), jeden
testovací dluh (reaktivní tasky) a jedna výkonnostní rezerva
(alokace – měřit). Doporučený start: **v3.0** (korektnost, malá
a bezpečná změna s okamžitým dopadem na Nether/End provoz).
