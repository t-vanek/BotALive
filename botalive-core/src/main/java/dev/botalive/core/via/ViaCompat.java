package dev.botalive.core.via;

import org.bukkit.Bukkit;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;

/**
 * Kompatibilita verzí protokolu – podpora ViaVersion/ViaBackwards.
 *
 * <p>Boti mluví pevnou verzí protokolu (verze zabudované MCProtocolLib).
 * Hostitelský server může běžet na jiné verzi – pak musí mezi botem a serverem
 * překládat ViaVersion (bot novější než server), případně ViaVersion
 * + ViaBackwards (bot starší než server). Tahle třída rozdíl detekuje
 * a rozhodne, zda se klient botů vůbec dokáže připojit.</p>
 *
 * <p>Porovnáváme <b>čísla protokolu</b>, ne řetězce verzí: patch vydání
 * (např. 26.1 vs. 26.1.2) často sdílejí protokol a spojení funguje nativně
 * bez překladu – porovnání řetězců by je chybně označilo za nekompatibilní.
 * Jádro ({@link #assess}) je čistá funkce bez závislosti na Bukkitu,
 * aby šla testovat jednotkově.</p>
 */
public final class ViaCompat {

    /** Výsledek posouzení kompatibility verzí. */
    public enum Status {
        /** Protokoly se shodují – žádný překlad není potřeba. */
        NATIVE_MATCH,
        /** Bot je novější než server; ViaVersion je nainstalován a překládá. */
        TRANSLATED_BY_VIAVERSION,
        /** Bot je starší než server; ViaVersion + ViaBackwards překládají. */
        TRANSLATED_BY_VIABACKWARDS,
        /** Bot je novější než server a ViaVersion chybí – login selže. */
        MISSING_VIAVERSION,
        /** Bot je starší než server a ViaBackwards (příp. i ViaVersion) chybí – login selže. */
        MISSING_VIABACKWARDS,
        /** Cizí server – jeho verzi ani pluginy odtud nezjistíme (rozhodne login). */
        UNKNOWN_TARGET
    }

    /**
     * Posouzení kompatibility s lidsky čitelným vysvětlením.
     *
     * @param status        kategorie výsledku
     * @param botVersion    verze Minecraftu, kterou mluví klient botů
     * @param serverVersion verze cílového serveru ({@code ?} u cizího serveru)
     * @param message       česká zpráva pro log/administrátora (co se děje a co s tím)
     */
    public record Assessment(Status status, String botVersion, String serverVersion,
                             String message) {

        /** @return {@code true} pokud má login šanci uspět (příp. to nevíme jistě) */
        public boolean connectable() {
            return status != Status.MISSING_VIAVERSION && status != Status.MISSING_VIABACKWARDS;
        }

        /** @return {@code true} pokud mezi botem a serverem překládá Via */
        public boolean translated() {
            return status == Status.TRANSLATED_BY_VIAVERSION
                    || status == Status.TRANSLATED_BY_VIABACKWARDS;
        }
    }

    private ViaCompat() {
    }

    /** @return verze Minecraftu, kterou mluví klient botů (dle MCProtocolLib) */
    public static String botVersion() {
        return MinecraftCodec.CODEC.getMinecraftVersion();
    }

    /** @return číslo protokolu klienta botů (dle MCProtocolLib) */
    public static int botProtocol() {
        return MinecraftCodec.CODEC.getProtocolVersion();
    }

    /**
     * Lidsky čitelný kontrakt podporovaných verzí serveru.
     *
     * <p>Boti mluví nativně verzí zabudované MCProtocolLib ({@link #botVersion()}) –
     * to je podporovaný baseline. Servery <b>novější</b> než baseline potřebují na
     * hostiteli ViaVersion + ViaBackwards, <b>starší</b> ViaVersion; při shodě
     * protokolu se nepřekládá nic. Řetězec se loguje při startu, ať admin zná
     * podporovaný rozsah, a je jediným zdrojem té věty (log, diagnostika).</p>
     *
     * @return věta „boti cílí nativně na verzi X; novější → ViaVersion+ViaBackwards, …"
     */
    public static String supportContract() {
        String baseline = botVersion();
        return "Boti cílí nativně na Minecraft " + baseline + " (protokol " + botProtocol()
                + "). Server " + baseline + " funguje bez překladu; novější verze vyžadují na"
                + " serveru ViaVersion + ViaBackwards, starší ViaVersion.";
    }

    /**
     * Posoudí kompatibilitu botů s <b>tímto</b> (hostitelským) serverem.
     *
     * @return posouzení včetně detekce nainstalovaných Via pluginů
     */
    @SuppressWarnings("deprecation") // UnsafeValues – jediný zdroj čísla protokolu serveru
    public static Assessment assessLocalServer() {
        return assess(Bukkit.getUnsafe().getProtocolVersion(), Bukkit.getMinecraftVersion(),
                botProtocol(), botVersion(),
                Bukkit.getPluginManager().getPlugin("ViaVersion") != null,
                Bukkit.getPluginManager().getPlugin("ViaBackwards") != null);
    }

    /**
     * Posouzení pro cizí server: verzi ani pluginy vzdáleného serveru odtud
     * zjistit nejde, rozhodne až login.
     *
     * @param host adresa cílového serveru (jen pro zprávu)
     * @return posouzení {@link Status#UNKNOWN_TARGET}
     */
    public static Assessment remoteTarget(String host) {
        return new Assessment(Status.UNKNOWN_TARGET, botVersion(), "?",
                "Boti míří na cizí server (" + host + ") a mluví verzí " + botVersion()
                        + ". Cílový server musí běžet na stejné verzi, nebo mít ViaVersion"
                        + " (server starší), příp. ViaVersion + ViaBackwards (server novější).");
    }

    /**
     * Čisté jádro posouzení – bez Bukkitu, jednotkově testovatelné.
     *
     * @param serverProtocol číslo protokolu serveru
     * @param serverVersion  verze serveru (pro zprávu)
     * @param botProtocol    číslo protokolu klienta botů
     * @param botVersion     verze klienta botů (pro zprávu)
     * @param viaVersion     je na serveru ViaVersion?
     * @param viaBackwards   je na serveru ViaBackwards?
     * @return posouzení kompatibility
     */
    static Assessment assess(int serverProtocol, String serverVersion,
                             int botProtocol, String botVersion,
                             boolean viaVersion, boolean viaBackwards) {
        if (serverProtocol == botProtocol) {
            return new Assessment(Status.NATIVE_MATCH, botVersion, serverVersion,
                    "Klient botů (" + botVersion + ") a server (" + serverVersion
                            + ") sdílejí protokol " + botProtocol + " – překlad není potřeba.");
        }
        if (botProtocol > serverProtocol) {
            // Bot novější než server → stačí ViaVersion.
            if (viaVersion) {
                return new Assessment(Status.TRANSLATED_BY_VIAVERSION, botVersion, serverVersion,
                        "Klient botů (" + botVersion + ") je novější než server ("
                                + serverVersion + ") – překládá ViaVersion.");
            }
            return new Assessment(Status.MISSING_VIAVERSION, botVersion, serverVersion,
                    "Klient botů mluví verzí " + botVersion + ", server běží na " + serverVersion
                            + " – nainstalujte plugin ViaVersion, jinak se boti nepřipojí.");
        }
        // Bot starší než server → ViaVersion + ViaBackwards.
        if (viaVersion && viaBackwards) {
            return new Assessment(Status.TRANSLATED_BY_VIABACKWARDS, botVersion, serverVersion,
                    "Klient botů (" + botVersion + ") je starší než server (" + serverVersion
                            + ") – překládají ViaVersion + ViaBackwards.");
        }
        String missing = !viaVersion && !viaBackwards ? "ViaVersion a ViaBackwards"
                : (viaVersion ? "ViaBackwards" : "ViaVersion");
        return new Assessment(Status.MISSING_VIABACKWARDS, botVersion, serverVersion,
                "Klient botů mluví verzí " + botVersion + ", server běží na novější "
                        + serverVersion + " – nainstalujte " + missing
                        + ", jinak se boti nepřipojí.");
    }
}
