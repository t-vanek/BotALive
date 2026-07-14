package dev.botalive.api.bot;

/**
 * Životní cyklus bota od vytvoření po odstranění.
 *
 * <p>Přechody: {@code CREATED -> CONNECTING -> CONFIGURING -> SPAWNED <-> PAUSED},
 * z libovolného stavu lze přejít do {@code DISCONNECTED} (výpadek) a {@code REMOVED}
 * (trvalé odstranění). Z {@code DISCONNECTED} se bot může vrátit přes {@code CONNECTING}
 * (automatický reconnect).</p>
 */
public enum BotLifecycleState {

    /** Bot je vytvořen v paměti, ale ještě se nepřipojil na server. */
    CREATED,

    /** Probíhá TCP připojení a login fáze protokolu. */
    CONNECTING,

    /** Probíhá konfigurační fáze protokolu (registry, resource packy, ...). */
    CONFIGURING,

    /** Bot je plně přihlášen a naspawnovaný ve světě; AI běží. */
    SPAWNED,

    /** Bot je připojen, ale jeho AI je pozastavena administrátorem. */
    PAUSED,

    /** Spojení bylo ukončeno (kick, výpadek, vypnutí). */
    DISCONNECTED,

    /** Bot byl trvale odstraněn a nelze jej znovu použít. */
    REMOVED
}
