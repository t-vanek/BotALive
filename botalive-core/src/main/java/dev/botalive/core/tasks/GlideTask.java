package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;

/**
 * Let na elytrách k cíli – rozběh přes hranu (nebo raketový start ze země),
 * rozevření křídel, klouzání a podrovnání před přistáním.
 *
 * <p>Volající ručí za smysluplný start: bot má elytry v hrudním slotu
 * a buď stojí na hraně s převýšením nad cílem (viz {@link #viable}), nebo
 * má rakety a task smí boostovat ({@link #GlideTask(BlockPos, boolean)}).
 * Task bota rozeběhne směrem k cíli; jakmile je ve vzduchu, rozevře křídla
 * ({@code START_FALL_FLYING} + klientská aerodynamika v {@code BotPhysics})
 * a dál kormidluje pohledem: mírné klesání na cíl, u země podrovnání
 * (flare). Přistání ukončuje fyzika (dotyk země/vody).</p>
 *
 * <p><b>Rakety</b>: použití rakety (use paket + klientská simulace tahu –
 * server počítá totéž nad připnutou raketou) prodlužuje dolet za klouzavý
 * poměr, umožňuje start ze země výskokem a přelet stoupání. Řízení zůstává
 * konzervativní: boost se zapaluje jen mimo podrovnání, s rozestupem
 * a s rozpočtem – žádné ohňostrojové akrobacie. Selže-li rozběh nebo let
 * trvá nesmyslně dlouho, task se vzdá a křídla složí – pád jistí stávající
 * reflexy ({@code FallReflex}).</p>
 */
public final class GlideTask implements BotTask {

    /** Minimální převýšení, aby čistý klouzavý let dával smysl (bloky). */
    private static final double MIN_HEIGHT_ADVANTAGE = 12;

    /** Konzervativní klouzavost pro plánování (vanilla ~10:1, rezerva). */
    private static final double SAFE_GLIDE_RATIO = 6.0;

    /** Kolik bloků doletu navíc plánovat na jednu raketu (konzervativně). */
    private static final double ROCKET_RANGE = 40;

    /** Strop délky rozběhu (ticky) – pak to nejspíš není hrana. */
    private static final int RUNUP_BUDGET_TICKS = 100;

    /** Strop délky letu (ticky) – pojistka proti kroužení. */
    private static final int FLIGHT_BUDGET_TICKS = 20 * 45;

    /** Strop letu s raketami (delší trasy přes void). */
    private static final int POWERED_BUDGET_TICKS = 20 * 120;

    /** Pod tímhle převýšením nad cílem začíná podrovnání. */
    private static final double FLARE_HEIGHT = 8;

    /** Mez sklonu pohledu dolů (složka y jednotkového vektoru). */
    private static final double MAX_DIVE_Y = -0.45;

    /** Doba tahu jedné rakety (ticky; vanilla letu 1 hoří ~12–20). */
    private static final int BOOST_TICKS = 16;

    /** Nejvýš tolik raket na jeden let – zbytek má zůstat na návrat. */
    private static final int ROCKET_BUDGET = 12;

    private enum Phase { RUNUP, GLIDE, DONE }

    private final BlockPos target;
    private final boolean rockets;

    private Phase phase = Phase.RUNUP;
    private MoveInput move = MoveInput.IDLE;
    private int runupTicks;
    private int flightTicks;
    private int airborneTicks;
    private int boostCooldown;
    private int rocketsUsed;
    private boolean flew;

    /**
     * Čistě klouzavý let (bez raket).
     *
     * @param target cílový blok přistání
     */
    public GlideTask(BlockPos target) {
        this(target, false);
    }

    /**
     * @param target  cílový blok přistání
     * @param rockets smí task pálit rakety (boost, start ze země)
     */
    public GlideTask(BlockPos target, boolean rockets) {
        this.target = target;
        this.rockets = rockets;
    }

    /**
     * Dává čistě klouzavý let smysl? Cíl musí ležet dostatečně hluboko pod
     * startem a v dosahu konzervativní klouzavosti.
     *
     * @param from   startovní pozice (nohy bota)
     * @param target cílový blok
     * @return {@code true}, pokud se vyplatí letět místo šlapat
     */
    public static boolean viable(Vec3 from, BlockPos target) {
        double drop = from.y() - target.y();
        if (drop < MIN_HEIGHT_ADVANTAGE) {
            return false;
        }
        double dx = target.x() + 0.5 - from.x();
        double dz = target.z() + 0.5 - from.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal <= drop * SAFE_GLIDE_RATIO;
    }

    /**
     * Dává let s raketami smysl? Rakety kupují dolet nad klouzavý poměr
     * (~{@value #ROCKET_RANGE} bloků/kus) a převýšení přestává být podmínkou
     * (start ze země výskokem + boost).
     *
     * @param from        startovní pozice (nohy bota)
     * @param target      cílový blok
     * @param rocketCount rakety v inventáři (po odečtení rezervy volajícího)
     * @return {@code true}, pokud na to dolet s raketami stačí
     */
    public static boolean viableWithRockets(Vec3 from, BlockPos target, int rocketCount) {
        if (rocketCount < 2) {
            return viable(from, target);
        }
        double drop = Math.max(0, from.y() - target.y());
        double dx = target.x() + 0.5 - from.x();
        double dz = target.z() + 0.5 - from.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double budget = Math.min(rocketCount, ROCKET_BUDGET);
        return horizontal <= drop * SAFE_GLIDE_RATIO + budget * ROCKET_RANGE;
    }

    @Override
    public boolean tick(BotContext ctx) {
        switch (phase) {
            case RUNUP -> tickRunup(ctx);
            case GLIDE -> tickGlide(ctx);
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    private void tickRunup(BotContext ctx) {
        if (ctx.gliding()) {
            phase = Phase.GLIDE;
            return;
        }
        if (!ctx.onGround()) {
            // Ve vzduchu (hrana, nebo výskok raketového startu) – po pár
            // ticích rozevřít křídla; s raketami hned zažehnout první boost.
            if (++airborneTicks >= 3) {
                ctx.startGliding(lookToward(ctx.position()));
                if (rockets) {
                    fireRocket(ctx);
                }
                phase = Phase.GLIDE;
            }
        } else {
            airborneTicks = 0;
            if (++runupTicks > RUNUP_BUDGET_TICKS) {
                phase = Phase.DONE; // hrana nikde (a bez raket) – pěšky
                return;
            }
        }
        Vec3 pos = ctx.position();
        double dx = target.x() + 0.5 - pos.x();
        double dz = target.z() + 0.5 - pos.z();
        // S raketami se startuje výskokem ze sprintu (vanilla ground takeoff);
        // bez nich se běží k hraně a čeká na odlepení.
        move = rockets
                ? MoveInput.of(new Vec3(dx, 0, dz), true, runupTicks > 4)
                : MoveInput.walk(new Vec3(dx, 0, dz));
    }

    private void tickGlide(BotContext ctx) {
        move = MoveInput.IDLE;
        if (!ctx.gliding()) {
            flew = true;
            phase = Phase.DONE; // dosedl (fyzika let ukončila)
            return;
        }
        int budget = rockets ? POWERED_BUDGET_TICKS : FLIGHT_BUDGET_TICKS;
        if (++flightTicks > budget) {
            ctx.stopGliding(); // pojistka – zbytek jistí pádové reflexy
            phase = Phase.DONE;
            return;
        }
        if (boostCooldown > 0) {
            boostCooldown--;
        }
        Vec3 pos = ctx.position();
        Vec3 look = lookToward(pos);
        double heightOver = pos.y() - target.y();
        double dx = target.x() + 0.5 - pos.x();
        double dz = target.z() + 0.5 - pos.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean flare = heightOver < FLARE_HEIGHT && horizontal < 24;
        if (flare) {
            // Podrovnání: pohled mírně vzhůru vymění rychlost za výšku
            // a dosed je měkký.
            look = unit(new Vec3(look.x(), 0.25, look.z()));
        } else if (rockets && !ctx.rocketBoosting() && boostCooldown == 0) {
            // Boost, když klouzání na cíl nestačí: cíl nad botem, nebo dál,
            // než konzervativní klouzavost z aktuální výšky unese.
            boolean needsClimb = heightOver < -2;
            boolean fallingShort = horizontal > Math.max(8, heightOver) * SAFE_GLIDE_RATIO;
            if ((needsClimb || fallingShort) && fireRocket(ctx)) {
                // Tah míří kam pohled: při stoupání ho zvednout.
                if (needsClimb) {
                    look = unit(new Vec3(look.x(), 0.35, look.z()));
                }
            }
        }
        ctx.glideSteer(look);
        // Hlava letí s tělem – viditelný pohled odpovídá aerodynamice.
        ctx.humanizer().lookAt(pos.add(0, 1.62, 0),
                pos.add(look.mul(8)).add(0, 1.62, 0));
    }

    /**
     * Zažehne raketu: vzít do ruky, use paket a klientská simulace tahu.
     *
     * @return {@code true} pokud raketa odešla
     */
    private boolean fireRocket(BotContext ctx) {
        if (rocketsUsed >= ROCKET_BUDGET) {
            return false;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !ctx.inventory().equipItem(snapshot, Material.FIREWORK_ROCKET)) {
            return false;
        }
        ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
        ctx.startRocketBoost(BOOST_TICKS);
        rocketsUsed++;
        boostCooldown = BOOST_TICKS + 8;
        return true;
    }

    /** Jednotkový pohled na cíl s omezeným sklonem (žádné střemhlavé nálety). */
    private Vec3 lookToward(Vec3 from) {
        Vec3 to = new Vec3(target.x() + 0.5 - from.x(),
                target.y() - from.y(), target.z() + 0.5 - from.z());
        Vec3 look = unit(to);
        if (look.y() < MAX_DIVE_Y) {
            look = unit(new Vec3(look.x(), MAX_DIVE_Y, look.z()));
        }
        return look;
    }

    private static Vec3 unit(Vec3 v) {
        double length = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
        return length < 1.0E-8 ? new Vec3(0, -0.1, 1) : v.mul(1.0 / length);
    }

    /** @return {@code true}, pokud bot skutečně letěl a dosedl */
    public boolean flew() {
        return flew;
    }

    /** @return kolik raket let spálil */
    public int rocketsUsed() {
        return rocketsUsed;
    }

    @Override
    public MoveInput move() {
        return move;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.stopGliding();
    }
}
