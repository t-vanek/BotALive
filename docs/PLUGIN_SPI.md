# BotAlive – subsystémy pro rozšíření cizími pluginy (Plugin SPI)

Tento dokument navrhuje **subsystémy, které umožní cizím pluginům rozšiřovat
BotAlive** – přidávat botům chování, profese, paměť, reakce a integrace bez
zásahu do jádra. Je to plán, ne hotový kód: každý oddíl popisuje *současnou
mezeru*, *navrhované rozhraní* a *cenu/riziko*. Řazení je podle priority –
nahoře leží věci, které odemykají všechno ostatní.

Terminologie: **SPI** (Service Provider Interface) = rozhraní, která BotAlive
zveřejní v modulu `botalive-api`, aby na nich cizí plugin mohl stavět. Dnešní
`GoalRegistry` je jediné skutečné SPI, které existuje.

---

## Výchozí stav: co dnes cizí plugin může a nemůže

Průzkum kódu ukázal, že reálně otevřené jsou jen **dva** body:

| Bod | Stav | Kde |
|---|---|---|
| Registrace AI cílů | otevřené (v API) | `GoalRegistry` (`botalive-api/.../ai/GoalRegistry.java`), impl `GoalRegistryImpl.java`, vestavěné cíle `CompositionRoot.registerBuiltInGoals` (~`bootstrap/CompositionRoot.java:246`) |
| Fráze / jazyky | otevřené souborem | vrstvení `lang/<kód>.yml` v `PhraseBankLoader` |

Všechno ostatní je **zavřené** – uzavřený enum, natvrdo psaná mapa nebo `switch`:

| Subsystém | Proč je zavřený |
|---|---|
| DI kontejner | `di/ServiceContainer.java` je jen pro bootstrap, nevystavuje se přes `BotAliveApi` |
| Příkazy | `commands/BotAliveCommand.java` – `switch (sub)` (~`:109`), žádný registr |
| Konfigurace | `config/BotAliveConfig.java` je jeden record, bez jmenného prostoru pro plugin |
| Kategorie frází | `chat/PhraseCategory.java` – uzavřený enum |
| Profese | `api/role/BotRole.java` uzavřený enum + `role/RoleProfiles.java` natvrdo psaná mapa |
| Paměť | `api/memory/MemoryKind.java` uzavřený enum |
| Osobnost | `api/personality/Trait.java` uzavřený enum |
| Tasky | `tasks/BotTask.java` interní, běží nad interním `BotContext`, žádný registr |
| Persistence | `persistence/SchemaMigrator.java` – natvrdo psaný `List<List<String>>`, globální verze |
| Pohled na svět | `world/WorldViewRegistry.java` klíčuje jen názvem světa, tvoří pevný `SnapshotWorldView` |
| Eventy | jen `BotChatEvent` je `Cancellable`; ostatní jsou čistě pozorovací |

### Skrytá zásadní mezera

`GoalRegistry` je „otevřené", ale **napůl použitelné**. Cíl (`Goal`) dostává
v `utility/start/tick/stop` jen tenké API `Bot` (`api/bot/Bot.java`) – umí
`say`, `teleport`, `forceGoal`, číst snapshot. Neumí **navigovat, kopat,
pokládat, útočit, používat itemy** – tedy nic, kvůli čemu se cíl píše. Veškerou
skutečnou sílu drží interní `BotContext` (`core/ai/BotContext.java:27`), který
cíl získá jen přetypováním `BotContext.of(bot)` (`:196`). Jenže `BotContext`
i vše, co vrací (`Navigator`, `CombatController`, `BotActions`, `WorldView`…),
žije v `botalive-core`. Aby cizí plugin napsal smysluplný cíl, musel by
záviset na implementačním modulu a castovat – **čímž se rozbije celý smysl
hranice api/core.**

**Závěr:** dokud neexistuje veřejné akční API pro bota, nemá smysl otevírat
zbytek. Proto je subsystém 0 keystone.

---

## Návrhové principy (platí pro všechny subsystémy níže)

1. **SPI patří do `botalive-api`.** Rozhraní bez závislosti na MCProtocolLib,
   Netty, Bukkit-internals. Cizí plugin závisí jen na `botalive-api`.
2. **Registr místo enumu.** Kde je dnes uzavřený enum, zavést registr s řetězcovou
   identitou; vestavěné hodnoty se do něj registrují při startu jako první
   „plugin" (samotný BotAlive). Enumy zůstanou jako konstanty pro vestavěné.
3. **Vlastnictví a úklid.** Každá registrace nese `Plugin` majitele. Při
   `onDisable` se jeho příspěvky (cíle, tasky, role, příkazy, listenery)
   automaticky odregistrují – žádné classloader leaky, žádní mrtví boti.
4. **Izolace selhání.** Výjimka z cizího cíle/tasku nesmí shodit tick bota.
   Obalit try/catch, opakovaného viníka utlumit a zalogovat (jméno pluginu).
5. **Vláknový kontrakt je součást API.** Každá metoda dokumentuje, na jakém
   vlákně se volá (tick bota vs. libovolné) a co je thread-safe. Navazuje na
   stávající model (per-bot tick confinement, mailboxy).
6. **Aditivní a verzované.** SPI se jen rozšiřuje; `default` metody drží
   zpětnou kompatibilitu. Zvážit `@ApiStatus.Experimental` pro nové plochy.

---

## Subsystém 0 — Veřejné akční API bota (keystone) — ✅ HOTOVO

> **Stav: implementováno.** `dev.botalive.api.bot.BotControl` (+ hodnotové typy
> `Position`, `NearbyEntity`) je v API; dostane se přes `Bot.control()`.
> Implementuje ho `BotControlImpl` (core) jako bezstavovou fasádu nad
> `BotContext`. **Zvolena nebreaking varianta:** podpis `Goal` se neměnil –
> cíl dál dostává `Bot` a řídí bota přes `bot.control()`, takže žádný z ~55
> vestavěných cílů se nepřepisoval a stará API zůstala kompatibilní.
> Pokrytí: perception (pozice, vitály, čas, počasí, bloky, entity),
> navigace (intent), pohled, útok, použití itemu, výběr nástroje, inventář,
> řeč. Testy: `BotControlTest`.

**Mezera.** Viz výše: cíl má jen `Bot`, síla je v interním `BotContext`.

**Návrh.** Zveřejnit v `botalive-api` kurátorovanou, stabilní podmnožinu
schopnosti bota – `BotControl` – kterou cíl dostane, aniž by viděl core typy.
`BotContext` zůstává interní; `BotControl` je jeho bezpečná fasáda nad
hodnotovými typy z API (`Vec3`, `BlockPos`, `Direction` – nové drobné API
records).

```java
package dev.botalive.api.bot;

/** Bezpečné akční rozhraní bota pro cizí AI cíle a tasky. Volá se z tick vlákna bota. */
public interface BotControl {
    Bot bot();

    // Vnímání (nemutabilní snímky, thread-safe)
    WorldSnapshot world();                 // dotaz na blok/trait v okolí (nad WorldView)
    List<EntityView> nearbyEntities(double radius);
    double health();  double food();  Vec3 position();  boolean onGround();

    // Navigace (asynchronní pod kapotou, tady jen záměr)
    void navigateTo(BlockPos target);      // spustí A* + následování
    boolean navigating();  boolean navigationDone();  void stopNavigation();

    // Akce (jdou pakety, server je validuje jako u hráče)
    CompletableFuture<Boolean> mine(BlockPos pos);
    CompletableFuture<Boolean> place(BlockPos pos, String blockId);
    void attack(int entityId);
    void useItem();  void lookAt(Vec3 point);

    // Inventář (čtení + výběr nástroje)
    boolean has(String itemId, int count);
    boolean selectBestTool(BlockPos forBlock);

    // Řeč projde humanizací
    void say(String message);
}
```

Cíl pak dostává `BotControl` (ne holý `Bot`): `Goal.tick(BotControl ctrl)` –
viz subsystém 1. Interně `BotImpl` implementuje `BotControl` delegací na
existující subsystémy, takže **nepřidává novou logiku, jen bezpečnou fasádu**.

**Cena/riziko.** Střední. Hlavní práce je návrh stabilního povrchu a mapování
na `BotContext`. Riziko: příliš tenké API = cizí cíle budou málo mocné; příliš
tlusté = svážeme si ruce do budoucna. Řešit iterativně, začít u toho, co
používají vestavěné cíle nejčastěji (navigace, mine, place, attack, look, has).

---

## Subsystém 1 — Goal SPI (dotažení stávajícího)

> **Stav: základ hotový subsystémem 0.** Cizí cíl už dokáže bota reálně řídit
> přes `bot.control()` – to byla podstata keystone. Níže popsané *dotažení*
> (kategorie, dimenzní brány jako u vestavěných, majitel kvůli úklidu) zůstává
> nepovinné rozšíření; podpis `Goal` se kvůli zpětné kompatibilitě neměnil.

**Mezera.** `Goal` vidí jen `Bot`; nezná dimenzní/rolové/rytmické brány, které
`Brain.decide` (`ai/Brain.java:175`) aplikuje na vestavěné cíle; nemá způsob,
jak deklarovat kategorii, cooldown nebo prioritu.

**Návrh.** Nová verze `Goal` nad `BotControl` + metadata:

```java
public interface Goal {
    String id();
    default GoalCategory category() { return GoalCategory.MISC; } // survival, work, social…
    double utility(BotControl ctrl);
    void start(BotControl ctrl);  void tick(BotControl ctrl);  void stop(BotControl ctrl);
    boolean finished(BotControl ctrl);
    default boolean blocksRelocation() { return false; }
    default DimensionMask dimensions() { return DimensionMask.OVERWORLD_ONLY; } // gate jako vestavěné
}
```

`GoalRegistry.register` doplnit o přetížení s majitelem (`Plugin owner`) kvůli
úklidu. `Brain` bude cizí cíle prohánět **stejnými branami** (dimenze, rytmus)
jako vestavěné, takže se chovají konzistentně a nespadnou mimo overworld tam,
kde nemají.

**Cena/riziko.** Nízká–střední (staví na subsystému 0). Vyžaduje migraci
podpisu `Goal` – proto dělat spolu s 0.

---

## Subsystém 2 — Task SPI (taktická primitiva)

**Mezera.** `tasks/BotTask.java` je interní, běží nad `BotContext`, žádný
registr. Cizí plugin nemůže znovupoužít „vytěž blok / postav most / přejdi
vodu" – musel by je psát od nuly.

**Návrh.** Zveřejnit `BotTask` nad `BotControl` a přidat `TaskRegistry`, aby
plugin mohl a) skládat cíle z hotových vestavěných tasků, b) registrovat
vlastní znovupoužitelné tasky.

```java
public interface BotTask { boolean tick(BotControl ctrl); void cancel(BotControl ctrl); }

public interface TaskRegistry {
    void register(String taskId, Function<TaskParams, BotTask> factory, Plugin owner);
    BotTask create(String taskId, TaskParams params);   // pro cíle: vezmi hotové primitivum
    List<String> registeredIds();
}
```

Vestavěná primitiva (`MineBlockTask`, `PlaceBlockTask`, `BridgeTask`,
`WaterCrossTask`…) se do registru přihlásí jako built-in a stanou se stavebními
kameny pro cizí cíle.

**Cena/riziko.** Střední. Vyžaduje, aby vestavěné tasky přešly z `BotContext`
na `BotControl` (nebo aby `BotControl` interně poskytoval, co potřebují).

---

## Subsystém 3 — Role SPI (otevřené profese)

**Mezera.** `BotRole` je uzavřený enum a `RoleProfiles` (`role/RoleProfiles.java:19`)
je natvrdo psaná `Map` enum → (goalId → násobič). Cizí plugin nepřidá profesi
ani nenaváže svůj cíl na existující roli.

**Návrh.** `RoleRegistry` s řetězcovou identitou role; role = id + název +
váhový profil + funkce skóre z osobnosti (pro `RolePicker`).

```java
public interface RoleRegistry {
    void register(RoleDefinition role, Plugin owner);
    Optional<RoleDefinition> byId(String roleId);
    Collection<RoleDefinition> all();
}
public record RoleDefinition(
    String id, String displayName,
    Map<String,Double> goalWeights,          // násobiče utility (i pro vestavěné cíle)
    ToDoubleFunction<Personality> pickScore) {} // váha při automatickém výběru role
```

Vestavěné role se do registru zaregistrují ze současné tabulky. `Bot.role(...)`
a persistence přejdou z enum ordinálu na string id (viz subsystém 8). Plugin
navíc může **připsat váhu svému cíli i k vestavěné roli** (kovář = i „forge-hammer": 2.0).

**Cena/riziko.** Střední. Dotýká se persistence (uložení role jako text) a
`RolePicker`. Enum lze ponechat jako fasádu nad built-in registracemi kvůli
zpětné kompatibilitě API.

---

## Subsystém 4 — Memory SPI (otevřené kategorie paměti)

**Mezera.** `MemoryKind` je uzavřený enum; persistence i gossip klíčují druh
paměti podle něj. Plugin nemůže mít vlastní typ vzpomínky (např. „mé teleport
kotvy", „dungeon k vyčištění").

**Návrh.** `MemoryKindRegistry` s řetězcovou identitou + vlastnosti druhu
(rozpad, zda se šíří drbem, důležitostní podlaha). `BotMemory.remember(...)`
přijme `String kind` (vestavěné druhy zůstanou jako konstanty).

```java
public interface MemoryKindRegistry {
    void register(MemoryKindDefinition kind, Plugin owner);
}
public record MemoryKindDefinition(
    String id, boolean gossipable, double dailyDecay, double importanceFloor) {}
```

Persistence uloží `kind` jako TEXT (dnes nejspíš ordinál/enum name) – to je
dopředu kompatibilní. Rozpad a drby (`RelationDecay`, `SocialGraph.exchangeGossip`)
budou číst vlastnosti z definice místo `switch` nad enumem.

**Cena/riziko.** Střední. Migrace formátu `kind` ve sloupci paměti + revize
míst, která dnes větví podle konkrétních konstant enumu.

---

## Subsystém 5 — Event SPI (rozhodovací háky, ne jen pozorování)

**Mezera.** Z devíti eventů je mutovatelný jediný – `BotChatEvent`. Plugin
umí bota poslouchat, ale ne **usměrnit** jeho rozhodnutí, aniž by nahradil celý
subsystém.

**Návrh.** Přidat malou sadu *cancellable/mutable* rozhodovacích eventů, které
core vystřelí v klíčových bodech:

| Event | Kdy | Co umožní |
|---|---|---|
| `BotGoalSelectEvent` (cancellable) | v `Brain.decide` před přepnutím cíle | veto/boost cíle, vnutit vlastní |
| `BotTargetEvent` (cancellable) | při výběru bojového cíle v `CombatController` | chránit entity, přesměrovat agresi |
| `BotPreSpawnEvent` (mutable) | před spawnem v `BotManagerImpl.create` | doladit `BotSpawnSpec` (role, seed, pozice) |
| `BotMemoryWriteEvent` (cancellable) | v `BotMemoryImpl.remember` | filtrovat/obohatit vzpomínky |

Vzor je už zavedený (`BotChatEvent`), jen se aplikuje na víc bodů. Pozor na
vlákno: většina vzniká async z tick vlákna – dokumentovat, že handler nesmí
sahat na main-thread Bukkit API (stejná poznámka jako `BotEvent`).

**Cena/riziko.** Nízká–střední. Malé, přírůstkové, nízké riziko regrese
(defaultně se nikdo nezapojí = chování beze změny). Dobrý „rychlý zisk".

---

## Subsystém 6 — Persistence SPI (vlastní data pluginu)

**Mezera.** `SchemaMigrator` má natvrdo psaný seznam migrací a jednu globální
verzi (`ba_schema_version`). Plugin nemá kam uložit svá data vázaná na životní
cyklus bota.

**Návrh.** Dvě úrovně, od jednoduché:

1. **Namespaced key-value store per bot** (pokrývá 90 % potřeb):
   ```java
   public interface BotDataStore {
       CompletableFuture<Void> put(UUID botId, String namespace, String key, String value);
       CompletableFuture<Optional<String>> get(UUID botId, String namespace, String key);
   }
   ```
   Jedna tabulka `ba_ext_data(bot_id, namespace, key, value)`, write-behind jako
   zbytek paměti. Data se smažou s botem (`remove(..., purge=true)`).

2. **Migration SPI** pro pluginy, které chtějí vlastní tabulky: registr migrací
   s **vlastním jmenným prostorem verzí** (`ba_ext_schema_version(namespace, version)`),
   aby se verze pluginů nemíchaly s jádrem ani mezi sebou.

**Cena/riziko.** Store je nízké riziko (jedna tabulka, dialektově neutrální
SQL jako zbytek). Migration SPI střední – musí respektovat rozdíly SQLite/PostgreSQL
přes `SqlDialect`.

---

## Subsystém 7 — Command SPI (podpříkazy `/botalive`)

**Mezera.** `BotAliveCommand` dispatchuje `switch (sub)` nad natvrdo psaným
seznamem; tab-complete taky. Plugin nepřidá `/botalive <něco>`.

**Návrh.** `SubcommandRegistry`:

```java
public interface BotSubcommand {
    String name();  String permission();
    boolean execute(CommandSender sender, String[] args);
    List<String> tabComplete(CommandSender sender, String[] args);
}
public interface SubcommandRegistry { void register(BotSubcommand cmd, Plugin owner); }
```

`BotAliveCommand` nejdřív zkusí vestavěný `switch`, pak registr. Úklid při
`onDisable` majitele.

**Cena/riziko.** Nízká. Čistě mechanické, izolované od AI.

---

## Subsystém 8 — Config SPI (jmenný prostor pluginu)

**Mezera.** `BotAliveConfig` je jeden typovaný record; plugin nemá kam přidat
sekci a číst ji typovaně.

**Návrh.** Menší přínos než ostatní – plugin má vlastní `config.yml`. Užitečné
jen pro věci, které chtějí žít *v* konfiguraci BotAlive (např. per-role ladění).
Návrh: vystavit read-only `ConfigView namespace(String)` nad libovolnou sekcí
`config.yml`. Nízká priorita.

**Cena/riziko.** Nízká, ale i nízký přínos. Zvážit až bude poptávka.

---

## Subsystém 9 — Chat & Personality SPI (nižší priorita)

- **Chat responders** – hák, kde plugin přidá rozpoznání intentu a reakci
  (dnes je klasifikace + fráze zadrátovaná v `ChatEngine`/`PhraseCategory`).
  Kategorie frází otevřít podobně jako paměť (registr místo enumu). Jazyky už
  jdou souborem, tohle řeší *chování*, ne překlad.
- **Trait SPI** – otevřít `Trait` enum registrem rysů, které vstupují do
  utility. **Nejhlubší coupling z celého seznamu** (rysy prorůstají utility,
  chat, boj, humanizaci) → nejvyšší riziko, nejnižší priorita. Doporučuji
  neotvírat, dokud nebude konkrétní potřeba.

---

## Doporučené fáze implementace

| Fáze | Subsystémy | Proč tady |
|---|---|---|
| **A – Keystone** | 0 (`BotControl`) + 1 (Goal SPI) | Bez nich je `GoalRegistry` nepoužitelné; odemyká vše ostatní |
| **B – Rychlé zisky** | 5 (Event háky) + 7 (Command SPI) | Malé, izolované, nízké riziko, okamžitá hodnota pro integrace |
| **C – Data & chování** | 2 (Task SPI) + 3 (Role SPI) + 6 (Persistence store) | Plná tvorba chování a jeho persistence |
| **D – Podle poptávky** | 4 (Memory SPI), 8 (Config), 9 (Chat/Trait) | Hlubší coupling / menší přínos; až bude konkrétní use-case |

## Průřezová infrastruktura (udělat v fázi A, používá ji vše)

- **`PluginExtension` handle + auto-teardown.** Jeden registrační bod
  (`api.extensions().register(myPlugin)`), který drží seznam příspěvků a při
  `onDisable` je hromadně uklidí. Zabrání classloader leakům.
- **Sandbox tick.** `Brain`/tasková smyčka obalí cizí kód try/catch, spočítá
  chyby, opakovaného viníka odregistruje a zaloguje s jménem pluginu –
  jeden špatný plugin nesmí zmrazit bota.
- **Rozšíření `BotAliveApi`.** Přidat gettery: `taskRegistry()`, `roleRegistry()`,
  `memoryKinds()`, `subcommands()`, `dataStore()`, `extensions()`. Aditivní,
  zpětně kompatibilní.

---

## Shrnutí

Skutečná mezera není „málo subsystémů" – jádro jich má přes patnáct. Mezera je,
že **ven vede jediné poloviční dveře** (`GoalRegistry`). Nejcennější krok je
**subsystém 0**: dát cizím cílům bezpečné akční API (`BotControl`), aby
`GoalRegistry` konečně dávalo smysl. Kolem něj pak vyrůstá zbytek SPI –
tasky, role, eventy, příkazy, persistence – vždy stejným vzorem: *registr
místo enumu, majitel kvůli úklidu, izolace selhání, rozhraní v `botalive-api`*.
