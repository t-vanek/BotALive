# BotAlive – vnitřní život bota (Bot Life)

Tento dokument navrhuje **subsystémy vnitřního stavu bota** – „podporu života",
která dává rozhodování bota vnitřní příčinu a propojuje jeho **paměť, chování,
roli a rozhodování** do jednoho živého celku. Není to o rozšiřitelnosti pro
cizí pluginy (to řeší [PLUGIN_SPI.md](PLUGIN_SPI.md)); je to o tom, aby bot
působil jako živý tvor, ne jako kalkulačka utility.

## Tři časové vrstvy psychiky

Klíčové pozorování: bot už má vrstvy psychiky na různých časových škálách,
jen mezi nimi chybí prostřední.

| Vrstva | Škála | Kdo to je / jak se mění | Existuje? |
|---|---|---|---|
| **Osobnost** (`Personality`) | celoživotní | seed + pomalý drift z prožitků (`PersonalityEvolution`) | ✅ |
| **Nálada a pudy** (`BotMood`, `Vitals`, `Drives`) | minuty–hodiny | rychlá reakce na prožitky a tělo, odeznívá | 🟡 **tady** |
| **Utility cílů** | tik | mozek vybere nejlepší cíl teď | ✅ |

Osobnost říká, **kdo bot je**; utility říká, **co dělá teď**; mezi nimi chybělo
**jak se právě cítí a co potřebuje**. To je „podpora života".

## Architektura: modulační vrstva mozku

Mozek (`Brain.decide`) skládá užitečnost jako součin vrstev:

```
utility = base(bot)              // utility funkce cíle (osobnost)
        × dimension              // Nether/End gaty
        × role                   // profese (RoleRegistry)
        × rhythm                 // denní doba (DayRhythm)
        × ambition × employment  // dlouhodobý tah
        × MOOD                   // ← nová vrstva: aktuální emoce
        × hysteresis × šum
```

Vnitřní stav je **další modulační vrstva** – stejný vzor jako role a rytmus,
takže zapadá bez přepisu mozku. V klidu vrací 1.0 (chování beze změny); pod
emocí jemně vychyluje priority.

## Subsystémy

### 1. `BotMood` – emoce (✅ implementováno jako keystone)

Krátkodobý emoční stav: **strach, vztek, spokojenost, samota**, každý 0–1.

- **Paměť → nálada.** Reaguje na tytéž prožitky (`BotExperience`), z nichž se
  učí osobnost – ale rychle a odeznívavě, ne trvalým driftem. Smrt vyvolá
  strach, přepadení vztek, dostavěný dům spokojenost, sdílení zahání samotu.
  Jeden hák: `BotImpl.gainExperience` (funnel všech prožitků).
- **Tělo → nálada.** Průběžně sleduje vitály a okolí (`observe`): nízké zdraví
  a hrozby v okolí zvyšují strach, sytost a klid spokojenost, čas o samotě
  zvyšuje samotu.
- **Osobnost škáluje reaktivitu.** Opatrný bot se bojí snáz, agresivní se
  vzteká snáz – tytéž události, jiná intenzita.
- **Nálada → chování → rozhodování.** `modulate(goalId)` je jemný násobič
  (á la `DayRhythm`, 0.6–1.6): strach táhne k útěku/úkrytu/domů a tlumí
  průzkum/těžbu/boj; vztek k boji a tlumí družení; spokojenost k družení,
  stavbě, průzkumu; samota k družení a následování. Intenzita škáluje vliv,
  takže klid = beze změny.
- Odeznívá (`decay`) k baseline klidu za desítky sekund. Vypínatelné
  (`ai.mood`). Čistá, jednotkově testovaná třída (`BotMoodTest`).

### 2. `Vitals` – fyziologie (návrh)

Sjednocený tělesný stav: zdraví a sytost (existují v `BotClientState`)
**+ energie/únava** (nová). Únava roste činností a bděním, klesá spánkem
a odpočinkem; nízká energie zvedá utilitu spánku/domova a tlumí dlouhé
výpravy. Dnes je „únava" jen implicitní v čase (`DayRhythm`, `SleepGoal`);
explicitní vitál by dal spánku a odpočinku vnitřní příčinu a napojil se na
náladu (vyčerpaný bot je náladovější).

### 3. `Drives` – pudy (návrh)

Sjednocené potřeby (á la Maslow) jako sdílená reprezentace, kterou dnes cíle
počítají každý zvlášť ve své utility: **obživa, bezpečí, odpočinek, sounáležitost,
seberealizace**. `BotNeeds` pokrývá materiální progresi (co těžit); `Drives`
by pokrývaly motivační stránku a mozek by jimi moduloval kategorie cílů. Role
by nakláněla baseline pudů (dobrodruh má vyšší seberealizaci, strážce bezpečí).

## Propojení s ostatními subsystémy

- **Paměť** – prožitky (a čerstvé ENEMY/DANGER vzpomínky) sytí náladu a pudy.
- **Chování** – nálada/pudy modulují utilitu cílů (co bot dělá).
- **Role** – profese naklání baseline pudů a mírně i emoční reaktivitu.
- **Rozhodování** – vše ústí do jedné modulační vrstvy v `Brain.decide`.
- **Osobnost** – dává baseline a reaktivitu; prožitky mění obojí (rychle náladu,
  pomalu osobnost).

## Fáze

| Fáze | Subsystém | Stav |
|---|---|---|
| 1 | `BotMood` (emoce) | ✅ hotovo |
| 2 | `Vitals` (energie/únava) | ⬜ návrh |
| 3 | `Drives` (sjednocené pudy) | ⬜ návrh |

Mood je keystone: přidává zcela novou dimenzi (krátkodobou emoci), je bezpečný
(v klidu neutrální, vypínatelný) a demonstruje celou smyčku
paměť → cit → rozhodnutí → chování, škálovanou osobností. Vitals a Drives na něj
navazují stejným modulačním vzorem.
