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

### 2. Zdroj geometrie světa: server-side snapshoty vs. parsování chunk paketů

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
„vzduch/pevné bloky", kdyby se interní API serveru změnilo. Server-side
služby (crafting, truhly, obchod, teleport API, přesné doby těžby) jsou
v packet režimu přirozeně neaktivní – pohyb, průzkum, boj, chat a paměť
fungují plně.

Stejný princip platí pro inventář: **akce** (kopání, jídlo, útok) jdou vždy
přes pakety a server je validuje jako u člověka; **čtení** (jaký materiál
držím, mám jídlo?) jde přes `ServerSideView` snapshot serverového hráče,
protože mapování síťových item ID na materiály by bylo jen křehkou rekonstrukcí
téže informace.

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
a vytvoření bota odmítne se srozumitelnou chybou (řešení: offline server nebo
Velocity proxy s offline backendem).

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

Architektonicky připravené, zatím neimplementované:

1. **Plný foreign-server survival** – paketový inventář (hashed container
   kliky) a odhad dob těžby bez server-side dat, aby crafting/truhly
   fungovaly i na cizích serverech.
