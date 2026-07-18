package dev.botalive.core.ai;

import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/**
 * Adresná prosba o rozdání věcí z chatu („dej mi dřevo", „hoď mi jídlo").
 *
 * <p>Chat ji zapíše botovi ({@code BotImpl}) a vynutí cíl {@code share};
 * {@code ShareGoal} si ji při startu vyzvedne a místo náhodného obdarování
 * nejbližšího hráče dojde za prosícím a upustí, o co si řekl.</p>
 *
 * @param requester kdo prosí (UUID hráče/bota)
 * @param materials žádané materiály; prázdný seznam = jídlo (cokoli k snědku)
 */
public record ShareRequest(UUID requester, List<Material> materials) {

    /** @return {@code true} pokud jde o prosbu o jídlo (bez konkrétních itemů) */
    public boolean foodOnly() {
        return materials.isEmpty();
    }
}
