<div align="center">

# BotAlive

**Fully autonomous AI players for Paper servers — real protocol-level clients, not NPCs.**

[![Build](https://github.com/t-vanek/BotALive/actions/workflows/build.yml/badge.svg)](https://github.com/t-vanek/BotALive/actions/workflows/build.yml)
![Paper](https://img.shields.io/badge/Paper-26.1-blue)
![Java](https://img.shields.io/badge/Java-25-orange)
![Folia](https://img.shields.io/badge/Folia-supported-brightgreen)

[Features](#features) · [Installation](#installation) · [Commands](#commands) · [Configuration](#configuration) · [Developer API](#developer-api) · [Building from Source](#building-from-source)

🇨🇿 [Česká verze](README.cs.md)

</div>

---

## Overview

BotAlive populates your server with bots that **join as real Minecraft clients** over the network protocol ([MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)). They pass through the full login and configuration phases, then move, dig, place blocks, eat and fight using the same packets a human player would — the server validates them like anyone else.

Every bot has its own identity, personality, memory, goals, inventory and history, all persisted across restarts. There are no scripts and no fixed behavior trees: bots decide what to do, remember what happened to them, and build a life on your server — houses, villages, trade, friendships and feuds.

|                   | Typical NPC plugins        | BotAlive                                              |
|-------------------|----------------------------|-------------------------------------------------------|
| Implementation    | Server-side entities       | Real network clients                                  |
| Behavior          | Scripted paths & dialogues | Utility-based AI, emergent behavior                   |
| Survival gameplay | Simulated at best          | Full vanilla progression up to netherite              |
| Persistence       | Position & skin            | Identity, memory, inventory, relationships, statistics |

## Features

### 🧠 Decision making & personality

- **Utility-based AI** — every decision tick the brain re-scores its goals (survive, eat, explore, mine, gather, fight, socialize, build shelter, go home…) and picks the best one with hysteresis. No scripts.
- **10 personality traits** (courage, caution, aggression, curiosity, sociability, laziness, intelligence, helpfulness, greed, patience), generated from a seed. Traits weight goals, combat, chat and small habits — no two bots are alike.
- **Personality evolves** — experience shifts traits within bounds of the seeded core: a thief who keeps getting away grows greedier, deaths teach caution, victories build courage. Drift is persistent and visible in `/botalive personality`.
- **Professions** — builder, miner, lumberjack, hunter, blacksmith, enchanter, trader, fisherman, farmer, all on vanilla mechanics. A role is a focus, not a cage: it multiplies the priority of related activities and is assigned to match personality.
- **Life ambitions & daily rhythm** — each bot pursues a long-term project (full iron gear, a cozy home, getting rich) and structures its day: fields in the morning, mining and building at noon, socializing at dusk, bed at night — with a personal lark/owl offset.
- **Intent layer** — bots can tell you what they are doing and why: `/botalive goal` shows the reasoning ("mining iron_ore — I want an iron pickaxe"), and asking "what are you doing?" in chat gets an answer based on the actual activity.

### 🗺️ Navigation

- **Custom asynchronous A\*** with node and time budgets, cooperative cancellation, memoized world queries and continuous path validation against a changing world — game threads are never blocked.
- **Player-grade escalation** — when no path exists, the bot doesn't give up: replanning → a **dig plan** (1×2 tunnels and carved staircases as graph edges, with liquid safeguards and a property deny-list — never through chests, furnaces or beds) → reactive assists (bridges over lava, pillars, ladders up walls). Everything is budgeted from the bot's inventory and gated by `ai.terraforming`.
- **Long-distance corridors** — a coarse A\* over surface probes routes around lakes, lava fields and mountain massifs before fine planning starts (`pathfinding.far-corridor`).
- **Goal predicates** — "within reach of the furnace", "away from this threat", "nearest reachable ore" are planned natively, instead of burning the node budget pathing to an unwalkable block.
- **Personality-flavored movement** — brave bots sprint-jump ravines, careful ones walk around and keep distance from lava, lazy ones avoid climbing — and everyone prefers the trodden paths of their own village.
- **Water, lava & vehicles** — swimming with real current simulation, bridges built block-by-block over lava lakes, boats piloted along water corridors and minecarts ridden along rails with client-side vanilla kinematics.
- **Diagnostics** — `/botalive path <bot>` shows the current target, waypoints, computation state and A\* metrics.

### ⚔️ Combat & PvP

- **Difficulty profiles** (easy / normal / hard / nightmare) — strafing, sprint resets, attack jitter, shield blocking, retreat driven by courage.
- **Ranged combat** — bow and crossbow with target-motion prediction and ballistic compensation.
- **Combat cooperates with navigation** — a target behind a fence, corner or ditch gets outflanked (kiting doesn't work), and low-health retreats are planned across walkable terrain — never backwards into lava.
- **PvP & alliances** (optional, off by default) — an attacked bot defends itself or flees by courage, calls for help in chat, and friends from memory answer; grudges live in persistent enemy memory. Fairness caps limit how many bots pile onto one target, and attacking human players has its own safety switch (`pvp.attack-players`).
- **Pets** — bots tame wolves, cats, parrots and horses through vanilla mechanics; tamed wolves fight at their side.

### 🌾 Survival progression

- **The complete vanilla chain** — planks → sticks → crafting table → wooden and stone tools → furnace → torches → iron tools → shield → iron armor → diamond gear → bow and arrows → chest, boat, door and bed — capped by **netherite**.
- **Purposeful mining** — tier-gated targets (never mining ore with a tool that drops nothing), access tunnels to buried ore, staircases into the deep (never straight down), vein following, torch placement, and lava/water checks before every block.
- **Stations & smelting chains** — smoker and blast furnace including prerequisites, a self-sufficient charcoal fuel loop, priority-based fuel selection; worn tools are replaced before they break and repaired at an anvil (crafted and placed if none exists).
- **Farming & food** — harvest and replanting, a composter loop for bone meal, villager trading with real trade recipes and stock limits, and food sharing with people around.
- **Homes & upkeep** — bots build real houses (walls, door opening, roof, chosen orientation), sleep in beds, store surplus in chests, repair creeper damage against the house plan, and after dying run back for their gear before the drops despawn.

### 🔥 Nether & 🌌 End

- **Nether expeditions** — a properly equipped bot finds a portal from memory, discovers one, or **builds its own** (mines 14 obsidian, builds a 4×5 frame, lights it with flint and steel). In the Nether it mines quartz, gold and glowstone, digs for **ancient debris**, loots fortresses and bastions (smithing templates!), barters with piglins in gold boots — then comes home to forge **netherite upgrades** with enchantments and durability preserved.
- **Colonization** — outposts with a chest on the Nether side of the portal, fire-resistance potions before deep dives, and return safeguards on health, hunger and a full inventory. Loot flows back into the community: markets, wallets, gossip about portals, template duplication.
- **End expeditions** — a bot that learns of a portal (by walking through, by exploring, from a friend's gossip, or via `/botalive end portal`) gears up and fights like an experienced player: shoot the crystals first, never look endermen in the eye, edge-guard against void falls, melee the perched dragon and shoot the flying one. Victory is celebrated in chat, saved as a trophy and raises courage.
- **Outer islands & elytra** — once the dragon falls, a bot with enough pearls throws one through a gateway, memorises the return gateway, finds an end city (server-side structure locate; purpur scanning on foreign servers), loots the towers, knocks the **elytra** off the end ship's item frame and equips them on the spot. Shulker levitation is simulated client-side so hits don't desync the bot; with elytra equipped, bots glide down from heights under a conservative flight controller (no dive-bombing, flare before touchdown).
- **Dimension discipline** — beds are never used in either dimension (they explode), and paths route around portals so a bot never changes dimension by accident.

### 🏘️ Society & economy

- **Settlements** — sociable bots found and join villages (with generated Czech names like "Pepov"), plots are allocated in rings around the green with doors facing the center, and finished houses get trodden paths and torch lighting. A fresh grudge can drive a bot out to found a rival village — best friends migrate along.
- **Bot-to-bot market** — surplus goods are offered in chat ("selling 5x bread for 12, anyone?"), reserved, delivered in person and paid for. Friends get discounts, the greedy add margins, and a hungry bot with money buys instead of stealing.
- **Crime & consequence** — a starving bot may "borrow" from a stranger's chest or, in true desperation, rob someone (always respecting the `pvp` section; friends are off-limits). Thefts land in a shared crime book — the owner finds out, gets angry and remembers.
- **Economy** — internal persistent wallets by default; with Vault installed, bots join the server economy (`/pay`, `/baltop`) and earnings from mining and trade are visible to everyone.
- **Wars & diplomacy** (optional, off by default) — grudges between members of different villages (discovered thefts, assaults) raise tension between the settlements. Past a threshold a militant mayor declares war: raid parties march on the enemy green, defence is summoned by the same machinery as regular PvP, casualties build war-weariness, and tired mayors negotiate a truce — the losing side pays reparations from its mayor's wallet. Strictly bot-vs-bot, players are never targets; raids respect the `pvp` switches (without them wars stay "cold"). `/botalive diplomacy` shows tensions, wars and truces.
- **Bots for hire** — players hire bots in person (`/botalive hire <bot> <worker|guard> [days]`, within 16 blocks): a **worker** focuses on productive goals and periodically walks his yield over to the employer; a **bodyguard** sticks with you and fights off attackers — mobs always, players and bots only within the `pvp` rules. Wages follow personality (greedy and lazy bots charge more, friends get the market discount) and are paid upfront via Vault `/pay`. Attack your own bot and he quits on the spot, keeping the money.

### 💬 Chat & human-like presence

- **Human motion envelope** — limited head-turn speed with easing and noise, permanent aim error, log-normal reaction latency, micro-glances, pauses, staggered ticks.
- **Human typing** — thinking time, typing speed, QWERTZ-neighbor typos, letter swaps and "*word" follow-up corrections.
- **Conversations** — greetings, factual questions ("where are you?", "what do you have?", "where is the village?") and requests ("follow me", "give me food") honored according to personality and friendship. Bots also comment on their own intentions from time to time.
- **Localization** — all phrases live in `lang/<code>.yml` (Czech and English built in, `chat.language`). A new language is a single new file; recognition patterns are localized too, and any missing category falls back to the built-in layer — bots never run out of words.

### ⚙️ Performance & compatibility

- **Multithreaded tick engine** — 20 Hz with phase staggering, a shared Caffeine chunk-snapshot cache, an async pathfinding pool and single-threaded virtual executors for packets. Game threads are never blocked.
- **Folia support** — built exclusively on the region-aware scheduler API.
- **Plays on servers you don't control** — the optional packet-based world model (`network.world-model: packet`) parses geometry straight from chunk packets and drives crafting, chests, furnaces, villager trading and enchanting through protocol container clicks — full survival on any offline-mode server.
- **ViaVersion aware** — a protocol mismatch is detected at startup and bot creation is refused with clear instructions (install ViaVersion / ViaBackwards) instead of failing silently (`network.version-check`).
- **Persistence** — embedded SQLite out of the box or PostgreSQL, write-behind saves and merging of nearby memories.

## Requirements

|          |                                                                                   |
|----------|-----------------------------------------------------------------------------------|
| Server   | Paper 26.1.x or Folia (other versions via ViaVersion / ViaBackwards)              |
| Java     | 25+                                                                               |
| Mode     | `online-mode=false` (bots are offline clients), or Velocity with an offline backend |
| Database | none required (embedded SQLite); PostgreSQL optional                              |
| Economy  | internal by default; Vault + any economy plugin optional                          |

## Installation

1. Drop `BotAlive-<version>.jar` into `plugins/`.
2. Restart the server — `plugins/BotAlive/config.yml` is generated.
3. Run `/botalive create` — your first bot connects.

> [!IMPORTANT]
> Bots are unsigned offline clients, so the server (or the backend behind your proxy) must run `online-mode=false`. On an online-mode server the plugin refuses to connect bots and explains why.

## Commands

Base command `/botalive`, aliases `/ba` and `/bots`.

| Command | Description |
|---|---|
| `create [name] [count]` | Spawn bots (picks a human-looking name from the pool if omitted) |
| `remove <name\|all> [purge]` | Disconnect and remove a bot; `purge` also wipes its database records |
| `tp <name>` | Teleport yourself to a bot |
| `tp <name> here` | Summon a bot to you |
| `tp <name> <x> <y> <z> [world]` | Teleport a bot to coordinates (admin only, console-capable) |
| `list` | Overview of bots, states, health and active goals |
| `pause` / `resume <name\|all>` | Suspend/resume the AI (the bot stays connected) |
| `personality <name>` | Archetype, seed and trait chart including drift |
| `memory <name> [category]` | Long-term memory contents |
| `goal <name> [set <goal>\|clear]` | Utility overview / force a goal |
| `stats <name>` | Blocks mined and placed, deaths, kills, distance walked, money… |
| `role <name> [role\|random]` | Show or assign a profession |
| `settlements` | Overview of bot villages (name, green, founder, members) |
| `diplomacy` | Tension, wars and truces between bot villages |
| `hire <name> <worker\|guard> [days]` | Ask a bot for a wage quote (in person, within 16 blocks) |
| `hire <name> confirm` | Confirm the hire — the bot then waits for `/pay` |
| `dismiss <name>` | End your bot's contract early (no refund) |
| `end portal <x> <y> <z> [world]` | Reveal an End portal location to all bots (gossip spreads it further) |
| `path <name>` | Navigation diagnostics (target, waypoints, computation state) + A\* metrics |

### Permissions

| Permission | Purpose | Default |
|---|---|---|
| `botalive.admin` | Full bot management, teleports without cooldown | op |
| `botalive.teleport` | `/botalive tp <bot>` + reduced `list` (names only) | op |
| `botalive.teleport.summon` | `/botalive tp <bot> here` | op |
| `botalive.use` | Base access to the command | everyone |

Player teleports have a configurable cooldown (`teleport.player-cooldown-seconds`, default 30 s) and can be disabled entirely (`teleport.enabled: false`); admins always bypass.

## Configuration

`plugins/BotAlive/config.yml` is generated on first start and documents every option inline. The sections you will touch most often:

| Section | Controls |
|---|---|
| `bots`, `spawn`, `worlds` | Bot count, naming, spawn rules, allowed worlds |
| `ai.*` | Goal weights, terraforming, desperation behavior, daily rhythm |
| `pathfinding.*` | Node/time budgets, far corridor, path diagnostics |
| `combat.*`, `pvp.*` | Difficulty profile, PvP toggles, player-attack safety switch |
| `nether.*`, `end.*` | Expedition gear thresholds, budgets, safeguards |
| `end.outer.*` | Outer-island expeditions: trip budget, pearl reserve, structure-locate assist, elytra flight |
| `settlement.*` | Villages, plots, lighting, paths |
| `settlement.war.*` | Wars & diplomacy: tension weights, raid size and cadence, truce terms, reparations (off by default) |
| `economy.*` | Vault integration, bot-to-bot market |
| `economy.employment.*` | Bots for hire: wages, contract caps, upfront-payment requirement |
| `chat.*` | Language, typing and conversation behavior |
| `memory.*`, `persistence.*` | Memory limits, SQLite/PostgreSQL |
| `network.*` | World model (`packet`), protocol version check |
| `teleport.*` | Player teleport cooldowns |

## Localization

Bots speak the language set in `chat.language` (default `cs`). On startup the plugin exports templates to `plugins/BotAlive/lang/cs.yml` and `en.yml`:

1. **New language** — copy a template to `lang/<code>.yml` (e.g. `de.yml`), translate the phrases and the `patterns` (regular expressions that recognize greetings and thanks), then set `chat.language: <code>`.
2. **Editing an existing language** — edit `lang/cs.yml` directly; the file is never overwritten on plugin upgrade.
3. **Partial translations are fine** — any category the file doesn't define falls back to the built-in layer, and a broken regex is reported and replaced by the previous pattern.

The `{name}` placeholder is replaced with the other party's name.

<details>
<summary>Phrase categories</summary>

`greetings`, `confused`, `agreement`, `disagreement`, `youre-welcome`, `idle-chatter`, `death-reactions`, `combat-taunts`, `meet-player`, `pvp-help-calls`, `pvp-assist`, `pvp-taunts`, `nether-depart`, `nether-arrive`, `nether-return`, `nether-loot`, `end-depart`, `end-arrive`, `dragon-slain`, `end-return`, `end-outer-depart`, `end-city-found`, `elytra-found`, `end-outer-return`, `war-declared`, `war-raid-depart`, `war-raid-taunts`, `war-truce-offer`, `war-truce-agreed`, `hire-pay-request`, `hire-accept`, `hire-decline`, `hire-expired`, `hire-quit`, `hire-deliver`, `guard-defend`, `emojis`

</details>

## Developer API

The public API lives in the dependency-free **`botalive-api`** module. Until artifacts are published to a public Maven repository, build it from source (`./gradlew :botalive-api:build`) and depend on the resulting jar.

```java
BotAliveApi api = BotAliveProvider.get();

// Register a custom AI goal for all bots
api.goalRegistry().register("greet-admins", bot -> new MyGreetAdminsGoal());

// Spawn a bot with a fixed personality seed
api.botManager()
   .create(new BotSpawnSpec("Pepa", null, 42L))
   .thenAccept(bot -> bot.say("hello world"));

// Teleportation (thread-safe, with a full client resync)
bot.teleport(location);                        // bot to a location
bot.teleportToPlayer(player.getUniqueId());    // bot to a player
bot.teleportPlayerToBot(player.getUniqueId()); // player to a bot
```

Bukkit events (all fired asynchronously): `BotSpawnedEvent`, `BotRemovedEvent`, `BotChatEvent` (cancellable), `BotDiedEvent`, `BotGoalChangedEvent`, `SettlementWarDeclaredEvent`, `SettlementTruceEvent`, `BotHiredEvent`, `BotDismissedEvent`.

## Building from Source

```bash
git clone https://github.com/t-vanek/BotALive.git
cd BotALive
./gradlew build
# output: botalive-core/build/libs/BotAlive-<version>.jar
```

Requires JDK 25 (the Gradle toolchain targets Java 25, as mandated by Paper API 26.1). The resulting jar is self-contained — MCProtocolLib, Netty, HikariCP, Caffeine and the JDBC drivers are bundled, with conflict-prone libraries relocated to `dev.botalive.libs`. CI builds and tests every push and pull request; the plugin jar is attached to each run as a workflow artifact.

## Architecture

Two Gradle modules: **`botalive-api`** (public interfaces, events and data types with no implementation dependencies) and **`botalive-core`** (the implementation — ~15 subsystems in isolated packages: network, ai, pathfinding, physics, combat, chat, memory, persistence, economy, tasks, commands, config, scheduler, world, human).

Design decisions and trade-offs are documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); deep dives are available for [pathfinding](docs/PATHFINDING_V4.md) and [settlement growth](docs/SETTLEMENTS_GROWTH.md).

## Known Limitations & Roadmap

- **Offline mode is required** — bots are unsigned clients. On an online-mode server the plugin refuses to connect them and explains why.
- **Nether** — bots don't ride striders and won't bridge large lava oceans (bridges cap at 12 blocks; routes are found elsewhere). Brewing, respawn anchors and the wither fight are out of scope; nether wart is collected only as chest loot. Offensive splash-potion throwing is missing (bots only splash themselves as an emergency save).
- **End outer islands** — elytra flight is gliding only (no firework rockets yet), shulker shell state isn't read (entity metadata stays unparsed — bots sometimes swing at a closed shell like a rookie), and cities beyond `end.outer.max-city-distance` are given up rather than bridged across the void. Shulker boxes aren't crafted or used.
- **Strongholds** — bots don't triangulate with eyes of ender; they learn portal locations by walking through, random discovery, gossip, or `/botalive end portal`. They do craft eyes of ender (pearl + blaze powder from Nether loot) and will fill an incomplete portal frame themselves.
- **Wars** — sieges don't loot or burn (raids are combat-only), wars never involve players, and cross-world villages don't fight (raiders can't walk between worlds).

## Contributing

Issues and pull requests are welcome. Before opening a PR, please run `./gradlew build` locally — CI runs the same build and test suite on every pull request. Keep changes focused and describe the motivation in the PR description; for larger behavioral changes, an issue first is appreciated.
