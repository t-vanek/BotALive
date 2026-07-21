# Upgrade na novou verzi Minecraftu (Paper API + MCProtocolLib)

Tenhle dokument je **playbook pro přechod BotAlive na novou verzi Minecraftu**.
Cílem architektury je, aby upgrade byl **změna na jednom místě plus lokalizované
opravy**, ne hledání „magických čísel" po celém stromu. Verze proto žijí
centralizovaně a verzně citlivá místa jsou tady vyjmenovaná.

> **Pozn. k „podpoře více verzí":** BotAlive **neběží** ve více verzích protokolu
> současně (multi-verzní klient) – to je vědomé rozhodnutí, viz
> [`ARCHITECTURE.md` §11](ARCHITECTURE.md). Bot mluví **jednou pevnou verzí**
> (verze zabudované MCProtocolLib) a hraní napříč verzemi řeší **ViaVersion /
> ViaBackwards** na straně serveru (`core/via/ViaCompat`). „Podpora více verzí"
> pro BotAlive proto znamená **levný přechod z verze na verzi**, ne běh N verzí
> najednou. Kdyby někdy padlo rozhodnutí pro multi-verzního klienta, je to
> samostatný velký projekt (paketová vrstva, fyzika a world model per protokol) –
> ne obsah tohohle dokumentu.

---

## 1. Dvě nezávislé osy verze

| Osa | Co určuje | Kde se pin nastavuje |
|-----|-----------|----------------------|
| **Paper API** | Na jaké verzi serveru se plugin nainstaluje a co vidí přes Bukkit (Material, bloky, entity, inventáře) | `paper`, `javaVersion`, `paperApiVersion` v `gradle/libs.versions.toml` |
| **MCProtocolLib** | Jakou verzí protokolu boti *mluví* jako klienti (login, pohyb, akce, boj, inventář) | `mcprotocollib` v `gradle/libs.versions.toml` |

Obě osy se povyšují **naráz** – bot má být klient stejné verze jako server, na
kterém plugin běží (jinak nastupuje ViaVersion, viz §5).

---

## 2. Rychlý postup (checklist)

1. **Katalog** – `gradle/libs.versions.toml`, skupina „Cílová verze Minecraftu":
   - `paper` – nová verze Paper API (přesný string z repo papermc).
   - `mcprotocollib` – verze pro stejný Minecraft.
   - `javaVersion` – Javu, kterou nová Paper vyžaduje (např. 25 → 26).
   - `paperApiVersion` – `major.minor` Minecraftu (hodnota `api-version` v plugin.yml).
2. **CI** – `.github/workflows/build.yml`, krok „Set up JDK": `java-version`
   musí ručně ladit s `javaVersion` (GitHub Actions neumí číst version catalog).
3. **Build** – `./gradlew build` na JDK z bodu 1. Pak řeš, co se nezkompiluje
   (§3) a co degraduje za běhu (§4).
4. **Dokumentace** – README (`Požadavky` / build sekce zmiňují „JDK 25").

`plugin.yml` (`version`, `api-version`) se **needituje** – plní se při buildu
z katalogu přes `processResources` (`botalive-core/build.gradle.kts`).

---

## 3. Co se může nezkompilovat (ruční oprava)

Tohle jsou místa svázaná s konkrétní verzí – po bumpu je zkontroluj, když build
spadne:

- **Paketové třídy MCProtocolLib** – ~44 souborů importuje konkrétní
  `Serverbound*/Clientbound*` pakety. Mezi verzemi se mění názvy, konstruktory
  a pole paketů. Rozložení (počet dotčených souborů):

  | Balíček `core/…` | Soubory | Typicky |
  |------------------|:-------:|---------|
  | `ai` (+ `ai/goals`) | 22 | `Direction`, `EntityType`, akce cílů |
  | `network` | 8 | connection, movement sender, session listener, velocity |
  | `tasks` | 5 | úkoly posílající akční pakety |
  | `container` | 3 | container klik/tracking |
  | `via`, `vehicle`, `tame`, `gateway`, `entity`, `bot` | po 1 | – |

- **Paper / Bukkit API** – hlavně `Material` (~77 referencí), dále
  `org.bukkit.{entity,block,inventory,event}`. Mezi verzemi přibývají/mizí
  hodnoty `Material` a mění se registry. Kompilace odhalí odstraněné konstanty;
  nové bloky/itemy je třeba zohlednit ručně.

- **Reflexe do hostitelského serveru (NMS/registry)** – čte interní struktury
  serveru, tzn. nejcitlivější na verzi:
  - `core/world/state/CollisionShapes.java`
  - `core/network/BotEventLoop.java`
  - `core/via/ViaCompat.java` (`Bukkit.getUnsafe().getProtocolVersion()`)

---

## 4. Co degraduje za běhu (ne nutně chyba kompilace)

- **Block-state / item mapping z registrů hostitele** je korektní jen při
  **shodě protokolu hostitele a bota**. Když mezi nimi překládá ViaVersion,
  mapping se automaticky degraduje s varováním (viz `ARCHITECTURE.md` §11).
  Po bumpu ověř, že host i bot sdílejí protokol → žádná degradace.

- **Fyzika, doby kopání, aerodynamika elytry** jsou laděné na vanilla vzorce
  dané verze (`ARCHITECTURE.md` §-fyzika/combat). Zásadní změny vanilla mechanik
  mezi verzemi vyžadují revizi příslušných konstant.

---

## 5. Co se NEmění (a proč je upgrade levný)

- **Verze protokolu bota se čte automaticky** z kodeku MCProtocolLib
  (`ViaCompat.botVersion()/botProtocol()` nad `MinecraftCodec.CODEC`,
  `new MinecraftProtocol(...)` v `BotConnection`). Bump závislosti = nová verze
  se propíše sama, bez editace čísel v kódu.
- **World model jde přes Bukkit, ne přes parsování protokolu** – paketový world
  model byl odstraněn (`ARCHITECTURE.md` §13), takže chunk/blok data přicházejí
  z `WorldView`/`ChunkSnapshot`, ne z verzně křehkého parsování paketů.
- **Hraní napříč verzemi řeší ViaVersion** (`ViaCompat`), ne kód BotAlive – bot
  novější než server → ViaVersion; starší → ViaVersion + ViaBackwards. Detekce
  a fail-fast s návodem je hotová a jednotkově testovaná (`ViaCompatTest`).

---

## 6. Jediný zdroj pravdy – shrnutí míst

| Co | Kde | Jak se mění |
|----|-----|-------------|
| Paper API, MCProtocolLib, Java, api-version | `gradle/libs.versions.toml` | ruční bump (1 skupina) |
| Java toolchain + bytecode target | `build.gradle.kts` | **auto** z katalogu (`javaVersion`) |
| `plugin.yml` `version`, `api-version` | `botalive-core/build.gradle.kts` (processResources) | **auto** z katalogu / Gradle verze |
| CI JDK | `.github/workflows/build.yml` | ruční bump (musí ladit s `javaVersion`) |
| Build požadavky v README | `README.md`, `README.cs.md` | ruční bump |
