# BotAlive

**Plně autonomní AI hráči pro Paper servery.** Každý bot je skutečný Minecraft
klient připojený přes síťový protokol ([MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)) –
žádné NPC, žádné skripty. Bot má vlastní identitu, osobnost, paměť, cíle,
inventář a historii; po restartu serveru pokračuje tam, kde skončil.

## Vlastnosti

- **Skuteční klienti** – boti procházejí loginem i konfigurační fází protokolu,
  posílají pohybové pakety, kopou, pokládají bloky, jedí a bojují stejnými
  pakety jako lidský hráč. Server je validuje jako kohokoli jiného.
- **Utility-based AI** – žádné pevné skripty. Mozek bota každý rozhodovací tick
  přepočítává užitečnost cílů (přežít, najíst se, prozkoumávat, těžit, sbírat,
  bojovat, socializovat, stavět úkryt, vrátit se domů…) a vybírá s hysterezí
  ten nejlepší.
- **Osobnost** – 10 rysů (odvaha, opatrnost, agresivita, zvědavost,
  společenskost, lenost, inteligence, ochota pomoci, chamtivost, trpělivost)
  generovaných gaussovsky ze seedu. Rysy ovlivňují váhy cílů, boj, chat i
  drobné návyky. Žádní dva boti nejsou stejní.
- **Profese** – stavitel, kopáč, dřevorubec, lovec, kovář, enchanter,
  obchodník, rybář, farmář (vše na vanilla mechanikách: pec, enchantovací
  stůl, prut a splávek, lov zvěře…). Role je zaměření, ne klec: násobí
  priority souvisejících činností, přiděluje se nové podle osobnosti
  (agresivní odvážlivec ~ lovec, trpělivý lenoch ~ rybář) a persistuje se.
- **Persistentní paměť** – navštívená místa, nepřátelé, přátelé, truhly, doly,
  nebezpečí, smrti, domov… v SQLite (výchozí) nebo PostgreSQL, s write-behind
  ukládáním a slučováním blízkých vzpomínek.
- **Vlastní A\* pathfinding** – asynchronní, s cenami za vodu a seskoky, tvrdým
  zákazem lávy/propastí, skoky, šplháním, otevíráním dveří i branek a detekcí
  zaseknutí. Když cesta nevede, bot to nevzdá: **eskaluje jako hráč** –
  replanning → prokopání překážky (štola 1×2, schod vzhůru z jámy; s nástrojem
  a kontrolou tekutin) → přemostění mezery položeným blokem. Zásahy do terénu
  respektují `ai.terraforming` a mají strop na jednu cestu.
- **Lidský projev** – omezená rychlost otáčení hlavy s easingem a šumem, trvalá
  chyba míření, log-normální reakční latence, mikro-rozhlížení, pauzy,
  rozfázované ticky. Chat s přemýšlením, rychlostí psaní, překlepy (QWERTZ
  sousedé, prohození, výpadky) i follow-up opravami „*slovo“.
- **Vícejazyčný chat** – fráze botů žijí v `lang/<kód>.yml` (vestavěná čeština
  a angličtina, `chat.language`). Nový jazyk = nový soubor v
  `plugins/BotAlive/lang/`; lokalizují se i rozpoznávací vzory (pozdrav,
  poděkování), takže boti správně reagují i na cizojazyčné zprávy. Neúplný
  překlad spadá po kategoriích na vestavěnou vrstvu – botům nikdy nedojde řeč.
- **Boj s obtížnostmi** – strafing, sprint reset, útoky s jitterem, ústup podle
  odvahy; profily easy/normal/hard/nightmare. Na dálku luk i kuše (predikce
  pohybu cíle, balistická kompenzace), v melee blokování štítem.
- **PvP a aliance** – boti bojují mezi sebou i s hráči (volitelné, výchozí
  vypnuto): napadený se brání nebo utíká podle odvahy, svolá spojence
  („pomoc!“ v chatu) a přátelé z paměti mu přijdou na pomoc – společný boj
  přátelství dál prohlubuje. Agresivní rváči si potyčky vyvolávají sami,
  pomsty žijí přes persistentní ENEMY paměť. Férovostní strop omezuje počet
  botů útočících na jeden cíl; útok na skutečné hráče má samostatnou pojistku
  (`pvp.attack-players`), obrana po napadení funguje vždy.
- **Mazlíčci** – boti si ochočují zvířata vanilla mechanikami: vlka kostí,
  kočku rybou, papouška semínky, koně/osla/mulu/lamu opakovaným nasedáním
  (server je shazuje, dokud nepovolí). Mazlíčky si pamatují (`PET`),
  ochočení vlci pak botovi vanilla mechanikou pomáhají v boji.
- **Kompletní crafting progrese** – boti si nafarmí suroviny a projdou celý
  vanilla řetěz: prkna → tyčky → ponk → dřevěné → kamenné nástroje (včetně
  sekery a lopaty) → **pec** (vyrobí a sami postaví) → pochodně → železné
  nástroje → **štít** (nosí ho v druhé ruce a blokují s ním) → železné
  brnění → diamantové nástroje a brnění → **luk a šípy** (dálkový boj) →
  truhla (položí si vlastní) → loďka → dveře a postel. Farmaří (sklizeň +
  přesazení), v noci spí v posteli nebo si staví úkryt a přebytky ukládají
  do truhel. Netherit chybí záměrně (vyžaduje Nether).
- **Těžba s účelem** – bot ví, co mu chybí: bez kamenných nástrojů kope kámen,
  s kamenným krumpáčem shání železo a uhlí, se železným jde po diamantech
  (tier gating – nekope rudu nástrojem, ze kterého by nic nepadlo). K zasypané
  rudě si **prorazí štolu**, do hloubky sestupuje schodištěm (nikdy kolmo
  dolů, kontrola lávy/vody před každým blokem), sleduje celé žíly a ve štolách
  si rozmisťuje pochodně. Vypínatelné přes `ai.terraforming`.
- **Stavba domů** – bot si najde rovné místo, připraví staveniště a postaví
  skutečný domek (zdi, dveřní otvor, střecha). Dům si uloží jako domov a
  vrací se do něj; role stavitel staví ochotněji.
- **Boti vědí, co dělají** – intent vrstva: každý cíl umí říct, co a proč bot
  právě dělá. `/botalive goal` ukazuje záměr („těžím iron_ore – chci si
  vyrobit železný krumpáč"), na otázku „co děláš?" v chatu bot odpoví podle
  skutečné činnosti a záměry občas sám komentuje.
- **Denní rytmus** – boti si den strukturují: ráno pole a výroba, přes den
  těžba a stavba, večer družení a úklid do truhel, v noci domů. Každý bot má
  osobní posun (skřivan/sova) podle povahy; násobiče jsou jemné, osobnost
  a profese zůstávají hlavní (`ai.daily-rhythm`).
- **Sdílení a vzpomínky** – ochotní boti rozdávají přebytky jídla lidem
  okolo („na, vem si něco k jídlu") a obdarované si pamatují jako přátele;
  kopáči se vracejí do dolů, kde už dřív rudu našli.
- **Nouzové chování** – hladovějící bot bez jídla a prostředků si „půjčí"
  z cizí truhly (pár jídel, nástroj, trocha materiálu – slušní se omluví,
  chamtiví ne) a v krajní nouzi i přepadne okolního hráče či bota kvůli
  kořisti. Přepadení **vždy respektuje sekci `pvp`** (enabled /
  attack-players / attack-bots + férovostní stropy) a kamarádi jsou tabu.
  Vypínatelné přes `ai.desperation`.
- **Životní ambice** – každý bot má dlouhodobý projekt podle povahy (železná
  výbava / útulný domov / zbohatnout): jemně táhne související cíle a je
  vidět v `/botalive goal` („životní cíl: zbohatnout, krok 1/3"). Postup se
  počítá ze stavu, takže přežívá restart.
- **Učení z chyb a reflexy** – pathfinding zdražuje průchod místy, kde bot
  zemřel (paměť DEATH/DANGER); creeper v odpalové vzdálenosti spouští
  okamžitý úprk; sebrané brnění si boti sami nasazují.
- **Plná správa inventáře** – boti klikají ve vlastním okně inventáře
  (SWAP, čistý protokol → funguje i na cizích serverech): nástroje, jídlo,
  bloky i brnění si přitáhnou z hlavního inventáře do hotbaru, když je
  potřebují; odkládají při tom do prázdných/obyčejných slotů, nikdy
  neobětují nástroj ani jídlo.
- **Stanice a tavicí řetězy** – nad základní pec boti craftí **udírnu**
  (jídlo 2× rychleji) i **blastovou pec** včetně prerekvizit: pec sama
  taví cobble → kámen → smooth stone, když je na blastovou pec potřeba,
  a při nouzi o palivo pálí klády na **dřevěné uhlí** (soběstačný palivový
  okruh). Palivo se obětuje podle priority (uhlí → prkna → klády) a
  **opotřebené nástroje** (nad 85 %) se pro plánování nepočítají – bot si
  vyrobí náhradu dřív, než mu prasknou v ruce.
- **Kovadlina** – výrazně opotřebené nástroje a zbroj (nad ~60 %, pečliví
  boti dřív) si bot nese ke kovadlině – a když žádná není, vyrobí si ji
  (9 ingotů → 3 bloky železa + 4 ingoty) a postaví vedle sebe. Oprava
  spotřebuje surovinu dle kusu (železo/diamant/prkna…) a 2 XP úrovně jako
  vanilla; kovář opravuje nejochotněji.
- **Composter** – farmářský okruh přebytků: semínka, sazenice, listí,
  pšenici a další rostlinné zbytky bot hází do composteru (vyrobí si ho
  z dřevěných půlek a postaví) a sbírá z něj **bone meal** na hnojení.
- **Domov se vším všudy** – k domu boti craftí a osazují dveře, uvnitř
  pochodeň a postel (vlna z lovu ovcí → spawn point).
- **Rozumí prosbám** – chat zvládá věcné dotazy a prosby: „kde jsi?",
  „co máš u sebe?", „kde je vesnice?", „pojď za mnou", „dej mi jídlo"
  (vyhoví dle povahy a přátelství). Hladový bot navíc nejdřív slušně
  poprosí okolí, než sáhne ke krádeži.
- **Zločin má oběti** – krádeže se zapisují do sdílené knihy zločinů;
  majitel vyloupené truhly to při návštěvě odhalí, naštve se, pachatele si
  uloží jako nepřítele (pomsta přes PvP feud) a poučí se z toho. Vlastní
  truhly boti nevykrádají.
- **Vývoj osobnosti** – povaha botů se formuje prožitky: komu projde krádež
  či přepadení, tomu roste chamtivost a agrese („začíná ho to bavit") a
  jeho zábrany klesají; kdo rozdává a pomáhá, tomu roste ochota; smrt učí
  opatrnosti, vítězství odvaze. Posuny jsou malé, omezené vůči základu ze
  seedu (jádro povahy zůstává) a persistentní. `/botalive personality`
  ukazuje drift šipkami (`caution 0,54 ↗ +0,06`), proměnu bot občas sám
  okomentuje v chatu a může se změnit i jeho archetyp.
- **Lodě a minecarty** – bot vozidlo najde (nebo položí z inventáře), nasedne
  a jede s klientskou simulací vanilla kinematiky (MoveVehicle/PaddleBoat
  pakety): loď pluje nejdelším vodním koridorem a u břehu vysedne, vozík
  sleduje koleje včetně zatáček, svahů a napájecích kolejí až na konec trati.
- **Teleportace** – plné API (`Bot.teleport(Location)`,
  `Bot.teleportToPlayer(uuid)`, `Bot.teleportPlayerToBot(uuid)`) i příkazy
  pro adminy a hráče: hráč se přenese k botovi, nebo si bota přivolá k sobě
  (oddělená práva + konfigurovatelný cooldown). Vždy s plným resyncem klienta;
  průchody portálem si bot ukládá do paměti (`PORTAL`).
- **Obchod s vesničany** – prodej plodin a surovin za smaragdy, nákup jídla
  při hladu; skutečné receptury vesničana včetně limitů zásob. Objevené
  vesnice si bot pamatuje a výdělek se propisuje do ekonomiky.
- **Vault ekonomika** – je-li nainstalovaný Vault s ekonomickým pluginem
  (EssentialsX, CMI…), peníze botů žijí v serverové ekonomice: hráči jim
  můžou poslat `/pay`, boti figurují v `/baltop` a výdělky z těžby či
  obchodu se propisují všem. Bez Vaultu (nebo s `economy.vault: false`)
  se použije interní persistentní peněženka.
- **Cizí servery s plným survivalem** – volitelný klientský world model
  (`network.world-model: packet`): geometrie světa se parsuje přímo z chunk
  paketů (block states, registry dimenzí, blokové změny) a **crafting,
  truhly, pec, obchod s vesničany i enchantování běží paketovými container
  kliky** – boti hrají plný survival na libovolném offline-mode serveru.
  Kliky posílají záměrně prázdnou „hashed stack" predikci: server klik
  provede a sám pošle korekce, takže klientský model zůstává autoritativně
  synchronizovaný bez počítání hashů. Doby kopání se odhadují klientsky
  (vanilla vzorec). Mapování block states a itemů se sestavuje z registrů
  hostitelského serveru s degradovaným fallbackem.
- **ViaVersion** – boti mluví pevnou verzí protokolu; běží-li server na jiné
  verzi, plugin to při startu detekuje (porovnává čísla protokolu, takže
  patch vydání se stejným protokolem fungují nativně) a zkontroluje
  přítomnost překladu: ViaVersion (server starší než boti), ViaVersion +
  ViaBackwards (server novější). Chybí-li, vytvoření bota se odmítne
  s návodem místo tichého selhání loginu (`network.version-check`).
- **Výkon** – vlastní vícevláknový tick engine (20 Hz, rozfázovaně), sdílená
  Caffeine cache chunk snapshotů, asynchronní pathfinding pool, jednovláknové
  virtuální executory pro pakety, žádné blokování herních vláken. Funguje na
  Paperu i Folii (výhradně region-aware scheduler API).

## Požadavky

| | |
|---|---|
| Server | Paper 26.1.x (nebo Folia); jiné verze s ViaVersion/ViaBackwards |
| Java | 25+ |
| Režim | `online-mode=false` (boti jsou offline klienti), příp. Velocity s offline backendem |
| Databáze | nic (SQLite embedded), volitelně PostgreSQL |
| Ekonomika | interní (výchozí), volitelně Vault + ekonomický plugin |

## Build

```bash
./gradlew build
# výsledek: botalive-core/build/libs/BotAlive-<verze>.jar
```

Jar je self-contained (MCProtocolLib, Netty, HikariCP, Caffeine, SQLite,
PostgreSQL driver – vše relokované do `dev.botalive.libs`).

## Instalace

1. Zkopíruj `BotAlive-*.jar` do `plugins/`.
2. Restartuj server – vygeneruje se `plugins/BotAlive/config.yml`.
3. `/botalive create` – první bot se připojí.

## Příkazy (`/botalive`, alias `/ba`)

| Příkaz | Popis |
|---|---|
| `create [jméno] [počet]` | vytvoří bota (bez jména vybere lidsky vypadající jméno z poolu) |
| `remove <jméno\|all> [purge]` | odpojí a odstraní bota; `purge` smaže i data v DB |
| `tp <jméno>` | teleport hráče k botovi (právo `botalive.teleport`) |
| `tp <jméno> here` | přivolání bota k hráči (právo `botalive.teleport.summon`) |
| `tp <jméno> <x> <y> <z> [svět]` | teleport bota na souřadnice (jen admin, i z konzole) |
| `list` | přehled botů, stavů, zdraví a aktivních cílů |
| `pause / resume <jméno\|all>` | pozastaví/obnoví AI (bot zůstává připojen) |
| `personality <jméno>` | archetyp, seed a graf rysů osobnosti |
| `memory <jméno> [kategorie]` | obsah dlouhodobé paměti |
| `goal <jméno> [set <cíl>\|clear]` | utility přehled / vynucení cíle |
| `stats <jméno>` | vytěženo, postaveno, smrti, zabití, nachozeno, peníze… |
| `role <jméno> [role\|random]` | zobrazí/nastaví profesi bota |

Oprávnění:

| Právo | Význam | Default |
|---|---|---|
| `botalive.admin` | plná správa botů, teleporty bez cooldownu | op |
| `botalive.teleport` | `/botalive tp <bot>` + zkrácený `list` (jen jména) | op |
| `botalive.teleport.summon` | `/botalive tp <bot> here` (přivolání bota) | op |
| `botalive.use` | základní přístup k příkazu | všichni |

Hráčské teleporty mají konfigurovatelný cooldown
(`teleport.player-cooldown-seconds`, výchozí 30 s) a lze je vypnout
(`teleport.enabled: false`); admin má vždy volnou cestu.

## API pro vývojáře

```java
BotAliveApi api = BotAliveProvider.get();

// vlastní AI cíl pro všechny boty
api.goalRegistry().register("greet-admins", bot -> new MyGreetAdminsGoal());

// spawn bota s daným seedem osobnosti
api.botManager()
   .create(new BotSpawnSpec("Pepa", null, 42L))
   .thenAccept(bot -> bot.say("ahoj svete"));

// teleportace (vše thread-safe, s plným resyncem klienta bota)
bot.teleport(location);                        // bot na lokaci
bot.teleportToPlayer(player.getUniqueId());    // bot k hráči
bot.teleportPlayerToBot(player.getUniqueId()); // hráč k botovi
```

Bukkit eventy: `BotSpawnedEvent`, `BotRemovedEvent`, `BotChatEvent`
(cancellable), `BotDiedEvent`, `BotGoalChangedEvent` – všechny asynchronní.

## Architektura

Dva Gradle moduly – `botalive-api` (veřejné rozhraní bez implementačních
závislostí) a `botalive-core` (implementace, ~15 subsystémů v oddělených
balíčcích: network, ai, pathfinding, physics, combat, chat, memory,
persistence, economy, tasks, commands, config, scheduler, world, human).
Detailní popis rozhodnutí a trade-offů: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Jazyky frází

Boti mluví jazykem podle `chat.language` (výchozí `cs`). Plugin při startu
vyexportuje šablony `plugins/BotAlive/lang/cs.yml` a `en.yml`:

1. Nový jazyk: zkopírujte šablonu na `lang/<kód>.yml` (např. `de.yml`),
   přeložte fráze i `patterns` (regulární výrazy rozpoznávající pozdrav
   a poděkování) a nastavte `chat.language: <kód>`.
2. Úprava stávajícího jazyka: editujte přímo `lang/cs.yml` – soubor se při
   upgradu pluginu nepřepisuje.
3. Kategorie, kterou soubor nedefinuje, automaticky spadne na vestavěnou
   vrstvu; rozbitý regex se zahlásí a použije se předchozí vzor.

Placeholder `{name}` se nahrazuje jménem protistrany. Kategorie: `greetings`,
`confused`, `agreement`, `disagreement`, `youre-welcome`, `idle-chatter`,
`death-reactions`, `combat-taunts`, `meet-player`, `pvp-help-calls`,
`pvp-assist`, `pvp-taunts`, `emojis`.

## Známá omezení a roadmapa
- Boti vyžadují offline-mode (jsou to nepodepsaní klienti); na online-mode
  serveru se plugin korektně odmítne připojit a vysvětlí proč.
