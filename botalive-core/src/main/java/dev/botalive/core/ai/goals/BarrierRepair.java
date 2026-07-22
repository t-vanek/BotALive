package dev.botalive.core.ai.goals;

import dev.botalive.core.build.Enclosure;

/**
 * Naléhavost opravy bariéry – čisté funkce, jádro prioritizace (a proto snadno
 * testovatelné). Naléhavost se zapéká do <b>raw utility</b> cíle (jako
 * {@code SurviveGoal}/{@code EatGoal} škálují podle nouze); drives ji zvednout
 * neumí, role weight ji pak dolaďuje (kdo co opravuje).
 *
 * <p>Uspořádání priorit (raw, platí i pro generalistu): <b>hradby za soumraku
 * &gt; ohrada zvířat &gt; hradby ve dne ≈ plot domu &gt; nová stavba</b>. Tedy
 * „opravit pen dřív než plot domu" a „blíží se noc → nejdřív hradby". Čísla drží
 * pod „opravdovým přežitím" (eat při hladu, survive), i po zesílení rolí.</p>
 */
final class BarrierRepair {

    /**
     * Nad tolik chybějících sloupců to už není oprava pár děr, ale (pře)stavba –
     * bere se jako běžná stavba (denní, nízká priorita), ne urgentní oprava.
     */
    static final int MAX_GAPS = 8;

    private BarrierRepair() {
    }

    /**
     * Je bariéra <b>poškozená</b> (pár děr v jinak stojící), ne rozestavěná?
     *
     * @param a ohodnocení obvodu
     * @return {@code true} když chybí málo sloupců a většina stojí
     */
    static boolean isDamaged(Enclosure.Assessment a) {
        return a.missing() > 0 && a.missing() <= MAX_GAPS && a.standing() > a.missing();
    }

    /**
     * Naléhavost opravy hradby: vysoká za soumraku/v noci (obrana sídla), jinak
     * mírná (ve dne, když je klid).
     *
     * @param a     ohodnocení obvodu
     * @param night blíží se noc / je noc
     * @return raw utility opravy
     */
    static double wallUrgency(Enclosure.Assessment a, boolean night) {
        return (night ? 34 : 12) + a.missingRatio() * (night ? 8 : 6);
    }

    /**
     * Naléhavost opravy ohrady zvířat (ať neutečou) – nad plotem domu, den i noc.
     *
     * @param a ohodnocení obvodu
     * @return raw utility opravy
     */
    static double penUrgency(Enclosure.Assessment a) {
        return 24 + a.missingRatio() * 10;
    }

    /**
     * Naléhavost opravy plotu domu – mírná (pod ohradou zvířat), jen ve dne.
     *
     * @param a ohodnocení obvodu
     * @return raw utility opravy
     */
    static double houseFenceUrgency(Enclosure.Assessment a) {
        return 10 + a.missingRatio() * 6;
    }
}
