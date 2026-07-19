# Pathfinding v4 – analýza spolupráce subsystémů

Analýzy v2 a v3 jsou beze zbytku implementované: jádro A* je levné a
měřené, plán i exekuce (včetně reaktivních tasků) jsou pod fyzickým
simulačním kontraktem, akční hrany pokrývají kopání, mosty, pilíře
i žebříky, voda má proudy. Uvnitř pathfindingu už systémová mezera
nezbývá – tahle runda proto hledala díry ve **spolupráci subsystémů**:
boj × navigace, dav × přesné tasky, živé hrozby × plánování, odolnost
exekuce vůči rušení. Nálezy jsou ověřené čtením kódu.

## 1. Slabá místa (s důkazy)

### P1: Boj se pohybuje úplně bez navigace

`CombatController.tick` vrací čistě přímočaré vektory: přiblížení je
`toTarget.normalized()`, ústup při nízkém zdraví `toTarget.mul(-1)`
(sprint pozpátku). Bojové goaly (`HuntGoal`, `PvpGoal`, `RobGoal`,
`SurviveGoal` v obranné větvi) jen předávají
`ctx.requestMove(ctx.combat().tick(...))`. Důsledky:

- **Kiting zdarma**: cíl za rohem, za příkopem, za plotem nebo o patro
  výš je nedosažitelný – bot běží do zdi a mele se o ni, dokud souboj
  nevyprší. Hráč bota porazí obíháním libovolné překážky.
- **Ústup naslepo**: couvání s pohledem na protivníka nevidí, kam
  couvá – `EdgeGuard` ho jistí jen v Endu (hrany, void); v overworldu
  bot klidně vycouvá do lávy, ohně nebo z útesu.
- Přitom všechna potřebná infrastruktura existuje: `near(target, r)`
  s drift throttlem umí pohyblivé cíle, `awayFrom` umí plánovaný ústup
  (SurviveGoal ho už používá), navigátor za běhu zvládá i skoky
  a žebříky.

Náprava (hybrid, boj si nechá mikropohyb): přímé řízení jen při
volné spojnici a klesající vzdálenosti (strafing, rozestup, timing
úderů zůstávají combatu); když spojnice chybí nebo se vzdálenost
nezmenšuje, přiblížení převezme `navigateTo(near(target, 2))`
– throttle pohyblivých cílů už existuje. Ústup při nízkém zdraví
přejde na dvoustupňový vzor SurviveGoalu: okamžité přímé couvání
drží bota v pohybu, a jakmile se dopočítá `awayFrom`, převezme
plánovaná trasa po pochozím terénu. Simulační kontrakt: kiting scénář
(cíl za L-zdí, bot ho musí oběhnout na dosah) a ústup podél lávového
pole bez vkročení do hazardu.

### P2: Dav šoupe botem uprostřed přesných tasků

`CrowdAvoidance.steer` se v `BotImpl.tick` aplikuje na výsledný vstup
bezpodmínečně (jedinou výjimkou je cíl útoku v boji). Jenže vstup může
zrovna řídit `LadderTask` (drží STŘED žebříkového sloupce – tlak do zdi
by server odmítl), `PillarUpTask` (centrování na sloupci pilíře;
vychýlení = pád z rozestavěného pilíře) nebo mířená pokládka/kopání
(bot stojí IDLE u waypointu akce). Kolemjdoucí bot v poloměru davu
vychýlí směr a přesný task selže nebo shodí bota dolů.

Náprava: dav ustoupí přesným taskům – při `obstacleTask != null`
(a `actionTaskRunning`) se steering vypne nebo silně utlumí, stejně
jako se dnes vynechává cíl útoku. Kolemjdoucí se vyhne sám (jeho
vlastní steering vidí stojícího bota). Simulace: jeden bot pilíří,
druhý prochází těsně kolem – pilíř musí dorůst a nikdo nespadne.

### P3: Živé hrozby do plánování nemluví

`Navigator.dangerSupplier` dodává jen vzpomínky (`DEATH`, `DANGER`
paměti, strop 24). Aktuálně viditelný creeper, skeleton nebo zombie
do cen tras nevstupuje – bot si klidně naplánuje trasu metr od
creepera a spoléhá, že to Survive/Combat vyřeší reaktivně (zpanikaří
uprostřed cesty). Přitom mechanismus je hotový: danger body nesou
`COST_DANGER_NEAR/COST_DANGER` s bbox early-outem; stačí v BotImplu
přidat do dodavatele pozice blízkých hostilů z `EntityTracker`
(žádná změna plánovače). Nové plány pak hrozby obcházejí obloukem;
posun moba mezi replány řeší stávající drift/validace. Vedlejší
sjednocení: dav dnes uhýbá jen hráčům (`e.isPlayer()`) – mobové ani
zvířata se v chůzi neobcházejí.

### P4: Odolnost exekuce vůči rušení není pod kontraktem

Všechny simulace běží v čistých světech: nikdo do bota nestrká.
V produkci bota průběžně perturbuje knockback (mobové, šípy), dav
a proudy. Zda se exekuce vzpamatuje – zvlášť uprostřed sprint-skoku
přes mezeru, na úzkém mostě nebo na žebříku – kontrakt negarantuje.
Návrh: perturbační kategorie simulací s deterministickým seedem
(náhodné impulzy během chůze/skoku/mostu/šplhu); kontrakt zní „bot se
vzpamatuje a dorazí, případný pád zachytí reflexy a replán". Zkušenost
z BotTask simulace (3 nálezy při prvním spuštění) říká, že tady nálezy
budou.

### P5: Čluny jsou mimo kontrakt (odloženo za gate)

`WaterCrossTask`/`VehicleTask` (přejezd širé vody člunem) nemají
fyzickou simulaci – `BotPhysics` modeluje jen chodce/plavce, člun je
serverem řízená entita s vlastní fyzikou. Poctivá simulace chce
BoatPhysics model (tření na vodě/ledu, zatáčení) – větší investice
s nízkou frekvencí užití. Odložit, dokud čluny nezpůsobí měřitelné
problémy v provozu.

### P6: Drobnosti (příležitostně)

- FarPlanner ignoruje danger paměti a proudy – koridor vedený zónou
  smrti nutí low-level plán bojovat se segmentovými mezicíli; vzácné.
- `FarmGoal`/`EndHarvestGoal` vybírají jediný cíl skenem – kandidáti
  přes `anyNear` by ušetřili marné pokusy (vzor z MineGoal/SleepGoal).
- Krajní případ žebříkových hran: trigger je těsný (pata stěny), ale
  smoothing s ním zatím nemá scénář v davu.

> **Stav: v4.0 implementováno.** `CombatController` je hybrid: mikropohyb
> (strafing, rozestupy, timing úderů, štít) zůstává přímému řízení, ale
> bez volné spojnice (voxelový raycast po půl bloku) nebo bez postupu
> (nejlepší dosažená vzdálenost se ≥ 30 ticků nezlepšuje mimo strafovací
> pásmo) převezme přiblížení `navigateTo(near(cíl, 2))` s drift throttlem;
> `tick` pak vrací `null` a všech šest bojových volajících nechá pohyb
> navigátoru. Hystereze: jednou zahájené obcházení se drží až na dosah
> úderu. Ústup při nízkém zdraví je dvoustupňový (okamžitá panika →
> plánovaný `awayFrom` 12) a panika couvá přes `EdgeGuard` – i pár
> slepých ticků s rozběhem umělo skončit v lávě za zády. **Bonusový
> nález simulace**: dvoustupňový vzor měl latentní deadlock i v
> SurviveGoalu – `hasPath()` se překlápí až v `navigator.tick()`, který
> při `requestMove(panika)` vůbec neběží, takže plánovaný útěk se v
> produkci nikdy neujal řízení a vzor tiše degradoval na čistou paniku.
> Nový `Navigator.pathReady()` vidí i dopočítanou, ještě nepřevzatou
> cestu; SurviveGoal i bojový ústup jedou přes něj. Simulace:
> `obejdeZedKeKitujicimuCili` (L-zeď, bot dojde na dosah jen obchůzkou)
> a `ustoupiKolemLavyPoPochozimTerenu` (lávové pole za zády, útěk na
> 11+ bloků bez vkročení do hazardu). Vědomé meze: navigované přiblížení
> končí až na dosah melee (lučištník bez spojnice dojde k cíli pěšky)
> a s běžícím bojem se neaktivují akční hrany (bot se v souboji
> neprokopává – gate `!combat.engaged()` v BotImplu trvá).

> **Stav: v4.1 implementováno.** (P2) Dav ustupuje přesným taskům:
> steering `CrowdAvoidance` se v BotImplu neaplikuje, dokud běží
> `obstacleTask` nebo čeká zásah z plánu – simulace
> `davNestrkaDoPilirujicihoBota` měří, že strkání souseda dotlačí
> stavitele až na hranu vlastního pilíře (odstup od středu > 0,4 bloku;
> pád byl o centimetry), zatímco bez strkání drží střed. Kolemjdoucí se
> vyhne sám – jeho steering stojícího vidí. (P3) Živé hrozby vstupují do
> plánování: `dangerSupplier` vedle vzpomínek přidává pozice viditelných
> hostilů (`isHostile`, okruh 24, strop 8, celkem ≤ 32 bodů) – nové
> trasy obcházejí creepera obloukem přes stávající `COST_DANGER`
> mechanismus, aktuální cíl boje se vynechává (k němu se přibližovat
> MÁ). Dav dál záměrně uhýbá jen hráčům/botům – vyhýbání zvířatům
> a mobům v chůzi by změnilo vyladěné chování stád a není součástí
> tohoto nálezu.

## 2. Doporučené fázování

| Fáze | Obsah | Náročnost | Riziko | Přínos |
|---|---|---|---|---|
| **v4.0 boj × navigace** ✅ | P1: hybridní přiblížení (LOS gate → `near`), plánovaný ústup `awayFrom`, kiting + ústupový sim scénář (+ oprava latentního deadlocku dvoustupňového útěku) | M | střední | konec kitingu, ústup přestane být sebevražedný – největší viditelná díra spolupráce |
| **v4.1 ohleduplnost** ✅ | P2 dav ustoupí přesným taskům + P3 živé hrozby do dangerSupplier | S | nízké | pilíře/žebříky v davu, trasy obcházejí moby |
| **v4.2 perturbační kontrakt** | P4: kategorie simulací s rušením | M | nízké | očekávané nálezy v exekuci pod tlakem |
| odloženo | P5 čluny (chce BoatPhysics), P6 drobnosti | M–L | – | nízká frekvence užití |

## 3. Shrnutí

Pathfinding sám je hotový a měřený; zbývající slabiny jsou v tom, kdo
ho (ne)používá a kdo mu (ne)překáží. Největší je boj: jediný subsystém
s vlastním pohybem, který navigaci ignoruje úplně – kiting a slepý
ústup jsou přímé důsledky. Druhá vlna jsou ohleduplnosti (dav vs.
přesné tasky, živé hrozby v cenách) a třetí je rozšíření kontraktu
o rušení. Doporučený start: **v4.0**.
