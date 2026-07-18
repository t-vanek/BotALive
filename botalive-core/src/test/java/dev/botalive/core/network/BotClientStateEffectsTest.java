package dev.botalive.core.network;

import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy sledování lektvarových efektů z paketů.
 */
class BotClientStateEffectsTest {

    @Test
    void efektPlatiPoAplikacniDobu() {
        BotClientState state = new BotClientState();
        assertFalse(state.effectActive(Effect.FIRE_RESISTANCE));
        state.effectApplied(Effect.FIRE_RESISTANCE, 20 * 60); // 60 s
        assertTrue(state.effectActive(Effect.FIRE_RESISTANCE));
        assertFalse(state.effectActive(Effect.REGENERATION), "jiný efekt neběží");
    }

    @Test
    void vyprsenyEfektJeNeaktivni() {
        BotClientState state = new BotClientState();
        state.effectApplied(Effect.REGENERATION, 0); // nulové trvání = hned pryč
        assertFalse(state.effectActive(Effect.REGENERATION));
    }

    @Test
    void nekonecnyEfektNevyprsi() {
        BotClientState state = new BotClientState();
        state.effectApplied(Effect.NIGHT_VISION, -1); // protokol: -1 = nekonečno
        assertTrue(state.effectActive(Effect.NIGHT_VISION));
    }

    @Test
    void odebraniAVycisteniEfektu() {
        BotClientState state = new BotClientState();
        state.effectApplied(Effect.FIRE_RESISTANCE, 20 * 60);
        state.effectRemoved(Effect.FIRE_RESISTANCE);
        assertFalse(state.effectActive(Effect.FIRE_RESISTANCE));

        state.effectApplied(Effect.SPEED, 20 * 60);
        state.clearEffects();
        assertFalse(state.effectActive(Effect.SPEED));
    }
}
