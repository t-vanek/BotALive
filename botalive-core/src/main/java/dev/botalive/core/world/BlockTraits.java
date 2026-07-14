package dev.botalive.core.world;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumMap;
import java.util.Map;

/**
 * Předpočítané vlastnosti bloků pro fyziku a pathfinding.
 *
 * <p>Vlastnosti se počítají líně z {@link Material} (jednou na materiál) a cache
 * se sdílí mezi všemi boty. Díky tomu se ve vnitřní smyčce A* nedotýkáme Bukkit
 * API vůbec – jen této mapy.</p>
 *
 * @param passable  bot může stát/procházet prostorem bloku (vzduch, tráva, květiny)
 * @param solid     blok má plnou kolizi a dá se na něm stát
 * @param liquid    voda/láva
 * @param climbable žebřík/liána – bot může šplhat
 * @param door      dveře/vrata – průchozí po interakci
 * @param hazard    kontakt zraňuje nebo zabíjí (láva, oheň, kaktus, magma)
 * @param openable  lze otevřít interakcí (dveře, vrata, branky)
 */
public record BlockTraits(
        boolean passable,
        boolean solid,
        boolean liquid,
        boolean climbable,
        boolean door,
        boolean hazard,
        boolean openable
) {

    private static final Map<Material, BlockTraits> CACHE = new EnumMap<>(Material.class);

    /** Vzduch – nejčastější případ, sdílená instance. */
    public static final BlockTraits AIR = new BlockTraits(true, false, false, false, false, false, false);

    /** Neznámý/nenačtený blok – konzervativně neprůchozí. */
    public static final BlockTraits UNKNOWN = new BlockTraits(false, false, false, false, false, false, false);

    /**
     * @param material materiál bloku
     * @return vlastnosti bloku (cachované)
     */
    public static synchronized BlockTraits of(Material material) {
        if (material == null || material.isAir()) {
            return AIR;
        }
        return CACHE.computeIfAbsent(material, BlockTraits::compute);
    }

    private static BlockTraits compute(Material m) {
        boolean liquid = m == Material.WATER || m == Material.LAVA;
        boolean climbable = m == Material.LADDER || m == Material.VINE
                || m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT
                || m == Material.WEEPING_VINES || m == Material.WEEPING_VINES_PLANT
                || m == Material.SCAFFOLDING;
        boolean door = Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
        boolean hazard = m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.SWEET_BERRY_BUSH
                || m == Material.WITHER_ROSE || m == Material.POWDER_SNOW || m == Material.CAMPFIRE
                || m == Material.SOUL_CAMPFIRE;
        boolean openable = door || Tag.TRAPDOORS.isTagged(m);
        // occluding = plný neprůhledný blok; isSolid zahrnuje i ploty apod.
        boolean solid = m.isSolid() && !door && !Tag.TRAPDOORS.isTagged(m);
        // Ploty a zdi jsou "solid", ale bot přes ně nepřeskočí (výška 1.5) –
        // pro pathfinding je bereme jako plné bloky, na kterých se nedá stát
        // bezpečně plánovat; jednoduché a bezpečné.
        boolean passable = !solid && !liquid && !hazard;
        return new BlockTraits(passable, solid, liquid, climbable, door, hazard, openable);
    }
}
