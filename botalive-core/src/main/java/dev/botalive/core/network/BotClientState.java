package dev.botalive.core.network;

import dev.botalive.core.util.Vec3;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sdílený protokolový stav bota – „poštovní schránka" mezi síťovým vláknem
 * a tick vláknem bota.
 *
 * <p>Síťové vlákno sem zapisuje, tick vlákno čte a odbavuje fronty. Všechna
 * pole jsou volatile nebo concurrent – žádné zámky, žádné čekání v paketovém
 * vlákně.</p>
 */
public final class BotClientState {

    /** Síťové entity id bota (z login paketu). */
    private volatile int entityId = -1;

    /** Klíč dimenze/světa z protokolu (např. {@code minecraft:overworld}). */
    private volatile String worldKey = "";

    /** Bot dostal login a první teleport – je plně ve hře. */
    private volatile boolean spawned;

    /** Bot je mrtvý a čeká na respawn. */
    private volatile boolean dead;

    private volatile float health = 20f;
    private volatile int food = 20;
    private volatile float saturation = 5f;

    /** Aktuálně vybraný hotbar slot (0–8). */
    private volatile int heldSlot;

    /** Id právě otevřeného kontejneru (0 = žádný/vlastní inventář). */
    private volatile int openContainerId;

    /** Entity id vozidla, ve kterém bot sedí (-1 = žádné). */
    private volatile int vehicleId = -1;

    /** Denní čas světa 0–23999 (SetTime paket; -1 = ještě nedorazil). */
    private volatile long worldTime = -1;

    /** Aktivní lektvarové efekty: efekt → čas vypršení (epoch ms). */
    private final java.util.concurrent.ConcurrentHashMap<
            org.geysermc.mcprotocollib.protocol.data.game.entity.Effect, Long> activeEffects =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Prší ve světě bota (GameEvent pakety – server posílá stav i po loginu). */
    private volatile boolean raining;

    /** Bouřka ve světě bota (síla hromu > 0). */
    private volatile boolean thundering;

    /** XP level bota (SetExperience paket). */
    private volatile int expLevel;

    /** Čítač sequence pro akce s bloky (kopání, pokládání, použití itemu). */
    private final AtomicInteger actionSequence = new AtomicInteger(1);

    /** Teleporty od serveru čekající na aplikaci v ticku. */
    private final Queue<TeleportSync> pendingTeleports = new ConcurrentLinkedQueue<>();

    /** Knockback/impulzy od serveru čekající na aplikaci v ticku. */
    private final Queue<Vec3> pendingImpulses = new ConcurrentLinkedQueue<>();

    /** @return síťové entity id bota, -1 před loginem */
    public int entityId() {
        return entityId;
    }

    /** Nastaví entity id (login paket). */
    public void entityId(int id) {
        this.entityId = id;
    }

    /** @return protokolový klíč světa */
    public String worldKey() {
        return worldKey;
    }

    /** Nastaví klíč světa (login/respawn). */
    public void worldKey(String key) {
        this.worldKey = key;
    }

    /**
     * Efekt aplikován (UpdateMobEffect).
     *
     * @param effect        efekt
     * @param durationTicks doba trvání v ticích (-1 = nekonečno)
     */
    public void effectApplied(org.geysermc.mcprotocollib.protocol.data.game.entity.Effect effect,
                              int durationTicks) {
        long expiry = durationTicks < 0 ? Long.MAX_VALUE
                : System.currentTimeMillis() + durationTicks * 50L;
        activeEffects.put(effect, expiry);
    }

    /**
     * Efekt odebrán (RemoveMobEffect).
     *
     * @param effect efekt
     */
    public void effectRemoved(org.geysermc.mcprotocollib.protocol.data.game.entity.Effect effect) {
        activeEffects.remove(effect);
    }

    /**
     * @param effect efekt
     * @return {@code true} pokud efekt právě působí (dle paketů a času)
     */
    public boolean effectActive(org.geysermc.mcprotocollib.protocol.data.game.entity.Effect effect) {
        Long expiry = activeEffects.get(effect);
        if (expiry == null) {
            return false;
        }
        if (expiry <= System.currentTimeMillis()) {
            activeEffects.remove(effect);
            return false;
        }
        return true;
    }

    /** Zahodí všechny efekty (respawn/smrt – server je čistí bez paketů). */
    public void clearEffects() {
        activeEffects.clear();
    }

    /** @return {@code true} pokud bot dokončil spawn sekvenci */
    public boolean spawned() {
        return spawned;
    }

    /** Označí dokončení spawn sekvence. */
    public void spawned(boolean value) {
        this.spawned = value;
    }

    /** @return {@code true} pokud je bot mrtvý */
    public boolean dead() {
        return dead;
    }

    /** Označí smrt/oživení. */
    public void dead(boolean value) {
        this.dead = value;
    }

    /** @return zdraví 0–20 */
    public float health() {
        return health;
    }

    /** @return jídlo 0–20 */
    public int food() {
        return food;
    }

    /** @return sytost */
    public float saturation() {
        return saturation;
    }

    /** Aktualizace vitálních hodnot (SetHealth paket). */
    public void updateVitals(float health, int food, float saturation) {
        this.health = health;
        this.food = food;
        this.saturation = saturation;
    }

    /** @return vybraný hotbar slot 0–8 */
    public int heldSlot() {
        return heldSlot;
    }

    /** Nastaví vybraný hotbar slot. */
    public void heldSlot(int slot) {
        this.heldSlot = slot;
    }

    /** @return další sequence číslo pro blokové akce */
    public int nextSequence() {
        return actionSequence.getAndIncrement();
    }

    /** @return id otevřeného kontejneru (0 = žádný) */
    public int openContainerId() {
        return openContainerId;
    }

    /** Nastaví id otevřeného kontejneru (OpenScreen paket / zavření). */
    public void openContainerId(int containerId) {
        this.openContainerId = containerId;
    }

    /** @return entity id vozidla bota, -1 pokud nesedí v žádném */
    public int vehicleId() {
        return vehicleId;
    }

    /** Nastaví vozidlo (SetPassengers paket). */
    public void vehicleId(int entityId) {
        this.vehicleId = entityId;
    }

    /** @return denní čas světa 0–23999, nebo -1 pokud ještě neznámý */
    public long worldTime() {
        return worldTime;
    }

    /** Nastaví denní čas světa (SetTime paket). */
    public void worldTime(long time) {
        this.worldTime = time;
    }

    /** @return prší ve světě bota? */
    public boolean raining() {
        return raining;
    }

    /** Nastaví stav deště (GameEvent paket). */
    public void raining(boolean value) {
        this.raining = value;
    }

    /** @return je ve světě bota bouřka? */
    public boolean thundering() {
        return thundering;
    }

    /** Nastaví stav bouřky (GameEvent paket). */
    public void thundering(boolean value) {
        this.thundering = value;
    }

    /** @return XP level bota */
    public int expLevel() {
        return expLevel;
    }

    /** Nastaví XP level (SetExperience paket). */
    public void expLevel(int level) {
        this.expLevel = level;
    }

    /** Zařadí teleport k aplikaci v ticku. */
    public void queueTeleport(TeleportSync teleport) {
        pendingTeleports.add(teleport);
    }

    /** @return další čekající teleport, nebo {@code null} */
    public TeleportSync pollTeleport() {
        return pendingTeleports.poll();
    }

    /** Zařadí impulz (knockback) k aplikaci v ticku. */
    public void queueImpulse(Vec3 impulse) {
        pendingImpulses.add(impulse);
    }

    /** @return další čekající impulz, nebo {@code null} */
    public Vec3 pollImpulse() {
        return pendingImpulses.poll();
    }

    /** Reset stavu při odpojení. */
    public void reset() {
        spawned = false;
        dead = false;
        entityId = -1;
        vehicleId = -1;
        openContainerId = 0;
        raining = false;
        thundering = false;
        pendingTeleports.clear();
        pendingImpulses.clear();
    }
}
