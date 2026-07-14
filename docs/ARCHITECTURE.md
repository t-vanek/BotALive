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

**Zvoleno (b), abstrakce (a) zachována.** Boti hrají na témže serveru, kde
plugin běží – server je autoritativní zdroj geometrie. Parsování chunk paketů
by vyžadovalo vlastní registry block-state ID (křehké napříč verzemi), druhou
kopii světa v paměti (stovky MB při stovkách botů) a přineslo by jen iluzi
„čistého klienta". Snapshoty jsou nemutabilní (bezpečné pro AI vlákna),
pořizují se na region vlákně (Folia-safe), sdílejí se mezi boty přes Caffeine
cache s TTL a bodovou invalidací z Bukkit eventů. Rozhraní `WorldView` je
jediné místo závislosti – čistě klientská implementace je do budoucna
zaměnitelná (např. pro boty na cizím serveru).

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
přes atomickou schránku. Nasednutí/vysednutí (interact/sneak pakety) funguje
i pro minecarty; autonomní jízda po kolejích zatím implementovaná není.

## Roadmapa rozšíření

Hotovo ve fázi 2: crafting progrese, střelba lukem/kuší s predikcí a
balistikou, štít, farmaření (Ageable přes chunk snapshoty), spánek v posteli,
ukládání přebytků do truhel, unit testy (A*, fyzika, překlepy, osobnosti) a CI.

Hotovo ve fázi 3: lodě (klientská simulace + plavba po vodních plochách,
pokládání lodi z inventáře) a obchodování s vesničany (prodej komodit za
smaragdy, nákup jídla, VILLAGE paměť, napojení na ekonomiku).

Architektonicky připravené, zatím neimplementované:

1. **Minecarty** – simulace jízdy po kolejích (mount/dismount už funguje).
2. **Klientský world model** – druhá implementace `WorldView` pro boty na
   cizích serverech.
3. **Konfigurovatelné fráze** – `PhraseBank` z YAML per jazyk.
