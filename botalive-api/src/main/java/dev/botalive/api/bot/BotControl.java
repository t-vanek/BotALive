package dev.botalive.api.bot;

import dev.botalive.api.task.BotTask;

import java.util.List;

/**
 * Bezpečné akční rozhraní bota pro cizí AI cíle ({@link dev.botalive.api.ai.Goal}).
 *
 * <p>Tenké API {@link Bot} umí bota jen pozorovat a dávat mu hrubé povely
 * (mluv, teleportuj se). Skutečné chování – jít někam, rozhlédnout se, zaútočit,
 * použít item – potřebuje přístup k vnitřním subsystémům (navigace, akce,
 * inventář, pohled na svět). Ty ale žijí v implementačním modulu a cizí plugin
 * by na ně nesměl sahat. {@code BotControl} je jejich <b>kurátorovaná, stabilní
 * fasáda</b>: cizí cíl si ji vezme přes {@link Bot#control()} a řídí bota, aniž
 * by závisel na implementaci.</p>
 *
 * <p><b>Vláknový kontrakt.</b> Metody se volají z <b>tick vlákna bota</b> –
 * typicky z {@link dev.botalive.api.ai.Goal#tick(Bot)} aktivního cíle. Odtud
 * jsou bezpečné a bez zámků. Nevolejte je z Bukkit event handlerů ani z jiných
 * vláken.</p>
 *
 * <p><b>Model záměru.</b> Pohyb je <i>záměr</i>, ne teleport: {@link #navigateTo}
 * jen zadá cíl a tick smyčka bota ho každý tick vykonává (pathfinding, fyzika).
 * Cíl proto typicky zavolá {@code navigateTo} jednou a pak každý tick jen
 * kontroluje {@link #navigating()}. Akce (útok, kopání) jdou přes protokol
 * a server je validuje stejně jako u člověka.</p>
 */
public interface BotControl {

    /** @return API pohled na tohoto bota */
    Bot bot();

    // ------------------------------------------------------------------ vnímání

    /** @return aktuální pozice bota (nohy) */
    Position position();

    /** @return zdraví bota (0–20) */
    double health();

    /** @return sytost/hlad bota (0–20) */
    int food();

    /** @return {@code true} pokud bot stojí na zemi */
    boolean onGround();

    /** @return herní čas světa (0–23999), nebo −1 pokud ještě neznámý */
    long worldTime();

    /** @return prší ve světě bota? */
    boolean raining();

    /** @return je ve světě bota bouřka? */
    boolean thundering();

    /** @return název Bukkit světa, ve kterém bot je, nebo {@code null} před spawnem */
    String worldName();

    /**
     * @param x blok X
     * @param y blok Y
     * @param z blok Z
     * @return materiál bloku jako řetězec (např. {@code "STONE"}), nebo
     *         {@code null} pokud chunk zatím není načtený
     */
    String blockAt(int x, int y, int z);

    /**
     * @param x blok X
     * @param y blok Y
     * @param z blok Z
     * @return {@code true} pokud má blok podstatnou kolizi a dá se na něm stát
     */
    boolean isSolid(int x, int y, int z);

    /**
     * @param x blok X
     * @param y blok Y
     * @param z blok Z
     * @return {@code true} pokud je blok tekutina (voda/láva)
     */
    boolean isLiquid(int x, int y, int z);

    /**
     * @param x blok X
     * @param y blok Y
     * @param z blok Z
     * @return {@code true} pokud lze prostorem bloku projít/stát v něm (vzduch,
     *         tráva, květiny)
     */
    boolean isPassable(int x, int y, int z);

    /**
     * Entity v okolí bota, seřazené od nejbližší.
     *
     * @param radius poloměr hledání (bloky)
     * @return seznam viditelných entit (může být prázdný)
     */
    List<NearbyEntity> nearbyEntities(double radius);

    // ---------------------------------------------------------------- navigace

    /**
     * Zadá cíl navigace. Cesta se počítá asynchronně a tick smyčka bota ho
     * pak vykonává (chůze, skoky, obcházení překážek). Nová hodnota přebije
     * předchozí cíl.
     *
     * @param x cílový blok X
     * @param y cílový blok Y
     * @param z cílový blok Z
     */
    void navigateTo(int x, int y, int z);

    /** @return {@code true} pokud bot právě někam směřuje (i než se cesta dopočítá) */
    boolean navigating();

    /** Zruší navigaci – bot zůstane stát. */
    void stopNavigation();

    // ------------------------------------------------------ taktická primitiva

    /**
     * Vytvoří task „vytěž blok" – zaměření, kopání po správnou dobu (server-side
     * odhad podle drženého nástroje a efektů) a ověření zmizení bloku. Vhodné
     * předtím {@link #selectBestTool(int, int, int)}. Task drží stav – tikejte
     * ho z {@link dev.botalive.api.ai.Goal#tick(Bot)}, dokud nevrátí hotovo.
     *
     * @param x blok X
     * @param y blok Y
     * @param z blok Z
     * @return nový task
     */
    BotTask mineBlock(int x, int y, int z);

    /**
     * Vytvoří task „polož blok" – vezme materiál do ruky a položí ho na cílovou
     * pozici (opře se o pevného souseda), jako hráč pravým klikem.
     *
     * @param x        blok X
     * @param y        blok Y
     * @param z        blok Z
     * @param material název materiálu k položení (např. {@code "COBBLESTONE"});
     *                bot ho musí mít v inventáři
     * @return nový task
     */
    BotTask placeBlock(int x, int y, int z, String material);

    /**
     * Vytvoří task „dojdi na pozici" – spustí navigaci a je hotový, jakmile bot
     * dorazí (nebo se navigace vzdá). Zkratka nad {@link #navigateTo(int, int, int)}.
     *
     * @param x cílový blok X
     * @param y cílový blok Y
     * @param z cílový blok Z
     * @return nový task
     */
    BotTask walkTo(int x, int y, int z);

    // ------------------------------------------------------------ pohled a akce

    /**
     * Otočí hlavu bota k bodu (prochází humanizací – omezená úhlová rychlost,
     * easing, drobná chyba míření). Pro plynulé sledování volejte každý tick.
     *
     * @param x cílový bod X
     * @param y cílový bod Y
     * @param z cílový bod Z
     */
    void lookAt(double x, double y, double z);

    /**
     * Zaútočí na entitu (musí být v dosahu, jinak to server zahodí).
     *
     * @param entityId síťové id cíle (z {@link NearbyEntity#id()})
     */
    void attack(int entityId);

    /** Máchne hlavní rukou (animace bez cíle). */
    void swingArm();

    /**
     * Vybere hotbar slot (drženou věc).
     *
     * @param index 0–8
     */
    void selectHotbarSlot(int index);

    /** Použije držený item „do vzduchu" (jídlo, natažení luku, hod…). */
    void useItem();

    // -------------------------------------------------------------- inventář

    /**
     * @param materialName název materiálu (např. {@code "COBBLESTONE"} nebo
     *                     {@code "minecraft:cobblestone"})
     * @param count        požadovaný počet kusů
     * @return {@code true} pokud má bot alespoň {@code count} kusů daného
     *         materiálu (napříč celým inventářem); {@code false} i tehdy,
     *         když snapshot inventáře ještě není k dispozici
     */
    boolean hasItem(String materialName, int count);

    /**
     * Vezme do ruky nejvhodnější nástroj na daný blok (krumpáč na kámen,
     * sekera na dřevo…), pokud ho bot má.
     *
     * @param x blok X
     * @param y blok Y
     * @param z blok Z
     * @return {@code true} pokud se podařilo nasadit vhodný nástroj
     */
    boolean selectBestTool(int x, int y, int z);

    // ----------------------------------------------------------------- řeč

    /**
     * Nechá bota promluvit do chatu (prochází humanizací – doba přemýšlení,
     * psaní, překlepy). Zkratka za {@link Bot#say(String)}.
     *
     * @param message text zprávy
     */
    void say(String message);
}
