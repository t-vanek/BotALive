<div align="center">

# BotAlive

**Plně autonomní AI hráči pro Paper servery — skuteční klienti na úrovni protokolu, žádné NPC.**

[![Build](https://github.com/t-vanek/BotALive/actions/workflows/build.yml/badge.svg)](https://github.com/t-vanek/BotALive/actions/workflows/build.yml)
![Paper](https://img.shields.io/badge/Paper-26.1-blue)
![Java](https://img.shields.io/badge/Java-25-orange)
![Folia](https://img.shields.io/badge/Folia-podporov%C3%A1na-brightgreen)

[Vlastnosti](#vlastnosti) · [Instalace](#instalace) · [Příkazy](#příkazy) · [Konfigurace](#konfigurace) · [API pro vývojáře](#api-pro-vývojáře) · [Build ze zdrojů](#build-ze-zdrojů)

🇬🇧 [English version](README.md)

</div>

---

## O projektu

BotAlive osídlí váš server boty, kteří se **připojují jako skuteční Minecraft klienti** přes síťový protokol ([MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)). Projdou plným loginem i konfigurační fází a pak se pohybují, kopou, pokládají bloky, jedí a bojují stejnými pakety jako lidský hráč — server je validuje jako kohokoli jiného.

Každý bot má vlastní identitu, osobnost, paměť, cíle, inventář a historii, vše persistentní přes restarty. Žádné skripty, žádné pevné stromy chování: boti se sami rozhodují, pamatují si, co se jim stalo, a budují si na serveru život — domy, vesnice, obchod, přátelství i sváry.

|                    | Typické NPC pluginy        | BotAlive                                            |
|--------------------|----------------------------|-----------------------------------------------------|
| Implementace       | Serverové entity           | Skuteční síťoví klienti                             |
| Chování            | Skriptované trasy a dialogy | Utility-based AI, emergentní chování               |
| Survival           | Nanejvýš simulovaný        | Plná vanilla progrese až po netherit                |
| Persistence        | Pozice a skin              | Identita, paměť, inventář, vztahy, statistiky       |

## Vlastnosti

### 🧠 Rozhodování a osobnost

- **Utility-based AI** — mozek bota každý rozhodovací tick přepočítá užitečnost cílů (přežít, najíst se, prozkoumávat, těžit, sbírat, bojovat, socializovat, stavět úkryt, vrátit se domů…) a s hysterezí vybere ten nejlepší. Žádné skripty.
- **10 rysů osobnosti** (odvaha, opatrnost, agresivita, zvědavost, společenskost, lenost, inteligence, ochota pomoci, chamtivost, trpělivost), generovaných ze seedu. Rysy ovlivňují váhy cílů, boj, chat i drobné návyky — žádní dva boti nejsou stejní.
- **Osobnost se vyvíjí** — prožitky posouvají rysy v mezích jádra ze seedu: komu projde krádež, tomu roste chamtivost, smrt učí opatrnosti, vítězství odvaze. Drift je persistentní a viditelný v `/botalive personality`.
- **Profese** — stavitel, kopáč, dřevorubec, lovec, kovář, enchanter, obchodník, rybář, farmář, vše na vanilla mechanikách. Role je zaměření, ne klec: násobí priority souvisejících činností a přiděluje se podle osobnosti.
- **Životní ambice a denní rytmus** — každý bot sleduje dlouhodobý projekt (železná výbava, útulný domov, zbohatnout) a strukturuje si den: ráno pole, přes den těžba a stavba, večer družení, v noci postel — s osobním posunem skřivan/sova.
- **Intent vrstva** — boti umí říct, co a proč dělají: `/botalive goal` ukáže záměr („těžím iron_ore — chci si vyrobit železný krumpáč“) a na otázku „co děláš?“ v chatu odpoví podle skutečné činnosti.

### 🗺️ Navigace

- **Vlastní asynchronní A\*** s uzlovým i časovým rozpočtem, kooperativním rušením, memo cache dotazů do světa a průběžnou validací cesty proti změnám světa — herní vlákna se nikdy neblokují.
- **Eskalace jako u hráče** — když cesta nevede, bot to nevzdá: replanning → **kopací plán** (tunely 1×2 a vylámané schody jako hrany grafu, s tekutinovou pojistkou a deny-listem majetku — nikdy skrz truhly, pece či postele) → reaktivní assist (mosty přes lávu, pilíře, žebříky na stěny). Vše s rozpočtem z inventáře a pod kontrolou `ai.terraforming`.
- **Dálkové koridory** — hrubý A\* nad povrchovými sondami obchází jezera, lávová pole i masivy dřív, než nastoupí jemné plánování (`pathfinding.far-corridor`).
- **Cílové predikáty** — „do okruhu k peci“, „pryč od hrozby“, „nejbližší dosažitelná ruda“ se plánují nativně, místo aby cesta na neprůchozí blok spálila celý rozpočet uzlů.
- **Pohyb s povahou** — odvážný bot rokli přeskočí sprintem, opatrný ji obejde a drží odstup od lávy, líný se vyhýbá šplhání — a všichni se drží udusaných cestiček vlastní vesnice.
- **Voda, láva a vozidla** — plavání se simulací proudu, mosty přes lávová jezera blok po bloku, plavba loďkou vodními koridory a jízda minecartem po kolejích s klientskou vanilla kinematikou.
- **Diagnostika** — `/botalive path <bot>` ukáže cíl, waypointy, stav výpočtu a metriky A\*.

### ⚔️ Boj a PvP

- **Profily obtížnosti** (easy / normal / hard / nightmare) — strafing, sprint resety, útoky s jitterem, blokování štítem, ústup podle odvahy.
- **Dálkový boj** — luk i kuše s predikcí pohybu cíle a balistickou kompenzací.
- **Boj spolupracuje s navigací** — cíl za plotem, rohem či příkopem bot obejde (kiting nefunguje) a ústup při nízkém zdraví vede plánovaně po pochozím terénu — nikdy pozpátku do lávy.
- **PvP a aliance** (volitelné, výchozí vypnuto) — napadený bot se podle odvahy brání nebo utíká, svolá pomoc v chatu a přátelé z paměti mu přijdou na pomoc; pomsty žijí přes persistentní ENEMY paměť. Férovostní strop omezuje počet botů na jeden cíl a útok na skutečné hráče má samostatnou pojistku (`pvp.attack-players`).
- **Mazlíčci** — boti si vanilla mechanikami ochočují vlky, kočky, papoušky i koně; ochočení vlci pak bojují po jejich boku.

### 🌾 Survival progrese

- **Kompletní vanilla řetěz** — prkna → tyčky → ponk → dřevěné a kamenné nástroje → pec → pochodně → železné nástroje → štít → železné brnění → diamantová výbava → luk a šípy → truhla, loďka, dveře a postel — završeno **netheritem**.
- **Těžba s účelem** — tier gating (nekope rudu nástrojem, ze kterého by nic nepadlo), štoly k zasypané rudě, schodiště do hloubky (nikdy kolmo dolů), sledování celých žil, rozmisťování pochodní a kontrola lávy/vody před každým blokem.
- **Stanice a tavicí řetězy** — udírna i blastová pec včetně prerekvizit, soběstačný palivový okruh přes dřevěné uhlí, výběr paliva podle priority; opotřebené nástroje se nahrazují dřív, než prasknou, a opravují u kovadliny (bot si ji vyrobí a postaví, když chybí).
- **Farmaření a jídlo** — sklizeň s přesazením, composterový okruh na bone meal, obchod s vesničany (skutečné receptury a limity zásob) a rozdávání přebytků jídla okolí.
- **Domov a údržba** — boti staví skutečné domy (zdi, dveřní otvor, střecha, natočení), spí v postelích, ukládají přebytky do truhel, opravují creeper díry proti plánu domu a po smrti si běží pro výbavu, než dropy zmizí.

### 🔥 Nether a 🌌 End

- **Netherové výpravy** — vybavený bot najde portál z paměti, objeví cizí, nebo si **postaví vlastní** (vytěží 14 obsidiánu, postaví rám 4×5, zapálí křesadlem). V Netheru těží quartz, zlato a glowstone, dobývá **starodávné trosky**, vylupuje pevnosti a bastiony (kovářské šablony!), se zlatými botami směňuje s pigliny — a doma poví diamantovou výbavu na **netheritovou** se zachovanými enchanty i poškozením.
- **Kolonizace** — předsunuté základny s truhlou u netherské strany portálu, lektvar odolnosti ohni před sestupem do hloubky a návratové pojistky na zdraví, hlad i plný batoh. Kořist se propisuje do života společenství: trh, peněženky, drby o portálech, kopírování šablon.
- **Výpravy do Endu** — bot, který se o portálu dozví (průchodem, toulkami, drby od kamaráda, nebo přes `/botalive end portal`), se vybaví a bojuje jako zkušený hráč: nejdřív sestřelit krystaly, endermanům se nekoukat do očí, ochrana hran proti pádu do voidu, usazeného draka mlátit, letícího střílet. Vítězství se slaví v chatu, ukládá jako trofej a zvedá odvahu.
- **Vnější ostrovy a elytry** — po skolení draka bot s dostatkem perel jednu prohodí gatewayí, zapamatuje si zpáteční portál, najde end city (server-side hledání struktur; na cizích serverech sken purpuru), vyluští truhly věží, z end ship srazí **elytry** (item frame) a rovnou si je oblékne. Levitaci po zásahu shulkerem klient poctivě simuluje, takže se bot neroztrhne se serverem; s elytrami pak slétá z výšek konzervativním řízením letu (žádné střemhlavé nálety, podrovnání před dosedem).
- **Dimenzní disciplína** — postel se v obou dimenzích nikdy nepoužívá (vybuchla by) a cesty se portálům vyhýbají, takže bot nikdy nezmění dimenzi omylem.

### 🏘️ Společenství a ekonomika

- **Vesnice** — společenští boti zakládají a rozšiřují vesnice (s generovanými jmény jako „Pepov“ či „Nová Lhota“), parcely se přidělují v prstencích kolem návsi s dveřmi ke středu a dostavěné domy dostanou udusané cestičky s pochodněmi. Čerstvá zášť umí bota vyhnat založit si vlastní vesnici — nejlepší kamarádi se stěhují s ním.
- **Trh mezi boty** — přebytky se vyvolávají v chatu („prodávám 5x bread za 12, kdo chce?“), zamluví se, osobně předají a zaplatí. Kamarádi mají slevu, chamtivci přirážku a hladový bot s penězi si koupí jídlo, místo aby kradl.
- **Zločin má následky** — hladovějící bot si „půjčí“ z cizí truhly a v krajní nouzi i někoho přepadne (vždy respektuje sekci `pvp`; kamarádi jsou tabu). Krádeže se zapisují do sdílené knihy zločinů — majitel to odhalí, naštve se a pamatuje si.
- **Ekonomika** — výchozí interní persistentní peněženky; s nainstalovaným Vaultem boti žijí v serverové ekonomice (`/pay`, `/baltop`) a výdělky z těžby či obchodu vidí všichni.
- **Války a diplomacie** (volitelné, výchozí vypnuto) — křivdy mezi členy různých vesnic (odhalené krádeže, napadení) zvedají napětí mezi sídly. Nad prahem bojovný starosta vyhlásí válku: nájezdy táhnou na cizí náves, obranu svolává stejná mašinerie jako běžné PvP, padlí zvyšují únavu z války a unavení starostové dojednají příměří — poražený platí reparace z peněženky starosty. Výhradně mezi boty, hráčů se války nikdy netýkají; nájezdy respektují sekci `pvp` (bez ní se válčí jen „studeně“). `/botalive diplomacy` ukazuje napětí, války i příměří.
- **Boti k pronájmu** — hráč si najme bota osobně (`/botalive hire <bot> <worker|guard> [dny]`, do 16 bloků): **dělník** se soustředí na produktivní práci a výtěžek pravidelně nosí zaměstnavateli; **bodyguard** chodí s vámi a bije útočníky — moby vždy, hráče a boty jen v mezích sekce `pvp`. Mzda jde podle povahy (chamtiví a líní jsou dražší, kamarádi mají tržní slevu) a platí se předem přes Vault `/pay`. Kdo vlastního bota napadne, přijde o něj na místě — a peníze nevrací.

### 💬 Chat a lidský projev

- **Lidský pohyb** — omezená rychlost otáčení hlavy s easingem a šumem, trvalá chyba míření, log-normální reakční latence, mikro-rozhlížení, pauzy, rozfázované ticky.
- **Lidské psaní** — přemýšlení, rychlost psaní, překlepy z QWERTZ sousedů, prohození písmen a follow-up opravy „*slovo“.
- **Konverzace** — pozdravy, věcné dotazy („kde jsi?“, „co máš u sebe?“, „kde je vesnice?“) a prosby („pojď za mnou“, „dej mi jídlo“) vyřizované podle povahy a přátelství. Boti své záměry občas sami komentují.
- **Lokalizace** — všechny fráze žijí v `lang/<kód>.yml` (vestavěná čeština a angličtina, `chat.language`). Nový jazyk = jeden nový soubor; lokalizují se i rozpoznávací vzory a chybějící kategorie spadá na vestavěnou vrstvu — botům nikdy nedojde řeč.

### ⚙️ Výkon a kompatibilita

- **Vícevláknový tick engine** — 20 Hz s rozfázováním, sdílená Caffeine cache chunk snapshotů, asynchronní pathfinding pool a jednovláknové virtuální executory pro pakety. Herní vlákna se nikdy neblokují.
- **Podpora Folie** — výhradně region-aware scheduler API.
- **Hraje i na cizích serverech** — volitelný paketový world model (`network.world-model: packet`) parsuje geometrii přímo z chunk paketů a crafting, truhly, pece, obchod s vesničany i enchantování řídí protokolovými container kliky — plný survival na libovolném offline-mode serveru.
- **Detekce ViaVersion** — nesoulad verzí protokolu se odhalí při startu a vytvoření bota se odmítne se srozumitelným návodem (ViaVersion / ViaBackwards) místo tichého selhání (`network.version-check`).
- **Ověřování identity botů** — na offline serveru se kdokoli může připojit pod libovolným jménem, včetně jména bota. BotAlive vydá každému botu krátkodobé, jednorázové podepsané pověření a server-side pojistka (`AsyncPlayerPreLogin`) odmítne přihlášení, které předstírá identitu bota — hráčů s vlastními jmény se to netýká a server není třeba nijak přenastavovat. Vestavěná gateway ve tvaru Mojang session API (`gateway.*`) navíc umožní kryptografické online-mode ověření pro pokročilá nasazení.
- **Persistence** — vestavěná SQLite, volitelně PostgreSQL, write-behind ukládání a slučování blízkých vzpomínek.

## Požadavky

|           |                                                                                     |
|-----------|-------------------------------------------------------------------------------------|
| Server    | Paper 26.1.x nebo Folia (jiné verze přes ViaVersion / ViaBackwards)                 |
| Java      | 25+                                                                                 |
| Režim     | `online-mode=false` (boti jsou offline klienti), příp. Velocity s offline backendem |
| Databáze  | nic (vestavěná SQLite); volitelně PostgreSQL                                        |
| Ekonomika | výchozí interní; volitelně Vault + libovolný ekonomický plugin                      |

## Instalace

1. Zkopírujte `BotAlive-<verze>.jar` do `plugins/`.
2. Restartujte server — vygeneruje se `plugins/BotAlive/config.yml`.
3. Spusťte `/botalive create` — první bot se připojí.

> [!IMPORTANT]
> Boti jsou nepodepsaní offline klienti, server (nebo backend za proxy) proto musí běžet s `online-mode=false`. Na online-mode serveru se plugin korektně odmítne připojit a vysvětlí proč. Vestavěná gateway BotAlive chrání před stinnou stránkou offline režimu — někým, kdo předstírá jméno bota — už ve výchozím stavu a umí boty ověřit i proti online-mode serveru nasměrovanému na ni (`gateway.client-auth`).

## Příkazy

Základní příkaz `/botalive`, aliasy `/ba` a `/bots`.

| Příkaz | Popis |
|---|---|
| `create [jméno] [počet]` | Vytvoří boty (bez jména vybere lidsky vypadající jméno z poolu) |
| `remove <jméno\|all> [purge]` | Odpojí a odstraní bota; `purge` smaže i data v databázi |
| `tp <jméno>` | Teleportuje hráče k botovi |
| `tp <jméno> here` | Přivolá bota k hráči |
| `tp <jméno> <x> <y> <z> [svět]` | Teleportuje bota na souřadnice (jen admin, i z konzole) |
| `list` | Přehled botů, stavů, zdraví a aktivních cílů |
| `pause` / `resume <jméno\|all>` | Pozastaví/obnoví AI (bot zůstává připojen) |
| `personality <jméno>` | Archetyp, seed a graf rysů osobnosti včetně driftu |
| `memory <jméno> [kategorie]` | Obsah dlouhodobé paměti |
| `goal <jméno> [set <cíl>\|clear]` | Utility přehled / vynucení cíle |
| `stats <jméno>` | Vytěženo, postaveno, smrti, zabití, nachozeno, peníze… |
| `role <jméno> [role\|random]` | Zobrazí/nastaví profesi bota |
| `settlements` | Přehled vesnic botů (jméno, náves, zakladatel, členové) |
| `diplomacy` | Napětí, války a příměří mezi vesnicemi botů |
| `hire <jméno> <worker\|guard> [dny]` | Nabídka mzdy od bota (osobně, do 16 bloků) |
| `hire <jméno> confirm` | Potvrzení najmutí — bot pak čeká na `/pay` |
| `dismiss <jméno>` | Předčasná výpověď smlouvy (mzda se nevrací) |
| `end portal <x> <y> <z> [svět]` | Prozradí všem botům polohu portálu do Endu (drby ji šíří dál) |
| `path <jméno>` | Diagnostika navigace (cíl, waypointy, stav výpočtu) + metriky A\* |

### Oprávnění

| Právo | Význam | Default |
|---|---|---|
| `botalive.admin` | Plná správa botů, teleporty bez cooldownu | op |
| `botalive.teleport` | `/botalive tp <bot>` + zkrácený `list` (jen jména) | op |
| `botalive.teleport.summon` | `/botalive tp <bot> here` | op |
| `botalive.use` | Základní přístup k příkazu | všichni |

Hráčské teleporty mají konfigurovatelný cooldown (`teleport.player-cooldown-seconds`, výchozí 30 s) a lze je vypnout (`teleport.enabled: false`); admin má vždy volnou cestu.

## Konfigurace

`plugins/BotAlive/config.yml` se vygeneruje při prvním startu a každou volbu dokumentuje přímo v souboru. Nejčastěji upravované sekce:

| Sekce | Ovládá |
|---|---|
| `bots`, `spawn`, `worlds` | Počet botů, jména, pravidla spawnu, povolené světy |
| `ai.*` | Váhy cílů, terraforming, nouzové chování, denní rytmus |
| `pathfinding.*` | Uzlové/časové rozpočty, dálkový koridor, diagnostika |
| `combat.*`, `pvp.*` | Profil obtížnosti, PvP přepínače, pojistka útoků na hráče |
| `nether.*`, `end.*` | Prahy výbavy pro výpravy, rozpočty, pojistky |
| `end.outer.*` | Vnější ostrovy: rozpočet výpravy, rezerva perel, hledání struktur, elytrové lety |
| `settlement.*` | Vesnice, parcely, osvětlení, cestičky |
| `settlement.war.*` | Války a diplomacie: váhy napětí, velikost a kadence nájezdů, příměří, reparace (výchozí vypnuto) |
| `economy.*` | Integrace Vaultu, trh mezi boty |
| `economy.employment.*` | Najímání botů: mzdy, stropy smluv, platba předem |
| `chat.*` | Jazyk, chování psaní a konverzace |
| `memory.*`, `persistence.*` | Limity paměti, SQLite/PostgreSQL |
| `network.*` | World model (`packet`), kontrola verze protokolu |
| `gateway.*` | Ověřování identity botů (proti zneužití), vestavěná Mojang API gateway, online-mode ověření klienta |
| `teleport.*` | Cooldowny hráčských teleportů |

## Lokalizace

Boti mluví jazykem podle `chat.language` (výchozí `cs`). Plugin při startu vyexportuje šablony `plugins/BotAlive/lang/cs.yml` a `en.yml`:

1. **Nový jazyk** — zkopírujte šablonu na `lang/<kód>.yml` (např. `de.yml`), přeložte fráze i `patterns` (regulární výrazy rozpoznávající pozdrav a poděkování) a nastavte `chat.language: <kód>`.
2. **Úprava stávajícího jazyka** — editujte přímo `lang/cs.yml`; soubor se při upgradu pluginu nikdy nepřepisuje.
3. **Neúplný překlad nevadí** — kategorie, kterou soubor nedefinuje, spadne na vestavěnou vrstvu, a rozbitý regex se zahlásí a použije se předchozí vzor.

Placeholder `{name}` se nahrazuje jménem protistrany.

<details>
<summary>Kategorie frází</summary>

`greetings`, `confused`, `agreement`, `disagreement`, `youre-welcome`, `idle-chatter`, `death-reactions`, `combat-taunts`, `meet-player`, `pvp-help-calls`, `pvp-assist`, `pvp-taunts`, `nether-depart`, `nether-arrive`, `nether-return`, `nether-loot`, `end-depart`, `end-arrive`, `dragon-slain`, `end-return`, `end-outer-depart`, `end-city-found`, `elytra-found`, `end-outer-return`, `war-declared`, `war-raid-depart`, `war-raid-taunts`, `war-truce-offer`, `war-truce-agreed`, `hire-pay-request`, `hire-accept`, `hire-decline`, `hire-expired`, `hire-quit`, `hire-deliver`, `guard-defend`, `emojis`

</details>

## API pro vývojáře

Veřejné API žije v modulu **`botalive-api`** bez implementačních závislostí. Dokud nejsou artefakty publikované ve veřejném Maven repozitáři, sestavte ho ze zdrojů (`./gradlew :botalive-api:build`) a závislost veďte na výsledný jar.

```java
BotAliveApi api = BotAliveProvider.get();

// Registrace vlastního AI cíle pro všechny boty
api.goalRegistry().register("greet-admins", bot -> new MyGreetAdminsGoal());

// Spawn bota s pevným seedem osobnosti
api.botManager()
   .create(new BotSpawnSpec("Pepa", null, 42L))
   .thenAccept(bot -> bot.say("ahoj svete"));

// Teleportace (thread-safe, s plným resyncem klienta)
bot.teleport(location);                        // bot na lokaci
bot.teleportToPlayer(player.getUniqueId());    // bot k hráči
bot.teleportPlayerToBot(player.getUniqueId()); // hráč k botovi
```

Bukkit eventy (všechny asynchronní): `BotSpawnedEvent`, `BotRemovedEvent`, `BotChatEvent` (cancellable), `BotDiedEvent`, `BotGoalChangedEvent`, `SettlementWarDeclaredEvent`, `SettlementTruceEvent`, `BotHiredEvent`, `BotDismissedEvent`.

## Build ze zdrojů

```bash
git clone https://github.com/t-vanek/BotALive.git
cd BotALive
./gradlew build
# výsledek: botalive-core/build/libs/BotAlive-<verze>.jar
```

Vyžaduje JDK 25 (Gradle toolchain cílí na Javu 25, jak vyžaduje Paper API 26.1). Výsledný jar je self-contained — MCProtocolLib, Netty, HikariCP, Caffeine a JDBC ovladače jsou přibalené, konfliktní knihovny relokované do `dev.botalive.libs`. CI sestavuje a testuje každý push i pull request; jar pluginu je u každého běhu jako workflow artefakt.

## Architektura

Dva Gradle moduly: **`botalive-api`** (veřejná rozhraní, eventy a datové typy bez implementačních závislostí) a **`botalive-core`** (implementace — ~15 subsystémů v oddělených balíčcích: network, ai, pathfinding, physics, combat, chat, memory, persistence, economy, tasks, commands, config, scheduler, world, human).

Rozhodnutí a trade-offy popisuje [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); podrobné rozbory: [pathfinding](docs/PATHFINDING_V4.md), [růst vesnic](docs/SETTLEMENTS_GROWTH.md).

## Známá omezení a roadmapa

- **Vyžadován offline mode** — boti jsou nepodepsaní klienti. Na online-mode serveru se plugin korektně odmítne připojit a vysvětlí proč.
- **Nether** — boti nejezdí na striderech a přes velké lávové oceány nestaví dlouhé mosty (strop 12 bloků; cesta se hledá jinudy). Brewing, respawn anchor a boj s witherem nejsou v plánu; nether wart se sbírá jen jako kořist z truhel. Chybí útočné házení splash lektvarů (bot si je hází jen sám pod sebe jako nouzovou záchranu).
- **Vnější ostrovy Endu** — elytrový let je zatím jen klouzání (bez raket), krunýř shulkera se nečte (entity metadata zůstávají neparsovaná — bot občas máchne do zavřeného krunýře jako začátečník) a města za `end.outer.max-city-distance` se vzdávají, přes void se nemostí. Shulker boxy se necraftí ani nepoužívají.
- **Strongholdy** — boti netriangulují očima Enderu; portál se naučí průchodem, náhodným objevem, drby, nebo přes `/botalive end portal`. Oči Enderu si ale craftí (perla + blaze prach z netherové kořisti) a nezaplněný rám portálu si doplní sami.
- **Války** — nájezdy jen bojují (nerabují a nezapalují), hráčů se nikdy netýkají a vesnice v různých světech spolu neválčí (nájezdník mezi světy nedojde).

## Přispívání

Issues i pull requesty jsou vítané. Před otevřením PR prosím spusťte lokálně `./gradlew build` — CI pouští stejný build s testy na každém pull requestu. Změny držte úzce zaměřené a motivaci popište v popisu PR; u větších zásahů do chování je lepší nejdřív založit issue.
