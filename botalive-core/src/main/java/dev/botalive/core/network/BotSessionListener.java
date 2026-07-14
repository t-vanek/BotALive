package dev.botalive.core.network;

import dev.botalive.core.entity.EntityTracker;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.ClientInventory;
import dev.botalive.core.util.Vec3;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Překlad příchozích paketů na události jádra bota.
 *
 * <p>Login a konfigurační fázi (registry, known packs, keep-alive) obsluhují
 * výchozí listenery MCProtocolLib; tady zpracováváme už jen PLAY fázi. Metoda
 * běží na paketovém vlákně bota – nesmí blokovat, jen aktualizuje thread-safe
 * struktury ({@link BotClientState}, {@link EntityTracker}, {@link ClientInventory})
 * a notifikuje {@link NetworkEvents}.</p>
 */
public final class BotSessionListener extends SessionAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BotSessionListener.class);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final BotClientState state;
    private final EntityTracker entities;
    private final ClientInventory inventory;
    private final NetworkEvents events;
    private final String botName;

    /** Klientský world model (jen v režimu {@code packet}, jinak {@code null}). */
    private final dev.botalive.core.world.PacketWorldManager packetWorlds;

    /**
     * @param botName      jméno bota (pro logy)
     * @param state        protokolový stav bota
     * @param entities     tracker viditelných entit
     * @param inventory    klientský model inventáře
     * @param events       callback do jádra bota
     * @param packetWorlds klientský world model ({@code null} v režimu server)
     */
    public BotSessionListener(String botName, BotClientState state, EntityTracker entities,
                              ClientInventory inventory, NetworkEvents events,
                              dev.botalive.core.world.PacketWorldManager packetWorlds) {
        this.botName = botName;
        this.state = state;
        this.entities = entities;
        this.inventory = inventory;
        this.events = events;
        this.packetWorlds = packetWorlds;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        try {
            switch (packet) {
                case ClientboundLoginPacket p -> handleLogin(p);
                case ClientboundPlayerPositionPacket p -> handleTeleport(session, p);
                case ClientboundSetHealthPacket p -> handleHealth(p);
                case ClientboundPlayerCombatKillPacket p -> handleDeath(p);
                case ClientboundRespawnPacket p -> handleRespawn(p);
                case ClientboundAddEntityPacket p -> handleAddEntity(p);
                case ClientboundRemoveEntitiesPacket p -> entities.remove(p.getEntityIds());
                case ClientboundMoveEntityPosPacket p ->
                        entities.byId(p.getEntityId()).ifPresent(e -> e.moveBy(p.getMoveX(), p.getMoveY(), p.getMoveZ()));
                case ClientboundMoveEntityPosRotPacket p ->
                        entities.byId(p.getEntityId()).ifPresent(e -> {
                            e.moveBy(p.getMoveX(), p.getMoveY(), p.getMoveZ());
                            e.setRotation(p.getYaw(), p.getPitch());
                        });
                case ClientboundMoveEntityRotPacket p ->
                        entities.byId(p.getEntityId()).ifPresent(e -> e.setRotation(p.getYaw(), p.getPitch()));
                case ClientboundTeleportEntityPacket p ->
                        entities.byId(p.getId()).ifPresent(e -> e.setPosition(
                                new Vec3(p.getPosition().getX(), p.getPosition().getY(), p.getPosition().getZ()),
                                p.getYRot(), p.getXRot()));
                case ClientboundSetEntityMotionPacket p -> handleMotion(p);
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity
                        .ClientboundSetPassengersPacket p -> handlePassengers(p);
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity
                        .ClientboundMoveVehiclePacket p -> {
                    if (state.vehicleId() >= 0) {
                        events.onVehicleMove(new Vec3(p.getPosition().getX(),
                                p.getPosition().getY(), p.getPosition().getZ()), p.getYRot());
                    }
                }
                case ClientboundContainerSetContentPacket p -> {
                    if (p.getContainerId() == 0) {
                        inventory.setContents(p.getItems());
                    }
                }
                case ClientboundContainerSetSlotPacket p -> {
                    if (p.getContainerId() == 0) {
                        inventory.setSlot(p.getSlot(), p.getItem());
                    }
                }
                case ClientboundSetHeldSlotPacket p -> state.heldSlot(p.getSlot());
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory
                        .ClientboundOpenScreenPacket p -> state.openContainerId(p.getContainerId());
                case ClientboundPlayerChatPacket p -> handleChat(p);
                // Klientský world model (jen v režimu packet).
                case org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound
                        .ClientboundRegistryDataPacket p -> {
                    if (packetWorlds != null) {
                        packetWorlds.onRegistryData(p.getRegistry(), p.getEntries());
                    }
                }
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level
                        .ClientboundLevelChunkWithLightPacket p -> {
                    if (packetWorlds != null) {
                        packetWorlds.onChunk(p);
                    }
                }
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level
                        .ClientboundBlockUpdatePacket p -> {
                    if (packetWorlds != null) {
                        packetWorlds.onBlockUpdate(p);
                    }
                }
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level
                        .ClientboundSectionBlocksUpdatePacket p -> {
                    if (packetWorlds != null) {
                        packetWorlds.onSectionUpdate(p);
                    }
                }
                case org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level
                        .ClientboundForgetLevelChunkPacket p -> {
                    if (packetWorlds != null) {
                        packetWorlds.onForgetChunk(p);
                    }
                }
                default -> {
                    // Ostatní pakety (světlo, čas, zvuky, ...) bot nepotřebuje.
                }
            }
        } catch (Throwable t) {
            // Nikdy nenechat výjimku probublat do netty pipeline – zabila by spojení.
            LOG.error("[{}] Chyba při zpracování paketu {}", botName, packet.getClass().getSimpleName(), t);
        }
    }

    @Override
    public void disconnected(DisconnectedEvent event) {
        String reason = PLAIN.serializeOr(event.getReason(), "neznámý důvod");
        if (event.getCause() != null) {
            LOG.warn("[{}] Odpojen: {} ({})", botName, reason, event.getCause().toString());
        } else {
            LOG.info("[{}] Odpojen: {}", botName, reason);
        }
        state.reset();
        entities.clear();
        inventory.clear();
        events.onDisconnected(reason);
    }

    private void handleLogin(ClientboundLoginPacket packet) {
        state.entityId(packet.getEntityId());
        String worldKey = packet.getCommonPlayerSpawnInfo().getWorldName().asString();
        state.worldKey(worldKey);
        if (packetWorlds != null) {
            packetWorlds.dimension(packet.getCommonPlayerSpawnInfo().getDimension());
        }
        LOG.debug("[{}] Login dokončen, entityId={}, world={}", botName, packet.getEntityId(), worldKey);
        events.onLogin(packet.getEntityId(), worldKey);
    }

    private void handleTeleport(Session session, ClientboundPlayerPositionPacket packet) {
        // Accept posíláme okamžitě (vanilla klient dělá totéž) – server jinak
        // bota po pár sekundách vykopne za "teleport confirm timeout".
        session.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
        List<org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement> relatives =
                packet.getRelatives();
        TeleportSync sync = new TeleportSync(
                packet.getId(),
                new Vec3(packet.getPosition().getX(), packet.getPosition().getY(), packet.getPosition().getZ()),
                new Vec3(packet.getDeltaMovement().getX(), packet.getDeltaMovement().getY(), packet.getDeltaMovement().getZ()),
                packet.getYRot(), packet.getXRot(),
                relatives);
        state.queueTeleport(sync);
        events.onTeleport(sync);
    }

    private void handleHealth(ClientboundSetHealthPacket packet) {
        boolean wasAlive = state.health() > 0;
        state.updateVitals(packet.getHealth(), packet.getFood(), packet.getSaturation());
        if (wasAlive && packet.getHealth() <= 0 && !state.dead()) {
            state.dead(true);
            events.onDeath("zdraví kleslo na nulu");
        }
    }

    private void handleDeath(ClientboundPlayerCombatKillPacket packet) {
        if (packet.getPlayerId() == state.entityId() && !state.dead()) {
            state.dead(true);
            Component message = packet.getMessage();
            events.onDeath(message != null ? PLAIN.serialize(message) : "smrt");
        }
    }

    private void handleRespawn(ClientboundRespawnPacket packet) {
        String worldKey = packet.getCommonPlayerSpawnInfo().getWorldName().asString();
        boolean afterDeath = state.dead(); // před resetem – odlišuje smrt od portálu
        state.worldKey(worldKey);
        state.dead(false);
        state.vehicleId(-1);
        entities.clear();
        if (packetWorlds != null) {
            packetWorlds.dimension(packet.getCommonPlayerSpawnInfo().getDimension());
        }
        events.onRespawn(worldKey, afterDeath);
    }

    private void handleAddEntity(ClientboundAddEntityPacket packet) {
        TrackedEntity entity = new TrackedEntity(packet.getEntityId(), packet.getUuid(), packet.getType(),
                new Vec3(packet.getX(), packet.getY(), packet.getZ()));
        entity.setRotation(packet.getYaw(), packet.getPitch());
        entities.add(entity);
    }

    /** Sleduje, zda bot sedí ve vozidle (server je autoritou nad pasažéry). */
    private void handlePassengers(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                                          .entity.ClientboundSetPassengersPacket packet) {
        int self = state.entityId();
        boolean containsSelf = false;
        for (int passengerId : packet.getPassengerIds()) {
            if (passengerId == self) {
                containsSelf = true;
                break;
            }
        }
        if (containsSelf) {
            state.vehicleId(packet.getEntityId());
        } else if (state.vehicleId() == packet.getEntityId()) {
            state.vehicleId(-1);
        }
    }

    private void handleMotion(ClientboundSetEntityMotionPacket packet) {
        if (packet.getEntityId() == state.entityId()) {
            events.onKnockback(new Vec3(packet.getMovement().getX(),
                    packet.getMovement().getY(), packet.getMovement().getZ()));
        }
    }

    private void handleChat(ClientboundPlayerChatPacket packet) {
        String content = packet.getContent();
        if (content == null && packet.getUnsignedContent() != null) {
            content = PLAIN.serialize(packet.getUnsignedContent());
        }
        if (content == null || content.isBlank()) {
            return;
        }
        String senderName = packet.getName() != null ? PLAIN.serialize(packet.getName()) : "?";
        events.onPlayerChat(packet.getSender(), senderName, content);
    }
}
