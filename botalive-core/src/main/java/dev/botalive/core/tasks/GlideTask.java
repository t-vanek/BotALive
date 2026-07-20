package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;

/**
 * Let na elytrách k cíli – rozběh přes hranu, rozevření křídel, klouzání
 * a podrovnání před přistáním.
 *
 * <p>Volající ručí za smysluplný start: bot stojí na hraně s dostatečným
 * převýšením nad cílem (viz {@link #viable}) a elytry má v hrudním slotu.
 * Task bota rozeběhne směrem k cíli; jakmile se odlepí od hrany a padá,
 * rozevře křídla ({@code START_FALL_FLYING} + klientská aerodynamika
 * v {@code BotPhysics}) a dál jen kormidluje pohledem: mírné klesání na
 * cíl, u země podrovnání (flare), které vymění rychlost za měkký dosed.
 * Přistání ukončuje fyzika (dotyk země/vody); task pak hlásí hotovo.</p>
 *
 * <p>Konzervativní řízení: sklon pohledu je omezený, žádné střemhlavé
 * nálety – rychlost zůstává v mezích, kde dosed nebolí. Selže-li rozběh
 * (hrana nikde) nebo let trvá nesmyslně dlouho, task se vzdá a křídla
 * složí – pád jistí stávající reflexy ({@code FallReflex}).</p>
 */
public final class GlideTask implements BotTask {

    /** Minimální převýšení, aby let dával smysl (bloky). */
    private static final double MIN_HEIGHT_ADVANTAGE = 12;

    /** Konzervativní klouzavost pro plánování (vanilla ~10:1, rezerva). */
    private static final double SAFE_GLIDE_RATIO = 6.0;

    /** Strop délky rozběhu (ticky) – pak to nejspíš není hrana. */
    private static final int RUNUP_BUDGET_TICKS = 100;

    /** Strop délky letu (ticky) – pojistka proti kroužení. */
    private static final int FLIGHT_BUDGET_TICKS = 20 * 45;

    /** Pod tímhle převýšením nad cílem začíná podrovnání. */
    private static final double FLARE_HEIGHT = 8;

    /** Mez sklonu pohledu dolů (složka y jednotkového vektoru). */
    private static final double MAX_DIVE_Y = -0.45;

    private enum Phase { RUNUP, GLIDE, DONE }

    private final BlockPos target;

    private Phase phase = Phase.RUNUP;
    private MoveInput move = MoveInput.IDLE;
    private int runupTicks;
    private int flightTicks;
    private int airborneTicks;
    private boolean flew;

    /**
     * @param target cílový blok přistání
     */
    public GlideTask(BlockPos target) {
        this.target = target;
    }

    /**
     * Dává let smysl? Cíl musí ležet dostatečně hluboko pod startem
     * a v dosahu konzervativní klouzavosti.
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
            // Odlepil se od hrany – po pár ticích pádu rozevřít křídla.
            if (++airborneTicks >= 3) {
                ctx.startGliding(lookToward(ctx.position()));
                phase = Phase.GLIDE;
            }
        } else {
            airborneTicks = 0;
            if (++runupTicks > RUNUP_BUDGET_TICKS) {
                phase = Phase.DONE; // hrana nikde, jde se pěšky
                return;
            }
        }
        Vec3 pos = ctx.position();
        double dx = target.x() + 0.5 - pos.x();
        double dz = target.z() + 0.5 - pos.z();
        move = MoveInput.walk(new Vec3(dx, 0, dz));
    }

    private void tickGlide(BotContext ctx) {
        move = MoveInput.IDLE;
        if (!ctx.gliding()) {
            flew = true;
            phase = Phase.DONE; // dosedl (fyzika let ukončila)
            return;
        }
        if (++flightTicks > FLIGHT_BUDGET_TICKS) {
            ctx.stopGliding(); // pojistka – zbytek jistí pádové reflexy
            phase = Phase.DONE;
            return;
        }
        Vec3 pos = ctx.position();
        Vec3 look = lookToward(pos);
        double heightOver = pos.y() - target.y();
        if (heightOver < FLARE_HEIGHT) {
            // Podrovnání: pohled mírně vzhůru vymění rychlost za výšku
            // a dosed je měkký.
            look = unit(new Vec3(look.x(), 0.25, look.z()));
        }
        ctx.glideSteer(look);
        // Hlava letí s tělem – viditelný pohled odpovídá aerodynamice.
        ctx.humanizer().lookAt(pos.add(0, 1.62, 0),
                pos.add(look.mul(8)).add(0, 1.62, 0));
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

    @Override
    public MoveInput move() {
        return move;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.stopGliding();
    }
}
