package dev.botalive.core.network;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Session listener, který za bota odpovídá na login-plugin dotaz Velocity
 * modern forwardingu.
 *
 * <p>Přidává se do session jen když je forwarding zapnutý. Během login fáze
 * pošle offline-mode backend za Velocity dotaz na kanálu
 * {@link VelocityForwarding#CHANNEL}; tento listener na něj odpoví podepsaným
 * payloadem s identitou bota. Na jiné kanály nereaguje (a když bot na Velocity
 * nemíří, dotaz nikdy nepřijde – listener je neškodný no-op).</p>
 *
 * <p>Vestavěný {@code ClientListener} MCProtocolLib na tento dotaz sám
 * neodpovídá, takže nehrozí dvojitá odpověď.</p>
 */
public final class VelocityForwardingListener extends SessionAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(VelocityForwardingListener.class);

    /** Adresa hlášená backendu jako adresa hráče (lokální bot = loopback). */
    private static final String LOCAL_ADDRESS = "127.0.0.1";

    private final String botName;
    private final UUID botId;
    private final byte[] secret;

    /**
     * @param botName jméno bota
     * @param botId   offline-mode UUID bota
     * @param secret  sdílený tajný klíč forwardingu (kopie se uloží)
     */
    public VelocityForwardingListener(String botName, UUID botId, byte[] secret) {
        this.botName = botName;
        this.botId = botId;
        this.secret = secret.clone();
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (!(packet instanceof ClientboundCustomQueryPacket query)
                || !VelocityForwarding.CHANNEL.equals(query.getChannel().asString())) {
            return;
        }
        try {
            byte[] response = VelocityForwarding.buildResponse(secret, LOCAL_ADDRESS, botId, botName);
            session.send(new ServerboundCustomQueryAnswerPacket(query.getMessageId(), response));
            LOG.debug("[{}] Odpovězeno na Velocity player_info (dotaz {})",
                    botName, query.getMessageId());
        } catch (RuntimeException e) {
            LOG.warn("[{}] Velocity forwarding selhal: {} – backend připojení nejspíš odmítne.",
                    botName, e.toString());
        }
    }
}
