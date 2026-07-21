package dev.botalive.core.ai;

import java.util.EnumMap;
import java.util.Map;

/**
 * Pudy bota – sjednocená motivace à la Maslow (viz docs/BOT_LIFE.md).
 *
 * <p>Zatímco nálada a vitály jednotlivé cíle <b>zesilují</b> (strach → útěk,
 * únava → spánek), pudy dělají to, co dřív neuměl žádný subsystém:
 * <b>hierarchickou arbitráž</b>. Pět potřeb (bezpečí, jídlo, odpočinek,
 * společnost, seberealizace) je uspořádáno od nejfundamentálnější; když je
 * některá základní potřeba naléhavá, <b>tlumí cíle sloužící vyšším potřebám</b>.
 * Vyhladovělý, ohrožený bot tak nevyrazí na průzkum – i když by ho jednotlivé
 * signály samy neodradily, jejich <i>souběh</i> ano.</p>
 *
 * <p>Sytí je tělo (zdraví, hlad, hrozby), únava (z vitál) a samota (z nálady),
 * takže pudy sjednocují celou vrstvu vnitřního stavu. Osobnost a role nakláněly
 * baseline: odvážní a zvídaví (a dobrodruzi) na seberealizaci netlačí zpět tak
 * snadno ({@code esteemResolve}). V klidu (základní potřeby uspokojené) vrací
 * {@link #modulate(String)} přesně 1.0. Čistá, testovaná třída.</p>
 */
public final class BotDrives {

    /** Potřeby od nejfundamentálnější (Maslow) – pořadí určuje arbitráž. */
    public enum Drive { SAFETY, SUSTENANCE, REST, SOCIAL, ESTEEM }

    /** Síla suprese vyšších potřeb naléhavou fundamentální. */
    private static final double SUPPRESS = 0.6;

    /** Naléhavost, nad kterou se potřeba považuje za dominantní (pro popis). */
    private static final double DOMINANT_THRESHOLD = 0.4;

    /** Jen fundamentální potřeby tlumí vyšší (samota netlumí průzkum). */
    private static final Drive[] SUPPRESSORS = {Drive.SAFETY, Drive.SUSTENANCE, Drive.REST};

    /** Které potřebě cíl slouží (nezmíněné cíle jsou neutrální – netlumí se). */
    private static final Map<String, Drive> GOAL_DRIVE = Map.ofEntries(
            Map.entry("survive", Drive.SAFETY), Map.entry("escape", Drive.SAFETY),
            Map.entry("shelter", Drive.SAFETY), Map.entry("creeper-dodge", Drive.SAFETY),
            Map.entry("eat", Drive.SUSTENANCE), Map.entry("hunt", Drive.SUSTENANCE),
            Map.entry("farm", Drive.SUSTENANCE), Map.entry("fish", Drive.SUSTENANCE),
            Map.entry("sleep", Drive.REST), Map.entry("home", Drive.REST),
            Map.entry("socialize", Drive.SOCIAL), Map.entry("share", Drive.SOCIAL),
            Map.entry("trade", Drive.SOCIAL), Map.entry("follow", Drive.SOCIAL),
            Map.entry("reconcile", Drive.SOCIAL), Map.entry("sell", Drive.SOCIAL),
            Map.entry("buy", Drive.SOCIAL),
            Map.entry("explore", Drive.ESTEEM), Map.entry("nether", Drive.ESTEEM),
            Map.entry("end-travel", Drive.ESTEEM), Map.entry("dragon-fight", Drive.ESTEEM),
            Map.entry("wither-fight", Drive.ESTEEM), Map.entry("mine", Drive.ESTEEM),
            Map.entry("enchant", Drive.ESTEEM), Map.entry("house", Drive.ESTEEM),
            Map.entry("smith", Drive.ESTEEM), Map.entry("end-harvest", Drive.ESTEEM));

    private final EnumMap<Drive, Double> urgency = new EnumMap<>(Drive.class);
    private double esteemResolve;

    /** Nový bot má potřeby uspokojené. */
    public BotDrives() {
        for (Drive drive : Drive.values()) {
            urgency.put(drive, 0.0);
        }
    }

    /**
     * @param drive potřeba
     * @return naléhavost 0–1
     */
    public double urgency(Drive drive) {
        return urgency.get(drive);
    }

    /**
     * Přepočítá naléhavost potřeb z tělesných a vnitřních signálů.
     *
     * @param health         zdraví 0–20
     * @param food           sytost 0–20
     * @param hostilesNearby počet nepřátel v okolí
     * @param tiredness      únava 0–1 (z vitál; 0 když vypnuté)
     * @param loneliness     samota 0–1 (z nálady; 0 když vypnutá)
     * @param esteemResolve  odolnost seberealizace vůči supresi (osobnost/role)
     */
    public void update(double health, int food, int hostilesNearby, double tiredness,
                       double loneliness, double esteemResolve) {
        this.esteemResolve = clamp01(esteemResolve);
        double safety = health <= 6 ? 0.9 : health <= 10 ? 0.5 : 0.0;
        safety = Math.max(safety, Math.min(1.0, hostilesNearby * 0.3));
        urgency.put(Drive.SAFETY, clamp01(safety));
        urgency.put(Drive.SUSTENANCE, food >= 16 ? 0.0 : clamp01((16 - food) / 16.0));
        // Odpočinek naléhá až při výraznější únavě (energie pod ~50 %).
        urgency.put(Drive.REST, clamp01((tiredness - 0.5) / 0.5));
        urgency.put(Drive.SOCIAL, clamp01(loneliness));
        urgency.put(Drive.ESTEEM, 0.2); // stálá mírná touha po seberealizaci
    }

    /**
     * Násobič utility cíle podle hierarchie potřeb. Cíle vyšších potřeb se tlumí
     * úměrně naléhavosti nižších; cíle základního bezpečí se netlumí nikdy.
     *
     * @param goalId id cíle
     * @return arbitrážní násobič (1.0 pro neutrální cíle a uspokojené potřeby)
     */
    public double modulate(String goalId) {
        Drive tier = GOAL_DRIVE.get(goalId);
        if (tier == null) {
            return 1.0;
        }
        double multiplier = 1.0;
        for (Drive lower : SUPPRESSORS) {
            if (lower.ordinal() < tier.ordinal()) {
                multiplier *= 1.0 - urgency.get(lower) * SUPPRESS;
            }
        }
        // Odvážní/zvídaví se seberealizace nevzdávají tak snadno.
        if (tier == Drive.ESTEEM) {
            multiplier += (1.0 - multiplier) * esteemResolve;
        }
        return multiplier;
    }

    /**
     * @return nejnaléhavější potřeba nad prahem, nebo {@code null} (vyrovnaný bot)
     */
    public Drive dominant() {
        Drive best = null;
        double bestUrgency = DOMINANT_THRESHOLD;
        for (Drive drive : Drive.values()) {
            double value = urgency.get(drive);
            if (value > bestUrgency) {
                bestUrgency = value;
                best = drive;
            }
        }
        return best;
    }

    /**
     * @return krátký český popis nejnaléhavější potřeby pro diagnostiku
     */
    public String describe() {
        Drive dominant = dominant();
        if (dominant == null) {
            return "vyrovnané";
        }
        String need = switch (dominant) {
            case SAFETY -> "bezpečí";
            case SUSTENANCE -> "jídlo";
            case REST -> "odpočinek";
            case SOCIAL -> "společnost";
            case ESTEEM -> "seberealizaci";
        };
        return "touží po: " + need;
    }

    private static double clamp01(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
