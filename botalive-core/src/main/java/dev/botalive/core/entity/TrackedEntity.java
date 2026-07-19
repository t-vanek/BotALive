package dev.botalive.core.entity;

import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entita, kterou bot „vidí" – rekonstruovaná z paketů serveru.
 *
 * <p>Pozice se aktualizuje ze síťového vlákna a čte z AI vlákna, proto je
 * držena v {@link AtomicReference} (nemutabilní {@link Vec3}).</p>
 */
public final class TrackedEntity {

    private final int entityId;
    private final UUID uuid;
    private final EntityType type;
    private final AtomicReference<Vec3> position = new AtomicReference<>();

    /** EWMA odhad rychlosti (bloky/tick) – pro predikci při střelbě. */
    private final AtomicReference<Vec3> velocity = new AtomicReference<>(Vec3.ZERO);
    private volatile float yaw;
    private volatile float pitch;
    private volatile long lastUpdateMs;

    /**
     * @param entityId síťové id entity
     * @param uuid     UUID entity
     * @param type     typ entity
     * @param initial  počáteční pozice
     */
    public TrackedEntity(int entityId, UUID uuid, EntityType type, Vec3 initial) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.type = type;
        this.position.set(initial);
        this.lastUpdateMs = System.currentTimeMillis();
    }

    /** @return síťové id entity */
    public int entityId() {
        return entityId;
    }

    /** @return UUID entity */
    public UUID uuid() {
        return uuid;
    }

    /** @return typ entity */
    public EntityType type() {
        return type;
    }

    /** @return poslední známá pozice */
    public Vec3 position() {
        return position.get();
    }

    /** @return natočení těla (stupně) */
    public float yaw() {
        return yaw;
    }

    /** @return náklon hlavy (stupně) */
    public float pitch() {
        return pitch;
    }

    /** @return epocha ms poslední aktualizace (pro detekci zatuchlých entit) */
    public long lastUpdateMs() {
        return lastUpdateMs;
    }

    /**
     * Absolutní aktualizace pozice (teleport paket).
     */
    public void setPosition(Vec3 pos, float yaw, float pitch) {
        position.set(pos);
        this.yaw = yaw;
        this.pitch = pitch;
        this.lastUpdateMs = System.currentTimeMillis();
    }

    /**
     * Relativní posun (move paket).
     */
    public void moveBy(double dx, double dy, double dz) {
        position.updateAndGet(p -> p.add(dx, dy, dz));
        // EWMA: 40 % nového vzorku – vyhladí nepravidelnou kadenci move paketů.
        velocity.updateAndGet(v -> v.mul(0.6).add(dx * 0.4, dy * 0.4, dz * 0.4));
        this.lastUpdateMs = System.currentTimeMillis();
    }

    /** @return vyhlazený odhad rychlosti (bloky/tick) */
    public Vec3 velocityEstimate() {
        return velocity.get();
    }

    /**
     * Aktualizace rotace.
     */
    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.lastUpdateMs = System.currentTimeMillis();
    }

    /** @return {@code true} pokud jde o hráče (nebo jiného bota) */
    public boolean isPlayer() {
        return type == EntityType.PLAYER;
    }

    /**
     * @return {@code true} pokud entita fyzicky blokuje pohyb – klientská
     *         fyzika kolize entit nemodeluje (jen bloky), takže zaparkovaná
     *         loď je pro bota neviditelná zeď (provozní nález: bot přišpendlený
     *         o loď u břehu). Tyhle entity se obcházejí steeringem davu.
     */
    public boolean blocksMovement() {
        if (type == EntityType.PLAYER || type == EntityType.SHULKER) {
            return true;
        }
        String name = type.name();
        return name.endsWith("_BOAT") || name.endsWith("_RAFT") || name.contains("MINECART");
    }

    /**
     * Vždy nepřátelské moby bot napadá sám; <b>neutrální</b> druhy (enderman,
     * piglin, zombifikovaný piglin) tu záměrně nejsou – na ty se útočí jen
     * odvetou přes čerstvou ENEMY vzpomínku (viz {@code CombatGoal}), jako
     * hráč: kdo si nezačne, s hordou zombifikovaných piglinů nebojuje.
     * Piglina navíc krotí zlatá zbroj ({@code NetherGoal} nasazuje zlaté boty).
     *
     * @return {@code true} pokud jde o vždy nepřátelského moba
     */
    public boolean isHostile() {
        return switch (type) {
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, SKELETON, STRAY, BOGGED, WITHER_SKELETON,
                 CREEPER, SPIDER, CAVE_SPIDER, WITCH, PILLAGER, VINDICATOR, EVOKER,
                 RAVAGER, VEX, PHANTOM, BLAZE, GHAST, MAGMA_CUBE, SLIME, SILVERFISH, ENDERMITE,
                 GUARDIAN, ELDER_GUARDIAN, SHULKER, HOGLIN, ZOGLIN, PIGLIN_BRUTE, WARDEN, BREEZE,
                 CREAKING -> true;
            default -> false;
        };
    }

    /**
     * Nemrtví: lektvar zranění je léčí a jed na ně nefunguje – útočné
     * splash lektvary se na ně nikdy nehází.
     *
     * @return {@code true} pokud jde o nemrtvého moba
     */
    public boolean isUndead() {
        return switch (type) {
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, SKELETON, STRAY, BOGGED,
                 WITHER_SKELETON, PHANTOM, ZOGLIN, ZOMBIFIED_PIGLIN, WITHER,
                 SKELETON_HORSE, ZOMBIE_HORSE -> true;
            default -> false;
        };
    }

    /** @return {@code true} pokud jde o upuštěný předmět */
    public boolean isItem() {
        return type == EntityType.ITEM;
    }

    /** @return {@code true} pokud jde o lovnou zvěř (jídlo, kůže, peří) */
    public boolean isHuntableAnimal() {
        return switch (type) {
            case COW, PIG, SHEEP, CHICKEN, RABBIT -> true;
            default -> false;
        };
    }

    /** @return {@code true} pokud jde o ochočitelný druh (vanilla taming) */
    public boolean isTameableType() {
        return switch (type) {
            case WOLF, CAT, PARROT, HORSE, DONKEY, MULE, LLAMA -> true;
            default -> false;
        };
    }

    /** @return {@code true} pokud jde o endermana (neutrál – nekoukat, neprovokovat) */
    public boolean isEnderman() {
        return type == EntityType.ENDERMAN;
    }

    /** @return {@code true} pokud jde o dračí (end) krystal na obsidiánovém pilíři */
    public boolean isEndCrystal() {
        return type == EntityType.END_CRYSTAL;
    }

    /** @return {@code true} pokud jde o ender draka */
    public boolean isEnderDragon() {
        return type == EntityType.ENDER_DRAGON;
    }

    /** @return {@code true} pokud jde o oblak efektu (dračí dech, lektvary) */
    public boolean isEffectCloud() {
        return type == EntityType.AREA_EFFECT_CLOUD;
    }
}
