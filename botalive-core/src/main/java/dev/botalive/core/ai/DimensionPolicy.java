package dev.botalive.core.ai;

import dev.botalive.core.world.WorldDimension;

import java.util.Map;

/**
 * Gating cílů podle dimenze – co v Endu (a Netheru) nedává smysl nebo zabíjí.
 *
 * <p>Aplikuje se centrálně v {@link Brain} (běžný výběr i vynucené cíle):
 * násobič 0 cíl vypíná, hodnoty pod 1 tlumí. Kriticky: <b>postel v Endu
 * i Netheru exploduje</b> – spánek tam musí být vypnutý vždy. Dál se v Endu
 * nestaví domy ani vesnice (End je výprava, ne domov), nefarmaří, neloví
 * zvěř ani nerybaří (není kde) a vypnuté jsou i cíle navigující na vzpomínky
 * z jiného světa (truhly, domov). Denní rytmus mimo overworld neplatí –
 * v Endu není den a noc.</p>
 *
 * <p>End cíle si dimenzi hlídají i samy v utility (levný early-return);
 * tabulka je drží vypnuté i kdyby se jejich gating v budoucnu změnil –
 * záměrná pojistka, ne omyl. Čistá tabulka bez závislostí – jednotkově
 * testovatelná.</p>
 */
public final class DimensionPolicy {

    /** Cíle vypnuté/tlumené v Endu. */
    private static final Map<String, Double> END = Map.ofEntries(
            Map.entry("sleep", 0.0),     // postel exploduje!
            Map.entry("shelter", 0.0),   // úkryt nechrání před endermany
            Map.entry("house", 0.0),     // v Endu se nebydlí
            Map.entry("maintain", 0.0),
            Map.entry("farm", 0.0),
            Map.entry("compost", 0.0),
            Map.entry("fish", 0.0),      // není voda
            Map.entry("boat", 0.0),
            Map.entry("minecart", 0.0),
            Map.entry("trade", 0.0),     // nejsou vesničané
            Map.entry("tame", 0.0),
            Map.entry("hunt", 0.0),      // není zvěř
            Map.entry("guard", 0.0),
            Map.entry("reconcile", 0.0),
            Map.entry("home", 0.0),      // domov je v jiném světě
            Map.entry("stash", 0.0),     // truhly z paměti jsou v jiném světě
            Map.entry("steal", 0.0),
            Map.entry("rob", 0.0),
            Map.entry("mine", 0.0),      // v Endu nejsou rudy – end stone řeší end-harvest
            Map.entry("enchant", 0.0),
            Map.entry("smelt", 0.4),     // tavení mid-výpravy jen z nouze
            Map.entry("explore", 0.5),   // opatrně, kolem je void
            Map.entry("end-travel", 0.0), // už tam je
            Map.entry("nether", 0.0),     // výprava do Netheru se plánuje doma
            Map.entry("smith", 0.0)       // kovářský stůl do Endu nepatří
    );

    /** Cíle vypnuté v Netheru (boti se tam zatím dostávají jen teleportem). */
    private static final Map<String, Double> NETHER = Map.ofEntries(
            Map.entry("sleep", 0.0),     // postel exploduje!
            Map.entry("fish", 0.0),      // voda se vypařuje
            Map.entry("boat", 0.0),
            Map.entry("farm", 0.0),
            Map.entry("compost", 0.0),
            Map.entry("house", 0.0),
            Map.entry("maintain", 0.0),
            Map.entry("home", 0.0),
            Map.entry("stash", 0.0),
            Map.entry("steal", 0.0),
            Map.entry("tame", 0.0),
            Map.entry("trade", 0.0),
            Map.entry("end-travel", 0.0),
            Map.entry("dragon-fight", 0.0),
            Map.entry("end-harvest", 0.0),
            Map.entry("end-return", 0.0)
    );

    /** Cíle, které mají smysl jen v Endu. */
    private static final Map<String, Double> OVERWORLD = Map.of(
            "dragon-fight", 0.0,
            "end-harvest", 0.0,
            "end-return", 0.0
    );

    private DimensionPolicy() {
    }

    /**
     * Násobič utility cíle v dané dimenzi.
     *
     * @param goalId    id cíle
     * @param dimension dimenze světa bota
     * @return násobič (1.0 = bez omezení, 0 = cíl v dimenzi vypnutý)
     */
    public static double weight(String goalId, WorldDimension dimension) {
        return switch (dimension) {
            case END -> END.getOrDefault(goalId, 1.0);
            case NETHER -> NETHER.getOrDefault(goalId, 1.0);
            // UNKNOWN = před spawnem; chová se overworldově (cíle beztak neběží).
            case OVERWORLD, UNKNOWN -> OVERWORLD.getOrDefault(goalId, 1.0);
        };
    }

    /**
     * Platí v dimenzi denní rytmus? V Endu (a Netheru) není den a noc,
     * rytmus by tam boty nesmyslně honil „domů na noc".
     *
     * @param dimension dimenze
     * @return {@code true} jen v overworldu
     */
    public static boolean rhythmApplies(WorldDimension dimension) {
        return dimension == WorldDimension.OVERWORLD || dimension == WorldDimension.UNKNOWN;
    }
}
