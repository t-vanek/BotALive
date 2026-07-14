package dev.botalive.core.chat;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.util.BotRandom;

/**
 * Individuální styl psaní bota – odvozený z osobnosti a per-bot náhody.
 *
 * <p>Žádní dva boti nepíšou stejně: liší se rychlostí psaní, chybovostí,
 * interpunkcí, používáním diakritiky, zkratek a smajlíků.</p>
 *
 * @param typoRate        šance na překlep v jednom slově (0–0.15)
 * @param correctTypos    zda bot posílá opravy překlepů („*slovo")
 * @param lowercase       píše všechno malými písmeny
 * @param dropDiacritics  píše bez diakritiky (cestina misto češtiny)
 * @param punctuation     používá tečky a čárky
 * @param emojiRate       šance přilepit smajlík na konec zprávy
 * @param abbreviate      používá zkratky (nz, jj, tj, btw)
 * @param wpm             rychlost psaní slov za minutu
 * @param exclamations    sklon k vykřičníkům
 */
public record ChatStyle(
        double typoRate,
        boolean correctTypos,
        boolean lowercase,
        boolean dropDiacritics,
        boolean punctuation,
        double emojiRate,
        boolean abbreviate,
        int wpm,
        double exclamations
) {

    /**
     * Odvodí styl z osobnosti bota.
     *
     * @param personality osobnost
     * @param rng         per-bot náhoda
     * @param baseWpm     základní rychlost psaní z konfigurace
     * @return styl psaní
     */
    public static ChatStyle derive(Personality personality, BotRandom rng, int baseWpm) {
        double intelligence = personality.trait(Trait.INTELLIGENCE);
        double laziness = personality.trait(Trait.LAZINESS);
        double sociability = personality.trait(Trait.SOCIABILITY);
        double patience = personality.trait(Trait.PATIENCE);

        double typoRate = Math.max(0.005, 0.10 - intelligence * 0.08 + rng.gaussian(0, 0.02));
        boolean correctTypos = intelligence > 0.55 && patience > 0.4 && rng.chance(0.7);
        boolean lowercase = laziness > 0.45 || rng.chance(0.5);
        boolean dropDiacritics = rng.chance(0.6 + laziness * 0.3);
        boolean punctuation = intelligence > 0.5 && !lowercase || rng.chance(0.25);
        double emojiRate = Math.max(0, sociability * 0.35 + rng.gaussian(0, 0.1));
        boolean abbreviate = laziness > 0.35 || rng.chance(0.4);
        int wpm = (int) Math.max(60, baseWpm * (0.7 + intelligence * 0.5 - laziness * 0.2)
                + rng.gaussian(0, 15));
        double exclamations = Math.max(0, sociability * 0.3 - patience * 0.15 + rng.gaussian(0.1, 0.08));

        return new ChatStyle(Math.min(0.15, typoRate), correctTypos, lowercase, dropDiacritics,
                punctuation, Math.min(0.5, emojiRate), abbreviate, wpm, Math.min(0.6, exclamations));
    }
}
