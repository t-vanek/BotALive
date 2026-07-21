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

### 2. `Vitals` – fyziologie (✅ implementováno)

Tělesný stav – zatím **energie/únava**. Energie 0–1 klesá bděním a námahou
(pohyb, boj) a obnovuje se spánkem; respawn tělo osvěží. `modulate(goalId)` je
jemný násobič (á la nálada): unavený bot zvedá utilitu spánku/domova/úkrytu
a tlumí dlouhé výpravy (Nether, End, průzkum, těžba) – svěží bot dostane přesně
1.0. Dřív byla „únava" jen implicitní v čase (`DayRhythm`, `SleepGoal`); teď má
spánek vnitřní příčinu i mimo noc. **Propojení na náladu:** vyčerpaný bot je
náladovější (`updateInnerState` mu přisype trochu podráždění). Vypínatelné
(`ai.vitals`). Čistá, jednotkově testovaná třída (`VitalsTest`).

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
| 2 | `Vitals` (energie/únava) | ✅ hotovo |
| 3 | `Drives` (sjednocené pudy) | ⬜ návrh |

Mood i Vitals sdílejí modulační vzor (v klidu neutrální, vypínatelné, jemné
násobiče v `Brain.decide`) a jsou provázané: prožitky a tělo sytí náladu,
únava náladu přiostřuje. Obojí se aktualizuje jedním řídkým krokem
(`BotImpl.updateInnerState`, ~1 s). Zbývá `Drives` – sjednocení pudů, které dnes
každý cíl počítá zvlášť; naváže stejným vzorem a role by nakláněla jejich baseline.
