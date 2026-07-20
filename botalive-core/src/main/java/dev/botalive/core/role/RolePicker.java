package dev.botalive.core.role;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.api.role.BotRole;
import dev.botalive.core.util.BotRandom;

import java.util.EnumMap;
import java.util.Map;

/**
 * Výběr profese nového bota podle jeho osobnosti.
 *
 * <p>Každá role dostane skóre z relevantních rysů plus gaussovský šum –
 * agresivní odvážlivec bude nejspíš lovec, trpělivý lenoch rybář, ale výjimky
 * existují (šum), takže populace botů není deterministická karikatura.
 * Část botů zůstane univerzály (baseline role NONE).</p>
 */
public final class RolePicker {

    private RolePicker() {
    }

    /**
     * Vybere roli pro bota.
     *
     * @param personality osobnost bota
     * @param rng         per-bot náhoda
     * @return vybraná role
     */
    /** Zvýhodnění profesí, bez kterých osada nevznikne (stavitel, kopáč…). */
    private static final double CORE = 1.15;
    /** Specializace – v osadě po jednom kuse. */
    private static final double UNCOMMON = 0.85;
    /** Profese silně měnící dynamiku serveru (zloděj, dobrodruh). */
    private static final double RARE = 0.6;

    public static BotRole pick(Personality personality, BotRandom rng) {
        double courage = personality.trait(Trait.COURAGE);
        double caution = personality.trait(Trait.CAUTION);
        double aggression = personality.trait(Trait.AGGRESSION);
        double curiosity = personality.trait(Trait.CURIOSITY);
        double sociability = personality.trait(Trait.SOCIABILITY);
        double laziness = personality.trait(Trait.LAZINESS);
        double intelligence = personality.trait(Trait.INTELLIGENCE);
        double helpfulness = personality.trait(Trait.HELPFULNESS);
        double greed = personality.trait(Trait.GREED);
        double patience = personality.trait(Trait.PATIENCE);

        Map<BotRole, Double> scores = new EnumMap<>(BotRole.class);
        scores.put(BotRole.NONE, 0.75);

        // Páteř osady – bez stavitelů a kopáčů vesnice nevznikne, proto mají
        // navrch. Bez téhle váhy se při 19 profesích rozdrobí na ~2 boty na roli.
        scores.put(BotRole.BUILDER, (caution * 0.6 + intelligence * 0.4) * CORE);
        scores.put(BotRole.MINER, (greed * 0.7 + patience * 0.4) * CORE);
        scores.put(BotRole.FARMER, (helpfulness * 0.6 + patience * 0.4) * CORE);
        scores.put(BotRole.LUMBERJACK,
                (patience * 0.5 + (1 - sociability) * 0.4 + courage * 0.2) * CORE);

        // Běžné doplňkové profese
        scores.put(BotRole.HUNTER, aggression * 0.7 + courage * 0.5);
        scores.put(BotRole.BLACKSMITH, intelligence * 0.6 + patience * 0.5);
        scores.put(BotRole.TRADER, greed * 0.5 + sociability * 0.6);
        scores.put(BotRole.COOK, helpfulness * 0.5 + patience * 0.4 + sociability * 0.3);
        scores.put(BotRole.COURIER, helpfulness * 0.5 + (1 - laziness) * 0.5);

        // Méně časté – specializace, kterou osada snese v jednom kuse
        scores.put(BotRole.ENCHANTER, (intelligence * 0.7 + curiosity * 0.4) * UNCOMMON);
        // Rybář je běžná vanilla profese, ne exotika – penalizace by rozbila
        // signaturu „trpělivý lenoch = rybář" (má na to test).
        scores.put(BotRole.FISHERMAN, patience * 0.7 + laziness * 0.4);
        scores.put(BotRole.ALCHEMIST, (intelligence * 0.6 + curiosity * 0.5) * UNCOMMON);
        scores.put(BotRole.GUARDIAN, (courage * 0.7 + helpfulness * 0.4) * UNCOMMON);
        scores.put(BotRole.SCOUT, (curiosity * 0.8 + (1 - caution) * 0.3) * UNCOMMON);
        scores.put(BotRole.BEASTMASTER,
                (patience * 0.6 + helpfulness * 0.4 + (1 - aggression) * 0.3) * UNCOMMON);
        scores.put(BotRole.DIPLOMAT,
                (sociability * 0.7 + helpfulness * 0.5) * UNCOMMON);

        // Vzácné – silně mění dynamiku serveru, po jednom na osadu bohatě stačí
        scores.put(BotRole.THIEF,
                (greed * 0.8 + (1 - helpfulness) * 0.5 + aggression * 0.2) * RARE);
        scores.put(BotRole.ADVENTURER,
                (courage * 0.8 + curiosity * 0.5 + (1 - caution) * 0.3) * RARE);

        BotRole best = BotRole.NONE;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<BotRole, Double> entry : scores.entrySet()) {
            double score = entry.getValue() + rng.gaussian(0, 0.22);
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        return best;
    }
}
