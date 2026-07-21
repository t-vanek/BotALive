package dev.botalive.core.ai;

import java.util.Locale;
import java.util.Map;

/**
 * Fyziologický stav bota – zatím <b>energie/únava</b> (viz docs/BOT_LIFE.md).
 *
 * <p>Energie 0–1 klesá bděním a námahou (pohyb, boj) a obnovuje se spánkem.
 * Dává spánku a odpočinku vnitřní příčinu, kterou dřív suploval jen denní čas:
 * unavený bot vyhledá postel i mimo obvyklou noční rutinu a odloží dlouhé
 * výpravy. Mozek únavu čte přes {@link #modulate(String)} – jemný násobič
 * utility, jehož vliv škáluje míra únavy, takže svěží bot (energie nad prahem)
 * dostane přesně 1.0 a chování zůstává beze změny.</p>
 *
 * <p>Čistá, jednotkově testovaná třída. Sazby jsou laděné na herní den
 * (~20 min): svěží bot je unavený zhruba po dni aktivity, prospaná noc ho
 * dobije.</p>
 */
public final class Vitals {

    /** Základní úbytek energie za krok aktualizace (~1 s bdění). */
    private static final double DRAIN_BASE = 0.0006;
    /** Dodatečný úbytek při plné námaze (pohyb, boj). */
    private static final double DRAIN_EXERTION = 0.0012;
    /** Obnova energie za krok spánku. */
    private static final double RECOVER_SLEEP = 0.0018;

    /** Práh únavy – pod ním se projeví modulace. */
    private static final double TIRED = 0.35;
    /** Práh vyčerpání – bot je otrávený a náladový. */
    private static final double EXHAUSTED = 0.15;

    /** Násobiče utility při únavě (obnova nahoru, náročné aktivity dolů). */
    private static final Map<String, Double> TIRED_MODULATION = Map.ofEntries(
            Map.entry("sleep", 1.8), Map.entry("home", 1.4), Map.entry("shelter", 1.3),
            Map.entry("camp", 1.3),
            Map.entry("explore", 0.7), Map.entry("nether", 0.6), Map.entry("end-travel", 0.6),
            Map.entry("dragon-fight", 0.6), Map.entry("wither-fight", 0.6),
            Map.entry("mine", 0.85), Map.entry("hunt", 0.85), Map.entry("pvp", 0.8));

    private double energy = 1.0;

    /** @return aktuální energie 0–1 */
    public double energy() {
        return energy;
    }

    /** @return {@code true} pokud je bot unavený (energie pod prahem) */
    public boolean tired() {
        return energy < TIRED;
    }

    /** @return {@code true} pokud je bot vyčerpaný */
    public boolean exhausted() {
        return energy < EXHAUSTED;
    }

    /** Plná energie – nový nebo právě respawnutý bot. */
    public void refresh() {
        energy = 1.0;
    }

    /**
     * Jeden krok vývoje energie.
     *
     * @param sleeping bot právě spí (rychlá obnova)
     * @param exertion míra námahy 0–1 (pohyb, boj)
     */
    public void tick(boolean sleeping, double exertion) {
        if (sleeping) {
            energy += RECOVER_SLEEP;
        } else {
            energy -= DRAIN_BASE + DRAIN_EXERTION * clamp01(exertion);
        }
        energy = clamp01(energy);
    }

    /**
     * Násobič utility cíle podle únavy. Svěží bot (energie nad prahem) vrací
     * 1.0; s klesající energií se vliv lineárně zesiluje.
     *
     * @param goalId id cíle
     * @return únavový násobič
     */
    public double modulate(String goalId) {
        if (energy >= TIRED) {
            return 1.0;
        }
        double tiredness = (TIRED - energy) / TIRED; // 0–1, jak hluboko pod prahem
        double weight = TIRED_MODULATION.getOrDefault(goalId, 1.0);
        return 1.0 + (weight - 1.0) * tiredness;
    }

    /**
     * @return krátký český popis stavu energie pro diagnostiku
     */
    public String describe() {
        String state = exhausted() ? "vyčerpán" : tired() ? "unaven" : "svěží";
        return state + " " + String.format(Locale.ROOT, "%.0f %%", energy * 100);
    }

    private static double clamp01(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
