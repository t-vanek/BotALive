package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;

/**
 * Přemostění tekutiny (láva, hluboká voda) nebo propasti jedním směrem.
 *
 * <p>Klasická hráčská technika: stoupnout na hranu, položit blok před sebe
 * (do hladiny tekutiny nebo do vzduchu), přejít na něj a opakovat, dokud se
 * nedosáhne pevného břehu. Task pokládá segmenty přes {@link PlaceBlockTask}
 * (míření → klik → ověření) a mezi segmenty bota sám posouvá – proto jako
 * jediný task využívá {@link #move()}.</p>
 *
 * <p>Dvě geometrie hladiny: tekutina o úroveň níž (deck v úrovni hladiny,
 * chůze rovně) a tekutina v úrovni nohou (deck v úrovni nohou, výstup o blok
 * výš). Task končí úspěchem na pevném břehu, neúspěchem při vyčerpání bloků,
 * segmentů nebo časového rozpočtu.</p>
 *
 * <p>Přechod po vlastním jednoblokovém mostě je choulostivý – jedno špatné
 * zamíření nebo strčení stačí ke spadnutí do prázdna. Proto se bot přes rovný
 * deck <b>plíží</b> (edge back-off fyziky ho udrží nad hranou) a každý segment
 * má vlastní časový strop ({@link #ADVANCE_TICKS}): vázne-li přechod, task
 * skončí a navigace cestu přepočítá z aktuální pozice, místo aby se celý
 * globální rozpočet protlačil do zdi.</p>
 */
public final class BridgeTask implements BotTask {

    /** Výchozí strop položených segmentů (šířka překonatelné překážky). */
    public static final int DEFAULT_MAX_SEGMENTS = 12;
    /** Tvrdý časový strop celého mostu (ticky) – pojistka proti zacyklení. */
    private static final int BUDGET_TICKS = 1200;
    /** Tolerance dosažení středu segmentu při přechodu (bloky, vodorovně). */
    private static final double ADVANCE_TOLERANCE = 0.35;
    /**
     * Strop ticků na přechod JEDNOHO segmentu. Jeden krok trvá ~15–20 ticků
     * i s plížením; delší vázne (strčení, entita, špatné doskočení) – radši
     * skončit a nechat navigaci přepočítat, než tlačit celý globální rozpočet
     * do zdi.
     */
    private static final int ADVANCE_TICKS = 45;

    private enum Phase { PLAN, PLACE, ADVANCE, DONE }

    private final int sx;
    private final int sz;
    private final int maxSegments;

    private Phase phase = Phase.PLAN;
    private PlaceBlockTask placing;
    private BlockPos deck;
    private MoveInput move = MoveInput.IDLE;
    private int segments;
    private int ticks;
    private int advanceTicks;
    private boolean finishing;
    private boolean succeeded;

    /**
     * Most s výchozím stropem {@value #DEFAULT_MAX_SEGMENTS} segmentů.
     *
     * @param sx krok mostu po ose X (-1/0/1); právě jedna osa nenulová
     * @param sz krok mostu po ose Z (-1/0/1)
     */
    public BridgeTask(int sx, int sz) {
        this(sx, sz, DEFAULT_MAX_SEGMENTS);
    }

    /**
     * Most s vlastním stropem: delší lávové mosty povoluje konfigurace
     * ({@code nether.lava-bridge-limit}), void lávky k end city dostávají
     * vyšší strop od {@code EndOuterGoal} (end stone je zadarmo).
     *
     * @param sx          krok mostu po ose X (-1/0/1); právě jedna osa nenulová
     * @param sz          krok mostu po ose Z (-1/0/1)
     * @param maxSegments strop položených segmentů
     */
    public BridgeTask(int sx, int sz, int maxSegments) {
        this.sx = sx;
        this.sz = sz;
        this.maxSegments = Math.max(1, maxSegments);
    }

    /** @return {@code true} pokud most došel na pevný břeh */
    public boolean succeeded() {
        return succeeded;
    }

    @Override
    public MoveInput move() {
        return move;
    }

    @Override
    public boolean tick(BotContext ctx) {
        move = MoveInput.IDLE;
        if (ctx.worldView() == null || ++ticks > BUDGET_TICKS) {
            phase = Phase.DONE;
            return true;
        }
        BlockPos feet = ctx.position().toBlockPos();

        switch (phase) {
            case PLAN -> {
                BlockPos front = feet.offset(sx, 0, sz);
                BlockTraits frontTraits = ctx.worldView().traitsAt(front);
                BlockTraits frontDown = ctx.worldView().traitsAt(front.down());

                // Pevný břeh před nosem. Nic nepostaveno → hned hotovo (navigace
                // to zvládne). Jinak ještě došlápnout na pevnou zem, ať bot
                // nekončí na lipu vlastního mostu (odkud by ho plížení nepustilo
                // dál) – finishing krok na břeh a teprve pak úspěch.
                if (frontDown.solid() && frontTraits.passable()) {
                    if (segments == 0) {
                        phase = Phase.DONE;
                        return true;
                    }
                    deck = front.down();
                    finishing = true;
                    advanceTicks = 0;
                    phase = Phase.ADVANCE;
                    return false;
                }
                if (segments >= maxSegments) {
                    phase = Phase.DONE;
                    return true;
                }
                // Deck: tekutina v úrovni nohou → pokládat do ní (výstup +1);
                // jinak o úroveň níž (hladina/propast pod hranou).
                deck = frontTraits.liquid() ? front : front.down();
                if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
                    phase = Phase.DONE; // došly bloky – most končí v půlce
                    return true;
                }
                placing = new PlaceBlockTask(deck);
                phase = Phase.PLACE;
            }
            case PLACE -> {
                if (!placing.tick(ctx)) {
                    return false; // míří/klika/ověřuje
                }
                placing = null;
                if (!ctx.worldView().traitsAt(deck).solid()) {
                    phase = Phase.DONE; // položení nevyšlo (entita, dosah…)
                    return true;
                }
                segments++;
                advanceTicks = 0;
                phase = Phase.ADVANCE;
            }
            case ADVANCE -> {
                Vec3 center = deck.up().center();
                Vec3 delta = center.sub(ctx.position());
                if (delta.horizontalLength() < ADVANCE_TOLERANCE) {
                    if (finishing) {
                        succeeded = true; // došlápnuto na břeh
                        phase = Phase.DONE;
                        return true;
                    }
                    phase = Phase.PLAN; // stojíme na novém segmentu → další
                    return false;
                }
                if (++advanceTicks > ADVANCE_TICKS) {
                    // Přechod segmentu vázne – skončit, navigace přepočítá
                    // cestu z aktuální pozice (most zůstává jako pochozí).
                    succeeded = segments > 0;
                    phase = Phase.DONE;
                    return true;
                }
                // Krok na čerstvý segment; deck v úrovni nohou chce výskok.
                // Při chůzi po vlastním 1-blokovém decku se bot plíží – edge
                // back-off ho udrží nad hranou, takže jedno strčení / špatné
                // zamíření ho neshodí do prázdna. Při výskoku o patro (deck
                // v úrovni nohou) se neplíží, potřebuje čistý odraz.
                boolean jump = deck.y() >= feet.y();
                move = new MoveInput(delta.horizontal().normalized(), false, jump, !jump);
                return false;
            }
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (placing != null) {
            placing.cancel(ctx);
            placing = null;
        }
        phase = Phase.DONE;
    }
}
