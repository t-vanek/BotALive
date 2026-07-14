package dev.botalive.core.combat;

import java.util.Locale;

/**
 * Bojová obtížnost botů – škáluje přesnost, reakce a pokročilé techniky.
 *
 * <p>Nastavuje se v {@code config.yml} klíčem {@code ai.difficulty}.</p>
 */
public enum CombatDifficulty {

    /** Pomalé reakce, časté minely, žádné techniky. */
    EASY(0.55, 8, 6, false),

    /** Průměrný hráč. */
    NORMAL(0.75, 3, 2, false),

    /** Zkušený hráč – rychlé reakce, sprint reset. */
    HARD(0.88, 1, 0, true),

    /** Turnajový hráč – téměř neomylný. */
    NIGHTMARE(0.96, 0, 0, true);

    private final double hitChance;
    private final int extraReactionTicks;
    private final int extraCooldownTicks;
    private final boolean sprintReset;

    CombatDifficulty(double hitChance, int extraReactionTicks, int extraCooldownTicks, boolean sprintReset) {
        this.hitChance = hitChance;
        this.extraReactionTicks = extraReactionTicks;
        this.extraCooldownTicks = extraCooldownTicks;
        this.sprintReset = sprintReset;
    }

    /** @return šance zásahu při útoku */
    public double hitChance() {
        return hitChance;
    }

    /** @return dodatečné ticky reakce na nový cíl */
    public int extraReactionTicks() {
        return extraReactionTicks;
    }

    /** @return dodatečné ticky mezi útoky */
    public int extraCooldownTicks() {
        return extraCooldownTicks;
    }

    /** @return zda bot používá sprint reset */
    public boolean sprintReset() {
        return sprintReset;
    }

    /**
     * @param name hodnota z konfigurace
     * @return obtížnost; neznámá hodnota → NORMAL
     */
    public static CombatDifficulty fromConfig(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
