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
- **Persistentní paměť** – navštívená místa, nepřátelé, přátelé, truhly, doly,
  nebezpečí, smrti, domov… v SQLite (výchozí) nebo PostgreSQL, s write-behind
  ukládáním a slučováním blízkých vzpomínek.
- **Vlastní A\* pathfinding** – asynchronní, s cenami za vodu a seskoky, tvrdým
  zákazem lávy/propastí, skoky, šplháním, otevíráním dveří a detekcí zaseknutí.
- **Lidský projev** – omezená rychlost otáčení hlavy s easingem a šumem, trvalá
  chyba míření, log-normální reakční latence, mikro-rozhlížení, pauzy,
  rozfázované ticky. Chat s přemýšlením, rychlostí psaní, překlepy (QWERTZ
  sousedé, prohození, výpadky) i follow-up opravami „*slovo“.
- **Boj s obtížnostmi** – strafing, sprint reset, útoky s jitterem, ústup podle
  odvahy; profily easy/normal/hard/nightmare. Na dálku luk i kuše (predikce
  pohybu cíle, balistická kompenzace), v melee blokování štítem.
- **Survival progrese** – boti těží dřevo a rudy, craftí (prkna → tyčky → ponk
  → dřevěné → kamenné nástroje), farmaří (sklizeň + přesazení), v noci spí
  v posteli nebo si staví úkryt a přebytky si ukládají do truhel, které si
  pamatují.
- **Lodě** – bot loď najde (nebo položí z inventáře na hladinu), nasedne,
  vybere si nejdelší vodní koridor a pluje s klientskou simulací vanilla
  kinematiky (MoveVehicle/PaddleBoat pakety); u břehu vysedne.
- **Obchod s vesničany** – prodej plodin a surovin za smaragdy, nákup jídla
  při hladu; skutečné receptury vesničana včetně limitů zásob. Objevené
  vesnice si bot pamatuje a výdělek se propisuje do ekonomiky.
- **Výkon** – vlastní vícevláknový tick engine (20 Hz, rozfázovaně), sdílená
  Caffeine cache chunk snapshotů, asynchronní pathfinding pool, jednovláknové
  virtuální executory pro pakety, žádné blokování herních vláken. Funguje na
  Paperu i Folii (výhradně region-aware scheduler API).

## Požadavky

| | |
|---|---|
| Server | Paper 26.1.x (nebo Folia) |
| Java | 25+ |
| Režim | `online-mode=false` (boti jsou offline klienti), příp. Velocity s offline backendem |
| Databáze | nic (SQLite embedded), volitelně PostgreSQL |

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
| `tp <jméno> [here]` | teleport k botovi / bota k sobě |
| `list` | přehled botů, stavů, zdraví a aktivních cílů |
| `pause / resume <jméno\|all>` | pozastaví/obnoví AI (bot zůstává připojen) |
| `personality <jméno>` | archetyp, seed a graf rysů osobnosti |
| `memory <jméno> [kategorie]` | obsah dlouhodobé paměti |
| `goal <jméno> [set <cíl>\|clear]` | utility přehled / vynucení cíle |
| `stats <jméno>` | vytěženo, postaveno, smrti, zabití, nachozeno, peníze… |

Oprávnění: `botalive.admin` (default op).

## API pro vývojáře

```java
BotAliveApi api = BotAliveProvider.get();

// vlastní AI cíl pro všechny boty
api.goalRegistry().register("greet-admins", bot -> new MyGreetAdminsGoal());

// spawn bota s daným seedem osobnosti
api.botManager()
   .create(new BotSpawnSpec("Pepa", null, 42L))
   .thenAccept(bot -> bot.say("ahoj svete"));
```

Bukkit eventy: `BotSpawnedEvent`, `BotRemovedEvent`, `BotChatEvent`
(cancellable), `BotDiedEvent`, `BotGoalChangedEvent` – všechny asynchronní.

## Architektura

Dva Gradle moduly – `botalive-api` (veřejné rozhraní bez implementačních
závislostí) a `botalive-core` (implementace, ~15 subsystémů v oddělených
balíčcích: network, ai, pathfinding, physics, combat, chat, memory,
persistence, economy, tasks, commands, config, scheduler, world, human).
Detailní popis rozhodnutí a trade-offů: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Známá omezení a roadmapa

- Minecarty: mount/dismount primitivy existují (`VehicleController`),
  autonomní jízda po kolejích (simulace rail fyziky) je na roadmapě.
- Boti vyžadují offline-mode (jsou to nepodepsaní klienti); na online-mode
  serveru se plugin korektně odmítne připojit a vysvětlí proč.
- Chat boti píší česky (vestavěná banka frází); vlastní fráze lze doplnit
  rozšířením `PhraseBank` (plánovaná konfigurace přes YAML).
