package dev.botalive.api.bot;

import java.util.UUID;

/**
 * Momentka entity, kterou bot „vidí" (klientský pohled rekonstruovaný z paketů
 * serveru). Nemutabilní datový typ pro veřejné API.
 *
 * @param id       síťové id entity (použije se pro {@link BotControl#attack(int)})
 * @param uuid     UUID entity ({@code null} u některých neživých entit)
 * @param type     typ entity jako řetězec (např. {@code "ZOMBIE"}, {@code "PLAYER"})
 * @param position poslední známá pozice entity
 * @param player   {@code true} pokud jde o hráče nebo jiného bota
 * @param hostile  {@code true} pokud jde o vždy nepřátelského moba (zombie,
 *                 kostlivec, creeper…); neutrální druhy (enderman, piglin)
 *                 zde {@code false} – útočí se na ně jen odvetou
 */
public record NearbyEntity(int id, UUID uuid, String type, Position position,
                           boolean player, boolean hostile) {
}
