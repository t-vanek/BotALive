package dev.botalive.core.settlement;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.role.BotRole;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.build.plan.StructureSize;
import dev.botalive.core.build.plan.StructureSizer;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.persistence.BotRepository;
import dev.botalive.core.util.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Vesnice botů – sdílená služba členství, parcel a sousedských vztahů.
 *
 * <p>Boti nestaví každý sám v lese: kdo chce dům a je aspoň trochu
 * společenský, přidá se k vesnici kamarádů (nebo ji založí) a dostane
 * parcelu na prstenci kolem návsi ({@link PlotLayout}), dveřmi ke středu.
 * Samotáři staví dál po svém, jen ne cizí vesnici pod okny.</p>
 *
 * <p>Soužití není idylka: čerstvá zášť vůči sousedovi (ENEMY vzpomínka –
 * krádež, potyčka) může bota vyhnat – opustí vesnici a založí si vlastní
 * kus dál; nejlepší kamarádi se za ním časem stěhují. Rozhodnutí dělá
 * {@link #checkCohesion} nad {@link SocialView} snímkem, který si bot
 * přináší ze své paměti – aliance tak zůstávají emergentní jako u PvP.</p>
 *
 * <p>Vlákna: mutace jsou {@code synchronized} (krátké operace nad malými
 * mapami, volané z tick vláken botů), zápisy do DB jsou write-behind přes
 * {@link BotRepository}. Repozitář smí být {@code null} (unit testy).</p>
 */
public final class SettlementService {

    /** Minimální součet přátelství, aby se bot přidal k cizí vesnici. */
    private static final double JOIN_MIN_AFFINITY = 0.25;
    /** Hodně společenský bot se přidá i k vesnici bez kamarádů (nejsou-li tam nepřátelé). */
    private static final double OPEN_JOIN_SOCIABILITY = 0.55;
    /** Minimální pouto k cizí vesnici, aby se bot stěhoval za kamarádem. */
    private static final double FOLLOW_MIN_TIE = 0.5;
    /** O kolik musí být vazby jinam silnější, aby se usazený bot stěhoval. */
    private static final double FOLLOW_RATIO = 1.25;
    /** Opustit hotový dům a přestavět se do vesnice chce silnější pouto. */
    private static final double RELOCATE_MIN_AFFINITY = 0.5;
    /** Jak dlouho si vesnice pamatuje nepoužitelnou parcelu (skála, jezero…). */
    private static final long PLOT_TOMBSTONE_MS = 24 * 60 * 60 * 1000L;
    /** Strop indexu parcel (7 prstenců, 224 parcel) – dál už vesnice neroste. */
    private static final int MAX_PLOT_INDEX = 224;

    /** Parcela k zástavbě: index, origin půdorysu a orientace dveří (k návsi). */
    public record PlotSlot(int index, BlockPos origin, Cardinal facing) {
    }

    /** Člen vesnice (pohled ven). */
    public record MemberInfo(UUID botId, int plotIndex, BlockPos plotOrigin) {
    }

    /**
     * Vesnice (pohled ven, nemutabilní snímek).
     *
     * @param houses počet dostavěných domů členů (substance stupně)
     * @param tier   odvozený stupeň sídla (osada/vesnice/město)
     * @param mayor  starosta – nejzakotvenější člen ({@code null} bez dat)
     */
    public record SettlementInfo(long id, String name, String world, BlockPos center,
                                 UUID founder, List<MemberInfo> members,
                                 int houses, SettlementTier tier, UUID mayor,
                                 List<ProjectInfo> projects) {
    }

    /**
     * Plán bydlení pro bota, který chce stavět dům.
     *
     * @param kind           druh plánu
     * @param settlementId   vesnice (pro MEMBER/JOIN)
     * @param settlementName jméno vesnice (pro MEMBER/JOIN)
     * @param awayFrom       střed cizí vesnice, od které se má odstěhovat
     *                       (pro FOUND_AWAY)
     */
    public record HomePlan(Kind kind, long settlementId, String settlementName,
                           BlockPos awayFrom) {

        /** Druh plánu bydlení. */
        public enum Kind {
            /** Už je členem – stavět na parcele své vesnice. */
            MEMBER,
            /** Přidat se k vesnici a stavět tam. */
            JOIN,
            /** Založit novou vesnici na místě, které si najde. */
            FOUND,
            /** Založit novou vesnici, ale nejdřív odejít dál od cizí. */
            FOUND_AWAY,
            /** Stavět sám pro sebe (samotář nebo vypnuté vesnice). */
            SOLO
        }

        /** @return plán „stavět sám pro sebe" */
        public static HomePlan solo() {
            return new HomePlan(Kind.SOLO, 0, null, null);
        }

        /** @return plán „založit vesnici na místě, které si bot najde" */
        public static HomePlan found() {
            return new HomePlan(Kind.FOUND, 0, null, null);
        }

        /** @return plán „odejít dál od cizí vesnice a založit vlastní" */
        public static HomePlan foundAway(BlockPos from) {
            return new HomePlan(Kind.FOUND_AWAY, 0, null, from);
        }
    }

    /**
     * Výsledek sousedské úvahy – co bot právě udělal se svým členstvím.
     *
     * @param type           druh akce
     * @param settlementName jméno dotčené vesnice (cílové, u odchodu opuštěné)
     * @param otherName      jméno protistrany (nepřítel u roztržky, kamarád
     *                       u stěhování), může být {@code null}
     */
    public record CohesionAction(Type type, String settlementName, String otherName) {

        /** Druh sousedské akce. */
        public enum Type {
            /** Roztržka: odchod z vesnice kvůli nepříteli, založí vlastní jinde. */
            GRUDGE_LEAVE,
            /** Stěhování za kamarádem do jeho vesnice. */
            FOLLOW_FRIEND,
            /** Samotář s domem se přidal k vesnici kamarádů (přestaví se tam). */
            JOIN_NEARBY,
            /** Založil vesnici kolem svého stávajícího domu. */
            FOUND_AT_HOME
        }

        /** @return má si bot zapomenout starý domov a postavit nový (odvozeno z typu) */
        public boolean rebuild() {
            return type != Type.FOUND_AT_HOME;
        }
    }

    /** Vnitřní stav vesnice – mutace jen pod zámkem služby. */
    private static final class Settlement {
        final long id;
        final String name;
        final String world;
        /** Náves; může se přepočítat, když zakladatelův dům zanikne. */
        BlockPos center;
        final UUID founder;
        final long createdAt;
        final Map<UUID, Member> members = new LinkedHashMap<>();
        /** Nepoužitelné parcely (index → do kdy platí zákaz). */
        final Map<Integer, Long> unusablePlots = new HashMap<>();
        /** Poslední OHLÁŠENÝ stupeň – hlásí se jen růst, jednou. */
        SettlementTier announcedTier = SettlementTier.OSADA;
        /** Společné stavby sídla (studna, později sýpka/tržiště). */
        final Map<ProjectKind, Project> projects = new HashMap<>();
        /** Silniční síť: kdo ji zrovna dusá (jeden naráz), a kdy naposled. */
        UUID roadBuilder;
        long roadClaimedAt;
        long roadsBuiltAt;
        /** Hradby: kdo je zrovna staví (jeden naráz), a kdy naposled. */
        UUID wallBuilder;
        long wallClaimedAt;
        long wallsBuiltAt;
        /**
         * Součet FRIEND vazeb člena k ostatním členům – plní se oportunisticky
         * ze sousedských úvah ({@code checkCohesion}); nejlépe zakotvený člen
         * je STAROSTA (odvozený, nevolený – jako stupeň sídla).
         */
        final Map<UUID, Double> memberTies = new HashMap<>();

        Settlement(long id, String name, String world, BlockPos center,
                   UUID founder, long createdAt) {
            this.id = id;
            this.name = name;
            this.world = world;
            this.center = center;
            this.founder = founder;
            this.createdAt = createdAt;
        }
    }

    /** Členství bota: parcela {@code plotOrigin} může být null (ještě nemá). */
    private record Member(UUID botId, long joinedAt, int plotIndex,
                          BlockPos plotOrigin, Cardinal facing, boolean houseDone) {
    }

    /**
     * Druh společné stavby sídla. Dva rody: <b>infrastruktura</b> (studna,
     * sýpka, tržiště – {@code workshopRole == null}, dělá stupeň sídla) a
     * <b>účelné řemeslné dílny</b>, které se pojí s profesí ({@link #workshopRole})
     * a staví se jen tehdy, když dané řemeslo v sídle někdo dělá (poptávkově,
     * ne dekretem – stejná DNA jako „vesnice si řemeslníky vychovává").
     */
    public enum ProjectKind {
        /**
         * Obecní pole – parcela vyhrazená farmaření.
         *
         * <p>Není to stavba pro {@code CommunalBuildGoal}: nemá blueprint a
         * nikdo ji „nestaví" – zakládá ji {@code FarmGoal} oráním. Do evidence
         * projektů patří kvůli tomu, co z ní plyne zadarmo: parcela se
         * persistuje, blokuje se na ní stavba domu a spadá pod ochranu staveb.
         * Proto se zakládá rovnou jako {@code done} – ať ji stavitelé neberou
         * jako rozdělanou práci ({@code nextProjectKind} ji nikdy nevrací).</p>
         */
        FIELD(null),
        /** Studna na návsi – dělá z osady vesnici. */
        WELL(null),
        /** Sýpka – první část městské infrastruktury. */
        GRANARY(null),
        /** Tržiště – druhá část městské infrastruktury (dělá město). */
        MARKET_STALL(null),
        /** Sklad – společná zásobárna materiálu (od vesnice). */
        WAREHOUSE(null),
        /** Radnice – prestižní stavba města (neposouvá stupeň). */
        TOWN_HALL(null),
        /** Zvonice – prestižní stavba města (neposouvá stupeň). */
        BELL_TOWER(null),
        /** Kostel – prestižní stavba města (neposouvá stupeň). */
        CHURCH(null),
        /** Kovárna – dílna kováře (pec + kovářský stůl). */
        FORGE(BotRole.BLACKSMITH),
        /** Kuchyně – dílna kuchaře (udírna). */
        KITCHEN(BotRole.COOK),
        /** Dílna – ponk a řezák pro stavitele/univerzály. */
        WORKSHOP(BotRole.BUILDER),
        /** Kompostárna – dílna farmáře (composter). */
        COMPOST_HUT(BotRole.FARMER),
        /** Enchantovna – dílna enchantera (enchantovací stůl). */
        ENCHANT_HALL(BotRole.ENCHANTER),
        /** Alchymistická dílna – dílna alchymisty (varný stojan). */
        ALCHEMY_LAB(BotRole.ALCHEMIST),
        /** Šípařská dílna – dílna šípaře (fletching table). */
        FLETCHER_HUT(BotRole.FLETCHER),
        /** Knihovna – dílna knihovníka (řečnický pult). */
        LIBRARY(BotRole.LIBRARIAN),
        /** Nástrojárna – dílna nástrojáře (kovářský stůl). */
        TOOLSMITHY(BotRole.TOOLSMITH),
        /** Zbrojírna – dílna zbrojíře (brus). */
        WEAPONSMITHY(BotRole.WEAPONSMITH),
        /** Brnířská zbrojnice – dílna brníře (tavicí pec). */
        ARMORY(BotRole.ARMORER),
        /** Kartografie – dílna kartografa (kartografický stůl). */
        CARTOGRAPHY(BotRole.CARTOGRAPHER),
        /** Kamenictví – dílna kameníka (řezák). */
        MASONRY(BotRole.MASON),
        /** Koželužna – dílna koželuha (kotlík). */
        TANNERY(BotRole.LEATHERWORKER),
        /** Tkalcovna – dílna pastýře (tkalcovský stav). */
        WEAVERY(BotRole.SHEPHERD);

        private final BotRole workshopRole;

        ProjectKind(BotRole workshopRole) {
            this.workshopRole = workshopRole;
        }

        /** @return {@code true} pro účelné řemeslné dílny (pojí se s profesí) */
        public boolean isWorkshop() {
            return workshopRole != null;
        }

        /** @return profese, které dílna slouží (prázdné pro infrastrukturu) */
        public Optional<BotRole> workshopRole() {
            return Optional.ofNullable(workshopRole);
        }

        /** @return účelné dílny v pevném pořadí priority stavby */
        public static List<ProjectKind> workshops() {
            return WORKSHOPS;
        }

        private static final List<ProjectKind> WORKSHOPS = List.of(
                FORGE, KITCHEN, WORKSHOP, COMPOST_HUT, ENCHANT_HALL, ALCHEMY_LAB,
                FLETCHER_HUT, LIBRARY, TOOLSMITHY, WEAPONSMITHY, ARMORY,
                CARTOGRAPHY, MASONRY, TANNERY, WEAVERY);
    }

    /**
     * Fáze životního cyklu společné stavby: parcela → materiál → stavba →
     * hotovo. Navazuje na {@code done} (autoritu dokončení): {@code DONE ⟺
     * done}. {@code SUPPLY} znamená „sháníme materiál" (sběrači nosí do
     * skladu), {@code BUILD} „staví se" (stráž drží stráž ostřeji).
     */
    public enum ProjectState { SITE, SUPPLY, BUILD, DONE }

    /**
     * Společná stavba (pohled ven).
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     * @param origin       origin stavby (roh půdorysu)
     * @param facing       orientace
     * @param done         dokončeno
     * @param width        šířka stavby (0 = legacy pevná velikost druhu)
     * @param depth        hloubka stavby (0 = legacy)
     * @param wallHeight   výška zdí (0 = legacy)
     */
    public record ProjectInfo(long settlementId, ProjectKind kind, BlockPos origin,
                              Cardinal facing, boolean done,
                              int width, int depth, int wallHeight) {

        /** Persistovaná velikost stavby jako {@link StructureSize} (0 = legacy). */
        public StructureSize size() {
            return new StructureSize(width, depth, wallHeight);
        }
    }

    /** Vnitřní stav projektu; {@code builder} je transientní (restart uvolní). */
    private static final class Project {
        final ProjectKind kind;
        /** Parcela projektu – přesune se, když se staveniště ukáže nepoužitelné. */
        int plotIndex;
        /** Roh půdorysu; výšku si stavitel dolaďuje podle terénu na místě. */
        BlockPos origin;
        Cardinal facing;
        UUID builder;
        /** Kdy si stavitel projekt zamluvil – starý claim expiruje. */
        long claimedAt;
        boolean done;
        /** Fáze životního cyklu (perzistentní); {@code DONE ⟺ done}. */
        ProjectState state;
        /** Rozpis materiálu (BOM) – kolik bloků stavba chce (0 = neznámo). */
        int needed;
        /** Kolik bloků už sběrači nanosili na tuto stavbu. */
        int contributed;
        /** Velikost stavby (0 = legacy pevná velikost druhu). Zvolena z prosperity
         *  při vzniku, persistovaná – idempotentní resume i monotónní růst. */
        int width;
        int depth;
        int wallHeight;

        Project(ProjectKind kind, int plotIndex, BlockPos origin, Cardinal facing,
                boolean done) {
            this(kind, plotIndex, origin, facing, done,
                    done ? ProjectState.DONE : ProjectState.SITE, 0, 0);
        }

        Project(ProjectKind kind, int plotIndex, BlockPos origin, Cardinal facing,
                boolean done, ProjectState state, int needed, int contributed) {
            this(kind, plotIndex, origin, facing, done, state, needed, contributed, 0, 0, 0);
        }

        Project(ProjectKind kind, int plotIndex, BlockPos origin, Cardinal facing,
                boolean done, ProjectState state, int needed, int contributed,
                int width, int depth, int wallHeight) {
            this.kind = kind;
            this.plotIndex = plotIndex;
            this.origin = origin;
            this.facing = facing;
            this.done = done;
            this.state = state;
            this.needed = needed;
            this.contributed = contributed;
            this.width = width;
            this.depth = depth;
            this.wallHeight = wallHeight;
        }
    }

    /** Po jaké době bez dokončení expiruje zamluvení projektu (stavitel zmizel). */
    private static final long PROJECT_CLAIM_TTL_MS = 10 * 60_000L;
    /** Po jaké době bez dokončení expiruje zamluvení dusání cest (stavitel zmizel). */
    private static final long ROAD_CLAIM_TTL_MS = 10 * 60_000L;
    /** Po jaké době bez dokončení expiruje zamluvení stavby hradeb (stavitel zmizel). */
    private static final long WALL_CLAIM_TTL_MS = 10 * 60_000L;

    private final BotAliveConfig.Settlement config;
    private final BotRepository repository;
    private final LongSupplier clock;

    private final Map<Long, Settlement> settlements = new HashMap<>();
    private final Map<UUID, Long> memberIndex = new HashMap<>();
    /** Poslední změna členství bota – tlumí stěhovací kolotoč. */
    private final Map<UUID, Long> lastChangeAt = new HashMap<>();
    /** Boti, kteří si po odchodu mají postavit nový dům (čte BuildHouseGoal). */
    private final Set<UUID> rebuildFlags = ConcurrentHashMap.newKeySet();
    private volatile BotManagerImpl botManager;

    /**
     * @param config     konfigurace vesnic
     * @param repository repozitář ({@code null} = bez persistence, testy)
     */
    public SettlementService(BotAliveConfig.Settlement config, BotRepository repository) {
        this(config, repository, System::currentTimeMillis);
    }

    /**
     * @param config     konfigurace vesnic
     * @param repository repozitář ({@code null} = bez persistence)
     * @param clock      zdroj času (testy)
     */
    SettlementService(BotAliveConfig.Settlement config, BotRepository repository,
                      LongSupplier clock) {
        this.config = config;
        this.repository = repository;
        this.clock = clock;
    }

    /** Připojí manager botů (jména protistran pro chat). */
    public void attach(BotManagerImpl manager) {
        this.botManager = manager;
    }

    /**
     * Načte vesnice a členství z databáze. Volat jednou při startu
     * (synchronně, jako migrace).
     */
    public void load() {
        if (repository == null) {
            return;
        }
        List<BotRepository.SettlementRow> rows = repository.loadSettlements().join();
        List<BotRepository.SettlementMemberRow> memberRows =
                repository.loadSettlementMembers().join();
        Map<UUID, Long> lastSeen = repository.loadLastSeen().join();
        synchronized (this) {
            for (BotRepository.SettlementRow row : rows) {
                Settlement settlement = new Settlement(row.id(), row.name(), row.world(),
                        new BlockPos(row.x(), row.y(), row.z()),
                        row.founder() == null ? null : UUID.fromString(row.founder()),
                        row.createdAt());
                if (row.announcedTier() != null) {
                    settlement.announcedTier = SettlementTier.valueOf(row.announcedTier());
                }
                settlements.put(row.id(), settlement);
            }
            for (BotRepository.SettlementMemberRow row : memberRows) {
                Settlement settlement = settlements.get(row.settlementId());
                if (settlement == null) {
                    continue; // osiřelé členství – vesnice smazána
                }
                BlockPos plot = row.plotX() == null
                        ? null
                        : new BlockPos(row.plotX(), row.plotY(), row.plotZ());
                Cardinal facing = row.plotFacing() == null
                        ? Cardinal.NORTH
                        : Cardinal.valueOf(row.plotFacing());
                settlement.members.put(row.botId(), new Member(row.botId(), row.joinedAt(),
                        row.plotIndex() == null ? -1 : row.plotIndex(), plot, facing,
                        row.houseDone()));
                memberIndex.put(row.botId(), row.settlementId());
                // Brzda stěhovacího kolotoče musí přežít restart – nejlepší
                // dostupný odhad poslední změny je čas vstupu do vesnice.
                lastChangeAt.put(row.botId(), row.joinedAt());
            }
            for (BotRepository.SettlementProjectRow row
                    : repository.loadSettlementProjects().join()) {
                Settlement settlement = settlements.get(row.settlementId());
                if (settlement == null) {
                    continue; // osiřelý projekt – sídlo zaniklo
                }
                ProjectKind kind = parseKind(row.kind());
                if (kind == null) {
                    continue; // neznámý druh (downgrade pluginu) – fyzická stavba zůstává
                }
                settlement.projects.put(kind, new Project(kind, row.plotIndex(),
                        new BlockPos(row.x(), row.y(), row.z()),
                        Cardinal.valueOf(row.facing()), row.done(),
                        parseState(row.state(), row.done()), row.needed(), row.contributed(),
                        row.width(), row.depth(), row.wallHeight()));
                // Parcela projektu není pro domy – trvale.
                settlement.unusablePlots.put(row.plotIndex(), Long.MAX_VALUE);
            }
            reapGhosts(lastSeen);
        }
    }

    /**
     * Vymete „duchy": členy, jejichž bot se dlouho nepřipojil (odstranění
     * bez purge, snížený max-count) nebo v {@code ba_bots} už vůbec není.
     * Parcela se uvolní živým; fyzický dům ve vesnici zůstává.
     */
    private void reapGhosts(Map<UUID, Long> lastSeen) {
        if (config.ghostDays() <= 0) {
            return;
        }
        long cutoff = clock.getAsLong() - config.ghostDays() * 24L * 60 * 60 * 1000;
        List<UUID> ghosts = new ArrayList<>();
        for (UUID botId : memberIndex.keySet()) {
            Long seen = lastSeen.get(botId);
            if (seen == null || seen < cutoff) {
                ghosts.add(botId);
            }
        }
        for (UUID ghost : ghosts) {
            leave(ghost);
            lastChangeAt.remove(ghost);
        }
    }

    // ================================================================ dotazy

    /** @return vesnice bota, pokud je členem */
    public synchronized Optional<SettlementInfo> settlementOf(UUID botId) {
        Long id = memberIndex.get(botId);
        return id == null ? Optional.empty() : Optional.of(info(settlements.get(id)));
    }

    /** @return id vesnice bota, pokud je členem */
    public synchronized OptionalLong settlementIdOf(UUID botId) {
        Long id = memberIndex.get(botId);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    /** @return všechny vesnice (snímek pro příkazy) */
    public synchronized List<SettlementInfo> all() {
        List<SettlementInfo> result = new ArrayList<>();
        for (Settlement settlement : settlements.values()) {
            result.add(info(settlement));
        }
        return result;
    }

    /** @return přidělená parcela bota (má-li ji) */
    public synchronized Optional<PlotSlot> claimedPlot(UUID botId) {
        Member member = member(botId);
        if (member == null || member.plotOrigin() == null) {
            return Optional.empty();
        }
        return Optional.of(new PlotSlot(member.plotIndex(), member.plotOrigin(),
                member.facing()));
    }

    /**
     * Má si bot po přestěhování postavit nový dům? Čtení příznak maže –
     * {@code BuildHouseGoal} si jím resetuje pojistku proti opakované stavbě.
     *
     * @param botId UUID bota
     * @return {@code true} právě jednou po každém přestěhování
     */
    public boolean consumeRebuild(UUID botId) {
        return rebuildFlags.remove(botId);
    }

    /**
     * Nejbližší vesnice v okruhu (vlastní i cizí) – pro chat „kde je vesnice?".
     *
     * @param world  svět
     * @param pos    odkud se ptá
     * @param radius poloměr (bloky)
     * @return nejbližší vesnice v okruhu
     */
    public synchronized Optional<SettlementInfo> nearestSettlement(String world,
                                                                   BlockPos pos, int radius) {
        return nearest(world, pos, radius, null).map(this::info);
    }

    /**
     * Nejbližší střed cizí vesnice v okruhu – „tady se nestaví, tohle je
     * jejich katastr".
     *
     * @param botId  kdo se ptá (vlastní vesnice se nepočítá)
     * @param world  svět
     * @param pos    zamýšlené místo
     * @param radius poloměr (bloky)
     * @return střed nejbližší cizí vesnice v okruhu
     */
    public synchronized Optional<BlockPos> nearestForeignCenter(UUID botId, String world,
                                                                BlockPos pos, int radius) {
        return nearest(world, pos, radius, memberIndex.get(botId)).map(s -> s.center);
    }

    /** Jediná implementace hledání nejbližší vesnice (volat pod zámkem). */
    private Optional<Settlement> nearest(String world, BlockPos pos, int radius,
                                         Long excludeId) {
        double bestSq = (double) radius * radius;
        Settlement best = null;
        for (Settlement settlement : settlements.values()) {
            if (!settlement.world.equals(world)
                    || (excludeId != null && settlement.id == excludeId)) {
                continue;
            }
            double distSq = horizontalSq(settlement.center, pos);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = settlement;
            }
        }
        return Optional.ofNullable(best);
    }

    // ============================================================== bydlení

    /**
     * Kde si má bot postavit dům? Volá {@code BuildHouseGoal} na začátku
     * stavební seance.
     *
     * @param view sociální snímek bota
     * @return plán bydlení
     */
    public synchronized HomePlan planHome(SocialView view) {
        if (!config.enabled()) {
            return HomePlan.solo();
        }
        Long own = memberIndex.get(view.botId());
        if (own != null) {
            Settlement settlement = settlements.get(own);
            // Bot v jiném světě než jeho vesnice staví po svém (členství zůstává).
            if (!settlement.world.equals(view.world())) {
                return HomePlan.solo();
            }
            return new HomePlan(HomePlan.Kind.MEMBER, own, settlement.name, null);
        }
        if (view.sociability() < config.lonerSociability()) {
            return HomePlan.solo();
        }
        Settlement best = bestJoinable(view, view.position(), config.joinRadius(),
                joinThreshold(view));
        if (best != null) {
            return new HomePlan(HomePlan.Kind.JOIN, best.id, best.name, null);
        }
        // Nikam se nepřidá – založí vlastní, ale ne cizí vesnici pod okny.
        Optional<BlockPos> foreign = nearest(view.world(), view.position(),
                config.minVillageDistance(), null).map(s -> s.center);
        return foreign.map(HomePlan::foundAway).orElseGet(HomePlan::found);
    }

    /**
     * Přidá bota do vesnice (bez parcely – tu si zabere při stavbě).
     *
     * @param settlementId vesnice
     * @param view         sociální snímek bota
     * @return {@code false} pokud vesnice zanikla, je plná nebo tam má nepřítele
     */
    public synchronized boolean join(long settlementId, SocialView view) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null || memberIndex.containsKey(view.botId())
                || settlement.members.size() >= config.maxMembers()
                || hasEnemyIn(view, settlement)) {
            return false;
        }
        addMember(settlement, new Member(view.botId(), clock.getAsLong(), -1, null,
                Cardinal.NORTH, false));
        lastChangeAt.put(view.botId(), clock.getAsLong());
        return true;
    }

    /**
     * Návrhy volných parcel od návsi ven (přeskakuje zabrané a nepoužitelné).
     *
     * @param settlementId vesnice
     * @param max          kolik návrhů nejvýš
     * @return parcely k ověření terénu (může být prázdné)
     */
    public synchronized List<PlotSlot> suggestPlots(long settlementId, int max) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return List.of();
        }
        Set<Integer> taken = new java.util.HashSet<>();
        for (Member member : settlement.members.values()) {
            if (member.plotIndex() >= 0 && member.plotOrigin() != null) {
                taken.add(member.plotIndex());
            }
        }
        long now = clock.getAsLong();
        settlement.unusablePlots.values().removeIf(until -> until < now);
        List<PlotSlot> result = new ArrayList<>();
        for (int index = 1; index <= MAX_PLOT_INDEX && result.size() < max; index++) {
            if (taken.contains(index) || settlement.unusablePlots.containsKey(index)) {
                continue;
            }
            BlockPos origin = PlotLayout.plotOrigin(settlement.center, index,
                    config.plotSpacing());
            result.add(new PlotSlot(index, origin,
                    PlotLayout.facingToward(origin, settlement.center)));
        }
        return result;
    }

    /**
     * Zabere parcelu pro bota (po ověření terénu).
     *
     * @param settlementId vesnice
     * @param botId        člen
     * @param slot         parcela z {@link #suggestPlots}
     * @return {@code false} pokud parcelu mezitím zabral jiný bot nebo bot
     *         není členem
     */
    public synchronized boolean claimPlot(long settlementId, UUID botId, PlotSlot slot) {
        Settlement settlement = settlements.get(settlementId);
        Long own = memberIndex.get(botId);
        if (settlement == null || own == null || own != settlementId) {
            return false;
        }
        for (Member member : settlement.members.values()) {
            if (member.plotIndex() == slot.index() && !member.botId().equals(botId)) {
                return false;
            }
        }
        Member member = settlement.members.get(botId);
        addMember(settlement, new Member(botId, member.joinedAt(), slot.index(),
                slot.origin(), slot.facing(), false));
        return true;
    }

    /** Uvolní parcelu bota (terén se ukázal nepoužitelný). */
    public synchronized void releasePlot(UUID botId) {
        Member member = member(botId);
        Long id = memberIndex.get(botId);
        if (member == null || id == null) {
            return;
        }
        Settlement settlement = settlements.get(id);
        boolean founderPlot = member.plotIndex() < 0 && member.plotOrigin() != null;
        addMember(settlement, new Member(botId, member.joinedAt(), -1, null,
                Cardinal.NORTH, false));
        if (founderPlot) {
            // Náves stála na zakladatelově domě a ten je pryč (zatopený,
            // zničený) – přepočítat střed na těžiště domů členů, ať se nové
            // domy nenatáčejí dveřmi k ruině.
            recenter(settlement);
        }
    }

    /** Přesune náves na těžiště parcel členů (má-li kdo domy). */
    private void recenter(Settlement settlement) {
        long sumX = 0;
        long sumY = 0;
        long sumZ = 0;
        int plots = 0;
        int spacing = config.plotSpacing();
        for (Member other : settlement.members.values()) {
            BlockPos o = other.plotOrigin();
            if (o != null) {
                // Střed parcely = roh přichycený na nejbližší uzel mřížky.
                // Footprint-nezávislé: pro domek 4×4 je to roh+2, pro širší
                // generovaný dům jeho skutečný střed (roh leží < půl rozestupu
                // od uzlu).
                sumX += snapToGrid(o.x(), settlement.center.x(), spacing);
                sumY += o.y();
                sumZ += snapToGrid(o.z(), settlement.center.z(), spacing);
                plots++;
            }
        }
        if (plots == 0) {
            return; // není k čemu centrovat – staré souřadnice jsou pořád nejlepší odhad
        }
        settlement.center = new BlockPos((int) (sumX / plots), (int) (sumY / plots),
                (int) (sumZ / plots));
        // Indexy prstenců se posunuly s návsí – tombstony už neplatí.
        settlement.unusablePlots.clear();
        if (repository != null) {
            repository.updateSettlementCenter(settlement.id, settlement.center.x(),
                    settlement.center.y(), settlement.center.z());
        }
    }

    /** Nejbližší uzel mřížky {@code origin + k×spacing} k dané souřadnici. */
    private static int snapToGrid(int coord, int origin, int spacing) {
        if (spacing <= 0) {
            return coord;
        }
        return origin + Math.round((float) (coord - origin) / spacing) * spacing;
    }

    /**
     * Označí parcelu za nepoužitelnou (jezero, skála, cizí stavba) – návrhy
     * ji nějakou dobu přeskakují. Záporné indexy (zakladatelův vlastní dům)
     * se ignorují – návrhy je nikdy nenabízejí.
     *
     * @param settlementId vesnice
     * @param index        index parcely
     */
    public synchronized void markPlotUnusable(long settlementId, int index) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement != null && index >= 1) {
            settlement.unusablePlots.put(index, clock.getAsLong() + PLOT_TOMBSTONE_MS);
        }
    }

    /**
     * Založí novou vesnici. Volá se až s hotovým domem zakladatele
     * ({@code BuildHouseGoal.finishHouse}) – fantomové vesnice bez jediného
     * domu nevznikají.
     *
     * @param view        sociální snímek zakladatele
     * @param center      náves (typicky střed zakladatelova domu)
     * @param founderPlot origin půdorysu zakladatelova domu; {@code null}
     *                    když dům stojí jinde než na budoucí parcele
     *                    (usazení kolem staršího domu s neznámou orientací)
     * @param facing      orientace zakladatelova domu
     * @param nameSeed    seed pro jméno vesnice
     * @return nová vesnice, nebo {@code empty} pokud je moc blízko cizí
     *         (mezitím vyrostla) nebo je bot už členem jinde
     */
    public synchronized Optional<SettlementInfo> foundSettlement(SocialView view,
                                                                 BlockPos center,
                                                                 BlockPos founderPlot,
                                                                 Cardinal facing,
                                                                 long nameSeed) {
        if (!config.enabled() || memberIndex.containsKey(view.botId())) {
            return Optional.empty();
        }
        if (nearest(view.world(), center, config.minVillageDistance(), null).isPresent()) {
            return Optional.empty();
        }
        // U spawnu serveru vesnice nevzniká – je to společný prostor hráčů
        // a náves by ho zastavěla i s prstenci parcel.
        if (isTooCloseToSpawn(view.world(), center)) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        // Náhodné id místo čítače: dvě serverové instance nad sdílenou
        // PostgreSQL by si čítačem přidělily stejná id (write-behind kolizi
        // jen zaloguje a vesnice by se po restartu tiše ztratila).
        long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        while (settlements.containsKey(id)) {
            id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        }
        Settlement settlement = new Settlement(id, uniqueName(view.botName(), nameSeed),
                view.world(), center, view.botId(), now);
        settlements.put(settlement.id, settlement);
        if (repository != null) {
            repository.insertSettlement(settlement.id, settlement.name, settlement.world,
                    center.x(), center.y(), center.z(), view.botId().toString(), now);
        }
        addMember(settlement, new Member(view.botId(), now, -1, founderPlot, facing, false));
        lastChangeAt.put(view.botId(), now);
        return Optional.of(info(settlement));
    }

    /**
     * Zaznamená dostavěný dům člena – substanci, ze které se odvozuje stupeň
     * sídla. Volá {@code BuildHouseGoal.finishHouse} (pro zakladatele
     * i členy s parcelou). Idempotentní.
     *
     * @param botId stavebník
     * @return nově dosažený stupeň, pokud dům sídlo právě povýšil a povýšení
     *         ještě nebylo ohlášeno (volající ohlásí v chatu)
     */
    public synchronized Optional<SettlementTier> houseFinished(UUID botId) {
        Long id = memberIndex.get(botId);
        Settlement settlement = id == null ? null : settlements.get(id);
        if (settlement == null) {
            return Optional.empty();
        }
        Member member = settlement.members.get(botId);
        if (member == null || member.houseDone()) {
            return Optional.empty();
        }
        addMember(settlement, new Member(member.botId(), member.joinedAt(),
                member.plotIndex(), member.plotOrigin(), member.facing(), true));
        return maybeAnnounceTier(settlement);
    }

    /** Odvozený stupeň sídla z dostavěných domů a společných staveb. */
    private SettlementTier tierOf(Settlement settlement) {
        // Město = sýpka + tržiště (městská infrastruktura), ne jen počet domů.
        boolean townInfra = projectDone(settlement, ProjectKind.GRANARY)
                && projectDone(settlement, ProjectKind.MARKET_STALL);
        return SettlementTier.of(houses(settlement),
                projectDone(settlement, ProjectKind.WELL), townInfra);
    }

    /** @return {@code true} pokud má sídlo dokončenou danou společnou stavbu */
    private static boolean projectDone(Settlement settlement, ProjectKind kind) {
        Project project = settlement.projects.get(kind);
        return project != null && project.done;
    }

    /** Tolerantní parse druhu projektu z DB – {@code null} pro neznámý (downgrade). */
    private static ProjectKind parseKind(String kind) {
        try {
            return ProjectKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    // ==================================================== společné stavby

    /**
     * Společná stavba, kterou sídlo potřebuje k dalšímu růstu a kterou si
     * tazatel smí vzít (volná, nebo už jeho). Projekt se při prvním dotazu
     * založí: vybere se první volná parcela (prstenec 1+), trvale se
     * zablokuje pro domy a persistuje.
     *
     * @param botId člen sídla, který se ptá
     * @return projekt k zamluvení/stavbě, nebo empty (nic netřeba / staví
     *         někdo jiný / bot není členem)
     */
    public synchronized Optional<ProjectInfo> neededProject(UUID botId) {
        Long id = memberIndex.get(botId);
        Settlement settlement = id == null ? null : settlements.get(id);
        if (settlement == null) {
            return Optional.empty();
        }
        ProjectKind needed = nextProjectKind(settlement, this::currentRole);
        if (needed == null) {
            return Optional.empty();
        }
        Project project = settlement.projects.get(needed);
        if (project == null) {
            Integer index = freePlotIndex(settlement);
            if (index == null) {
                return Optional.empty(); // plno – bez místa se nestaví
            }
            BlockPos origin = PlotLayout.plotOrigin(settlement.center, index,
                    config.plotSpacing());
            project = new Project(needed, index, origin,
                    PlotLayout.facingToward(origin, settlement.center), false);
            // Velikost z prosperity sídla, zvolená JEDNOU při vzniku a persistovaná
            // (idempotentní resume i pozdější růst). 0 = legacy pevná velikost druhu.
            StructureSize sz = communalSize(needed, tierOf(settlement));
            project.width = sz.width();
            project.depth = sz.depth();
            project.wallHeight = sz.wallHeight();
            settlement.projects.put(needed, project);
            settlement.unusablePlots.put(index, Long.MAX_VALUE);
            persistProject(settlement, project);
        }
        if (activeBuilder(project) != null && !botId.equals(project.builder)) {
            return Optional.empty(); // už na tom dělá někdo jiný
        }
        return Optional.of(projectInfo(settlement, project));
    }

    /**
     * Právě rozestavěná společná stavba v sídle bota – staveniště, kam
     * sběrač nosí materiál a u kterého drží hlídač stráž. Vrací projekt
     * s aktivním stavitelem, který ještě není hotový; při více rozestavěných
     * bere naposled zamluvený (nejčerstvější staveniště). Na rozdíl od
     * {@link #neededProject} nezakládá projekt ani nerezervuje parcelu – je
     * to čistý pohled pro pomocníky (sběrač/hlídač), ne pro stavitele. Když
     * staviteli vyprší zamluvení ({@link #PROJECT_CLAIM_TTL_MS}), staveniště
     * z pohledu zmizí (nemá cenu nosit materiál na opuštěnou stavbu).
     *
     * @param botId člen sídla, který se ptá (sběrač/hlídač)
     * @return rozestavěná stavba, nebo prázdné (nic se právě nestaví /
     *         bot není členem sídla)
     */
    public synchronized Optional<ProjectInfo> activeProject(UUID botId) {
        Long id = memberIndex.get(botId);
        Settlement settlement = id == null ? null : settlements.get(id);
        Project active = activeProjectOf(settlement);
        return active == null ? Optional.empty() : Optional.of(projectInfo(settlement, active));
    }

    /** Nejčerstvěji zamluvená rozestavěná stavba sídla (i po expiraci claimu). */
    private Project activeProjectOf(Settlement settlement) {
        if (settlement == null) {
            return null;
        }
        Project active = null;
        for (Project project : settlement.projects.values()) {
            if (project.done || activeBuilder(project) == null) {
                continue; // hotové nebo bez stavitele (i po expiraci claimu)
            }
            if (active == null || project.claimedAt > active.claimedAt) {
                active = project; // nejčerstvější rozestavěné staveniště
            }
        }
        return active;
    }

    /**
     * Fáze životního cyklu stavby daného druhu (SITE→SUPPLY→BUILD→DONE).
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     * @return stav, nebo prázdné (projekt neexistuje)
     */
    public synchronized Optional<ProjectState> projectState(long settlementId, ProjectKind kind) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        return project == null ? Optional.empty() : Optional.of(project.state);
    }

    /**
     * Zahájí shánění materiálu (SITE→SUPPLY) a zapíše rozpis (BOM) – kolik
     * bloků stavba chce. Volá stavitel po zamluvení, když zná blueprint;
     * idempotentní, hotový projekt nevrací zpět. Rozpis pak čtou sběrači
     * přes {@link #contributionNeeds}.
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     * @param needed       počet bloků k dostavbě (BOM)
     */
    public synchronized void beginSupply(long settlementId, ProjectKind kind, int needed) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project == null || project.done) {
            return;
        }
        project.needed = Math.max(0, needed);
        if (project.state == ProjectState.SITE) {
            project.state = ProjectState.SUPPLY;
        }
        persistProject(settlement, project);
    }

    /**
     * Přejde do stavby (→BUILD). Volá stavitel při zahájení seance pokládky.
     * Idempotentní; hotový projekt nevrací zpět.
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     */
    public synchronized void beginBuild(long settlementId, ProjectKind kind) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project == null || project.done || project.state == ProjectState.BUILD) {
            return;
        }
        project.state = ProjectState.BUILD;
        persistProject(settlement, project);
    }

    /**
     * Domy STOJÍCÍ MIMO parcely sídel (samotáři, domy postavené před vstupem
     * do vesnice) – svět → originy. Bez nich by ochrana kryla jen menšinu
     * zástavby: naměřeno 74 domovů proti 30 parcelám, tedy přes 40 nechráněných
     * domů, a přesně pod ně se boti prokopali.
     */
    private final Map<String, Map<BlockPos, UUID>> standaloneHouses = new HashMap<>();

    /** Bod spawnu serveru na svět – kolem něj se nestaví. */
    private final Map<String, BlockPos> worldSpawns = new HashMap<>();

    /**
     * Zaeviduje dům, který nestojí na parcele sídla, aby ho krylo totéž
     * chráněné pásmo. Volá se po dostavbě i při načtení paměti HOME po startu.
     *
     * @param world  název světa
     * @param origin roh domu
     * @param owner  majitel (dostane na vlastní dům stejnou výjimku jako na
     *               parcelu; {@code null} = neznámý)
     */
    public synchronized void registerHouse(String world, BlockPos origin, UUID owner) {
        if (world == null || origin == null) {
            return;
        }
        standaloneHouses.computeIfAbsent(world, key -> new HashMap<>()).put(origin, owner);
    }

    /**
     * Zapamatuje si spawn světa (volá se z hlavního vlákna při startu) – cíle
     * běží na jiných vláknech a Bukkit se jich ptát nemají.
     *
     * @param world název světa
     * @param spawn bod spawnu
     */
    public synchronized void setWorldSpawn(String world, BlockPos spawn) {
        if (world != null && spawn != null) {
            worldSpawns.put(world, spawn);
        }
    }

    /**
     * Je pozice tak blízko spawnu serveru, že se tam nesmí stavět?
     *
     * <p>Spawn je společný prostor hráčů – domy botů (a celé vesnice) tam
     * nemají co dělat. Poloměr drží {@code settlement.spawn-keepout};
     * 0 = ochrana vypnutá.</p>
     *
     * @param world název světa
     * @param pos   zamýšlené staveniště
     * @return {@code true} když je staveniště v ochranném pásmu spawnu
     */
    public synchronized boolean isTooCloseToSpawn(String world, BlockPos pos) {
        int keepout = config.spawnKeepout();
        if (keepout <= 0 || world == null || pos == null) {
            return false;
        }
        BlockPos spawn = worldSpawns.get(world);
        return spawn != null && horizontalDistance(spawn, pos) < keepout;
    }

    /**
     * Parcela obecního pole pro bota – existující, nebo nově vyhrazená.
     *
     * <p>Pole se tím stává řádnou součástí sídla: má vlastní parcelu v mřížce
     * (takže na něm nikdo nepostaví dům), přežije restart a kryje ho ochrana
     * staveb. Bez toho si farmář oral, kde zrovna stál – včetně podlahy
     * vlastního domu, protože ta je uvnitř přírodní tráva.</p>
     *
     * @param botId farmář
     * @param size  hrana pole (bloky)
     * @return roh pole, nebo prázdné (bot není v sídle / v katastru je plno)
     */
    public synchronized Optional<BlockPos> fieldSite(UUID botId, int size) {
        Long id = memberIndex.get(botId);
        Settlement settlement = id == null ? null : settlements.get(id);
        if (settlement == null) {
            return Optional.empty();
        }
        Project existing = settlement.projects.get(ProjectKind.FIELD);
        if (existing != null) {
            return Optional.of(existing.origin);
        }
        return reserveSite(id, size, size).map(slot -> {
            // done=true záměrně: parcela je rezervace, ne rozdělaná stavba –
            // stavitelé ji tak neberou jako práci a SupplyGoal jako aktivní BOM.
            Project field = new Project(ProjectKind.FIELD, slot.index(), slot.origin(),
                    slot.facing(), true);
            settlement.projects.put(ProjectKind.FIELD, field);
            persistProject(settlement, field);
            return slot.origin();
        });
    }

    /** Spodní mez vodorovného poloměru ochrany (legacy domek 4×4). */
    private static final int GUARD_RADIUS = 8;
    /** Kolik bloků NAD úrovní stavby se ještě chrání (patra, střecha). */
    private static final int GUARD_HEIGHT = 20;
    /** Hrubý filtr: sídla dál než tohle od pozice se neprocházejí. */
    private static final int GUARD_SETTLEMENT_REACH = 256;

    /**
     * Je pozice uvnitř chráněného objemu nějaké stavby (parcela člena nebo
     * společná stavba), a tedy zapovězená těžbě?
     *
     * <p>Chrání se hranol kolem originu stavby: vodorovně {@link #GUARD_RADIUS},
     * nahoru {@link #GUARD_HEIGHT} (patra a střecha) a dolů
     * {@code settlement.protect-depth} – právě ta spodní část je ochrana proti
     * <b>poddolování</b>. Hlouběji pod sídlem se kopat smí; zakázané je
     * podhrabat základy. Legitimní bourání (stavba, oprava vlastního domu,
     * sklizeň) sem nechodí – guard se ptá jen těžba.</p>
     *
     * @param world název světa
     * @param pos   zkoumaný blok
     * @return {@code true} když se blok nesmí vytěžit
     */
    public synchronized boolean isStructureProtected(String world, BlockPos pos) {
        return isStructureProtected(world, pos, null);
    }

    /**
     * Totéž s výjimkou pro jednoho bota: <b>vlastní</b> parcela a stavba, kterou
     * má zamluvenou, se mu nechrání.
     *
     * <p>Bez té výjimky si stavitel neprokope cestu na vlastní staveniště –
     * ochrana ho uvězní před jeho vlastním domem (naměřeno živě: 8 watchdog
     * resetů u jednoho bota s cílem {@code house}). Na těžbu se výjimka
     * NEpoužívá: podhrabat si vlastní dům je pořád nesmysl.</p>
     *
     * @param world     název světa
     * @param pos       zkoumaný blok
     * @param exemptBot bot, jemuž se vlastní stavby nechrání ({@code null} = nikdo)
     * @return {@code true} když se blok nesmí vytěžit
     */
    public synchronized boolean isStructureProtected(String world, BlockPos pos, UUID exemptBot) {
        if (!config.protectStructures() || world == null || pos == null) {
            return false;
        }
        // Domy mimo sídla (samotáři) – ty žádné sídlo neeviduje.
        for (var house : standaloneHouses.getOrDefault(world, Map.of()).entrySet()) {
            if (!java.util.Objects.equals(house.getValue(), exemptBot)
                    && guards(house.getKey(), pos)) {
                return true;
            }
        }
        for (Settlement settlement : settlements.values()) {
            if (!world.equals(settlement.world)
                    || horizontalDistance(settlement.center, pos) > GUARD_SETTLEMENT_REACH) {
                continue; // jiný svět / daleko – celé sídlo přeskočit
            }
            for (Member member : settlement.members.values()) {
                if (member.botId().equals(exemptBot)) {
                    continue; // na vlastní parcelu si bot smí (staví tam)
                }
                if (member.plotOrigin() != null && guards(member.plotOrigin(), pos)) {
                    return true;
                }
            }
            for (Project project : settlement.projects.values()) {
                if (project.builder != null && project.builder.equals(exemptBot)) {
                    continue; // vlastní zamluvená společná stavba
                }
                if (guards(project.origin, pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Leží {@code pos} v chráněném hranolu kolem {@code origin}? Poloměr roste
     * s rozestupem parcel, aby pokryl i nejširší dům/sál, který se v sídle staví
     * (origin je roh, stavba se rozpíná do kladného směru).
     */
    private boolean guards(BlockPos origin, BlockPos pos) {
        int radius = guardRadius();
        return Math.abs(pos.x() - origin.x()) <= radius
                && Math.abs(pos.z() - origin.z()) <= radius
                && pos.y() >= origin.y() - config.protectDepth()
                && pos.y() <= origin.y() + GUARD_HEIGHT;
    }

    /**
     * Vodorovný poloměr ochrany: aspoň {@link #GUARD_RADIUS} (legacy 4×4), ale
     * nejméně tak velký, aby pokryl nejširší stavbu, která se vejde do parcely.
     * Dům i sál mají půdorys menší než rozestup parcel (jinak by lezly k sousedovi),
     * takže {@code plotSpacing − 3} je pokryje i s malou rezervou; odvození
     * z konfigurace drží ochranu korektní, i když se strop velikosti zvedne.
     */
    private int guardRadius() {
        return Math.max(GUARD_RADIUS, config.plotSpacing() - 3);
    }

    /** Vodorovná (Chebyshevova) vzdálenost – hrubý filtr, levnější než odmocnina. */
    private static int horizontalDistance(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
    }

    /**
     * Nejbližší sídlo, jehož zástavbě by se měl důl vyhnout.
     *
     * @param world název světa
     * @param pos   zkoumaná pozice
     * @return vodorovná vzdálenost k nejbližší návsi, nebo prázdné (žádné sídlo)
     */
    public synchronized OptionalInt distanceToNearestSettlement(String world, BlockPos pos) {
        int best = Integer.MAX_VALUE;
        for (Settlement settlement : settlements.values()) {
            if (world == null || !world.equals(settlement.world)) {
                continue;
            }
            best = Math.min(best, horizontalDistance(settlement.center, pos));
        }
        return best == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /**
     * Sběrač přispěl {@code blocks} bloky na aktivní stavbu sídla; zvýší
     * evidenci nasbíraného a vrátí, kolik ještě chybí (BOM − nasbíráno, ≥0).
     * Bez aktivní stavby (nebo bez známého BOM) vrací 0 – sběrač si to bere
     * jako „dost".
     *
     * @param settlementId sídlo
     * @param blocks       počet přispěných bloků
     * @return zbývající potřeba bloků (0 = dost / žádná aktivní stavba)
     */
    public synchronized int contribute(long settlementId, int blocks) {
        Settlement settlement = settlements.get(settlementId);
        Project project = activeProjectOf(settlement);
        if (project == null) {
            return 0;
        }
        project.contributed = Math.max(0, project.contributed + blocks);
        persistProject(settlement, project);
        return project.needed <= 0 ? 0 : Math.max(0, project.needed - project.contributed);
    }

    /**
     * Kolik bloků ještě chybí aktivní stavbě sídla (BOM − nasbíráno). Prázdné,
     * když se nic nestaví nebo BOM není znám ({@code needed == 0}) – sběrač to
     * pak bere jako „nos, dokud máš přebytek" (fallback na dřívější chování).
     *
     * @param settlementId sídlo
     * @return zbývající potřeba bloků, nebo prázdné
     */
    public synchronized OptionalInt contributionNeeds(long settlementId) {
        Settlement settlement = settlements.get(settlementId);
        Project project = activeProjectOf(settlement);
        if (project == null || project.needed <= 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Math.max(0, project.needed - project.contributed));
    }

    /**
     * @param settlement sídlo
     * @param roleOf     zdroj profese člena (pro poptávku po dílnách)
     * @return další společná stavba, kterou sídlo pro růst potřebuje.
     *         Nejdřív <b>infrastruktura</b> (posouvá stupeň): studna dělá
     *         z osady vesnici, sýpka a tržiště (od 8 domů) dělají město.
     *         Pak <b>účelné dílny</b> (od vesnice, tj. po studni): pro každé
     *         řemeslo, které v sídle někdo dělá a ještě nemá dílnu, v pevném
     *         pořadí priority. {@code null} = nic netřeba.
     */
    private ProjectKind nextProjectKind(Settlement settlement,
                                        java.util.function.Function<UUID, BotRole> roleOf) {
        int houses = houses(settlement);
        boolean wellDone = projectDone(settlement, ProjectKind.WELL);
        if (houses >= SettlementTier.VILLAGE_HOUSES && !wellDone) {
            return ProjectKind.WELL;
        }
        if (houses >= SettlementTier.TOWN_HOUSES && wellDone
                && !projectDone(settlement, ProjectKind.GRANARY)) {
            return ProjectKind.GRANARY;
        }
        // Tržiště je druhá půlka městské infrastruktury – po sýpce dělá z vesnice město.
        if (houses >= SettlementTier.TOWN_HOUSES
                && projectDone(settlement, ProjectKind.GRANARY)
                && !projectDone(settlement, ProjectKind.MARKET_STALL)) {
            return ProjectKind.MARKET_STALL;
        }
        // Účelné dílny: od vesnice (studna hotová), jen pro řemesla, která tu
        // někdo dělá – nedostavěná infrastruktura (sýpka/tržiště) má přednost.
        if (wellDone) {
            for (ProjectKind workshop : ProjectKind.workshops()) {
                if (!projectDone(settlement, workshop)
                        && practisesCraft(settlement, roleOf, workshop.workshopRole().orElseThrow())) {
                    return workshop;
                }
            }
        }
        // Společný sklad na materiál: od vesnice, po dílnách (řemesla mají
        // přednost, ale zásobárnu si vesnice nakonec postaví taky).
        if (wellDone && !projectDone(settlement, ProjectKind.WAREHOUSE)) {
            return ProjectKind.WAREHOUSE;
        }
        // Prestižní stavby, až když je sídlo už město (neposouvají stupeň):
        // nejdřív radnice, pak zvonice, pak kostel.
        if (tierOf(settlement) == SettlementTier.MESTO
                && !projectDone(settlement, ProjectKind.TOWN_HALL)) {
            return ProjectKind.TOWN_HALL;
        }
        if (tierOf(settlement) == SettlementTier.MESTO
                && !projectDone(settlement, ProjectKind.BELL_TOWER)) {
            return ProjectKind.BELL_TOWER;
        }
        if (tierOf(settlement) == SettlementTier.MESTO
                && !projectDone(settlement, ProjectKind.CHURCH)) {
            return ProjectKind.CHURCH;
        }
        return null;
    }

    /** Dělá aspoň jeden člen sídla dané řemeslo? (poptávka po dílně). */
    private static boolean practisesCraft(Settlement settlement,
                                          java.util.function.Function<UUID, BotRole> roleOf,
                                          BotRole craft) {
        for (Member member : settlement.members.values()) {
            if (roleOf.apply(member.botId()) == craft) {
                return true;
            }
        }
        return false;
    }

    /** Aktuální profese bota z manageru ({@code NONE} bez manageru/bota). */
    private BotRole currentRole(UUID botId) {
        BotManagerImpl manager = botManager;
        return manager == null
                ? BotRole.NONE
                : manager.byId(botId).map(Bot::role).orElse(BotRole.NONE);
    }

    /**
     * Testovací pohled na rozhodnutí „další stavba" s injektovaným zdrojem
     * rolí – bez mutace (nezakládá projekt ani nerezervuje parcelu).
     *
     * @param settlementId sídlo
     * @param roleOf       zdroj profese člena
     * @return další potřebná stavba, nebo prázdné
     */
    synchronized Optional<ProjectKind> nextProject(long settlementId,
            java.util.function.Function<UUID, BotRole> roleOf) {
        Settlement settlement = settlements.get(settlementId);
        return settlement == null
                ? Optional.empty()
                : Optional.ofNullable(nextProjectKind(settlement, roleOf));
    }

    /**
     * @param settlementId sídlo
     * @return origin dokončené sýpky – pro normy sdílení jídla (fáze C)
     */
    public synchronized Optional<BlockPos> granaryOf(long settlementId) {
        Settlement settlement = settlements.get(settlementId);
        Project granary = settlement == null
                ? null : settlement.projects.get(ProjectKind.GRANARY);
        return granary != null && granary.done
                ? Optional.of(granary.origin) : Optional.empty();
    }

    /**
     * Dokončená společná stavba daného druhu (origin + orientace) – aby si cíl
     * dopočítal pozici truhly z geometrie blueprintu (sýpka, sklad).
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     * @return projekt, pokud je dokončený; jinak prázdné
     */
    public synchronized Optional<ProjectInfo> doneProject(long settlementId, ProjectKind kind) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        return project != null && project.done
                ? Optional.of(projectInfo(settlement, project)) : Optional.empty();
    }

    /** @return stavitel projektu, nebo {@code null} po expiraci zamluvení */
    private UUID activeBuilder(Project project) {
        if (project.builder != null
                && clock.getAsLong() - project.claimedAt > PROJECT_CLAIM_TTL_MS) {
            project.builder = null; // stavitel zmizel – projekt je zase volný
        }
        return project.builder;
    }

    /**
     * Zamluví projekt staviteli – první bere (vzor trhu).
     *
     * @return {@code false} když projekt mezitím vzal jiný bot nebo neexistuje
     */
    public synchronized boolean claimProject(long settlementId, ProjectKind kind, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project == null || project.done
                || (activeBuilder(project) != null && !botId.equals(project.builder))) {
            return false;
        }
        project.builder = botId;
        project.claimedAt = clock.getAsLong();
        return true;
    }

    /**
     * Doladěná výška staveniště – stavitel na místě srovnal origin podle
     * terénu (katastr rozdává parcely s Y návsi, na svahu je to vedle).
     * Evidence musí sedět: po restartu se ke stavbě navazuje z ní a truhlu
     * sýpky/skladu si cíle dopočítávají z originu.
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     * @param origin       skutečný roh půdorysu
     */
    public synchronized void updateProjectOrigin(long settlementId, ProjectKind kind,
                                                 BlockPos origin) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project == null || project.origin.equals(origin)) {
            return;
        }
        project.origin = origin;
        persistProject(settlement, project);
    }

    /**
     * Staveniště projektu se ukázalo nepoužitelné (sráz, jezero, skála) –
     * přesunout stavbu na jinou parcelu. Bez toho by sídlo na jedné špatné
     * parcele uvázlo napořád: {@code neededProject} vrací pořád týž projekt
     * a stavitelé se na něm střídají donekonečna.
     *
     * @param settlementId sídlo
     * @param kind         druh stavby
     * @return {@code true} když se projekt přesunul na volnou parcelu
     */
    public synchronized boolean relocateProject(long settlementId, ProjectKind kind) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project == null || project.done) {
            return false;
        }
        // Stará parcela přestává být rezervovaná projektem a dostane BĚŽNÝ
        // tombstone (jako u domů) – ne trvalý. Rezervace projektu je
        // Long.MAX_VALUE; nechat ji tu by parcelu navždy sebralo i domům,
        // a to na jediné posouzení terénu (které navíc mohlo vidět jen kus
        // nenačteného okolí). Vesnice pod horou by si takhle proškrtala
        // celý prstenec.
        settlement.unusablePlots.put(project.plotIndex,
                clock.getAsLong() + PLOT_TOMBSTONE_MS);
        Integer index = freePlotIndex(settlement);
        if (index == null) {
            return false; // plno – zkusí se zas, až se něco uvolní
        }
        BlockPos origin = PlotLayout.plotOrigin(settlement.center, index,
                config.plotSpacing());
        project.plotIndex = index;
        project.origin = origin;
        project.facing = PlotLayout.facingToward(origin, settlement.center);
        project.builder = null;
        settlement.unusablePlots.put(index, Long.MAX_VALUE);
        persistProject(settlement, project);
        return true;
    }

    /** Stavitel to vzdal (přerušení cíle) – projekt se uvolní dalšímu. */
    public synchronized void releaseProject(long settlementId, ProjectKind kind, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project != null && botId.equals(project.builder)) {
            project.builder = null;
        }
    }

    /**
     * Dokončená společná stavba – substance sídla.
     *
     * @return nově dosažený stupeň k ohlášení (jako {@link #houseFinished})
     */
    public synchronized Optional<SettlementTier> projectFinished(long settlementId,
                                                                 ProjectKind kind) {
        Settlement settlement = settlements.get(settlementId);
        Project project = settlement == null ? null : settlement.projects.get(kind);
        if (project == null || project.done) {
            return Optional.empty();
        }
        project.done = true;
        project.state = ProjectState.DONE;
        project.builder = null;
        persistProject(settlement, project);
        return maybeAnnounceTier(settlement);
    }

    // ================================================================ cesty

    /**
     * Smí bot právě teď udusat silniční síť sídla? Levná brána utility:
     * síť se nedusala nedávno a nikdo jiný ji zrovna nezamluvil (starý claim
     * expiruje). Fyzická cesta je autorita, plán je idempotentní – proto se
     * stav sítě nepersistuje (restart ji jednou přepočítá, většinou naprázdno).
     *
     * @param settlementId sídlo
     * @param minIntervalMs minimální odstup od poslední seance (ms)
     * @param botId        tazatel (jeho vlastní zamluvení nepřekáží)
     * @return {@code true} když je síť volná ke stavbě
     */
    public synchronized boolean roadsDue(long settlementId, long minIntervalMs, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return false;
        }
        if (clock.getAsLong() - settlement.roadsBuiltAt < minIntervalMs) {
            return false;
        }
        UUID builder = activeRoadBuilder(settlement);
        return builder == null || builder.equals(botId);
    }

    /**
     * Zamluví dusání silniční sítě staviteli – první bere (vzor projektů).
     *
     * @return {@code false} když síť mezitím zamluvil jiný bot nebo sídlo zaniklo
     */
    public synchronized boolean claimRoads(long settlementId, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return false;
        }
        UUID builder = activeRoadBuilder(settlement);
        if (builder != null && !builder.equals(botId)) {
            return false;
        }
        settlement.roadBuilder = botId;
        settlement.roadClaimedAt = clock.getAsLong();
        return true;
    }

    /** Stavitel to vzdal (přerušení cíle) – síť se uvolní dalšímu. */
    public synchronized void releaseRoads(long settlementId, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement != null && botId.equals(settlement.roadBuilder)) {
            settlement.roadBuilder = null;
        }
    }

    /** Seance dusání doběhla – nastaví odstup a uvolní síť dalšímu. */
    public synchronized void roadsBuilt(long settlementId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement != null) {
            settlement.roadsBuiltAt = clock.getAsLong();
            settlement.roadBuilder = null;
        }
    }

    /** @return stavitel sítě, nebo {@code null} po expiraci zamluvení */
    private UUID activeRoadBuilder(Settlement settlement) {
        if (settlement.roadBuilder != null
                && clock.getAsLong() - settlement.roadClaimedAt > ROAD_CLAIM_TTL_MS) {
            settlement.roadBuilder = null; // stavitel zmizel – síť je zase volná
        }
        return settlement.roadBuilder;
    }

    // =============================================================== hradby

    /**
     * Smí bot právě teď stavět hradby sídla? Levná brána utility (vzor cest):
     * nestavěly se nedávno a nikdo jiný je zrovna nezamluvil. Fyzická hradba je
     * autorita, plán je idempotentní – stav se proto nepersistuje.
     *
     * @param settlementId  sídlo
     * @param minIntervalMs minimální odstup od poslední seance (ms)
     * @param botId         tazatel (jeho vlastní zamluvení nepřekáží)
     * @return {@code true} když jsou hradby volné ke stavbě
     */
    public synchronized boolean wallsDue(long settlementId, long minIntervalMs, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return false;
        }
        if (clock.getAsLong() - settlement.wallsBuiltAt < minIntervalMs) {
            return false;
        }
        UUID builder = activeWallBuilder(settlement);
        return builder == null || builder.equals(botId);
    }

    /**
     * Zamluví stavbu hradeb staviteli – první bere (vzor cest/projektů).
     *
     * @return {@code false} když hradby mezitím zamluvil jiný bot nebo sídlo zaniklo
     */
    public synchronized boolean claimWalls(long settlementId, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return false;
        }
        UUID builder = activeWallBuilder(settlement);
        if (builder != null && !builder.equals(botId)) {
            return false;
        }
        settlement.wallBuilder = botId;
        settlement.wallClaimedAt = clock.getAsLong();
        return true;
    }

    /** Stavitel to vzdal (přerušení cíle) – hradby se uvolní dalšímu. */
    public synchronized void releaseWalls(long settlementId, UUID botId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement != null && botId.equals(settlement.wallBuilder)) {
            settlement.wallBuilder = null;
        }
    }

    /** Seance stavby hradeb doběhla – nastaví odstup a uvolní dalšímu. */
    public synchronized void wallsBuilt(long settlementId) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement != null) {
            settlement.wallsBuiltAt = clock.getAsLong();
            settlement.wallBuilder = null;
        }
    }

    /** @return stavitel hradeb, nebo {@code null} po expiraci zamluvení */
    private UUID activeWallBuilder(Settlement settlement) {
        if (settlement.wallBuilder != null
                && clock.getAsLong() - settlement.wallClaimedAt > WALL_CLAIM_TTL_MS) {
            settlement.wallBuilder = null; // stavitel zmizel – hradby jsou zase volné
        }
        return settlement.wallBuilder;
    }

    /** Řemesla, bez kterých sídlo kulhá – v pořadí naléhavosti. */
    private static final List<dev.botalive.api.role.BotRole> CORE_ROLES = List.of(
            dev.botalive.api.role.BotRole.FARMER,
            dev.botalive.api.role.BotRole.HUNTER,
            dev.botalive.api.role.BotRole.BLACKSMITH,
            dev.botalive.api.role.BotRole.BUILDER);

    /**
     * První klíčové řemeslo, které v sídle nikdo nedělá – nový univerzál si
     * ho při vstupu může vzít (role je zaměření, ne klec: nudge v životním
     * zlomu, žádné přeřazování zavedených členů).
     *
     * @param settlementId sídlo
     * @return chybějící řemeslo, nebo empty (vše pokryto / sídlo neexistuje)
     */
    public Optional<dev.botalive.api.role.BotRole> missingCoreRole(long settlementId) {
        BotManagerImpl manager = botManager;
        if (manager == null) {
            return Optional.empty();
        }
        return missingCoreRole(settlementId,
                id -> manager.byId(id).map(dev.botalive.api.bot.Bot::role)
                        .orElse(dev.botalive.api.role.BotRole.NONE));
    }

    /** Testovatelná varianta s injektovaným zdrojem rolí. */
    synchronized Optional<dev.botalive.api.role.BotRole> missingCoreRole(
            long settlementId,
            java.util.function.Function<UUID, dev.botalive.api.role.BotRole> roleOf) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return Optional.empty();
        }
        for (dev.botalive.api.role.BotRole needed : CORE_ROLES) {
            boolean covered = false;
            for (Member member : settlement.members.values()) {
                if (roleOf.apply(member.botId()) == needed) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return Optional.of(needed);
            }
        }
        return Optional.empty();
    }

    /** @return {@code true} pro páteřní řemeslo osady (viz {@link #CORE_ROLES}) */
    public static boolean isCoreRole(dev.botalive.api.role.BotRole role) {
        return CORE_ROLES.contains(role);
    }

    /**
     * Kolik členů sídla dělá dané řemeslo. Slouží k rozhodnutí, zda je role
     * <b>přebytková</b> (≥ 2) – přebytkový specialista se pak smí přeškolit na
     * chybějící core řemeslo, aniž by své vlastní nechal nepokryté.
     *
     * @param settlementId sídlo
     * @param role         řemeslo
     * @return počet členů s tou rolí
     */
    public int roleCount(long settlementId, dev.botalive.api.role.BotRole role) {
        BotManagerImpl manager = botManager;
        if (manager == null) {
            return 0;
        }
        return roleCount(settlementId, role,
                id -> manager.byId(id).map(dev.botalive.api.bot.Bot::role)
                        .orElse(dev.botalive.api.role.BotRole.NONE));
    }

    /** Testovatelná varianta s injektovaným zdrojem rolí. */
    synchronized int roleCount(long settlementId, dev.botalive.api.role.BotRole role,
            java.util.function.Function<UUID, dev.botalive.api.role.BotRole> roleOf) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null || role == null) {
            return 0;
        }
        int count = 0;
        for (Member member : settlement.members.values()) {
            if (roleOf.apply(member.botId()) == role) {
                count++;
            }
        }
        return count;
    }

    /**
     * Zaznamená zakotvenost člena (Σ FRIEND vazeb k ostatním členům) pro
     * odvození starosty. Volá se ze sousedské úvahy; oddělené kvůli
     * testovatelnosti bez vedlejších efektů {@code checkCohesion}.
     */
    synchronized void noteTies(SocialView view) {
        Long id = memberIndex.get(view.botId());
        Settlement settlement = id == null ? null : settlements.get(id);
        if (settlement == null) {
            return;
        }
        double ties = 0;
        for (Member member : settlement.members.values()) {
            if (!member.botId().equals(view.botId())) {
                ties += view.friend(member.botId());
            }
        }
        settlement.memberTies.put(view.botId(), ties);
    }

    /**
     * @return starosta sídla: nejzakotvenější člen (nejvyšší Σ FRIEND vazeb
     *         k sousedům); bez dat zakladatel, je-li dosud členem
     */
    private UUID mayorOf(Settlement settlement) {
        UUID best = null;
        double bestTies = 0;
        for (Member member : settlement.members.values()) {
            Double ties = settlement.memberTies.get(member.botId());
            if (ties != null && ties > bestTies) {
                bestTies = ties;
                best = member.botId();
            }
        }
        if (best != null) {
            return best;
        }
        return settlement.founder != null
                && settlement.members.containsKey(settlement.founder)
                ? settlement.founder : null;
    }

    /** @return první parcela nezabraná členem ani zákazem, nebo {@code null} */
    private Integer freePlotIndex(Settlement settlement) {
        for (int index = 1; index <= MAX_PLOT_INDEX; index++) {
            if (plotAvailable(settlement, index)) {
                return index;
            }
        }
        return null;
    }

    /** Volná parcela: v rozsahu katastru, ne rezervovaná a nikým nezabraná. */
    private boolean plotAvailable(Settlement settlement, int index) {
        if (index < 1 || index > MAX_PLOT_INDEX || settlement.unusablePlots.containsKey(index)) {
            return false;
        }
        for (Member member : settlement.members.values()) {
            if (member.plotIndex() == index) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rezervuje staveniště pro stavbu, která je větší než jedna parcela –
     * zabere souvislý blok přiléhajících parcel ({@code cellsX×cellsZ} buněk
     * mřížky) tak, aby se do nich nepostavil dům. Malá stavba (do rozestupu)
     * spadne na jednu parcelu jako dosud. Origin je vycentrovaný nad blokem,
     * výšku i jemné umístění doladí stavitel ({@code SiteFinder}).
     *
     * <p>Blokace je trvalá (jako u projektových parcel): vzor
     * {@code unusablePlots.put(index, Long.MAX_VALUE)}. Volá se, až bude druh
     * stavby s půdorysem přes parcelu (dnes se všechny civilní stavby vejdou
     * do jedné – studna 3×3 až kostel 5×7 ≤ rozestup); metoda je připravená
     * pro budoucí větší stavby (zvonice, hradní sál).</p>
     *
     * @param settlementId sídlo
     * @param footprintW   šířka půdorysu (X, bloky)
     * @param footprintD   hloubka půdorysu (Z, bloky)
     * @return kotevní parcela s vycentrovaným originem, nebo prázdné (plno /
     *         sídlo neexistuje)
     */
    public synchronized Optional<PlotSlot> reserveSite(long settlementId, int footprintW,
                                                       int footprintD) {
        Settlement settlement = settlements.get(settlementId);
        if (settlement == null) {
            return Optional.empty();
        }
        int spacing = config.plotSpacing();
        int cellsX = PlotLayout.plotSpan(footprintW, spacing);
        int cellsZ = PlotLayout.plotSpan(footprintD, spacing);
        settlement.unusablePlots.values().removeIf(until -> until < clock.getAsLong());
        for (int anchor = 1; anchor <= MAX_PLOT_INDEX; anchor++) {
            int[] cell = PlotLayout.cellFor(anchor);
            List<Integer> block = blockIndices(cell[0], cell[1], cellsX, cellsZ);
            if (block == null || !block.stream().allMatch(i -> plotAvailable(settlement, i))) {
                continue; // mimo katastr / obsahuje náves / nějaká buňka obsazená
            }
            for (int index : block) {
                settlement.unusablePlots.put(index, Long.MAX_VALUE);
            }
            int cx = settlement.center.x() + Math.round((cell[0] + (cellsX - 1) / 2.0f) * spacing);
            int cz = settlement.center.z() + Math.round((cell[1] + (cellsZ - 1) / 2.0f) * spacing);
            BlockPos origin = new BlockPos(cx - footprintW / 2, settlement.center.y(),
                    cz - footprintD / 2);
            Cardinal facing = Cardinal.toward(cx, cz, settlement.center.x(), settlement.center.z());
            return Optional.of(new PlotSlot(anchor, origin, facing));
        }
        return Optional.empty();
    }

    /**
     * Indexy parcel v obdélníkovém bloku {@code cellsX×cellsZ} mřížky s kotvou
     * v levém předním rohu {@code (ax, az)}; {@code null}, když blok sahá za
     * katastr nebo na náves (index mimo {@code [1, MAX_PLOT_INDEX]}).
     */
    private static List<Integer> blockIndices(int ax, int az, int cellsX, int cellsZ) {
        List<Integer> block = new ArrayList<>(cellsX * cellsZ);
        for (int i = 0; i < cellsX; i++) {
            for (int j = 0; j < cellsZ; j++) {
                int index = PlotLayout.indexFor(ax + i, az + j);
                if (index < 1 || index > MAX_PLOT_INDEX) {
                    return null; // mimo katastr nebo náves
                }
                block.add(index);
            }
        }
        return block;
    }

    private void persistProject(Settlement settlement, Project project) {
        if (repository != null) {
            repository.upsertSettlementProject(settlement.id, project.kind.name(),
                    project.plotIndex, project.origin.x(), project.origin.y(),
                    project.origin.z(), project.facing.name(), project.done,
                    project.state.name(), project.needed, project.contributed,
                    project.width, project.depth, project.wallHeight);
        }
    }

    /** Stav z DB (hotové = DONE; neznámý/null → SITE) – tolerantní k downgradu. */
    private static ProjectState parseState(String state, boolean done) {
        if (done) {
            return ProjectState.DONE;
        }
        if (state == null) {
            return ProjectState.SITE;
        }
        try {
            return ProjectState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return ProjectState.SITE;
        }
    }

    private ProjectInfo projectInfo(Settlement settlement, Project project) {
        return new ProjectInfo(settlement.id, project.kind, project.origin,
                project.facing, project.done, project.width, project.depth, project.wallHeight);
    }

    /**
     * Velikost společné stavby z prosperity sídla. Prestižní sály (radnice,
     * kostel) a městské sklady (sýpka, zásobárna) rostou s městem; landmarky
     * (studna, tržiště, zvonice) i účelné dílny drží pevnou legacy velikost –
     * jsou to malé stavby, jejichž tvar je součástí identity. Span je přichycen
     * pod rozestup parcel (rezerva na cestu), takže stavba zůstane na jedné
     * parcele. {@link StructureSize#LEGACY} = drž dnešní pevnou stavbu.
     */
    private StructureSize communalSize(ProjectKind kind, SettlementTier tier) {
        int spanCap = Math.max(5, config.plotSpacing() - 3);
        return switch (kind) {
            case TOWN_HALL -> StructureSizer.scaledHall(5, 5, 5, tier, spanCap);
            case CHURCH -> StructureSizer.scaledHall(5, 7, 6, tier, spanCap);
            case GRANARY, WAREHOUSE -> tier == SettlementTier.MESTO
                    ? StructureSizer.scaledHall(5, 5, 3, tier, spanCap)
                    : StructureSize.LEGACY;
            default -> StructureSize.LEGACY; // landmarky a dílny: pevná velikost
        };
    }

    /**
     * Ohlásí růst stupně, pokud substance právě překročila dosud ohlášený
     * stupeň. Pokles je tichý – stupeň se jen živě přepočítává.
     */
    private Optional<SettlementTier> maybeAnnounceTier(Settlement settlement) {
        SettlementTier tier = tierOf(settlement);
        if (tier.ordinal() > settlement.announcedTier.ordinal()) {
            settlement.announcedTier = tier;
            if (repository != null) {
                repository.updateSettlementTier(settlement.id, tier.name());
            }
            return Optional.of(tier);
        }
        return Optional.empty();
    }

    /** @return počet dostavěných domů členů */
    private int houses(Settlement settlement) {
        int houses = 0;
        for (Member member : settlement.members.values()) {
            if (member.houseDone()) {
                houses++;
            }
        }
        return houses;
    }

    // ======================================================== soudržnost

    /**
     * Sousedská úvaha – volá se z ticku bota jednou za pár desítek sekund.
     *
     * <p>Řeší roztržky (nepřítel ve vesnici → odchod a založení vlastní),
     * stěhování za kamarády a usazování samotářů s domem: přidání k vesnici
     * kamarádů, nebo založení vesnice kolem vlastního domu, když má bot
     * přátele mezi boty a v okolí žádná vesnice není.</p>
     *
     * @param view sociální snímek bota
     * @return provedená akce (členství už je změněné), nebo empty
     */
    public synchronized Optional<CohesionAction> checkCohesion(SocialView view) {
        if (!config.enabled() || view.world() == null) {
            return Optional.empty();
        }
        noteTies(view);
        long now = clock.getAsLong();
        long cooldown = config.changeCooldownMinutes() * 60_000L;
        long sinceChange = now - lastChangeAt.getOrDefault(view.botId(), 0L);
        Long own = memberIndex.get(view.botId());

        if (own != null) {
            Settlement settlement = settlements.get(own);
            // 1) Roztržka: čerstvý nepřítel mezi sousedy převáží vazby.
            if (sinceChange >= cooldown / 6) {
                UUID enemy = strongestFreshEnemy(view, settlement, now);
                if (enemy != null) {
                    double grudge = view.enemy(enemy);
                    double ties = tiesTo(view, settlement, enemy);
                    if (grudge > ties + view.patience() * 0.3) {
                        leave(view.botId());
                        rebuildFlags.add(view.botId());
                        lastChangeAt.put(view.botId(), now);
                        return Optional.of(new CohesionAction(
                                CohesionAction.Type.GRUDGE_LEAVE, settlement.name,
                                nameOf(enemy)));
                    }
                }
            }
            // 2) Stěhování za kamarády, když jinde má silnější vazby.
            if (wantsCompany(view) && sinceChange >= cooldown) {
                Settlement target = bestJoinable(view, view.position(),
                        config.joinRadius() * 2, FOLLOW_MIN_TIE);
                if (target != null && target.id != settlement.id) {
                    double there = affinity(view, target);
                    double here = tiesTo(view, settlement, null);
                    if (there > here * FOLLOW_RATIO + 0.1) {
                        UUID friend = strongestFriendIn(view, target);
                        leave(view.botId());
                        addMember(target, new Member(view.botId(), now, -1, null,
                                Cardinal.NORTH, false));
                        rebuildFlags.add(view.botId());
                        lastChangeAt.put(view.botId(), now);
                        return Optional.of(new CohesionAction(
                                CohesionAction.Type.FOLLOW_FRIEND, target.name,
                                nameOf(friend)));
                    }
                }
            }
            return Optional.empty();
        }

        // Bez vesnice: usazování řeší jen boti, kteří už dům mají
        // (bezdomovce přivede BuildHouseGoal přes planHome).
        if (view.housePos() == null || !wantsCompany(view) || sinceChange < cooldown) {
            return Optional.empty();
        }
        // 3) Samotář s domem: přidat se k vesnici kamarádů (a přestavět se tam).
        Settlement target = bestJoinable(view, view.position(), config.joinRadius(),
                Math.max(RELOCATE_MIN_AFFINITY, joinThreshold(view)));
        if (target != null) {
            addMember(target, new Member(view.botId(), now, -1, null, Cardinal.NORTH, false));
            rebuildFlags.add(view.botId());
            lastChangeAt.put(view.botId(), now);
            return Optional.of(new CohesionAction(CohesionAction.Type.JOIN_NEARBY,
                    target.name, nameOf(strongestFriendIn(view, target))));
        }
        // 4) Nikde nic a má kamarády mezi boty → jeho dům se stane návsí.
        // Starší dům nemá známou orientaci, parcela zakladatele se tedy
        // neeviduje – dům je náves sama o sobě (bot ho má v HOME paměti).
        if (hasBotFriend(view)
                && nearest(view.world(), view.housePos(),
                config.minVillageDistance(), null).isEmpty()) {
            Optional<SettlementInfo> founded = foundSettlement(view, view.housePos(),
                    null, Cardinal.NORTH, view.botId().getLeastSignificantBits());
            return founded.map(info -> new CohesionAction(
                    CohesionAction.Type.FOUND_AT_HOME, info.name(), null));
        }
        return Optional.empty();
    }

    /** Stojí bot o společnost? Jednotná brána pro stěhování i usazování. */
    private boolean wantsCompany(SocialView view) {
        return view.sociability() >= Math.max(0.4, config.lonerSociability());
    }

    /** Odstraní bota ze všech struktur (remove/purge bota). */
    public synchronized void removeBot(UUID botId) {
        leave(botId);
        lastChangeAt.remove(botId);
        rebuildFlags.remove(botId);
    }

    // ============================================================== interní

    private Member member(UUID botId) {
        Long id = memberIndex.get(botId);
        if (id == null) {
            return null;
        }
        Settlement settlement = settlements.get(id);
        return settlement == null ? null : settlement.members.get(botId);
    }

    /** Zapíše člena do vesnice + indexu a persistuje. */
    private void addMember(Settlement settlement, Member member) {
        settlement.members.put(member.botId(), member);
        memberIndex.put(member.botId(), settlement.id);
        if (repository != null) {
            repository.upsertSettlementMember(member.botId(), settlement.id,
                    member.joinedAt(),
                    member.plotIndex() < 0 ? null : member.plotIndex(),
                    member.plotOrigin() == null ? null : member.plotOrigin().x(),
                    member.plotOrigin() == null ? null : member.plotOrigin().y(),
                    member.plotOrigin() == null ? null : member.plotOrigin().z(),
                    member.facing().name(), member.houseDone());
        }
    }

    /** Odebere bota z vesnice; prázdnou vesnici zruší (domy zůstávají ve světě). */
    private void leave(UUID botId) {
        Long id = memberIndex.remove(botId);
        if (id == null) {
            return;
        }
        Settlement settlement = settlements.get(id);
        if (settlement != null) {
            settlement.members.remove(botId);
            if (settlement.members.isEmpty()) {
                settlements.remove(id);
                if (repository != null) {
                    repository.deleteSettlement(id);
                }
            }
        }
        if (repository != null) {
            repository.deleteSettlementMember(botId);
        }
    }

    /**
     * Nejlepší vesnice, ke které se bot může přidat: stejný svět, v dosahu,
     * volná kapacita, žádný nepřítel a součet přátelství aspoň
     * {@code minAffinity}.
     */
    private Settlement bestJoinable(SocialView view, BlockPos from, int radius,
                                    double minAffinity) {
        double radiusSq = (double) radius * radius;
        Settlement best = null;
        double bestAffinity = 0;
        for (Settlement settlement : settlements.values()) {
            if (!settlement.world.equals(view.world())
                    || settlement.members.containsKey(view.botId())
                    || settlement.members.size() >= config.maxMembers()
                    || horizontalSq(settlement.center, from) > radiusSq
                    || hasEnemyIn(view, settlement)) {
                continue;
            }
            double affinity = affinity(view, settlement);
            if (affinity < minAffinity) {
                continue;
            }
            if (best == null || affinity > bestAffinity) {
                best = settlement;
                bestAffinity = affinity;
            }
        }
        return best;
    }

    /** Práh přátelství pro vstup – hodně společenským stačí, že tam nikoho nemají proti. */
    private double joinThreshold(SocialView view) {
        return view.sociability() >= OPEN_JOIN_SOCIABILITY ? 0.0 : JOIN_MIN_AFFINITY;
    }

    /** Součet přátelství minus dvojnásobek nevraživostí vůči členům vesnice. */
    private double affinity(SocialView view, Settlement settlement) {
        double sum = 0;
        for (UUID member : settlement.members.keySet()) {
            sum += view.friend(member) - 2 * view.enemy(member);
        }
        return sum;
    }

    /** Vazby k vesnici bez započtení jednoho člena (nepřítele). */
    private double tiesTo(SocialView view, Settlement settlement, UUID except) {
        double sum = 0;
        for (UUID member : settlement.members.keySet()) {
            if (member.equals(view.botId()) || member.equals(except)) {
                continue;
            }
            sum += view.friend(member);
        }
        return sum;
    }

    /** Má bot ve vesnici nepřítele nad prahem zášti? (bez ohledu na stáří) */
    private boolean hasEnemyIn(SocialView view, Settlement settlement) {
        for (UUID member : settlement.members.keySet()) {
            if (!member.equals(view.botId())
                    && view.enemy(member) >= config.grudgeThreshold()) {
                return true;
            }
        }
        return false;
    }

    /** Nejsilnější čerstvý nepřítel mezi sousedy (nad prahem zášti). */
    private UUID strongestFreshEnemy(SocialView view, Settlement settlement, long now) {
        UUID worst = null;
        double worstImportance = 0;
        for (UUID member : settlement.members.keySet()) {
            if (member.equals(view.botId())) {
                continue;
            }
            double importance = view.enemy(member);
            long updatedAt = view.enemyUpdatedAt().getOrDefault(member, 0L);
            if (importance >= config.grudgeThreshold()
                    && now - updatedAt <= config.grudgeWindowHours() * 60L * 60 * 1000
                    && importance > worstImportance) {
                worst = member;
                worstImportance = importance;
            }
        }
        return worst;
    }

    private UUID strongestFriendIn(SocialView view, Settlement settlement) {
        UUID best = null;
        double bestImportance = 0;
        for (UUID member : settlement.members.keySet()) {
            double importance = view.friend(member);
            if (importance > bestImportance) {
                best = member;
                bestImportance = importance;
            }
        }
        return best;
    }

    /** Má bot kamaráda, který je bot (ne hráč)? Vesnice zakládá jen kvůli botům. */
    private boolean hasBotFriend(SocialView view) {
        BotManagerImpl manager = botManager;
        if (manager == null) {
            return false;
        }
        for (Map.Entry<UUID, Double> entry : view.friends().entrySet()) {
            if (entry.getValue() >= dev.botalive.core.pvp.PvpCoordinator.ALLY_THRESHOLD
                    && manager.byId(entry.getKey()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private String nameOf(UUID botId) {
        BotManagerImpl manager = botManager;
        if (botId == null || manager == null) {
            return null;
        }
        return manager.byId(botId).map(Bot::name).orElse(null);
    }

    private String uniqueName(String founderName, long seed) {
        Set<String> taken = new java.util.HashSet<>();
        for (Settlement settlement : settlements.values()) {
            taken.add(settlement.name);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            String name = SettlementNames.generate(founderName, seed, attempt);
            if (!taken.contains(name)) {
                return name;
            }
        }
        return SettlementNames.generate(founderName, seed, 0) + " " + (settlements.size() + 1);
    }

    private Optional<BlockPos> nearestForeignCenterLocked(Long ignoredId, String world,
                                                          BlockPos pos, int radius) {
        double bestSq = (double) radius * radius;
        BlockPos best = null;
        for (Settlement settlement : settlements.values()) {
            if (!settlement.world.equals(world)
                    || (ignoredId != null && settlement.id == ignoredId)) {
                continue;
            }
            double distSq = horizontalSq(settlement.center, pos);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = settlement.center;
            }
        }
        return Optional.ofNullable(best);
    }

    private static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private SettlementInfo info(Settlement settlement) {
        List<MemberInfo> members = new ArrayList<>();
        for (Member member : settlement.members.values()) {
            members.add(new MemberInfo(member.botId(), member.plotIndex(),
                    member.plotOrigin()));
        }
        List<ProjectInfo> projects = new ArrayList<>();
        for (Project project : settlement.projects.values()) {
            projects.add(projectInfo(settlement, project));
        }
        return new SettlementInfo(settlement.id, settlement.name, settlement.world,
                settlement.center, settlement.founder, List.copyOf(members),
                houses(settlement), tierOf(settlement), mayorOf(settlement),
                List.copyOf(projects));
    }
}
