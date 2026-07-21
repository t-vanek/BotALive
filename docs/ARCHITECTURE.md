# BotAlive – architektura

Tento dokument popisuje klíčová architektonická rozhodnutí, jejich alternativy
a důvody, proč byla zvolena robustnější varianta.

## Přehled

```
┌────────────────────────────────────────────────────────────────────┐
│ botalive-api        veřejná rozhraní, eventy, datové typy          │
└──────────────▲─────────────────────────────────────────────────────┘
               │
┌──────────────┴─────────────────────────────────────────────────────┐
│ botalive-core                                                      │
│                                                                    │
│  bootstrap   CompositionRoot, plugin lifecycle, Bukkit listenery   │
│  config      typovaná konfigurace (records) + loader               │
│  scheduler   BotTickEngine (20 Hz pool), MainThreadBridge (Folia)  │
│  network     BotConnection, SessionListener, MovementSender,      │
│              BotActions, BotClientState (mailbox síť ⇄ tick)       │
│  world       WorldView (abstrakce), SnapshotWorldView + cache      │
│  physics     BotPhysics (AABB kolize, gravitace, krok, plavání)    │
│  pathfinding AStarPathfinder, NavigationService, Navigator         │
│  entity      EntityTracker (klientský pohled na entity)            │
│  ai          Brain (utility výběr), BotContext, goals/*            │
│  tasks       BotTask primitivy (MineBlockTask, PlaceBlockTask)     │
│  personality generátor rysů (gauss + korelace), archetypy          │
│  human       Humanizer (pohled, reakce, mikro-chování)             │
│  combat      CombatController, CombatDifficulty                    │
│  chat        ChatEngine, ChatStyle, TypoEngine, PhraseBank         │
│  inventory   ClientInventory (pakety), InventoryHelper (nástroje)  │
│  memory      BotMemoryImpl (write-behind, slučování vzpomínek)     │
│  economy     BotWalletImpl + transakční log                        │
│  persistence Database (Hikari), SqlDialect, migrace, repozitář     │
│  commands    /botalive dispatcher + tab-complete                   │
│  bot         BotImpl (kompozice subsystémů), BotManagerImpl,       │
│              ServerSideView, BotStats, BotNameGenerator            │
└────────────────────────────────────────────────────────────────────┘
```

## Zásadní rozhodnutí a trade-offy

### 1. Gradle moduly vs. balíčky

**Volby:** (a) 16 Gradle modulů dle subsystémů, (b) api + core moduly
s balíčkovými hranicemi.

**Zvoleno (b).** Gradle moduly jsou nástroj pro nezávislé publikování a tvrdé
hranice závislostí. Zde existuje jediná skutečná hranice: veřejné API vs.
implementace (cizí pluginy nesmí záviset na MCProtocolLib). Subsystémy uvnitř
core sdílejí typy (Vec3, BlockPos, BotContext) a vždy se nasazují společně –
16 modulů by přidalo jen build komplexitu a kruhové závislosti by se stejně
musely řešit slepováním. Balíčky s disciplínou (žádný balíček nesahá do
interních tříd jiného; komunikace přes rozhraní jako `WorldView`,
`NetworkEvents`, `BotContext`) dávají stejnou modularitu za nižší cenu.

### 2. Zdroj geometrie světa: server-side snapshoty

> **Aktualizováno.** Původně existovaly dvě implementace (klientský model
> z chunk paketů pro cizí servery + server-side snapshoty). Klientský
> (paketový) model byl odstraněn – BotAlive běží jen na serveru, kde je
> nainstalovaný, takže autoritativní `ChunkSnapshot` je jediný zdroj.
> Text níže popisuje původní rozhodnutí; `PacketWorldView` a spol. už
> v kódu nejsou.

**Volby:** (a) klientský model světa parsováním `ClientboundLevelChunkWithLightPacket`,
(b) `ChunkSnapshot` Bukkit světa, kde plugin běží.

**Zvoleny obě, výchozí (b).** Boti typicky hrají na témže serveru, kde plugin
běží – server je autoritativní zdroj geometrie. Snapshoty jsou nemutabilní
(bezpečné pro AI vlákna), pořizují se na region vlákně (Folia-safe), sdílejí
se mezi boty přes Caffeine cache s TTL a bodovou invalidací z Bukkit eventů.

Pro boty na <b>cizím serveru</b> existuje druhá implementace –
`PacketWorldView` (`network.world-model: packet`): chunky se parsují přímo
z paketů (`MinecraftTypes.readChunkSection`), počty sekcí a `min_y` se berou
z registru dimenzí poslaného v konfigurační fázi (spojení si vynutí plná
registry data prázdnou known-packs odpovědí) a blokové změny se aplikují
z BlockUpdate/SectionBlocksUpdate/ForgetLevelChunk paketů. Číselné block
states překládá `BlockStateMapper`: přesná tabulka se sestavuje reflexí
z registrů hostitelského serveru (stejná verze protokolu ⇒ identická globální
paleta; Bukkit API státní ID nevystavuje) s degradovaným fallbackem
„vzduch/pevné bloky", kdyby se interní API serveru změnilo. Crafting, truhly,
pec, obchod i enchant běží v packet režimu paketovými container kliky (§13);
neaktivní zůstává jen teleport API a ochočování (server-side ověření).

Stejný princip platí pro inventář: **akce** (kopání, jídlo, útok) jdou vždy
přes pakety a server je validuje jako u člověka; **čtení** (jaký materiál
držím, mám jídlo?) jde na lokálním serveru přes `ServerSideView` snapshot
serverového hráče (autoritativní a zadarmo), v packet režimu přes tentýž
snapshot sestavený klientsky (`PacketPlayerView` + `ItemMapper`, §13).

### 3. Threading model

- **Tick engine:** vlastní `ScheduledThreadPoolExecutor` (default ¼ jader).
  Každý bot má periodickou úlohu 50 ms s náhodným rozfázováním – per-bot stav
  je confinovaný na jeho tick a nepotřebuje zámky. Lifecycle zásahy z cizích
  vláken (pause, disconnect) jdou přes `tickLock`.
- **Síť:** každý bot má jednovláknový executor na **virtuálním vlákně** –
  garantované pořadí paketů, zanedbatelná cena i pro stovky botů.
- **Mailboxy:** síťové vlákno nikdy nesahá na AI stav; zapisuje do
  `BotClientState` (volatile + concurrent fronty teleportů/knockbacků),
  `EntityTracker` a `ClientInventory`. Tick vlákno fronty odbavuje.
- **Herní vlákna:** výhradně přes `MainThreadBridge` (global/region/entity
  scheduler) – plugin je beze změny Folia-kompatibilní. Herní vlákna nikdy
  nečekají na boty; boti nikdy neblokují na herních vláknech (všude
  CompletableFuture).
- **Databáze:** dedikované DB vlákno, write-behind. SQLite pool=1 (jediný
  zapisovatel, WAL), PostgreSQL plný pool.

### 4. AI: utility systém místo behavior tree / skriptů

Cíl (`Goal`) je malý stavový automat s funkcí `utility(bot)`. Mozek každých
N ticků vybere cíl s nejvyšší užitečností; aktivní cíl má hysterezi (+15 %)
proti kmitání a do výběru vstupuje per-bot šum – dva boti ve stejné situaci se
nerozhodnou stejně. Osobnost vstupuje přímo do utility (např.
`6 + curiosity·18 − laziness·6` u průzkumu), takže chování se liší
kvantitativně i kvalitativně. Strategii (cíle) od taktiky (tasky
`MineBlockTask`, `PlaceBlockTask`) odděluje vrstva `BotTask` – nové schopnosti
se skládají z hotových primitiv. Cizí pluginy registrují cíle přes
`GoalRegistry` bez zásahu do jádra.

Užitečnost je součin modulačních vrstev (dimenze, role, denní rytmus, ambice,
zaměstnání, **nálada**, **únava** a **pudy**) – každá jen jemně vychyluje
priority, osobnost zůstává hlavní. Vnitřní stav (`BotMood` – emoce z prožitků
a těla; `Vitals` – energie/únava; `BotDrives` – hierarchická arbitráž potřeb
à la Maslow) je krátkodobá vrstva mezi celoživotní osobností a per-tik utilitou:
v klidu neutrální, vypínatelná – viz [BOT_LIFE.md](BOT_LIFE.md).

### 5. Pohyb: simulovaná fyzika, ne teleportace

`BotPhysics` integruje zjednodušenou vanilla kinematiku (akcelerace, tření,
gravitace 0.08, skok 0.42, step-up 0.6, plavání, šplhání) s axis-by-axis AABB
kolizemi proti `WorldView`. Cíle nastavují jen **záměr** (`MoveInput` – směr,
sprint, skok), tudíž je pohyb spojitý, se setrvačností a v rychlostních
limitech serveru. `MovementSender` replikuje paketový vzor vanilla klienta
(Pos/Rot/PosRot dle změn, idle resend po 20 ticích, PlayerInput/PlayerCommand
pro sneak/sprint, `ClientTickEnd` každý tick) – odchylky od vzoru jsou snadno
detekovatelné anti-cheatem, proto se držíme věrně.

### 6. Humanizace jako průřezová vrstva

Vše, co by prozradilo stroj, prochází `Humanizer`em a per-bot RNG:
omezená úhlová rychlost hlavy + easing + šum + trvalá individuální chyba
míření; log-normální reakční latence škálované inteligencí/leností; útoky
s jitterem (nikdy přesných 600 ms); rozfázování ticků, spawnů i reconnectů;
chat s dobou přemýšlení a psaní, překlepy a opravami. Determinismus per-bot
seedů znamená, že bot má konzistentní „rukopis".

### 7. Persistence

Verzované migrace (`ba_schema_version`), SQL psané v průniku dialektů SQLite
a PostgreSQL (rozdíly – autoinkrement, upsert – řeší `SqlDialect`). UUID jako
TEXT, časy jako epoch millis. Paměť používá write-behind se slučováním
blízkých vzpomínek stejného druhu (opakovaná návštěva truhly zvyšuje
důležitost záznamu místo duplikace), statistiky se flushují jako delty.

### 8. Offline-mode identity

Bot dostává UUID `OfflinePlayer:<jméno>` – stejné schéma jako server
v offline režimu. Identita je tak stabilní napříč restarty (paměť, statistiky
i serverová playerdata se správně párují). Online-mode server plugin detekuje
a vytvoření bota odmítne se srozumitelnou chybou (řešení: offline server,
Velocity proxy s offline backendem, nebo vlastní gateway s `client-auth`).

Offline režim má ale i stinnou stránku: bez ověření se kdokoli může připojit
pod libovolným jménem, včetně jmen botů. Tuto zranitelnost řeší vlastní
ověřovací proces a Mojang API gateway – viz fáze 29.

## Výkonnostní vlastnosti

| Mechanismus | Efekt |
|---|---|
| Rozfázované per-bot ticky | žádné špičky zátěže, žádná synchronicita botů |
| Sdílená chunk-snapshot cache (Caffeine, TTL + invalidace) | O(1) dotazy na bloky z libovolného vlákna |
| Asynchronní A* s rozpočtem uzlů a částečnými cestami | dlouhé trasy bez blokování, plynulé doplánování |
| Virtuální vlákna pro pakety | stovky botů bez stovek OS vláken |
| Write-behind DB + delta statistiky | disk nikdy neblokuje hru |
| Line-of-sight jen nad snapshoty | žádné dotazy do živého světa z AI |

### 9. Server-side simulace tam, kde je protokol křehký

Crafting a přesuny v truhlách jdou v protokolu 26.1 přes „hashed item stacky"
– paketová implementace by byla extrémně křehká vůči změnám protokolu.
`CraftingService` proto používá `Server#craftItemResult` (respektuje skutečné
receptury serveru včetně custom receptů a craft eventů) a `ContainerService`
přesouvá itemy autoritativně na vlákně regionu truhly. Pozorovatelné chování
zůstává lidské: bot dojde k truhle, klikne (animace víka), „probírá se
obsahem" a zase ji zavře (`ContainerClose` paket). Naopak vše, co protokol
umí robustně – kopání, pokládání, jídlo, luk, kuše, štít, postel – jde vždy
pakety.

### 10. Vozidla: klientsky autoritativní simulace

Jízda ve vozidle je v Minecraftu klientsky autoritativní – řidič posílá
`ServerboundMoveVehiclePacket` a server validuje. `BoatPhysics` replikuje
vanilla kinematiku lodi (zrychlení 0.04/tick, útlum 0.9 → terminální rychlost
~7.2 bloků/s, zatáčení max 2.5°/tick) jako čistou, testovanou třídu;
`VehicleController` k ní přidává pakety (PlayerInput = „klávesy",
PaddleBoat = animace pádel, MoveVehicle = pozice) a stav nasednutí drží
`BotClientState` z clientbound SetPassengers – server je autoritou nad tím,
kdo v čem sedí. Korekce (clientbound MoveVehicle) tečou ze síťového vlákna
přes atomickou schránku.

Minecarty používají stejný vzor s vlastní kolejovou fyzikou
(`MinecartPhysics` nad abstrakcí `RailReader`): pohyb vázaný na osu koleje,
zatáčky přesměrováním v bloku, stoupání s gravitačním zrychlením ±0.0078,
napájecí koleje (boost +0.06 / brzda ×0.5), tření 0.997 a strop 0.4 bloku/tick.
Kolejová data čte `WorldRailReader` z chunk snapshotů (Bukkit `Rail` block
data), testy si trať definují přímo – fyzika je čistá a plně testovaná.

### 11. ViaVersion: detekce místo emulace více protokolů

**Volby:** (a) multi-verzní klient (více MCProtocolLib kodeků a přepínání
podle serveru), (b) jedna pevná verze klienta + překlad na serveru
(ViaVersion/ViaBackwards) s detekcí a naváděním.

**Zvoleno (b).** Multi-verzní klient znamená udržovat paketovou vrstvu,
fyziku a world model pro každou verzi protokolu zvlášť – přesně ta práce,
kterou ViaVersion už dělá na straně serveru a s komunitní údržbou. Bot proto
mluví vždy verzí zabudované MCProtocolLib a `ViaCompat` řeší zbytek:

- **Porovnává čísla protokolu, ne řetězce verzí** (Paper
  `UnsafeValues#getProtocolVersion()` vs. MCProtocolLib
  `PacketCodec#getProtocolVersion()`): patch vydání sdílející protokol
  (26.1 vs. 26.1.2) fungují nativně a porovnání řetězců by je chybně
  označilo za nekompatibilní.
- **Fail-fast s návodem:** bot novější než server → nutný ViaVersion; bot
  starší → ViaVersion + ViaBackwards. Chybí-li překlad, odmítne se už
  vytvoření bota s českou zprávou co nainstalovat (stejný vzor jako
  online-mode kontrola) místo tichého timeoutu loginu. Kill-switch
  `network.version-check: false` pro exotické překladové stacky, které
  se nejmenují ViaVersion.
- **Korektnost block-state mapperu:** Via překládá pakety do formátu
  klienta, takže bot dostává chunk data ve *své* verzi protokolu.
  `ReflectionBlockStateMapper` (tabulka z NMS registrů hostitele) je proto
  správný jen při shodě protokolu hostitele s botem – při překladu se
  automaticky degraduje na `FallbackBlockStateMapper` s varováním.
- **Cizí servery:** verzi vzdáleného serveru odtud zjistit nejde
  (`UNKNOWN_TARGET`) – při startu se jen zaloguje, jaká verze se očekává,
  a rozhodne login.

Čisté jádro (`ViaCompat.assess`) je bez závislosti na Bukkitu a testuje se
jednotkově včetně směrů překladu a chybějících pluginů.

### 13. Paketový survival: prázdná hashed predikce + server jako autorita

> **Odstraněno.** Paketový world model (hraní botů na serverech, které
> neprovozuješ) byl záměrně odstraněn – BotAlive běží výhradně na serveru,
> kde je nainstalovaný jako plugin (server režim), a nepřipojuje se na cizí
> servery. Následující text je historický záznam původního rozhodnutí;
> třídy `Packet*Station`, `PacketWorldView`, `ItemMapper` ap. v kódu
> už nejsou.

**Volby:** (a) plná implementace „hashed item stacků" (CRC32C hashe data
komponent v container klicích), (b) prázdná predikce a spolehnutí na
korekce serveru, (c) žádná manipulace s kontejnery na cizích serverech
(stav před fází 12).

**Zvoleno (b).** Klíčové pozorování protokolu 26.1: server container klik
**vždy provede** – hashovaná predikce klienta slouží jen k rozhodnutí,
které sloty musí klientovi doposlat. Pošleme-li záměrně prázdnou predikci
(`changedSlots` prázdné, `carriedItem` null), server po každém
kliku pošle SetSlot/SetContent korekce všeho, co se změnilo, a klientský
model oken (`ContainerView`) i inventáře (`ClientInventory`)
zůstává autoritativně synchronizovaný. Žádné počítání hashů = žádná
křehkost vůči změnám hash algoritmu (přesně ta, kvůli které §9 volí
server-side simulaci na lokálním serveru). Cena: pár korekčních paketů
navíc po kliku.

Stavební bloky:

- **Stanice jako rozhraní** (`core/station`): `CraftingStation`,
  `ChestStation`, `FurnaceStation`, `TradeStation`, `EnchantStation`.
  Server-side služby (§9) je implementují na lokálním serveru; v režimu
  `world-model: packet` kompoziční kořen zapojí paketové implementace.
  Cíle znají jen rozhraní – nezměnily se.
- **Toky na virtuálních vláknech** (`StationFlow`): každá operace je čitelný
  imperativní kód s lidskými pauzami mezi kliky (120–320 ms) a čekáním na
  odpovědi serveru (polling nad `ContainerView`); virtuální vlákno smí
  blokovat zadarmo. Chyba/timeout vrací prázdný výsledek – bot pokrčí
  rameny a jde dál.
- **Sdílené plánování**: progresi survival craftingu plánuje čistý
  `CraftPlanner` (jediný zdroj pravdy pro obě implementace), rozmístění
  kliků do mřížky čistý `GridPlacer` (levý klik zvedne stack, pravé kliky
  po kusu, levý vrátí zbytek) – obojí jednotkově testované. Recepty 2×2
  jdou v mřížce vlastního inventáře; 3×3 vyžadují položený ponk – když
  chybí a bot ho má v inventáři, stanice vrátí sentinel `NEED_TABLE`
  a `CraftGoal` ho položí (`PlaceBlockTask`), jako hráč.
- **Klientský snapshot** (`PacketPlayerView`): na cizím serveru není Bukkit
  `Player`, ale skoro všechna data snapshotu máme z paketů – inventář
  (`ClientInventory` + `ItemMapper` z registrů hostitele, stejný vzor
  a stejná verzní podmínka jako block-state mapper §11), vitály, XP levely
  (SetExperience) a čas světa (SetTime). Sestavením
  `ServerSideView.Snapshot` ze stejných polí funguje celá rozhodovací
  vrstva (utility cílů, výběr nástrojů, jídlo) beze změny.
- **Obchod bez skládání kliků**: `ServerboundSelectTradePacket` na vanilla
  serveru sám přesune suroviny do obchodních slotů – stačí shift-klik na
  výsledek. Enchant čte ceny nabídek z vlastností okna (SetData) a volí
  button klikem; potvrzení je pokles levelů.
- **Doby kopání klientsky**: `BreakTimeEstimator` (vanilla vzorec
  rychlost/tvrdost/dělitel 30|100) nahrazuje server-side
  `Block.getBreakSpeed`; enchanty a efekty ignoruje – je to tempo,
  `MineBlockTask` beztak ověřuje skutečné zmizení bloku.

Na cizím serveru zůstává neaktivní jen to, co ze své podstaty vyžaduje
server-side ověření: teleport API a ochočování (čtení `Tameable` stavu).

### 12. Lokalizace frází: vrstvené jazykové soubory

**Volby:** (a) jeden editovatelný `phrases.yml`, (b) jazykové soubory
`lang/<kód>.yml` s výběrem přes `chat.language`, uživatelskými přepisy
a fallbackem po kategoriích.

**Zvoleno (b).** Jeden soubor umí jen jeden jazyk najednou a upgrade pluginu
by přepisoval úpravy. Jazykové soubory se vrství: vestavěná čeština (vždy
kompletní, úplnost vynucuje konstruktor `PhraseBank` a hlídá unit test) →
vestavěný soubor zvoleného jazyka (přibalené `cs`, `en`) → uživatelský soubor
v `plugins/BotAlive/lang/`. Každá vrstva přepisuje jen kategorie, které sama
definuje – neúplný překlad botům nikdy nevezme řeč, jen se míchá s fallbackem.

Klíčová rozhodnutí:

- **Lokalizují se i rozpoznávací vzory** (`patterns.greeting`,
  `patterns.thanks`): klasifikace příchozích zpráv je stejně jazyková jako
  odpovědi – anglicky mluvící bot musí poznat „thanks", ne „díky". Rozbitý
  regex se zahlásí a spadne na předchozí vrstvu.
- **Unicode fix:** vzory se kompilují s `UNICODE_CHARACTER_CLASS` +
  `UNICODE_CASE` + `CASE_INSENSITIVE`. Původní zadrátovaný regex používal
  `\b` v ASCII režimu, kde česká diakritika není „slovní znak" – „čau" se
  nikdy nerozpoznalo. Teď fungují hranice slov pro libovolný jazyk.
- **`PhraseBank` je nemutabilní instance** (jedna na server, sdílená všemi
  boty napříč tick vlákny) místo statických konstant; kategorie jsou enum
  `PhraseCategory` – kontrakt mezi kódem a YAML, překlep v klíči chytí test.
- **Šablony se exportují** do datové složky (jen chybějící soubory), takže
  admin má co kopírovat a upravovat; per-bot výběr frází zůstává na per-bot
  RNG, boti se tedy neopakují synchronně ani ve stejném jazyce.

## Roadmapa rozšíření

Hotovo ve fázi 2: crafting progrese, střelba lukem/kuší s predikcí a
balistikou, štít, farmaření (Ageable přes chunk snapshoty), spánek v posteli,
ukládání přebytků do truhel, unit testy (A*, fyzika, překlepy, osobnosti) a CI.

Hotovo ve fázi 3: lodě (klientská simulace + plavba po vodních plochách,
pokládání lodi z inventáře) a obchodování s vesničany (prodej komodit za
smaragdy, nákup jídla, VILLAGE paměť, napojení na ekonomiku).

Hotovo ve fázi 4: minecarty (kolejová fyzika se zatáčkami, svahy a napájecími
kolejemi, pokládání vozíku na kolej) a podpora teleportace
(`Bot.teleport(Location)` API, `/botalive tp` na souřadnice, plný resync
klienta při velkém skoku – přerušení navigace/boje/plavby, přepnutí světa –
a PORTAL vzpomínky při průchodu portálem zaživa).

Hotovo ve fázi 6: klientský world model (`PacketWorldView`) pro boty na
cizích serverech – parsování chunk paketů, registry dimenzí, block-state
mapper z registrů hostitelského serveru s fallbackem.

Hotovo ve fázi 7: profese botů (`BotRole` + `RoleProfiles`) – role násobí
utility souvisejících cílů v mozku (zaměření, ne klec), vybírá se podle
osobnosti (`RolePicker`) a persistuje (migrace v2). Nové vanilla řemeslné
cíle: lov zvěře (`hunt`), rybaření se skutečnou detekcí záběru ze škubnutí
splávku (`fish`), tavení v peci s návratem pro hotovou vsázku (`smelt`)
a enchantování za XP a lapis (`enchant`); kovárna a enchant běží server-side
podle precedentu §9, otevření bloku vždy reálným interact paketem.

Hotovo ve fázi 8: PvP a aliance (`PvpCoordinator` + cíl `pvp`). Aliance jsou
emergentní – vznikají z FRIEND paměti (socializace, společný boj) místo
pevných týmů. Napadení bota (damage event) vytvoří hrozbu a svolá spojence
v dosahu; PvpGoal pak řeší obranu (odvaha vs. útěk přes SurviveGoal),
asistenci, pomstu z čerstvé ENEMY paměti a u rváčů i vyvolání potyčky.
Souboj vede tentýž `CombatController` jako PvE (proti hráčům bojuje bot
identicky). Pojistky: hlavní vypínač, zvlášť útoky na hráče (obrana povolena
vždy), zvlášť na boty, férovostní strop současných útočníků na jeden cíl.
`CombatGoal` je nově čistě PvE.

Hotovo ve fázi 9: ochočování zvířat (cíl `tame` + `TameService`). Item-based
taming (vlk/kočka/papoušek – opakované interakce se správným itemem, šanci
hází vanilla server) i mount-based (koně, osli, muly, lamy – opakované
nasedání, server shazuje přes SetPassengers). Stav `Tameable` entit se
autoritativně ověřuje server-side, mazlíčci se ukládají jako PET vzpomínky
a `CombatGoal` nikdy neútočí na vlastního mazlíčka (a nově oplácí
neutrálním zvířatům, která bota napadla – rozzuření vlci).

Hotovo ve fázi 10: podpora ViaVersion (`ViaCompat`, viz §11).

Hotovo ve fázi 11: vícejazyčné fráze (`lang/<kód>.yml`, `PhraseBankLoader`,
viz §12) – vestavěná čeština a angličtina, vlastní jazyky souborem,
lokalizované rozpoznávací vzory, fallback po kategoriích.

Hotovo ve fázi 12: plný survival na cizích serverech (viz §13) – paketové
container kliky s prázdnou hashed predikcí (crafting, truhly, pec, obchod,
enchant přes rozhraní stanic), item mapper z registrů hostitele, klientský
snapshot hráče a klientský odhad dob těžby.

Hotovo ve fázi 13: vesnice botů (`core/settlement`). Domy se nestaví
náhodně: `SettlementService` (sdílený koordinátor po vzoru
`PvpCoordinator`) drží vesnice, členství a parcely; `PlotLayout` počítá
parcely v prstencích kolem návsi (čistá geometrie) a `HouseBlueprint` se
naučil natočení (`HouseFacing`) – domy koukají dveřmi ke středu.
Rozhodování (`planHome`/`checkCohesion`) běží nad snímkem `SocialView`,
který si bot sestaví z vlastní FRIEND/ENEMY paměti a povahy – služba tak
nedrží zámky přes cizí stav a logika je jednotkově testovatelná
(repository je nullable, čas se injektuje). Členství vyrůstá z přátelství
(hodně společenští vstupují i k cizím, samotáři pod `loner-sociability`
nikdy), roztržky z ENEMY paměti (čerstvá zášť > vazby) vyhánějí boty
zakládat rivalské vesnice za `min-village-distance` a kamarádi je
následují – aliance zůstávají emergentní, žádné pevné týmy. Persistence:
migrace v3 (`ba_settlements`, `ba_settlement_members`), id jsou náhodná
(čítač by na sdílené PostgreSQL kolidoval mezi instancemi), zápisy
write-behind. Vesnice zanikají s posledním členem; fyzické domy zůstávají
ve světě jako opuštěné.

Dvě klíčová rozhodnutí proti tichým selháním: (1) **vesnice vzniká až
s hotovým domem zakladatele** (`finishHouse`) – kdyby se zakládala při
výběru staveniště, nedostavěný dům by nechal ve světě fantomovou vesnici,
která blokuje zakládání v okruhu `min-village-distance`; (2) **terén
vzdálené parcely se posuzuje až na místě** – nenačtené chunky hlásí
`BlockTraits.UNKNOWN` a kdyby se braly jako „vzduch", bot by si z dálky
otombstonoval všech 224 parcel vesnice. Bot proto parcelu nejdřív zabere,
dojde na ni (RELOCATE/GOTO_SITE) a rozhoduje s teplou chunk cache; vlastní
rozestavěné zdi se při návratu nepočítají jako překážka (dostavuje se,
nebourá). Když vesnice opakovaně nemá použitelnou parcelu, člen si po
třech marných seancích postaví dům po svém – bydlení má přednost před
urbanismem. Při stěhování se zapomíná jen starý dům/postel/přístřešek
(`BotMemory.forgetIf`), kotva spawnu zůstává.

Hotovo ve fázi 14: uzavření životního cyklu. (1) **Corpse run**
(`RecoverItemsGoal`) – dosud nevyužitá LOST_ITEMS paměť dostala konzumenta:
priorita klesá s despawn oknem, beznadějné smrti (láva/void, z death
message) se neběhají a záznam se po pokusu vždy uklidí. (2) **Údržba domu**
(`MaintainHomeGoal`) – diff proti blueprintu; orientaci zná člen z parcely,
novější sólo domy z HOME dat (`ox/oy/oz/facing`), staré se rekonstruují
(stand point, sever). (3) **Osvětlení a cestičky** – fáze DECORATE
v `BuildHouseGoal`: linie dveře→náves, pochodně vedle cesty, cestička
lopatou (vanilla use-item na grass). (4) **Obnova ambic** – po splnění se
z `Ambition.ranked(personality)` vybere další nesplněná; povaha se vývojem
mění, takže druhý sen bývá jiný. (5) **Ghost reaping** – při `load()` se
vyřadí členové vesnic s `last_seen_at` starším než `settlement.ghost-days`.
(6) **Trh mezi boty** (`MarketBoard` + `SellGoal`/`BuyGoal`) – chat je jen
prezentace, pravda o nabídkách žije ve sdílené službě (žádné parsování
vlastních zpráv); zamluvení je first-come, peníze se převádějí až při
předávce (`BotWallet` je thread-safe), takže rozpadlý obchod nikoho
neokrade; ceník `MarketPrices` je čistý a testovaný, chamtivost zdražuje,
kamarádství (sdílený `PvpCoordinator.ALLY_THRESHOLD`) zlevňuje a vydařený
obchod zapisuje oboustranné FRIEND vzpomínky – trh tká sociální síť.

Hotovo ve fázi 15: sociální udržitelnost a hráč ve smyčce.
(1) **Rozpad vztahů** (`RelationDecay` + `BotMemoryImpl`) – FRIEND/ENEMY
důležitost bez oživení denně klesá (`memory.relation-decay`, zášť rychleji,
podlaha drží stopu). Rozpad je derivovaná hodnota počítaná při čtení
z `(importance, updatedAt)` – nikdy se nepersistuje, takže se nesčítá
dvakrát ani přes restart; oživení staví na rozpadlé hodnotě (vztahy chtějí
údržbu). (2) **Drby** (`SocialGraph.exchangeGossip` v `SocializeGoal`) –
boti si při pokecu předají 1–2 vzpomínky (VILLAGE/MINE/DANGER volně,
pomluvy ENEMY jen mezi kamarády) s poloviční důležitostí a značkou
`via=gossip`; slabé drby se dál nešíří, řetěz vyhasíná, ale opakované drby
se slučují – zloděj časem získá reputaci v celé vesnici bez centrální
autority. (3) **Usmíření** (`ReconcileGoal` + `CrimeLog.pendingAmends`) –
odhalené krádeže žijí déle (na pokání musí být čas) a ochotný zloděj nese
oběti dar; přijetí (povaha oběti) srazí zášť pod práh roztržky – feud
i vesnický blok mizí, jizva (ENEMY 0.25, `via=reconciled`) zůstává.
Odmítnutý dar druhý pokus nedostane. (4) **Prodej hráčům** – hráč odpoví
na vyvolávanou nabídku „beru" (pattern `market-buy`), chat mu ji zamluví
mimo pravděpodobnostní brány (`ChatContext.marketBuyRequest`, boti se
filtrují přes `SocialGraph`) a `SellGoal` si řekne o `/pay`: platba se
ověřuje proti baseline zůstatku zachycené hned při zamluvení (hráč smí
platit cestou), zaplacené zboží nikdy nepropadne (vyloží se u pultu)
a bez Vaultu se prodej hráčům sám vypne. (5) **Vesnice vítají hráče**
(`tickVillageWelcome`) – člen u návsi přivítá skutečného hráče jménem
vesnice, kamarádovi nabídne doprovod k trhu; čistě chat s per-hráč
cooldownem. (6) **Kvalita místa při zakládání** – `localScan` zakladatele
boduje vodu do 24 a stromy do 32 bloků (levné sondy po paprscích).
(7) **Noční hlídka** (`GuardGoal`) – lovec místo spánku obchází prstenec
vesnice; boj řeší existující bojová AI s vyšší utilitou. (8) **Počasí
v utility** – stav deště/bouřky drží `BotClientState` z GameEvent paketů
(funguje i v packet režimu): bouřka boostuje návrat domů a povoluje denní
úkryt, déšť bez bouřky zvedá rybaření.

Hotovo ve fázi 16: plná podpora Netheru (`core/nether` + cíl `nether`).

- **Dimenze jako vlastnost world view** (`WorldView.dimension()`,
  `WorldDimension`): server režim čte autoritativně Bukkit
  `World.Environment`, packet režim heuristicky z protokolového klíče světa
  (`minecraft:the_nether`, `world_nether`…). Cíle vázané na overworld
  (spánek – postel v Netheru **vybuchuje**, farmy, rybaření, stavba domů,
  vesnice, trh, ukládání do truhel, klasická těžba) se mimo overworld
  odmlčí jedním sdíleným gatem (`AbstractGoal.outsideOverworld`).
- **Portálový blok je trait** (`BlockTraits.portal` + `COST_PORTAL` v A*):
  vnitřní smyčka pathfinderu zůstává čistě nad traits (žádné dotazy na
  materiály) a boti portály obcházejí – dimenzi nikdy nezmění omylem.
  Záměrný vstup penalizace neblokuje: cíl cesty smí být portálová buňka
  (konstantní přirážka, žádný zákaz).
- **Rám se staví plný, 14 obsidiánu** (`PortalBlueprint`): ekonomický rám
  bez rohů by při pokládce horní řady neměl žádného pevného souseda
  a vyžadoval dočasné opěrné bloky. Pořadí spodní řada → sloupky zdola
  → horní řada od krajů zaručuje, že každý blok má oporu v zemi nebo v už
  položeném sousedovi (invariant hlídá test). Zapaluje se křesadlem přes
  horní plochu spodního obsidiánu; křesadlo a zlaté boty přidává
  `CraftPlanner` po diamantovém krumpáči (dřív nemá smysl – obsidián jiným
  nejde vytěžit) a pazourek/obsidián si bot řekne přes `BotNeeds` wishlist.
- **PORTAL paměť z obou stran**: odchodová vzpomínka vzniká při živém
  respawnu se změnou světa (jako dřív), příletová při **prvním teleportu
  v novém světě** (`BotImpl.onSpawnComplete`) – to je pozice cílového
  portálu, tedy cesta zpátky. Návrat výpravy: paměť → sken → stavba
  vlastního portálu z kořisti → kotva `NetherMath.toNether(domov)`
  (overworld/8, celočíselně k −∞) a hledání cestou. V Netheru drží cíl
  `nether` utilitu 30 bez ohledu na config – bot přerušený bojem, jídlem
  nebo restartem serveru se k návratu vždy vrátí a v pekle „nezůstane
  bydlet" (restart uprostřed výpravy naváže s polovičním rozpočtem).
- **Výprava s rozpočty** (`NetherGoal`): časový limit
  (`nether.max-trip-minutes`), návrat při zdraví < 8 / hladu < 6 / plném
  batohu; výkopy sdílejí sondy `MineGoal` (podlaha do 3 bloků, tekutina
  v 6-okolí = stop). Gear gating je čistá funkce (`NetherReadiness`).
  Truhly pevností/bastionů vylupuje `ChestStation.lootValuables`
  (server-side i paketové kliky; klasifikace kořisti sdílená
  v `ContainerService.isValuableLoot` – hlavně **kovářské šablony**).
  Barter: se zlatými botami na nohou (nasazuje se výslovně – tier logika
  by zlato nikdy nevzala) bot piglinovi hodí ingot a počká si na zboží.
- **Netheritový řetěz**: pec taví `ANCIENT_DEBRIS` (rozšíření SMELTABLE),
  `CraftPlanner` skládá ingot (4 úlomky + 4 zlato) a kovářský stůl;
  povýšení dělá `SmithingStation` – server-side `SmithingService`
  (`ItemStack.withType` zachová enchanty a poškození, spotřeba šablony
  + ingotu dle vanilly, precedent §9) a `PacketSmithingStation` (explicitní
  dvojice kliků do slotů 0–2, výběr výsledku shift-klikem, §13). Nový cíl
  `smith` je aktivní jen s kompletní trojicí ingot+šablona+diamantový kus.
- **Neutrální mobové**: z `TrackedEntity.isHostile` vypadl ENDERMAN –
  boti na neutrální druhy (enderman, piglin, zombifikovaný piglin) útočí
  jen odvetou přes čerstvou ENEMY vzpomínku, přesně jako hráč, který si
  s hordou zombifikovaných piglinů nezačíná. Piglinům se navíc platí
  respektem: zlaté boty v Netheru = klid a možnost barteru.

Hotovo ve fázi 17: lektvary a metadata itemy.

- **Varianty itemů ve snapshotu** (`Snapshot.itemVariants`, `ItemVariants`):
  identita lektvarů, obalených šípů a enchantovaných knih žije v ItemMeta,
  ne v Materialu – snapshot proto nese slotovou mapu normalizovaných
  variant (`fire_resistance`, `sharpness:4`), plněnou v server režimu na
  vlákně entity z `PotionMeta`/`EnchantmentStorageMeta`. Packet režim čte
  typ lektvaru z komponenty `POTION_CONTENTS`: registr lektvarů je
  statický (per verze protokolu), takže `ReflectionItemMapper` sestavuje
  tabulku id → klíč z `BuiltInRegistries.POTION` hostitele stejně jako
  u itemů (§11 – platí táž verzní podmínka a degradace). Enchanty knih
  jsou naopak **dynamický** registr: `DimensionRegistry` si z konfigurační
  fáze ukládá klíče v pořadí síťových ID (stejně jako dimenze a biomy)
  a `PacketPlayerView` jimi překládá `STORED_ENCHANTMENTS` komponentu –
  boti na cizích serverech tak znají i obsah enchantovaných knih.
- **Aktivní efekty z paketů** (`BotClientState.effectActive`):
  UpdateMobEffect/RemoveMobEffect se sledují s časem vypršení (-1 =
  nekonečno), respawn efekty čistí. Funguje v obou režimech world modelu –
  je to čistě protokolová znalost.
- **Pití jako nouzový reflex, ne alchymie** (cíl `drink`): boti nevaří,
  lektvary mají z barteru a truhel. Utility se probouzí jen v nouzi:
  odolnost ohni při hoření/lávě (95 – nad bojem; 1,6 s pití se vyplatí),
  léčení/regenerace při zdraví ≤ 10. Typ se ověřuje přes varianty – láhev
  vody není medicína; splash lektvary se záměrně ignorují (házení je jiná
  mechanika). Mechanika pití zrcadlí jídlo (use + ~32 ticků); úspěch
  potvrzuje aplikovaný efekt z paketu, u okamžitého léčení nárůst zdraví.
- **Enchantované knihy u kovadliny** (`AnvilService.applyBook`
  + rozšířený `RepairGoal`): kniha z kořisti se aplikuje na nejlepší
  kompatibilní kus (Bukkit `canEnchantItem`/`conflictsWith`, vyšší úroveň
  vyhrává), spotřebuje se a strhne 4 XP úrovně – zjednodušení bez
  prior-work penalizace po precedentu oprav (§9). Obalené/svítící šípy
  jsou střelivo už tím, že je server při střelbě z luku bere z inventáře
  sám – stačilo je počítat v kontrolách zásob.

Hotovo ve fázi 18: dimenze End. (1) **Dimenzní povědomí** –
`WorldView.dimension()` (server mode z Bukkit `World.Environment`, packet
mode z klíče světa přes `WorldDimension.fromWorldKey`) a centrální
`DimensionPolicy` aplikovaná v `Brain.decide()`: jediné místo, které ví,
že v Endu/Netheru **postel exploduje** (spánek 0), že se v Endu nestaví,
nefarmaří a nenavigují vzpomínky z jiného světa (home/stash/steal), a že
denní rytmus mimo overworld neplatí. Per-goal roztroušené kontroly by
tenhle invariant dřív nebo později prolomily. (2) **Znalost portálů** –
PORTAL paměť dostala konzumenty: zapisuje ji průchod (`onRespawn`,
`data.to`), pasivní všímání si portálových bloků (pomalý sken v tick
smyčce, jen dokud bot žádný nezná), admin (`/botalive end portal`), a
šíří ji gossip (PORTAL přidán mezi místní drby). Dvě pojistky drží drby
oddělené od vlastních zážitků: kopie nese jen `type=end` (nikdy `to` –
z doslechu se nestává „vlastní průchod"), a portál, který posluchač už
zná, se znovu nevypráví – opakovaný remember by se slil do jeho záznamu
a bumpl `updatedAt`. Rozestup výprav se totiž měří z `updatedAt`
průchodové vzpomínky (bez gossip značky) – přežije restart bez nového
stavu. (3) **Bezpečnost Endu** – `EdgeGuard` (čistá třída) chrání
přímý, nenaplánovaný pohyb (panický útěk, strafing, úhyby) před hranami:
otáčí směr podél hrany, láva v cílové buňce i pod hranou nejistí nikdy,
osamělý pilíř = stát. V SurviveGoal/CombatGoal/PvpGoal se zapíná jen
v Endu (v overworldu by měnil zavedené chování – přiblížení k cíli přes
seskok, přímočarý útěk), End cíle ho mají vždy; sondu podlahy sdílí
i brzda navigátoru u hran (`cliffAhead`), takže bot nově brzdí i před
hranou lávového jezera. Pathfinding sám je vůči voidu bezpečný odjakživa
(drop scan + UNKNOWN pod minY). Obstacle pipeline se naučila **mostit propasti**
(dřív jen tekutiny) – `BridgeTask` deck nad prázdnem, směr k cíli.
Endermani jsou nově korektně **neutrální** (`isHostile` je nezahrnuje;
rozzuřené řeší ENEMY paměť z damage eventů) a humanizer má „end gaze" –
chůzi a mikro-rozhlížení s pohledem u země, cílené `lookAt` (míření,
kliky) nedotčené. (4) **Výprava jako řetěz cílů** – `end-travel`
(overworld: připravenost `EndReadiness`, cesta k portálu, nástup přes
rám – A* na portálový blok cíleně nevede, vkročení je řízený krok),
`dragon-fight` (krystaly `RangedAttack` zezdola s bezpečným odstupem,
drak přes standardní `CombatController`, úhyby před dechem; vítězství =
zmizení draka poblíž středu **potvrzené výstupním portálem** na fontáně –
pouhý výpadek z trackeru umí i drak kroužící mimo dosah sledování entit
→ `TROPHY type=dragon`, oslava, odvaha roste), `end-harvest` (osamocení endermani na perly, end stone na mosty,
chorus když je) a `end-return` (výstupní portál na fontáně existuje jen
po drakovi – „nenašel jsem portál" znamená „drak žije, zpátky do
práce"). (5) **Ambice `DRAGON_SLAYER`** – skóre COURAGE ×0.9: odvážlivec
si nejdřív splní železnou výbavu (COURAGE ×1.0), chamtivý dá přednost
i netheritu, a na draka dojde, když je odvaha dominantní rys; `Ambition.progress` přešel na `State` record (výbava, luk, znalost
portálu, trofej), gating `end.enabled` drží `BotImpl` (enum zůstává
čistý). Ceník trhu zná `ender_pearl`, chorus ovoce je nouzové jídlo
(`isEmergencyFood` – jí se až při hladu ≤ 8, teleport je menší zlo).
(6) **Řetěz očí Enderu** – `CraftPlanner` mele blaze prach (1 rod =
2 prachy; mele se, jen když na něj čeká perla – přebytek rodů zůstává
vcelku na budoucí vaření) a oči Enderu (perla + prach, strop 12 = sloty
rámu). `end-travel` u rámu bez portálu obejde prstenec a proklikne oko
do každého rámu – vyplněné rámy vklad ignorují a stav oka se z paketů
nepřečte, takže se klikají všechny naslepo (jako hráč bez F3); po
probuzení portálu se vstupuje. Když očí není dost, anotují se PORTAL
záznamy `eyes=missing`: utility pak bez zásoby očí výpravu nepustí,
po aktivaci se poznámka maže a drby (`gossipStamp`) ji šíří dál.
Házení očí (triangulace strongholdů) záměrně chybí – portál se pořád
musí nejdřív znát.

Hotovo ve fázi 19: pathfinding v2.0 – evoluce jádra a chytřejší replanning
(analýza a plán dalších fází: [docs/PATHFINDING_V2.md](PATHFINDING_V2.md)).

- **Levné jádro A***: každý výpočet má vlastní memo cache traits a pochozích
  výšek – jedna buňka se světa ptá jednou, sousední expanze ji čtou zadarmo
  (dřív se každou buňku ptalo až 8 sousedních expanzí znovu; v bludišťovém
  scénáři ~70× méně dotazů do světa, regresi hlídá
  `PathfindingEfficiencyTest`). Tie-break open setu preferuje při shodném
  f uzel s vyšším g (řeže plata expanzí na rovinách) a částečná cesta se
  vybírá podle `h·16 + g` – ze stejně blízkých přiblížení vyhrává levněji
  dosažené, hluboké zajížďky do drahých slepých kapes přestávají vítězit.
  Danger penalizace má bounding-box early-out místo O(N) smyčky na uzel.
- **Rozpočty a zrušení**: vedle uzlového rozpočtu i časový strop
  (`pathfinding.time-budget-ms`, výchozí 25 ms) – garantovaná latence
  i v členitém terénu, kde jsou uzly drahé; kontroluje se po blocích 128
  expanzí spolu se signálem zrušení. `PathRequest.cancel()` ukončí běžící
  výpočet kooperativně a `Navigator` ruší zahozené výpočty při `stop()`
  i novém cíli – pool nemele mrtvou práci při každé změně plánu (boj, útěk).
- **Replanning**: pohyblivý cíl (follow, eskorta) rozpracovanou cestu
  nezahazuje – posun cíle ≤ 2 bloky se jen zapamatuje (stará cesta končí
  u něj, zbytek se doplánuje při dokončení) a plný replán běží nejvýš
  jednou za sekundu; dřív sledování spouštělo plný A* při každém kroku
  cíle o blok. Cesta se navíc každých ~10 ticků levně validuje proti
  změnám světa (`PathValidator`): rozbitý waypoint (zazděno, láva,
  stržená podlaha) znamená replán hned, ne až fyzické zaseknutí o 2,5 s
  později. Validace je záměrně konzervativní – zneplatňuje jen stavy,
  které by odmítl i A* (jinak by se replán zacyklil), a UNKNOWN po
  vypršení chunk cache nechává být (žádné replán bouře nad dlouhými
  trasami).
- **Observabilita**: `PathfindingStats` (počty výpočtů, úplné/částečné/
  prázdné, timeouty, zrušení, průměr a maximum uzlů i ms) a příkaz
  `/botalive path <bot>` – cíl, segment, postup po waypointech, běžící
  výpočet a agregované metriky. Rozpočty ladí sekce `pathfinding.*`.
- **Simulační kontrakt plánovač ↔ fyzika**
  (`PathExecutionSimulationTest`): naplánovaná cesta se v testu skutečně
  **odejde** – tick smyčka zrcadlí `BotImpl.tick` (navigator → LiquidReflex
  → FallReflex → `BotPhysics.step`) a scénář selže, když bot fyzicky
  nedorazí, utrpí pád, vkročí do hazardu, nebo navigace eskaluje k zásahu
  do terénu. Matice: chůze/diagonály, terasy, schody, desky, sníh, seskoky,
  mezery 1–2 (i diagonální, i s dopadem níž, i s ledovým rozběhem), voda
  (přeplavání, vynoření, **potopení na dno**, podvodní tunel, skok z výšky
  do hlubiny), žebříky (výstup 10 bloků s mantlem, **sestup šachtou**),
  úzký most, klikatá chodba, nízký tunel, led u hrany útesu, zeď postavená
  uprostřed chůze (validace → obchůzka), **dveře ve zdi** (klik navigátoru
  jde přes úzké rozhraní `DoorOpener` – produkce ho adaptuje na paketový
  `BotActions.useItemOn`, test na lambdu přepínající blok, takže i dveře
  jsou kryté end-to-end), daleká segmentová trasa a 20
  seedů náhodného terénu (každá krajina s kompletním plánem musí být
  fyzicky průchozí). Simulace odhalila a opravila pět reálných mezer
  exekuce: (1) bot s hlavou pod vodou držel skok bezpodmínečně a
  naplánovaný sestup vodou nikdy neprovedl – potápěcí waypoint teď skok
  pouští (o dech se dál stará `LiquidReflex`); (2) volná svislá tolerance
  waypointů ve vodě „dokončovala" sestupy vysoko nad cílem a záchranný
  reflex bota vynesl zpět k hladině – při klesání je tolerance těsná;
  (3) sestup po žebříku posílal vodorovný vstup, který fyzika (vanilla)
  bere jako „šplhej vzhůru" – bot teď nad kolmým waypointem žebřík pustí
  a sjíždí; (4) heuristika skoku přes mezeru se řídila jen vzdáleností
  waypointu a „spadni do jámy o buňku dál" přeskakovala sprintem tam
  a zpět – skok se pozná podle rozestupu waypointů ≥ 2 (kontrakt
  plánovače); (5) odbavování waypointů při vyhlazení šlo jen podle
  blízkosti, diagonální zkratka nechala rohový waypoint stranou a okno
  vyhlazení se zaseklo – waypointy se odbavují i projekcí „je za botem".
  Ve fyzice navíc step-up nikdy nezabírá na žebříku/liáně – zbytková
  rychlost při sestupu šachtou bota „vymantlovávala" přes okraj ven.

Hotovo ve fázi 20: pathfinding v2.1 – hrubé koridory dálkových tras
(`FarPlanner`).

Přímková segmentace s laterálními posuny ±24 neuměla obejít slepá ramena
širší než posun (velké jezero, lávové pole, horský masiv) – bot skončil
u překážky, replánoval do vyčerpání a eskaloval k terraformingu.
FarPlanner hledá trasu A* nad **mřížkou povrchových sond** 8×8 bloků
(`SegmentPlanner.surfaceAt` ukotvená na výšce souseda):

- hrana mezi sousedními buňkami existuje jen při výškovém rozdílu povrchů
  ≤ 8 bloků (útes/převis = zeď) a nese přirážku za převýšení;
- vodní hladina je průchozí s dvojnásobnou cenou (plave se, ale suchá
  obchůzka vyhrává), láva a void jsou zeď;
- **nenačtené chunky jsou optimisticky průchozí** s trojnásobnou cenou
  a zděděnou výškou – bot smí vyrazit „směrem tam" jako hráč s mapou
  a detail vyřeší low-level plán s prefetchem při přiblížení; rozlišení
  „nenačteno" vs. „načteno bez povrchu" dává `WorldView.isAvailable`.

Koridor se počítá asynchronně na pathfinding poolu a navigátor z něj bere
mezicíle segmentů: nejvzdálenější bod **souvislého** úseku v dosahu 64
bloků (souvislost brání zkratování obchůzky přes jezero na body protějšího
břehu), vadný bod se přeskočí (`segmentAttempt`), každý bod se před
použitím znovu ověří povrchovou sondou a částečný koridor (vyčerpaný
rozpočet) se na svém konci přepočítá z aktuální pozice. Dokud koridor
není spočítaný – a jako trvalý fallback – jede se postaru po přímce.
Kill-switch `pathfinding.far-corridor`. Simulační kontrakt (fáze 19) má
scénář obchůzky lávového pole 40×124 bloků: bot ho s koridorem fyzicky
obejde severní stranou a nikdy nevkročí do hazardu.

Hotovo ve fázi 21: cílové predikáty pathfindingu (`PathGoal`) a dav
v simulačním kontraktu.

- **PathGoal** – cíl hledání už není jen „dojdi na blok": predikát
  dosažení + přípustná heuristika. Vestavěné cíle: `block` (dnešní
  chování; jako jediný normalizuje cíl nad deskou a používá drift
  throttle pohyblivých cílů), `near` (okruh – interakce s truhlou,
  ponkem, entitou), `anyOf` (**nejbližší dosažitelný** z kandidátů –
  strom/ruda/truhla se vybírá podle skutečné dosažitelnosti, ne
  vzdušnou čarou; multi-target hledání zadarmo), `awayFrom` (plánovaný
  útěk po pochozím terénu – žádné hrany, láva ani slepé kouty panického
  přímého běhu; heuristika ×7/blok drží přípustnost i pro diagonální
  úprky) a `yLevel` (těžební hladina; ×6/blok = nejlevnější svislý
  pohyb, pád). Predikáty přijímá A* (`findPath(start, PathGoal, …)`),
  NavigationService i Navigator (`navigateTo(from, PathGoal)`);
  mezicíle segmentů zůstávají blokové a dálková logika se řídí kotvou
  cíle (`anchor()`). Drift throttle pohyblivých cílů je zobecněný přes
  `PathGoal.sameShape` – cíl téhož tvaru s posunutou kotvou (blok za
  entitou, `near` se stejným poloměrem za jdoucím hráčem, `awayFrom`
  před pohybující se hrozbou) starou cestu dojíždí a plný replán běží
  nejvýš jednou za sekundu. První migrované goaly: `FollowPlayerGoal`
  sleduje přes `near(hráč, 3)` (cesta legitimně končí kousek od hráče,
  jako by šel člověk) a `SurviveGoal` má dvoustupňový útěk – okamžitá
  přímočará panika drží bota v pohybu hned, a jakmile se dopočítá
  plánovaný ústup `awayFrom` (po pochozím terénu, obchází lávu, hrany
  i slepé kouty, respektuje DANGER paměť), převezme řízení navigace.
  Ostatní goaly migrují postupně.
- **Dav v simulačním kontraktu** – dva boti proti sobě (chodba šířky 2
  i přesně čelní střet na volném poli) si každý tick předávají pozice
  přes `TrackedEntity` a řídí se `CrowdAvoidance.steer` stejně jako
  `BotImpl.tick`; oba musí fyzicky dorazit – deadlock z přetlačování je
  selhání testu. Plus scénář plánovaného útěku: bot sevřený mezi
  hrozbou a lávovým polem uteče na bezpečnou vzdálenost po pochozím
  terénu a do lávy nikdy nevkročí.

Hotovo ve fázi 22: osobnost v cenách cest (`PathCosts`).

Styl cesty je deterministická funkce povahy – žádný šum, kmitání tras
mezi replány by vypadalo stroze. Profil násobí jen **přirážky** nad
základní cenou kroku: odvaha zlevňuje sprint-skoky přes mezery
(0,5–1,5×), opatrnost zdražuje seskoky (1–1,8×), blízkost lávy
(0,7–1,7×) a vodu (0,8–1,5×), lenost šplhání a výskoky (0,85–1,6×).
Přípustnost heuristik drží podlahy: pád nikdy pod 6/blok (heuristika
`yLevel`), šplh nikdy pod 0,85× (oktilová svislice 10 ≤ 12·0,85).
Profil se odvozuje jednou v konstruktoru navigátoru a teče přes
`NavigationService.request(…, PathCosts)` do A* (škálované ceny se
předpočítají do instančních polí – vnitřní smyčka zůstává celočíselná).
Neutrální profil je bit-shodný s chováním před personalizací. Test
`odvaznySkaceOpatrnyObchazi`: nad stejnou roklí odvážný bot volí
sprint-skok a opatrný obchůzku – dva boti, dvě trasy, viditelná
individualita.

Hotovo ve fázi 23: rozšíření parkouru. Sprint-skok nově zvládá mezeru
**3 bloků** prázdna (vanilla dolet ~4 bloky; strop `MAX_GAP = 3`,
4 bloky se korektně odmítají) a přes jednoblokovou díru umí bot
**parkour výskok na římsu o blok výš** (jen kardinálně, letová dráha
se ověřuje o buňku výš nad odrazem i mezerou). Exekuce rozšířila
svislé meze skokového segmentu (−1.6 až +1.7 – rozestup waypointů ≥ 2
zaručuje, že jde o naplánovaný skok). Oba pohyby prošly simulačním
kontraktem: bot je fyzicky doskočí bez poškození – dolet je ověřený
proti reálné fyzice, ne jen slíbený plánovačem.

Hotovo ve fázi 24: kopací hrany v plánu cesty (`TerrainAction`,
`PathOptions.WITH_DIGGING`).

Dřív byl každý zásah do terénu reaktivní: bot došel k překážce, zasekl
se (2,5 s), replánoval, eskaloval k assistu, vykopl 1–2 bloky a celý
cyklus s plným A* se opakoval – dlouhý tunel nešel vůbec (strop 10
cyklů). Nově se po selhání pěšího plánu cesta **přepočítá s kopacími
hranami**: tunel 1×2, vylámaný schod vzhůru i dolů (nikdy kolmá šachta)
jsou hrany grafu s cenou ~8 kroků chůze za blok – pěší obchůzka vyhrává,
kdykoli rozumná existuje, a tunel je pak JEDEN souvislý plán.

- **Bezpečnost kopání**: každý blok musí být pevný, mimo deny-list
  chráněných materiálů (bedrock, obsidián, spawner a hlavně majetek –
  truhly, pece, postele, ponky, kovadliny: bot si tunel neprorazí cizím
  domem skrz vybavení) a bez tekutiny v 6-okolí (protržení by štolu
  zatopilo). Deny-list testuje obsidiánový masiv, tekutinovou pojistku
  vodní kapsa v ose tunelu – plánovač ji korektně obchází.
- **Dvojí gate**: kopací hrany se aktivují jen po selhání čistě pěšího
  plánu (empty path / marné replány po zaseknutí), jen s
  `ai.terraforming` a kill-switchem `pathfinding.planned-actions`.
  Reaktivní assist pipeline zůstává jako fallback pro vše, co plán
  neumí (mosty přes lávu, pilíře, žebříky na stěny).
- **Exekuce bez replánů**: `Path` nese mapu `TerrainAction` podle indexu
  waypointu; navigátor u zablokovaného waypointu zásah ohlásí
  (`actionNeeded`), `BotImpl` ho vykoná sekvencí `MineBlockTask`
  (`TaskSequence`, per-blok equip nejlepšího nástroje) a po
  `actionResolved` cesta pokračuje **beze změny** – validace cesty
  záměrně zablokované waypointy přeskakuje, dokud nejsou vykopané.
- **Simulační důkaz** (`prokopeTunelPodlePlanuBezReplanu`): bot se
  bedrockem ohrazeným kamenným masivem tloušťky 3 prokope podle jednoho
  plánu na ≤ 4 výpočty celkem. Půvabný vedlejší nález z ladění fixtur:
  s kamennou ohradou si plánovač korektně spočítal, že levnější tunel
  vede jednoblokovou boční stěnou ven a kolem – optimalizuje opravdu
  přes celý prostor zásahů.
- **Pokládací hrany** (druhá polovina fáze): most přes chybějící podlahu
  (opěra pod cílovou buňku) a pilíř pod vlastní nohy (uzel o patro výš)
  jako hrany grafu s cenou ~10 kroků chůze za blok. **Rozpočet
  inventáře**: plán nikdy neslíbí víc bloků, než bot má
  (`Navigator.placeBudget` ← `InventoryHelper.countBuildingBlocks`,
  strop 12 na plán jako u BridgeTasku) – počet položených bloků se hlídá
  podél celé cesty v uzlech A*. Pokládá se jen do čistě prázdných buněk,
  nikdy do tekutin, a pod opěrou běží **hloubkový sken hazardu** (jako
  u skoků): láva kdekoli v dohledné hloubce pod mostkem = žádný most,
  ani o patro výš – lávová jezera zůstávají reaktivnímu BridgeTasku.
  Nejhezčí emergentní chování: plánovač **míchá strategie jako hráč** –
  přes mezeru 5 sloupců položí 2 opory a zbytek přeskočí sprint-skokem
  z položeného decku, na plošinu +4 staví jen 3 bloky pilíře a poslední
  patro vyskočí; simulace obojí fyzicky odehrála bez poškození
  (`premostiPropastPodlePlanu`). A při ladění pojistky vymyslel boční
  lávku o dráhu vedle lávového pruhu, kde pod oporami láva není –
  testy dostaly bedrockové mantinely a pojistka hloubkový sken.

Hotovo ve fázi 25: preference cestiček (v2.3-G, poslední písmeno roadmapy
pathfindingu v2) a migrace goalů na `near` cíle.

- **Preference cestiček**: nový trait `BlockTraits.pathSurface` – udusaná
  cestička (`DIRT_PATH`, DECORATE fáze ji lopatou vyrábí z trávy), štěrk
  (vanilla vesnické silnice) a prkna (lávky, podlahy). Krok terénem HNED
  VEDLE cestového povrchu nese přirážku +1, takže bot jdoucí podél
  cestičky na ni uhne a vesnické pěšiny, které si boti sami udusávají,
  se opravdu používají (zpětná vazba vesnice ↔ pathfinding). Návrh je
  schválně dvakrát krotký: cesta nedostává slevu, okolí přirážku
  (minimální cena kroku zůstává 10 → oktilová heuristika je dál
  přípustná a trasy optimální), a daleko od cest se ceny nemění vůbec –
  globální přirážka rozvolňovala heuristiku o ~10 % všude a bludišťový
  benchmark (`PathfindingEfficiencyTest`) přestal stíhat uzlový rozpočet.
  Sběr povrchu v okolí jede zadarmo v existujícím 3×3 hazard skenu
  (nula nových dotazů do světa). Testy: cesta o řadu vedle přitáhne
  (`drziSeCesticky`), vzdálená ne (`cestickaNestojiZaVelkouZajizdku`).
- **Goaly chodí „do okruhu", ne „na blok"**: pec, ponk, kovadlina,
  truhla, postel, kompostér, obohacovací stůl, ruda, plodina i cílové
  entity (obchod, socializace, krocení, usmíření) migrovány
  z `navigateTo(blok)` na `PathGoal.near(blok, r)` s poloměrem uvnitř
  interakčního prahu goalu (vesměs r=2 pro prahy ~3, r=1 pro těsné
  prahy 2,2–2,5). Důvod je výkonnostní i sémantický: cíl „přesně tento
  blok" se u neprůchozího bloku (pec, truhla, ruda) nikdy nesplní –
  A* pokaždé spálil celý uzlový rozpočet a vrátil částečnou cestu;
  `near` končí vedle bloku za pár desítek expanzí
  (`nearCilUNepruchozihoBlokuSetriRozpocet`). U pohyblivých cílů
  (vesničan, zvíře, hráč) navíc `near` zapadá do drift throttlu
  (`sameShape`) – replány se tlumí jako u followu. Kde je cíl záměrně
  konkrétní pochozí buňka (rybářské stanoviště, stanoviště kopání,
  sběr dropů pod nohama), zůstává blokový cíl.

Hotovo ve fázi 26: pathfinding v3.0 – tři poslední známé rozpory plánu
s kolizním systémem (analýza [docs/PATHFINDING_V3.md](PATHFINDING_V3.md)).

- **Padavé bloky nad výkopem**: kopací hrana se odmítne, když má kopaná
  buňka přímo nad sebou padavý blok (písek, štěrk, beton v prášku,
  kovadlina, krápník) mimo vlastní výkop – sesyp by štolu zasypal
  a rozbil „jeden souvislý plán" na smyčku replánů. Padavý blok smí být
  sám cílem krumpáče (štěrk pod pevným stropem se kope normálně).
- **Smrtící hloubka a void**: `dropDepth` skenuje dno do 24 bloků – láva
  na dně zakazuje skok i tam, kam starý sken (8) neviděl, a stejnou
  pojistku sdílí mostní opěry. Skok nad pádem smrtící hloubky (≥ 20)
  nese příplatek škálovaný opatrností povahy, nad bezednem dvojnásobný:
  bázlivý bot volí mezi ostrovy lávku, odvážný skáče – a mostění nad
  bezednem zůstává povolené (ostrovy v Endu). Práh je záměrně „smrtící",
  ne „bolestivý": rokle hloubky 12 nechává volbu na odvaze (přísnější
  práh převracel chování odvážných, odhalil to osobnostní test).
- **Dveře v letu a v rohu**: nový `flightClear` (průchozí bez ruky na
  klice) hlídá rohy diagonál i letovou dráhu skoku – zavřené dveře už
  neřežou roh (bot je při diagonále neotvírá a odíral se o kolizi)
  a neskáče se skrz ně (letová dráha je dřív brala za průchozí jako
  chůze). Otevřené dveře nevadí – jsou bez kolize.
- **Dokončení `near` migrace (v3.1)**: zbylých 16 blokových cílů mimo
  overworld přešlo na `near` s poloměrem uvnitř prahu goalu – dračí
  souboj (střed ostrova, přiblížení ke krystalu na dostřel), End (rám
  portálu, vzpomínka na portál, end stone), Nether (základna, těžený
  blok, strukturní truhla, piglin k barteru), vozidla (vozík, loď,
  kolej), hlídka, návrat domů a průzkum (bod expedice v koruně stromu
  či jezeře už nepálí rozpočet). Záměrně blokové zůstávají cíle „stoupni
  si přesně sem": sběr dropů, stanoviště kopání a rybaření, dekorace,
  stavební buňky. Vzor „na neprůchozí blok se nedá dojít" je tím
  vyřešený v celé kódové základně.
- **`anyNear` – kandidáti podle dosažitelnosti (v3.2)**: nový predikát
  „do okruhu nejbližšího dosažitelného kandidáta" (dosavadní `anyOf`
  chce na kandidátní buňku došlápnout – pro neprůchozí rudu či postel
  se nesplní nikdy). `MineGoal` skenuje až 6 nejbližších odkrytých
  bloků (rudy i kmeny) a vytěží ten, ke kterému se skutečně došlo –
  ruda za lávou už nestojí 300 ticků timeoutu s blacklistem, A* dojde
  rovnou k dalšímu kandidátovi; žíla se dál sleduje sousedstvím.
  `SleepGoal` sbírá všechny postele v okolí a uléhá do té, ke které
  vede cesta (vlastní zapamatovaná postel má přednost jako jediný
  cíl). Truhly (`StashGoal`, `StealGoal`) záměrně nemigrují – na
  identitě truhly záleží (vlastnictví, sociální paměť) a `anyNear`
  by cíl tiše zaměnil.
- **Žebříkové hrany (v3.3)**: stěna výšky 2–8 s plným solidním čelem
  je hrana grafu „přelez po žebříku" – `TerrainAction` nese směr
  a výšku, rozpočet příček hlídá inventář (`PathOptions.maxLadders`
  ← `Navigator.ladderBudget`, strop 8 jako `LadderTask.MAX_HEIGHT`).
  Exekuci vlastní stávající reaktivní `LadderTask` (sloupec příček
  z footholdu, verifikace, jeden plynulý výstup) – plán jen říká kde
  a jak vysoko. Trigger akce má u žebříku širší svislé okno (waypoint
  je NAD botem na vršku stěny) a těsnější vodorovné (task odvozuje
  sloupec od živé pozice – bot musí stát na patě stěny). Simulace
  `prelezeZedPoZebrikuPodlePlanu`: bot fyzicky vyšplhá přilepený
  sloupec a seskočí za zdí bez poškození, vše jeden plán. Fixtures
  chtěly bedrockovou podlahu – plánovač si jinak spočítal, že levnější
  je zeď PODKOPAT (kopací hrany jsou při akčním plánování povolené),
  což je korektní chování, ne chyba. Poslední kus parity plán ↔ assist;
  z v3.3 zbývá BotTask-level simulace reaktivních tasků.
- **BotTask simulace a vanilla skok (v3.3 dokončeno)**: `FakeBotContext`
  – testovací dvojník kontextu (svět `FakeWorldView`, `useItemOn`
  pokládá držený materiál rovnou do světa s vanilla pravidlem „pevný
  blok se nepoloží do vlastního těla", inventář = počítadlo, nepoužité
  subsystémy vyhazují) – a `ReactiveTaskSimulationTest`: pilíř, most
  i žebřík se fyzicky vykonají proti `BotPhysics`. První běh odhalil
  tři produkční chyby: (1) skok měl vrchol 0,83 bloku místo vanilla
  1,2522 – gravitace se srážela už v ticku odrazu; výskoky na +1 římsu
  maskoval step-up, ale pilíř (pokládka pod nohy chce světlost ≥ 1,0)
  byl potichu nemožný. V ticku odrazu se teď gravitace neuplatní –
  přesná vanilla trajektorie. (2) Vzdušný step-up při klesání
  (kompenzace slabého skoku) uměl s opraveným skokem nelegálně přelézt
  plot – vanilla steppuje jen na zemi, klauzule odstraněna a dopady na
  římsy vychází z čisté kolize. (3) `PillarUpTask` rozhodoval ve
  vzduchu – při okamžitém potvrzení bloku se kontrola cílové výšky
  minula a pilíř rostl, dokud stačily bloky; mezi skoky se teď čeká
  na dopad. Navíc `PlaceBlockTask` verifikuje přes traits místo
  `Material.isAir` (to sahá na server Registry – vrstva tasků patří
  nad `WorldView`). `BotActions` a `InventoryHelper` přestaly být
  `final` kvůli dvojníkům.
- **v3.4 výkon – uzavřeno měřením, přepis zamítnut**: ruční benchmark
  (`PerfBenchTest`, @Disabled) ukázal ~252 uzlů/ms v adversariálním
  bludišti a žádný dominantní hotspot (alokace ~2 %, prioritní fronta
  ~12 %, zbytek memo dotazy rozprostřené po smyčce). Bucket queue by
  přinesla ~12 % za riziko změny tie-breaku, long-přepis ~2 % za
  čitelnost jádra – nevyplatí se. Časový rozpočet 25 ms správně ořezává
  nejtěžší hledání a vzor „nedosažitelný cíl pálí celý rozpočet" řeší
  migrace goalů na `near`/`anyNear`. Tím je roadmapa pathfindingu v3
  kompletní (proudy vody zůstávají vědomě odložené).

Hotovo ve fázi 27: proudy vody (P7 – poslední odložený bod analýzy v3).

- **Světový model**: `BlockTraits.liquidLevel` – 0 zdroj, 1–7 tekoucí
  (vyšší = tenčí), 8 padající sloupec, −1 ne-tekutina. Hladinu čte state
  vrstva (`Levelled`, voda i láva nově state-sensitive); materiálová
  úroveň a testové zdroje mají hladinu zdroje → nulový proud a chování
  beze změny (jezera, oceány, všechny stávající vodní scénáře).
- **`WaterFlow.at(traits, pos)`**: směr proudu z gradientu hladin
  sousedů + přepad přes hrany + tah dolů v padajícím sloupci (vanilla
  `FlowingFluid.getFlow` zjednodušeně). Bere lookup funkci – pathfinding
  počítá proud nad svou memo cache (nula dotazů navíc), fyzika nad
  živým světem.
- **Fyzika**: tekoucí voda bota snáší ~0.014/tick po směru proudu
  (terminální snos ~1,1 m/s, vanilla hodnota); test nečinného plavce
  unášeného kanálem.
- **Plánovač**: přirážka za horizontální plavání PROTI proudu
  (`COST_AGAINST_CURRENT` 15) – jen přirážka, sleva po proudu by
  rozbila přípustnost heuristiky (stejná lekce jako u preference
  cestiček); zdrojová voda se odbaví bez výpočtu proudu. Test: bot
  v koridoru volí klidnou řadu místo plavání proti proudu.
- **Simulační kontrakt**: `preplaveTekouciRekuNapric` – řeka fyzicky
  snáší plavajícího bota po směru toku a navigace kurz průběžně
  koriguje; bot dorazí bez assistu. Analýza v3 je tím vyčerpaná celá.

Hotovo ve fázi 28: boj × navigace (v4.0 – analýza
[docs/PATHFINDING_V4.md](PATHFINDING_V4.md)).

- **Hybridní bojový pohyb**: mikropohyb (strafing, rozestupy, timing
  úderů, štít) zůstává přímému řízení `CombatController`u, ale když
  přímá cesta nefunguje – chybí volná spojnice (voxelový raycast po půl
  bloku), nebo se nejlepší dosažená vzdálenost ≥ 30 ticků nezlepšuje
  mimo strafovací pásmo (příkop, plot se spojnicí) – převezme
  přiblížení `navigateTo(near(cíl, 2))` s drift throttlem pohyblivých
  cílů. `tick` vrací `null` a bojové goaly nechají pohyb navigátoru;
  hystereze drží obcházení až na dosah úderu. Konec kitingu: cíl za
  rohem, plotem či příkopem se obchází, simulace
  `obejdeZedKeKitujicimuCili` to fyzicky dokazuje.
- **Plánovaný ústup v boji**: při nízkém zdraví dvoustupňově – okamžitá
  panika (nově přes `EdgeGuard`: i pár slepých ticků s rozběhem umělo
  skončit v lávě za zády) a jakmile je plán, `awayFrom(hrozba, 12)` po
  pochozím terénu. Simulace `ustoupiKolemLavyPoPochozimTerenu`.
- **Nález simulace – latentní deadlock dvoustupňového útěku**:
  `hasPath()` se překlápí až v `navigator.tick()`, který při
  `requestMove(panika)` v BotImplu vůbec neběží – plánovaný útěk
  SurviveGoalu se tak v produkci nikdy neujal řízení a vzor tiše
  degradoval na čistou paniku. Nový `Navigator.pathReady()` vidí
  i dopočítanou, ještě nepřevzatou cestu; SurviveGoal i bojový ústup
  jedou přes něj.
- Vědomé meze: navigované přiblížení končí až na dosah melee
  (lučištník bez spojnice dojde k cíli pěšky) a s běžícím bojem se
  neaktivují akční hrany (bot se v souboji neprokopává – gate
  `!combat.engaged()` trvá).
- **Dav ustupuje přesným taskům (v4.1)**: steering `CrowdAvoidance` se
  neaplikuje, dokud běží `obstacleTask` nebo čeká zásah z plánu –
  pilíř a žebřík drží střed sloupce, pokládka a kopání míří na blok.
  Simulace `davNestrkaDoPilirujicihoBota` měří, že strkání souseda
  dotlačí stavitele až na hranu vlastního pilíře (odstup od středu
  > 0,4 bloku – pád o centimetry); kolemjdoucí se vyhne sám, jeho
  steering stojícího vidí.
- **Živé hrozby v plánování (v4.1)**: `dangerSupplier` vedle vzpomínek
  (DEATH/DANGER) přidává pozice viditelných hostilů (okruh 24, strop 8)
  – nové trasy obcházejí creepera obloukem přes stávající `COST_DANGER`
  mechanismus, místo spoléhání na paniku uprostřed cesty. Aktuální cíl
  boje se vynechává (k němu se přibližovat má). Dav dál záměrně uhýbá
  jen hráčům/botům.
- **Perturbační kontrakt (v4.2)**: `PerturbationSimulationTest` – exekuce
  cest pod rušením přes produkční knockback kanál
  (`BotPhysics.setVelocity`). Opakované strkání při chůzi, sražení do
  příkopu, strčení uprostřed sprint-skoku nad jámou s vodním dnem
  i srážení ze žebříku: bot se pokaždé vzpamatuje a dorazí bez poškození
  a bez eskalace k terraformingu. Mechanismy zotavení z v2/v3 (catch-up
  projekce, validace, reflexy, replán) obstály bez oprav – vrstva teď
  hlídá regrese trvale. Roadmapa v4 je tím kompletní (čluny a drobnosti
  vědomě odloženy).
- **P6 drobnosti (dokončení série)**: FarPlanner zná danger body –
  koridorové buňky do 12 bloků od špatné vzpomínky či živé hrozby nesou
  přirážku a hrubá trasa zóny smrti obchází (`zonuSmrtiKoridorObchazi`);
  Navigator je bere z téhož `dangerSupplier` jako low-level plán.
  Proudy na hrubosti 8×8 záměrně nemodelujeme (lokální gradient nemá
  na volbě buňky co říct, voda nese ×2). `FarmGoal` (plodiny, strop 6)
  a `EndHarvestGoal` (end stone/chorus, strop 4) přešly na kandidáty
  s `anyNear` – sklízí se to, k čemu skutečně vede cesta. Z celé série
  analýz v2–v4 zbývá jediné vědomé odložení: čluny (BoatPhysics gate).

Hotovo ve fázi 29: vlastní Mojang API gateway a ověřování botů
(`core/gateway`) – ochrana proti zneužití offline identity (viz §8).

**Problém.** Boti jsou offline klienti a server běží v `online-mode=false`
(§8). V offline režimu ale server nikoho neověřuje – kdokoli se může připojit
pod libovolným jménem, tedy i pod jménem bota (a získat jeho serverová
playerdata, pozici, roli, majetek), nebo zaplavit server falešnými „boty".
Klasické řešení (authlib-injector, cizí Yggdrasil server) je těžké nasazení
mimo dosah pluginu. BotAlive proto přináší vlastní autoritu.

**Vlastní ověřovací proces** (`CredentialAuthority`). Autorita vydává každému
připojení bota krátkodobé, na jedno použití určené pověření: token
`payload '.' HMAC_SHA256(secret, payload)`, kde payload nese `botId`, jméno,
časy a nonce (Base64URL, oddělovač `.` se v abecedě nevyskytuje). Klíč se
generuje a ukládá do `gateway-secret.key` (nebo se sdílí konfigurací mezi
servery jednoho fleetu). Autorita je čistá (bez závislosti na Bukkitu i na
síťové knihovně), hodiny se injektují a je plně jednotkově testovaná
(`CredentialAuthorityTest`) – ověření podpisu je konstantní v čase
(`MessageDigest.isEqual`), padělaný i vypršelý token se odmítne.

Dvě nezávislé cesty ověření:

- **Offline cesta** (výchozí, žádná změna serveru). Token se offline
  handshakem neposílá (nemá pole), autorita si proto při vydání zapamatuje
  *čekající autorizaci*. Server-side pojistka `BotLoginGuard`
  (`AsyncPlayerPreLoginEvent`) u každého přihlášení pod jménem spravovaného
  bota vyžaduje živou čekající autorizaci (a dle konfigurace i lokální zdroj
  spojení). Přihlášení hráčů s vlastními jmény propouští beze změny.
  Pověření se vydává v `BotConnection.connect` těsně před navázáním spojení
  (i při reconnectu), takže při vlastním přihlášení bota autorizace vždy
  existuje; impersonátor ji nezpůsobí a je odmítnut. Pojistka je záměrně
  **fail-open**: neočekávaná chyba přihlašování na serveru nikdy nerozbije
  (jen zaloguje), odmítá se jen explicitní selhání ověření.
- **Online cesta** (volitelná, `gateway.client-auth`). Vestavěná HTTP gateway
  (`MojangGatewayServer`, JDK `com.sun.net.httpserver` + Gson) reimplementuje
  tvar Mojang session API: `POST /session/minecraft/join`,
  `GET /session/minecraft/hasJoined`, `GET /session/minecraft/profile/{id}`
  a `GET /botalive/health`. Bot v online-mode handshaku pošle token na `/join`
  (klientský `SessionService` má URL Mojangu zadrátované, proto ho přepisuje
  `GatewaySessionService` přes flag `SESSION_SERVICE_KEY`), server pak profil
  vyzvedne přes `hasJoined`. Token se ověří kryptograficky (bez stavu).
  Nasazení: online-mode server (nebo proxy) nasměrovaný na gateway, např.
  `-Dminecraft.api.session.host=http://127.0.0.1:41000`. Kolotoč join →
  hasJoined je otestován reálným HTTP kolotočem (`MojangGatewayServerTest`).

**Bezpečnostní model.** Hlavní strukturální obranou proti nejběžnějšímu
vektoru (vzdálený útočník předstírající bota) je zdrojová politika:
přihlášení pod jménem bota z veřejné adresy se odmítne (loopback/LAN je OK).
Jednorázové podepsané pověření přidává obranu do hloubky – i lokální pokus
bez čerstvě vydaného tokenu neprojde. Obojí řídí sekce `gateway.*`; hlavní
vypínač `gateway.enabled`, vynucení `enforce-prelogin`, zdrojová politika
`restrict-source`. Výchozí stav chrání běžný offline server hned a bez
nutnosti cokoli na serveru přenastavovat; HTTP gateway a `client-auth`
zůstávají pro pokročilá nasazení vypnuté.

Hotovo ve fázi 30: velký obsahový balík – války sídel, najímání botů
a vnější ostrovy Endu s elytrami.

(1) **Diplomacie sídel** (`core/settlement/DiplomacyService`, migrace v6,
`ba_settlement_relations`): křivdy mezi členy různých vesnic (odhalená
krádež z knihy zločinů, napadení z damage eventu) zvedají napětí
uspořádané dvojice sídel; napětí líně chladne (decay počítaný při dotyku
vztahu). Rozhoduje **starosta na svém ticku** nad snímkem sídel – vzor
`CohesionAction`: rozhodovací jádro (`mayorTick`) je čisté a testovatelné,
hlášky a Bukkit eventy dělá obálka. Bojovnost starosty (agrese 0.6 +
odvaha 0.4) škáluje práh vyhlášení; válka = nájezdové vlny (`WarRaidGoal`,
výběr nejbojovnějších členů, cíle výhradně válečné vesnice přes
`isWarEnemy` + `PvpCoordinator.mayEngage` a férovostní strop), obranu
svolává stávající PvP mašinerie zadarmo. Padlí se válce přičítají jen
s nedávnou vlnou (oddělené `nextRaidAt`/`lastWaveAt` – plánování vs.
atribuce); únava vede k příměří s reparacemi poraženého (peněženky
starostů). Bez `pvp.enabled`+`pvp.attack-bots` se válčí jen studeně.
Rozkazy k nájezdům se nepersistují – restart je korektně zruší.

(2) **Najímání botů hráči** (`core/economy/EmploymentService`, migrace v7,
`ba_employment`): `/botalive hire <bot> <worker|guard> [dny]` s osobním
jednáním (do 16 bloků). Mzda z čistého ceníku `EmploymentPrices`
(chamtivost a lenost zdražují, ochota a kamarádství přes
`ALLY_THRESHOLD` zlevňují); platba předem přes Vault `/pay` s detekcí
baseline zůstatku (vzor `SellGoal`), `require-payment: false` pro servery
bez ekonomiky. Dělník: násobiče produktivních cílů v mozku (nový krok
v `Brain.decide` vedle ambicí) + pravidelná donáška výtěžku
(`WorkDeliveryGoal`, předávka drop&pickup). Bodyguard (`BodyguardGoal`):
následování zaměstnavatele + poplach z damage eventu skutečného hráče;
moby bije vždy, hráče a boty jen v mezích sekce `pvp`. Napadení vlastního
bota = okamžitá výpověď bez náhrady (čerstvá ENEMY vzpomínka). Nezaplacené
nabídky žijí jen v paměti.

(3) **Vnější ostrovy Endu a elytry** (`EndOuterGoal`, `GlideTask`,
rozšíření `BotPhysics`): po drakovi bot prohodí perlu gatewayí (hod =
precedent splash lektvarů; teleport se pozná skokem pozice), zapamatuje
si zpáteční gateway, end city najde server-side
`locateNearestStructure` přes `MainThreadBridge` (precedent §9; paketový
režim skenuje purpur při průzkumu), vyluští truhly (`ChestStation`),
z end ship sundá elytry (útok na item frame – jediná vanilla struktura
s item framem v Endu, metadata netřeba) a oblékne je pravým klikem.
Fyzika nově simuluje **levitaci** (zásah shulkerem – bez toho by se
klient rozjel se serverem) a **slow falling**; elytrový let je plná
vanilla aerodynamika (vztlak z cos²pitch, konverze klesání v tah, flare)
– pozor, běžné vzdušné tření 0.91 se na letícího neaplikuje, jinak
klouzání umře (odhalil test). `GlideTask` řídí slety konzervativně
(klouzavost 6:1 s rezervou, omezený sklon, podrovnání před dosedem);
`end-return`/`end-harvest` jsou mimo hlavní ostrov potlačené, aby bota
netáhly přes void. Vědomé meze fáze 30 (bez raket, nečtený krunýř
shulkera, města za `max-city-distance` se vzdávala) padly ve fázi 31.

Hotovo ve fázi 31: Nether a End bez známých omezení – roadmapa herních
mechanik obou dimenzí je vyčerpaná.

(1) **Entity metadata a krunýř shulkera** (`BotSessionListener`
+ `TrackedEntity.applyMetadata`): parsuje se přesně jedna položka
`SetEntityData`, kterou bot skutečně používá – peek shulkera (index 17,
pevná verze protokolu jako u všech paketů). `CombatController` na
zavřený krunýř neútočí (pancíř +20, šípy se odráží): drží se na dosah,
krouží a čeká, až se shulker otevře ke střelbě – přesně chvíle, kdy je
zranitelný. Bez známých metadat (-1) se bojuje postaru – stará data
nesmí boty zablokovat. Nově se čte i **boss bar**
(`BotClientState.bossBarHealth`, ADD/UPDATE_HEALTH/REMOVE): jediný
ukazatel zdraví bosse, který vidí i člověk – wither z něj čte fáze.

(2) **Strider přes lávové oceány** (`StriderPhysics`, `LavaCrossTask`,
`Striders`): lávová analogie lodí. `shouldBoardStrider` v `BotImpl`
zrcadlí `shouldBoardBoat` – navigace míří přes souvislou lávu širší než
`Striders.MIN_CROSS_WIDTH` (užší řeší most/obchůzka), bot má houbu na
prutu a poblíž se brouzdá strider → `LavaCrossTask` (marker
`VehicleTask`, tiká i ve vozidle). Osedlání jde pravým klikem se sedlem
a **potvrzuje se úbytkem sedla v inventáři** (metadata osedlání se
nečtou; strider osedlaný z minula se pozná tak, že se po dvou marných
sedláních prostě zkusí nasednout). Jízda je klientsky autoritativní jako
loď: `StriderPhysics` (čistá, testovaná) drží rychlost 0.096/tick na
lávě (vanilla s jezdcem), mimo lávu „mrzne" na 0.06, výška sleduje
hladinu se schodem ±1 a pevný břeh jízdu končí (`ashore`); korekční
série od serveru = stuck (pojistka proti přetlačování, vzor lodi).
Sedlo je kořist (`isValuableLoot`), houbu bot natrhá v pokřiveném lese
(forage krok výpravy) a prut s houbou skládá `CraftPlanner`, jen když
obojí čeká. `EdgeGuard` hlídá nasedání i vystupování – kvůli striderovi
se do lávy nevkračuje. Strop reaktivního lávového mostu se stal
konfigurací (`nether.lava-bridge-limit`, `BridgeTask` dostal parametr).

(3) **Vaření lektvarů** (`BrewPlanner`, `BrewingService`
/ `PacketBrewingStation`, cíl `brew`): čistý plánovač (obdoba
`CraftPlanner`) rozhoduje vsázky – voda + bradavice → awkward, z něj
odolnost ohni (magma krém) → léčení (třpytivý meloun) → síla (blaze
prach, poslední kus zůstává) → jed (pavoučí oko) → splash konverze
střelným prachem. Stanice je dvoufázová jako pec (`load`/`collect` –
vaření trvá vanilla 20 s, čekání vlastní cíl; `collect(force)` po
timeoutu vrací i nevalidní vsázku, nic nepropadne). Server-side vybírá
lahve podle `PotionMeta.getBasePotionType`; paketová stanice nakládá
explicitními dvojicemi kliků (shift-klik má ve stojanu nejednoznačné
směrování – prach je palivo i přísada) a varianty lahví z okna nečte
(vědomé zjednodušení: nevalidní kombinaci server prostě neuvaří).
Bradavici bot sklízí zralou v pevnostech (a přesazuje – pěstírna
zůstává živá), soul sand kope tamtéž a **záhon si zakládá doma**
(`FarmGoal.BED_PLACE/BED_PLANT` u domova/pole; bradavice na soul sandu
roste i v overworldu) – vaření nezávisí na návratech do pevnosti.
Lahve plní u vody (use s pohledem na hladinu), sklo taví pec z písku
vykopaného cestou. `DimensionPolicy` drží `brew` v overworldu (lahve
se plní u vody). Ofenzivní splash (`CombatGoal.maybeThrowSplash`,
existující od fáze 17+) dostal vypínač `combat.splash-potions`.

(4) **Kotva respawnu** (`NetherGoal.tryAnchor`): jednou za výpravu se
u outpostu položí kotva z kořisti (`CraftPlanner`: 4 prachy → glowstone,
6 crying obsidiánů + 3 glowstony → kotva), dobije se glowstonem (klik;
nečitelná block data v packet režimu → dvě nabití naslepo, jako hráč
bez F3) a klikem čímkoli jiným se nastaví spawn. Smrt na výpravě pak
bota vrací k portálu do Netheru místo přes půl overworldu – corpse run
(`RecoverItemsGoal`) má rázem šanci stihnout despawn okno. Exploze
nehrozí: klik kotvy je bezpečný právě v Netheru a `tickWork` jinam
nepustí.

(5) **Wither** (`WitherAltar`, cíl `wither-fight`; **default vypnuto**
`nether.wither.enabled` – opuštěný boss a díry v terénu jsou rozhodnutí
admina, ne botů; precedent válek). Geometrie oltáře je čistá třída se
stejnými invarianty jako `PortalBlueprint`: pořadí pokládky se zaručenou
oporou a **prostřední lebka poslední** (test hlídá). Lebky bot sbírá
opportunisticky – `tryHuntSkulls` ho přibližuje k wither skeletonům
a souboj převezme `CombatGoal` (hostil v dohledu má vyšší utilitu než
výprava); soul sand jde z forage kroku. Souboj: oltář ≥ 32 bloků od
OUTPOST/PORTAL vzpomínek (exploze nesmí vzít základnu), po poslední
lebce sprint pryč (11 s růstu s nezranitelností je přesně okno na
rozestup), doušek síly z vlastního vaření, nad polovinou **boss baru**
luk s kite odstupem 14–28, pod polovinou je wither obrněný (šípy se
odráží) a dobíjí se mečem přes standardní `CombatController`. Utility
38 přebíjí aktivní výpravu (30×1,15) jen s kompletní výbavou; rozběhnutý
boj drží 60 (přežití přebíjí dál) a rozpočet `max-fight-minutes` boj
utíná útěkem. Nether star = `TROPHY type=wither`, odvaha roste
(`WITHER_SLAIN` prožitek).

(6) **Rakety na elytrách** (`BotPhysics.startRocketBoost`, rozšířený
`GlideTask`): klient MUSÍ tah simulovat (server počítá totéž nad
připnutou raketou – bez klientské poloviny by korekce let utrhly);
vanilla vzorec táhne rychlost k 1,5násobku pohledu po ~16 ticků.
`GlideTask` boostuje konzervativně: jen mimo podrovnání, s rozestupem,
rozpočtem 12 raket na let a stoupavým pohledem, když je cíl výš –
a s raketami umí **start ze země** (výskok ze sprintu → křídla →
boost), takže let přestal vyžadovat převýšení
(`viableWithRockets`). Rakety skládá `CraftPlanner` z papíru a prachu
(jen s křídly), třtinu sklízí `FarmGoal` (druhý článek, základ
dorůstá).

(7) **Cesta k městu se nevzdává** (`EndOuterGoal`): město v dosahu
`max-city-distance` (tvrdý strop výpravy) se dosáhne (a) raketovým
přeletem, když bot křídla už nese (čte se hrudní slot snapshotu, ne
heuristika), (b) **end stone lávkou přes void** – `BridgeTask` dostal
parametr stropu a void legy jedou po 32 segmentech (end stone je na
ostrovech zadarmo); detekce uváznutí (120 ticků bez postupu) spouští
leg směrem k městu, 6 marných legů = návrat po vlastní lávce. Zpáteční
let domů používá rakety také (`viableWithRockets` + rezerva 4 kusy).

(8) **Shulker boxy** (`CraftPlanner` + `EndOuterGoal.BOX_*`
+ `StashGoal.placeShulkerBox`): box z 2 ulit + truhly. Na výpravě
funguje jako přenosná truhla: plný batoh (≤ 4 volné sloty) → box vedle
sebe (vzor outpost truhly), kořist do něj (`ChestStation.depositLoot`
– nová metoda obou implementací; klasifikace `isHaul` = cennosti bez
perel a raket, spotřebák cesty zůstává v ruce), box se vykope **i s
obsahem** (vanilla) a domů se nese dvojnásobný náklad v jednom slotu.
Doma ho `StashGoal` po uložení přebytků postaví vedle vlastní truhly
(CHEST vzpomínka `type=shulker_box`) – sklad se rozrůstá. Ulity
padají z shulkerů zabitých obrannou mašinerií; bot s křídly, ale bez
ulit smí na jednu extra výpravu (gate `wantsBox` v utility – jediná
výjimka z „elytry má, není za čím letět").

Průřezově: `isValuableLoot` zná suroviny všech nových řetězů (sedlo,
bradavice, přísady, prach, papír, ulity, lebky), nové kategorie frází
(`strider-ride`, `brew-done`, `wither-summon`, `wither-slain`,
`end-flight`) drží úplnostní kontrakt `PhraseBank` v cs i en, a nové
čisté třídy mají jednotkové testy (StriderPhysics, WitherAltar,
BrewPlanner, metadata TrackedEntity, raketová větev ElytraPhysics,
nové recepty CraftPlanneru).
