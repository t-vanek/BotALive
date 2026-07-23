package dev.botalive.core.ai.goals;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.plan.BuildTier;
import dev.botalive.core.build.plan.HouseDesigner;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementTier;

/**
 * Společný základ vestavěných cílů.
 *
 * <p>Poskytuje pohodlný přístup k internímu {@link BotContext} a prázdné
 * implementace lifecycle metod – konkrétní cíle přepisují jen to, co potřebují.</p>
 */
public abstract class AbstractGoal implements Goal {

    private final String id;

    /**
     * @param id stabilní identifikátor cíle
     */
    protected AbstractGoal(String id) {
        this.id = id;
    }

    @Override
    public final String id() {
        return id;
    }

    /**
     * @param bot API bot
     * @return interní kontext bota
     */
    protected static BotContext ctx(Bot bot) {
        return BotContext.of(bot);
    }

    /**
     * Cíle vázané na běžný svět (spánek, farmy, vesnice, rybaření, trh…)
     * se v Netheru/Endu odmlčí – tam žije výprava ({@code NetherGoal}).
     *
     * @param ctx kontext bota
     * @return {@code true} pokud bot NENÍ v overworldu
     */
    protected static boolean outsideOverworld(BotContext ctx) {
        return ctx.dimension() != dev.botalive.core.world.WorldDimension.OVERWORLD;
    }

    /**
     * Cílový stavební stupeň domu bota z prosperity sídla a osobnosti
     * ({@link HouseDesigner#tierFor}). Bez sídla / bez služby = osada (srub).
     * Sdílené místo pravdy pro cíle, které se řídí tím, na jaký dům bot míří
     * (stavba, údržba, těžba surovin, craft/tavba materiálu).
     *
     * @param bot API bot
     * @return cílový stavební stupeň
     */
    protected static BuildTier houseTier(Bot bot) {
        BotContext ctx = ctx(bot);
        SettlementTier tier = SettlementTier.OSADA;
        SettlementService settlements = ctx.settlements();
        if (settlements != null) {
            tier = settlements.settlementOf(bot.id())
                    .map(SettlementService.SettlementInfo::tier).orElse(SettlementTier.OSADA);
        }
        return HouseDesigner.tierFor(tier, bot.personality().trait(Trait.LAZINESS));
    }

    /**
     * Míří bot na reprezentativní (cihlový + tesaný kámen) dům? Gate pro
     * zednické řetězce – tavbu cobble→kámen a craft tesaných cihel – aby je
     * dělal jen stavitel, jehož dům je vyžaduje, a cizí boti neplýtvali.
     *
     * @param bot API bot
     * @return {@code true} pokud je cílový tier {@link BuildTier#REFINED}
     */
    protected static boolean wantsMasonry(Bot bot) {
        return houseTier(bot) == BuildTier.REFINED;
    }

    @Override
    public void start(Bot bot) {
        // výchozí: nic
    }

    @Override
    public void stop(Bot bot) {
        // výchozí: zrušit navigaci, ať po cíli nezůstane rozjetá cesta
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return false;
    }

    /**
     * Blok s vlastním UI/interakcí – klik na něj neumístí drženou stanici,
     * ale otevře ho. Taková „podlaha“ se při pokládání přeskakuje.
     *
     * @param material materiál podlahy (null = neznámý chunk)
     * @return {@code true} pro interaktivní bloky
     */
    private static boolean interactiveBlock(org.bukkit.Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("ANVIL") || name.endsWith("CHEST") || name.endsWith("FURNACE")
                || name.endsWith("_TABLE") || name.endsWith("BED") || name.contains("DOOR")
                || material == org.bukkit.Material.COMPOSTER
                || material == org.bukkit.Material.BARREL
                || material == org.bukkit.Material.SMOKER
                || material == org.bukkit.Material.STONECUTTER
                || material == org.bukkit.Material.GRINDSTONE
                || material == org.bukkit.Material.BREWING_STAND;
    }

    /** Výsledek jednoho pokusu o položení stanice. */
    protected enum PlaceAttempt {
        /** Stanice není v inventáři – nemá smysl to zkoušet dál. */
        NO_ITEM,
        /** Kolem bota teď není vhodné místo (voda, sráz…) – zkusit později. */
        NO_SPOT,
        /** Item se teprve přetahuje do hotbaru – klik přijde v dalším pokusu. */
        EQUIPPING,
        /** Klik na podlahu odeslán – blok by se měl objevit. */
        CLICKED
    }

    /**
     * Jeden pokus o položení vlastní stanice (pec, truhla, kovadlina…) vedle bota.
     *
     * <p>Najde volný sousední sloupec s pevnou podlahou, vezme stanici do ruky
     * a klikne na podlahu – jako hráč. Míření hlavy ale trvá několik ticků
     * (humanizer otáčí plynule) a server klik s nesedící rotací zahodí, takže
     * jeden pokus nestačí – volat opakovaně přes {@link StationPlacement},
     * dokud sken cíle blok nenajde.</p>
     *
     * @param ctx     kontext bota
     * @param station materiál stanice v inventáři
     * @return výsledek pokusu
     */
    protected static PlaceAttempt placeOwnStation(BotContext ctx, org.bukkit.Material station) {
        var log = org.slf4j.LoggerFactory.getLogger(AbstractGoal.class);
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || ctx.worldView() == null
                || !snapshot.hasItem(m -> m == station)) {
            return PlaceAttempt.NO_ITEM;
        }
        dev.botalive.core.util.BlockPos feet = ctx.position().toBlockPos();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            var target = feet.offset(d[0], 0, d[1]);
            var below = target.down();
            // Sloupec, do kterého zasahuje vlastní bounding box (šířka 0.6),
            // přeskočit – server placement do entity tiše odmítne.
            double gapX = Math.abs(ctx.position().x() - (target.x() + 0.5));
            double gapZ = Math.abs(ctx.position().z() - (target.z() + 0.5));
            if (Math.max(gapX, gapZ) < 0.82) {
                continue;
            }
            if (ctx.worldView().traitsAt(target).passable()
                    && ctx.worldView().traitsAt(target.up()).passable()
                    && ctx.worldView().traitsAt(below).solid()
                    && !interactiveBlock(ctx.worldView().materialAt(below))) {
                if (!ctx.inventory().equipItem(snapshot, station)) {
                    return PlaceAttempt.EQUIPPING;
                }
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        below.center().add(0, 0.5, 0));
                ctx.actions().useItemOn(below,
                        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.UP);
                log.debug("[placeOwnStation] {} klik: below={} feet={}", station, below, feet);
                return PlaceAttempt.CLICKED;
            }
        }
        log.debug("[placeOwnStation] {} bez místa u feet={}", station, feet);
        return PlaceAttempt.NO_SPOT;
    }

    /**
     * Opakované pokládání vlastní stanice přes ticky.
     *
     * <p>FIND fáze cíle volá {@link #tick(BotContext)} každý tick, dokud její
     * sken stanici nenajde. Pokusy jdou po čtyřech ticích, aby mělo míření čas
     * se dotočit (stejný princip jako AIM fáze u
     * {@link dev.botalive.core.tasks.PlaceBlockTask}); po ~5 s to vzdá.
     * Okamžitě to vzdá jen bez itemu – „není místo“ se zkouší dál, bot se
     * mezitím mohl zastavit nebo vylézt z vody.</p>
     */
    protected static final class StationPlacement {

        private static final int BUDGET_TICKS = 100;

        private final org.bukkit.Material station;
        private int ticks;

        /**
         * @param station materiál stanice, kterou má bot v inventáři
         */
        protected StationPlacement(org.bukkit.Material station) {
            this.station = station;
        }

        /** Do kdy tolerovat NO_ITEM – server-side snapshot bývá vteřinu pozadu. */
        private static final int NO_ITEM_GRACE_TICKS = 40;

        /**
         * Jeden tick pokládání.
         *
         * @param ctx kontext bota
         * @return {@code true} dokud má smysl pokračovat; {@code false} = vzdát
         *         (chybí item i po grace period, nebo vypršel rozpočet)
         */
        protected boolean tick(BotContext ctx) {
            if (++ticks > BUDGET_TICKS) {
                return false;
            }
            if (ticks % 4 == 1
                    && placeOwnStation(ctx, station) == PlaceAttempt.NO_ITEM
                    && ticks > NO_ITEM_GRACE_TICKS) {
                return false;
            }
            return true;
        }
    }

    /**
     * Je bot ve stavu vyrazit na výpravu do cizí dimenze? Návratové prahy
     * výprav jsou ~8 HP / 6 hladu – bez odjezdové brány by se bot vracel
     * z Netheru na 7,9 HP a okamžitě „připravený" pochodoval do Endu.
     * Sdílí {@code NetherGoal} a {@code EndTravelGoal}.
     *
     * @param ctx kontext bota
     * @return {@code true} pokud je dost zdravý a najedený na výpravu
     */
    protected static boolean expeditionFit(BotContext ctx) {
        return ctx.clientState().health() >= 14 && ctx.clientState().food() >= 14;
    }

    /**
     * Nedávný útočník z paměti – neutrální mob (rozzuřený enderman, vlk…),
     * který botovi ublížil ({@code ServerEventListener} zapisuje ENEMY) nebo
     * kterého bot sám vyprovokoval. Enderman zuří prakticky trvale, ostatním
     * hněv vyprchá za minutu; vlastní mazlíček se za útočníka nepovažuje
     * nikdy. Sdílí {@code SurviveGoal} (útěk) a {@code CombatGoal} (oplácení).
     *
     * @param bot    bot
     * @param entity podezřelá entita
     * @return {@code true} pokud je entita čerstvým nepřítelem z paměti
     */
    protected static boolean recentAggressor(Bot bot, TrackedEntity entity) {
        if (entity.uuid() == null || entity.isPlayer()) {
            return false;
        }
        long window = entity.isEnderman() ? 600_000 : 60_000;
        long now = System.currentTimeMillis();
        var about = bot.memory().recallAbout(entity.uuid());
        boolean enemy = about.stream().anyMatch(r -> r.kind() == MemoryKind.ENEMY
                && now - r.updatedAt() < window);
        return enemy && about.stream().noneMatch(r -> r.kind() == MemoryKind.PET);
    }

    /**
     * Lidsky čitelný popis toho, co bot v rámci cíle právě dělá a proč.
     *
     * <p>Slouží intent vrstvě: {@code /botalive goal} ho zobrazuje, chat s ním
     * odpovídá na otázku „co děláš?" a boti jím občas komentují své záměry.
     * Vrací {@code null}, když cíl nemá co říct (použije se obecná odpověď).</p>
     *
     * @param bot bot vykonávající cíl
     * @return krátká věta v první osobě, nebo {@code null}
     */
    public String explain(Bot bot) {
        return null;
    }
}
