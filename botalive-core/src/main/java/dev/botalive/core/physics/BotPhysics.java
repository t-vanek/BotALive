package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Zjednodušená, ale věrohodná fyzika hráče (klientská simulace pohybu).
 *
 * <p>Cíl není bit-exact kopie vanilla klienta, ale pohyb, který:
 * (a) server přijme (rychlosti v mezích anti-cheat kontrol „moved too quickly“),
 * (b) vypadá lidsky (setrvačnost, oblouky, doskoky) a
 * (c) je deterministický vůči {@link WorldView} pro plánování cest.</p>
 *
 * <p>Konstanty vychází z vanilla hodnot: chůze ~4.32 m/s, sprint ~5.61 m/s,
 * gravitace 0.08/tick², skok 0.42, útlum rychlosti 0.91 (země) resp. 0.98 (vzduch).</p>
 */
public final class BotPhysics {

    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG_Y = 0.98;
    private static final double AIR_FRICTION = 0.91;
    private static final double WALK_ACCEL = 0.098;
    private static final double SPRINT_ACCEL = 0.127;
    private static final double SNEAK_ACCEL = 0.029;
    private static final double AIR_ACCEL = 0.02;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double STEP_HEIGHT = 0.6;
    private static final double WATER_DRAG = 0.8;
    /** Síla snosu proudem tekoucí vody (vanilla ~0.014/tick). */
    private static final double WATER_FLOW_PUSH = 0.014;
    /** Rychlost šplhání vzhůru po žebříku (vanilla 0.2 bloku/tick). */
    private static final double CLIMB_UP_SPEED = 0.2;
    /** Nejrychlejší pád po žebříku (vanilla −0.15). */
    private static final double CLIMB_FALL_SPEED = 0.15;
    /** Strop vodorovné rychlosti na žebříku (vanilla ±0.15). */
    private static final double CLIMB_MAX_HORIZONTAL = 0.15;
    private static final double MAX_FALL_SPEED = -3.92;
    /** Výška pádu (bloky), od které začíná poškození – vanilla „safe fall" = 3. */
    private static final double FALL_DAMAGE_THRESHOLD = 3.0;
    /** Krok, po kterém se ořezává pohyb u hrany při plížení (vanilla 0.05). */
    private static final double EDGE_BACKOFF_STEP = 0.05;
    private static final double EPSILON = 1.0E-7;

    private final WorldView world;

    /** Rychlost stoupání prašanem při držení skoku (vanilla únik jump-spamem). */
    private static final double SNOW_CLIMB_SPEED = 0.08;
    /** Rychlost klesání v prašanu (bez akumulace – hustý sníh brzdí). */
    private static final double SNOW_SINK_SPEED = 0.12;
    /** Vodorovný „stuck" multiplikátor prašanu (vanilla 0.9 na tick bez setrvačnosti). */
    private static final double SNOW_STUCK_HORIZONTAL = 0.9;

    /** Vodorovný multiplikátor „váznutí" v pavučině (vanilla 0.25). */
    private static final double WEB_HORIZONTAL = 0.25;
    /** Svislý multiplikátor váznutí v pavučině (vanilla 0.05). */
    private static final double WEB_VERTICAL = 0.05;

    /**
     * Světlost nad podlahou nutná k narovnání (vestoje). Stojící hitbox 1,8
     * zasahuje 0,8 do buňky nad hlavou; níž začínající strop = nutno se plazit.
     */
    private static final double STAND_CLEARANCE = 0.8;
    /**
     * Jak daleko před sebe bot „nakoukne", jestli ho čeká jednobloková mezera
     * (přes hranici buňky do sousedního sloupce) – vstupní heuristika plazení.
     */
    private static final double CRAWL_PEEK = 0.6;

    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private boolean onGround;
    private boolean inWater;
    private boolean onClimbable;
    private boolean inPowderSnow;
    private boolean inWeb;
    private boolean horizontalCollision;

    /**
     * Plazení povoleno konfigurací ({@code ai.crawling}) – EXPERIMENTÁLNÍ,
     * default vypnuto. Vypnuto = hitbox vždy 1,8 (žádná změna chování).
     */
    private boolean crawlEnabled;
    /** Bot je právě sražený do plazivé pózy (hitbox {@link AABB#CRAWL_HEIGHT}). */
    private boolean crawling;
    /** Kolik ticků v kuse má bot hlavu pod hladinou (dochází mu dech). */
    private int submergedTicks;

    /** Levitace (střela shulkera) – server ji aplikuje, klient musí taky. */
    private boolean levitating;
    /** Slow falling – gravitace klesá na 0.01 a pád nezraňuje. */
    private boolean slowFalling;
    /** Let na elytrách (fall flying) – řídí se pohledem, ne vstupem. */
    private boolean fallFlying;
    /** Směr pohledu pro elytrový let (jednotkový vektor). */
    private Vec3 glideLook;
    /** Zbývající ticky tahu rakety (použitá raketa při letu). */
    private int rocketBoostTicks;

    /** Kumulovaná výška aktuálního pádu (bloky); nula, když bot nepadá. */
    private double fallDistance;
    /** Výška posledního dokončeného pádu v okamžiku dopadu (bloky). */
    private double lastFallDistance;
    /** Odhad poškození z posledního dopadu (body; 2 body = 1 srdce). */
    private int lastFallDamage;
    /** {@code true} jen v ticku, kdy bot právě dopadl na zem po pádu. */
    private boolean landedThisTick;

    /**
     * @param world pohled na svět pro kolize
     * @param start počáteční pozice (nohy bota)
     */
    public BotPhysics(WorldView world, Vec3 start) {
        this.world = world;
        this.position = start;
    }

    /** @return aktuální pozice nohou bota */
    public Vec3 position() {
        return position;
    }

    /** @return aktuální rychlost (bloky/tick) */
    public Vec3 velocity() {
        return velocity;
    }

    /** @return {@code true} pokud bot stojí na zemi */
    public boolean onGround() {
        return onGround;
    }

    /** @return {@code true} pokud je bot ve vodě */
    public boolean inWater() {
        return inWater;
    }

    /** @return {@code true} pokud se bot boří v prašanu (powder snow) */
    public boolean inPowderSnow() {
        return inPowderSnow;
    }

    /** @return {@code true} pokud bot vázne v pavučině */
    public boolean inWeb() {
        return inWeb;
    }

    /**
     * Zapne/vypne plazení ({@code ai.crawling}) – experimentální, default
     * vypnuto. Vypnuto = hitbox zůstává vždy 1,8 (bez jakékoli změny fyziky).
     *
     * @param enabled povolit sražení do plazivé pózy v jednoblokových mezerách
     */
    public void setCrawlEnabled(boolean enabled) {
        this.crawlEnabled = enabled;
        if (!enabled) {
            this.crawling = false;
        }
    }

    /** @return {@code true} pokud je bot právě sražený do plazivé pózy (hitbox 0,6) */
    public boolean crawling() {
        return crawling;
    }

    /** @return počet ticků v kuse s hlavou pod hladinou (0 = dýchá) */
    public int submergedTicks() {
        return submergedTicks;
    }

    /** @return {@code true} pokud tick skončil vodorovnou kolizí (naražení do zdi) */
    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    /** @return kumulovaná výška probíhajícího pádu v blocích (0, když bot nepadá) */
    public double fallDistance() {
        return fallDistance;
    }

    /** @return {@code true} jen v ticku, kdy bot právě dopadl na zem po pádu */
    public boolean landedThisTick() {
        return landedThisTick;
    }

    /** @return výška posledního dokončeného pádu v okamžiku dopadu (bloky) */
    public double lastFallDistance() {
        return lastFallDistance;
    }

    /**
     * Odhad poškození z posledního dopadu. Vanilla vzorec:
     * {@code ceil(výška − 3)} bodů (2 body = 1 srdce); pád do 3 bloků neubližuje.
     * Měkký dopad (seno, slime, med, postel) tlumí na ~20 %; pád do vody se
     * nuluje průběžně. Nezohledňuje efekty (Slow Falling, Jump Boost).
     *
     * @return odhadované poškození v bodech (0 = bez zranění)
     */
    public int lastFallDamage() {
        return lastFallDamage;
    }

    /**
     * Tvrdé nastavení pozice (teleport od serveru). Vynuluje rychlost.
     *
     * @param pos nová pozice
     */
    public void teleport(Vec3 pos) {
        this.position = pos;
        this.velocity = Vec3.ZERO;
        this.fallDistance = 0;
    }

    /**
     * Přičte impulz k rychlosti (odhození výbuchem).
     *
     * @param impulse změna rychlosti
     */
    public void addImpulse(Vec3 impulse) {
        this.velocity = velocity.add(impulse);
    }

    /**
     * Nastaví absolutní rychlost (SetEntityMotion – knockback od serveru).
     *
     * @param newVelocity nová rychlost (bloky/tick)
     */
    public void setVelocity(Vec3 newVelocity) {
        this.velocity = newVelocity;
    }

    /**
     * Provede jeden fyzikální tick.
     *
     * @param input pohybový záměr bota pro tento tick
     */
    public void step(MoveInput input) {
        updateMediums();
        updateCrawlPose(input);
        boolean wasOnGround = onGround;

        double vx = velocity.x();
        double vy = velocity.y();
        double vz = velocity.z();

        // --- Akcelerace ze vstupu -------------------------------------------------
        BlockTraits support = supportTraits();
        Vec3 dir = input.direction();
        if (dir.horizontalLength() > EPSILON) {
            // V prašanu se bot brodí plnou chodeckou silou (vzdušná akcelerace
            // by ho v sněhu prakticky zastavila – vanilla „stuck" bere vstup).
            double accel = inPowderSnow ? WALK_ACCEL
                    : !onGround ? AIR_ACCEL
                    : input.sneak() ? SNEAK_ACCEL
                    : input.sprint() ? SPRINT_ACCEL
                    : WALK_ACCEL;
            if (onGround && !inPowderSnow) {
                // Vanilla: pozemní akcelerace škáluje s (0.6/slip)³ – na ledu
                // se špatně zrychluje; pomalé povrchy (soul sand, med) navíc
                // tlumí přes speedFactor.
                double slip = support.slipperiness();
                double slipRatio = BlockTraits.DEFAULT_SLIPPERINESS / slip;
                accel *= slipRatio * slipRatio * slipRatio * support.speedFactor();
            }
            vx += dir.x() * accel;
            vz += dir.z() * accel;
        }

        // --- Vertikála: skok / plavání / šplhání / gravitace ----------------------
        if (inWater) {
            vy = input.jump() ? Math.min(vy + 0.04, 0.12) : (vy - 0.02) * WATER_DRAG;
            vx *= WATER_DRAG;
            vz *= WATER_DRAG;
            // Proud: tekoucí voda bota snáší po směru gradientu hladin
            // (vanilla ~0.014/tick; s dragem 0.8 vychází terminální snos
            // ~0.056 bloku/tick ≈ 1,1 m/s). Zdrojová tůň proud nemá.
            Vec3 flow = dev.botalive.core.world.WaterFlow.at(
                    world::traitsAt, position.toBlockPos());
            vx += flow.x() * WATER_FLOW_PUSH;
            vy += flow.y() * WATER_FLOW_PUSH;
            vz += flow.z() * WATER_FLOW_PUSH;
        } else if (inWeb) {
            // Pavučina: pohyb drasticky vázne v obou osách (vanilla stuck
            // multiplikátory), rychlost se mezi ticky neakumuluje.
            vy = (vy - GRAVITY) * WEB_VERTICAL;
            vx *= WEB_HORIZONTAL;
            vz *= WEB_HORIZONTAL;
        } else if (inPowderSnow) {
            // Prašan: hustý sníh bez kolize – bot se boří. Držení skoku znamená
            // pomalé stoupání (vanilla únik jump-spamem), jinak zvolna klesá;
            // rychlost se mezi ticky neakumuluje („stuck" chování).
            vy = input.jump() ? SNOW_CLIMB_SPEED : -SNOW_SINK_SPEED;
            vx *= SNOW_STUCK_HORIZONTAL;
            vz *= SNOW_STUCK_HORIZONTAL;
        } else if (onClimbable) {
            // Vanilla žebřík: vodorovná rychlost omezená na ±0.15, pád pomalý
            // (max −0.15), šplhání vzhůru rychlostí 0.2/t při držení skoku nebo
            // tlaku ke stěně. Přesná replika, aby server pohyb přijal.
            if (input.jump() || dir.horizontalLength() > EPSILON) {
                vy = CLIMB_UP_SPEED;
            } else if (input.sneak()) {
                vy = 0.0; // přidržení – neklouzat dolů
            } else {
                vy = Math.max(vy - GRAVITY, -CLIMB_FALL_SPEED);
            }
            vx = clamp(vx, -CLIMB_MAX_HORIZONTAL, CLIMB_MAX_HORIZONTAL);
            vz = clamp(vz, -CLIMB_MAX_HORIZONTAL, CLIMB_MAX_HORIZONTAL);
        } else if (fallFlying && glideLook != null && !onGround) {
            // Elytrový let – vanilla aerodynamika řízená pohledem. Pro
            // jednotkový pohled platí cos(pitch) = vodorovná délka vektoru.
            Vec3 look = glideLook;
            double hLook = look.horizontalLength();
            double sqPitchCos = hLook * hLook;
            vy += -GRAVITY + sqPitchCos * 0.06; // vztlak křídel
            double hSpeed = Math.sqrt(vx * vx + vz * vz);
            if (vy < 0 && hLook > EPSILON) {
                // Klesání se přetavuje v dopředný tah (klouzání).
                double d = vy * -0.1 * sqPitchCos;
                vy += d;
                vx += look.x() / hLook * d;
                vz += look.z() / hLook * d;
            }
            if (look.y() > 0 && hLook > EPSILON) {
                // Pohled vzhůru: výměna rychlosti za výšku (flare).
                double d = hSpeed * look.y() * 0.04;
                vy += d * 3.2;
                vx -= look.x() / hLook * d;
                vz -= look.z() / hLook * d;
            }
            if (hLook > EPSILON) {
                // Zatáčení – rychlost se stáčí za pohledem (10 %/tick).
                vx += (look.x() / hLook * hSpeed - vx) * 0.1;
                vz += (look.z() / hLook * hSpeed - vz) * 0.1;
            }
            if (rocketBoostTicks > 0) {
                // Raketa (vanilla FireworkRocketEntity): tah táhne rychlost
                // k 1,5násobku směru pohledu – server počítá totéž nad
                // připnutou raketou, klient MUSÍ boost simulovat, jinak se
                // rozjede a korekce let utrhnou.
                rocketBoostTicks--;
                vx += look.x() * 0.1 + (look.x() * 1.5 - vx) * 0.5;
                vy += look.y() * 0.1 + (look.y() * 1.5 - vy) * 0.5;
                vz += look.z() * 0.1 + (look.z() * 1.5 - vz) * 0.5;
            }
            vx *= 0.99;
            vy *= 0.98;
            vz *= 0.99;
        } else if (levitating) {
            // Levitace (vanilla): rychlost konverguje k +0.05/tick (amp. 0).
            // Bez téhle větve by se klient po zásahu shulkerem rozjel se
            // serverem – server bota zvedá, zatímco klient by padal.
            vy += (0.05 - vy) * 0.2;
        } else {
            boolean jumped = false;
            if (input.jump() && onGround) {
                vy = JUMP_VELOCITY;
                jumped = true;
                if (input.sprint()) {
                    // sprint-jump boost ve směru pohybu (vanilla chování)
                    Vec3 boost = dir.mul(0.2);
                    vx += boost.x();
                    vz += boost.z();
                }
            }
            // V ticku odrazu se gravitace NEuplatní – vanilla pohne entitou
            // o plných 0,42 a gravitaci sráží až od dalšího ticku. Dřívější
            // srážení už v ticku odrazu snižovalo vrchol skoku na ~0,83 bloku
            // (vanilla 1,25): výskok na +1 římsu maskoval step-up, ale pilíř
            // (pokládka pod nohy vyžaduje světlost ≥ 1,0) byl potichu nemožný
            // – odhalila to fyzická simulace PillarUpTasku.
            if (!jumped) {
                // Slow falling (vanilla): gravitace při klesání jen 0.01.
                double gravity = slowFalling && vy <= 0 ? 0.01 : GRAVITY;
                vy = (vy - gravity) * AIR_DRAG_Y;
            }
            vy = Math.max(vy, MAX_FALL_SPEED);
        }

        // --- Kolize a posun -------------------------------------------------------
        Vec3 attempted = new Vec3(vx, vy, vz);
        Vec3 moved = collide(attempted, input.sneak());

        position = position.add(moved);
        horizontalCollision = Math.abs(moved.x() - attempted.x()) > EPSILON
                || Math.abs(moved.z() - attempted.z()) > EPSILON;
        boolean verticalCollision = Math.abs(moved.y() - attempted.y()) > EPSILON;
        onGround = verticalCollision && attempted.y() < 0;

        // --- Pády: kumulace výšky a odhad poškození při dopadu ---------------------
        // Ve vodě, na žebříku, v prašanu a v pavučině se pád „nuluje" (měkký
        // doskok). Jinak přičítáme uraženou výšku klesání; v ticku dopadu
        // spočteme poškození a resetujeme.
        landedThisTick = false;
        if (inWater || onClimbable || inPowderSnow || inWeb || fallFlying
                || slowFalling || levitating) {
            // Měkké režimy pád nulují (elytry: konzervativní řízení letu
            // nedovoluje strmé střemhlavé nálety, kinetická energie se
            // nemodeluje; slow falling nezraňuje z principu).
            fallDistance = 0;
        } else if (moved.y() < 0) {
            fallDistance -= moved.y(); // moved.y je záporné → přičti kladnou výšku
        }
        if (onGround) {
            if (!wasOnGround) {
                landedThisTick = true;
                lastFallDistance = fallDistance;
                lastFallDamage = fallDamageFor(fallDistance, supportTraits());
            }
            fallDistance = 0;
        }

        // --- Tření ----------------------------------------------------------------
        // Pozemní tření závisí na kluzkosti opory (vanilla slip × 0.91):
        // běžný blok 0.546, led 0.892 – bot na ledu klouže. Elytrový let má
        // vlastní odpor (0.99/0.98 ve větvi letu) – běžné vzdušné tření by
        // klouzání zabilo (vanilla ho na letícího neaplikuje).
        double friction = onGround
                ? supportTraits().slipperiness() * AIR_FRICTION
                : fallFlying ? 1.0 : AIR_FRICTION;
        velocity = new Vec3(moved.x() * friction, moved.y(), moved.z() * friction);
        if (verticalCollision) {
            velocity = new Vec3(velocity.x(), 0, velocity.z());
        }

        // Výskok z vody na břeh: plavec u stěny se skokem vyhoupne o blok výš
        // (vanilla „water hop") – jinak by bot u břehu jen šlapal vodu.
        if (inWater && input.jump() && horizontalCollision) {
            velocity = new Vec3(velocity.x(), 0.3, velocity.z());
        }

        // Prašan a pavučina „drží": žádná setrvačnost mezi ticky (vanilla
        // stuck-in-block chování).
        if (inPowderSnow || inWeb) {
            velocity = Vec3.ZERO;
        }

        // Přistání/voda let na elytrách ukončují (vanilla).
        if (fallFlying && (onGround || inWater)) {
            fallFlying = false;
            glideLook = null;
            rocketBoostTicks = 0;
        }
    }

    /**
     * Nastaví aktivní pohybové efekty (čte se z {@code BotClientState} před
     * každým krokem – server je autorita, klient je musí simulovat taky).
     *
     * @param levitation  levitace (střela shulkera)
     * @param slowFalling pomalé padání
     */
    public void effects(boolean levitation, boolean slowFalling) {
        this.levitating = levitation;
        this.slowFalling = slowFalling;
    }

    /**
     * Zahájí let na elytrách (volá se po odlepení od země, spolu s odesláním
     * {@code START_FALL_FLYING}).
     *
     * @param look jednotkový směr pohledu
     */
    public void startGliding(Vec3 look) {
        this.fallFlying = true;
        this.glideLook = look;
    }

    /**
     * Průběžné řízení letu – směr pohledu určuje aerodynamiku.
     *
     * @param look jednotkový směr pohledu
     */
    public void glideLook(Vec3 look) {
        if (fallFlying) {
            this.glideLook = look;
        }
    }

    /** Ukončí let (přistání řeší {@link #step} samo). */
    public void stopGliding() {
        this.fallFlying = false;
        this.glideLook = null;
        this.rocketBoostTicks = 0;
    }

    /** @return {@code true} během letu na elytrách */
    public boolean gliding() {
        return fallFlying;
    }

    /**
     * Zapálí raketu při letu: po dobu {@code ticks} táhne tah rychlost
     * k 1,5násobku směru pohledu (vanilla; raketa letu 1 hoří ~12–20 ticků).
     * Volá se spolu s odesláním use paketu rakety – server simuluje totéž
     * nad připnutou raketou.
     *
     * @param ticks doba hoření rakety
     */
    public void startRocketBoost(int ticks) {
        if (fallFlying) {
            this.rocketBoostTicks = ticks;
        }
    }

    /** @return {@code true} dokud raketa hoří (tah působí) */
    public boolean rocketBoosting() {
        return fallFlying && rocketBoostTicks > 0;
    }

    /** Ořízne hodnotu do intervalu [min, max]. */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Vanilla odhad poškození z pádu: {@code ceil(výška − 3)}, nezáporné.
     * Měkký dopad (seno, slime, med, postel) tlumí na ~20 % – aproximace
     * vanilla hodnot (seno/med ×0.2, slime 0, postel ×0.5) jednou konstantou.
     */
    private static int fallDamageFor(double distance, BlockTraits landing) {
        int raw = (int) Math.max(0, Math.ceil(distance - FALL_DAMAGE_THRESHOLD));
        return landing.softLanding() ? (int) Math.floor(raw * 0.2) : raw;
    }

    /** Vlastnosti bloku přímo pod nohama (opora, na které bot stojí). */
    private BlockTraits supportTraits() {
        return world.traitsAt(new Vec3(position.x(), position.y() - 0.5, position.z()).toBlockPos());
    }

    /** Zjistí, v jakém prostředí se bot nachází (voda, žebřík, prašan, pavučina). */
    private void updateMediums() {
        BlockPos feet = position.toBlockPos();
        BlockTraits feetTraits = world.traitsAt(feet);
        BlockTraits headTraits = world.traitsAt(feet.up());
        inWater = feetTraits.liquid() || headTraits.liquid();
        onClimbable = feetTraits.climbable();
        inPowderSnow = feetTraits.powderSnow() || headTraits.powderSnow();
        inWeb = feetTraits.web() || headTraits.web();
        // Dech: hlava pod hladinou (výška očí ~1,62 nad nohama).
        boolean headUnder = world.traitsAt(
                new Vec3(position.x(), position.y() + 1.62, position.z()).toBlockPos()).liquid();
        submergedTicks = headUnder ? submergedTicks + 1 : 0;
    }

    /** Výška hitboxu pro tento tick: 0,6 při plazení, jinak plná 1,8. */
    private double boxHeight() {
        return crawling ? AABB.CRAWL_HEIGHT : AABB.PLAYER_HEIGHT;
    }

    /**
     * Rozhodne plazivou pózu (experimentální, jen s {@code ai.crawling}).
     *
     * <p>Bot se plazí, když (a) se v aktuální buňce nemůže narovnat – strop
     * začíná níž než {@link #STAND_CLEARANCE} nad hlavou (už je v mezeře),
     * nebo (b) míří vpřed do sousední buňky, která je jednoblokovou mezerou
     * (nohy volné a s oporou, strop nízko) – vstupní heuristika řešící
     * slepičí problém: aby bot do mezery vůbec vkročil, musí se srazit už na
     * jejím prahu. Jinak (volný strop a před ním nic nízkého) stojí. Ve vodě,
     * na žebříku a v prašanu se neplazí – ty mají vlastní režim pohybu.</p>
     */
    private void updateCrawlPose(MoveInput input) {
        if (!crawlEnabled || inWater || onClimbable || inPowderSnow) {
            crawling = false;
            return;
        }
        BlockPos feet = position.toBlockPos();
        if (cannotStandAt(feet)) {
            crawling = true;
            return;
        }
        Vec3 dir = input.direction();
        double len = dir.horizontalLength();
        if (onGround && len > EPSILON) {
            double px = position.x() + dir.x() / len * CRAWL_PEEK;
            double pz = position.z() + dir.z() / len * CRAWL_PEEK;
            if (crawlGapAt(new Vec3(px, position.y(), pz).toBlockPos())) {
                crawling = true;
                return;
            }
        }
        crawling = false;
    }

    /**
     * Nemůže se bot v této buňce narovnat? (strop v úrovni hlavy začíná níž
     * než {@link #STAND_CLEARANCE} – stojící hitbox by narazil). Plazivé tělo
     * 0,6 se přesto vejde, protože nesahá do buňky nad nohama.
     */
    private boolean cannotStandAt(BlockPos feet) {
        return world.traitsAt(feet.up()).lowestCollisionStart() < STAND_CLEARANCE - EPSILON;
    }

    /**
     * Je daná buňka jednobloková mezera k proplížení? Nohy volné pro tělo
     * (nízký profil) s pevnou oporou pod nimi, bez tekutiny/hazardu/pavučiny,
     * a strop tak nízko, že vestoje by to neprošlo (jinak stačí chůze).
     */
    private boolean crawlGapAt(BlockPos feet) {
        BlockTraits t = world.traitsAt(feet);
        BlockTraits head = world.traitsAt(feet.up());
        if (t == BlockTraits.UNKNOWN || head == BlockTraits.UNKNOWN) {
            return false;
        }
        if (t.hazard() || head.hazard() || t.web() || head.web() || t.liquid()) {
            return false;
        }
        if (!t.lowProfile()) {
            return false; // v buňce nohou je částečný/plný blok – tady se nechodí ani neplazí
        }
        double floorBelow = world.traitsAt(feet.down()).floorHeight();
        if (floorBelow < 0.99 || floorBelow > 1.01) {
            return false; // bez pevné podlahy pod mezerou
        }
        return head.lowestCollisionStart() < STAND_CLEARANCE - EPSILON;
    }

    /**
     * Axis-by-axis kolizní řešení s automatickým výstupem na schod (step-up).
     *
     * @param motion zamýšlený posun
     * @param sneak  při plížení bot nespadne z hrany
     * @return skutečný možný posun
     */
    private Vec3 collide(Vec3 motion, boolean sneak) {
        // Plížení u hrany: než vůbec začneme řešit kolize, ořízni vodorovný
        // pohyb tak, aby bot nesešel z pevné hrany do prázdna (vanilla
        // „back off from edge"). Platí jen na zemi a při klesání/rovině.
        if (sneak && onGround && motion.y() <= 0.0) {
            motion = backOffFromEdge(motion);
        }

        AABB box = AABB.playerAt(position, boxHeight());

        double dy = clipAxis(box, motion.y(), Axis.Y);
        box = box.move(0, dy, 0);

        double dx = clipAxis(box, motion.x(), Axis.X);
        box = box.move(dx, 0, 0);

        double dz = clipAxis(box, motion.z(), Axis.Z);

        boolean collidedHorizontally = dx != motion.x() || dz != motion.z();

        // Step-up: pokud jsme narazili do zdi a jsme NA ZEMI, zkusit posun o schod
        // výš. Nikdy na žebříku/liáně – zbytková vodorovná rychlost při sestupu
        // šachtou by bota „vymantlovala" přes okraj ven (vanilla po žebřících
        // taky nedovolí nedobrovolný výstup na hranu). A nikdy ve vzduchu:
        // dřívější povolení při klesání kompenzovalo slabý skok (vrchol 0,83
        // před opravou gravitace v ticku odrazu) – s vanilla skokem 1,25 už
        // dopady na římsy vycházejí z čisté kolize a vzdušný mantle uměl
        // nelegálně přelézt plot (zbývajících 0,25 nad vrcholem skoku spadlo
        // do výšky schodu).
        if (collidedHorizontally && !onClimbable && onGround) {
            AABB stepBox = AABB.playerAt(position, boxHeight());
            double stepUp = clipAxis(stepBox, STEP_HEIGHT, Axis.Y);
            stepBox = stepBox.move(0, stepUp, 0);
            double sdx = clipAxis(stepBox, motion.x(), Axis.X);
            stepBox = stepBox.move(sdx, 0, 0);
            double sdz = clipAxis(stepBox, motion.z(), Axis.Z);
            stepBox = stepBox.move(0, 0, sdz);
            double stepDown = clipAxis(stepBox, -stepUp, Axis.Y);

            if (sdx * sdx + sdz * sdz > dx * dx + dz * dz) {
                return new Vec3(sdx, stepUp + stepDown, sdz);
            }
        }
        return new Vec3(dx, dy, dz);
    }

    /**
     * Ořízne vodorovný pohyb tak, aby plížící se bot zůstal nad pevnou hranou
     * (vanilla {@code maybeBackOffFromEdge}). Postupně zkracuje složky pohybu po
     * {@link #EDGE_BACKOFF_STEP}, dokud by hráč po posunu neztratil oporu pod
     * nohama (kontrola boxu sníženého o {@link #STEP_HEIGHT}).
     *
     * @param motion zamýšlený posun
     * @return posun oříznutý u hrany (svislá složka beze změny)
     */
    private Vec3 backOffFromEdge(Vec3 motion) {
        AABB stand = AABB.playerAt(position);
        double dx = motion.x();
        double dz = motion.z();

        while (dx != 0.0 && !supported(stand, dx, 0.0)) {
            dx = shrinkToward0(dx);
        }
        while (dz != 0.0 && !supported(stand, 0.0, dz)) {
            dz = shrinkToward0(dz);
        }
        while (dx != 0.0 && dz != 0.0 && !supported(stand, dx, dz)) {
            dx = shrinkToward0(dx);
            dz = shrinkToward0(dz);
        }
        return new Vec3(dx, motion.y(), dz);
    }

    /** Má bot po posunu o (dx, dz) pevnou oporu do {@link #STEP_HEIGHT} pod nohama? */
    private boolean supported(AABB stand, double dx, double dz) {
        return intersectsSolid(stand.move(dx, -STEP_HEIGHT, dz));
    }

    /** Zkrátí hodnotu o {@link #EDGE_BACKOFF_STEP} směrem k nule (nepřestřelí). */
    private static double shrinkToward0(double value) {
        if (value < EDGE_BACKOFF_STEP && value >= -EDGE_BACKOFF_STEP) {
            return 0.0;
        }
        return value > 0 ? value - EDGE_BACKOFF_STEP : value + EDGE_BACKOFF_STEP;
    }

    /**
     * Protíná daný box aspoň jednu kolizi bloku? Neznámé chunky mají plnou
     * kolizi (shodně s {@link #clipAxis}) – bot se nespoléhá na nenačtený terén.
     *
     * <p>Rozsah Y začíná o buňku níž: vysoké bloky (plot 1,5) přesahují do
     * buňky nad sebou.</p>
     */
    private boolean intersectsSolid(AABB box) {
        int minX = (int) Math.floor(box.minX());
        int maxX = (int) Math.floor(box.maxX());
        int minY = (int) Math.floor(box.minY()) - 1;
        int maxY = (int) Math.floor(box.maxY());
        int minZ = (int) Math.floor(box.minZ());
        int maxZ = (int) Math.floor(box.maxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double[] boxes = world.traitsAt(new BlockPos(x, y, z)).boxes();
                    for (int i = 0; i < boxes.length; i += 6) {
                        AABB block = new AABB(x + boxes[i], y + boxes[i + 1], z + boxes[i + 2],
                                x + boxes[i + 3], y + boxes[i + 4], z + boxes[i + 5]);
                        if (box.intersects(block)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private enum Axis { X, Y, Z }

    /**
     * Ořízne posun po jedné ose o kolize s pevnými bloky.
     *
     * @param box    hitbox před posunem
     * @param motion zamýšlený posun po ose
     * @param axis   osa
     * @return maximální možný posun
     */
    private double clipAxis(AABB box, double motion, Axis axis) {
        if (Math.abs(motion) < EPSILON) {
            return 0;
        }
        AABB swept = switch (axis) {
            case X -> new AABB(
                    Math.min(box.minX(), box.minX() + motion), box.minY(), box.minZ(),
                    Math.max(box.maxX(), box.maxX() + motion), box.maxY(), box.maxZ());
            case Y -> new AABB(
                    box.minX(), Math.min(box.minY(), box.minY() + motion), box.minZ(),
                    box.maxX(), Math.max(box.maxY(), box.maxY() + motion), box.maxZ());
            case Z -> new AABB(
                    box.minX(), box.minY(), Math.min(box.minZ(), box.minZ() + motion),
                    box.maxX(), box.maxY(), Math.max(box.maxZ(), box.maxZ() + motion));
        };

        double result = motion;
        int minX = (int) Math.floor(swept.minX());
        int maxX = (int) Math.floor(swept.maxX());
        // O buňku níž kvůli přesahu vysokých bloků (plot 1,5) do buňky nad.
        int minY = (int) Math.floor(swept.minY()) - 1;
        int maxY = (int) Math.floor(swept.maxY());
        int minZ = (int) Math.floor(swept.minZ());
        int maxZ = (int) Math.floor(swept.maxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Neznámé chunky mají plnou kolizi – bot nevkročí do nenačtena.
                    double[] boxes = world.traitsAt(new BlockPos(x, y, z)).boxes();
                    for (int i = 0; i < boxes.length; i += 6) {
                        AABB block = new AABB(x + boxes[i], y + boxes[i + 1], z + boxes[i + 2],
                                x + boxes[i + 3], y + boxes[i + 4], z + boxes[i + 5]);
                        result = clipAgainst(box, block, result, axis);
                    }
                }
            }
        }
        return result;
    }

    /** Ořízne posun {@code motion} tak, aby {@code box} nepronikl do {@code block}. */
    private static double clipAgainst(AABB box, AABB block, double motion, Axis axis) {
        // Kolidují ostatní dvě osy?
        boolean overlap = switch (axis) {
            case X -> box.minY() < block.maxY() && box.maxY() > block.minY()
                    && box.minZ() < block.maxZ() && box.maxZ() > block.minZ();
            case Y -> box.minX() < block.maxX() && box.maxX() > block.minX()
                    && box.minZ() < block.maxZ() && box.maxZ() > block.minZ();
            case Z -> box.minX() < block.maxX() && box.maxX() > block.minX()
                    && box.minY() < block.maxY() && box.maxY() > block.minY();
        };
        if (!overlap) {
            return motion;
        }
        double boxMin = switch (axis) {
            case X -> box.minX();
            case Y -> box.minY();
            case Z -> box.minZ();
        };
        double boxMax = switch (axis) {
            case X -> box.maxX();
            case Y -> box.maxY();
            case Z -> box.maxZ();
        };
        double blockMin = switch (axis) {
            case X -> block.minX();
            case Y -> block.minY();
            case Z -> block.minZ();
        };
        double blockMax = switch (axis) {
            case X -> block.maxX();
            case Y -> block.maxY();
            case Z -> block.maxZ();
        };
        // Blok omezuje pohyb jen tehdy, leží-li ve směru pohybu (ne za zády,
        // ne v překryvu – z penetrace se bot smí volně vysunout).
        if (motion > 0 && blockMin >= boxMax) {
            return Math.min(motion, Math.max(0, blockMin - boxMax - EPSILON));
        }
        if (motion < 0 && blockMax <= boxMin) {
            return Math.max(motion, Math.min(0, blockMax - boxMin + EPSILON));
        }
        return motion;
    }
}
